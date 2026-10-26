package com.brendan.holograms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Draws the terminal cells the bitmap font cannot represent properly.
 * <p>
 * Box-drawing characters are not in the default font, so they fall back to the 8x16 unicode font
 * whose glyphs are wider and taller than a terminal cell. Rendered in a cell they overlap the
 * neighbours, which showed up as bars bleeding past the panel and lines running through words.
 * Drawing the frame segments as exact rectangles instead makes every line meet its neighbours at
 * the cell edges, whatever size the cell is.
 */
public final class TerminalGlyphs {
    private static final int NONE = 0;
    private static final int SINGLE = 1;
    private static final int DOUBLE = 2;
    private static final int HEAVY = 3;
    private static final int LEFT = 0;
    private static final int RIGHT = 2;
    private static final int UP = 4;
    private static final int DOWN = 6;

    private TerminalGlyphs() {
    }

    /** True when {@link #drawGui} or {@link #drawWorld} would draw the character as lines. */
    public static boolean hasLines(char character) {
        return edges(character) >= 0;
    }

    /** Draws one box-drawing cell into a GUI grid cell; returns false for every other character. */
    public static boolean drawGui(GuiGraphicsExtractor graphics, char character, int x, int y,
                                  int width, int height, int color) {
        int edges = edges(character);
        if (edges < 0) return false;
        int thickness = Math.max(1, Math.round(height / 9.0F));
        int separation = Math.max(thickness + 1, Math.round(height * 0.22F));
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        guiHorizontal(graphics, x, centerX, centerY, styleAt(edges, LEFT), thickness, separation, color);
        guiHorizontal(graphics, centerX, x + width, centerY, styleAt(edges, RIGHT), thickness, separation, color);
        guiVertical(graphics, centerX, y, centerY, styleAt(edges, UP), thickness, separation, color);
        guiVertical(graphics, centerX, centerY, y + height, styleAt(edges, DOWN), thickness, separation, color);
        return true;
    }

