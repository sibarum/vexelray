package dev.vexelray.canvas.shadelet;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a shadelet's parameters sit: its fields in order, each some floats wide, each either <b>shared</b> (in the
 * style table, one copy per style, like a material's) or <b>per instance</b> (in the element's own record, written
 * when the element is drawn, like an instancing buffer's). A shadelet reads its fields through a {@link StyleView}
 * and never learns which of the two a field is in.
 *
 * @param fields in table order
 */
public record StyleLayout(List<Field> fields) {

    /** No parameters: a shadelet that reads only the fragment. */
    public static final StyleLayout NONE = new StyleLayout(List.of());

    /**
     * One parameter.
     *
     * @param name        unique within the layout
     * @param width       floats, 1 to 4
     * @param perInstance in the element's record rather than the style table
     */
    public record Field(String name, int width, boolean perInstance) {
        public Field {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a style field has a name");
            }
            if (width < 1 || width > 4) {
                throw new IllegalArgumentException("style field '" + name + "' is " + width + " floats; 1 to 4");
            }
        }
    }

    public StyleLayout {
        fields = List.copyOf(fields);
        List<String> seen = new ArrayList<>();
        for (Field field : fields) {
            if (seen.contains(field.name())) {
                throw new IllegalArgumentException("style field '" + field.name() + "' is declared twice");
            }
            seen.add(field.name());
        }
    }

    public static StyleLayout of(Field... fields) {
        return new StyleLayout(List.of(fields));
    }

    /** A shared field. */
    public static Field shared(String name, int width) {
        return new Field(name, width, false);
    }

    /** A per-instance field. */
    public static Field perInstance(String name, int width) {
        return new Field(name, width, true);
    }

    /** Floats in the style table. */
    public int sharedFloats() {
        return fields.stream().filter(f -> !f.perInstance()).mapToInt(Field::width).sum();
    }

    /** Floats in each element's record. */
    public int instanceFloats() {
        return fields.stream().filter(Field::perInstance).mapToInt(Field::width).sum();
    }

    /** The field named, or an exception naming what there is. */
    public Field field(String name) {
        for (Field field : fields) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        throw new IllegalArgumentException("no style field '" + name + "' in " + fields);
    }

    /** The field's offset among the shared fields, or among the per-instance ones: the floats before it there. */
    public int offset(String name) {
        Field target = field(name);
        int offset = 0;
        for (Field field : fields) {
            if (field == target) {
                return offset;
            }
            if (field.perInstance() == target.perInstance()) {
                offset += field.width();
            }
        }
        throw new AssertionError("unreachable");
    }

    /**
     * A stable digest of the layout: names, widths and placement, in order. A shader compiled against one layout and
     * handed tables written for another reads parameters at the wrong offsets and says nothing; the manifest records
     * this, and the tables are refused when it differs.
     */
    public long hash() {
        long h = 0xcbf29ce484222325L;   // FNV-1a, 64-bit: stable across runs and JVMs, unlike String.hashCode mixing
        for (Field field : fields) {
            String part = field.name() + ':' + field.width() + ':' + (field.perInstance() ? 'i' : 's') + ';';
            for (int k = 0; k < part.length(); k++) {
                h ^= part.charAt(k);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }
}
