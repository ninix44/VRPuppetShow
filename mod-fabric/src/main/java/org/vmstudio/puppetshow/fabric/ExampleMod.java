package org.vmstudio.puppetshow.fabric;

import org.vmstudio.puppetshow.core.client.PuppetLogic;
import org.vmstudio.puppetshow.core.common.PuppetNetworking;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.puppetshow.core.client.ExampleAddonClient;
import org.vmstudio.puppetshow.core.server.ExampleAddonServer;
import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PuppetLogic.bridge = (entity, x, y, z, vx, vy, vz, isHeld) -> {
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
        };

        ServerPlayNetworking.registerGlobalReceiver(PuppetNetworking.SYNC_ENTITY_POS, (server, player, handler, buf, responseSender) -> {
            int id = buf.readInt();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            double vx = buf.readDouble(), vy = buf.readDouble(), vz = buf.readDouble();
            boolean isHeld = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(id);
                if (entity != null && entity.distanceToSqr(player) < 512) {
                    entity.noPhysics = isHeld;
                    entity.teleportTo(x, y, z);
                    if (!isHeld) {
                        entity.setDeltaMovement(vx, vy, vz);
                        entity.hasImpulse = true;
                        entity.hurtMarked = true;
                    } else {
                        entity.setDeltaMovement(0, 0, 0);
                    }
                    entity.fallDistance = 0;
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
