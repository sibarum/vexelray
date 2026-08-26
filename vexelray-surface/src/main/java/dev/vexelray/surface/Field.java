package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.List;

/**
 * A lowered surface: the distance expression, plus what the compiler knows about how fast it changes.
 *
 * <p>The bound is the whole point. Sphere-tracing steps by the field's own value, so it converges only if the
 * field never reports more distance than there really is — which holds exactly when {@code |grad d| <= 1}. Every
 * primitive and combinator in {@link Surface} preserves that; an {@link Surface.Implicit} does not, and is
 * normalised on the way in. Carrying the bound explicitly is what lets the compiler tell those cases apart and
 * charge only the second one (docs/surface-compiler.md §3).
 *
 * @param distance  signed distance at {@link Ir#POINT}: negative inside, zero on the surface
 * @param lipschitz an upper bound on {@code |grad distance|} — {@code 1.0} for a true distance field, larger for
 *                  a field that would overshoot, {@link Double#POSITIVE_INFINITY} when nothing is known
 */
public record Field(Expr distance, double lipschitz) {

    /** The bound a true signed-distance field carries. */
    public static final double EXACT = 1.0;

    /** The bound for an expression the compiler cannot vouch for — an un-normalised implicit. */
    public static final double UNKNOWN = Double.POSITIVE_INFINITY;

    public Field {
        if (distance == null) {
            throw new IllegalArgumentException("distance expression must not be null");
        }
        if (!Ir.F32.equals(distance.type())) {
            throw new IllegalArgumentException("a distance must be a scalar float, got " + distance.type());
        }
        if (Double.isNaN(lipschitz) || lipschitz <= 0) {
            throw new IllegalArgumentException("lipschitz bound must be positive, got " + lipschitz);
        }
    }

    /** A field the compiler knows to be a true distance field. */
    public static Field exact(Expr distance) {
        return new Field(distance, EXACT);
    }

    /** Whether this can be sphere-traced as-is without overshooting. */
    public boolean isMarchable() {
        return lipschitz <= EXACT + 1e-9;
    }

    /**
     * This field's distance expression evaluated at some other point expression — the field relocated into a
     * caller's frame. Lets a compiled surface be dropped into IR that was authored around a different variable,
     * which is how it reaches the research harness and anything else that names its own sample point.
     */
    public Expr at(Expr point) {
        return Substitute.point(distance, point);
    }

    /**
     * This field as a standalone {@code float sdf(vec3)} function — the form both backends consume: the fragment
     * shader calls it (once, rather than inlining the field at all eight of its use sites — D12), and the CPU
     * side lowers the same function to query the same surface. One definition, two targets: render == sim.
     */
    public Function asFunction(String name) {
        return new Function(name, new Type.FunctionType(Ir.F32, List.of(Ir.V3)),
                Region.of(new Statement.Return(distance)));
    }
}
