package com.critaim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class CritAimClient implements ClientModInitializer {

    // ===== Настройки =====
    /** Дистанция поиска цели для наводки (блоки). */
    static final double AIM_RANGE = 6.0;
    /** Дистанция, с которой начинаем прыгать для крита (≈ досягаемость удара). */
    static final double ATTACK_RANGE = 3.0;
    /** Угол обзора, в котором ищем цель (градусы, от центра экрана). */
    static final float AIM_FOV = 90f;
    /** Плавность наводки: доля оставшегося угла за тик (0..1). */
    static final float AIM_SMOOTH = 0.35f;
    /** Максимальный поворот за тик (градусы). */
    static final float AIM_MAX_STEP = 25f;
    /** Минимальный заряд удара перед атакой (1.0 = полный кулдаун). */
    static final float MIN_COOLDOWN = 0.92f;

    // ===== Состояние =====
    static boolean aimEnabled = false;
    static boolean critsEnabled = false;

    private static KeyBinding aimKey;
    private static KeyBinding critKey;

    @Override
    public void onInitializeClient() {
        aimKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.critaim.aim", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.critaim"));
        critKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.critaim.crits", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.critaim"));

        ClientTickEvents.END_CLIENT_TICK.register(CritAimClient::onTick);

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            ctx.drawTextWithShadow(mc.textRenderer,
                    "Aim: " + (aimEnabled ? "§aON" : "§cOFF"), 4, 4, 0xFFFFFF);
            ctx.drawTextWithShadow(mc.textRenderer,
                    "Crits: " + (critsEnabled ? "§aON" : "§cOFF"), 4, 14, 0xFFFFFF);
        });
    }

    private static void onTick(MinecraftClient mc) {
        while (aimKey.wasPressed()) aimEnabled = !aimEnabled;
        while (critKey.wasPressed()) critsEnabled = !critsEnabled;

        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.currentScreen != null) return;

        PlayerEntity target = findTarget(mc, player);
        if (target == null) return;

        if (aimEnabled) aimAt(player, target);
        if (critsEnabled) doCrit(mc, player, target);
    }

    /** Ближайший видимый живой игрок в радиусе и в FOV. */
    private static PlayerEntity findTarget(MinecraftClient mc, ClientPlayerEntity self) {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == self || !p.isAlive() || p.isSpectator() || p.isInvisible()) continue;
            double dist = self.distanceTo(p);
            if (dist > AIM_RANGE || !self.canSee(p)) continue;

            float[] rot = rotationsTo(self, aimPoint(p));
            float yawDiff = Math.abs(MathHelper.wrapDegrees(rot[0] - self.getYaw()));
            if (yawDiff > AIM_FOV / 2f) continue;

            // приоритет: ближе к прицелу + ближе по дистанции
            double score = yawDiff + dist * 5.0;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Точка прицеливания — чуть ниже глаз цели (в грудь). */
    private static Vec3d aimPoint(Entity e) {
        return e.getPos().add(0, e.getStandingEyeHeight() * 0.85, 0);
    }

    private static float[] rotationsTo(ClientPlayerEntity self, Vec3d point) {
        Vec3d diff = point.subtract(self.getEyePos());
        double horiz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(diff.y, horiz));
        return new float[]{yaw, MathHelper.clamp(pitch, -90f, 90f)};
    }

    /** Плавный поворот камеры к цели. */
    private static void aimAt(ClientPlayerEntity self, Entity target) {
        float[] rot = rotationsTo(self, aimPoint(target));

        float dYaw = MathHelper.wrapDegrees(rot[0] - self.getYaw());
        float dPitch = rot[1] - self.getPitch();

        dYaw = MathHelper.clamp(dYaw * AIM_SMOOTH, -AIM_MAX_STEP, AIM_MAX_STEP);
        dPitch = MathHelper.clamp(dPitch * AIM_SMOOTH, -AIM_MAX_STEP, AIM_MAX_STEP);

        self.setYaw(self.getYaw() + dYaw);
        self.setPitch(MathHelper.clamp(self.getPitch() + dPitch, -90f, 90f));
    }

    /** Прыжок → удар на падении, когда выполняются условия ванильного крита. */
    private static void doCrit(MinecraftClient mc, ClientPlayerEntity self, PlayerEntity target) {
        if (self.isTouchingWater() || self.isInLava() || self.isClimbing()
                || self.hasVehicle() || self.hasStatusEffect(StatusEffects.BLINDNESS)
                || self.getAbilities().flying) return;

        double dist = self.distanceTo(target);

        // 1) на земле, цель рядом, удар почти заряжен → прыгаем
        if (self.isOnGround()) {
            if (dist <= ATTACK_RANGE + 0.5 && self.getAttackCooldownProgress(0.5f) >= 0.7f) {
                self.jump();
            }
            return;
        }

        // 2) в воздухе: ждём падения и полного заряда
        boolean falling = self.fallDistance > 0.0f && self.getVelocity().y < 0;
        if (!falling || self.getAttackCooldownProgress(0.5f) < MIN_COOLDOWN) return;

        // бьём только если прицел реально на цели
        if (!(mc.crosshairTarget instanceof EntityHitResult hit) || hit.getEntity() != target) return;

        // крит не проходит при спринте — сбрасываем его до удара
        if (self.isSprinting()) {
            self.setSprinting(false);
            mc.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(self, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }

        mc.interactionManager.attackEntity(self, target);
        self.swingHand(Hand.MAIN_HAND);
    }
}
