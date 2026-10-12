package dev.vexelray.shadelets;

import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * Where a field is morphing to: a second cell of the <em>same</em> texture, as the offset from the element's own cell,
 * and how far along. One icon into another, from one atlas, so the second shape costs a second read of what is
 * already bound. All per instance.
 *
 * @param du the second cell's offset from the first, in texture coordinates across
 * @param dv the same, down
 * @param t  0 is the element's own cell, 1 the second
 */
@Style
public record FieldMorphStyle(@PerInstance float du, @PerInstance float dv, @PerInstance float t) {
}
