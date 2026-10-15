package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A fill running from one colour at a point to another at a distance from it, cut to the shape. Per instance, like
 * the linear one.
 *
 * @param inner  the colour at the centre, straight alpha
 * @param outer  the colour at {@code radius} and beyond
 * @param x      the centre, canvas units right of the element's centre
 * @param y      and below it
 * @param radius how far the colour takes to change, canvas units
 */
@Style
public record RadialGradientStyle(@PerInstance Color inner, @PerInstance Color outer, @PerInstance float x,
                                  @PerInstance float y, @PerInstance float radius) {
}
