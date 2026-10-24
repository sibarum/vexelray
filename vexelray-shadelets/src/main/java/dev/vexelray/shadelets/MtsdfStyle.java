package dev.vexelray.shadelets;

import dev.vexelray.canvas.shadelet.Style;

/**
 * How a field in the MTSDF layout splits its range: the median of red, green and blue is the distance near the edge,
 * over {@code near} of the range alpha holds, and alpha is the true distance as far as the field's spread. One byte
 * cannot be both precise at the edge and true sixty pixels out; two ranges in one texel are.
 *
 * @param near the share of the far range the near channels span: {@code 0.125} gives the edge eight times the
 *             precision of alpha
 */
@Style
public record MtsdfStyle(float near) {

    /** The share a field baked by the icon plugin's {@code pair} goal uses. */
    public static final float BAKED = 0.125f;
}
