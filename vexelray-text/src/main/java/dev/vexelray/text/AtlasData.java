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
public record AtlasData(AtlasInfo info, FontMetrics metrics, Map<Integer, GlyphData> glyphs) {

    /** Codepoint of the synthetic "missing glyph" (tofu) box, if the atlas bakes one (U+FFFD). */
    public static final int NOTDEF_CODEPOINT = 0xFFFD;

    public GlyphData glyph(int codepoint) {
        return glyphs.get(codepoint);
    }

    /** The baked missing-glyph box, or {@code null} if this atlas lacks one. */
    public GlyphData notdef() {
        return glyphs.get(NOTDEF_CODEPOINT);
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

        FontMetrics metrics;
        Map<Integer, GlyphData> glyphs = new HashMap<>();

        List<Object> topGlyphs = (List<Object>) root.get("glyphs");
        if (topGlyphs != null) {
            // Single-font atlas: metrics and glyphs live at the top level.
            metrics = parseMetrics((Map<String, Object>) root.get("metrics"));
            parseGlyphsInto(topGlyphs, glyphs);
        } else {
            // Multi-font atlas (msdf-atlas-gen -and): per-font data grouped into "variants" over one shared image.
            // Flatten into one map — the first variant is primary: its metrics drive layout and win collisions.
            List<Object> variants = (List<Object>) root.get("variants");
            if (variants == null || variants.isEmpty()) {
                throw new IllegalStateException("Atlas JSON has neither 'glyphs' nor 'variants'");
            }
            Map<String, Object> primary = (Map<String, Object>) variants.get(0);
            metrics = parseMetrics((Map<String, Object>) primary.get("metrics"));
            for (Object v : variants) {
                List<Object> vGlyphs = (List<Object>) ((Map<String, Object>) v).get("glyphs");
                if (vGlyphs != null) {
                    parseGlyphsInto(vGlyphs, glyphs);
                }
            }
        }
        return new AtlasData(info, metrics, glyphs);
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
