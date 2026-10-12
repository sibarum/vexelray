package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.type.Type;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.i;

/**
 * A shadelet's parameters, as shader expressions: each field read from the style table or from the element's record,
 * whichever its {@link StyleLayout} puts it in. The composition makes one for each place a shadelet runs, with the
 * offsets the look gives it.
 */
public final class StyleView {

    private final StyleLayout layout;
    private final Buffer tables;
    private final Expr sharedBase;
    private final Expr instanceBase;

    /**
     * @param layout       the shadelet's fields
     * @param tables       the one buffer the tables are in
     * @param sharedBase   where this style's shared fields start (an {@code i32})
     * @param instanceBase where this shadelet's per-instance fields start in the element's record (an {@code i32})
     */
    public StyleView(StyleLayout layout, Buffer tables, Expr sharedBase, Expr instanceBase) {
        this.layout = layout;
        this.tables = tables;
        this.sharedBase = sharedBase;
        this.instanceBase = instanceBase;
    }

    public StyleLayout layout() {
        return layout;
    }

    /** A one-float field. */
    public Expr scalar(String name) {
        StyleLayout.Field field = layout.field(name);
        if (field.width() != 1) {
            throw new IllegalArgumentException("style field '" + name + "' is " + field.width() + " floats, not 1");
        }
        return component(field, 0);
    }

    /** A field of 2 to 4 floats, as a vector. */
    public Expr vector(String name) {
        StyleLayout.Field field = layout.field(name);
        if (field.width() == 1) {
            throw new IllegalArgumentException("style field '" + name + "' is one float: read it with scalar");
        }
        List<Expr> components = new ArrayList<>();
        for (int k = 0; k < field.width(); k++) {
            components.add(component(field, k));
        }
        return new Expr.VectorConstruct(new Type.Vector(F32, field.width()), components);
    }

    private Expr component(StyleLayout.Field field, int k) {
        Expr base = field.perInstance() ? instanceBase : sharedBase;
        return new Expr.BufferLoad(tables, add(base, i(layout.offset(field.name()) + k)));
    }
}
