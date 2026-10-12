package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;

import java.util.List;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.I32;
import static dev.vexelray.ir.Ir.V2;
import static dev.vexelray.ir.Ir.i;
import static dev.vexelray.ir.Ir.v2;

/**
 * A {@link PaintContext} outside a composition, for running one painter alone in a test kernel: its constructor is
 * the composition's, so this lives in its package (test scope only).
 */
public final class PaintContexts {

    private PaintContexts() {
    }

    /**
     * A context handing a painter {@code distance} at {@code fragment}. It has no source to look elsewhere with: its
     * {@code distanceAt} is a function that returns nothing useful and is never added to a module, so a painter that
     * calls it cannot be run this way (its call would not lower).
     */
    public static PaintContext of(Fragment fragment, Expr distance) {
        Function none = new Function("distanceAt", new Type.FunctionType(V2, List.of(V2, F32, F32, I32, I32)),
                Region.of(new Statement.Return(v2(0.0, 0.0))));
        return new PaintContext(fragment, distance, none, i(0), i(0));
    }
}
