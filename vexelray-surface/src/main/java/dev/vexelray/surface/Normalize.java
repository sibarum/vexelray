package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;

/**
 * Turns an expression whose <em>zero set</em> is a surface into an expression whose <em>value</em> is a usable
 * distance to it: {@code f / max(|grad f|, eps)}.
 *
 * <p>Why anything is needed at all: {@code x²+y²+z²-1} vanishes on the unit sphere, but at radius 3 it reads 8
 * where the true distance is 2. A sphere-tracer that steps by 8 sails straight through the surface, and the
 * render comes back with holes in it. Dividing by the gradient magnitude fixes the units: here it gives
 * {@code 8/6 = 1.33}, which <em>under</em>-estimates the distance — and that is the correct direction to err.
 * A short step wastes iterations; a long one loses the surface. The estimate also tightens as the surface is
 * approached (it is exact to first order there, and exact everywhere for a linear {@code f}), which is precisely
 * where a march needs it to be accurate.
 *
 * <p><b>What this does not do.</b> It is a local correction, not a proof. The step is safe as long as the
 * gradient does not fall away sharply along the ray ahead; where it does — a field that flattens out just before
 * the surface — the normalised value can still overreach. Interval arithmetic over a box is what actually
 * <em>proves</em> a step is safe, and it is the next pass to build (docs/surface-compiler.md §2.2, §7). Until
 * then this is the honest state of things: good enough for the implicit surfaces people write, not a guarantee.
 *
 * <p>The {@code eps} floor matters more than it looks. At a critical point the gradient vanishes, and without a
 * floor the field divides by zero and the march takes an infinite (or NaN) step. Clamping keeps the step finite
 * and merely slow, which is the failure mode to prefer.
 */
public final class Normalize {

    /**
     * Default floor on the gradient magnitude. Small enough not to distort the field anywhere it matters, large
     * enough that a vanishing gradient produces a big-but-finite step rather than a NaN.
     */
    public static final double DEFAULT_EPSILON = 1e-4;

    private Normalize() {
    }

    /** {@link #lipschitz(Expr, double)} with {@link #DEFAULT_EPSILON}. */
    public static Field lipschitz(Expr f) {
        return lipschitz(f, DEFAULT_EPSILON);
    }

    /**
     * Rescale {@code f} by its own symbolic gradient, yielding a field that is 1-Lipschitz wherever the
     * correction holds — and so is marked {@link Field#EXACT} and marchable.
     *
     * @param f       a scalar expression of {@link Ir#POINT} whose zero set is the surface
     * @param epsilon floor on {@code |grad f|}; must be positive
     * @throws UnsupportedOperationException if {@code f} contains something with no derivative
     */
    public static Field lipschitz(Expr f, double epsilon) {
        if (!(epsilon > 0) || !Double.isFinite(epsilon)) {
            throw new IllegalArgumentException("epsilon must be finite and positive, got " + epsilon);
        }
        Expr magnitude = Ir.max(Ir.length(Gradient.of(f)), Ir.f(epsilon));
        return Field.exact(Ir.div(f, magnitude));
    }

    /**
     * Rescale by a known global constant instead of a pointwise gradient — cheaper (no derivative in the shader
     * at all) and safe everywhere, but as loose as the field's worst point, so the march takes more steps than it
     * needs across the whole scene. Worth it when the constant is tight; a trap when it is not.
     */
    public static Field byConstant(Expr f, double lipschitzBound) {
        if (!(lipschitzBound > 0) || !Double.isFinite(lipschitzBound)) {
            throw new IllegalArgumentException("lipschitz bound must be finite and positive, got " + lipschitzBound);
        }
        if (lipschitzBound == Field.EXACT) {
            return Field.exact(f);
        }
        return Field.exact(Ir.div(f, Ir.f(lipschitzBound)));
    }
}
