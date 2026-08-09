package dev.vexelray.text;

/**
 * Per-glyph metrics from msdf-atlas-gen output. For whitespace glyphs only {@code unicode} and {@code advance}
 * are meaningful; {@code planeBounds} and {@code atlasBounds} are {@code null}.
 *
 * @param unicode     codepoint
 * @param advance     glyph advance in em units (multiply by em pixel size for screen pixels)
 * @param planeBounds glyph quad relative to the baseline in em units, Y-up, or {@code null} for whitespace
 * @param atlasBounds rectangle in the atlas image in pixel coords (atlas yOrigin), or {@code null} for whitespace
 */
public record GlyphData(int unicode, float advance, Rect planeBounds, Rect atlasBounds) {
}
