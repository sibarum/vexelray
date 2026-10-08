package dev.vexelray.msdf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What the build needs to know about a font file without rendering it: its family, the style of each face it
 * holds, and the code points it maps. A reader for five sfnt tables ({@code name}, {@code OS/2}, {@code head},
 * {@code cmap}, {@code fvar}) and nothing else — outlines are {@code msdf-atlas-gen}'s business.
 *
 * <p>Build-time only, like the rest of this plugin. The runtime never reads a font file: it reads the atlases this
 * build makes from one.
 */
final class SfntFont {

    private static final int TRUETYPE = 0x00010000;
    private static final int OPENTYPE_CFF = 0x4F54544F; // 'OTTO'
    private static final int APPLE_TRUE = 0x74727565;   // 'true'
    private static final int COLLECTION = 0x74746366;   // 'ttcf'

    /** One axis of a variable font. */
    record Axis(String tag, float min, float defaultValue, float max) {
    }

    /** One named instance of a variable font: its name and its position on every axis, in axis order. */
    record Instance(String name, float[] coordinates) {
    }

    final Path path;
    /** Typographic family ({@code name} ID 16), else the legacy family (ID 1). */
    final String family;
    /** Typographic subfamily ({@code name} ID 17), else the legacy subfamily (ID 2) — "Bold Italic". */
    final String subfamily;
    /** {@code OS/2.usWeightClass}: 100–900. 400 when the table is absent. */
    final int weightClass;
    /** {@code OS/2.usWidthClass}: 1–9, 5 is normal. */
    final int widthClass;
    final boolean italic;
    final boolean oblique;
    final List<Axis> axes;
    final List<Instance> instances;
    private final SortedSet<Integer> codepoints;

    private SfntFont(Path path, String family, String subfamily, int weightClass, int widthClass, boolean italic,
                     boolean oblique, List<Axis> axes, List<Instance> instances, SortedSet<Integer> codepoints) {
        this.path = path;
        this.family = family;
        this.subfamily = subfamily;
        this.weightClass = weightClass;
        this.widthClass = widthClass;
        this.italic = italic;
        this.oblique = oblique;
        this.axes = axes;
        this.instances = instances;
        this.codepoints = codepoints;
    }

    boolean isVariable() {
        return !axes.isEmpty();
    }

    /** Every code point the font's best Unicode {@code cmap} subtable maps to a glyph other than .notdef. */
    SortedSet<Integer> codepoints() {
        return codepoints;
    }

