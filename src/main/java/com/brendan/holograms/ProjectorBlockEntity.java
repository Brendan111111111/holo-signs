package com.brendan.holograms;

import com.brendan.holograms.network.EditDisplayPayload;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ProjectorBlockEntity extends BlockEntity {
    /**
     * Projectors changed during a server tick. Sending one block update per projector at the end of
     * the tick instead of one per write keeps a chatty computer from flooding every nearby client.
     * <p>
     * Writes arrive from OpenComputers' computer threads as well as from the server thread, so the
     * queue must tolerate concurrent modification; the end-of-tick flush drains it safely.
     */
    private static final ProjectorUpdateQueue<ProjectorBlockEntity> PENDING_UPDATES =
            new ProjectorUpdateQueue<>();

    public static final int MAX_LINES = 8;
    public static final int MAX_LINE_LENGTH = 64;
    public static final int MIN_DISTANCE = 1;
    public static final int MAX_DISTANCE = 16;
    public static final int DEFAULT_DISTANCE = 2;
    public static final int MIN_SIZE_PERCENT = 25;
    public static final int MAX_SIZE_PERCENT = 300;
    public static final int DEFAULT_SIZE_PERCENT = 100;
    public static final int SIZE_STEP_PERCENT = 25;
    public static final int DEFAULT_BACKGROUND_COLOR = 0x0A2E2C;

    private List<String> lines = new ArrayList<>(List.of("HOLOGRAM", "Edit me"));
    private int distance = DEFAULT_DISTANCE;
    private int sizePercent = DEFAULT_SIZE_PERCENT;
    private int backgroundColor = DEFAULT_BACKGROUND_COLOR;
    private @Nullable BlockState camouflage;
    private DisplayMode mode = DisplayMode.LINES;
    private final TerminalBuffer terminal = new TerminalBuffer();
    private boolean updatingFromDisk;
    private int textVersion;

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
        trimTrailingEmptyLines(lines);
        mode = DisplayMode.LINES;
        textVersion++;
        markUpdated();
        return true;
    }

    public void setLines(List<String> incoming) {
        if (incoming == null) return;
        List<String> normalized = new ArrayList<>(MAX_LINES);
        for (int i = 0; i < Math.min(MAX_LINES, incoming.size()); i++) {
            String line = incoming.get(i);
            if (line == null) return;
            normalized.add(sanitize(line));
        }
        trimTrailingEmptyLines(normalized);
        // The editor sends every field on save, including text the player never touched. Switching
        // to the authored lines unconditionally therefore ended a linked computer's monitor view
        // whenever any other setting (size, distance, colour, facing) was changed, which looked
        // like the link had died until the computer wrote again. Only an actual text edit takes the
        // hologram back from the computer.
        if (normalized.equals(lines)) return;
        lines = normalized;
        mode = DisplayMode.LINES;
        textVersion++;
        markUpdated();
    }

    public void clearLines() {
        lines = new ArrayList<>();
        mode = DisplayMode.LINES;
        textVersion++;
        markUpdated();
    }

    /** Increments whenever the authored lines change, for render-state caching. */
    public int getTextVersion() {
        return textVersion;
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

    public int getSizePercent() { return sizePercent; }

    public void setSizePercent(int value) {
        int clamped = Math.max(MIN_SIZE_PERCENT, Math.min(MAX_SIZE_PERCENT, value));
        if (clamped == sizePercent) return;
        sizePercent = clamped;
        markUpdated();
    }

    public int getBackgroundColor() { return backgroundColor; }

    public void setBackgroundColor(int value) {
        int rgb = value & 0xFFFFFF;
        if (rgb == backgroundColor) return;
        backgroundColor = rgb;
        markUpdated();
    }

    public @Nullable BlockState getCamouflage() { return camouflage; }

    /**
     * Changes the block the projector pretends to be. The camouflage is visual only: the projector
     * keeps its own block, collision shape, screen data and computer peripherals. The block state
     * flag lets the chunk renderer skip the projector model while the block entity renders the
     * replacement model.
     */
    public void setCamouflage(@Nullable BlockState state) {
        BlockState next = isValidCamouflage(state) ? state : null;
        if (next == camouflage || (next != null && next.equals(camouflage))) return;
        camouflage = next;
        if (level != null) {
            BlockState blockState = getBlockState();
            if (blockState.hasProperty(ProjectorBlock.CAMOUFLAGED)) {
                boolean hidden = next != null;
                if (blockState.getValue(ProjectorBlock.CAMOUFLAGED) != hidden) {
                    level.setBlock(worldPosition, blockState.setValue(ProjectorBlock.CAMOUFLAGED, hidden), Block.UPDATE_ALL);
                }
            }
        }
        markUpdated();
    }

    /** A camouflage needs a regular block model and a placeable item; BE-only blocks render blank. */
    public static boolean isValidCamouflage(@Nullable BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        if (block == HoloSigns.PROJECTOR.get()) return false;
        if (block instanceof EntityBlock) return false;
        if (block.asItem() == Items.AIR) return false;
        return state.getRenderShape() == RenderShape.MODEL;
    }

    public static @Nullable BlockState parseCamouflage(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        Identifier key = Identifier.tryParse(id);
        if (key == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(key)
                .map(Block::defaultBlockState)
                .filter(ProjectorBlockEntity::isValidCamouflage)
                .orElse(null);
    }

    public static String camouflageId(@Nullable BlockState state) {
        if (state == null) return "";
        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "" : key.toString();
    }

    /**
     * A hologram is emitted along the projector's facing axis. If that path is blocked by world
     * geometry, the display must not appear on the far side of the obstacle. Closed doors need an
     * explicit check because a door model only fills a thin slab of its block: the projection ray
     * can otherwise slip past the slab and make the hologram look like it shines through the door.
     * The collision-shape clip also catches full blocks, slabs, stairs and similar obstacles whose
     * shape actually crosses the beam.
     */
    public boolean isProjectionObstructed() {
        if (level == null) return false;
        Direction facing = getFacing();
        boolean anyObstacle = false;
        for (int step = 1; step <= distance; step++) {
            BlockPos pos = worldPosition.relative(facing, step);
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.DOORS)
                    && state.hasProperty(BlockStateProperties.OPEN)
                    && !state.getValue(BlockStateProperties.OPEN)) {
                return true;
            }
            if (!state.isAir()) anyObstacle = true;
        }
        if (!anyObstacle) return false;

        // Stay just outside the projector block and stop just short of the display plane, so a
        // block the hologram is mounted flush against does not count as an obstacle.
        Vec3 center = Vec3.atCenterOf(worldPosition);
        Vec3 start = center.relative(facing, 0.5 + 1.0E-4);
        Vec3 end = center.relative(facing, 0.5 + distance - 1.0E-4);
        ClipContext context = new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        return level.clip(context).getType() != HitResult.Type.MISS;
    }

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

    /**
     * One input event from the terminal screen that opens when a player clicks the floating
     * hologram panel. The ordinal is what travels over the wire.
     */
    public enum TerminalAction {
        KEY_DOWN,
        KEY_UP,
        CLIPBOARD,
        MOUSE_DOWN,
        MOUSE_DRAG,
        MOUSE_UP,
        MOUSE_SCROLL,
        REFRESH;

        public static @Nullable TerminalAction byId(int id) {
            TerminalAction[] values = values();
            return id >= 0 && id < values.length ? values[id] : null;
        }
    }

    /**
     * Whether a computer is bound to this projector, so clicking the projected panel can open a
     * terminal and type into that computer. The base projector has no computer integrations.
     */
    public boolean isComputerLinked() {
        return false;
    }

    /**
     * Forwards one event from the terminal screen to the bound computer. Implemented by the
     * optional computer integration; the base projector simply refuses the input so the click
     * falls back to the normal display behaviour.
     */
    public boolean handleTerminalInput(Player player, TerminalAction action, char character, int code,
                                       double x, double y, int button, @Nullable String text) {
        return false;
    }

    public void openEditor(ServerPlayer player) {
        EditDisplayPayload.sendOpen(player, worldPosition, lines, getFacing(), distance,
                sizePercent, backgroundColor, camouflageId(camouflage));
    }

    private void onTerminalChanged() {
        if (updatingFromDisk) return;
        mode = DisplayMode.MONITOR;
        markUpdated();
    }

    private static String sanitize(String value) {
        // Bound the scan before filtering; this also protects against oversized computer strings.
        if (value.length() > MAX_LINE_LENGTH * 2) {
            value = value.substring(0, MAX_LINE_LENGTH * 2);
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_LINE_LENGTH));
        value.codePoints().filter(cp -> !Character.isISOControl(cp)).limit(MAX_LINE_LENGTH).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    private static void trimTrailingEmptyLines(List<String> target) {
        while (!target.isEmpty() && target.get(target.size() - 1).isEmpty()) target.remove(target.size() - 1);
    }

    protected void markUpdated() {
        if (!(level instanceof ServerLevel serverLevel)) {
            setChanged();
            return;
        }
        // Computer integrations write from the computer's own Lua thread. Touching the level there
        // makes Minecraft schedule work on the server thread and wait for it, which deadlocks when
        // the server thread is saving that very computer. Queue the update instead; the end-of-tick
        // flush marks the chunk dirty and sends the block update on the server thread.
        if (!serverLevel.getServer().isSameThread()) {
            PENDING_UPDATES.add(this);
            return;
        }
        setChanged();
        PENDING_UPDATES.add(this);
    }

    /**
     * Server-side tick hook. The base projector has nothing to do each tick; the OpenComputers
     * subclass uses it to finish a Screen Linker bind whose computer loaded after the projector.
     */
    protected void serverTick() {
    }

    /**
     * Whether a display update for this projector is waiting for the end-of-tick flush. Visible to
     * the in-world game test, which checks that a write made during a server tick is drained by it.
     */
    boolean isUpdatePending() {
        return PENDING_UPDATES.contains(this);
    }

    /** Flushes coalesced display updates once per server tick. */
    public static void flushPendingUpdates(ServerTickEvent.Post event) {
        List<ProjectorBlockEntity> pending = PENDING_UPDATES.drain();
        for (ProjectorBlockEntity projector : pending) {
            if (projector.isRemoved()) continue;
            if (projector.level instanceof ServerLevel serverLevel) {
                // Off-thread writes could only queue the update, so the dirty mark happens here.
                projector.setChanged();
                BlockState state = projector.getBlockState();
                serverLevel.sendBlockUpdated(projector.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public void setRemoved() {
        PENDING_UPDATES.remove(this);
        super.setRemoved();
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
            trimTrailingEmptyLines(lines);
            textVersion++;

            distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, input.getIntOr("distance", DEFAULT_DISTANCE)));
            sizePercent = Math.max(MIN_SIZE_PERCENT, Math.min(MAX_SIZE_PERCENT, input.getIntOr("size", DEFAULT_SIZE_PERCENT)));
            backgroundColor = input.getIntOr("background", DEFAULT_BACKGROUND_COLOR) & 0xFFFFFF;
            camouflage = input.read("camouflage", BlockState.CODEC)
                    .filter(ProjectorBlockEntity::isValidCamouflage)
                    .orElse(null);
            mode = DisplayMode.byName(input.getStringOr("mode", DisplayMode.LINES.name()));

            ValueInput terminalInput = input.childOrEmpty("terminal");
            List<String> terminalRows = new ArrayList<>();
            for (String row : terminalInput.listOrEmpty("rows", Codec.STRING)) {
                if (terminalRows.size() == TerminalBuffer.MAX_ROWS) break;
                terminalRows.add(row);
            }
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
        output.putInt("size", sizePercent);
        output.putInt("background", backgroundColor);
        output.storeNullable("camouflage", BlockState.CODEC, camouflage);
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
