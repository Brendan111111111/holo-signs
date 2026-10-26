package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import com.brendan.holograms.Utf8Text;
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
import org.jetbrains.annotations.Nullable;

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
    /** How often to retry a saved link whose computer was not ready during load. */
    private static final int RELINK_RETRY_TICKS = 10;
    private static final int[] DEFAULT_PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x191919
    };

    /** The projector's own {@code hologram_projector} component, on its local network. */
    private Component node;
    private final ProjectorHost projectorHost = new ProjectorHost();
    private final ScreenHost screenHost = new ScreenHost();
    private final KeyboardHost keyboardHost = new KeyboardHost();
    /**
     * The screen the linked computer's graphics card binds.
     * <p>
     * A graphics card will bind any {@link TextBuffer} host on its network, but OpenOS and other
     * operating systems look the display up as a {@code screen} component before they bind it. The
     * projector's own component keeps its sign-line name, so the linked computer gets a second
     * node registered as {@code screen}; without it a real shell boots with no display at all.
     */
    private Component screenNode;
    /**
     * A {@code keyboard} component for the linked computer, created only when the computer has no
     * keyboard of its own. OpenOS accepts key events only when their source address is the one the
     * screen's {@code getKeyboards} reports, and its primary-screen fast path reads the first
     * {@code keyboard} component instead. Registering the projector as that keyboard makes typing
     * on the hologram work without a physical keyboard next to the far-away computer.
     */
    private Component keyboardNode;
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
    private int relinkCooldown;
    /** Set when a saved link still has to point the computer's card at this hologram after a load. */
    private boolean displayBindPending;

    public OCProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(HoloSigns.PROJECTOR_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------ network

    @Override
    public void onLoad() {
        super.onLoad();
        if (!(level instanceof ServerLevel)) return;
        if (node == null) {
            node = Network.newNode(projectorHost, Visibility.Network)
                    .withComponent(COMPONENT_NAME)
                    .create();
        }
        if (node.network() == null) {
            Network.joinOrCreateNetwork(this);
        }
        // A projector linked with OpenComputers' Screen Linker is reattached after a reload.
        maintainWirelessLink();
        // The linked computer may load after this projector, so the card's screen is pointed at
        // the hologram from the server tick once the computer exists.
        if (linkedCase != null) displayBindPending = true;
    }

    @Override
    public void setRemoved() {
        removeLinkedNodes();
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
        removeLinkedNodes();
        if (!joinNodeToNetwork(own, target)) {
            linkedCase = null;
            markUpdated();
            return false;
        }

        // A screen component is what OpenOS looks for before it binds the display; the projector's
        // own TextBuffer interface alone only satisfies a graphics card that already has an address.
        // The host has to know its node before it joins: the graphics card's onConnect reads the
        // screen's address while the network notification is still running.
        Component screen = Network.newNode(screenHost, Visibility.Network).withComponent("screen").create();
        screenNode = screen;
        if (!joinNodeToNetwork(screen, target)) {
            screen.remove();
            screenNode = null;
            linkedCase = null;
            markUpdated();
            return false;
        }

        // Reuse a real keyboard when the computer has one: the terminal's key filter is the address
        // of the first keyboard component, and adding a second one would make that choice arbitrary.
        if (!hasKeyboardOnNetwork(target)) {
            Component keyboard = Network.newNode(keyboardHost, Visibility.Network).withComponent("keyboard").create();
            keyboardNode = keyboard;
            if (!joinNodeToNetwork(keyboard, target)) {
                keyboard.remove();
                keyboardNode = null;
            }
        }

        linkedCase = casePos.immutable();
        wirelessEdge = true;
        // Linking means the hologram is now a screen. Showing the authored lines until the GPU's
        // first write made a linked, still-blank terminal look like it had not linked at all.
        setMode(DisplayMode.MONITOR);
        // Adding the screen to the network is not enough: the graphics card keeps drawing on
        // whichever screen it was bound to, which left the hologram mirroring another monitor and
        // typing on it invisible. Point every card on the computer at the hologram instead.
        displayBindPending = !bindGraphicsCards(target);
        // Let the terminal client drop any cached dimensions and redraw on the new screen.
        node.sendToReachable("computer.signal", "screen_resized",
                getTerminal().getColumns(), getTerminal().getRows());
        // A computer that was already running has its prompt on whatever screen it was bound to,
        // and a screen only paints on its next write. Copying the bound screen gives the hologram
        // the current picture right away instead of staying blank until the next keystroke.
        copyBoundScreen();
        // Clients need to know about the link too: clicking the floating panel checks it before
        // opening the terminal, and a plain setChanged() would not send a block update.
        markUpdated();
        return true;
    }

    public void unlinkComputer() {
        dropWirelessEdge();
        removeLinkedNodes();
        if (linkedCase != null) {
            linkedCase = null;
            markUpdated();
        }
    }

    private void maintainWirelessLink() {
        BlockPos targetPos = linkedCase;
        if (targetPos == null || !(level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getBlockEntity(targetPos) instanceof Case computer) {
            if (!hasActiveWirelessLink()) {
                linkToComputer(targetPos, computer);
            }
        }
    }

    /**
     * Points the computer's graphics cards at the hologram's screen component.
     * <p>
     * OpenComputers' screen linker only moves the screen onto the computer's network; the graphics
     * card binds a screen on its own schedule and otherwise keeps drawing on the monitor it was
     * already using. That made a linked hologram show a copy of that other monitor and made keys
     * typed on the panel invisible, so linking and loading explicitly bind the cards with the same
     * {@code gpu.bind(address)} call an operating system uses.
     */
    private boolean bindGraphicsCards(Node computerNode) {
        if (computerNode == null || computerNode.network() == null) return false;
        Node screen = screenNode;
        if (screen == null || screen.address() == null || screen.address().isEmpty()) return false;
        boolean bound = false;
        for (Node candidate : computerNode.network().nodes()) {
            if (!(candidate instanceof Component component) || !"gpu".equals(component.name())) continue;
            try {
                component.invoke("bind", null, screen.address());
                bound = true;
            } catch (Throwable ignored) {
                // A card with no room for this resolution keeps its previous screen; nothing to do.
            }
        }
        return bound;
    }

    /**
     * Puts a component node on the computer's network, replacing any network it was on before. This
     * is the same wireless hop OpenComputers' Screen Linker makes: the node joins a fresh network
     * and is connected to the computer's node, which merges the two.
     */
    private static boolean joinNodeToNetwork(Node node, Node target) {
        if (node == null || target == null || target.network() == null) return false;
        if (node.network() != null) node.remove();
        Network.joinNewNetwork(node);
        node.connect(target);
        return node.network() == target.network();
    }

    /** True when a real keyboard component is already available to the linked computer. */
    private static boolean hasKeyboardOnNetwork(Node target) {
        if (target == null || target.network() == null) return false;
        for (Node candidate : target.network().nodes()) {
            if (candidate != target && candidate instanceof Component component
                    && "keyboard".equals(component.name())) {
                return true;
            }
        }
        return false;
    }

    /** Removes the screen and keyboard components added by a link. */
    private void removeLinkedNodes() {
        if (screenNode != null) {
            screenNode.remove();
            screenNode = null;
        }
        if (keyboardNode != null) {
            keyboardNode.remove();
            keyboardNode = null;
        }
    }

    /**
     * A world reload can load the projector before the linked computer's block entity or network
     * node exists. The one-shot attempt in {@link #onLoad()} then cannot reconnect, so retry from
     * the server tick until the computer is available again.
     */
    @Override
    protected void serverTick() {
        if (linkedCase == null) return;
        if (relinkCooldown > 0) {
            relinkCooldown--;
            return;
        }
        relinkCooldown = RELINK_RETRY_TICKS;
        if (!hasActiveWirelessLink()) {
            maintainWirelessLink();
        }
        if (displayBindPending && level instanceof ServerLevel serverLevel
                && serverLevel.getBlockEntity(linkedCase) instanceof Case computer
                && computer.node() != null) {
            // Retry until the computer has a graphics card to point at the hologram.
            displayBindPending = !bindGraphicsCards(computer.node());
        }
    }

    /**
     * Whether the saved computer is loaded and already on the projector's network. A missing
     * computer counts as active because there is nothing to connect to until it loads.
     */
    private boolean hasActiveWirelessLink() {
        if (!(level instanceof ServerLevel serverLevel)) return true;
        if (!(serverLevel.getBlockEntity(linkedCase) instanceof Case computer)) return true;
        Node own = node();
        Node screen = screenNode;
        Node target = computer.node();
        return own != null && screen != null && target != null
                && own.network() != null && own.network() == target.network()
                && screen.network() == target.network();
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
        // OpenComputers decodes Lua strings with the platform charset, which turns UTF-8 box
        // drawing and other multi-byte glyphs into one cell per byte. Recover the characters so a
        // single box character occupies one cell instead of rendering as three pieces.
        value = Utf8Text.repairByteMapped(value);
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

    /**
     * The terminal screen is only offered for projectors that actually have a computer behind the
     * hologram, so the client can decide whether a right-click should open it.
     */
    @Override
    public boolean isComputerLinked() {
        return hasComputerLink();
    }

    @Override
    public boolean handleTerminalInput(Player player, TerminalAction action, char character, int code,
                                       double x, double y, int button, String text) {
        if (linkedCase == null || player == null) return false;
        switch (action) {
            case KEY_DOWN -> keyDown(character, code, player);
            case KEY_UP -> keyUp(character, code, player);
            case CLIPBOARD -> clipboard(text == null ? "" : text, player);
            case MOUSE_DOWN -> mouseDown(x, y, button, player);
            case MOUSE_DRAG -> mouseDrag(x, y, button, player);
            case MOUSE_UP -> mouseUp(x, y, button, player);
            case MOUSE_SCROLL -> mouseScroll(x, y, button, player);
            case REFRESH -> refreshScreen();
        }
        return true;
    }

    /**
     * Nudges the terminal client to redraw into the newly opened hologram. The resize signal is the
     * only way a graphics card repaints a screen that was bound after it had already drawn, which
     * is exactly the blank-monitor case right after linking a running computer.
     */
    private void refreshScreen() {
        if (node == null || node.network() == null) return;
        try {
            node.sendToReachable("computer.signal", "screen_resized", getWidth(), getHeight());
        } catch (Throwable ignored) {
            // A removed computer has nothing to repaint.
        }
        copyBoundScreen();
    }

    /**
     * Copies the screen the graphics card is drawing on into the hologram grid.
     * <p>
     * A terminal only paints when it writes, so a prompt that was already on a monitor never
     * appears on a freshly linked hologram. Reading the bound screen's cells makes the hologram
     * show the same picture immediately; every later write arrives through OpenComputers' own
     * screen mirroring. A graphics card bound to the hologram itself has nothing to copy.
     */
    private void copyBoundScreen() {
        li.cil.oc.api.internal.TextBuffer source = boundScreen();
        if (source == null) return;
        int columns = Math.min(source.getWidth(), getWidth());
        int rows = Math.min(source.getHeight(), getHeight());
        if (columns <= 0 || rows <= 0) return;
        TerminalBuffer target = getTerminal();
        target.beginBatch();
        try {
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int codePoint = source.getCodePoint(column, row);
                    char character = codePoint > 0 && codePoint <= Character.MAX_VALUE ? (char) codePoint : ' ';
                    target.setCell(column, row, character,
                            source.getForegroundColor(column, row) & 0xFFFFFF,
                            source.getBackgroundColor(column, row) & 0xFFFFFF);
                }
            }
        } catch (Throwable ignored) {
            // A screen being removed has nothing to copy.
        } finally {
            target.endBatch();
        }
    }

    /**
     * The {@code TextBuffer} a graphics card on this network is currently bound to, or null when
     * no card is bound or the card is bound to this hologram.
     */
    private @Nullable li.cil.oc.api.internal.TextBuffer boundScreen() {
        Node root = screenNode != null ? screenNode : node;
        if (root == null || root.network() == null) return null;
        for (Node candidate : root.network().nodes()) {
            if (candidate == null || candidate == root || candidate == node) continue;
            if (!(candidate instanceof Component component) || !"gpu".equals(component.name())) continue;
            try {
                Object[] result = component.invoke("getScreen", null);
                if (result.length == 0 || !(result[0] instanceof String address) || address.isEmpty()) continue;
                Node boundNode = root.network().node(address);
                if (boundNode != null && boundNode != root && boundNode != node && boundNode != screenNode
                        && boundNode.host() != this
                        && boundNode.host() instanceof li.cil.oc.api.internal.TextBuffer buffer) {
                    return buffer;
                }
            } catch (Throwable ignored) {
                // A card without a bound screen has nothing to lend.
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ terminal input

    @Override
    public void keyDown(char character, int code, Player player) {
        sendKeySignal("key_down", player, (int) character, code);
    }

    @Override
    public void keyUp(char character, int code, Player player) {
        sendKeySignal("key_up", player, (int) character, code);
    }

    @Override
    public void clipboard(String value, Player player) {
        if (value == null || value.isEmpty()) return;
        sendCheckedSignal(keySourceNode(), player, "clipboard", value);
    }

    @Override
    public void dropFile(String fileName, String fileContent, Player player) {
        sendCheckedSignal(screenSourceNode(), player, "drop_file", fileName, fileContent);
    }

    @Override
    public void mouseDown(double x, double y, int button, Player player) {
        sendMouseEvent(player, "touch", x, y, button);
    }

    @Override
    public void mouseDrag(double x, double y, int button, Player player) {
        sendMouseEvent(player, "drag", x, y, button);
    }

    @Override
    public void mouseUp(double x, double y, int button, Player player) {
        sendMouseEvent(player, "drop", x, y, button);
    }

    @Override
    public void mouseScroll(double x, double y, int delta, Player player) {
        sendMouseEvent(player, "scroll", x, y, delta);
    }

    /**
     * Delivers a key event straight to the bound computer, the same way OpenComputers' own tablet
     * fallback does.
     * <p>
     * A monitor normally routes keys through its built-in keyboard component. The projector has no
     * such component, and a keyboard installed in the computer case only listens within eight blocks
     * of the case, while the panel may be up to 64 blocks away. Sending the checked signal itself
     * raises the regular {@code key_down}/{@code key_up} Lua events without either limitation.
     */
    private void sendKeySignal(String name, Player player, int character, int code) {
        sendCheckedSignal(keySourceNode(), player, name, character, code);
    }

    /**
     * The node key events appear to come from.
     * <p>
     * OpenComputers' terminal filters key events by source address: the one returned by the bound
     * screen's {@code getKeyboards}. While linked, the projector either registers itself as the
     * computer's keyboard or types through the computer's own keyboard, and both {@code getKeyboards}
     * and the events use this same node so the filter always matches.
     */
    private Node keySourceNode() {
        if (keyboardNode != null) return keyboardNode;
        Node root = screenNode != null ? screenNode : node;
        if (root == null || root.network() == null) return root;
        for (Node candidate : root.network().nodes()) {
            if (candidate != null && candidate != root && candidate instanceof Component component
                    && "keyboard".equals(component.name())) {
                return candidate;
            }
        }
        return root;
    }

    /**
     * The node mouse and touch events appear to come from. OpenComputers' terminal checks those
     * against the screen the graphics card is bound to, so a hologram mirroring a monitor clicks as
     * that monitor; a hologram bound to the card itself clicks as its {@code screen} component.
     */
    private Node screenSourceNode() {
        li.cil.oc.api.internal.TextBuffer screen = boundScreen();
        Node boundScreenNode = screen != null ? screen.node() : null;
        if (boundScreenNode != null) return boundScreenNode;
        return screenNode != null ? screenNode : node;
    }

    /** A mouse event is delivered to the machine as an OpenComputers checked signal. */
    private void sendMouseEvent(Player player, String name, double x, double y, int data) {
        // Screens report 1-based integer cell coordinates unless precision mode is enabled.
        sendCheckedSignal(screenSourceNode(), player, name, (int) x + 1, (int) y + 1, data);
    }

    private void sendCheckedSignal(Node source, Player player, String name, Object... data) {
        if (source == null || source.network() == null || player == null) return;
        Object[] payload = new Object[data.length + 2];
        payload[0] = player;
        payload[1] = name;
        System.arraycopy(data, 0, payload, 2, data.length);
        try {
            source.sendToReachable("computer.checked_signal", payload);
        } catch (Throwable ignored) {
            // Same as keys: a removed computer must not break the open terminal.
        }
    }

    // ------------------------------------------------------------------ component callbacks

    /**
     * The keyboards that type into this hologram. The terminal only accepts {@code key_down} and
     * {@code clipboard} events whose source is listed here, so the hologram reports the computer's
     * keyboard, and falls back to itself: the panel then acts as the screen's own keyboard.
     */
    @Callback(doc = "function():table -- The keyboards that type into this hologram.")
    public Object[] getKeyboards(Context context, Arguments arguments) {
        Node source = keySourceNode();
        if (source == null || source.address() == null || source.address().isEmpty()) {
            return new Object[]{List.of()};
        }
        return new Object[]{List.of(source.address())};
    }

    /** The address of the {@code screen} component, or null while no computer is linked. */
    public @Nullable String screenAddress() {
        return screenNode != null ? screenNode.address() : null;
    }

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

    // ------------------------------------------------------------------ component hosts

    /**
     * Environment of the {@code screen} component. Everything is forwarded to the projector's own
     * terminal grid, but the node is the screen node, not the projector's: OpenComputers asks the
     * bound screen for its node address ({@code gpu.getScreen}), and an operating system resolves
     * that address as a screen component. Hosting the screen on the projector would report an
     * address OpenOS cannot find, which made it abort with "no such component".
     */
    public final class ScreenHost implements TextBuffer {
        @Override public Node node() { return screenNode; }
        @Override public void onConnect(Node node) { }
        @Override public void onDisconnect(Node node) { }
        @Override public void onMessage(Message message) { }
        @Override public void load(CompoundTag nbt, HolderLookup.Provider provider) { }
        @Override public void save(CompoundTag nbt, HolderLookup.Provider provider) { }
        @Override public boolean canUpdate() { return false; }
        @Override public void update() { }

        @Override public double getEnergyCostPerTick() {
            return OCProjectorBlockEntity.this.getEnergyCostPerTick();
        }

        @Override public void setEnergyCostPerTick(double value) {
            OCProjectorBlockEntity.this.setEnergyCostPerTick(value);
        }

        @Override public boolean getPowerState() {
            return OCProjectorBlockEntity.this.getPowerState();
        }

        @Override public void setPowerState(boolean value) {
            OCProjectorBlockEntity.this.setPowerState(value);
        }

        @Override public void setMaximumResolution(int width, int height) {
            OCProjectorBlockEntity.this.setMaximumResolution(width, height);
        }

        @Override public int getMaximumWidth() {
            return OCProjectorBlockEntity.this.getMaximumWidth();
        }

        @Override public int getMaximumHeight() {
            return OCProjectorBlockEntity.this.getMaximumHeight();
        }

        @Override public void setAspectRatio(double width, double height) {
            OCProjectorBlockEntity.this.setAspectRatio(width, height);
        }

        @Override public double getAspectRatio() {
            return OCProjectorBlockEntity.this.getAspectRatio();
        }

        @Override public boolean setResolution(int width, int height) {
            return OCProjectorBlockEntity.this.setResolution(width, height);
        }

        @Override public int getWidth() {
            return OCProjectorBlockEntity.this.getWidth();
        }

        @Override public int getHeight() {
            return OCProjectorBlockEntity.this.getHeight();
        }

        @Override public boolean setViewport(int width, int height) {
            return OCProjectorBlockEntity.this.setViewport(width, height);
        }

        @Override public int getViewportWidth() {
            return OCProjectorBlockEntity.this.getViewportWidth();
        }

        @Override public int getViewportHeight() {
            return OCProjectorBlockEntity.this.getViewportHeight();
        }

        @Override public ColorDepth getMaximumColorDepth() {
            return OCProjectorBlockEntity.this.getMaximumColorDepth();
        }

        @Override public void setMaximumColorDepth(ColorDepth depth) {
            OCProjectorBlockEntity.this.setMaximumColorDepth(depth);
        }

        @Override public boolean setColorDepth(ColorDepth depth) {
            return OCProjectorBlockEntity.this.setColorDepth(depth);
        }

        @Override public ColorDepth getColorDepth() {
            return OCProjectorBlockEntity.this.getColorDepth();
        }

        @Override public void setPaletteColor(int index, int color) {
            OCProjectorBlockEntity.this.setPaletteColor(index, color);
        }

        @Override public int getPaletteColor(int index) {
            return OCProjectorBlockEntity.this.getPaletteColor(index);
        }

        @Override public void setForegroundColor(int color, boolean isFromPalette) {
            OCProjectorBlockEntity.this.setForegroundColor(color, isFromPalette);
        }

        @Override public int getForegroundColor() {
            return OCProjectorBlockEntity.this.getForegroundColor();
        }

        @Override public void setForegroundColor(int color) {
            OCProjectorBlockEntity.this.setForegroundColor(color);
        }

        @Override public boolean isForegroundFromPalette() {
            return OCProjectorBlockEntity.this.isForegroundFromPalette();
        }

        @Override public void setBackgroundColor(int color, boolean isFromPalette) {
            OCProjectorBlockEntity.this.setBackgroundColor(color, isFromPalette);
        }

        @Override public int getBackgroundColor() {
            return OCProjectorBlockEntity.this.getBackgroundColor();
        }

        @Override public void setBackgroundColor(int color) {
            OCProjectorBlockEntity.this.setBackgroundColor(color);
        }

        @Override public boolean isBackgroundFromPalette() {
            return OCProjectorBlockEntity.this.isBackgroundFromPalette();
        }

        @Override public void copy(int column, int row, int width, int height,
                                  int horizontalTranslation, int verticalTranslation) {
            OCProjectorBlockEntity.this.copy(column, row, width, height, horizontalTranslation, verticalTranslation);
        }

        @Override public void fill(int column, int row, int width, int height, int value) {
            OCProjectorBlockEntity.this.fill(column, row, width, height, value);
        }

        @Override public void set(int column, int row, String value, boolean vertical) {
            OCProjectorBlockEntity.this.set(column, row, value, vertical);
        }

        @Override public int getCodePoint(int column, int row) {
            return OCProjectorBlockEntity.this.getCodePoint(column, row);
        }

        @Override public int getForegroundColor(int column, int row) {
            return OCProjectorBlockEntity.this.getForegroundColor(column, row);
        }

        @Override public boolean isForegroundFromPalette(int column, int row) {
            return OCProjectorBlockEntity.this.isForegroundFromPalette(column, row);
        }

        @Override public int getBackgroundColor(int column, int row) {
            return OCProjectorBlockEntity.this.getBackgroundColor(column, row);
        }

        @Override public boolean isBackgroundFromPalette(int column, int row) {
            return OCProjectorBlockEntity.this.isBackgroundFromPalette(column, row);
        }

        @Override public void rawSetText(int column, int row, int[][] text) {
            OCProjectorBlockEntity.this.rawSetText(column, row, text);
        }

        @Override public void rawSetForeground(int column, int row, int[][] color) {
            OCProjectorBlockEntity.this.rawSetForeground(column, row, color);
        }

        @Override public void rawSetBackground(int column, int row, int[][] color) {
            OCProjectorBlockEntity.this.rawSetBackground(column, row, color);
        }

        @Override public boolean renderText(Object stack) {
            return OCProjectorBlockEntity.this.renderText(stack);
        }

        @Override public int renderWidth() {
            return OCProjectorBlockEntity.this.renderWidth();
        }

        @Override public int renderHeight() {
            return OCProjectorBlockEntity.this.renderHeight();
        }

        @Override public boolean isRenderingEnabled() {
            return OCProjectorBlockEntity.this.isRenderingEnabled();
        }

        @Override public void setRenderingEnabled(boolean enabled) {
            OCProjectorBlockEntity.this.setRenderingEnabled(enabled);
        }

        @Override public void keyDown(char character, int code, Player player) {
            OCProjectorBlockEntity.this.keyDown(character, code, player);
        }

        @Override public void keyUp(char character, int code, Player player) {
            OCProjectorBlockEntity.this.keyUp(character, code, player);
        }

        @Override public void clipboard(String value, Player player) {
            OCProjectorBlockEntity.this.clipboard(value, player);
        }

        @Override public void dropFile(String fileName, String fileContent, Player player) {
            OCProjectorBlockEntity.this.dropFile(fileName, fileContent, player);
        }

        @Override public void mouseDown(double x, double y, int button, Player player) {
            OCProjectorBlockEntity.this.mouseDown(x, y, button, player);
        }

        @Override public void mouseDrag(double x, double y, int button, Player player) {
            OCProjectorBlockEntity.this.mouseDrag(x, y, button, player);
        }

        @Override public void mouseUp(double x, double y, int button, Player player) {
            OCProjectorBlockEntity.this.mouseUp(x, y, button, player);
        }

        @Override public void mouseScroll(double x, double y, int delta, Player player) {
            OCProjectorBlockEntity.this.mouseScroll(x, y, delta, player);
        }

        @Callback(doc = "function():table -- The keyboards that type into this hologram.")
        public Object[] getKeyboards(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.getKeyboards(context, arguments);
        }

        @Callback(doc = "function():number -- The screen's aspect ratio.")
        public Object[] getAspectRatio(Context context, Arguments arguments) {
            return new Object[]{OCProjectorBlockEntity.this.getAspectRatio()};
        }
    }

    /**
     * Environment of the projector's own {@code hologram_projector} component.
     * <p>
     * Each component node needs its own host, because OpenComputers reads a node's identity from
     * {@code host().node()}. The projector component must not look like a {@code TextBuffer}: a
     * graphics card auto-binds the first TextBuffer on its network, and if it picked the projector
     * instead of the screen it would report an address that is not a screen component. OpenOS then
     * fails to resolve its boot screen.
     */
    public final class ProjectorHost implements Environment {
        @Override public Node node() { return node; }
        @Override public void onConnect(Node node) { }
        @Override public void onDisconnect(Node node) { }
        @Override public void onMessage(Message message) { }

        @Callback(doc = "function():number -- The projection distance in blocks.")
        public Object[] getDistance(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.getDistance(context, arguments);
        }

        @Callback(doc = "function(distance:number):number -- Sets the projection distance in blocks and returns the new value.")
        public Object[] setDistance(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.setDistance(context, arguments);
        }

        @Callback(doc = "function():string -- The direction the hologram is projected in.")
        public Object[] getDirection(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.getDirection(context, arguments);
        }

        @Callback(doc = "function(direction:string):boolean -- Points the projection up, down, north, south, east or west.")
        public Object[] setDirection(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.setDirection(context, arguments);
        }

        @Callback(doc = "function():string -- Either 'lines' or 'monitor'.")
        public Object[] getMode(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.getMode(context, arguments);
        }

        @Callback(doc = "function(mode:string):boolean -- Switches the hologram between 'lines' and 'monitor'.")
        public Object[] setMode(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.setMode(context, arguments);
        }

        @Callback(doc = "function():table -- Returns the current hologram lines, indexed from 1.")
        public Object[] getLines(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.getLines(context, arguments);
        }

        @Callback(doc = "function(line:number, text:string):boolean -- Sets a single 1-based hologram line.")
        public Object[] setLine(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.setLine(context, arguments);
        }

        @Callback(doc = "function(lines:table):boolean -- Replaces all hologram lines; an empty table clears them.")
        public Object[] setLines(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.setLines(context, arguments);
        }

        @Callback(doc = "function():boolean -- Clears all hologram lines.")
        public Object[] clearLines(Context context, Arguments arguments) {
            return OCProjectorBlockEntity.this.clearLines(context, arguments);
        }
    }

    /** Environment of the {@code keyboard} component the linked computer types through. */
    public final class KeyboardHost implements Environment {
        @Override public Node node() { return keyboardNode; }
        @Override public void onConnect(Node node) { }
        @Override public void onDisconnect(Node node) { }
        @Override public void onMessage(Message message) { }
    }
}
