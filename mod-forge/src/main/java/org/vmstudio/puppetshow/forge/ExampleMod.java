package org.vmstudio.puppetshow.forge;

import net.minecraft.world.entity.LivingEntity;
import org.vmstudio.puppetshow.core.client.ExampleAddonClient;
import org.vmstudio.puppetshow.core.client.PuppetLogic;
import org.vmstudio.puppetshow.core.common.PuppetNetworking;
import org.vmstudio.puppetshow.core.common.VisorExample;
import org.vmstudio.puppetshow.core.server.ExampleAddonServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
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

    private static final String PROTOCOL_VERSION = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        PuppetNetworking.SYNC_ENTITY_POS,
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    public ExampleMod() {
        CHANNEL.registerMessage(0, PuppetSyncPacket.class,
            PuppetSyncPacket::encode,
            PuppetSyncPacket::decode,
            PuppetSyncPacket::handle);
        CHANNEL.registerMessage(1, PuppetActionPacket.class, PuppetActionPacket::encode, PuppetActionPacket::decode, PuppetActionPacket::handle);

        if (!ModLoader.get().isDedicatedServer()) {
            PuppetLogic.bridge = new PuppetLogic.NetworkBridge() {
                @Override
                public void sendSync(Entity e, double x, double y, double z, double vx, double vy, double vz, boolean h) {
                    CHANNEL.sendToServer(new PuppetSyncPacket(e.getId(), x, y, z, vx, vy, vz, h));
                }
                @Override
                public void sendAction(Entity e, String action) {
                    CHANNEL.sendToServer(new PuppetActionPacket(e.getId(), action));
                }
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
        private final int id;
        private final double x, y, z, vx, vy, vz;
        private final boolean held;

        public PuppetSyncPacket(int id, double x, double y, double z, double vx, double vy, double vz, boolean held) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.held = held;
        }

        public static void encode(PuppetSyncPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.id);
            buf.writeDouble(msg.x);
            buf.writeDouble(msg.y);
            buf.writeDouble(msg.z);
            buf.writeDouble(msg.vx);
            buf.writeDouble(msg.vy);
            buf.writeDouble(msg.vz);
            buf.writeBoolean(msg.held);
        }

        public static PuppetSyncPacket decode(FriendlyByteBuf buf) {
            return new PuppetSyncPacket(buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean());
        }

        public static void handle(PuppetSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                Entity entity = ctx.get().getSender().level().getEntity(msg.id);
                if (entity != null) {
                    entity.noPhysics = msg.held;
                    entity.teleportTo(msg.x, msg.y, msg.z);
                    if (!msg.held) { entity.setDeltaMovement(msg.vx, msg.vy, msg.vz); entity.hasImpulse = true; }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static class PuppetActionPacket {
        private final int id;
        private final String action;

        public PuppetActionPacket(int id, String action) {
            this.id = id;
            this.action = action;
        }

        public static void encode(PuppetActionPacket msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.id);
            buf.writeUtf(msg.action);
        }

        public static PuppetActionPacket decode(FriendlyByteBuf buf) {
            return new PuppetActionPacket(buf.readInt(), buf.readUtf()); }

        public static void handle(PuppetActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                Entity entity = player.level().getEntity(msg.id);
                if (entity == null) return;
                switch (msg.action) {
                    case "EAT" -> { entity.discard(); player.getFoodData().eat(2, 0.2f); }
                    case "SCRATCH" -> { player.hurt(player.damageSources().mobAttack((LivingEntity)entity), 2.0f); }
                    case "EGG" -> {
                        if (player.getRandom().nextFloat() < 0.80f) {
                            player.level().addFreshEntity(new ItemEntity(player.level(), entity.getX(), entity.getY(), entity.getZ(), new ItemStack(Items.EGG)));
                        }
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
