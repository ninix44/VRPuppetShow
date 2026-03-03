package dev.ninix.visor.puppetshow.core.client;

import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.player.VRLocalPlayer;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseClient;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.common.HandType;
import org.joml.Vector3fc;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PuppetLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, boolean isHeld);
    }
    public static NetworkBridge bridge;

    private static Entity pickedEntity = null;
    private static int captureTicks = 0;
    private static int releaseCooldownTicks = 0;
    private static boolean hapticTriggered = false;
    private static int syncTimer = 0;

    private static final double RELEASE_DISTANCE = 0.8;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        VRLocalPlayer vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer == null) return;

        PlayerPoseClient pose = vrPlayer.getPoseData(PlayerPoseType.TICK);
        Vector3fc leftPosJoml = pose.getOffhand().getPosition();
        Vector3fc rightPosJoml = pose.getMainHand().getPosition();

        Vec3 lp = new Vec3(leftPosJoml.x(), leftPosJoml.y(), leftPosJoml.z());
        Vec3 rp = new Vec3(rightPosJoml.x(), rightPosJoml.y(), rightPosJoml.z());

        if (pickedEntity == null) {
            if (releaseCooldownTicks <= 0) handleDetection(mc, lp, rp);
        } else {
            handleCarrying(lp, rp);
        }

        if (releaseCooldownTicks > 0) releaseCooldownTicks--;
    }

    //todo rotate mobs around the axis (360 degrees)
    private static void handleDetection(Minecraft mc, Vec3 lp, Vec3 rp) {
        AABB searchBox = mc.player.getBoundingBox().inflate(3.0);
        List<LivingEntity> entities = mc.level.getEntitiesOfClass(LivingEntity.class, searchBox);

        LivingEntity target = null;
        for (LivingEntity entity : entities) {
            if (entity instanceof Enemy) continue;

            if (isValidPuppet(entity)) {
                AABB hb = entity.getBoundingBox().inflate(0.15);
                if (hb.contains(lp) && hb.contains(rp)) {
                    target = entity;
                    break;
                }
            }
        }

        if (target != null) {
            if (!hapticTriggered) {
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 160f, 0.6f, 0.06f);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 160f, 0.6f, 0.06f);
                hapticTriggered = true;
            }
            captureTicks++;
            if (captureTicks >= 20) {
                pickedEntity = target;
                pickedEntity.noPhysics = true;

                captureTicks = 0;
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 200f, 1.0f, 0.2f);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 200f, 1.0f, 0.2f);
            }
        } else {
            captureTicks = 0;
            hapticTriggered = false;
        }
    }

    private static boolean isValidPuppet(LivingEntity entity) {
        if (entity instanceof AgeableMob ageable && ageable.isBaby()) {
            return true;
        }

        return entity instanceof Chicken ||
            entity instanceof Cat ||
            entity instanceof Axolotl ||
            entity instanceof Rabbit ||
            entity instanceof Parrot ||
            entity instanceof Bat;
    }

    private static void handleCarrying(Vec3 lp, Vec3 rp) {
        if (pickedEntity == null || !pickedEntity.isAlive()) {
            releaseEntity();
            return;
        }

        Vec3 center = lp.add(rp).scale(0.5);
        double yPos = center.y - (pickedEntity.getBbHeight() / 2.0);

        pickedEntity.setPos(center.x, yPos, center.z);
        pickedEntity.setDeltaMovement(Vec3.ZERO);
        pickedEntity.noPhysics = true;

        syncTimer++;
        if (syncTimer >= 2) {
            if (bridge != null) bridge.sendSync(pickedEntity, center.x, yPos, center.z, true);
            syncTimer = 0;
        }

        if (lp.distanceTo(rp) > RELEASE_DISTANCE) {
            releaseEntity();
        }
    }

    private static void releaseEntity() {
        if (pickedEntity != null) {
            pickedEntity.noPhysics = false;
            if (bridge != null) {
                bridge.sendSync(pickedEntity, pickedEntity.getX(), pickedEntity.getY(), pickedEntity.getZ(), false);
            }
        }
        pickedEntity = null;
        captureTicks = 0;
        releaseCooldownTicks = 30;
        hapticTriggered = false;
    }
}
