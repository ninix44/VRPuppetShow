package dev.ninix.visor.puppetshow.fabric;

import dev.ninix.visor.puppetshow.core.client.PuppetLogic;
import dev.ninix.visor.puppetshow.core.common.PuppetNetworking;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import dev.ninix.visor.puppetshow.core.client.ExampleAddonClient;
import dev.ninix.visor.puppetshow.core.server.ExampleAddonServer;
import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PuppetLogic.bridge = (entity, x, y, z, isHeld) -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeInt(entity.getId());
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeBoolean(isHeld);
            ClientPlayNetworking.send(PuppetNetworking.SYNC_ENTITY_POS, buf);
        };

        ServerPlayNetworking.registerGlobalReceiver(PuppetNetworking.SYNC_ENTITY_POS, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            boolean isHeld = buf.readBoolean();

            server.execute(() -> {
                Entity entity = player.level().getEntity(entityId);
                if (entity != null && entity.distanceToSqr(player) < 256) {
                    entity.noPhysics = isHeld;
                    entity.teleportTo(x, y, z);
                    entity.setDeltaMovement(0, 0, 0);
                    entity.fallDistance = 0;

                    if (!isHeld) {
                        entity.setPos(x, y, z);
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