    static SfntFont read(Path path) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(path));
        int version = b.getInt(0);
        if (version == COLLECTION) {
            throw new IOException(path.getFileName() + " is a font collection (.ttc). Collections hold several"
                    + " families or faces in one file and are not read here; use the individual .ttf/.otf files.");
        }
        if (version != TRUETYPE && version != OPENTYPE_CFF && version != APPLE_TRUE) {
            throw new IOException(path.getFileName() + " is not a TrueType/OpenType font (sfnt version 0x"
                    + Integer.toHexString(version) + ").");
        }
        Map<String, int[]> tables = new HashMap<>();
        int count = u16(b, 4);
        for (int i = 0; i < count; i++) {
            int rec = 12 + i * 16;
            tables.put(tag(b, rec), new int[] {(int) u32(b, rec + 8), (int) u32(b, rec + 12)});
        }

        Map<Integer, String> names = names(b, tables.get("name"));
        String family = first(names, 16, 1);
        String subfamily = first(names, 17, 2);
        if (family == null) {
            throw new IOException(path.getFileName() + " has no family name in its 'name' table.");
        }

        int weight = 400;
        int width = 5;
        boolean italic = false;
        boolean oblique = false;
        int[] os2 = tables.get("OS/2");
        if (os2 != null) {
            weight = u16(b, os2[0] + 4);
            width = u16(b, os2[0] + 6);
            int fsSelection = u16(b, os2[0] + 62);
            italic = (fsSelection & 0x0001) != 0;
            oblique = (fsSelection & 0x0200) != 0;
        } else {
            int[] head = tables.get("head");
            if (head != null) {
                int macStyle = u16(b, head[0] + 44);
                weight = (macStyle & 1) != 0 ? 700 : 400;
                italic = (macStyle & 2) != 0;
            }
        }

        List<Axis> axes = new ArrayList<>();
        List<Instance> instances = new ArrayList<>();
        int[] fvar = tables.get("fvar");
        if (fvar != null) {
            readFvar(b, fvar[0], names, axes, instances);
        }

        int[] cmap = tables.get("cmap");
        if (cmap == null) {
            throw new IOException(path.getFileName() + " has no 'cmap' table, so no code point maps to a glyph.");
        }
        return new SfntFont(path, family, subfamily == null ? "Regular" : subfamily, weight, width, italic, oblique,
                List.copyOf(axes), List.copyOf(instances), codepoints(b, cmap[0], path));
    }

    // --- name ------------------------------------------------------------------------------------------------

    /**
     * Name records by ID, preferring Windows/Unicode English (3/1/0x409), then any Windows Unicode record, then
     * Mac Roman English. A font names itself in several places; these are the ones every tool agrees on.
     */
    private static Map<Integer, String> names(ByteBuffer b, int[] table) {
        Map<Integer, String> out = new HashMap<>();
        Map<Integer, Integer> rank = new HashMap<>();
        if (table == null) {
            return out;
        }
        int base = table[0];
        int count = u16(b, base + 2);
        int strings = base + u16(b, base + 4);
        for (int i = 0; i < count; i++) {
            int rec = base + 6 + i * 12;
            int platform = u16(b, rec);
            int encoding = u16(b, rec + 2);
            int language = u16(b, rec + 4);
            int id = u16(b, rec + 6);
            int length = u16(b, rec + 8);
            int offset = u16(b, rec + 10);
            int r;
            String s;
            if (platform == 3 && (encoding == 1 || encoding == 10)) {
                r = language == 0x409 ? 3 : 2;
                s = new String(bytes(b, strings + offset, length), StandardCharsets.UTF_16BE);
            } else if (platform == 0) {
                r = 2;
                s = new String(bytes(b, strings + offset, length), StandardCharsets.UTF_16BE);
            } else if (platform == 1 && encoding == 0 && language == 0) {
                r = 1;
                s = new String(bytes(b, strings + offset, length), StandardCharsets.ISO_8859_1);
            } else {
                continue;
            }
            if (r > rank.getOrDefault(id, 0)) {
                rank.put(id, r);
                out.put(id, s.trim());
            }
        }
        return out;
    }

    private static String first(Map<Integer, String> names, int preferred, int fallback) {
        String s = names.get(preferred);
        return s != null && !s.isEmpty() ? s : names.get(fallback);
    }

    // --- fvar ------------------------------------------------------------------------------------------------

    private static void readFvar(ByteBuffer b, int base, Map<Integer, String> names, List<Axis> axes,
                                 List<Instance> instances) {
        int axesOffset = u16(b, base + 4);
        int axisCount = u16(b, base + 8);
        int axisSize = u16(b, base + 10);
        int instanceCount = u16(b, base + 12);
        int instanceSize = u16(b, base + 14);
        int a = base + axesOffset;
        for (int i = 0; i < axisCount; i++) {
            int rec = a + i * axisSize;
            axes.add(new Axis(tag(b, rec), fixed(b, rec + 4), fixed(b, rec + 8), fixed(b, rec + 12)));
        }
        int in = a + axisCount * axisSize;
        for (int i = 0; i < instanceCount; i++) {
            int rec = in + i * instanceSize;
            int nameId = u16(b, rec);
            float[] coordinates = new float[axisCount];
            for (int k = 0; k < axisCount; k++) {
                coordinates[k] = fixed(b, rec + 4 + k * 4);
            }
            String name = names.getOrDefault(nameId, "instance " + i);
            instances.add(new Instance(name, coordinates));
        }
    }

    // --- cmap ------------------------------------------------------------------------------------------------

    /**
     * The mapped code points from the widest Unicode subtable: format 12 (full repertoire) when there is one, else
     * format 4 (BMP). Code points that map to glyph 0 are not mapped — that glyph is .notdef.
     */
    private static SortedSet<Integer> codepoints(ByteBuffer b, int base, Path path) throws IOException {
        int count = u16(b, base + 2);
        int format4 = -1;
        int format12 = -1;
        for (int i = 0; i < count; i++) {
            int rec = base + 4 + i * 8;
            int platform = u16(b, rec);
            int encoding = u16(b, rec + 2);
            int sub = base + (int) u32(b, rec + 4);
            boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
            if (!unicode) {
                continue;
            }
            int format = u16(b, sub);
            if (format == 12 && format12 < 0) {
                format12 = sub;
            } else if (format == 4 && format4 < 0) {
                format4 = sub;
            }
        }
        SortedSet<Integer> out = new TreeSet<>();
        if (format12 >= 0) {
            long groups = u32(b, format12 + 12);
            for (long g = 0; g < groups; g++) {
                int rec = format12 + 16 + (int) g * 12;
                long start = u32(b, rec);
                long end = u32(b, rec + 4);
                long glyph = u32(b, rec + 8);
                for (long cp = start; cp <= end; cp++) {
                    if (glyph + (cp - start) != 0) {
                        out.add((int) cp);
                    }
                }
            }
            return out;
        }
        if (format4 >= 0) {
            int segX2 = u16(b, format4 + 6);
            int ends = format4 + 14;
            int starts = ends + segX2 + 2;
            int deltas = starts + segX2;
            int ranges = deltas + segX2;
            for (int s = 0; s < segX2 / 2; s++) {
                int end = u16(b, ends + s * 2);
                int start = u16(b, starts + s * 2);
                int delta = (short) u16(b, deltas + s * 2);
                int rangeAt = ranges + s * 2;
                int rangeOffset = u16(b, rangeAt);
                for (int cp = start; cp <= end && cp != 0xFFFF; cp++) {
                    int glyph;
                    if (rangeOffset == 0) {
                        glyph = (cp + delta) & 0xFFFF;
                    } else {
                        int at = rangeAt + rangeOffset + (cp - start) * 2;
                        glyph = u16(b, at);
                        if (glyph != 0) {
                            glyph = (glyph + delta) & 0xFFFF;
                        }
                    }
                    if (glyph != 0) {
                        out.add(cp);
                    }
                }
            }
            return out;
        }
        throw new IOException(path.getFileName() + " has no Unicode cmap subtable of format 4 or 12.");
    }

    // --- primitives ------------------------------------------------------------------------------------------

    private static int u16(ByteBuffer b, int at) {
        return b.getShort(at) & 0xFFFF;
    }

    private static long u32(ByteBuffer b, int at) {
        return b.getInt(at) & 0xFFFFFFFFL;
    }

    private static float fixed(ByteBuffer b, int at) {
        return b.getInt(at) / 65536f;
    }

    private static String tag(ByteBuffer b, int at) {
        return new String(bytes(b, at, 4), StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(ByteBuffer b, int at, int length) {
        byte[] out = new byte[length];
        b.get(at, out);
        return out;
    }
}
