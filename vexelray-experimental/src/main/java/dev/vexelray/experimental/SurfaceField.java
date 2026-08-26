package dev.vexelray.experimental;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.surface.Field;
import dev.vexelray.surface.Surface;
import dev.vexelray.surface.SurfaceCompiler;

/**
 * A {@link Surface} rendered through the harness — the adapter that lets surfaces-as-data be compared head to
 * head with the hand-written {@link ShapeField}s, in the same ray-march, under the same camera.
 *
 * <p>This is the bridge the whole surface-compiler design has been aimed at proving. A {@code Surface} is a
 * record tree; {@link SurfaceCompiler} lowers it to the same {@code core} IR a hand-written field emits, with
 * the same 1-Lipschitz guarantee. If that claim holds, a surface plugged in here is indistinguishable from a
 * field written by hand — which is testable by looking at the picture, and that is the point.
 *
 * @param name          label for captures and the report table
 * @param surface       the geometry
 * @param applicability free-form note for the comparison's "general applicability" column
 */
public record SurfaceField(String name, Surface surface, String applicability) implements ShapeField {

    /** Compiled once per call, because {@link ShapeField#sdf} must hand back fresh IR each time. */
    @Override
    public Expr sdf(Expr point) {
        return field().at(point);
    }

    /** The compiled field, for anything that wants the Lipschitz bound rather than the expression. */
    public Field field() {
        return SurfaceCompiler.compile(surface);
    }
}
