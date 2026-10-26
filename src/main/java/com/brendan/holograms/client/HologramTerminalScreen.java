package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import com.brendan.holograms.network.TerminalInputPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * A typeable terminal for a hologram that a computer is drawing on.
 * <p>
 * It renders the projector's synced {@link TerminalBuffer} as a grid of cells and sends every key,
 * mouse and clipboard event to the server, where the OpenComputers integration forwards it to the
 * bound computer. ESC closes the screen; the hologram keeps showing the terminal afterwards.
 */
public final class HologramTerminalScreen extends Screen {
    /** Native font cell size; the grid grows in whole multiples of it to fill the window. */
    private static final int GLYPH_WIDTH = 6;
    private static final int GLYPH_HEIGHT = 9;
    private static final int MIN_CELL_SCALE = 1;
    private static final int MAX_CELL_SCALE = 4;
    private static final int TITLE_SPACE = 22;
    private static final int HINT_SPACE = 18;
    private static final int BORDER_COLOR = 0xFF4A4A4A;
    private static final int PANEL_COLOR = 0xFF141414;
    private static final int SCREEN_COLOR = 0xFF050505;
    private static final int LABEL_COLOR = 0xFFC8C8C8;

    private final BlockPos pos;
    /** GLFW key -> character that was reported to the computer, so the release matches the press. */
    private final Map<Integer, Character> pressedKeys = new HashMap<>();
    private int pendingKey = GLFW.GLFW_KEY_UNKNOWN;
    private boolean dragging;
    private boolean refreshRequested;
    private int gridLeft;
    private int gridTop;
    private int cellWidth = GLYPH_WIDTH;
    private int cellHeight = GLYPH_HEIGHT;
    private int cellScale = MIN_CELL_SCALE;
    private int titleY;
    private int hintY;

    public HologramTerminalScreen(BlockPos pos) {
        super(Component.translatable("screen.holosigns.terminal.title"));
        this.pos = pos;
    }

    private @Nullable ProjectorBlockEntity projector() {
        if (this.minecraft == null || this.minecraft.level == null) return null;
        return this.minecraft.level.getBlockEntity(this.pos) instanceof ProjectorBlockEntity projector
                ? projector : null;
    }

    @Override
    protected void init() {
        layout();
        // Ask the computer to repaint once. A shell that started before the projector was linked
        // has already drawn its prompt to nowhere, and binding does not replay that frame; this
        // resize notice makes the terminal client redraw its current buffer onto the panel.
        if (!this.refreshRequested) {
            this.refreshRequested = true;
            send(ProjectorBlockEntity.TerminalAction.REFRESH, '\0', 0, 0, 0, 0, "");
        }
    }

    private void layout() {
        ProjectorBlockEntity projector = projector();
        int columns = projector != null ? projector.getTerminal().getColumns() : TerminalBuffer.DEFAULT_COLUMNS;
        int rows = projector != null ? projector.getTerminal().getRows() : TerminalBuffer.DEFAULT_ROWS;

        // Reserve room for the title and hint, then grow the cell grid in whole font multiples
        // until it fills the window. Scaling the glyphs with the cells keeps the terminal reading
        // as a monitor pulled closer instead of a small box in the middle of a large screen.
        int availableHeight = Math.max(rows * GLYPH_HEIGHT, this.height - TITLE_SPACE - HINT_SPACE - 8);
        int availableWidth = Math.max(columns * GLYPH_WIDTH, this.width - 24);
        int scale = MIN_CELL_SCALE;
        for (int candidate = MAX_CELL_SCALE; candidate > MIN_CELL_SCALE; candidate--) {
            if (columns * GLYPH_WIDTH * candidate <= availableWidth
                    && rows * GLYPH_HEIGHT * candidate <= availableHeight) {
                scale = candidate;
                break;
            }
        }
        this.cellScale = scale;
        this.cellWidth = GLYPH_WIDTH * scale;
        this.cellHeight = GLYPH_HEIGHT * scale;

        int gridWidth = columns * this.cellWidth;
        int gridHeight = rows * this.cellHeight;
        this.gridLeft = Math.max(8, (this.width - gridWidth) / 2);
        int contentHeight = this.height - TITLE_SPACE - HINT_SPACE;
        this.gridTop = TITLE_SPACE + Math.max(0, (contentHeight - gridHeight) / 2);
        this.titleY = Math.max(4, this.gridTop - 13);
        this.hintY = Math.min(this.height - 11, this.gridTop + gridHeight + 7);
    }

