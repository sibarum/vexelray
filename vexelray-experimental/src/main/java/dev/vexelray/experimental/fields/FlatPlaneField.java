package dev.vexelray.experimental.fields;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.experimental.Ir;
import dev.vexelray.experimental.ShapeField;

/**
 * The baseline: an exact flat ground plane, {@code sdf = y}. Perfect distance field, trivially cheap, and the
 * reference for "what does a long flat surface cost/look like" — including the grazing-horizon sphere-trace
 * worst case that motivated curved terrain in the first place.
 */
public final class FlatPlaneField implements ShapeField {

    @Override
    public String name() {
        return "flat-plane";
    }

    @Override
    public Expr sdf(Expr point) {
        return Ir.y(point);
    }

    @Override
    public String applicability() {
        return "exact SDF; trivial; but a long flat plane is the sphere-trace grazing worst case (horizon smear)";
    }
}
