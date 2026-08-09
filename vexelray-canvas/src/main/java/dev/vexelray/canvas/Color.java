package dev.vexelray.canvas;

/**
 * A straight (non-premultiplied) RGBA colour, components in {@code [0,1]}. The Canvas blends with
 * src-alpha / one-minus-src-alpha, so {@code a} is the coverage of the primitive over what is behind it.
 */
public record Color(float r, float g, float b, float a) {

    public static Color rgb(float r, float g, float b) {
        return new Color(r, g, b, 1f);
    }

    public static Color rgba(float r, float g, float b, float a) {
        return new Color(r, g, b, a);
    }

    /** From 0xAARRGGBB. */
    public static Color argb(int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return new Color(r, g, b, a);
    }

    /** From 0xRRGGBB, fully opaque. */
    public static Color rgb(int rgb) {
        return argb(0xFF000000 | (rgb & 0xFFFFFF));
    }

    /** This colour with a replaced alpha. */
    public static Color withAlpha(Color c, float a) {
        return new Color(c.r, c.g, c.b, a);
    }

    public static final Color WHITE = rgb(1, 1, 1);
    public static final Color BLACK = rgb(0, 0, 0);
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
}
