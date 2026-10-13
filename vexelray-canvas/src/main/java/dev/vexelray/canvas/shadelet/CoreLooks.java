package dev.vexelray.canvas.shadelet;

import java.util.List;

/**
 * The looks the canvas's own primitives draw with: each is a core source and its painter. They come first in every
 * look table, in {@link Primitive} order, so each primitive's element record is at an offset fixed for every
 * application, and {@link dev.vexelray.canvas.Canvas} writes it into a vertex without knowing whose table it will be.
 */
public final class CoreLooks {

    /** What the canvas draws of its own, each by a look of the core set. */
    public enum Primitive {
        /** A rounded box, filled in the vertex colour. */
        BOX(CoreShadelets.BOX, CoreShadelets.FILL),
        /** A glyph of text, from the MSDF atlas. */
        TEXT(CoreShadelets.MSDF, CoreShadelets.GLYPH),
        /** An image across a rounded box. */
        IMAGE(CoreShadelets.BOX, CoreShadelets.IMAGE_FILL),
        /** A shape from a distance field, filled in the vertex colour. */
        FIELD(CoreShadelets.FIELD, CoreShadelets.FIELD_FILL);

        private final Look look;

        Primitive(Shadelet source, Shadelet painter) {
            this.look = Look.of(Look.Use.of(source.name()), Look.Use.of(painter.name()));
        }

        /** The look this primitive draws with. */
        public Look look() {
            return look;
        }
    }

    private static final List<Primitive> PRIMITIVES = List.of(Primitive.values());

    private static final int[] RECORDS = seed(new LookTable(CoreShadelets.composition().manifest()));

    private CoreLooks() {
    }

    /** The element record a vertex of {@code primitive} names: the same in every table {@link #table} starts. */
    public static int record(Primitive primitive) {
        return RECORDS[primitive.ordinal()];
    }

    /**
     * A look table for a shader with {@code manifest}, the primitives' looks already in it. The shader must hold the
     * whole core set, in any order (a look names its shadelets, not their places): without one of them, a primitive's
     * look would lose it and every record after it would move.
     */
    public static LookTable table(Manifest manifest) {
        return checked(new LookTable(manifest));
    }

    /** As {@link #table(Manifest)}, for {@code composition}'s shader: the table also knows each look's reach. */
    public static LookTable table(Composition composition) {
        return checked(new LookTable(composition));
    }

    private static LookTable checked(LookTable table) {
        int[] records = seed(table);
        for (int k = 0; k < records.length; k++) {
            if (records[k] != RECORDS[k]) {
                throw new IllegalArgumentException("this shader lacks some of the core set, so the canvas's own "
                        + "primitives cannot all be drawn. Compose it as CoreShadelets.composition().with(...)");
            }
        }
        return table;
    }

    /**
     * The tables for the canvas's own shader ({@link CoreShadelets#composition()}): what a host uploads to
     * {@link Composition#TABLES} when it draws with {@link dev.vexelray.canvas.CanvasShader} and only the canvas's own
     * primitives. A host that also draws looks of its own keeps a {@link #table} and uploads it as it grows.
     */
    public static float[] coreTables() {
        return table(CoreShadelets.composition().manifest()).floats();
    }

    private static int[] seed(LookTable table) {
        int[] records = new int[PRIMITIVES.size()];
        for (Primitive p : PRIMITIVES) {
            records[p.ordinal()] = table.record(p.look());
        }
        return records;
    }
}
