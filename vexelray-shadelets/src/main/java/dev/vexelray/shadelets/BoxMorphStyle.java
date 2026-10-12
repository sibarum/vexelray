package dev.vexelray.shadelets;

import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * Where a box is morphing to: a second rounded box about the same centre, and how far along. All per instance, since
 * each element morphs between its own two boxes and the progress animates.
 *
 * @param halfW  the second box's half width, canvas units
 * @param halfH  its half height
 * @param radius its corner radius
 * @param t      0 is the element's own box, 1 the second
 */
@Style
public record BoxMorphStyle(@PerInstance float halfW, @PerInstance float halfH, @PerInstance float radius,
                            @PerInstance float t) {
}
