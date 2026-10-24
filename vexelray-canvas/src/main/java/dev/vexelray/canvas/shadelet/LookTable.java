package dev.vexelray.canvas.shadelet;

import dev.vexelray.diag.Diagnostics;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes looks into the table a composed shader reads ({@link Composition}'s layout), against the {@link Manifest}
 * of that shader.
 *
 * <p>Built when a theme is installed or changed, and added to when a look is first drawn; never rewritten per frame.
 * A modifier or painter the shader was not composed with is <b>left out</b> of the look and said once through
 * {@link Diagnostics#dropped}: the element still draws, without that effect. A source the shader lacks is an error,
 * since there would be nothing to draw; so is a style whose floats are not its layout's, which is a theme written
 * against a different shadelet.
 *
 * <p>The table lives below {@link Composition#RECORDS_BASE}; the floats above it are the frame's element records,
 * which the canvas writes. A host uploads {@link #floats()} when {@link #version()} has moved, and the frame's records
 * every frame.
 */
public final class LookTable {

    /** What the table knows of a look it has written: where, how much an element carries, and its parts. */
    private record Written(int offset, int instanceFloats, Part source, List<Part> modifiers, List<Part> painters) {
    }

    private final Manifest manifest;
    private final Map<String, Shadelet> shadelets = new HashMap<>();
    private float[] data = new float[256];
    private int size;
    private int version;
    private final Map<Look, Written> looks = new HashMap<>();
    private final Map<Look, Integer> records = new HashMap<>();

    /** A table for a shader with {@code manifest}: every look's reach is 0, which is right for the core set. */
    public LookTable(Manifest manifest) {
        this.manifest = manifest;
    }

    /** A table for {@code composition}'s shader, which also knows each look's {@linkplain #reach reach}. */
    public LookTable(Composition composition) {
        this(composition.manifest());
        for (Shadelet shadelet : composition.shadelets()) {
            shadelets.put(shadelet.name(), shadelet);
        }
    }

    /** What the shader this table is for was compiled with. */
    public Manifest manifest() {
        return manifest;
    }

    /** The look's offset in the table, adding it (and its styles) the first time it is seen. */
    public int look(Look look) {
        return written(look).offset;
    }

    /** Floats of per-instance values an element of this look carries in its record, after its look. */
    public int instanceFloats(Look look) {
        return written(look).instanceFloats;
    }

    /**
     * How far past its geometry an element of this look draws, in canvas units, for an element that carries no
     * per-instance values: see {@link #reach(Look, float[], float, float)}.
     */
    public float reach(Look look) {
        return reach(look, new float[instanceFloats(look)], 0f, 0f);
    }

    /**
     * How far past its geometry an element of this look draws, in canvas units: the source's reach, plus each
     * modifier's (a weight moves the edge out, or in), plus the farthest painter's (an outline reaches past the edge
     * as moved). Never less than 0. The canvas grows the element's quad by it.
     *
     * @param instance the element's per-instance values, in look order
     * @param halfW    the element's own geometry's half width, which a source's reach may be measured from
     * @param halfH    its half height
     */
    public float reach(Look look, float[] instance, float halfW, float halfH) {
        Written w = written(look);
        float reach = w.source.reach(instance, halfW, halfH);
        for (Part m : w.modifiers) {
            reach += m.reach(instance, halfW, halfH);
        }
        float painted = 0f;
        for (Part p : w.painters) {
            painted = Math.max(painted, p.reach(instance, halfW, halfH));
        }
        return Math.max(0f, reach + painted);
    }

    /**
     * One shadelet of a written look: its style's shared floats, and where its per-instance floats are among the
     * element's (counted from the first after the record's look). The shadelet is null for a table built from a
     * manifest alone, which then reaches nowhere.
     */
    private record Part(Shadelet shadelet, float[] shared, int instanceAt, int instanceFloats) {
        float reach(float[] instance, float halfW, float halfH) {
            if (shadelet == null) {
                return 0f;
            }
            float[] mine = Arrays.copyOfRange(instance, instanceAt, instanceAt + instanceFloats);
            return shadelet.reach(shared, mine, halfW, halfH);
        }
    }

    /**
     * An element record that holds only its look: what an element whose look has no per-instance fields names, so
     * any number of such elements share it. Its offset is what the element's vertices carry. Written once a look.
     */
    public int record(Look look) {
        Integer known = records.get(look);
        if (known != null) {
            return known;
        }
        int lookOffset = look(look);
        int offset = size;
        put(lookOffset);
        records.put(look, offset);
        version++;
        return offset;
    }

    /** Moves whenever the table does: a host re-uploads {@link #floats()} when it differs from the last it saw. */
    public int version() {
        return version;
    }

    /** The table, to upload at offset 0. */
    public float[] floats() {
        return Arrays.copyOf(data, size);
    }

    private Written written(Look look) {
        Written known = looks.get(look);
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
        Part sourcePart = part(look.source(), instance, source.instanceFloats());
        instance += source.instanceFloats();
        put(modifiers.size());
        List<Part> modifierParts = new java.util.ArrayList<>();
        for (int k = 0; k < modifiers.size(); k++) {
            put(modifiers.get(k).entry.id());
            put(modifierStyles[k]);
            put(instance);
            modifierParts.add(part(modifiers.get(k).use, instance, modifiers.get(k).entry.instanceFloats()));
            instance += modifiers.get(k).entry.instanceFloats();
        }
        put(painters.size());
        List<Part> painterParts = new java.util.ArrayList<>();
        for (int k = 0; k < painters.size(); k++) {
            put(painters.get(k).entry.id());
            put(painterStyles[k]);
            put(instance);
            painterParts.add(part(painters.get(k).use, instance, painters.get(k).entry.instanceFloats()));
            instance += painters.get(k).entry.instanceFloats();
        }

        Written written = new Written(offset, instance - 1, sourcePart, List.copyOf(modifierParts),
                List.copyOf(painterParts));
        looks.put(look, written);
        version++;
        return written;
    }

    /** {@code use} as a part whose per-instance floats start at {@code instanceAt} in the record (1 is the first). */
    private Part part(Look.Use use, int instanceAt, int floats) {
        return new Part(shadelets.get(use.shadelet()), use.shared(), instanceAt - 1, floats);
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
        if (size == Composition.RECORDS_BASE) {
            throw new IllegalStateException("the look table is full at " + Composition.RECORDS_BASE
                    + " floats: looks are meant to be tens, shared by elements, not one per element");
        }
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = v;
    }
}
