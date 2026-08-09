package dev.vexelray.text;

import java.util.List;

/**
 * Packs screen-space {@link GlyphQuad}s into an interleaved vertex array for the MSDF pipeline: two triangles
 * (six vertices) per glyph, each vertex {@code [x_ndc, y_ndc, u, v]} (4 floats). Positions are converted from
 * screen pixels to Vulkan clip space here — Vulkan clip-space Y points down, matching the screen's Y-down, so a
 * pixel at the top of the screen maps to NDC y = -1.
 *
 * <p>Stride is {@link #FLOATS_PER_VERTEX} floats; the vertex format the pipeline expects is two
 * {@code R32G32_SFLOAT} attributes (position at offset 0, uv at offset 8).
 */
public final class TextMesh {

    /** Floats per vertex: position (vec2) + uv (vec2). */
    public static final int FLOATS_PER_VERTEX = 4;
    /** Byte stride of one vertex. */
    public static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private TextMesh() {
    }

    /** Convert quads to an interleaved NDC vertex array given the target viewport size in pixels. */
    public static float[] toVertices(List<GlyphQuad> quads, int viewWidth, int viewHeight) {
        float[] out = new float[quads.size() * 6 * FLOATS_PER_VERTEX];
        int o = 0;
        for (GlyphQuad q : quads) {
            float xl = ndcX(q.x(), viewWidth);
            float xr = ndcX(q.x() + q.w(), viewWidth);
            float yt = ndcY(q.y(), viewHeight);
            float yb = ndcY(q.y() + q.h(), viewHeight);
            // Triangle 1: TL, TR, BR
            o = vert(out, o, xl, yt, q.u0(), q.v0());
            o = vert(out, o, xr, yt, q.u1(), q.v0());
            o = vert(out, o, xr, yb, q.u1(), q.v1());
            // Triangle 2: TL, BR, BL
            o = vert(out, o, xl, yt, q.u0(), q.v0());
            o = vert(out, o, xr, yb, q.u1(), q.v1());
            o = vert(out, o, xl, yb, q.u0(), q.v1());
        }
        return out;
    }

    /** Vertex count for a vertex array produced by {@link #toVertices}. */
    public static int vertexCount(float[] vertices) {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    private static int vert(float[] out, int o, float x, float y, float u, float v) {
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = u;
        out[o + 3] = v;
        return o + FLOATS_PER_VERTEX;
    }

    private static float ndcX(float screenX, int viewWidth) {
        return screenX / viewWidth * 2f - 1f;
    }

    private static float ndcY(float screenY, int viewHeight) {
        return screenY / viewHeight * 2f - 1f;
    }
}
