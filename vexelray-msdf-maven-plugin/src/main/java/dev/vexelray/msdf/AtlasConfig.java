package dev.vexelray.msdf;

import java.io.File;

/**
 * One entry under the plugin's {@code <atlases>} block. Field names match the XML element names Maven injects
 * into; a plain POJO with public fields is the friendliest shape for Maven parameter injection.
 */
public class AtlasConfig {

    /** Logical atlas name; output files are {@code <name>.png} and {@code <name>.json}. */
    public String name;

    /** Path to the TTF/OTF input font, typically relative to {@code project.basedir}. */
    public File font;

    /**
     * Charset specifier. Presets {@code ascii} (0x20–0x7E, the default) and {@code latin-1} expand to ranges;
     * anything else is passed through verbatim as msdf-atlas-gen charset-file content (ranges like
     * {@code [0x20, 0x7E]}, literal strings — see msdf-atlas-gen docs).
     */
    public String charset = "ascii";

    /** Atlas image dimensions in pixels (square). */
    public int atlasSize = 1024;

    /** Glyph em size in pixels within the atlas. */
    public int fontSize = 32;

    /** SDF distance range in output pixels (the {@code pxrange}). */
    public int pxRange = 4;
}
