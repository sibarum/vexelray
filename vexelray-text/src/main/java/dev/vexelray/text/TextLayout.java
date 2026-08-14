package dev.vexelray.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Text layout on top of {@link GlyphLayout}: line breaking + wrapping, horizontal/vertical alignment, placement
 * into a box or against an anchor point, block measurement, and fit-hypothesis queries ("does it fit?", "what's
 * the biggest size that fits?"). Everything works in screen pixels (top-left origin, Y-down) and produces
 * {@link GlyphQuad}s that {@link TextMesh#toVertices} turns into pipeline vertices.
 *
 * <p>Stateless beyond the atlas reference — one instance per atlas suffices; all inputs are per call. Wrapping
 * follows the same break-opportunity model as Dasum (whitespace primary; letter↔punctuation and letter↔digit
 * transitions as secondary opportunities for long runs), with optional character breaking for words that still
 * overflow. No kerning (the atlas provides none) and no bidi/shaping — advance-based Latin layout.
 */
public final class TextLayout {

    /** Horizontal alignment of each line within the layout width. */
    public enum HAlign { LEFT, CENTER, RIGHT, JUSTIFY }

    /** Vertical alignment of the text block within the box height. */
    public enum VAlign { TOP, MIDDLE, BOTTOM }

    /**
     * How lines may be broken to fit a width.
     * <ul>
     *   <li>{@code NONE} — break on {@code '\n'} only; never wrap.</li>
     *   <li>{@code WORD} — wrap at break opportunities; a single word wider than the width overflows.</li>
     *   <li>{@code WORD_CHAR} — like {@code WORD}, but break an over-wide word across characters.</li>
     *   <li>{@code CHAR} — break between any characters to fit (ignores word boundaries).</li>
     * </ul>
     */
    public enum WrapMode { NONE, WORD, WORD_CHAR, CHAR }

    /** A nine-point anchor for placing a block relative to a point. */
    public enum Anchor {
        TOP_LEFT(0f, 0f), TOP_CENTER(0.5f, 0f), TOP_RIGHT(1f, 0f),
        CENTER_LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1f, 0.5f),
        BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(0.5f, 1f), BOTTOM_RIGHT(1f, 1f);

        final float fx;
        final float fy;

        Anchor(float fx, float fy) {
            this.fx = fx;
            this.fy = fy;
        }
    }

    /** A screen-space rectangle, top-left origin, Y-down. */
    public record TextBox(float x, float y, float width, float height) {
    }

    /**
     * One visual line as an offset span into the original text: {@code [start, end)} (untrimmed, so it maps
     * caret offsets exactly), and whether a hard break ({@code '\n'} or the end of the text) ended it. Produced
     * by {@link #breakLineSpans} for callers (e.g. a text editor) that must map character offsets to visual lines
     * across wrapping. Note: leading whitespace dropped when wrapping a line belongs to the preceding span's tail,
     * so consecutive spans can leave a gap of dropped spaces — treat a character as belonging to the last span
     * whose {@code start <= offset}.
     */
    public record LineSpan(int start, int end, boolean hardBreak) {
    }

    /** One visual line: its text (trailing whitespace trimmed), advance width in px, and whether a hard break ('\n' or end) ended it. */
    public record TextLine(String text, float width, boolean hardBreak) {
    }

    /**
     * Measured extent of a laid-out block.
     *
     * @param width      widest line, px
     * @param height     top of the first line's ascent to the bottom of the last line's descent, px
     * @param lineCount  number of visual lines
     * @param lineHeight baseline-to-baseline distance used, px
     * @param ascent     first-baseline offset from the block top, px
     * @param descent    descender depth below the last baseline, px
     */
    public record TextBounds(float width, float height, int lineCount, float lineHeight, float ascent, float descent) {
    }

    /**
     * A layout request. Use {@link #of(float)} then the {@code with*} methods.
     *
     * @param pixelSize   em size in screen pixels
     * @param wrap        wrapping mode
     * @param hAlign      horizontal alignment
     * @param vAlign      vertical alignment
     * @param lineSpacing multiplier on the font's natural line height (1.0 = natural)
     */
    public record TextStyle(float pixelSize, WrapMode wrap, HAlign hAlign, VAlign vAlign, float lineSpacing) {
        /** A left/top-aligned, word+char-wrapping style at natural line spacing. */
        public static TextStyle of(float pixelSize) {
            return new TextStyle(pixelSize, WrapMode.WORD_CHAR, HAlign.LEFT, VAlign.TOP, 1f);
        }

        public TextStyle withSize(float px) {
            return new TextStyle(px, wrap, hAlign, vAlign, lineSpacing);
        }

        public TextStyle withWrap(WrapMode w) {
            return new TextStyle(pixelSize, w, hAlign, vAlign, lineSpacing);
        }

        public TextStyle withAlign(HAlign h, VAlign v) {
            return new TextStyle(pixelSize, wrap, h, v, lineSpacing);
        }

        public TextStyle withHAlign(HAlign h) {
            return new TextStyle(pixelSize, wrap, h, vAlign, lineSpacing);
        }

        public TextStyle withVAlign(VAlign v) {
            return new TextStyle(pixelSize, wrap, hAlign, v, lineSpacing);
        }

        public TextStyle withLineSpacing(float s) {
            return new TextStyle(pixelSize, wrap, hAlign, vAlign, s);
        }
    }

    /** Result of a placement: positioned glyph quads plus the block's measured bounds and top-left origin. */
    public record PlacedText(List<GlyphQuad> quads, TextBounds bounds, float x, float y) {
    }

    private static final float EPS = 0.01f;

    private final GlyphLayout glyphs;

    public TextLayout(AtlasData atlas) {
        this.glyphs = new GlyphLayout(atlas);
    }

    public TextLayout(GlyphLayout glyphs) {
        this.glyphs = glyphs;
    }

    /** The underlying single-line layout (advance/ascent/screenPxRange helpers). */
    public GlyphLayout glyphLayout() {
        return glyphs;
    }

    /** The MSDF {@code screenPxRange} push-constant value for this pixel size (see {@link GlyphLayout#screenPxRange}). */
    public float screenPxRange(float pixelSize) {
        return glyphs.screenPxRange(pixelSize);
    }

    // --- breaking / wrapping --------------------------------------------------------------------------------

    /**
     * Break {@code text} into visual lines for the given pixel size, wrapping to {@code maxWidth} px per
     * {@code mode}. Honours embedded {@code '\n'}. A {@code maxWidth <= 0} disables wrapping regardless of mode.
     */
    public List<TextLine> breakLines(String text, float pixelSize, float maxWidth, WrapMode mode) {
        WrapMode effective = (maxWidth <= 0f) ? WrapMode.NONE : mode;
        List<TextLine> out = new ArrayList<>();
        int n = text.length();
        if (n == 0) {
            out.add(new TextLine("", 0f, true));
            return out;
        }
        boolean charBreak = effective == WrapMode.WORD_CHAR || effective == WrapMode.CHAR;
        boolean pureChar = effective == WrapMode.CHAR;

        int i = 0;
        int lineStart = 0;
        float lineWidth = 0f;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(makeLine(text, lineStart, i, true, pixelSize));
                i++;
                lineStart = i;
                lineWidth = 0f;
                continue;
            }
            if (effective == WrapMode.NONE) {
                // Consume to the next '\n' in one chunk.
                int e = i;
                while (e < n && text.charAt(e) != '\n') {
                    e++;
                }
                i = e;
                continue;
            }

            // Next unbreakable chunk.
            int chunkEnd;
            if (pureChar) {
                chunkEnd = i + Character.charCount(text.codePointAt(i));
            } else {
                chunkEnd = i + 1;
                while (chunkEnd < n && text.charAt(chunkEnd) != '\n' && !canBreakBetween(text, chunkEnd)) {
                    chunkEnd++;
                }
            }
            float chunkWidth = measureRange(text, i, chunkEnd, pixelSize);

            // If the chunk alone overflows and we may break characters, clip it to the largest prefix that fits an
            // empty line (at least one codepoint) so the greedy step below can lay it across successive lines.
            if (charBreak && chunkWidth > maxWidth) {
                chunkEnd = largestPrefixWithin(text, i, chunkEnd, pixelSize, maxWidth);
                chunkWidth = measureRange(text, i, chunkEnd, pixelSize);
            }

            boolean lineEmpty = lineWidth == 0f;
            boolean fits = lineWidth + chunkWidth <= maxWidth + EPS;
            boolean chunkTooBig = chunkWidth > maxWidth;

            if (fits || lineEmpty || chunkTooBig) {
                lineWidth += chunkWidth;
                i = chunkEnd;
            } else {
                out.add(makeLine(text, lineStart, i, false, pixelSize));
                while (i < n && isSpaceOrTab(text.charAt(i))) {
                    i++;   // drop leading whitespace on the wrapped line
                }
                lineStart = i;
                lineWidth = 0f;
            }
        }
        out.add(makeLine(text, lineStart, n, true, pixelSize));
        return out;
    }

    /**
     * Break {@code text} into visual lines as offset spans into the original string (see {@link LineSpan}) —
     * the offset-aware companion to {@link #breakLines}, for callers that must map character offsets to visual
     * lines (a wrapped text editor). Same wrapping rules; {@code maxWidth <= 0} disables wrapping.
     */
    public List<LineSpan> breakLineSpans(String text, float pixelSize, float maxWidth, WrapMode mode) {
        WrapMode effective = (maxWidth <= 0f) ? WrapMode.NONE : mode;
        List<LineSpan> out = new ArrayList<>();
        int n = text.length();
        if (n == 0) {
            out.add(new LineSpan(0, 0, true));
            return out;
        }
        boolean charBreak = effective == WrapMode.WORD_CHAR || effective == WrapMode.CHAR;
        boolean pureChar = effective == WrapMode.CHAR;

        int i = 0;
        int lineStart = 0;
        float lineWidth = 0f;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(new LineSpan(lineStart, i, true));
                i++;
                lineStart = i;
                lineWidth = 0f;
                continue;
            }
            if (effective == WrapMode.NONE) {
                int e = i;
                while (e < n && text.charAt(e) != '\n') {
                    e++;
                }
                i = e;
                continue;
            }

            int chunkEnd;
            if (pureChar) {
                chunkEnd = i + Character.charCount(text.codePointAt(i));
            } else {
                chunkEnd = i + 1;
                while (chunkEnd < n && text.charAt(chunkEnd) != '\n' && !canBreakBetween(text, chunkEnd)) {
                    chunkEnd++;
                }
            }
            float chunkWidth = measureRange(text, i, chunkEnd, pixelSize);
            if (charBreak && chunkWidth > maxWidth) {
                chunkEnd = largestPrefixWithin(text, i, chunkEnd, pixelSize, maxWidth);
                chunkWidth = measureRange(text, i, chunkEnd, pixelSize);
            }

            boolean lineEmpty = lineWidth == 0f;
            boolean fits = lineWidth + chunkWidth <= maxWidth + EPS;
            boolean chunkTooBig = chunkWidth > maxWidth;

            if (fits || lineEmpty || chunkTooBig) {
                lineWidth += chunkWidth;
                i = chunkEnd;
            } else {
                out.add(new LineSpan(lineStart, i, false));
                while (i < n && isSpaceOrTab(text.charAt(i))) {
                    i++;
                }
                lineStart = i;
                lineWidth = 0f;
            }
        }
        out.add(new LineSpan(lineStart, n, true));
        return out;
    }

    // --- measuring ------------------------------------------------------------------------------------------

    /** Measure a single line (no wrapping), i.e. one line's bounds; {@code '\n'} is treated as a hard break. */
    public TextBounds measure(String text, float pixelSize) {
        return measure(text, TextStyle.of(pixelSize).withWrap(WrapMode.NONE), 0f);
    }

    /** Measure the block {@code text} wraps to at {@code maxWidth} under {@code style}. */
    public TextBounds measure(String text, TextStyle style, float maxWidth) {
        return boundsOf(breakLines(text, style.pixelSize(), maxWidth, style.wrap()), style);
    }

    private TextBounds boundsOf(List<TextLine> lines, TextStyle style) {
        float px = style.pixelSize();
        float ascent = glyphs.ascent(px);
        float descent = glyphs.descent(px);
        float lineHeight = glyphs.lineHeight(px) * style.lineSpacing();
        float width = 0f;
        for (TextLine l : lines) {
            width = Math.max(width, l.width());
        }
        int count = lines.size();
        float height = ascent + descent + (count - 1) * lineHeight;
        return new TextBounds(width, height, count, lineHeight, ascent, descent);
    }

    // --- placement ------------------------------------------------------------------------------------------

    /** Wrap {@code text} to {@code box.width} and align it inside {@code box}; returns positioned quads + bounds. */
    public PlacedText place(String text, TextBox box, TextStyle style) {
        float maxWidth = style.wrap() == WrapMode.NONE ? 0f : box.width();
        List<TextLine> lines = breakLines(text, style.pixelSize(), maxWidth, style.wrap());
        TextBounds bounds = boundsOf(lines, style);
        float px = style.pixelSize();

        float startY = switch (style.vAlign()) {
            case TOP -> box.y();
            case MIDDLE -> box.y() + (box.height() - bounds.height()) / 2f;
            case BOTTOM -> box.y() + box.height() - bounds.height();
        };
        float firstBaseline = startY + bounds.ascent();

        List<GlyphQuad> quads = new ArrayList<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            TextLine line = lines.get(idx);
            float baselineY = firstBaseline + idx * bounds.lineHeight();
            float penX = box.x();
            float extraWordSpacing = 0f;
            switch (style.hAlign()) {
                case LEFT -> penX = box.x();
                case CENTER -> penX = box.x() + (box.width() - line.width()) / 2f;
                case RIGHT -> penX = box.x() + box.width() - line.width();
                case JUSTIFY -> {
                    int spaces = spaceCount(line.text());
                    if (!line.hardBreak() && spaces > 0) {
                        extraWordSpacing = Math.max(0f, (box.width() - line.width()) / spaces);
                    }
                }
            }
            quads.addAll(glyphs.layout(line.text(), penX, baselineY, px, extraWordSpacing));
        }
        return new PlacedText(quads, bounds, box.x(), startY);
    }

    /**
     * Place {@code text} so its {@code anchor} point sits at {@code (x, y)} (no wrapping beyond {@code '\n'}).
     * Handy for HUD labels: {@code placeAt(s, cx, cy, Anchor.CENTER, style)} centres the block on a point.
     */
    public PlacedText placeAt(String text, float x, float y, Anchor anchor, TextStyle style) {
        TextBounds b = measure(text, style, 0f);
        float originX = x - anchor.fx * b.width();
        float originY = y - anchor.fy * b.height();
        return place(text, new TextBox(originX, originY, b.width(), b.height()),
                style.withVAlign(VAlign.TOP));
    }

    // --- fit hypotheses -------------------------------------------------------------------------------------

    /** Whether {@code text} fits inside {@code box} at {@code style} (wrapping to the box width if the mode allows). */
    public boolean fits(String text, TextBox box, TextStyle style) {
        float maxWidth = style.wrap() == WrapMode.NONE ? 0f : box.width();
        TextBounds b = measure(text, style, maxWidth);
        return b.width() <= box.width() + EPS && b.height() <= box.height() + EPS;
    }

    /**
     * The largest pixel size in {@code [minPx, maxPx]} at which {@code text} fits inside {@code box}, to ~0.1px.
     * Returns {@code 0} if it doesn't fit even at {@code minPx}. Fit is monotonic in size, so this binary-searches.
     */
    public float largestSizeThatFits(String text, TextBox box, TextStyle style, float minPx, float maxPx) {
        if (!fits(text, box, style.withSize(minPx))) {
            return 0f;
        }
        float lo = minPx;
        float hi = maxPx;
        for (int iter = 0; iter < 24 && hi - lo > 0.1f; iter++) {
            float mid = (lo + hi) / 2f;
            if (fits(text, box, style.withSize(mid))) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Fit {@code text} to {@code box} by shrinking to the largest size in {@code [minPx, maxPx]} that fits, then
     * place it. If nothing fits down to {@code minPx}, places at {@code minPx} anyway (text may overflow the box).
     */
    public PlacedText fitAndPlace(String text, TextBox box, TextStyle style, float minPx, float maxPx) {
        float size = largestSizeThatFits(text, box, style, minPx, maxPx);
        if (size <= 0f) {
            size = minPx;
        }
        return place(text, box, style.withSize(size));
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private TextLine makeLine(String text, int start, int end, boolean hardBreak, float pixelSize) {
        int e = end;
        while (e > start && isSpaceOrTab(text.charAt(e - 1))) {
            e--;   // trim trailing whitespace so alignment/measurement use the visible width
        }
        String s = text.substring(start, e);
        return new TextLine(s, glyphs.measure(s, pixelSize), hardBreak);
    }

    /**
     * True if a visual break is permissible between {@code s[i-1]} and {@code s[i]}: whitespace boundaries
     * (primary), and letter↔punctuation / letter↔digit transitions (secondary, for long unbreakable runs).
     */
    public static boolean canBreakBetween(String s, int i) {
        if (i <= 0 || i >= s.length()) {
            return false;
        }
        char prev = s.charAt(i - 1);
        char cur = s.charAt(i);
        if (Character.isWhitespace(prev) || Character.isWhitespace(cur)) {
            return true;
        }
        boolean prevLetter = Character.isLetter(prev);
        boolean curLetter = Character.isLetter(cur);
        boolean prevDigit = Character.isDigit(prev);
        boolean curDigit = Character.isDigit(cur);
        boolean prevPunct = !prevLetter && !prevDigit;
        boolean curPunct = !curLetter && !curDigit;
        if ((prevLetter && curPunct) || (prevPunct && curLetter)) {
            return true;
        }
        return (prevLetter && curDigit) || (prevDigit && curLetter);
    }

    private float measureRange(String text, int from, int to, float pixelSize) {
        float w = 0f;
        int j = from;
        while (j < to) {
            int cp = text.codePointAt(j);
            j += Character.charCount(cp);
            w += glyphs.advance(cp, pixelSize);
        }
        return w;
    }

    /** Largest end index in ({@code from}, {@code hi}] whose range width fits {@code maxWidth}; at least one codepoint. */
    private int largestPrefixWithin(String text, int from, int hi, float pixelSize, float maxWidth) {
        int j = from;
        float w = 0f;
        int last = from;
        while (j < hi) {
            int cp = text.codePointAt(j);
            int next = j + Character.charCount(cp);
            float nw = w + glyphs.advance(cp, pixelSize);
            if (nw > maxWidth && j > from) {
                return j;
            }
            w = nw;
            j = next;
            last = j;
        }
        return last;
    }

    private static boolean isSpaceOrTab(char c) {
        return c == ' ' || c == '\t';
    }

    private static int spaceCount(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') {
                c++;
            }
        }
        return c;
    }
}
