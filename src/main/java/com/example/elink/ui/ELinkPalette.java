package com.example.elink.ui;

/**
 * Pixel-art colour palette for the 易联 title screen.
 *
 * <p>All values are flat ARGB ints so the renderer can pass them straight to {@code GuiGraphics.fill}.
 * The palette is deliberately limited — a small set of inky blues, a couple of accents and a soft
 * glow — so the screen reads as a coherent pixel-art poster rather than a generic GUI.
 *
 * <p>Numbers are {@code 0xAARRGGBB}; alpha stays opaque except where a translucent overlay is wanted.
 */
public final class ELinkPalette {
    private ELinkPalette() {}

    // --- Backgrounds --------------------------------------------------------
    /** Almost-black backdrop behind everything, like an old CRT boot screen. */
    public static final int VOID = 0xFF050B14;
    /** Deep navy used for the largest card. */
    public static final int DEEP = 0xFF0B1830;
    /** Card surface, slightly lifted from {@link #DEEP}. */
    public static final int SURFACE = 0xFF142544;
    /** Surface highlight, one step brighter — used for pressed / active rows. */
    public static final int SURFACE_HI = 0xFF1E3460;
    /** Track background inside the card. */
    public static final int TRACK = 0xFF0A1426;

    // --- Borders ------------------------------------------------------------
    /** Cool dark border between two surface shades. */
    public static final int BORDER = 0xFF2A4378;
    /** Bright outline drawn around the title card to make it pop on the void. */
    public static final int OUTER = 0xFF4A7BD4;

    // --- Accents ------------------------------------------------------------
    /** Cyan-blue used for the logo, the "online" indicators and headings. */
    public static final int ACCENT = 0xFF4FC3F7;
    /** Slightly desaturated cyan, used for body text and secondary chips. */
    public static final int ACCENT_DIM = 0xFF8FB7E0;
    /** Pale sky for the hero text on the title. */
    public static final int HERO = 0xFFE3F4FF;
    /** Saturated cyan, used for the "hosting / connected" status light. */
    public static final int GLOW = 0xFF7FE6FF;
    /** Warning amber, used sparingly for offline / error states. */
    public static final int WARN = 0xFFFFB74D;
    /** Alarm red for "no room key" or "no account" tags. */
    public static final int ALERT = 0xFFFF6E6E;

    // --- Pixel grid helpers --------------------------------------------------
    /** A single glow pixel with low alpha — used to draw CRT scanline sparkles. */
    public static final int GLOW_SOFT = 0x664FC3F7;
    /** Scanline overlay, semi-transparent cyan, multiplied across the card. */
    public static final int SCANLINE = 0x221E3460;
}
