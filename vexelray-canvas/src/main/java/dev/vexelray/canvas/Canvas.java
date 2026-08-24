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

    /** Effectively-infinite half-extent for the default (unclipped) clip box. */
    private static final float NO_CLIP = 1e9f;

    private int width;
    private int height;
    private float[] data = new float[CanvasVertex.FLOATS_PER_VERTEX * 6 * 64];
    private int count;   // floats used

    // Current clip (rounded rect, px) stamped into every vertex, and the stack for push/pop. Default = infinite.
    private float clipCx;
    private float clipCy;
    private float clipHw = NO_CLIP;
    private float clipHh = NO_CLIP;
    private float clipR;
    private final java.util.ArrayDeque<float[]> clipStack = new java.util.ArrayDeque<>();

    // Current translation (px) added to every vertex, and the stack for push/pop. Default = none.
    private float tx;
    private float ty;
    private final java.util.ArrayDeque<float[]> translateStack = new java.util.ArrayDeque<>();

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

    /**
     * Change the surface size in pixels — only the pixel→NDC mapping; the vertex buffer is retained. Call between
     * frames (e.g. on a window resize) to avoid reallocating the whole canvas.
     */
    public Canvas resize(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    /** Reset the draw list for a new frame and clear any clip or translation. */
    public Canvas begin() {
        count = 0;
        clipStack.clear();
        clipCx = 0f;
        clipCy = 0f;
        clipHw = NO_CLIP;
        clipHh = NO_CLIP;
        clipR = 0f;
        translateStack.clear();
        tx = 0f;
        ty = 0f;
        return this;
    }

    /**
     * Offset every subsequent draw by {@code (dx, dy)} pixels, cumulatively. Balance with
     * {@link #popTranslate()}.
     *
     * <p>Applied where a vertex is written, so it moves the geometry <em>and</em> the screen position the clip
     * SDF evaluates at — a translated shape is clipped where it is drawn rather than where it was described. The
     * clip box itself does not move: {@link #pushClip} takes screen coordinates and means them, so a clip pushed
     * by a container goes on holding that container's children as they travel inside it, which is what makes a
     * slide-in expressible at all.
     *
     * <p>This is the transform a caller cannot substitute for by adjusting the coordinates it passes. That works
     * only while it owns every coordinate, and it stops the moment any of them are baked: laid-out text carries
     * absolute glyph and caret positions, so nothing upstream can move a line of text without laying it out
     * again. Here it is one addition per vertex, and nothing above needs to know.
     */
    public Canvas pushTranslate(float dx, float dy) {
        translateStack.push(new float[]{tx, ty});
        tx += dx;
        ty += dy;
        return this;
    }

    /** Pop the most recent {@link #pushTranslate}. */
    public Canvas popTranslate() {
        if (!translateStack.isEmpty()) {
            float[] t = translateStack.pop();
            tx = t[0];
            ty = t[1];
        }
        return this;
    }

    /**
     * Push a rounded-rect clip in pixels: subsequent draws are clipped (as antialiased coverage) to the
     * intersection of this rect with the current clip, with corner radius {@code radius}. Balance with
     * {@link #popClip()}. The intersection is of the axis-aligned bounds (so content never escapes an ancestor
     * clip); the corner radius is that of this innermost push.
     */
    public Canvas pushClip(float x, float y, float w, float h, float radius) {
        clipStack.push(new float[]{clipCx, clipCy, clipHw, clipHh, clipR});
        float ncx = x + w * 0.5f;
        float ncy = y + h * 0.5f;
        float nhw = w * 0.5f;
        float nhh = h * 0.5f;
        float minX = Math.max(clipCx - clipHw, ncx - nhw);
        float maxX = Math.min(clipCx + clipHw, ncx + nhw);
        float minY = Math.max(clipCy - clipHh, ncy - nhh);
        float maxY = Math.min(clipCy + clipHh, ncy + nhh);
        clipCx = (minX + maxX) * 0.5f;
        clipCy = (minY + maxY) * 0.5f;
        clipHw = Math.max(0f, (maxX - minX) * 0.5f);
        clipHh = Math.max(0f, (maxY - minY) * 0.5f);
        clipR = Math.max(0f, radius);
        return this;
    }

    /** Pop the most recent {@link #pushClip}. */
    public Canvas popClip() {
        if (!clipStack.isEmpty()) {
            float[] c = clipStack.pop();
            clipCx = c[0];
            clipCy = c[1];
            clipHw = c[2];
            clipHh = c[3];
            clipR = c[4];
        }
        return this;
    }

    // --- shapes ---------------------------------------------------------------------------------------------

    /** Filled axis-aligned rectangle. */
    public Canvas fillRect(float x, float y, float w, float h, Color color) {
        return fillRoundRect(x, y, w, h, 0f, color);
    }

    /** Filled rounded rectangle; {@code radius} clamped to half the smaller side. */
    public Canvas fillRoundRect(float x, float y, float w, float h, float radius, Color color) {
        return fillRoundRect(x, y, w, h, radius, radius, color);
    }

    /** As {@link #fillRoundRect} with independent top and bottom corner radii — a tab is {@code (r, 0)}. */
    public Canvas fillRoundRect(float x, float y, float w, float h, float radiusTop, float radiusBottom,
                                Color color) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        shape(x + halfW, y + halfH, halfW, halfH, clampRadius(radiusTop, halfW, halfH),
                clampRadius(radiusBottom, halfW, halfH), 0f, CanvasVertex.KIND_SHAPE, 0f, 0f, PAD, color);
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

    // --- effects (still the same SDF; a different transfer function over its distance) ------------------------

    /**
     * A soft shadow (or, tinted, an outer glow) for the rounded rect {@code (x,y,w,h,radius)}: coverage falls off
     * over {@code blur} px either side of the edge. Draw it before the shape it sits under, offset as desired.
     */
    public Canvas shadowRoundRect(float x, float y, float w, float h, float radius, float blur, Color color) {
        return shadowRoundRect(x, y, w, h, radius, radius, blur, color);
    }

    /** As {@link #shadowRoundRect} with independent top and bottom corner radii. */
    public Canvas shadowRoundRect(float x, float y, float w, float h, float radiusTop, float radiusBottom,
                                  float blur, Color color) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float b = Math.max(blur, AA);
        shape(x + halfW, y + halfH, halfW, halfH, clampRadius(radiusTop, halfW, halfH),
                clampRadius(radiusBottom, halfW, halfH), 0f, CanvasVertex.KIND_SHADOW, b, 0f, b + 1f, color);
        return this;
    }

    /** A crisp {@code width}-px outline hugging the inside edge of the rounded rect — no fill-then-inset. */
    public Canvas strokeRoundRect(float x, float y, float w, float h, float radius, float width, Color color) {
        return strokeRoundRect(x, y, w, h, radius, radius, width, color);
    }

    /** As {@link #strokeRoundRect} with independent top and bottom corner radii. */
    public Canvas strokeRoundRect(float x, float y, float w, float h, float radiusTop, float radiusBottom,
                                  float width, Color color) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        shape(x + halfW, y + halfH, halfW, halfH, clampRadius(radiusTop, halfW, halfH),
                clampRadius(radiusBottom, halfW, halfH), 0f, CanvasVertex.KIND_STROKE, Math.max(width, 0f), 0f,
                PAD, color);
        return this;
    }

    /**
     * A filled rounded rect lit from the top-left: an embossed edge highlight/shade confined to {@code bevel} px
     * inside the edge, plus a vertical luminance gradient of amplitude {@code gradient} (0 = flat, ~0.06 subtle).
     */
    public Canvas litRoundRect(float x, float y, float w, float h, float radius, float bevel, float gradient,
                               Color color) {
        return litRoundRect(x, y, w, h, radius, radius, bevel, gradient, color);
    }

    /** As {@link #litRoundRect} with independent top and bottom corner radii. */
    public Canvas litRoundRect(float x, float y, float w, float h, float radiusTop, float radiusBottom,
                               float bevel, float gradient, Color color) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        shape(x + halfW, y + halfH, halfW, halfH, clampRadius(radiusTop, halfW, halfH),
                clampRadius(radiusBottom, halfW, halfH), 0f, CanvasVertex.KIND_LIT, Math.max(bevel, 1f), gradient,
                PAD, color);
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
        appendGlyphs(quads, screenPxRange, c, 0f, 0f);
    }

    /** Append glyph quads offset by {@code (dx,dy)} px. */
    private void appendGlyphs(List<GlyphQuad> quads, float screenPxRange, Color c, float dx, float dy) {
        for (GlyphQuad q : quads) {
            float x0 = q.x() + dx;
            float y0 = q.y() + dy;
            float x1 = x0 + q.w();
            float y1 = y0 + q.h();
            glyphVert(x0, y0, q.u0(), q.v0(), screenPxRange, c);
            glyphVert(x1, y0, q.u1(), q.v0(), screenPxRange, c);
            glyphVert(x1, y1, q.u1(), q.v1(), screenPxRange, c);
            glyphVert(x0, y0, q.u0(), q.v0(), screenPxRange, c);
            glyphVert(x1, y1, q.u1(), q.v1(), screenPxRange, c);
            glyphVert(x0, y1, q.u0(), q.v1(), screenPxRange, c);
        }
    }

    /**
     * Sunken ("letterpress") text: the glyphs read as pressed below the surface, lit by the same overhead light
     * as everything else. Depth is directional, not an outline: a soft dark copy nudged <em>up</em> (the cavity's
     * top lip shades the recessed face) and a faint light copy nudged <em>down</em> (the bottom edge catches the
     * light), with the text itself crisp on top. Three passes over one layout, all plain glyph draws — the soft
     * edges come from a reduced screenPxRange, because in MSDF a reduced px range <em>is</em> a blur.
     */
    public Canvas textSunken(TextLayout layout, String text, float x, float y, float w, float h,
                             TextLayout.TextStyle style, Color color, float depthPx) {
        TextLayout.PlacedText placed = layout.place(text, new TextLayout.TextBox(x, y, w, h), style);
        float spr = layout.screenPxRange(style.pixelSize());
        appendGlyphs(placed.quads(), spr * 0.7f, SUNKEN_SHADE, 0f, -depthPx);
        appendGlyphs(placed.quads(), spr * 0.7f, SUNKEN_GLINT, 0f, depthPx);
        appendGlyphs(placed.quads(), spr, color);
        return this;
    }

    /** Letterpress inks: the shade above the glyph and the glint below it. */
    private static final Color SUNKEN_SHADE = Color.withAlpha(Color.rgb(0x000000), 0.4f);
    private static final Color SUNKEN_GLINT = Color.withAlpha(Color.rgb(0xffffff), 0.25f);

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
        shape(cx, cy, halfW, halfH, radius, radius, angle, CanvasVertex.KIND_SHAPE, 0f, 0f, PAD, color);
    }

    /**
     * Emit a shape-family quad: {@code kind} picks the transfer function, {@code (u,v)} are its parameters (rides
     * in the atlas-UV slot, which shapes never sample), {@code pad} is how far past the box the quad must extend
     * so the effect's falloff isn't clipped (a shadow reaches {@code blur} px outside; a fill only {@code AA}).
     * Corner radii are per vertical half: {@code rTop} above the centre line, {@code rBottom} below.
     */
    private void shape(float cx, float cy, float halfW, float halfH, float rTop, float rBottom, float angle,
                       int kind, float u, float v, float pad, Color color) {
        float ex = halfW + pad;
        float ey = halfH + pad;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        // Box-local corners (unrotated); the SDF is evaluated in this frame. Screen positions are rotated.
        shapeVert(cx, cy, cos, sin, -ex, -ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
        shapeVert(cx, cy, cos, sin, ex, -ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
        shapeVert(cx, cy, cos, sin, ex, ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
        shapeVert(cx, cy, cos, sin, -ex, -ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
        shapeVert(cx, cy, cos, sin, ex, ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
        shapeVert(cx, cy, cos, sin, -ex, ey, halfW, halfH, rTop, rBottom, kind, u, v, color);
    }

    private void shapeVert(float cx, float cy, float cos, float sin, float lx, float ly,
                           float halfW, float halfH, float rTop, float rBottom, int kind, float u, float v,
                           Color c) {
        float sx = cx + lx * cos - ly * sin;
        float sy = cy + lx * sin + ly * cos;
        push(sx, sy, c, u, v, kind, lx, ly, halfW, halfH, rTop, rBottom);
    }

    private void glyphVert(float sx, float sy, float u, float v, float screenPxRange, Color c) {
        push(sx, sy, c, u, v, CanvasVertex.KIND_GLYPH, 0f, 0f, screenPxRange, 0f, 0f, 0f);
    }

    /** Append one vertex. {@code sx,sy} is the screen-pixel position (converted to NDC for {@code pos}, and kept
     *  raw for the clip SDF); the current clip box is stamped in from {@link #pushClip}. */
    private void push(float sx, float sy, Color c, float u, float v, int kind,
                      float localX, float localY, float s0, float s1, float s2, float s3) {
        ensure(CanvasVertex.FLOATS_PER_VERTEX);
        // The one place the current translation is applied. Both the position and the screen coordinate the clip
        // is evaluated at move together, so a translated vertex is clipped where it lands.
        sx += tx;
        sy += ty;
        int o = count;
        data[o] = ndcX(sx);
        data[o + 1] = ndcY(sy);
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
        data[o + 15] = clipCx;
        data[o + 16] = clipCy;
        data[o + 17] = clipHw;
        data[o + 18] = clipHh;
        data[o + 19] = sx;
        data[o + 20] = sy;
        data[o + 21] = clipR;
        data[o + 22] = AA;
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
