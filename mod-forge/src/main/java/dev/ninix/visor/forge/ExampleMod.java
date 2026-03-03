package dev.ninix.visor.forge;

import dev.ninix.visor.puppetshow.core.client.ExampleAddonClient;
import dev.ninix.visor.puppetshow.core.client.PuppetLogic;
import dev.ninix.visor.puppetshow.core.common.PuppetNetworking;
import dev.ninix.visor.puppetshow.core.common.VisorExample;
import dev.ninix.visor.puppetshow.core.server.ExampleAddonServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;

import java.util.function.Supplier;

@Mod(VisorExample.MOD_ID)
public class ExampleMod {

    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        PuppetNetworking.SYNC_ENTITY_POS,
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    public ExampleMod() {
        int id = 0;
        CHANNEL.registerMessage(id++, PuppetSyncPacket.class,
            PuppetSyncPacket::encode,
            PuppetSyncPacket::decode,
            PuppetSyncPacket::handle);

        if (!ModLoader.get().isDedicatedServer()) {
            PuppetLogic.bridge = (entity, x, y, z, isHeld) -> {
                CHANNEL.sendToServer(new PuppetSyncPacket(entity.getId(), x, y, z, isHeld));
            };

            MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        }

        if(ModLoader.get().isDedicatedServer()){
            VisorAPI.registerAddon(
                    new ExampleAddonServer()
            );
        }else{
            VisorAPI.registerAddon(
                    new ExampleAddonClient()
            );
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            PuppetLogic.tick();
        }
    }

    public static class PuppetSyncPacket {
        private final int entityId;
        private final double x, y, z;
        private final boolean isHeld;

        public PuppetSyncPacket(int entityId, double x, double y, double z, boolean isHeld) {
            this.entityId = entityId;
            this.x = x; this.y = y; this.z = z;
            this.isHeld = isHeld;
        }

        public static void encode(PuppetSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.entityId);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeBoolean(msg.isHeld);
        }

        public static PuppetSyncPacket decode(FriendlyByteBuf buf) {
            return new PuppetSyncPacket(buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean());
        }

        public static void handle(PuppetSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                Entity entity = ctx.get().getSender().level().getEntity(msg.entityId);
                if (entity != null && entity.distanceToSqr(ctx.get().getSender()) < 256) {
                    entity.noPhysics = msg.isHeld;
                    entity.teleportTo(msg.x, msg.y, msg.z);
                    entity.setDeltaMovement(0, 0, 0);
                    entity.fallDistance = 0;
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
