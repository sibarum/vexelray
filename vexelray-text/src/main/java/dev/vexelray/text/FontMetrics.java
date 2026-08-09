package dev.vexelray.text;

/** Font-wide metrics from msdf-atlas-gen, all in em units. */
public record FontMetrics(float emSize, float lineHeight, float ascender,
                          float descender, float underlineY, float underlineThickness) {
}