    @Override
    public void tick() {
        ProjectorBlockEntity projector = projector();
        boolean outOfRange = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.distanceToSqr(
                        this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5) > 64.0 * 64.0;
        if (projector == null || !projector.isComputerLinked() || outOfRange) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        ProjectorBlockEntity projector = projector();
        if (projector == null) return;
        TerminalBuffer terminal = projector.getTerminal();
        layout();
        int columns = terminal.getColumns();
        int rows = terminal.getRows();
        int gridWidth = columns * this.cellWidth;
        int gridHeight = rows * this.cellHeight;

        // The same silver bezel and black face as the in-world hologram, so the screen reads as the
        // panel being pulled closer rather than as an unrelated menu.
        graphics.fill(this.gridLeft - 8, this.gridTop - 8, this.gridLeft + gridWidth + 8,
                this.gridTop + gridHeight + 8, BORDER_COLOR);
        graphics.fill(this.gridLeft - 6, this.gridTop - 6, this.gridLeft + gridWidth + 6,
                this.gridTop + gridHeight + 6, PANEL_COLOR);
        graphics.fill(this.gridLeft - 4, this.gridTop - 4, this.gridLeft + gridWidth + 4,
                this.gridTop + gridHeight + 4, SCREEN_COLOR);

        for (int row = 0; row < rows; row++) {
            int y = this.gridTop + row * this.cellHeight;
            for (int column = 0; column < columns; column++) {
                char character = (char) terminal.characterAt(column, row);
                if (character == ' ' || character == 0) continue;
                int color = 0xFF000000 | (terminal.foregroundAt(column, row) & 0xFFFFFF);
                int x = this.gridLeft + column * this.cellWidth;
                if (TerminalGlyphs.drawGui(graphics, character, x, y, this.cellWidth, this.cellHeight, color)) {
                    continue;
                }
                // One glyph per cell, at its natural size and centred in the cell. Stretching a word
                // to fill its cells smeared letters into each other and pushed box-drawing runs past
                // the panel, while natural word spacing left multi-space gaps between words.
                String glyph = String.valueOf(character);
                int glyphWidth = this.font.width(glyph);
                graphics.pose().pushMatrix();
                graphics.pose().translate(x, y);
                graphics.pose().scale(this.cellScale, this.cellScale);
                graphics.text(this.font, glyph, Math.max(0, (GLYPH_WIDTH - glyphWidth) / 2), 0, color, false);
                graphics.pose().popMatrix();
            }
        }

        graphics.text(this.font, this.title, this.gridLeft, this.titleY, LABEL_COLOR, false);
        if (this.hintY >= this.gridTop + gridHeight + 2) {
            graphics.text(this.font, Component.translatable("screen.holosigns.terminal.hint"),
                    this.gridLeft, this.hintY, LABEL_COLOR, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        ProjectorBlockEntity projector = projector();
        if (projector == null) return super.keyPressed(event);
        if (event.isEscape() || event.key() == GLFW.GLFW_KEY_F11) return super.keyPressed(event);

        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_V) {
            String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (clipboard != null && !clipboard.isEmpty()) {
                send(ProjectorBlockEntity.TerminalAction.CLIPBOARD, '\0', 0, 0, 0, 0,
                        clipboard.substring(0, Math.min(clipboard.length(), TerminalInputPayload.MAX_CLIPBOARD_LENGTH)));
            }
            return true;
        }

        int key = event.key();
        int code = glfwToLwjglKeyCode(key);
        if (event.hasControlDown() && !event.hasAltDown()) {
            char control = key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z
                    ? (char) (key - GLFW.GLFW_KEY_A + 1) : 0;
            press(key, control, code);
            return true;
        }
        if (event.hasControlDown()) {
            // AltGr arrives as Ctrl+Alt; the real glyph is delivered by charTyped.
            this.pendingKey = key;
            return true;
        }
        char special = specialCharacter(key);
        if (special != 0) {
            press(key, special, code);
            return true;
        }
        char numpad = numpadCharacter(key);
        if (numpad != 0) {
            press(key, numpad, code);
            return true;
        }
        if (isCharacterKey(key)) {
            char guessed = guessCharacter(key, event.hasShiftDown());
            if (guessed != 0) {
                press(key, guessed, code);
            } else {
                // Shifted symbols and layouts the key code cannot predict arrive in charTyped.
                this.pendingKey = key;
            }
            return true;
        }
        press(key, '\0', code);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        this.pendingKey = GLFW.GLFW_KEY_UNKNOWN;
        Character character = this.pressedKeys.remove(event.key());
        if (character != null) {
            send(ProjectorBlockEntity.TerminalAction.KEY_UP, character, glfwToLwjglKeyCode(event.key()),
                    0, 0, 0, "");
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int key = this.pendingKey;
        if (event.codepoint() >= 32 && key != GLFW.GLFW_KEY_UNKNOWN) {
            this.pendingKey = GLFW.GLFW_KEY_UNKNOWN;
            this.pressedKeys.put(key, (char) event.codepoint());
            send(ProjectorBlockEntity.TerminalAction.KEY_DOWN, (char) event.codepoint(),
                    glfwToLwjglKeyCode(key), 0, 0, 0, "");
        }
        return true;
    }

    private void press(int key, char character, int code) {
        this.pendingKey = GLFW.GLFW_KEY_UNKNOWN;
        this.pressedKeys.put(key, character);
        send(ProjectorBlockEntity.TerminalAction.KEY_DOWN, character, code, 0, 0, 0, "");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isOverGrid(event.x(), event.y()) || event.button() > 2) {
            return super.mouseClicked(event, doubleClick);
        }
        this.dragging = true;
        send(ProjectorBlockEntity.TerminalAction.MOUSE_DOWN, '\0', 0,
                cellX(event.x()), cellY(event.y()), event.button(), "");
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!this.dragging || event.button() > 2) return false;
        // Keep reporting while the pointer leaves the grid; the terminal clamps the cell itself.
        send(ProjectorBlockEntity.TerminalAction.MOUSE_DRAG, '\0', 0,
                cellX(event.x()), cellY(event.y()), event.button(), "");
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!this.dragging || event.button() > 2) return super.mouseReleased(event);
        this.dragging = false;
        send(ProjectorBlockEntity.TerminalAction.MOUSE_UP, '\0', 0,
                cellX(event.x()), cellY(event.y()), event.button(), "");
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isOverGrid(mouseX, mouseY) || scrollY == 0.0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        send(ProjectorBlockEntity.TerminalAction.MOUSE_SCROLL, '\0', 0,
                cellX(mouseX), cellY(mouseY), scrollY > 0.0 ? 1 : -1, "");
        return true;
    }

    @Override
    public void onClose() {
        for (Map.Entry<Integer, Character> entry : this.pressedKeys.entrySet()) {
            send(ProjectorBlockEntity.TerminalAction.KEY_UP, entry.getValue(),
                    glfwToLwjglKeyCode(entry.getKey()), 0, 0, 0, "");
        }
        this.pressedKeys.clear();
        super.onClose();
    }

    private void send(ProjectorBlockEntity.TerminalAction action, char character, int code,
                      double x, double y, int button, String text) {
        TerminalInputPayload.send(action, this.pos, character, code, x, y, button, text);
    }

    private boolean isOverGrid(double mouseX, double mouseY) {
        ProjectorBlockEntity projector = projector();
        if (projector == null) return false;
        int gridWidth = projector.getTerminal().getColumns() * this.cellWidth;
        int gridHeight = projector.getTerminal().getRows() * this.cellHeight;
        return mouseX >= this.gridLeft && mouseX < this.gridLeft + gridWidth
                && mouseY >= this.gridTop && mouseY < this.gridTop + gridHeight;
    }

    private double cellX(double mouseX) {
        ProjectorBlockEntity projector = projector();
        int columns = projector != null ? projector.getTerminal().getColumns() : TerminalBuffer.DEFAULT_COLUMNS;
        return Math.max(0.0, Math.min(columns - 1, (mouseX - this.gridLeft) / this.cellWidth));
    }

    private double cellY(double mouseY) {
        ProjectorBlockEntity projector = projector();
        int rows = projector != null ? projector.getTerminal().getRows() : TerminalBuffer.DEFAULT_ROWS;
        return Math.max(0.0, Math.min(rows - 1, (mouseY - this.gridTop) / this.cellHeight));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static char specialCharacter(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> '\b';
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> '\n';
            case GLFW.GLFW_KEY_TAB -> '\t';
            default -> 0;
        };
    }

    private static char numpadCharacter(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_KP_0 -> '0';
            case GLFW.GLFW_KEY_KP_1 -> '1';
            case GLFW.GLFW_KEY_KP_2 -> '2';
            case GLFW.GLFW_KEY_KP_3 -> '3';
            case GLFW.GLFW_KEY_KP_4 -> '4';
            case GLFW.GLFW_KEY_KP_5 -> '5';
            case GLFW.GLFW_KEY_KP_6 -> '6';
            case GLFW.GLFW_KEY_KP_7 -> '7';
            case GLFW.GLFW_KEY_KP_8 -> '8';
            case GLFW.GLFW_KEY_KP_9 -> '9';
            case GLFW.GLFW_KEY_KP_DECIMAL -> '.';
            case GLFW.GLFW_KEY_KP_DIVIDE -> '/';
            case GLFW.GLFW_KEY_KP_MULTIPLY -> '*';
            case GLFW.GLFW_KEY_KP_SUBTRACT -> '-';
            case GLFW.GLFW_KEY_KP_ADD -> '+';
            case GLFW.GLFW_KEY_KP_EQUAL -> '=';
            default -> 0;
        };
    }

    private static char guessCharacter(int key, boolean shift) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return (char) ((shift ? 'A' : 'a') + (key - GLFW.GLFW_KEY_A));
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && !shift) {
            return (char) ('0' + (key - GLFW.GLFW_KEY_0));
        }
        if (key == GLFW.GLFW_KEY_SPACE) return ' ';
        return 0;
    }

    private static boolean isCharacterKey(int key) {
        return (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z)
                || (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9)
                || key == GLFW.GLFW_KEY_SPACE
                || key == GLFW.GLFW_KEY_MINUS
                || key == GLFW.GLFW_KEY_EQUAL
                || key == GLFW.GLFW_KEY_LEFT_BRACKET
                || key == GLFW.GLFW_KEY_RIGHT_BRACKET
                || key == GLFW.GLFW_KEY_BACKSLASH
                || key == GLFW.GLFW_KEY_SEMICOLON
                || key == GLFW.GLFW_KEY_APOSTROPHE
                || key == GLFW.GLFW_KEY_COMMA
                || key == GLFW.GLFW_KEY_PERIOD
                || key == GLFW.GLFW_KEY_SLASH
                || key == GLFW.GLFW_KEY_GRAVE_ACCENT
                || key == GLFW.GLFW_KEY_WORLD_1
                || key == GLFW.GLFW_KEY_WORLD_2;
    }

    /**
     * OpenComputers hands the code straight to Lua as the second {@code key_down} argument, and its
     * own screen converts GLFW key codes to LWJGL ones first. The same table keeps programs that
     * match on codes (arrow keys, enter, function keys) working on the hologram.
     */
    private static int glfwToLwjglKeyCode(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_A -> 30;
            case GLFW.GLFW_KEY_B -> 48;
            case GLFW.GLFW_KEY_C -> 46;
            case GLFW.GLFW_KEY_D -> 32;
            case GLFW.GLFW_KEY_E -> 18;
            case GLFW.GLFW_KEY_F -> 33;
            case GLFW.GLFW_KEY_G -> 34;
            case GLFW.GLFW_KEY_H -> 35;
            case GLFW.GLFW_KEY_I -> 23;
            case GLFW.GLFW_KEY_J -> 36;
            case GLFW.GLFW_KEY_K -> 37;
            case GLFW.GLFW_KEY_L -> 38;
            case GLFW.GLFW_KEY_M -> 50;
            case GLFW.GLFW_KEY_N -> 49;
            case GLFW.GLFW_KEY_O -> 24;
            case GLFW.GLFW_KEY_P -> 25;
            case GLFW.GLFW_KEY_Q -> 16;
            case GLFW.GLFW_KEY_R -> 19;
            case GLFW.GLFW_KEY_S -> 31;
            case GLFW.GLFW_KEY_T -> 20;
            case GLFW.GLFW_KEY_U -> 22;
            case GLFW.GLFW_KEY_V -> 47;
            case GLFW.GLFW_KEY_W -> 17;
            case GLFW.GLFW_KEY_X -> 45;
            case GLFW.GLFW_KEY_Y -> 21;
            case GLFW.GLFW_KEY_Z -> 44;
            case GLFW.GLFW_KEY_0 -> 11;
            case GLFW.GLFW_KEY_1 -> 2;
            case GLFW.GLFW_KEY_2 -> 3;
            case GLFW.GLFW_KEY_3 -> 4;
            case GLFW.GLFW_KEY_4 -> 5;
            case GLFW.GLFW_KEY_5 -> 6;
            case GLFW.GLFW_KEY_6 -> 7;
            case GLFW.GLFW_KEY_7 -> 8;
            case GLFW.GLFW_KEY_8 -> 9;
            case GLFW.GLFW_KEY_9 -> 10;
            case GLFW.GLFW_KEY_MINUS -> 12;
            case GLFW.GLFW_KEY_EQUAL -> 13;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 26;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 27;
            case GLFW.GLFW_KEY_SEMICOLON -> 39;
            case GLFW.GLFW_KEY_APOSTROPHE -> 40;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 41;
            case GLFW.GLFW_KEY_BACKSLASH -> 43;
            case GLFW.GLFW_KEY_COMMA -> 51;
            case GLFW.GLFW_KEY_PERIOD -> 52;
            case GLFW.GLFW_KEY_SLASH -> 53;
            case GLFW.GLFW_KEY_SPACE -> 57;
            case GLFW.GLFW_KEY_TAB -> 15;
            case GLFW.GLFW_KEY_ENTER -> 28;
            case GLFW.GLFW_KEY_KP_ENTER -> 156;
            case GLFW.GLFW_KEY_BACKSPACE -> 14;
            case GLFW.GLFW_KEY_INSERT -> 210;
            case GLFW.GLFW_KEY_DELETE -> 211;
            case GLFW.GLFW_KEY_HOME -> 199;
            case GLFW.GLFW_KEY_END -> 207;
            case GLFW.GLFW_KEY_PAGE_UP -> 201;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 209;
            case GLFW.GLFW_KEY_UP -> 200;
            case GLFW.GLFW_KEY_DOWN -> 208;
            case GLFW.GLFW_KEY_LEFT -> 203;
            case GLFW.GLFW_KEY_RIGHT -> 205;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 42;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 54;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 29;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 157;
            case GLFW.GLFW_KEY_LEFT_ALT -> 56;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 184;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 219;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 220;
            case GLFW.GLFW_KEY_F1 -> 59;
            case GLFW.GLFW_KEY_F2 -> 60;
            case GLFW.GLFW_KEY_F3 -> 61;
            case GLFW.GLFW_KEY_F4 -> 62;
            case GLFW.GLFW_KEY_F5 -> 63;
            case GLFW.GLFW_KEY_F6 -> 64;
            case GLFW.GLFW_KEY_F7 -> 65;
            case GLFW.GLFW_KEY_F8 -> 66;
            case GLFW.GLFW_KEY_F9 -> 67;
            case GLFW.GLFW_KEY_F10 -> 68;
            case GLFW.GLFW_KEY_F11 -> 87;
            case GLFW.GLFW_KEY_F12 -> 88;
            case GLFW.GLFW_KEY_KP_0 -> 82;
            case GLFW.GLFW_KEY_KP_1 -> 79;
            case GLFW.GLFW_KEY_KP_2 -> 80;
            case GLFW.GLFW_KEY_KP_3 -> 81;
            case GLFW.GLFW_KEY_KP_4 -> 75;
            case GLFW.GLFW_KEY_KP_5 -> 76;
            case GLFW.GLFW_KEY_KP_6 -> 77;
            case GLFW.GLFW_KEY_KP_7 -> 71;
            case GLFW.GLFW_KEY_KP_8 -> 72;
            case GLFW.GLFW_KEY_KP_9 -> 73;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 83;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 181;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 55;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 74;
            case GLFW.GLFW_KEY_KP_ADD -> 78;
            case GLFW.GLFW_KEY_KP_EQUAL -> 141;
            case GLFW.GLFW_KEY_NUM_LOCK -> 69;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 58;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 70;
            case GLFW.GLFW_KEY_PAUSE -> 197;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> 183;
            default -> key;
        };
    }
}
