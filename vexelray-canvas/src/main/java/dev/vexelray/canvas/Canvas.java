package dev.vexelray.canvas;

import dev.vexelray.text.GlyphQuad;
import dev.vexelray.text.TextLayout;

import java.util.List;

/**
 * An immediate-mode 2D drawing surface. Each frame: {@link #begin()}, issue draw calls (rounded rects, circles,
 * lines, text), then hand {@link #toVertexArray()} + {@link #vertexCount()} to the binding layer to upload and
 * draw. Coordinates are in the target's pixels (top-left origin, Y-down); positions are converted to clip space
 * here, so the vertex buffer is ready to draw with no projection.
 *
 * <p>Everything batches into one {@link CanvasVertex} stream drawn by the {@link CanvasShader} uber-shader, so a
 * whole overlay — panels, buttons, and text — is one buffer and one draw call, rebuilt cheaply each frame.
 */
public final class Canvas {

    /** Analytic edge softness for shapes, in pixels. */
    public static final float AA = 1.0f;
    private static final float PAD = AA + 1f;   // quad extends this far past the shape so the AA falloff isn't clipped

    private final int width;
    private final int height;
    private float[] data = new float[CanvasVertex.FLOATS_PER_VERTEX * 6 * 64];
    private int count;   // floats used

    /** A canvas sized to the target it will be drawn into, in pixels. */
    public Canvas(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Reset the draw list for a new frame. */
    public Canvas begin() {
        count = 0;
        return this;
    }

    // --- shapes ---------------------------------------------------------------------------------------------

    /** Filled axis-aligned rectangle. */
    public Canvas fillRect(float x, float y, float w, float h, Color color) {
        return fillRoundRect(x, y, w, h, 0f, color);
    }

    /** Filled rounded rectangle; {@code radius} clamped to half the smaller side. */
    public Canvas fillRoundRect(float x, float y, float w, float h, float radius, Color color) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float r = clampRadius(radius, halfW, halfH);
        shape(x + halfW, y + halfH, halfW, halfH, r, 0f, color);
        return this;
    }

    /** Filled circle of radius {@code r} centred at {@code (cx, cy)}. */
    public Canvas fillCircle(float cx, float cy, float r, Color color) {
        shape(cx, cy, r, r, r, 0f, color);
        return this;
    }

    /** A line from {@code (x0,y0)} to {@code (x1,y1)} with round caps, {@code thickness} px wide. */
    public Canvas strokeLine(float x0, float y0, float x1, float y1, float thickness, Color color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float half = thickness * 0.5f;
        if (len < 1e-4f) {
            fillCircle(x0, y0, half, color);   // degenerate line = a dot
            return this;
        }
        float cx = (x0 + x1) * 0.5f;
        float cy = (y0 + y1) * 0.5f;
        float angle = (float) Math.atan2(dy, dx);
        shape(cx, cy, len * 0.5f, half, half, angle, color);
        return this;
    }

    // --- text -----------------------------------------------------------------------------------------------

    /** Lay {@code text} out inside {@code box} (wrap + align per {@code style}) and append its glyphs in {@code color}. */
    public Canvas text(TextLayout layout, String text, TextLayout.TextBox box, TextLayout.TextStyle style, Color color) {
        TextLayout.PlacedText placed = layout.place(text, box, style);
        appendGlyphs(placed.quads(), layout.screenPxRange(style.pixelSize()), color);
        return this;
    }

    /** Lay {@code text} out inside the box {@code (x,y,w,h)} (wrap + align per {@code style}) in {@code color}. */
    public Canvas text(TextLayout layout, String text, float x, float y, float w, float h,
                       TextLayout.TextStyle style, Color color) {
        return text(layout, text, new TextLayout.TextBox(x, y, w, h), style, color);
    }

    /** Place {@code text} with its top-left at {@code (x,y)} (no wrapping beyond {@code '\n'}) in {@code color}. */
    public Canvas text(TextLayout layout, String text, float x, float y, TextLayout.TextStyle style, Color color) {
        TextLayout.PlacedText placed = layout.placeAt(text, x, y, TextLayout.Anchor.TOP_LEFT, style);
        appendGlyphs(placed.quads(), layout.screenPxRange(style.pixelSize()), color);
        return this;
    }

