package dev.vexelray.msdf;

import java.io.File;
import java.util.List;

/**
 * One entry under the plugin's {@code <families>} block: a font family, every face of it, every glyph of each face.
 * The high-level counterpart of {@link AtlasConfig}, which bakes one code-point list from one file.
 *
 * <p>Nothing here has to be listed for the common case. The faces are found by reading the fonts in
 * {@link #sources}, and the glyphs are every code point each face maps. {@link #match}, {@link #styles} and
 * {@link #charset} only narrow.
 */
public class FamilyConfig {

    /**
     * The key the application selects this family by, and the output subdirectory: {@code sans} writes
     * {@code sans/regular.json}, {@code sans/bold.json}, and so on.
     */
    public String name;

    /** Font files, or directories whose {@code .ttf}/{@code .otf} files are read (not recursively). */
    public List<File> sources;

    /**
     * The typographic family to take from {@link #sources}, when they hold more than one — a directory of Noto
     * holds Noto Sans, Noto Sans Mono and more. Matched case-insensitively against {@code name} ID 16, else 1.
     * Without it, sources holding several families fail the build and list them.
     */
    public String match;

    /**
     * The faces to bake, as style keys separated by spaces or commas ({@code regular bold italic bold-italic}).
     * Default: every face found. A key asked for and not found fails the build.
     */
    public String styles;

    /**
     * Narrows each face to these code points, in the same syntax as {@link AtlasConfig#charset} (presets
     * {@code ascii} and {@code latin-1}, or msdf-atlas-gen charset content). Default: every code point the face
     * maps, except controls and U+FFFD, which is the synthesized missing-glyph box in every face.
     */
    public String charset;

    /** Glyph em size in pixels within the atlas. */
    public int fontSize = 32;

    /** SDF distance range in output pixels (the {@code pxrange}). */
    public int pxRange = 4;
}
