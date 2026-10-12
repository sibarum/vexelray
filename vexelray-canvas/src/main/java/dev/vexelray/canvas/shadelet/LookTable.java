package dev.vexelray.canvas.shadelet;

import dev.vexelray.diag.Diagnostics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes looks into the table a composed shader reads ({@link Composition}'s layout), against the {@link Manifest}
 * of that shader.
 *
 * <p>Built when a theme is installed or changed, never per frame. A modifier or painter the shader was not composed
 * with is <b>left out</b> of the look and said once through {@link Diagnostics#dropped}: the element still draws,
 * without that effect. A source the shader lacks is an error, since there would be nothing to draw; so is a style
 * whose floats are not its layout's, which is a theme written against a different shadelet.
 */
public final class LookTable {

    private final Manifest manifest;
    private float[] data = new float[256];
    private int size;
    private final Map<Look, Integer> looks = new LinkedHashMap<>();

    public LookTable(Manifest manifest) {
        this.manifest = manifest;
    }

    /** The look's offset in the table, adding it (and its styles) the first time it is seen. */
    public int look(Look look) {
        Integer known = looks.get(look);
        if (known != null) {
            return known;
        }
        Manifest.Entry source = manifest.find(look.source().shadelet())
                .filter(e -> e.role() == Manifest.Role.SOURCE)
                .orElseThrow(() -> new IllegalArgumentException("source '" + look.source().shadelet()
                        + "' is not in this shader, and a look without its source draws nothing"));
        List<Resolved> modifiers = resolve(look.modifiers(), Manifest.Role.MODIFIER);
        List<Resolved> painters = resolve(look.painters(), Manifest.Role.PAINTER);
        if (painters.size() > Composition.MAX_PAINTERS) {
            throw new IllegalArgumentException("a look has at most " + Composition.MAX_PAINTERS
                    + " painters; this one has " + painters.size() + ": write the stack as one painter");
        }

        // Styles first, so the look's entries can name where they went.
        int sourceStyle = style(source, look.source());
        int[] modifierStyles = modifiers.stream().mapToInt(r -> style(r.entry, r.use)).toArray();
        int[] painterStyles = painters.stream().mapToInt(r -> style(r.entry, r.use)).toArray();

        int offset = size;
        // Instance offsets: after the element record's first float (its look), in look order.
        int instance = 1;
        put(source.id());
        put(sourceStyle);
        put(instance);
        instance += source.instanceFloats();
        put(modifiers.size());
        for (int k = 0; k < modifiers.size(); k++) {
            put(modifiers.get(k).entry.id());
            put(modifierStyles[k]);
            put(instance);
            instance += modifiers.get(k).entry.instanceFloats();
        }
        put(painters.size());
        for (int k = 0; k < painters.size(); k++) {
            put(painters.get(k).entry.id());
            put(painterStyles[k]);
            put(instance);
            instance += painters.get(k).entry.instanceFloats();
        }
        looks.put(look, offset);
        return offset;
    }

    /**
     * An element record that holds only its look: what an element whose look has no per-instance fields names, so
     * any number of such elements share it. Its offset is what the element's vertices carry.
     */
    public int record(Look look) {
        int lookOffset = look(look);
        int offset = size;
        put(lookOffset);
        return offset;
    }

    /** The table, to upload. */
    public float[] floats() {
        return Arrays.copyOf(data, size);
    }

    private record Resolved(Manifest.Entry entry, Look.Use use) {
    }

    private List<Resolved> resolve(List<Look.Use> uses, Manifest.Role role) {
        return uses.stream().flatMap(use -> {
            var entry = manifest.find(use.shadelet()).filter(e -> e.role() == role);
            if (entry.isEmpty()) {
                Diagnostics.dropped("shadelet:" + use.shadelet(),
                        "the " + role.name().toLowerCase(java.util.Locale.ROOT) + " '" + use.shadelet() + "'",
                        "the application's canvas shader was not composed with it, so looks that name it draw "
                                + "without it; add it to the shader's composition to draw it");
                return java.util.stream.Stream.empty();
            }
            return java.util.stream.Stream.of(new Resolved(entry.get(), use));
        }).toList();
    }

    private int style(Manifest.Entry entry, Look.Use use) {
        if (use.shared().length != entry.sharedFloats()) {
            throw new IllegalArgumentException("'" + entry.name() + "' takes " + entry.sharedFloats()
                    + " shared floats; the look gives " + use.shared().length);
        }
        int offset = size;
        for (float v : use.shared()) {
            put(v);
        }
        return offset;
    }

    private void put(float v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = v;
    }
}
