package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A field's contours out across the page, shown where a moving light (an orb) falls: the same lines, every 6 design
 * units, that {@link CondenseStyle} lights round its shape, carried on past where that one stops. Drawn under the shape's
 * own element, it adds only what that element's contours do not already give, so where both are drawn the two read as
 * one set of lines, and past it these go on alone, fading over {@code falloff}.
 *
 * <p>Positions are in the element's own frame, canvas units from its centre; {@code unit} is the shape's, so the lines
 * fall where the shape's own do.
 *
 * @param heat      the colour of the lines
 * @param falloff   design units over which the lines fade with distance from the shape
 * @param unit      canvas units per design unit; the shape's square over 104
 * @param orbX      the light's centre, right of the element's
 * @param orbY      and below it
 * @param orbRadius the light's radius, canvas units
 * @param preview   how strongly the light shows the contours, {@code 0..1}
 */
@Style
public record ContoursStyle(Color heat, float falloff,
                            @PerInstance float unit,
                            @PerInstance float orbX, @PerInstance float orbY, @PerInstance float orbRadius,
                            @PerInstance float preview) {
}
