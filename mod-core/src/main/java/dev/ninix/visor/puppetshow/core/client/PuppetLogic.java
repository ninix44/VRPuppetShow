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
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PuppetLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean isHeld);
    }
    public static NetworkBridge bridge;

    private static Entity pickedEntity = null;
    private static int captureTicks = 0;
    private static int releaseCooldownTicks = 0;
    private static boolean hapticTriggered = false;
    private static int syncTimer = 0;

    private static Vec3 lastRelativeCenterPos = Vec3.ZERO;

    private static final double RELEASE_DISTANCE = 0.6;
    private static final double THROW_THRESHOLD = 0.15;
    private static final double STRENGTH_MODIFIER = 3.0;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        VRLocalPlayer vrPlayer = VisorAPI.client().getVRLocalPlayer();
        if (vrPlayer == null) return;

        PlayerPoseClient poseTick = vrPlayer.getPoseData(PlayerPoseType.TICK);
        PlayerPoseClient poseRel = vrPlayer.getPoseData(PlayerPoseType.RELATIVE);

        Vec3 lpWorld = jomlToVec3(poseTick.getOffhand().getPosition());
        Vec3 rpWorld = jomlToVec3(poseTick.getMainHand().getPosition());
        Vec3 lpRel = jomlToVec3(poseRel.getOffhand().getPosition());
        Vec3 rpRel = jomlToVec3(poseRel.getMainHand().getPosition());

        if (pickedEntity == null) {
            if (releaseCooldownTicks <= 0) handleDetection(mc, lpWorld, rpWorld, lpRel, rpRel);
        } else {
            handleCarrying(mc, lpWorld, rpWorld, lpRel, rpRel);
        }

        if (releaseCooldownTicks > 0) releaseCooldownTicks--;
    }

    private static Vec3 jomlToVec3(Vector3fc vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    //todo rotate mobs around the axis (360 degrees)
    private static void handleDetection(Minecraft mc, Vec3 lpW, Vec3 rpW, Vec3 lpR, Vec3 rpR) {
        AABB searchBox = mc.player.getBoundingBox().inflate(3.0);
        List<LivingEntity> entities = mc.level.getEntitiesOfClass(LivingEntity.class, searchBox);

        LivingEntity target = null;
        for (LivingEntity entity : entities) {
            if (entity instanceof Enemy) continue;

            if (isValidPuppet(entity)) {
                AABB hb = entity.getBoundingBox().inflate(0.15);
                if (hb.contains(lpW) && hb.contains(rpW)) {
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
                lastRelativeCenterPos = lpR.add(rpR).scale(0.5);

                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 200f, 1.0f, 0.2f);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 200f, 1.0f, 0.2f);
            }
        } else {
            captureTicks = 0;
            hapticTriggered = false;
        }
    }

    private static void handleCarrying(Minecraft mc, Vec3 lpW, Vec3 rpW, Vec3 lpR, Vec3 rpR) {
        if (pickedEntity == null || !pickedEntity.isAlive()) {
            releaseEntity(Vec3.ZERO);
            return;
        }

        Vec3 centerWorld = lpW.add(rpW).scale(0.5);
        Vec3 centerRel = lpR.add(rpR).scale(0.5);

        Vec3 relativeVelocity = centerRel.subtract(lastRelativeCenterPos);
        lastRelativeCenterPos = centerRel;

        if (relativeVelocity.length() > THROW_THRESHOLD) {
            Vec3 throwVec = relativeVelocity.scale(STRENGTH_MODIFIER).add(0, 0.2, 0);
            releaseEntity(throwVec);
            return;
        }

        if (lpW.distanceTo(rpW) > RELEASE_DISTANCE) {
            releaseEntity(Vec3.ZERO);
            return;
        }

        double yPos = centerWorld.y - (pickedEntity.getBbHeight() / 2.0);

        pickedEntity.setPos(centerWorld.x, yPos, centerWorld.z);
        pickedEntity.setDeltaMovement(Vec3.ZERO);
        pickedEntity.noPhysics = true;

        syncTimer++;
        if (syncTimer >= 2) {
            if (bridge != null) bridge.sendSync(pickedEntity, centerWorld.x, yPos, centerWorld.z, 0, 0, 0, true);
            syncTimer = 0;
        }
    }

    private static void releaseEntity(Vec3 throwVector) {
        if (pickedEntity != null) {
            pickedEntity.noPhysics = false;
            if (bridge != null) {
                bridge.sendSync(pickedEntity, pickedEntity.getX(), pickedEntity.getY(), pickedEntity.getZ(),
                    throwVector.x, throwVector.y, throwVector.z, false);
            }
        }
        pickedEntity = null;
        captureTicks = 0;
        releaseCooldownTicks = 30;
        hapticTriggered = false;
    }

    private static boolean isValidPuppet(LivingEntity entity) {
        if (entity instanceof AgeableMob ageable && ageable.isBaby()) return true;

        return entity instanceof Chicken ||
            entity instanceof Cat ||
            entity instanceof Axolotl ||
            entity instanceof Rabbit ||
            entity instanceof Parrot ||
            entity instanceof Bat;
    }
}
