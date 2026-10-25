package com.brendan.holograms;

import java.util.ArrayList;
import java.util.List;

/**
 * Character grid shared by the hologram display and the computer integrations.
 * <p>
 * It behaves like the terminal of a monitor: text is written at a cursor, wraps at the end of a
 * row, scrolls when it reaches the bottom and remembers a foreground and background color per
 * cell. Colors are stored as packed 0xRRGGBB values; the computer adapters translate their own
 * color representations into these when writing.
 */
public final class TerminalBuffer {
    public static final int MIN_COLUMNS = 8;
    public static final int MIN_ROWS = 3;
    public static final int MAX_COLUMNS = 32;
    public static final int MAX_ROWS = 12;
    public static final int DEFAULT_COLUMNS = 24;
    public static final int DEFAULT_ROWS = 10;

    public static final int DEFAULT_FOREGROUND = 0xFFFFFF;
    public static final int DEFAULT_BACKGROUND = 0x000000;

    private int columns;
    private int rows;
    private char[] characters;
    private int[] foreground;
    private int[] background;
    private int cursorColumn;
    private int cursorRow;
    private int activeForeground = DEFAULT_FOREGROUND;
    private int activeBackground = DEFAULT_BACKGROUND;
    private boolean cursorBlink;
    private Runnable onChanged = () -> {};

