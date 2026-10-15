package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A fill running from one colour to another across the shape, along a direction. The ends are the shape's own extent
 * in that direction, so the same style spans a chip and a panel alike. Per instance, so a gradient can animate (a
 * hover warming it, a theme change blending it) without a look per frame.
 *
 * @param from the colour at the start, straight alpha
 * @param to   the colour at the end
 * @param dx   the direction's x, a unit vector with {@code dy}; {@link #at} makes one from an angle
 * @param dy   its y, downward
 */
@Style
public record LinearGradientStyle(@PerInstance Color from, @PerInstance Color to, @PerInstance float dx,
                                  @PerInstance float dy) {

    /** From {@code from} to {@code to} along {@code degrees}: 0 runs left to right, 90 top to bottom. */
    public static LinearGradientStyle at(Color from, Color to, float degrees) {
        double r = Math.toRadians(degrees);
        return new LinearGradientStyle(from, to, (float) Math.cos(r), (float) Math.sin(r));
    }
}