    /**
     * Draws one box-drawing cell into a text-order collector, so the segments land in front of the
     * already submitted panel without any coplanar depth fighting.
     */
    public static boolean drawWorld(PoseStack poseStack, OrderedSubmitNodeCollector collector, char character,
                                    float x, float y, float width, float height, int color) {
        int edges = edges(character);
        if (edges < 0) return false;
        float thickness = Math.max(0.75F, height / 9.0F);
        float separation = Math.max(thickness * 1.6F, height * 0.22F);
        float centerX = x + width / 2.0F;
        float centerY = y + height / 2.0F;
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
            worldHorizontal(pose, buffer, x, centerX, centerY, styleAt(edges, LEFT), thickness, separation, color);
            worldHorizontal(pose, buffer, centerX, x + width, centerY, styleAt(edges, RIGHT), thickness, separation, color);
            worldVertical(pose, buffer, centerX, y, centerY, styleAt(edges, UP), thickness, separation, color);
            worldVertical(pose, buffer, centerX, centerY, y + height, styleAt(edges, DOWN), thickness, separation, color);
        });
        return true;
    }

    private static void guiHorizontal(GuiGraphicsExtractor graphics, int from, int to, int centerY, int style,
                                      int thickness, int separation, int color) {
        switch (style) {
            case SINGLE -> graphics.fill(from, centerY - thickness / 2, to, centerY - thickness / 2 + thickness, color);
            case HEAVY -> {
                int heavy = thickness * 2;
                graphics.fill(from, centerY - heavy / 2, to, centerY - heavy / 2 + heavy, color);
            }
            case DOUBLE -> {
                int first = centerY - separation - thickness / 2;
                int second = centerY + separation - thickness / 2;
                graphics.fill(from, first, to, first + thickness, color);
                graphics.fill(from, second, to, second + thickness, color);
            }
            default -> {
            }
        }
    }

    private static void guiVertical(GuiGraphicsExtractor graphics, int centerX, int from, int to, int style,
                                    int thickness, int separation, int color) {
        switch (style) {
            case SINGLE -> graphics.fill(centerX - thickness / 2, from, centerX - thickness / 2 + thickness, to, color);
            case HEAVY -> {
                int heavy = thickness * 2;
                graphics.fill(centerX - heavy / 2, from, centerX - heavy / 2 + heavy, to, color);
            }
            case DOUBLE -> {
                int first = centerX - separation - thickness / 2;
                int second = centerX + separation - thickness / 2;
                graphics.fill(first, from, first + thickness, to, color);
                graphics.fill(second, from, second + thickness, to, color);
            }
            default -> {
            }
        }
    }

    private static void worldHorizontal(PoseStack.Pose pose, VertexConsumer buffer, float from, float to, float centerY,
                                        int style, float thickness, float separation, int color) {
        float half = thickness / 2.0F;
        switch (style) {
            case SINGLE -> quad(pose, buffer, from, centerY - half, to, centerY + half, color);
            case HEAVY -> quad(pose, buffer, from, centerY - thickness, to, centerY + thickness, color);
            case DOUBLE -> {
                quad(pose, buffer, from, centerY - separation - half, to, centerY - separation + half, color);
                quad(pose, buffer, from, centerY + separation - half, to, centerY + separation + half, color);
            }
            default -> {
            }
        }
    }

    private static void worldVertical(PoseStack.Pose pose, VertexConsumer buffer, float centerX, float from, float to,
                                      int style, float thickness, float separation, int color) {
        float half = thickness / 2.0F;
        switch (style) {
            case SINGLE -> quad(pose, buffer, centerX - half, from, centerX + half, to, color);
            case HEAVY -> quad(pose, buffer, centerX - thickness, from, centerX + thickness, to, color);
            case DOUBLE -> {
                quad(pose, buffer, centerX - separation - half, from, centerX - separation + half, to, color);
                quad(pose, buffer, centerX + separation - half, from, centerX + separation + half, to, color);
            }
            default -> {
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float minX, float minY, float maxX, float maxY,
                             int color) {
        buffer.addVertex(pose, minX, minY, 0.0F).setColor(color);
        buffer.addVertex(pose, minX, maxY, 0.0F).setColor(color);
        buffer.addVertex(pose, maxX, maxY, 0.0F).setColor(color);
        buffer.addVertex(pose, maxX, minY, 0.0F).setColor(color);
    }

    private static int styleAt(int edges, int shift) {
        return (edges >> shift) & 3;
    }

    private static int code(int left, int right, int up, int down) {
        return left | (right << RIGHT) | (up << UP) | (down << DOWN);
    }

    /** Four two-bit styles (left, right, up, down), or -1 when the character is not a frame line. */
    private static int edges(char character) {
        return switch (character) {
            // Single lines, the frames OpenOS draws by default.
            case '\u2500' -> code(SINGLE, SINGLE, NONE, NONE);   // ─
            case '\u2502' -> code(NONE, NONE, SINGLE, SINGLE);   // │
            case '\u250C' -> code(NONE, SINGLE, NONE, SINGLE);   // ┌
            case '\u2510' -> code(SINGLE, NONE, NONE, SINGLE);   // ┐
            case '\u2514' -> code(NONE, SINGLE, SINGLE, NONE);   // └
            case '\u2518' -> code(SINGLE, NONE, SINGLE, NONE);   // ┘
            case '\u251C' -> code(NONE, SINGLE, SINGLE, SINGLE); // ├
            case '\u2524' -> code(SINGLE, NONE, SINGLE, SINGLE); // ┤
            case '\u252C' -> code(SINGLE, SINGLE, NONE, SINGLE); // ┬
            case '\u2534' -> code(SINGLE, SINGLE, SINGLE, NONE); // ┴
            case '\u253C' -> code(SINGLE, SINGLE, SINGLE, SINGLE); // ┼
            // Heavy lines.
            case '\u2501' -> code(HEAVY, HEAVY, NONE, NONE);     // ━
            case '\u2503' -> code(NONE, NONE, HEAVY, HEAVY);     // ┃
            case '\u250F' -> code(NONE, HEAVY, NONE, HEAVY);     // ┏
            case '\u2513' -> code(HEAVY, NONE, NONE, HEAVY);     // ┓
            case '\u2517' -> code(NONE, HEAVY, HEAVY, NONE);     // ┗
            case '\u251B' -> code(HEAVY, NONE, HEAVY, NONE);     // ┛
            case '\u2523' -> code(NONE, HEAVY, HEAVY, HEAVY);    // ┣
            case '\u252B' -> code(HEAVY, NONE, HEAVY, HEAVY);    // ┫
            case '\u2533' -> code(HEAVY, HEAVY, NONE, HEAVY);    // ┳
            case '\u253B' -> code(HEAVY, HEAVY, HEAVY, NONE);    // ┻
            case '\u254B' -> code(HEAVY, HEAVY, HEAVY, HEAVY);   // ╋
            // Rounded single corners.
            case '\u256D' -> code(NONE, SINGLE, SINGLE, NONE);   // ╭
            case '\u256E' -> code(SINGLE, NONE, SINGLE, NONE);   // ╮
            case '\u256F' -> code(SINGLE, NONE, NONE, SINGLE);   // ╯
            case '\u2570' -> code(NONE, SINGLE, NONE, SINGLE);   // ╰
            // Double frames, including the double-top banner frame OpenOS boots with.
            case '\u2550' -> code(DOUBLE, DOUBLE, NONE, NONE);   // ═
            case '\u2551' -> code(NONE, NONE, DOUBLE, DOUBLE);   // ║
            case '\u2554' -> code(NONE, DOUBLE, NONE, DOUBLE);   // ╔
            case '\u2557' -> code(DOUBLE, NONE, NONE, DOUBLE);   // ╗
            case '\u255A' -> code(NONE, DOUBLE, DOUBLE, NONE);   // ╚
            case '\u255D' -> code(DOUBLE, NONE, DOUBLE, NONE);   // ╝
            case '\u2560' -> code(NONE, DOUBLE, DOUBLE, DOUBLE); // ╠
            case '\u2563' -> code(DOUBLE, NONE, DOUBLE, DOUBLE); // ╣
            case '\u2566' -> code(DOUBLE, DOUBLE, NONE, DOUBLE); // ╦
            case '\u2569' -> code(DOUBLE, DOUBLE, DOUBLE, NONE); // ╩
            case '\u256C' -> code(DOUBLE, DOUBLE, DOUBLE, DOUBLE); // ╬
            case '\u2552' -> code(NONE, DOUBLE, NONE, SINGLE);   // ╒
            case '\u2555' -> code(DOUBLE, NONE, NONE, SINGLE);   // ╕
            case '\u2558' -> code(NONE, DOUBLE, SINGLE, NONE);   // ╘
            case '\u255B' -> code(DOUBLE, NONE, SINGLE, NONE);   // ╛
            case '\u2553' -> code(NONE, SINGLE, NONE, DOUBLE);   // ╓
            case '\u2556' -> code(SINGLE, NONE, NONE, DOUBLE);   // ╖
            case '\u2559' -> code(NONE, SINGLE, DOUBLE, NONE);   // ╙
            case '\u255C' -> code(SINGLE, NONE, DOUBLE, NONE);   // ╜
            case '\u255E' -> code(NONE, DOUBLE, SINGLE, SINGLE); // ╞
            case '\u2561' -> code(DOUBLE, NONE, SINGLE, SINGLE); // ╡
            case '\u255F' -> code(NONE, SINGLE, DOUBLE, DOUBLE); // ╟
            case '\u2562' -> code(SINGLE, NONE, DOUBLE, DOUBLE); // ╢
            case '\u2564' -> code(DOUBLE, DOUBLE, NONE, SINGLE); // ╤
            case '\u2567' -> code(DOUBLE, DOUBLE, SINGLE, NONE); // ╧
            case '\u2565' -> code(SINGLE, SINGLE, NONE, DOUBLE); // ╥
            case '\u2568' -> code(SINGLE, SINGLE, DOUBLE, NONE); // ╨
            case '\u256A' -> code(DOUBLE, DOUBLE, SINGLE, SINGLE); // ╪
            case '\u256B' -> code(SINGLE, SINGLE, DOUBLE, DOUBLE); // ╫
            default -> -1;
        };
    }
}
