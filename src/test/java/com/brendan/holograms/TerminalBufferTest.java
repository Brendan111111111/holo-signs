package com.brendan.holograms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalBufferTest {
    @Test
    void gridGrowsWithHologramSize() {
        assertEquals(8, TerminalBuffer.columnsForSizePercent(25));
        assertEquals(3, TerminalBuffer.rowsForSizePercent(25));

        assertEquals(24, TerminalBuffer.columnsForSizePercent(100));
        assertEquals(10, TerminalBuffer.rowsForSizePercent(100));

        assertEquals(32, TerminalBuffer.columnsForSizePercent(300));
        assertEquals(12, TerminalBuffer.rowsForSizePercent(300));
    }

    @Test
    void gridStaysWithinTerminalLimits() {
        assertEquals(TerminalBuffer.MIN_COLUMNS, TerminalBuffer.columnsForSizePercent(-100));
        assertEquals(TerminalBuffer.MIN_ROWS, TerminalBuffer.rowsForSizePercent(0));
        assertEquals(TerminalBuffer.MIN_COLUMNS, TerminalBuffer.columnsForSizePercent(1));
        assertEquals(TerminalBuffer.MAX_COLUMNS, TerminalBuffer.columnsForSizePercent(400));
        assertEquals(TerminalBuffer.MAX_ROWS, TerminalBuffer.rowsForSizePercent(10_000));
    }

    @Test
    void terminalResizesToTheScaledGrid() {
        TerminalBuffer buffer = new TerminalBuffer();
        assertEquals(TerminalBuffer.DEFAULT_COLUMNS, buffer.getColumns());
        assertEquals(TerminalBuffer.DEFAULT_ROWS, buffer.getRows());

        buffer.setSize(TerminalBuffer.columnsForSizePercent(25), TerminalBuffer.rowsForSizePercent(25));
        assertEquals(8, buffer.getColumns());
        assertEquals(3, buffer.getRows());

        buffer.setSize(TerminalBuffer.columnsForSizePercent(300), TerminalBuffer.rowsForSizePercent(300));
        assertEquals(32, buffer.getColumns());
        assertEquals(12, buffer.getRows());

        // A computer may ask for a smaller grid, but never one below the terminal's minimum.
        buffer.setSize(1, 1);
        assertEquals(TerminalBuffer.MIN_COLUMNS, buffer.getColumns());
        assertEquals(TerminalBuffer.MIN_ROWS, buffer.getRows());
    }

    @Test
    void textWrittenIntoAScaledGridIsBoundedByItsDimensions() {
        TerminalBuffer buffer = new TerminalBuffer();
        buffer.setSize(TerminalBuffer.columnsForSizePercent(25), TerminalBuffer.rowsForSizePercent(25));

        buffer.setCell(0, 0, 'O', TerminalBuffer.DEFAULT_FOREGROUND, TerminalBuffer.DEFAULT_BACKGROUND);
        buffer.setCell(7, 2, 'X', TerminalBuffer.DEFAULT_FOREGROUND, TerminalBuffer.DEFAULT_BACKGROUND);

        List<String> rows = buffer.textRows();
        assertEquals(3, rows.size());
        assertEquals("O       ", rows.get(0));
        assertEquals("       X", rows.get(2));
        assertTrue(buffer.hasText());
    }
}
