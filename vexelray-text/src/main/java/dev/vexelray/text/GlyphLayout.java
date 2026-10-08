package dev.vexelray.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a string into screen-space {@link GlyphQuad}s against an {@link AtlasData}, advancing the pen per glyph.
 * Adapted from Dasum's {@code GlyphLayout}, with two changes for VexelRay/Vulkan: it emits UVs in the Vulkan
 * convention (V flipped, since msdf-atlas-gen writes a bottom-origin atlas), and it computes the MSDF
 * {@code screenPxRange} for a given em pixel size. No kerning (msdf-atlas-gen emits none) and single-line only —
 * line breaking is later work.
 *
 * <p>Over a {@link FontSet}, a layout draws one face and falls back along its {@link FontSet#chain chain}: each code
 * point comes from the first face that has it, at that face's advance, and its quad says which face that was.
 * Measuring and drawing go through the same resolution, so a borrowed glyph occupies exactly the width it is
 * measured at. The vertical metrics — ascent, descent, line height — are the asked-for face's alone; a borrowed
 * glyph sits on its baseline.
 */
public final class GlyphLayout {

    /** No face has the code point, and it is one that draws nothing (whitespace, a control). */
    private static final int NONE = -1;
    /** No face has the code point, and it is printable: the asked-for face's missing-glyph box. */
    private static final int NOTDEF = -2;

    private final AtlasData atlas;
    private final AtlasData[] chain;
    private final int[] ids;

    public GlyphLayout(AtlasData atlas) {
        this.atlas = atlas;
        this.chain = new AtlasData[] {atlas};
        this.ids = new int[] {0};
    }

    /** A layout of {@code chain.get(0)}, borrowing from the rest in order. See {@link FontSet#layout}. */
    GlyphLayout(List<FontSet.Face> chain) {
        this.atlas = chain.get(0).atlas();
        this.chain = new AtlasData[chain.size()];
        this.ids = new int[chain.size()];
        for (int i = 0; i < chain.size(); i++) {
            this.chain[i] = chain.get(i).atlas();
            this.ids[i] = chain.get(i).id();
        }
    }

    /** The {@link FontSet.Face#id() id} of the face this layout draws; 0 over a bare {@link AtlasData}. */
    public int face() {
        return ids[0];
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
        float cx = penX;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int from = source(cp);
            GlyphData g = glyph(cp, from);
            if (g == null) {
                continue;
            }
            Rect pb = g.planeBounds();
            Rect ab = g.atlasBounds();
            if (pb != null && ab != null) {
                int k = from < 0 ? 0 : from;
                AtlasInfo info = chain[k].info();
                float w = info.width();
                float h = info.height();
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
                quads.add(new GlyphQuad(left, top, right - left, bottom - top, u0, v0, u1, v1, ids[k],
                        info.distanceRange() * (pixelSize / info.emSize())));
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

    /**
     * The glyph this layout draws for {@code codepoint}, in em: from the first face in the chain that has it, else
     * the missing-glyph box, else {@code null} for a code point that draws nothing (whitespace, a control) — exactly
     * what {@link #advance} and {@link #layout} use, for a caller that needs the bounds as well as the advance.
     */
    public GlyphData resolve(int codepoint) {
        return glyph(codepoint, source(codepoint));
    }

    /** Which face in the chain draws {@code codepoint}: its index, or {@link #NONE} or {@link #NOTDEF}. */
    private int source(int codepoint) {
        for (int k = 0; k < chain.length; k++) {
            if (chain[k].glyph(codepoint) != null) {
                return k;
            }
        }
        return isRenderableMiss(codepoint) ? NOTDEF : NONE;
    }

    private GlyphData glyph(int codepoint, int source) {
        if (source >= 0) {
            return chain[source].glyph(codepoint);
        }
        return source == NOTDEF ? atlas.notdef() : null;
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
