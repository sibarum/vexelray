package dev.vexelray.text;

/**
 * One glyph placed in screen space (pixels, top-left origin, Y-down) with its atlas UV rectangle already in the
 * <em>Vulkan</em> convention (V=0 at the top of the image). Colour and the MSDF {@code screenPxRange} are uniform
 * across a run and travel as push constants, so they are not per-quad here.
 *
 * @param x  left edge, screen px
 * @param y  top edge, screen px (Y-down)
 * @param w  width, screen px
 * @param h  height, screen px
 * @param u0 left texture coordinate
 * @param v0 top texture coordinate (Vulkan: smaller V is higher on screen)
 * @param u1 right texture coordinate
 * @param v1 bottom texture coordinate
 */
public record GlyphQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
}
