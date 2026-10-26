package com.brendan.holograms.network;

import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * One key, mouse or clipboard event from the terminal screen that opens when a player clicks the
 * floating hologram panel.
 * <p>
 * The screen is drawn entirely on the client from the synced {@link ProjectorBlockEntity}, so only
 * input has to travel to the server. The server validates the projector, the player's reach and the
 * action before handing the event to the computer integration; no input is trusted.
 */
public record TerminalInputPayload(BlockPos pos, int action, char character, int code,
                                   double x, double y, int button, String text) implements CustomPacketPayload {
    /** Longer pastes are truncated instead of rejected, matching a normal terminal's behaviour. */
    public static final int MAX_CLIPBOARD_LENGTH = 1024;

    public static final Type<TerminalInputPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(HoloSigns.MOD_ID, "terminal_input"));

    private static final StreamCodec<ByteBuf, String> TEXT_CODEC =
            ByteBufCodecs.stringUtf8(MAX_CLIPBOARD_LENGTH);

    public static final StreamCodec<ByteBuf, TerminalInputPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                buf.writeByte(payload.action());
                buf.writeChar(payload.character());
                buf.writeInt(payload.code());
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeInt(payload.button());
                TEXT_CODEC.encode(buf, payload.text() == null ? "" : payload.text());
            },
            buf -> new TerminalInputPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    buf.readUnsignedByte(),
                    buf.readChar(),
                    buf.readInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readInt(),
                    TEXT_CODEC.decode(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Same network version as the editor payloads, so mixed mod versions are rejected cleanly.
        event.registrar("2").playToServer(TYPE, CODEC, TerminalInputPayload::handle);
    }

    private static void handle(TerminalInputPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos pos = payload.pos();
        if (!level.hasChunkAt(pos) || !player.mayInteract(level, pos)) return;
        // Same range as the Screen Linker: the player may be looking at a panel far from the block.
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0 * 64.0) return;
        ProjectorBlockEntity.TerminalAction action = ProjectorBlockEntity.TerminalAction.byId(payload.action());
        if (action == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ProjectorBlockEntity projector) {
            projector.handleTerminalInput(player, action, payload.character(), payload.code(),
                    payload.x(), payload.y(), payload.button(), payload.text());
        }
    }

    public static void send(ProjectorBlockEntity.TerminalAction action, BlockPos pos, char character, int code,
                            double x, double y, int button, String text) {
        ClientPacketDistributor.sendToServer(
                new TerminalInputPayload(pos, action.ordinal(), character, code, x, y, button, text));
    }
}
