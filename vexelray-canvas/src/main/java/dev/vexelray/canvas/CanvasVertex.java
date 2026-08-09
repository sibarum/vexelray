package dev.vexelray.canvas;

import java.util.List;

/**
 * The Canvas "fat" vertex format — one interleaved layout carrying everything the uber-shader needs for either a
 * shape or a glyph, so shapes and text batch into one vertex buffer and one draw.
 *
 * <pre>
 *   loc0 pos   vec2   clip-space position
 *   loc1 color vec4   straight RGBA
 *   loc2 uv    vec2   atlas UV (glyphs; 0 for shapes)
 *   loc3 kind  float  {@link #KIND_SHAPE} or {@link #KIND_GLYPH}
 *   loc4 local vec2   shape-local pixel coord, box-centre origin (shapes); unused for glyphs
 *   loc5 shape vec4   shapes: (halfW, halfH, cornerRadius, aa) px ; glyphs: (screenPxRange, 0, 0, 0)
 * </pre>
 *
 * Attributes are exposed as {@code (location, components, offsetBytes)} so the binding layer maps them to formats
 * without this module depending on any graphics API.
 */
public final class CanvasVertex {

    private CanvasVertex() {
    }

    public static final int KIND_SHAPE = 0;
    public static final int KIND_GLYPH = 1;

    public static final int FLOATS_PER_VERTEX = 15;
    public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    public static final int OFF_POS = 0;
    public static final int OFF_COLOR = 8;
    public static final int OFF_UV = 24;
    public static final int OFF_KIND = 32;
    public static final int OFF_LOCAL = 36;
    public static final int OFF_SHAPE = 44;

    /** Shader attribute locations, shared by {@link CanvasShader} (author side) and the pipeline (bind side). */
    public static final int LOC_POS = 0;
    public static final int LOC_COLOR = 1;
    public static final int LOC_UV = 2;
    public static final int LOC_KIND = 3;
    public static final int LOC_LOCAL = 4;
    public static final int LOC_SHAPE = 5;

    /** One vertex attribute: shader {@code location}, component count (1/2/4), and byte {@code offset}. */
    public record Attr(int location, int components, int offset) {
    }

    /** The interleaved attribute descriptors, in location order. */
    public static final List<Attr> ATTRIBUTES = List.of(
            new Attr(LOC_POS, 2, OFF_POS),
            new Attr(LOC_COLOR, 4, OFF_COLOR),
            new Attr(LOC_UV, 2, OFF_UV),
            new Attr(LOC_KIND, 1, OFF_KIND),
            new Attr(LOC_LOCAL, 2, OFF_LOCAL),
            new Attr(LOC_SHAPE, 4, OFF_SHAPE));
}
