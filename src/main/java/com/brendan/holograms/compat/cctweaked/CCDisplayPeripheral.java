package com.brendan.holograms.compat.cctweaked;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.GenericPeripheral;
import dan200.computercraft.api.peripheral.PeripheralType;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Projector peripheral for CC:Tweaked.
 * <p>
 * The projector reports itself as a {@code monitor}, so Lua programs can treat the hologram like
 * any other screen: {@code peripheral.find("monitor")}, then {@code write}, {@code setCursorPos},
 * {@code setTextColour} and friends. The previous sign-line methods remain available under the
 * same peripheral, and {@code holosigns:projector} is advertised as an additional type.
 */
public final class CCDisplayPeripheral implements GenericPeripheral {
    /** CC:Tweaked's default sixteen-color monitor palette. */
    private static final int[] PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x191919
    };
    private static final int WHITE = 0x1;
    private static final int BLACK = 0x8000;

    private int textColour = WHITE;
    private int backgroundColour = BLACK;

    @Override
    public String id() { return Identifier.fromNamespaceAndPath(HoloSigns.MOD_ID, "projector").toString(); }

    @Override
    public PeripheralType getType() {
        return PeripheralType.ofType("monitor", "holosigns:projector");
    }

    // ---------------------------------------------------------------- monitor API

    @LuaFunction(mainThread = true)
    public final Object[] getSize(ProjectorBlockEntity projector) {
        TerminalBuffer terminal = projector.getTerminal();
        return new Object[]{terminal.getColumns(), terminal.getRows()};
    }

    @LuaFunction(mainThread = true)
    public final Object[] getCursorPos(ProjectorBlockEntity projector) {
        TerminalBuffer terminal = projector.getTerminal();
        return new Object[]{terminal.getCursorColumn() + 1, terminal.getCursorRow() + 1};
    }

    @LuaFunction(mainThread = true)
    public final void setCursorPos(ProjectorBlockEntity projector, int x, int y) {
        TerminalBuffer terminal = projector.getTerminal();
        terminal.setCursor(Math.max(1, Math.min(terminal.getColumns(), x)) - 1,
                Math.max(1, Math.min(terminal.getRows(), y)) - 1);
    }

    @LuaFunction(mainThread = true)
    public final void write(ProjectorBlockEntity projector, String text) {
        TerminalBuffer terminal = projector.getTerminal();
        applyColours(terminal);
        terminal.write(text);
    }

    @LuaFunction(mainThread = true)
    public final void clear(ProjectorBlockEntity projector) {
        TerminalBuffer terminal = projector.getTerminal();
        applyColours(terminal);
        terminal.clear();
    }

    @LuaFunction(mainThread = true)
    public final void clearLine(ProjectorBlockEntity projector) {
        TerminalBuffer terminal = projector.getTerminal();
        applyColours(terminal);
        terminal.clearLine();
    }

    @LuaFunction(mainThread = true)
    public final void scroll(ProjectorBlockEntity projector, int lines) {
        TerminalBuffer terminal = projector.getTerminal();
        applyColours(terminal);
        terminal.scroll(lines);
    }

    @LuaFunction(mainThread = true)
    public final void setTextColour(ProjectorBlockEntity projector, int colour) throws LuaException {
        textColour = checkColour(colour);
        applyColours(projector.getTerminal());
    }

    @LuaFunction(mainThread = true)
    public final int getTextColour(ProjectorBlockEntity projector) {
        return textColour;
    }

    @LuaFunction(mainThread = true)
    public final void setBackgroundColour(ProjectorBlockEntity projector, int colour) throws LuaException {
        backgroundColour = checkColour(colour);
        applyColours(projector.getTerminal());
    }

    @LuaFunction(mainThread = true)
    public final int getBackgroundColour(ProjectorBlockEntity projector) {
        return backgroundColour;
    }

    @LuaFunction(mainThread = true)
    public final boolean isColour(ProjectorBlockEntity projector) {
        return true;
    }

    @LuaFunction(mainThread = true)
    public final boolean isColor(ProjectorBlockEntity projector) {
        return true;
    }

    @LuaFunction(mainThread = true)
    public final void blit(ProjectorBlockEntity projector, String text, int textColour, int backgroundColour) throws LuaException {
        TerminalBuffer terminal = projector.getTerminal();
        terminal.blit(text, colourToRgb(checkColour(textColour)), colourToRgb(checkColour(backgroundColour)));
    }

    @LuaFunction(mainThread = true)
    public final double getTextScale(ProjectorBlockEntity projector) {
        return 0.5;
    }

    @LuaFunction(mainThread = true)
    public final double setTextScale(ProjectorBlockEntity projector, double scale) {
        // The hologram scales with the projector's distance; there is no separate text scale.
        return 0.5;
    }

    // ---------------------------------------------------------------- projector API

    @LuaFunction(mainThread = true)
    public final int getDistance(ProjectorBlockEntity projector) { return projector.getDistance(); }

    @LuaFunction(mainThread = true)
    public final void setDistance(ProjectorBlockEntity projector, int distance) {
        projector.setDistance(distance);
    }

    @LuaFunction(mainThread = true)
    public final String getDirection(ProjectorBlockEntity projector) { return projector.getFacing().getSerializedName(); }

    @LuaFunction(mainThread = true)
    public final boolean setDirection(ProjectorBlockEntity projector, String direction) throws LuaException {
        Direction parsed = Direction.byName(direction == null ? "" : direction.toLowerCase(java.util.Locale.ROOT));
        if (parsed == null) {
            throw new LuaException("direction must be one of up, down, north, south, east, west");
        }
        return projector.setFacing(parsed);
    }

    @LuaFunction(mainThread = true)
    public final String getMode(ProjectorBlockEntity projector) { return projector.getMode().lowerName(); }

    @LuaFunction(mainThread = true)
    public final void setMode(ProjectorBlockEntity projector, String mode) {
        projector.setMode(DisplayMode.byName(mode));
    }

    @LuaFunction(mainThread = true)
    public final List<String> getLines(ProjectorBlockEntity projector) { return projector.getLines(); }

    @LuaFunction(mainThread = true)
    public final void setLine(ProjectorBlockEntity projector, int line, String text) throws LuaException {
        if (line < 1 || line > ProjectorBlockEntity.MAX_LINES) throw new LuaException("line must be between 1 and " + ProjectorBlockEntity.MAX_LINES);
        if (text.codePointCount(0, text.length()) > ProjectorBlockEntity.MAX_LINE_LENGTH) throw new LuaException("text must be at most " + ProjectorBlockEntity.MAX_LINE_LENGTH + " characters");
        projector.setLine(line - 1, text);
    }

    @LuaFunction(mainThread = true)
    public final void setLines(ProjectorBlockEntity projector, List<String> lines) throws LuaException {
        if (lines.size() > ProjectorBlockEntity.MAX_LINES) throw new LuaException("at most " + ProjectorBlockEntity.MAX_LINES + " lines are supported");
        if (lines.stream().anyMatch(line -> line.codePointCount(0, line.length()) > ProjectorBlockEntity.MAX_LINE_LENGTH)) throw new LuaException("each line must be at most " + ProjectorBlockEntity.MAX_LINE_LENGTH + " characters");
        projector.setLines(lines);
    }

    @LuaFunction(mainThread = true)
    public final void clearLines(ProjectorBlockEntity projector) { projector.clearLines(); }

    // ---------------------------------------------------------------- helpers

    private void applyColours(TerminalBuffer terminal) {
        terminal.setActiveColors(colourToRgb(textColour), colourToRgb(backgroundColour));
    }

    private static int checkColour(int colour) throws LuaException {
        if (colour <= 0 || colour > 0x8000 || Integer.bitCount(colour) != 1) {
            throw new LuaException("colour must be one of the sixteen computer colours");
        }
        return colour;
    }

    private static int colourToRgb(int colour) {
        int index = Integer.numberOfTrailingZeros(colour);
        return index >= 0 && index < PALETTE.length ? PALETTE[index] : 0xFFFFFF;
    }
}
