package dev.vexelray.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a string into screen-space {@link GlyphQuad}s against an {@link AtlasData}, advancing the pen per glyph.
 * Adapted from Dasum's {@code GlyphLayout}, with two changes for VexelRay/Vulkan: it emits UVs in the Vulkan
 * convention (V flipped, since msdf-atlas-gen writes a bottom-origin atlas), and it computes the MSDF
 * {@code screenPxRange} for a given em pixel size. No kerning (msdf-atlas-gen emits none) and single-line only —
 * line breaking is later work.
 */
public final class GlyphLayout {

    private final AtlasData atlas;

    public GlyphLayout(AtlasData atlas) {
        this.atlas = atlas;
    }

    /**
     * Lay out {@code text} on one line whose baseline is at screen y {@code baselineY}, starting at pen x
     * {@code penX}. {@code pixelSize} is the em size in screen pixels. Whitespace advances the pen without a quad;
     * a printable codepoint the atlas lacks falls back to the baked missing-glyph box if present.
     */
    public List<GlyphQuad> layout(String text, float penX, float baselineY, float pixelSize) {
        return layout(text, penX, baselineY, pixelSize, 0f);
    }

    /**
     * As {@link #layout(String, float, float, float)}, but adds {@code extraWordSpacing} screen pixels of advance
     * after each whitespace codepoint — the mechanism justified alignment uses to stretch a line to a target width.
     */
    public List<GlyphQuad> layout(String text, float penX, float baselineY, float pixelSize, float extraWordSpacing) {
        List<GlyphQuad> quads = new ArrayList<>();
        float w = atlas.info().width();
        float h = atlas.info().height();
        float cx = penX;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            GlyphData g = resolve(cp);
            if (g == null) {
                continue;
            }
            Rect pb = g.planeBounds();
            Rect ab = g.atlasBounds();
            if (pb != null && ab != null) {
                // Plane Y is Y-up from the baseline; screen Y is Y-down.
                float left = cx + pb.left() * pixelSize;
                float right = cx + pb.right() * pixelSize;
                float top = baselineY - pb.top() * pixelSize;
                float bottom = baselineY - pb.bottom() * pixelSize;
                // Atlas is bottom-origin; flip V for Vulkan (V=0 at image top). ab.top() > ab.bottom().
                float u0 = ab.left() / w;
                float u1 = ab.right() / w;
                float v0 = (h - ab.top()) / h;      // glyph visual top
                float v1 = (h - ab.bottom()) / h;   // glyph visual bottom
                quads.add(new GlyphQuad(left, top, right - left, bottom - top, u0, v0, u1, v1));
            }
            cx += g.advance() * pixelSize;
            if (extraWordSpacing != 0f && Character.isWhitespace(cp)) {
                cx += extraWordSpacing;
            }
        }
        return quads;
    }

    /** Advance width of a single codepoint in screen pixels at {@code pixelSize} (0 if the atlas lacks it). */
    public float advance(int codepoint, float pixelSize) {
        GlyphData g = resolve(codepoint);
        return g == null ? 0f : g.advance() * pixelSize;
    }

    /** Total advance width of {@code text} in screen pixels at {@code pixelSize}. */
    public float measure(String text, float pixelSize) {
        float cx = 0f;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            GlyphData g = resolve(cp);
            if (g != null) {
                cx += g.advance() * pixelSize;
            }
        }
        return cx;
    }

    /** Distance from the baseline up to the top of the tallest glyph, in screen pixels. */
    public float ascent(float pixelSize) {
        return atlas.metrics().ascender() * pixelSize;
    }

    /** Distance from the baseline down to the lowest descender, in screen pixels (a positive value). */
    public float descent(float pixelSize) {
        return -atlas.metrics().descender() * pixelSize;
    }

    /** Baseline-to-baseline distance for consecutive lines, in screen pixels, at the font's natural leading. */
    public float lineHeight(float pixelSize) {
        return atlas.metrics().lineHeight() * pixelSize;
    }

    /** The atlas this layout draws from. */
    public AtlasData atlas() {
        return atlas;
    }

    /**
     * The MSDF {@code screenPxRange} for glyphs drawn at {@code pixelSize} px/em: the atlas distance range scaled
     * from atlas texels to screen pixels. Since the whole run shares a size, this is a single push-constant value.
     * (This is the derivative-free equivalent of the classic {@code fwidth}-based {@code screenPxRange()}.)
     */
    public float screenPxRange(float pixelSize) {
        return atlas.info().distanceRange() * (pixelSize / atlas.info().emSize());
    }

    private GlyphData resolve(int codepoint) {
        GlyphData g = atlas.glyph(codepoint);
        if (g != null) {
            return g;
        }
        if (!isRenderableMiss(codepoint)) {
            return null;
        }
        return atlas.notdef();
    }

    /** Whether an absent codepoint should show the missing-glyph box (printable) vs stay invisible (whitespace/control). */
    private static boolean isRenderableMiss(int codepoint) {
        if (Character.isWhitespace(codepoint)) {
            return false;
        }
        return switch (Character.getType(codepoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                 Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
