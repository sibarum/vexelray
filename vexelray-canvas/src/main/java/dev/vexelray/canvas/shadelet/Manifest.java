package dev.vexelray.canvas.shadelet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a shader was composed of: each shadelet's name, role, id in the shader's dispatch, and the layout it reads
 * its style with (floats and a hash). The look table is written against it, and it is what tells a style whose
 * shadelet was not compiled in from one that was.
 *
 * <p>Text, one entry a line, so it ships in a jar beside the shader's SPIR-V and is read without reflection:
 * {@code role id name sharedFloats instanceFloats layoutHash}.
 */
public record Manifest(List<Entry> entries) {

    public enum Role { SOURCE, MODIFIER, PAINTER }

    /**
     * One compiled shadelet.
     *
     * @param name           its name
     * @param role           its role
     * @param id             its place among its role's, which the shader dispatches on
     * @param sharedFloats   floats of its style in the style table
     * @param instanceFloats floats of its style in an element's record
     * @param layoutHash     {@link StyleLayout#hash()} of the layout the shader reads
     */
    public record Entry(String name, Role role, int id, int sharedFloats, int instanceFloats, long layoutHash) {
        static Entry of(Shadelet shadelet, Role role, int id) {
            StyleLayout layout = shadelet.style();
            return new Entry(shadelet.name(), role, id, layout.sharedFloats(), layout.instanceFloats(), layout.hash());
        }
    }

    public Manifest {
        entries = List.copyOf(entries);
    }

    /** The entry named, if the shader has it. */
    public Optional<Entry> find(String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    /** The manifest as text: see the class. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.role()).append(' ').append(e.id()).append(' ').append(e.name()).append(' ')
                    .append(e.sharedFloats()).append(' ').append(e.instanceFloats()).append(' ')
                    .append(Long.toHexString(e.layoutHash())).append('\n');
        }
        return sb.toString();
    }

    /**
     * A digest of the whole manifest: which shadelets, at which ids, reading which layouts. A composed shader's name
     * carries it, so SPIR-V compiled against other layouts is never loaded in its place (see
     * {@link dev.vexelray.canvas.CanvasShader.Composed}).
     */
    public long hash() {
        String text = toText();
        long h = 0xcbf29ce484222325L;   // FNV-1a, 64-bit, as StyleLayout#hash
        for (int k = 0; k < text.length(); k++) {
            h ^= text.charAt(k);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Reads {@link #toText()}'s form back. */
    public static Manifest parse(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.trim().split(" ");
            if (p.length != 6) {
                throw new IllegalArgumentException("not a manifest line: '" + line + "'");
            }
            entries.add(new Entry(p[2], Role.valueOf(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Long.parseUnsignedLong(p[5], 16)));
        }
        return new Manifest(entries);
    }
}
