package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import li.cil.oc.api.Network;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.internal.TextBuffer;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Projector block entity exposed to OpenComputers.
 * <p>
 * Besides the {@code hologram_projector} component with the original sign-line callbacks, this
 * block entity implements {@link TextBuffer}, which is the type OpenComputers graphics cards look
 * for when binding a screen. A GPU next to a projector therefore binds the hologram and the
 * {@code term} API draws its character grid onto it, exactly like a monitor.
 */
public final class OCProjectorBlockEntity extends ProjectorBlockEntity implements Environment, TextBuffer {
    /** Same range as OpenComputers' Screen Linker. */
    public static final int LINK_RANGE = 64;
    private static final String COMPONENT_NAME = "hologram_projector";
    private static final int[] DEFAULT_PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x191919
    };

    private Component node;
    private final int[] palette = DEFAULT_PALETTE.clone();
    private ColorDepth colorDepth = ColorDepth.EightBit;
    private ColorDepth maximumColorDepth = ColorDepth.EightBit;
    private double aspectRatio = 1.0;
    private int viewportWidth = TerminalBuffer.DEFAULT_COLUMNS;
    private int viewportHeight = TerminalBuffer.DEFAULT_ROWS;
    private int activeForeground = 0xFFFFFF;
    private boolean foregroundFromPalette;
    private int activeBackground = 0x000000;
    private boolean backgroundFromPalette;
    private boolean renderingEnabled = true;
    private boolean powered = true;
    private BlockPos linkedCase;
    private boolean wirelessEdge;

    public OCProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(HoloSigns.PROJECTOR_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------ network

    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel)) return;
        if (node == null) {
            node = Network.newNode(this, Visibility.Network)
                    .withComponent(COMPONENT_NAME)
                    .create();
        }
        if (node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }
        // A projector linked with OpenComputers' Screen Linker is reattached after a reload.
        maintainWirelessLink();
    }

    @Override
    public void setRemoved() {
        if (node != null) {
            node.remove();
            node = null;
        }
        wirelessEdge = false;
        super.setRemoved();
    }

    // ------------------------------------------------------------------ wireless (Screen Linker) link

    /** Whether a computer was bound to this projector with the Screen Linker. */
    public boolean hasComputerLink() {
        return linkedCase != null;
    }

    /**
     * Binds the projector to a computer as if the Screen Linker had been used on an OpenComputers
     * screen. The projector's node is moved onto the computer's network, which is what the graphics
     * card needs to discover the projector as a TextBuffer and bind it.
     */
    public boolean linkToComputer(BlockPos casePos, Case computer) {
        if (casePos == null || computer == null || level == null || level.isClientSide()) return false;
        if (casePos.distSqr(worldPosition) > (long) LINK_RANGE * LINK_RANGE) return false;
        Node own = node();
        Node target = computer.node();
        if (own == null || target == null || target.network() == null) return false;

        dropWirelessEdge();
        if (own.network() != null) own.remove();
        Network.joinNewNetwork(own);
        own.connect(target);
        if (own.network() != target.network()) {
            linkedCase = null;
            setChanged();
            return false;
        }
        linkedCase = casePos.immutable();
        wirelessEdge = true;
        // Linking means the hologram is now a screen. Showing the authored lines until the GPU's
        // first write made a linked, still-blank terminal look like it had not linked at all.
        setMode(DisplayMode.MONITOR);
        // Let the terminal client drop any cached dimensions and redraw on the new screen.
        node.sendToReachable("computer.signal", "screen_resized",
                getTerminal().getColumns(), getTerminal().getRows());
        setChanged();
        return true;
    }

    public void unlinkComputer() {
        dropWirelessEdge();
        if (linkedCase != null) {
            linkedCase = null;
            setChanged();
        }
    }

    private void maintainWirelessLink() {
        BlockPos targetPos = linkedCase;
        if (targetPos == null || !(level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getBlockEntity(targetPos) instanceof Case computer) {
            Node target = computer.node();
            Node own = node();
            if (target != null && own != null && target.network() != null && own.network() != target.network()) {
                linkToComputer(targetPos, computer);
            }
        }
    }

    private void dropWirelessEdge() {
        if (!wirelessEdge) return;
        wirelessEdge = false;
        Node own = node();
        BlockPos targetPos = linkedCase;
        if (own == null || targetPos == null || level == null) return;
        if (level.getBlockEntity(targetPos) instanceof Case computer && computer.node() != null) {
            try {
                own.disconnect(computer.node());
            } catch (Throwable ignored) {
                // The computer may already be gone; nothing else to clean up.
            }
        }
    }

    @Override
    public Node node() {
        return node;
    }

    @Override
    public void onConnect(Node node) {
    }

    @Override
    public void onDisconnect(Node node) {
    }

    @Override
    public void onMessage(Message message) {
    }

    // ------------------------------------------------------------------ TextBuffer state

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public void update() {
    }

    @Override
    public void load(CompoundTag nbt, HolderLookup.Provider provider) {
        // The terminal is saved with the block entity itself.
    }

    @Override
    public void save(CompoundTag nbt, HolderLookup.Provider provider) {
        // The terminal is saved with the block entity itself.
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int[] savedLink = input.getIntArray("linkedCase").orElse(null);
        linkedCase = savedLink != null && savedLink.length == 3
                ? new BlockPos(savedLink[0], savedLink[1], savedLink[2])
                : null;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        BlockPos target = linkedCase;
        if (target != null) {
            output.putIntArray("linkedCase", new int[]{target.getX(), target.getY(), target.getZ()});
        }
    }

    @Override
    public double getEnergyCostPerTick() {
        return 0.0;
    }

    @Override
    public void setEnergyCostPerTick(double value) {
    }

    @Override
    public boolean getPowerState() {
        return powered;
    }

    @Override
    public void setPowerState(boolean value) {
        powered = value;
    }

    @Override
    public void setMaximumResolution(int width, int height) {
        // Resolution is bounded by what a hologram can legibly render.
    }

    @Override
    public int getMaximumWidth() {
        return TerminalBuffer.columnsForSizePercent(getSizePercent());
    }

    @Override
    public int getMaximumHeight() {
        return TerminalBuffer.rowsForSizePercent(getSizePercent());
    }

    /**
     * A hologram's terminal grid follows its configured size, so resizing the projection re-binds
     * the monitor at the new resolution instead of leaving the old grid stretched over it.
     */
    @Override
    public void setSizePercent(int value) {
        int previous = getSizePercent();
        super.setSizePercent(value);
        if (getSizePercent() != previous) {
            resizeTerminalToHologram();
        }
    }

    /** Resizes the grid to the projection's scaled resolution and tells a bound computer about it. */
    private void resizeTerminalToHologram() {
        int oldViewportWidth = getViewportWidth();
        int oldViewportHeight = getViewportHeight();
        boolean changed = getTerminal().setSize(getMaximumWidth(), getMaximumHeight());
        // The whole new grid is visible, as after a monitor resolution change.
        viewportWidth = getTerminal().getColumns();
        viewportHeight = getTerminal().getRows();
        boolean viewportChanged = viewportWidth != oldViewportWidth || viewportHeight != oldViewportHeight;
        if ((changed || viewportChanged) && node != null) {
            node.sendToReachable("computer.signal", "screen_resized", viewportWidth, viewportHeight);
        }
    }

    @Override
    public void setAspectRatio(double width, double height) {
        if (height > 0.0) aspectRatio = width / height;
    }

    @Override
    public double getAspectRatio() {
        return aspectRatio;
    }

    @Override
    public boolean setResolution(int width, int height) {
        // A program may ask for less than the hologram's maximum, but never for more cells than the
        // configured projection size provides.
        width = Math.max(1, Math.min(width, getMaximumWidth()));
        height = Math.max(1, Math.min(height, getMaximumHeight()));
        boolean changed = getTerminal().setSize(width, height);
        // Like an OpenComputers screen, changing the resolution resets the viewport to it, so a
        // graphics card that binds the projector gets a terminal that fills the hologram.
        int newViewportWidth = Math.min(getTerminal().getColumns(), Math.max(1, width));
        int newViewportHeight = Math.min(getTerminal().getRows(), Math.max(1, height));
        boolean viewportChanged = newViewportWidth != viewportWidth || newViewportHeight != viewportHeight;
        viewportWidth = newViewportWidth;
        viewportHeight = newViewportHeight;
        if ((changed || viewportChanged) && node != null) {
            // OpenComputers' own screen sends this when its resolution or viewport changes; the
            // terminal client uses it to drop cached dimensions and redraw after a bind.
            node.sendToReachable("computer.signal", "screen_resized", viewportWidth, viewportHeight);
        }
        return changed || viewportChanged;
    }

    @Override
    public int getWidth() {
        return getTerminal().getColumns();
    }

    @Override
    public int getHeight() {
        return getTerminal().getRows();
    }

    @Override
    public boolean setViewport(int width, int height) {
        int newWidth = Math.max(1, Math.min(getWidth(), width));
        int newHeight = Math.max(1, Math.min(getHeight(), height));
        if (newWidth == viewportWidth && newHeight == viewportHeight) return false;
        viewportWidth = newWidth;
        viewportHeight = newHeight;
        if (node != null) {
            node.sendToReachable("computer.signal", "screen_resized", viewportWidth, viewportHeight);
        }
        return true;
    }

    @Override
    public int getViewportWidth() {
        return Math.min(viewportWidth, getWidth());
    }

    @Override
    public int getViewportHeight() {
        return Math.min(viewportHeight, getHeight());
    }

    @Override
    public ColorDepth getMaximumColorDepth() {
        return maximumColorDepth;
    }

    @Override
    public void setMaximumColorDepth(ColorDepth depth) {
        if (depth != null) maximumColorDepth = depth;
    }

    @Override
    public boolean setColorDepth(ColorDepth depth) {
        if (depth == null || depth == colorDepth) return false;
        colorDepth = depth.ordinal() <= maximumColorDepth.ordinal() ? depth : maximumColorDepth;
        return true;
    }

    @Override
    public ColorDepth getColorDepth() {
        return colorDepth;
    }

    @Override
    public void setPaletteColor(int index, int color) {
        if (index < 0 || index >= palette.length) return;
        palette[index] = color & 0xFFFFFF;
    }

    @Override
    public int getPaletteColor(int index) {
        return index >= 0 && index < palette.length ? palette[index] : 0xFFFFFF;
    }

    @Override
    public void setForegroundColor(int color, boolean isFromPalette) {
        activeForeground = color;
        foregroundFromPalette = isFromPalette;
        applyActiveColors();
    }

    @Override
    public int getForegroundColor() {
        return activeForeground;
    }

    @Override
    public void setForegroundColor(int color) {
        setForegroundColor(color, false);
    }

    @Override
    public boolean isForegroundFromPalette() {
        return foregroundFromPalette;
    }

    @Override
    public void setBackgroundColor(int color, boolean isFromPalette) {
        activeBackground = color;
        backgroundFromPalette = isFromPalette;
        applyActiveColors();
    }

    @Override
    public int getBackgroundColor() {
        return activeBackground;
    }

    @Override
    public void setBackgroundColor(int color) {
        setBackgroundColor(color, false);
    }

    @Override
    public boolean isBackgroundFromPalette() {
        return backgroundFromPalette;
    }

    @Override
    public void copy(int column, int row, int width, int height, int horizontalTranslation, int verticalTranslation) {
        getTerminal().copy(column, row, width, height, horizontalTranslation, verticalTranslation);
    }

    @Override
    public void fill(int column, int row, int width, int height, int value) {
        char character = value > 0 && value <= Character.MAX_VALUE ? (char) value : ' ';
        getTerminal().fill(column, row, width, height, character, foregroundRgb(), backgroundRgb());
    }

    @Override
    public void set(int column, int row, String value, boolean vertical) {
        if (value == null) return;
        TerminalBuffer buffer = getTerminal();
        int currentColumn = column;
        int currentRow = row;
        // Batch so a graphics-card row write produces one block update, not one per character.
        buffer.beginBatch();
        try {
            for (int index = 0; index < value.length(); ) {
                int codePoint = value.codePointAt(index);
                index += Character.charCount(codePoint);
                if (currentColumn < 0 || currentColumn >= getWidth() || currentRow < 0 || currentRow >= getHeight()) break;
                char character = Character.isBmpCodePoint(codePoint) ? (char) codePoint : '?';
                buffer.setCell(currentColumn, currentRow, character, foregroundRgb(), backgroundRgb());
                if (vertical) currentRow++;
                else currentColumn++;
            }
        } finally {
            buffer.endBatch();
        }
    }

    @Override
    public int getCodePoint(int column, int row) {
        return getTerminal().characterAt(column, row);
    }

    @Override
    public int getForegroundColor(int column, int row) {
        return getTerminal().foregroundAt(column, row);
    }

    @Override
    public boolean isForegroundFromPalette(int column, int row) {
        return false;
    }

    @Override
    public int getBackgroundColor(int column, int row) {
        return getTerminal().backgroundAt(column, row);
    }

    @Override
    public boolean isBackgroundFromPalette(int column, int row) {
        return false;
    }

    @Override
    public void rawSetText(int column, int row, int[][] text) {
        if (text == null) return;
        TerminalBuffer buffer = getTerminal();
        // Clamp to the display so a malicious graphics-card payload cannot force a huge loop.
        int rowsToCopy = Math.min(text.length, getHeight());
        buffer.beginBatch();
        try {
            for (int rowOffset = 0; rowOffset < rowsToCopy; rowOffset++) {
                int targetRow = row + rowOffset;
                if (targetRow < 0 || targetRow >= getHeight()) continue;
                int[] line = text[rowOffset];
                if (line == null) continue;
                int columnsToCopy = Math.min(line.length, getWidth());
                for (int columnOffset = 0; columnOffset < columnsToCopy; columnOffset++) {
                    int targetColumn = column + columnOffset;
                    if (targetColumn < 0 || targetColumn >= getWidth()) continue;
                    int codePoint = line[columnOffset];
                    char character = codePoint > 0 && codePoint <= Character.MAX_VALUE ? (char) codePoint : ' ';
                    buffer.setCell(targetColumn, targetRow, character,
                            buffer.foregroundAt(targetColumn, targetRow),
                            buffer.backgroundAt(targetColumn, targetRow));
                }
            }
        } finally {
            buffer.endBatch();
        }
    }

    @Override
    public void rawSetForeground(int column, int row, int[][] color) {
        if (color == null) return;
        TerminalBuffer buffer = getTerminal();
        int rowsToCopy = Math.min(color.length, getHeight());
        buffer.beginBatch();
        try {
            for (int rowOffset = 0; rowOffset < rowsToCopy; rowOffset++) {
                int targetRow = row + rowOffset;
                if (targetRow < 0 || targetRow >= getHeight()) continue;
                int[] line = color[rowOffset];
                if (line == null) continue;
                int columnsToCopy = Math.min(line.length, getWidth());
                for (int columnOffset = 0; columnOffset < columnsToCopy; columnOffset++) {
                    int targetColumn = column + columnOffset;
                    if (targetColumn < 0 || targetColumn >= getWidth()) continue;
                    buffer.setCell(targetColumn, targetRow,
                            (char) buffer.characterAt(targetColumn, targetRow),
                            line[columnOffset],
                            buffer.backgroundAt(targetColumn, targetRow));
                }
            }
        } finally {
            buffer.endBatch();
        }
    }

    @Override
    public void rawSetBackground(int column, int row, int[][] color) {
        if (color == null) return;
        TerminalBuffer buffer = getTerminal();
        int rowsToCopy = Math.min(color.length, getHeight());
        buffer.beginBatch();
        try {
            for (int rowOffset = 0; rowOffset < rowsToCopy; rowOffset++) {
                int targetRow = row + rowOffset;
                if (targetRow < 0 || targetRow >= getHeight()) continue;
                int[] line = color[rowOffset];
                if (line == null) continue;
                int columnsToCopy = Math.min(line.length, getWidth());
                for (int columnOffset = 0; columnOffset < columnsToCopy; columnOffset++) {
                    int targetColumn = column + columnOffset;
                    if (targetColumn < 0 || targetColumn >= getWidth()) continue;
                    buffer.setCell(targetColumn, targetRow,
                            (char) buffer.characterAt(targetColumn, targetRow),
                            buffer.foregroundAt(targetColumn, targetRow),
                            line[columnOffset]);
                }
            }
        } finally {
            buffer.endBatch();
        }
    }

    @Override
    public boolean renderText(Object stack) {
        return false;
    }

    @Override
    public int renderWidth() {
        return getWidth();
    }

    @Override
    public int renderHeight() {
        return getHeight();
    }

    @Override
    public boolean isRenderingEnabled() {
        return renderingEnabled;
    }

    @Override
    public void setRenderingEnabled(boolean enabled) {
        renderingEnabled = enabled;
    }

    @Override
    public void keyDown(char character, int code, Player player) {
    }

    @Override
    public void keyUp(char character, int code, Player player) {
    }

    @Override
    public void clipboard(String value, Player player) {
    }

    @Override
    public void dropFile(String fileName, String fileContent, Player player) {
    }

    @Override
    public void mouseDown(double x, double y, int button, Player player) {
    }

    @Override
    public void mouseDrag(double x, double y, int button, Player player) {
    }

    @Override
    public void mouseUp(double x, double y, int button, Player player) {
    }

    @Override
    public void mouseScroll(double x, double y, int delta, Player player) {
    }

    // ------------------------------------------------------------------ component callbacks

    @Callback(doc = "function():number -- The projection distance in blocks.")
    public Object[] getDistance(Context context, Arguments arguments) {
        return new Object[]{super.getDistance()};
    }

    @Callback(doc = "function(distance:number):number -- Sets the projection distance in blocks and returns the new value.")
    public Object[] setDistance(Context context, Arguments arguments) {
        super.setDistance(arguments.checkInteger(0));
        return new Object[]{super.getDistance()};
    }

    @Callback(doc = "function():string -- The direction the hologram is projected in.")
    public Object[] getDirection(Context context, Arguments arguments) {
        return new Object[]{super.getFacing().getSerializedName()};
    }

    @Callback(doc = "function(direction:string):boolean -- Points the projection up, down, north, south, east or west.")
    public Object[] setDirection(Context context, Arguments arguments) {
        Direction direction = Direction.byName(arguments.checkString(0).toLowerCase(java.util.Locale.ROOT));
        if (direction == null) {
            return new Object[]{false, "direction must be one of up, down, north, south, east or west"};
        }
        return new Object[]{super.setFacing(direction)};
    }

    @Callback(doc = "function():string -- Either 'lines' or 'monitor'.")
    public Object[] getMode(Context context, Arguments arguments) {
        return new Object[]{super.getMode().lowerName()};
    }

    @Callback(doc = "function(mode:string):boolean -- Switches the hologram between 'lines' and 'monitor'.")
    public Object[] setMode(Context context, Arguments arguments) {
        super.setMode(DisplayMode.byName(arguments.checkString(0)));
        return new Object[]{true};
    }

    @Callback(doc = "function():table -- Returns the current hologram lines, indexed from 1.")
    public Object[] getLines(Context context, Arguments arguments) {
        return new Object[]{super.getLines()};
    }

    @Callback(doc = "function(line:number, text:string):boolean -- Sets a single 1-based hologram line.")
    public Object[] setLine(Context context, Arguments arguments) {
        int line = arguments.checkInteger(0);
        String text = arguments.checkString(1);
        if (line < 1 || line > MAX_LINES) {
            return new Object[]{false, "line must be between 1 and " + MAX_LINES};
        }
        if (isTooLong(text)) {
            return new Object[]{false, "text must be at most " + MAX_LINE_LENGTH + " characters"};
        }
        return new Object[]{super.setLine(line - 1, text)};
    }

    @Callback(doc = "function(lines:table):boolean -- Replaces all hologram lines; an empty table clears them.")
    @SuppressWarnings("rawtypes")
    public Object[] setLines(Context context, Arguments arguments) {
        Map input = arguments.checkTable(0);
        if (input.size() > MAX_LINES) {
            return new Object[]{false, "at most " + MAX_LINES + " lines are supported"};
        }

        String[] indexed = new String[MAX_LINES];
        int highestIndex = 0;
        for (Object entryObject : input.entrySet()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
            if (!(entry.getKey() instanceof Number number)) {
                return new Object[]{false, "lines must be a 1-based numeric array"};
            }
            double numericIndex = number.doubleValue();
            int index = (int) numericIndex;
            if (!Double.isFinite(numericIndex) || numericIndex != index || index < 1 || index > MAX_LINES) {
                return new Object[]{false, "line indexes must be between 1 and " + MAX_LINES};
            }
            if (indexed[index - 1] != null) {
                return new Object[]{false, "line indexes must be unique"};
            }
            if (!(entry.getValue() instanceof String text)) {
                return new Object[]{false, "all lines must be strings"};
            }
            if (isTooLong(text)) {
                return new Object[]{false, "each line must be at most " + MAX_LINE_LENGTH + " characters"};
            }
            indexed[index - 1] = text;
            highestIndex = Math.max(highestIndex, index);
        }

        List<String> lines = new ArrayList<>(highestIndex);
        for (int i = 0; i < highestIndex; i++) {
            if (indexed[i] == null) {
                return new Object[]{false, "lines must be a contiguous 1-based array"};
            }
            lines.add(indexed[i]);
        }
        super.setLines(lines);
        return new Object[]{true};
    }

    @Callback(doc = "function():boolean -- Clears all hologram lines.")
    public Object[] clearLines(Context context, Arguments arguments) {
        super.clearLines();
        return new Object[]{true};
    }

    // ------------------------------------------------------------------ colors

    /** Rejects oversized strings before scanning every code point. */
    private static boolean isTooLong(String text) {
        return text.length() > MAX_LINE_LENGTH * 2
                || text.codePointCount(0, text.length()) > MAX_LINE_LENGTH;
    }

    private void applyActiveColors() {
        getTerminal().setActiveColors(foregroundRgb(), backgroundRgb());
    }

    private int foregroundRgb() {
        return resolveColor(activeForeground, foregroundFromPalette);
    }

    private int backgroundRgb() {
        return resolveColor(activeBackground, backgroundFromPalette);
    }

    private int resolveColor(int color, boolean fromPalette) {
        if (!fromPalette) return color & 0xFFFFFF;
        int index = Math.max(0, Math.min(palette.length - 1, color));
        return palette[index];
    }
}
