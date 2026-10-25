package com.brendan.holograms;

/** What the hologram currently shows: authored sign lines, or a computer's terminal grid. */
public enum DisplayMode {
    LINES,
    MONITOR;

    public static DisplayMode byName(String name) {
        if (name == null) return LINES;
        for (DisplayMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return LINES;
    }

    public String lowerName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