    private void appendGlyphs(List<GlyphQuad> quads, float screenPxRange, Color c) {
        for (GlyphQuad q : quads) {
            float x0 = q.x();
            float y0 = q.y();
            float x1 = q.x() + q.w();
            float y1 = q.y() + q.h();
            glyphVert(x0, y0, q.u0(), q.v0(), screenPxRange, c);
            glyphVert(x1, y0, q.u1(), q.v0(), screenPxRange, c);
            glyphVert(x1, y1, q.u1(), q.v1(), screenPxRange, c);
            glyphVert(x0, y0, q.u0(), q.v0(), screenPxRange, c);
            glyphVert(x1, y1, q.u1(), q.v1(), screenPxRange, c);
            glyphVert(x0, y1, q.u0(), q.v1(), screenPxRange, c);
        }
    }

    // --- output ---------------------------------------------------------------------------------------------

    /** Number of vertices accumulated. */
    public int vertexCount() {
        return count / CanvasVertex.FLOATS_PER_VERTEX;
    }

    /** A tightly-sized copy of the accumulated vertex data, ready to upload. */
    public float[] toVertexArray() {
        float[] out = new float[count];
        System.arraycopy(data, 0, out, 0, count);
        return out;
    }

    // --- internals ------------------------------------------------------------------------------------------

    /** Emit a shape quad centred at {@code (cx,cy)} px, rotated by {@code angle}, with the given SDF parameters. */
    private void shape(float cx, float cy, float halfW, float halfH, float radius, float angle, Color color) {
        float ex = halfW + PAD;
        float ey = halfH + PAD;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        // Box-local corners (unrotated); the SDF is evaluated in this frame. Screen positions are rotated.
        shapeVert(cx, cy, cos, sin, -ex, -ey, halfW, halfH, radius, color);
        shapeVert(cx, cy, cos, sin, ex, -ey, halfW, halfH, radius, color);
        shapeVert(cx, cy, cos, sin, ex, ey, halfW, halfH, radius, color);
        shapeVert(cx, cy, cos, sin, -ex, -ey, halfW, halfH, radius, color);
        shapeVert(cx, cy, cos, sin, ex, ey, halfW, halfH, radius, color);
        shapeVert(cx, cy, cos, sin, -ex, ey, halfW, halfH, radius, color);
    }

    private void shapeVert(float cx, float cy, float cos, float sin, float lx, float ly,
                           float halfW, float halfH, float radius, Color c) {
        float sx = cx + lx * cos - ly * sin;
        float sy = cy + lx * sin + ly * cos;
        push(ndcX(sx), ndcY(sy), c, 0f, 0f, CanvasVertex.KIND_SHAPE, lx, ly, halfW, halfH, radius, AA);
    }

    private void glyphVert(float sx, float sy, float u, float v, float screenPxRange, Color c) {
        push(ndcX(sx), ndcY(sy), c, u, v, CanvasVertex.KIND_GLYPH, 0f, 0f, screenPxRange, 0f, 0f, 0f);
    }

    private void push(float px, float py, Color c, float u, float v, int kind,
                      float localX, float localY, float s0, float s1, float s2, float s3) {
        ensure(CanvasVertex.FLOATS_PER_VERTEX);
        int o = count;
        data[o] = px;
        data[o + 1] = py;
        data[o + 2] = c.r();
        data[o + 3] = c.g();
        data[o + 4] = c.b();
        data[o + 5] = c.a();
        data[o + 6] = u;
        data[o + 7] = v;
        data[o + 8] = kind;
        data[o + 9] = localX;
        data[o + 10] = localY;
        data[o + 11] = s0;
        data[o + 12] = s1;
        data[o + 13] = s2;
        data[o + 14] = s3;
        count = o + CanvasVertex.FLOATS_PER_VERTEX;
    }

    private void ensure(int extra) {
        if (count + extra > data.length) {
            int cap = data.length * 2;
            while (cap < count + extra) {
                cap *= 2;
            }
            float[] grown = new float[cap];
            System.arraycopy(data, 0, grown, 0, count);
            data = grown;
        }
    }

    private float ndcX(float screenX) {
        return screenX / width * 2f - 1f;
    }

    private float ndcY(float screenY) {
        return screenY / height * 2f - 1f;
    }

    private static float clampRadius(float radius, float halfW, float halfH) {
        float max = Math.min(halfW, halfH);
        return Math.max(0f, Math.min(radius, max));
    }
}
