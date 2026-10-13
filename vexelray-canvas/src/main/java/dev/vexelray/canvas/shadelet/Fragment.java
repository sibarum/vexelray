package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;

/**
 * What a shadelet may read of the fragment it runs for: the element's per-vertex geometry, as the host's varyings,
 * and the two scalars that relate canvas units to screen pixels where it is drawn.
 *
 * <p>A shadelet reads these and nothing else of the stage: no interface variable, no builtin. The host builds this
 * from its own varyings, so one shadelet runs in every host that places a canvas, on the screen or on a plane.
 *
 * @param color         the vertex colour, straight alpha
 * @param uv            the vertex UV: an atlas, image or field coordinate; unused for a box
 * @param local         the fragment in the element's own frame, in canvas units, centred on it
 * @param shape         the element's shape parameters: a box's {@code (halfW, halfH, rTop, rBottom)}
 * @param filter        the image filter, or a field's {@code (range, v per px, glow, shadow)}
 * @param aa            the anti-aliasing half-width in canvas units: 1 on a screen-aligned canvas
 * @param pixelsPerUnit screen pixels covered by one canvas unit: 1 on a screen-aligned canvas
 */
public record Fragment(Expr color, Expr uv, Expr local, Expr shape, Expr filter, Expr aa, Expr pixelsPerUnit) {
}
