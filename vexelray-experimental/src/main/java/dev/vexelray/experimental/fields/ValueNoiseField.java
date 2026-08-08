package dev.vexelray.experimental.fields;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.tools.Noise;
import dev.vexelray.experimental.ShapeField;

import static dev.vexelray.experimental.Ir.f;
import static dev.vexelray.experimental.Ir.mul;
import static dev.vexelray.experimental.Ir.mulS2;
import static dev.vexelray.experimental.Ir.sub;
import static dev.vexelray.experimental.Ir.xz;
import static dev.vexelray.experimental.Ir.y;

/**
 * A value-noise heightfield ({@link Noise#fbm2} — quintic-interpolated, rotated octaves). Cheap and CPU-evaluable,
 * but interpolates random <em>values</em> on a square grid, so it retains a faint quilted/grid character that
 * finite-difference shading exposes. Same frequency/relief as {@link PerlinField} for a fair basis comparison.
 */
public final class ValueNoiseField implements ShapeField {

    @Override
    public String name() {
        return "value-noise";
    }

    @Override
    public Expr sdf(Expr point) {
        // fbm2 in ~[0,1]; centre and scale to relief, then a heightfield SDF with a conservative Lipschitz factor.
        Expr h = mul(sub(Noise.fbm2(mulS2(xz(point), f(0.16)), 3), f(0.5)), f(1.5));
        return mul(sub(y(point), h), f(0.4));
    }

    @Override
    public String applicability() {
        return "heightfield only; cheap; render==sim; grid-quilted look; needs Lipschitz scaling for tracing";
    }
}
