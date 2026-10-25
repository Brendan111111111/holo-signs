package com.brendan.holograms;

import com.brendan.holograms.network.EditDisplayPayload;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    public static final int MIN_DISTANCE = 1;
    public static final int MAX_DISTANCE = 16;
    public static final int DEFAULT_DISTANCE = 2;

    private List<String> lines = new ArrayList<>(List.of("HOLOGRAM", "Edit me"));
    private int distance = DEFAULT_DISTANCE;
    private DisplayMode mode = DisplayMode.LINES;
    private final TerminalBuffer terminal = new TerminalBuffer();
    private boolean updatingFromDisk;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(HoloSigns.PROJECTOR_ENTITY.get(), pos, state);
        this.terminal.setOnChanged(this::onTerminalChanged);
    }

    protected ProjectorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.terminal.setOnChanged(this::onTerminalChanged);
    }

    public List<String> getLines() { return List.copyOf(lines); }
    public String getLine(int index) { return index < 0 || index >= lines.size() ? "" : lines.get(index); }

    public boolean setLine(int index, String value) {
        if (index < 0 || index >= MAX_LINES || value == null) return false;
        while (lines.size() <= index) lines.add("");
        lines.set(index, sanitize(value));
        trimTrailingEmptyLines();
        mode = DisplayMode.LINES;
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
        mode = DisplayMode.LINES;
        markUpdated();
    }

    public void clearLines() {
        lines = new ArrayList<>();
        mode = DisplayMode.LINES;
        markUpdated();
    }

    public Direction getFacing() {
        BlockState state = getBlockState();
        return state.hasProperty(ProjectorBlock.FACING) ? state.getValue(ProjectorBlock.FACING) : Direction.UP;
    }

    /**
     * Points the projection in the given direction. The block state carries the direction, so the
     * model and the renderer stay in sync for every player.
     */
    public boolean setFacing(Direction direction) {
        if (direction == null || level == null || level.isClientSide()) return false;
        BlockState state = getBlockState();
        if (!state.hasProperty(ProjectorBlock.FACING) || state.getValue(ProjectorBlock.FACING) == direction) return false;
        level.setBlock(worldPosition, state.setValue(ProjectorBlock.FACING, direction), Block.UPDATE_ALL);
        markUpdated();
        return true;
    }

    public int getDistance() { return distance; }

    public void setDistance(int value) {
        int clamped = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, value));
        if (clamped == distance) return;
        distance = clamped;
        markUpdated();
    }

    public DisplayMode getMode() { return mode; }

    public void setMode(DisplayMode newMode) {
        if (newMode == null || newMode == mode) return;
        mode = newMode;
        markUpdated();
    }

    public TerminalBuffer getTerminal() { return terminal; }

    public void openEditor(ServerPlayer player) {
        EditDisplayPayload.sendOpen(player, worldPosition, lines, getFacing(), distance);
    }

    private void onTerminalChanged() {
        if (updatingFromDisk) return;
        mode = DisplayMode.MONITOR;
        markUpdated();
    }

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
        updatingFromDisk = true;
        try {
            lines = new ArrayList<>();
            for (String line : input.listOrEmpty("lines", Codec.STRING)) {
                if (lines.size() == MAX_LINES) break;
                lines.add(sanitize(line));
            }
            trimTrailingEmptyLines();

            distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, input.getIntOr("distance", DEFAULT_DISTANCE)));
            mode = DisplayMode.byName(input.getStringOr("mode", DisplayMode.LINES.name()));

            ValueInput terminalInput = input.childOrEmpty("terminal");
            List<String> terminalRows = new ArrayList<>();
            for (String row : terminalInput.listOrEmpty("rows", Codec.STRING)) terminalRows.add(row);
            int[] foreground = terminalInput.getIntArray("fg").orElse(null);
            int[] background = terminalInput.getIntArray("bg").orElse(null);
            terminal.restore(
                    terminalInput.getIntOr("columns", TerminalBuffer.DEFAULT_COLUMNS),
                    terminalInput.getIntOr("height", TerminalBuffer.DEFAULT_ROWS),
                    terminalRows,
                    foreground,
                    background,
                    terminalInput.getIntOr("cursorColumn", 0),
                    terminalInput.getIntOr("cursorRow", 0),
                    terminalInput.getIntOr("foreground", TerminalBuffer.DEFAULT_FOREGROUND),
                    terminalInput.getIntOr("background", TerminalBuffer.DEFAULT_BACKGROUND)
            );
        } finally {
            updatingFromDisk = false;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("lines", Codec.STRING.listOf(), lines);
        output.putInt("distance", distance);
        output.putString("mode", mode.name());

        ValueOutput terminalOutput = output.child("terminal");
        terminalOutput.putInt("columns", terminal.getColumns());
        terminalOutput.putInt("height", terminal.getRows());
        terminalOutput.putInt("cursorColumn", terminal.getCursorColumn());
        terminalOutput.putInt("cursorRow", terminal.getCursorRow());
        terminalOutput.putInt("foreground", terminal.getActiveForeground());
        terminalOutput.putInt("background", terminal.getActiveBackground());
        terminalOutput.store("rows", Codec.STRING.listOf(), terminal.textRows());
        terminalOutput.putIntArray("fg", terminal.foregroundData());
        terminalOutput.putIntArray("bg", terminal.backgroundData());
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
