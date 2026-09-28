package com.example.elink.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Tiny pixel-art primitives: filled rectangles, 1-pixel outlines, dotted dividers and a "sparkle"
 * pattern. Everything snaps to the integer grid because Minecraft's bitmap font is 8×9; non-aligned
 * lines look soft and muddy.
 */
public final class PixelShapes {
    private PixelShapes() {}

    /** Fills a solid rectangle in {@code color}. */
    public static void fill(final GuiGraphics graphics, final int x, final int y, final int w, final int h, final int color) {
        graphics.fill(x, y, x + w, y + h, color);
    }

    /** Fills a rectangle with a vertical gradient from {@code top} to {@code bottom}. */
    public static void fillGradientV(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h, final int top, final int bottom) {
        graphics.fillGradient(x, y, x + w, y + h, top, bottom);
    }

    /** Draws a 1-pixel rectangle outline; useful for inset borders on cards. */
    public static void outline(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h, final int color) {
        // Top + bottom
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        // Left + right
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** Draws a 2-pixel "double" outline — used around the hero card so it reads from a distance. */
    public static void doubleOutline(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h, final int color) {
        outline(graphics, x, y, w, h, color);
        outline(graphics, x + 1, y + 1, w - 2, h - 2, color);
    }

    /** Dotted horizontal divider; every other pixel is drawn. */
    public static void dottedDivider(
            final GuiGraphics graphics, final int x, final int y, final int width, final int color) {
        for (int i = 0; i < width; i += 2) {
            graphics.fill(x + i, y, x + i + 1, y + 1, color);
        }
    }

    /**
     * Stamps a small repeating dot pattern over {@code height} — a quick substitute for the CRT scan
     * lines designers reach for. {@code alpha} controls how visible the scan lines are.
     */
    public static void scanlines(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h, final int color) {
        for (int yy = y; yy < y + h; yy += 2) {
            graphics.fill(x, yy, x + w, yy + 1, color);
        }
    }

    /** Tiny 3×3 status dot — used in front of status lines. */
    public static void statusDot(final GuiGraphics graphics, final int cx, final int cy, final int color) {
        // Render a 3x3 dot centered on (cx, cy) so the dot aligns to the pixel grid.
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
    }
}
