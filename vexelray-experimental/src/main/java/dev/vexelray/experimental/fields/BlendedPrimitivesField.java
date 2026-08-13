package dev.vexelray.experimental.fields;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.experimental.Sdf;
import dev.vexelray.experimental.ShapeField;

import static dev.vexelray.experimental.Ir.add;
import static dev.vexelray.experimental.Ir.div;
import static dev.vexelray.experimental.Ir.f;
import static dev.vexelray.experimental.Ir.mul;
import static dev.vexelray.experimental.Ir.mulS3;
import static dev.vexelray.experimental.Ir.v3;
import static dev.vexelray.experimental.Ir.y;

/**
 * V0 of the vexel-world thesis (docs/vexel-world.md): a scene built from a few AABB-bounded SDF primitives —
 * ground plane, a half-buried rock, and a "tree" (trunk capsule + canopy blob) — combined with smooth-union so
 * their interfaces vary by material: the rock <em>melts</em> into the ground (soft blend), the trunk meets the
 * ground <em>crisply</em> (near-hard blend), the canopy fillets onto the trunk. Because every primitive is a real
 * SDF and {@code smin} keeps the union a conservative distance field, this sphere-traces seam-free — the point of
 * V0. Material colour is a proximity-weighted (soft-max) blend of the per-primitive colours, so the geometric
 * blends read as smooth colour transitions too.
 *
 * <p>Caveat under test: the geometry is a left-fold of pairwise {@code smin} with per-interface {@code k}, which
 * is <em>not</em> associative (docs/refactor-decisions D13). For this small fixed scene it is fine; V1 revisits a
 * weighted soft-min if ordering artifacts appear at scale.
 */
public final class BlendedPrimitivesField implements ShapeField {

    // Per-material colours (linear RGB).
    private static final double[] GROUND = {0.42, 0.50, 0.40};
    private static final double[] ROCK = {0.55, 0.55, 0.58};
    private static final double[] BARK = {0.40, 0.27, 0.16};
    private static final double[] LEAF = {0.30, 0.55, 0.30};

    private static final double COLOR_BLEND = 6.0;   // soft-max sharpness for the material colour blend

    @Override
    public String name() {
        return "blended-prims";
    }

    // --- the primitives (each a proper bounded SDF) ---
    private static Expr ground(Expr p) {
        return y(p);
    }

    private static Expr rock(Expr p) {
        return Sdf.sphere(p, 1.2, 0.35, 3.0, 0.7);   // half-buried -> melts into the ground
    }

    private static Expr trunk(Expr p) {
        return Sdf.capsule(p, -1.2, 0.0, 2.5, -1.2, 1.4, 2.5, 0.18);
    }

    private static Expr canopy(Expr p) {
        return Sdf.sphere(p, -1.2, 1.95, 2.5, 0.7);
    }

    @Override
    public Expr sdf(Expr p) {
        Expr d = ground(p);
        d = Sdf.smin(d, rock(p), 0.50);     // soft: rock melts into the ground
        d = Sdf.smin(d, trunk(p), 0.03);    // crisp: trunk rises sharply from the ground
        d = Sdf.smin(d, canopy(p), 0.25);   // medium: canopy fillets onto the trunk
        return d;
    }

    @Override
    public Expr material(Expr p) {
        Expr wg = weight(ground(p));
        Expr wr = weight(rock(p));
        Expr wt = weight(trunk(p));
        Expr wc = weight(canopy(p));
        Expr sum = add(add(add(wg, wr), wt), wc);
        Expr colour = add(add(add(
                mulS3(v3(GROUND[0], GROUND[1], GROUND[2]), wg),
                mulS3(v3(ROCK[0], ROCK[1], ROCK[2]), wr)),
                mulS3(v3(BARK[0], BARK[1], BARK[2]), wt)),
                mulS3(v3(LEAF[0], LEAF[1], LEAF[2]), wc));
        return mulS3(colour, div(f(1.0), sum));   // normalise the partition of unity
    }

    /** Proximity weight for the material soft-max: closer surfaces dominate the blended colour. */
    private static Expr weight(Expr d) {
        return Expr.MathCall.exp(mul(f(-COLOR_BLEND), d));
    }

    @Override
    public String applicability() {
        return "bounded primitives + smooth-union + material blend; seam-free; per-interface k (not yet N-ary)";
    }
}