    public TerminalBuffer() {
        allocate(DEFAULT_COLUMNS, DEFAULT_ROWS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void allocate(int newColumns, int newRows) {
        this.columns = clamp(newColumns, MIN_COLUMNS, MAX_COLUMNS);
        this.rows = clamp(newRows, MIN_ROWS, MAX_ROWS);
        int cells = this.columns * this.rows;
        this.characters = new char[cells];
        this.foreground = new int[cells];
        this.background = new int[cells];
        this.cursorColumn = 0;
        this.cursorRow = 0;
        fillRaw(0, 0, this.columns, this.rows, ' ', activeForeground, activeBackground);
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    public int getCursorColumn() {
        return cursorColumn;
    }

    public int getCursorRow() {
        return cursorRow;
    }

    public int getActiveForeground() {
        return activeForeground;
    }

    public int getActiveBackground() {
        return activeBackground;
    }

    public boolean isCursorBlink() {
        return cursorBlink;
    }

    public void setCursorBlink(boolean blink) {
        if (cursorBlink == blink) return;
        cursorBlink = blink;
        changed();
    }

    public void setActiveColors(int foregroundColor, int backgroundColor) {
        this.activeForeground = foregroundColor & 0xFFFFFF;
        this.activeBackground = backgroundColor & 0xFFFFFF;
    }

    public boolean setSize(int newColumns, int newRows) {
        int clampedColumns = clamp(newColumns, MIN_COLUMNS, MAX_COLUMNS);
        int clampedRows = clamp(newRows, MIN_ROWS, MAX_ROWS);
        if (clampedColumns == columns && clampedRows == rows) return false;

        char[] oldCharacters = characters;
        int[] oldForeground = foreground;
        int[] oldBackground = background;
        int oldColumns = columns;
        int oldRows = rows;

        allocate(clampedColumns, clampedRows);

        int copyColumns = Math.min(oldColumns, columns);
        int copyRows = Math.min(oldRows, rows);
        for (int row = 0; row < copyRows; row++) {
            for (int column = 0; column < copyColumns; column++) {
                int from = row * oldColumns + column;
                int to = row * columns + column;
                characters[to] = oldCharacters[from];
                foreground[to] = oldForeground[from];
                background[to] = oldBackground[from];
            }
        }
        cursorColumn = Math.min(cursorColumn, columns - 1);
        cursorRow = Math.min(cursorRow, rows - 1);
        changed();
        return true;
    }

    public void write(String text) {
        if (text == null || text.isEmpty()) return;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                newLineRaw();
            } else if (codePoint == '\r') {
                cursorColumn = 0;
            } else {
                if (cursorColumn >= columns) {
                    cursorColumn = 0;
                    newLineRaw();
                }
                int cell = cursorRow * columns + cursorColumn;
                characters[cell] = Character.isBmpCodePoint(codePoint) ? (char) codePoint : '?';
                foreground[cell] = activeForeground;
                background[cell] = activeBackground;
                cursorColumn++;
            }
        }
        changed();
    }

    private void newLineRaw() {
        cursorColumn = 0;
        cursorRow++;
        if (cursorRow >= rows) {
            scrollRaw(1);
            cursorRow = rows - 1;
        }
    }

    public void newLine() {
        newLineRaw();
        changed();
    }

    public void setCursor(int column, int row) {
        cursorColumn = clamp(column, 0, columns - 1);
        cursorRow = clamp(row, 0, rows - 1);
        changed();
    }

    public void clear() {
        fillRaw(0, 0, columns, rows, ' ', activeForeground, activeBackground);
        cursorColumn = 0;
        cursorRow = 0;
        changed();
    }

    public void clearLine() {
        fillRaw(cursorColumn, cursorRow, columns - cursorColumn, 1, ' ', activeForeground, activeBackground);
        changed();
    }

    public void scroll(int lines) {
        if (lines == 0) return;
        if (lines > 0) {
            scrollRaw(Math.min(lines, rows));
        } else {
            scrollDownRaw(Math.min(-lines, rows));
        }
        changed();
    }

    private void scrollRaw(int lines) {
        if (lines <= 0) return;
        if (lines >= rows) {
            fillRaw(0, 0, columns, rows, ' ', activeForeground, activeBackground);
            return;
        }
        int shift = lines * columns;
        System.arraycopy(characters, shift, characters, 0, characters.length - shift);
        System.arraycopy(foreground, shift, foreground, 0, foreground.length - shift);
        System.arraycopy(background, shift, background, 0, background.length - shift);
        fillRaw(0, rows - lines, columns, lines, ' ', activeForeground, activeBackground);
    }

    private void scrollDownRaw(int lines) {
        if (lines <= 0) return;
        if (lines >= rows) {
            fillRaw(0, 0, columns, rows, ' ', activeForeground, activeBackground);
            return;
        }
        int shift = lines * columns;
        System.arraycopy(characters, 0, characters, shift, characters.length - shift);
        System.arraycopy(foreground, 0, foreground, shift, foreground.length - shift);
        System.arraycopy(background, 0, background, shift, background.length - shift);
        fillRaw(0, 0, columns, lines, ' ', activeForeground, activeBackground);
    }

    public void setCell(int column, int row, char value, int foregroundColor, int backgroundColor) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) return;
        int cell = row * columns + column;
        characters[cell] = value;
        foreground[cell] = foregroundColor & 0xFFFFFF;
        background[cell] = backgroundColor & 0xFFFFFF;
        changed();
    }

    public void fill(int column, int row, int width, int height, char value, int foregroundColor, int backgroundColor) {
        fillRaw(column, row, width, height, value, foregroundColor & 0xFFFFFF, backgroundColor & 0xFFFFFF);
        changed();
    }

    public void fillActive(int column, int row, int width, int height, char value) {
        fill(column, row, width, height, value, activeForeground, activeBackground);
    }

    private void fillRaw(int column, int row, int width, int height, char value, int foregroundColor, int backgroundColor) {
        int startColumn = Math.max(0, column);
        int startRow = Math.max(0, row);
        int endColumn = Math.min(columns, column + width);
        int endRow = Math.min(rows, row + height);
        for (int currentRow = startRow; currentRow < endRow; currentRow++) {
            for (int currentColumn = startColumn; currentColumn < endColumn; currentColumn++) {
                int cell = currentRow * columns + currentColumn;
                characters[cell] = value;
                foreground[cell] = foregroundColor;
                background[cell] = backgroundColor;
            }
        }
    }

    public void copy(int column, int row, int width, int height, int horizontalTranslation, int verticalTranslation) {
        int startColumn = Math.max(0, column);
        int startRow = Math.max(0, row);
        int endColumn = Math.min(columns, column + Math.max(0, width));
        int endRow = Math.min(rows, row + Math.max(0, height));
        if (startColumn >= endColumn || startRow >= endRow) return;

        int copyWidth = endColumn - startColumn;
        int copyHeight = endRow - startRow;
        char[] copiedCharacters = new char[copyWidth * copyHeight];
        int[] copiedForeground = new int[copyWidth * copyHeight];
        int[] copiedBackground = new int[copyWidth * copyHeight];
        for (int currentRow = 0; currentRow < copyHeight; currentRow++) {
            for (int currentColumn = 0; currentColumn < copyWidth; currentColumn++) {
                int from = (startRow + currentRow) * columns + startColumn + currentColumn;
                int to = currentRow * copyWidth + currentColumn;
                copiedCharacters[to] = characters[from];
                copiedForeground[to] = foreground[from];
                copiedBackground[to] = background[from];
            }
        }

        for (int currentRow = 0; currentRow < copyHeight; currentRow++) {
            int targetRow = startRow + currentRow + verticalTranslation;
            if (targetRow < 0 || targetRow >= rows) continue;
            for (int currentColumn = 0; currentColumn < copyWidth; currentColumn++) {
                int targetColumn = startColumn + currentColumn + horizontalTranslation;
                if (targetColumn < 0 || targetColumn >= columns) continue;
                int from = currentRow * copyWidth + currentColumn;
                int to = targetRow * columns + targetColumn;
                characters[to] = copiedCharacters[from];
                foreground[to] = copiedForeground[from];
                background[to] = copiedBackground[from];
            }
        }
        changed();
    }

    public void blit(String pattern, int foregroundColor, int backgroundColor) {
        if (pattern == null || pattern.isEmpty()) {
            fill(0, 0, columns, rows, ' ', foregroundColor, backgroundColor);
            return;
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int cell = row * columns + column;
                characters[cell] = pattern.charAt((row * columns + column) % pattern.length());
                foreground[cell] = foregroundColor & 0xFFFFFF;
                background[cell] = backgroundColor & 0xFFFFFF;
            }
        }
        changed();
    }

    public int characterAt(int column, int row) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) return ' ';
        return characters[row * columns + column];
    }

    public int foregroundAt(int column, int row) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) return activeForeground;
        return foreground[row * columns + column];
    }

    public int backgroundAt(int column, int row) {
        if (column < 0 || column >= columns || row < 0 || row >= rows) return activeBackground;
        return background[row * columns + column];
    }

    public boolean hasText() {
        for (char character : characters) {
            if (character != ' ') return true;
        }
        return false;
    }

    public List<String> textRows() {
        List<String> result = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            result.add(new String(characters, row * columns, columns));
        }
        return result;
    }

    public int[] foregroundData() {
        return foreground.clone();
    }

    public int[] backgroundData() {
        return background.clone();
    }

    /**
     * Restores a previously saved terminal. Invalid data falls back to a blank buffer of the
     * requested size instead of throwing, so a corrupted block entity cannot break chunk loading.
     */
    public void restore(int savedColumns, int savedRows, List<String> savedText, int[] savedForeground,
                        int[] savedBackground, int savedCursorColumn, int savedCursorRow,
                        int savedActiveForeground, int savedActiveBackground) {
        activeForeground = (savedActiveForeground & 0xFFFFFF);
        activeBackground = (savedActiveBackground & 0xFFFFFF);
        allocate(savedColumns, savedRows);
        int cells = columns * rows;
        boolean validColors = savedForeground != null && savedBackground != null
                && savedForeground.length >= cells && savedBackground.length >= cells;

        for (int row = 0; row < rows; row++) {
            String text = savedText != null && row < savedText.size() ? savedText.get(row) : "";
            for (int column = 0; column < columns; column++) {
                int cell = row * columns + column;
                characters[cell] = column < text.length() ? text.charAt(column) : ' ';
                if (validColors) {
                    foreground[cell] = savedForeground[cell] & 0xFFFFFF;
                    background[cell] = savedBackground[cell] & 0xFFFFFF;
                }
            }
        }
        cursorColumn = clamp(savedCursorColumn, 0, columns - 1);
        cursorRow = clamp(savedCursorRow, 0, rows - 1);
        changed();
    }

    private void changed() {
        onChanged.run();
    }
}
