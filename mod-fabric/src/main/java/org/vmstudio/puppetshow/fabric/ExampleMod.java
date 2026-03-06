package org.vmstudio.puppetshow.fabric;

import net.minecraft.world.entity.LivingEntity;
import org.vmstudio.puppetshow.core.client.PuppetLogic;
import org.vmstudio.puppetshow.core.common.PuppetNetworking;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.puppetshow.core.client.ExampleAddonClient;
import org.vmstudio.puppetshow.core.server.ExampleAddonServer;
import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PuppetLogic.bridge = new PuppetLogic.NetworkBridge() {
            @Override
            public void sendSync(Entity entity, double x, double y, double z, double vx, double vy, double vz, boolean isHeld) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeInt(entity.getId());
                buf.writeDouble(x);
                buf.writeDouble(y);
                buf.writeDouble(z);
                buf.writeDouble(vx);
                buf.writeDouble(vy);
                buf.writeDouble(vz);
                buf.writeBoolean(isHeld);
                ClientPlayNetworking.send(PuppetNetworking.SYNC_ENTITY_POS, buf);
            }

            @Override
            public void sendAction(Entity entity, String actionType) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeInt(entity.getId());
                buf.writeUtf(actionType);
                ClientPlayNetworking.send(PuppetNetworking.PUPPET_ACTION, buf);
            }
        };

        ServerPlayNetworking.registerGlobalReceiver(PuppetNetworking.SYNC_ENTITY_POS, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            double vx = buf.readDouble(), vy = buf.readDouble(), vz = buf.readDouble();
            boolean isHeld = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity != null) {
                    entity.noPhysics = isHeld;
                    entity.teleportTo(x, y, z);
                    if (!isHeld) {
                        entity.setDeltaMovement(vx, vy, vz);
                        entity.hasImpulse = true;
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PuppetNetworking.PUPPET_ACTION, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            String action = buf.readUtf();
            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity == null) return;

                switch (action) {
                    case "EAT" -> {
                        entity.discard();
                        player.getFoodData().eat(2, 0.2f);
                    }
                    case "SCRATCH" -> {
                        player.hurt(player.damageSources().mobAttack((LivingEntity)entity), 2.0f);
                    }
                    case "EGG" -> {
                        if (player.getRandom().nextFloat() < 0.80f) {
                            ItemEntity egg = new ItemEntity(player.level(), entity.getX(), entity.getY(), entity.getZ(), new ItemStack(Items.EGG));
                            player.level().addFreshEntity(egg);
                        }
                    }
                }
            });
        });

        if(ModLoader.get().isDedicatedServer()){
            VisorAPI.registerAddon(
                    new ExampleAddonServer()
            );
        }else{
            VisorAPI.registerAddon(
                    new ExampleAddonClient()
            );
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                PuppetLogic.tick();
            });
        }
    }
}
