package dev.vexelray.experimental.fields;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.tools.Noise;
import dev.vexelray.experimental.ShapeField;

import static dev.vexelray.experimental.Ir.add;
import static dev.vexelray.experimental.Ir.div;
import static dev.vexelray.experimental.Ir.f;
import static dev.vexelray.experimental.Ir.mul;
import static dev.vexelray.experimental.Ir.mulS2;
import static dev.vexelray.experimental.Ir.sub;
import static dev.vexelray.experimental.Ir.v2;
import static dev.vexelray.experimental.Ir.xz;
import static dev.vexelray.experimental.Ir.y;

/**
 * The same Perlin heightfield as {@link PerlinField}, but with a <em>gradient-normalised</em> distance:
 * {@code (y - h) / sqrt(1 + |grad h|^2)} instead of a flat Lipschitz constant. That is the true first-order
 * cone bound on the distance to a heightfield, so the sphere-tracer stops overshooting slopes — which is what
 * produces the straight dark seam artifacts that a constant-factor heightfield SDF leaves on any bumpy surface,
 * value or Perlin alike. The cost: the gradient needs finite differences, so the height is evaluated 5× per
 * sample instead of 1× — the harness measures exactly what that buys.
 */
public final class PerlinAnalyticField implements ShapeField {

    private static final double FREQ = 0.16;
    private static final double AMP = 2.0;
    private static final double E = 0.05;   // world-space gradient step

    @Override
    public String name() {
        return "perlin-analytic";
    }

    /** The signed height h(xz) — same field as {@link PerlinField}, factored out so we can sample its gradient. */
    private static Expr height(Expr xzPoint) {
        return mul(Noise.fbmPerlin2(mulS2(xzPoint, f(FREQ)), 3), f(AMP));
    }

    @Override
    public Expr sdf(Expr point) {
        Expr q = xz(point);
        Expr hp = height(q);
        // central differences of h -> gradient components (dh/dx, dh/dz)
        Expr hx = div(sub(height(add(q, v2(E, 0.0))), height(sub(q, v2(E, 0.0)))), f(2.0 * E));
        Expr hz = div(sub(height(add(q, v2(0.0, E))), height(sub(q, v2(0.0, E)))), f(2.0 * E));
        // |grad(y - h)| = sqrt(1 + hx^2 + hz^2): the cone factor that makes (y - h) a conservative distance
        Expr g = Expr.MathCall.sqrt(add(add(f(1.0), mul(hx, hx)), mul(hz, hz)));
        return div(sub(y(point), hp), g);
    }

    @Override
    public String applicability() {
        return "heightfield with true cone bound; kills overshoot seams; ~5x height evals; render==sim";
    }
}
