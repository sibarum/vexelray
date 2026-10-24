package dev.vexelray.shadelets;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.PerInstance;
import dev.vexelray.canvas.shadelet.Style;

/**
 * A polished sphere seen head on, centred on its element, lit by the canvas's one light from the top left: the
 * silhouette is a circle, so its distance alone gives every pixel's normal. A halo of {@code halo} glows round it.
 *
 * @param halo   the halo's colour
 * @param radius the sphere's radius, canvas units
 * @param blur   how far out of focus, as a share of the radius its edge softens over
 * @param alpha  the whole sphere and its halo, {@code 0..1}
 * @param glow   the halo's strength
 * @param bounce light thrown up onto its underside from what it rests on, {@code 0..1}
 */
@Style
public record OrbStyle(Color halo, @PerInstance float radius, @PerInstance float blur, @PerInstance float alpha,
                       @PerInstance float glow, @PerInstance float bounce) {
}
