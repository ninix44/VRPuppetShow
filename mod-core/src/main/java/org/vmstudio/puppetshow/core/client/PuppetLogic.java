package org.vmstudio.puppetshow.core.client;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.player.VRLocalPlayer;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseClient;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.common.HandType;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class PuppetLogic {
    public interface NetworkBridge {
        void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean isHeld);
        void sendAction(Entity entity, String actionType);
    }
    public static NetworkBridge bridge;

    private static Entity pickedEntity = null;
    private static int captureTicks = 0;
    private static int releaseCooldownTicks = 0;
    private static boolean hapticTriggered = false;
    private static int syncTimer = 0;

    private static int shakeTicks = 0;
    private static int chickenShakeTicks = 0;
    private static int interactionCooldown = 0;
    //private static Vec3 lastVelocity = Vec3.ZERO;

    private static Vec3 lastRelativeCenterPos = Vec3.ZERO;

    private static final double RELEASE_DISTANCE = 0.6;
    private static final double THROW_THRESHOLD = 0.15;
    private static final double STRENGTH_MODIFIER = 3.0;
    private static final double FACE_DISTANCE = 0.90; // TODO maybe change

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
            shakeTicks = 0;
            chickenShakeTicks = 0;
        } else {
            handleCarrying(mc, lpWorld, rpWorld, lpRel, rpRel, poseTick);
            handleSpecialMechanics(mc, poseTick);
        }

        if (releaseCooldownTicks > 0) releaseCooldownTicks--;
        if (interactionCooldown > 0) interactionCooldown--;
    }

    private static void handleSpecialMechanics(Minecraft mc, PlayerPoseClient pose) {
        if (pickedEntity == null || interactionCooldown > 0) return;

        Vec3 headPos = jomlToVec3(pose.getHmd().getPosition());
        double distToHead = pickedEntity.position().distanceTo(headPos);

        boolean nearWater = mc.level.getFluidState(pickedEntity.blockPosition()).is(FluidTags.WATER) ||
            mc.level.getFluidState(pickedEntity.blockPosition().above()).is(FluidTags.WATER);

        if (pickedEntity instanceof Frog && distToHead < FACE_DISTANCE) {
            spawnClientParticles(ParticleTypes.HEART, 5);
            interactionCooldown = 30;
        }

        if (pickedEntity instanceof AbstractFish && distToHead < 0.80) { // TODO maybe change
            if (bridge != null) bridge.sendAction(pickedEntity, "EAT");

            mc.player.playSound(SoundEvents.GENERIC_EAT, 1.0f, 1.0f);
            mc.player.playSound(SoundEvents.PLAYER_BURP, 1.0f, 1.0f);
            spawnClientParticles(ParticleTypes.CLOUD, 5);
            VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 300f, 1.0f, 0.2f);
            VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 300f, 1.0f, 0.2f);

            pickedEntity = null;
            shakeTicks = 0;
            chickenShakeTicks = 0;
            return;
        }

        if (pickedEntity instanceof Cat) {
            if (distToHead < FACE_DISTANCE || nearWater) {
                if (bridge != null) bridge.sendAction(pickedEntity, "SCRATCH");

                mc.player.playSound(SoundEvents.CAT_HISS, 1.0f, 1.0f);
                spawnClientParticles(ParticleTypes.ANGRY_VILLAGER, 3);

                interactionCooldown = 40;

                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 400f, 1.0f, 0.1f);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 400f, 1.0f, 0.1f);
            } else {
                if (mc.level.getGameTime() % 60 == 0) {
                    mc.player.playSound(SoundEvents.CAT_PURR, 0.8f, 1.0f);

                    if (mc.level.random.nextFloat() < 0.3f) {
                        spawnClientParticles(ParticleTypes.HEART, 1);
                    }

                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 80f, 0.15f, 0.8f);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 80f, 0.15f, 0.8f);
                }
            }
        }

        Vec3 currentVelocity = pickedEntity.getDeltaMovement();
        double speed = currentVelocity.length();
        boolean isShaking = speed > 0.05;
        //lastVelocity = currentVelocity;

        if (isShaking) {
            shakeTicks++;
        } else {
            if (shakeTicks > 0) shakeTicks--;
        }

        if (pickedEntity instanceof Villager v && v.isBaby()) {
            if (shakeTicks >= 40) {
                spawnClientParticles(ParticleTypes.ANGRY_VILLAGER, 1);
                if (shakeTicks % 10 == 0) {
                    mc.player.playSound(SoundEvents.VILLAGER_NO, 1.0f, 1.5f);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 150f, 0.5f, 0.05f); // TODO maybe change
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 150f, 0.5f, 0.05f); // TODO maybe change
                }
            }
        }

        if (pickedEntity instanceof Chicken) {
            boolean isVerticalShaking = Math.abs(currentVelocity.y) > 0.04;

            if (isVerticalShaking) {
                chickenShakeTicks++;

                if (chickenShakeTicks % 20 == 0) {
                    mc.player.playSound(SoundEvents.CHICKEN_AMBIENT, 1.0f, 1.2f);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 100f, 0.5f, 0.1f);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 100f, 0.5f, 0.1f);
                }

                if (chickenShakeTicks >= 100) {
                    if (bridge != null) bridge.sendAction(pickedEntity, "EGG");

                    mc.player.playSound(SoundEvents.CHICKEN_EGG, 1.0f, 1.0f);
                    spawnClientParticles(ParticleTypes.HEART, 3);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 300f, 1.0f, 0.3f);
                    VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 300f, 1.0f, 0.3f);

                    chickenShakeTicks = 0;
                    shakeTicks = 0;
                    interactionCooldown = 20;
                }
            } else {
                if (chickenShakeTicks > 0) chickenShakeTicks--;
            }
        }
    }

    private static void spawnClientParticles(ParticleOptions type, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (pickedEntity == null) return;
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(type,
                pickedEntity.getX() + (mc.level.random.nextDouble() - 0.5) * 0.3,
                pickedEntity.getY() + pickedEntity.getBbHeight() + 0.2,
                pickedEntity.getZ() + (mc.level.random.nextDouble() - 0.5) * 0.3,
                0, 0.05, 0);
        }
    }

    private static Vec3 jomlToVec3(Vector3fc vec) {
        return new Vec3(vec.x(), vec.y(), vec.z());
    }

    private static void handleDetection(Minecraft mc, Vec3 lpW, Vec3 rpW, Vec3 lpR, Vec3 rpR) {
        AABB searchBox = mc.player.getBoundingBox().inflate(3.0);
        List<LivingEntity> entities = mc.level.getEntitiesOfClass(LivingEntity.class, searchBox);

        LivingEntity target = null;
        for (LivingEntity entity : entities) {
            if (entity instanceof Enemy) continue;

            if (isValidPuppet(entity)) {
                double boxSize = 0.8;
                double half = boxSize / 2.0;
                AABB blockHitbox = new AABB(
                    entity.getX() - half, entity.getY(), entity.getZ() - half,
                    entity.getX() + half, entity.getY() + boxSize, entity.getZ() + half
                );
                if (blockHitbox.contains(lpW) && blockHitbox.contains(rpW)) {
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
            if (captureTicks >= 15) {
                pickedEntity = target;
                pickedEntity.noPhysics = true;
                captureTicks = 0;
                shakeTicks = 0;
                chickenShakeTicks = 0;
                lastRelativeCenterPos = lpR.add(rpR).scale(0.5);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.MAIN, 200f, 1.0f, 0.2f);
                VisorAPI.client().getInputManager().triggerHapticPulse(HandType.OFFHAND, 200f, 1.0f, 0.2f);
            }
        } else {
            captureTicks = 0;
            hapticTriggered = false;
        }
    }

    private static void handleCarrying(Minecraft mc, Vec3 lpW, Vec3 rpW, Vec3 lpR, Vec3 rpR, PlayerPoseClient poseTick) {
        if (pickedEntity == null || !pickedEntity.isAlive()) {
            releaseEntity(Vec3.ZERO);
            return;
        }

        Vec3 centerWorld = lpW.add(rpW).scale(0.5);
        Vec3 centerRel = lpR.add(rpR).scale(0.5);
        Vec3 deltaRel = centerRel.subtract(lastRelativeCenterPos);
        lastRelativeCenterPos = centerRel;

        Vector3f jomlDelta = new Vector3f((float)deltaRel.x, (float)deltaRel.y, (float)deltaRel.z);
        Vector3f worldOrientedDelta = poseTick
            .convertPositionFrom(PlayerPoseType.RELATIVE, jomlDelta)
            .add(poseTick.getOrigin().mul(-1, new Vector3f()));

        Vec3 finalVelocity = new Vec3(worldOrientedDelta.x(), worldOrientedDelta.y(), worldOrientedDelta.z());

        if (finalVelocity.length() > THROW_THRESHOLD) {
            Vec3 throwVec = finalVelocity.scale(STRENGTH_MODIFIER).add(0, 0.2, 0);
            releaseEntity(throwVec);
            return;
        }

        if (lpW.distanceTo(rpW) > RELEASE_DISTANCE) {
            releaseEntity(Vec3.ZERO);
            return;
        }

        double yPos = centerWorld.y - (pickedEntity.getBbHeight() / 2.0);
        pickedEntity.setPos(centerWorld.x, yPos, centerWorld.z);

        pickedEntity.setDeltaMovement(finalVelocity);
        pickedEntity.noPhysics = true;

        syncTimer++;
        if (syncTimer >= 2) {
            if (bridge != null) bridge.sendSync(pickedEntity, centerWorld.x, yPos, centerWorld.z, finalVelocity.x, finalVelocity.y, finalVelocity.z, true);
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
        shakeTicks = 0;
        chickenShakeTicks = 0;
        releaseCooldownTicks = 30;
        hapticTriggered = false;
    }

    private static boolean isValidPuppet(LivingEntity entity) {
        if (entity instanceof Enemy) return false;
        double w = entity.getBbWidth();
        double h = entity.getBbHeight();
        return w <= 1.0 && h <= 1.0;
    }
}
