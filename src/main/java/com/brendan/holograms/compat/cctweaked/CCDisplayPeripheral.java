package com.brendan.holograms.compat.cctweaked;

import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.GenericPeripheral;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class CCDisplayPeripheral implements GenericPeripheral {
    @Override
    public String id() { return Identifier.fromNamespaceAndPath(HoloSigns.MOD_ID, "projector").toString(); }

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
}
