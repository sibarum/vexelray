package dev.vexelray.canvas;

import java.util.List;

/**
 * The Canvas "fat" vertex format — one interleaved layout carrying everything the uber-shader needs for any primitive,
 * so boxes, text, images and fields batch into one vertex buffer and one draw.
 *
 * <pre>
 *   loc0 pos     vec2  clip-space position
 *   loc1 color   vec4  straight RGBA
 *   loc2 uv      vec2  atlas UV (glyphs); image or field UV (images, fields); unused for boxes
 *   loc3 local   vec2  shape-local pixel coord, box-centre origin (boxes, images, fields); unused for glyphs
 *   loc4 shape   vec4  boxes: (halfW, halfH, cornerRadiusTop, cornerRadiusBottom) px — the radius is selected
 *                      per vertical half, so a tab is (r, 0); AA is the system constant 1px, baked in the shader.
 *                      glyphs: (screenPxRange, 0, 0, 0)
 *   loc5 clipBox vec4  clip rounded-rect in px: (centerX, centerY, halfW, halfH)
 *   loc6 clipRs  vec4  (screenX, screenY, clipCornerRadius, clipAa) px — the fragment's own screen pos + clip edge
 *   loc7 filter  vec4  images: (filter, textureWidth, textureHeight, texelsPerPixel) — how the image is scaled,
 *                      {@link ImageFilter#ordinal()} + 1, and 0 for the sampler's own bilinear, which is also what
 *                      every other primitive carries (all zero). Fields: (px per unit of the field's value,
 *                      texture coordinate per px, 0, 0).
 *   loc8 element float the element's record in the shadelet tables, which names its look
 * </pre>
 *
 * <p>Nothing here says how a vertex is painted: that is its element's look, in the tables. The vertex carries the
 * geometry and the record.
 *
 * <p>The clip is applied as coverage, not a scissor or discard: the fragment evaluates the rounded-rect SDF of the
 * clip box at its own screen position and multiplies its alpha by the result, so clipping is antialiased, honours
 * rounded corners, and stays a single batched draw. A default (unclipped) canvas stamps an effectively infinite
 * clip box, so coverage is 1 everywhere.
 *
 * Attributes are exposed as {@code (location, components, offsetBytes)} so the binding layer maps them to formats
 * without this module depending on any graphics API.
 */
public final class CanvasVertex {

    private CanvasVertex() {
    }

    public static final int FLOATS_PER_VERTEX = 27;
    public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    public static final int OFF_POS = 0;
    public static final int OFF_COLOR = 8;
    public static final int OFF_UV = 24;
    public static final int OFF_LOCAL = 32;
    public static final int OFF_SHAPE = 40;
    public static final int OFF_CLIPBOX = 56;
    public static final int OFF_CLIPRS = 72;
    public static final int OFF_FILTER = 88;
    /** The element's record in the shadelet tables, as a float: see {@link dev.vexelray.canvas.shadelet.Composition}. */
    public static final int OFF_ELEMENT = 104;

    /** Shader attribute locations, shared by {@link CanvasShader} (author side) and the pipeline (bind side). */
    public static final int LOC_POS = 0;
    public static final int LOC_COLOR = 1;
    public static final int LOC_UV = 2;
    public static final int LOC_LOCAL = 3;
    public static final int LOC_SHAPE = 4;
    public static final int LOC_CLIPBOX = 5;
    public static final int LOC_CLIPRS = 6;
    public static final int LOC_FILTER = 7;
    public static final int LOC_ELEMENT = 8;

    /** One vertex attribute: shader {@code location}, component count (1/2/4), and byte {@code offset}. */
    public record Attr(int location, int components, int offset) {
    }

    /** The interleaved attribute descriptors, in location order. */
    public static final List<Attr> ATTRIBUTES = List.of(
            new Attr(LOC_POS, 2, OFF_POS),
            new Attr(LOC_COLOR, 4, OFF_COLOR),
            new Attr(LOC_UV, 2, OFF_UV),
            new Attr(LOC_LOCAL, 2, OFF_LOCAL),
            new Attr(LOC_SHAPE, 4, OFF_SHAPE),
            new Attr(LOC_CLIPBOX, 4, OFF_CLIPBOX),
            new Attr(LOC_CLIPRS, 4, OFF_CLIPRS),
            new Attr(LOC_FILTER, 4, OFF_FILTER),
            new Attr(LOC_ELEMENT, 1, OFF_ELEMENT));
}
