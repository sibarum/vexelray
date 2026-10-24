package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A shape precipitating out of its distance field: a front sweeps out from a point, and behind it the shape grows from
 * its middle to its edge with a hot rim on the moving cut, while rings outside it close in; a moving light (an orb)
 * lights the field's contours as it passes and glints on the shape's bevel. At {@code settle = 1} the shape's coverage
 * is the colour layer's own alpha, so the resting frame is the art, texel for texel.
 *
 * <p>The colour is a second cell of the field's texture: {@code t = uv * map.xy + map.zw} is where the fragment falls in
 * the shape's square ({@code 0..1} across it), and the cell {@code colour} spans that square. Every position is in the
 * element's own frame, canvas units from its centre; every length the effect is designed in is scaled by
 * {@code unit}, so it looks the same at every size of the shape.
 *
 * @param heat      the hot colour: the rim, the rings, the light
 * @param unit      canvas units per design unit; the shape's square over 104
 * @param frontX    the front's centre, right of the element's
 * @param frontY    and below it
 * @param front     the front's radius, canvas units; far negative before it starts
 * @param orbX      the light's centre, right of the element's
 * @param orbY      and below it
 * @param orbRadius the light's radius, canvas units
 * @param preview   how strongly the light shows the field's contours, {@code 0..1}
 * @param ring      the front's own ring of light, {@code 0..1}
 * @param glint     the light's highlight on the bevel, {@code 0..1}
 * @param settle    {@code 0} the field's coverage, {@code 1} the colour's own alpha
 * @param time      seconds, for the rings' drift
 * @param mapX      {@code t.x = uv.x * mapX + mapU}
 * @param mapY      {@code t.y = uv.y * mapY + mapV}
 * @param mapU      see {@code mapX}
 * @param mapV      see {@code mapY}
 * @param colourU0  the colour cell's left, in texture coordinates
 * @param colourV0  its top
 * @param colourU1  its right
 * @param colourV1  its bottom
 */
@Style
public record CondenseStyle(Color heat,
                            @PerInstance float unit,
                            @PerInstance float frontX, @PerInstance float frontY, @PerInstance float front,
                            @PerInstance float orbX, @PerInstance float orbY, @PerInstance float orbRadius,
                            @PerInstance float preview, @PerInstance float ring, @PerInstance float glint,
                            @PerInstance float settle, @PerInstance float time,
                            @PerInstance float mapX, @PerInstance float mapY,
                            @PerInstance float mapU, @PerInstance float mapV,
                            @PerInstance float colourU0, @PerInstance float colourV0,
                            @PerInstance float colourU1, @PerInstance float colourV1) {
}
