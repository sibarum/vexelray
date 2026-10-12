package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A band of colour along a shape's edge, half inside and half out.
 *
 * @param colour the band's colour, straight alpha
 * @param width  the band's width in canvas units
 */
@Style
public record OutlineStyle(Color colour, float width) {
}
