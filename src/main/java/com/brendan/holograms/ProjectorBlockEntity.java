package com.brendan.holograms;

import com.brendan.holograms.network.EditDisplayPayload;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;

public class ProjectorBlockEntity extends BlockEntity {
    public static final int MAX_LINES = 8;
    public static final int MAX_LINE_LENGTH = 64;
    private static final Codec<List<String>> LINES_CODEC = Codec.STRING.listOf().comapFlatMap(
            lines -> lines.size() <= MAX_LINES ? com.mojang.serialization.DataResult.success(lines)
                    : com.mojang.serialization.DataResult.error(() -> "Too many projector lines"),
            lines -> lines
    );

    private List<String> lines = new ArrayList<>(List.of("HOLOGRAM", "Edit me"));

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(HoloSigns.PROJECTOR_ENTITY.get(), pos, state);
    }

    protected ProjectorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public List<String> getLines() { return List.copyOf(lines); }
    public String getLine(int index) { return index < 0 || index >= lines.size() ? "" : lines.get(index); }

    public boolean setLine(int index, String value) {
        if (index < 0 || index >= MAX_LINES || value == null) return false;
        while (lines.size() <= index) lines.add("");
        lines.set(index, sanitize(value));
        trimTrailingEmptyLines();
        markUpdated();
        return true;
    }

    public void setLines(List<String> incoming) {
        List<String> normalized = new ArrayList<>(MAX_LINES);
        for (int i = 0; i < Math.min(MAX_LINES, incoming.size()); i++) {
            String line = incoming.get(i);
            if (line == null) return;
            normalized.add(sanitize(line));
        }
        lines = normalized;
        trimTrailingEmptyLines();
        markUpdated();
    }

    public void clearLines() {
        lines = new ArrayList<>();
        markUpdated();
    }

    public void openEditor(ServerPlayer player) { EditDisplayPayload.sendOpen(player, worldPosition, lines); }

    private static String sanitize(String value) {
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_LINE_LENGTH));
        value.codePoints().filter(cp -> !Character.isISOControl(cp)).limit(MAX_LINE_LENGTH).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    private void trimTrailingEmptyLines() {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
    }

    private void markUpdated() {
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        lines = new ArrayList<>();
        for (String line : input.listOrEmpty("lines", Codec.STRING)) {
            if (lines.size() == MAX_LINES) break;
            lines.add(sanitize(line));
        }
        trimTrailingEmptyLines();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("lines", Codec.STRING.listOf(), lines);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
