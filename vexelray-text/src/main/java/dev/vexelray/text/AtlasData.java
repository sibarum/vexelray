package dev.vexelray.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed contents of an msdf-atlas-gen JSON output — the atlas header, font metrics, and per-codepoint glyph
 * data. Adapted from Dasum (sibarum.dasum.gui.core.text.AtlasData). Use {@link #glyph(int)} for codepoint lookup.
 */
public record AtlasData(AtlasInfo info, FontMetrics metrics, Map<Integer, GlyphData> glyphs,
                        List<AtlasData> extraFaces) {

    /** A single-face atlas — the shape every caller before multi-font support constructed. */
    public AtlasData(AtlasInfo info, FontMetrics metrics, Map<Integer, GlyphData> glyphs) {
        this(info, metrics, glyphs, List.of());
    }

    /** Codepoint of the synthetic "missing glyph" (tofu) box, if the atlas bakes one (U+FFFD). */
    public static final int NOTDEF_CODEPOINT = 0xFFFD;

    public GlyphData glyph(int codepoint) {
        return glyphs.get(codepoint);
    }

    /** The baked missing-glyph box, or {@code null} if this atlas lacks one. */
    public GlyphData notdef() {
        return glyphs.get(NOTDEF_CODEPOINT);
    }

    /** How many faces this atlas carries (1 for a single-font atlas). */
    public int faceCount() {
        return 1 + extraFaces.size();
    }

    /**
     * The atlas restricted to one face: that face's metrics and glyphs over the same image. Face 0 is the
     * primary (this object); indices are clamped, so a UI asking for a face the atlas doesn't carry degrades
     * to the primary instead of failing. Every face resolves the shared U+FFFD missing-glyph box.
     */
    public AtlasData face(int index) {
        if (index <= 0 || extraFaces.isEmpty()) {
            return this;
        }
        return extraFaces.get(Math.min(index, extraFaces.size()) - 1);
    }

    /** Load an atlas JSON from the classpath (e.g. {@code "/dev/vexelray/text/atlas/primary.json"}). */
    public static AtlasData loadFromResource(String classpathPath) {
        try (InputStream in = AtlasData.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Atlas JSON not found on classpath: " + classpathPath);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading atlas JSON " + classpathPath, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static AtlasData parse(String jsonText) {
        Map<String, Object> root = Json.parseObject(jsonText);

        Map<String, Object> atlasObj = (Map<String, Object>) root.get("atlas");
        AtlasInfo info = new AtlasInfo(
                (String) atlasObj.get("type"),
                asFloat(atlasObj.get("distanceRange")),
                asFloat(atlasObj.get("size")),
                asInt(atlasObj.get("width")),
                asInt(atlasObj.get("height")),
                !"top".equalsIgnoreCase(String.valueOf(atlasObj.getOrDefault("yOrigin", "bottom"))));

        List<Object> topGlyphs = (List<Object>) root.get("glyphs");
        if (topGlyphs != null) {
            // Single-font atlas: metrics and glyphs live at the top level.
            FontMetrics metrics = parseMetrics((Map<String, Object>) root.get("metrics"));
            Map<Integer, GlyphData> glyphs = new HashMap<>();
            parseGlyphsInto(topGlyphs, glyphs);
            return new AtlasData(info, metrics, glyphs);
        }

        // Multi-font atlas (msdf-atlas-gen -and): per-font data grouped into "variants" over one shared image.
        // The first variant is the primary face; the rest become extraFaces in order. Each extra face keeps its
        // own metrics but backs its glyph map with the primary's, so a codepoint the face lacks renders with the
        // primary font (right glyph, wrong face) rather than as the missing-glyph box — and the baked U+FFFD
        // (spliced into the primary's glyphs) resolves from every face the same way.
        List<Object> variants = (List<Object>) root.get("variants");
        if (variants == null || variants.isEmpty()) {
            throw new IllegalStateException("Atlas JSON has neither 'glyphs' nor 'variants'");
        }
        Map<String, Object> primary = (Map<String, Object>) variants.get(0);
        FontMetrics primaryMetrics = parseMetrics((Map<String, Object>) primary.get("metrics"));
        Map<Integer, GlyphData> primaryGlyphs = new HashMap<>();
        parseGlyphsInto((List<Object>) primary.get("glyphs"), primaryGlyphs);

        List<AtlasData> extra = new java.util.ArrayList<>();
        for (int i = 1; i < variants.size(); i++) {
            Map<String, Object> v = (Map<String, Object>) variants.get(i);
            FontMetrics faceMetrics = parseMetrics((Map<String, Object>) v.get("metrics"));
            Map<Integer, GlyphData> faceGlyphs = new HashMap<>(primaryGlyphs);
            Map<Integer, GlyphData> own = new HashMap<>();
            parseGlyphsInto((List<Object>) v.get("glyphs"), own);
            faceGlyphs.putAll(own);
            extra.add(new AtlasData(info, faceMetrics, faceGlyphs));
        }
        return new AtlasData(info, primaryMetrics, primaryGlyphs, List.copyOf(extra));
    }

    private static FontMetrics parseMetrics(Map<String, Object> m) {
        return new FontMetrics(
                asFloat(m.get("emSize")), asFloat(m.get("lineHeight")), asFloat(m.get("ascender")),
                asFloat(m.get("descender")), asFloat(m.get("underlineY")), asFloat(m.get("underlineThickness")));
    }

    /** Parse glyph entries into {@code out}; first writer wins (primary font overrides supplementary fonts). */
    @SuppressWarnings("unchecked")
    private static void parseGlyphsInto(List<Object> glyphList, Map<Integer, GlyphData> out) {
        for (Object g : glyphList) {
            Map<String, Object> gm = (Map<String, Object>) g;
            int cp = asInt(gm.get("unicode"));
            float advance = asFloat(gm.get("advance"));
            Rect plane = asRect((Map<String, Object>) gm.get("planeBounds"));
            Rect atlas = asRect((Map<String, Object>) gm.get("atlasBounds"));
            out.putIfAbsent(cp, new GlyphData(cp, advance, plane, atlas));
        }
    }

    private static Rect asRect(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new Rect(asFloat(m.get("left")), asFloat(m.get("bottom")), asFloat(m.get("right")), asFloat(m.get("top")));
    }

    private static float asFloat(Object o) {
        return o == null ? 0f : ((Number) o).floatValue();
    }

    private static int asInt(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
