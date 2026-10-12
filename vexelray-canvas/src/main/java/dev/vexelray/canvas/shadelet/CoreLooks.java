package dev.vexelray.canvas.shadelet;

import dev.vexelray.canvas.CanvasVertex;

import java.util.List;

/**
 * The looks the canvas's kinds draw with: each kind is a core source and its painter. They come first in every look
 * table, in kind order, so each kind's element record is at an offset fixed for every application, and
 * {@link dev.vexelray.canvas.Canvas} writes it into a vertex without knowing whose table it will be.
 */
public final class CoreLooks {

    private static final List<Look> BY_KIND = List.of(
            Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(CoreShadelets.FILL.name())),
            Look.of(Look.Use.of(CoreShadelets.MSDF.name()), Look.Use.of(CoreShadelets.GLYPH.name())),
            Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(CoreShadelets.SHADOW.name())),
            Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(CoreShadelets.STROKE.name())),
            Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(CoreShadelets.EMBOSS.name())),
            Look.of(Look.Use.of(CoreShadelets.BOX.name()), Look.Use.of(CoreShadelets.IMAGE_FILL.name())),
            Look.of(Look.Use.of(CoreShadelets.FIELD.name()), Look.Use.of(CoreShadelets.FIELD_FILL.name())));

    private static final int[] RECORDS = seed(new LookTable(CoreShadelets.composition().manifest()));

    private CoreLooks() {
    }

    /** The look a kind ({@link CanvasVertex}'s {@code KIND_*}) draws with. */
    public static Look forKind(int kind) {
        return BY_KIND.get(kind);
    }

    /** The element record a vertex of {@code kind} names: the same in every table {@link #table} starts. */
    public static int record(int kind) {
        return RECORDS[kind];
    }

    /**
     * A look table for a shader with {@code manifest}, the kinds' looks already in it. The shader must hold the whole
     * core set, in any order (a look names its shadelets, not their places): without one of them, a kind's look would
     * lose it and every record after it would move.
     */
    public static LookTable table(Manifest manifest) {
        LookTable table = new LookTable(manifest);
        int[] records = seed(table);
        for (int k = 0; k < records.length; k++) {
            if (records[k] != RECORDS[k]) {
                throw new IllegalArgumentException("this shader lacks some of the core set, so the canvas's kinds "
                        + "cannot all be drawn. Compose it as CoreShadelets.composition().with(...)");
            }
        }
        return table;
    }

    /**
     * The tables for the canvas's own shader ({@link CoreShadelets#composition()}): what a host uploads to
     * {@link Composition#TABLES} when it draws with {@link dev.vexelray.canvas.CanvasShader}.
     */
    public static float[] coreTables() {
        return table(CoreShadelets.composition().manifest()).floats();
    }

    private static int[] seed(LookTable table) {
        int[] records = new int[BY_KIND.size()];
        for (int kind = 0; kind < records.length; kind++) {
            records[kind] = table.record(BY_KIND.get(kind));
        }
        return records;
    }

    static {
        if (BY_KIND.size() != CanvasVertex.KIND_FIELD + 1) {
            throw new AssertionError("a kind without a look");
        }
    }
}
