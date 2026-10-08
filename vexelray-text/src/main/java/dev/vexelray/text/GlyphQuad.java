package dev.vexelray.text;

/**
 * One glyph placed in screen space (pixels, top-left origin, Y-down) with its atlas UV rectangle already in the
 * <em>Vulkan</em> convention (V=0 at the top of the image). Colour is uniform across a run and is not per-quad.
 *
 * <p>The UVs are into the atlas of the face that drew the glyph, which under fallback need not be the face the run
 * asked for — so the quad says which face that was, and the {@code screenPxRange} of its atlas at this size.
 *
 * @param x             left edge, screen px
 * @param y             top edge, screen px (Y-down)
 * @param w             width, screen px
 * @param h             height, screen px
 * @param u0            left texture coordinate
 * @param v0            top texture coordinate (Vulkan: smaller V is higher on screen)
 * @param u1            right texture coordinate
 * @param v1            bottom texture coordinate
 * @param face          the {@link FontSet.Face#id() id} of the face whose atlas the UVs index; 0 for a layout
 *                      over a bare {@link AtlasData}
 * @param screenPxRange that atlas's MSDF distance range in screen pixels at the size this quad was laid out at
 */
public record GlyphQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int face,
                        float screenPxRange) {
}
