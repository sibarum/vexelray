package dev.vexelray.experimental.fields;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.tools.Noise;
import dev.vexelray.experimental.ShapeField;

import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.scale;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.xz;
import static dev.vexelray.ir.Ir.y;

/**
 * A gradient (Perlin) heightfield ({@link Noise#fbmPerlin2} — random gradients per grid node, zero at nodes). The
 * field is organic with no grid quilting, at a modest extra cost over value noise (a cos+sin per corner). Same
 * frequency/relief as {@link ValueNoiseField} for a fair basis comparison; signed noise, used directly as height.
 */
public final class PerlinField implements ShapeField {

    @Override
    public String name() {
        return "perlin";
    }

    @Override
    public Expr sdf(Expr point) {
        Expr h = mul(Noise.fbmPerlin2(scale(xz(point), f(0.16)), 3), f(2.0));
        return mul(sub(y(point), h), f(0.30));
    }

    @Override
    public String applicability() {
        return "heightfield only; organic, no grid; ~1.5x value-noise cost; render==sim; needs Lipschitz scaling";
    }
}
