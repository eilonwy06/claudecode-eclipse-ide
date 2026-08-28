package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.swt.graphics.RGB;

/** Small stateless color-math helpers. */
public final class ColorUtils {

    private ColorUtils() {}

    /**
     * Perceived (Rec. 601) luminance of an sRGB color, 0 (black)–255 (white).
     * This is perceptual luminance, NOT HSB brightness ({@code RGB.getHSB()[2]}, which is
     * {@code max(r,g,b)/255}) — the weighting reflects how bright each channel reads to the eye.
     */
    public static double luminance(int r, int g, int b) {
        return r * 0.299 + g * 0.587 + b * 0.114;
    }

    /** {@link #luminance(int, int, int)} for an SWT {@link RGB}. */
    public static double luminance(RGB rgb) {
        return luminance(rgb.red, rgb.green, rgb.blue);
    }

    /** Formats an SWT {@link RGB} as a CSS hex color ({@code "#rrggbb"}). */
    public static String toHex(RGB rgb) {
        return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
    }
}
