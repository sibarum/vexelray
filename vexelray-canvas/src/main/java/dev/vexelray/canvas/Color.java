package dev.vexelray.canvas;

/**
 * A straight (non-premultiplied) RGBA colour, components in {@code [0,1]}. The Canvas blends with
 * src-alpha / one-minus-src-alpha, so {@code a} is the coverage of the primitive over what is behind it.
 *
 * <h2>Why this is not {@code Surface.Rgb}, and where the boundary is</h2>
 *
 * <p>The project has exactly two colour types on purpose, and they sit on opposite sides of a real line.
 *
 * <p>This one is a <b>compositing</b> colour: {@code float}, because it is one of eight numbers in a vertex
 * that a shader reads as {@code float}, and there is nothing to gain from a precision the GPU discards on the
 * way in; and it carries <b>alpha</b>, because coverage is the whole business of a 2D batch — a rounded corner
 * is antialiased by alpha, an image is modulated by it, and a panel is translucent through it.
 *
 * <p>{@code dev.vexelray.surface.Surface.Rgb} is a <b>shading</b> colour: {@code double}, because it is
 * arithmetic in a compiler that carries every other number as {@code double} and rounds once at lowering; and
 * it has <b>no alpha</b>, because a signed-distance surface either is or is not at a point, and "half a
 * surface" is not a thing the field can express. Its javadoc says the other half of this.
 *
 * <p>Both are linear — {@code Rgb} says so and this one is written into a linear framebuffer — so the
 * difference is never colour space. Converting is three casts either way and no library exists for it,
 * deliberately: a place that needs the conversion is a place where compositing meets shading, which is worth
 * noticing rather than smoothing over.
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
