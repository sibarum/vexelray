package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A disc of colour spreading from a point, cut to the shape it is on: the ink of a press. All per instance, since each
 * press has its own point and the radius and the colour's alpha animate.
 *
 * @param colour the ink, straight alpha; fade it out by its alpha
 * @param x      the disc's centre, canvas units right of the element's centre
 * @param y      and below it
 * @param radius the disc's radius, canvas units
 */
@Style
public record RippleStyle(@PerInstance Color colour, @PerInstance float x, @PerInstance float y,
                          @PerInstance float radius) {

    /**
     * The radius that just covers a box {@code halfW} by {@code halfH} from {@code (x, y)}: the distance to its
     * farthest corner, which is where a ripple has finished spreading.
     */
    public static float covering(float x, float y, float halfW, float halfH) {
        float dx = Math.abs(x) + halfW;
        float dy = Math.abs(y) + halfH;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
