package com.example.elink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Thin helpers around the Minecraft bitmap font so the title screen reads as a single pixel-art
 * composition. Everything is plain {@code GuiGraphics.drawString} underneath; the wrappers exist to
 * keep shadow offsets and line heights consistent.
 *
 * <p>Why not a custom bitmap font? Minecraft ships a bitmap font that already has the right look on
 * every locale; layering a second one would only break when players switch languages.
 */
public final class PixelFont {
    private PixelFont() {}

    /** Body line height — used as the vertical step for multi-line text blocks. */
    public static final int LINE_HEIGHT = 12;
    /** Big-title line height — bumped by 2 to leave breathing room between hero lines. */
    public static final int TITLE_HEIGHT = 22;

    /** Draws body text with a soft 1-pixel shadow at the default Minecraft font size. */
    public static void body(final GuiGraphics graphics, final String text, final int x, final int y, final int color) {
        final Font font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x + 1, y + 1, ELinkPalette.VOID, false);
        graphics.drawString(font, text, x, y, color, false);
    }

    /** Draws a heading line: same shadow, but rendered with the highlighted (bold-ish) font variant. */
    public static void heading(final GuiGraphics graphics, final String text, final int x, final int y, final int color) {
        final Font font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x + 1, y + 1, ELinkPalette.VOID, false);
        graphics.drawString(font, text, x, y, color, true);
    }

    /** Draws an emphasised hero line with a stronger two-pixel shadow. */
    public static void hero(final GuiGraphics graphics, final String text, final int x, final int y, final int color) {
        final Font font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x + 2, y + 2, ELinkPalette.VOID, false);
        graphics.drawString(font, text, x, y, color, false);
    }

    /** Convenience accessor so callers can measure strings against the same font they draw with. */
    public static Font font() {
        return Minecraft.getInstance().font;
    }
}
