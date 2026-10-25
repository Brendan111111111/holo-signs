package com.brendan.holograms.network;

import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.client.ProjectorScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public final class EditDisplayPayload {
    private EditDisplayPayload() {}

    private static final StreamCodec<ByteBuf, List<String>> LINES_CODEC =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(ProjectorBlockEntity.MAX_LINES));

    public record Open(BlockPos pos, List<String> lines) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HoloSigns.MOD_ID, "open_editor"));
        public static final StreamCodec<ByteBuf, Open> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Open::pos, LINES_CODEC, Open::lines, Open::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Save(BlockPos pos, List<String> lines) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HoloSigns.MOD_ID, "save_display"));
        public static final StreamCodec<ByteBuf, Save> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Save::pos, LINES_CODEC, Save::lines, Save::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Open.TYPE, Open.CODEC);
        registrar.playToServer(Save.TYPE, Save.CODEC, EditDisplayPayload::handleSave);
    }

    @EventBusSubscriber(modid = HoloSigns.MOD_ID, value = Dist.CLIENT)
    public static final class ClientHandlers {
        private ClientHandlers() {}
        @net.neoforged.bus.api.SubscribeEvent
        public static void register(RegisterClientPayloadHandlersEvent event) { event.register(Open.TYPE, ClientHandlers::handleOpen); }
        private static void handleOpen(Open payload, IPayloadContext context) { ProjectorScreen.open(payload.pos(), payload.lines()); }
    }

    private static void handleSave(Save payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (payload.lines().size() > ProjectorBlockEntity.MAX_LINES) return;
        if (payload.lines().stream().anyMatch(line -> line == null || line.codePointCount(0, line.length()) > ProjectorBlockEntity.MAX_LINE_LENGTH)) return;
        var level = player.level();
        BlockPos pos = payload.pos();
        if (!level.hasChunkAt(pos) || !player.mayInteract(level, pos)) return;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ProjectorBlockEntity projector) projector.setLines(new ArrayList<>(payload.lines()));
    }

    public static void sendSave(BlockPos pos, List<String> lines) { ClientPacketDistributor.sendToServer(new Save(pos, lines)); }
    public static void sendOpen(ServerPlayer player, BlockPos pos, List<String> lines) { PacketDistributor.sendToPlayer(player, new Open(pos, lines)); }
}
