package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A band standing clear of a shape's edge, following its outline: a focus ring. Per instance, so a ring can arrive
 * (its offset closing in, its alpha coming up) without a look per frame.
 *
 * @param colour the band's colour, straight alpha
 * @param width  the band's width, canvas units
 * @param offset the gap between the shape's edge and the band, canvas units
 */
@Style
public record RingStyle(@PerInstance Color colour, @PerInstance float width, @PerInstance float offset) {
}
