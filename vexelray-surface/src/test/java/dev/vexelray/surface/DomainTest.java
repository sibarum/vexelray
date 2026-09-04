package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The domain operators: does each one put the geometry where it claims, and does the field it leaves behind still
 * satisfy the bound a sphere trace needs?
 *
 * <p>Those two questions want different tests. Placement is checked by evaluating the compiled field at points
 * where the answer is known by construction — the centre of a copy reads {@code -radius}, the midpoint between
 * two reads the gap. Conservatism is checked against a brute-force {@code min} over the copies actually generated
 * (for the repeats, where the danger is an <em>over</em>estimate near a cell wall) or against finite differences
 * of the field itself (for the deformations, where the danger is a gradient longer than one).
 */
class DomainTest {

    private static final double EPS = 1e-9;

    // --- isometries: exact, and no cheaper way to say where things went ---

    @Test
    @DisplayName("rotate turns the object, not the field")
    void rotatePlacesAndPreserves() {
        // A sphere at +X, turned a quarter turn about +Y, lands on -Z.
        Field field = SurfaceCompiler.compile(
                Surface.Rotate.aboutY(Math.PI / 2, new Surface.Sphere(1, 0, 0, 0.5)));

        assertEquals(Field.EXACT, field.lipschitz(), "a rotation is an isometry and must stay exact");
        assertEquals(-0.5, Eval.at(field.distance(), 0, 0, -1), 1e-9);
        assertEquals(0.5, Eval.at(field.distance(), 0, 0, -2), 1e-9);
        assertEquals(0.0, Eval.at(field.distance(), 0, 0, -1.5), 1e-9);
    }

    @Test
    @DisplayName("a full turn about any axis is the identity, to the last bit the constants allow")
    void rotateComposesBackToIdentity() {
        Surface sphere = new Surface.Sphere(0.7, -0.3, 1.1, 0.4);
        Field turned = SurfaceCompiler.compile(new Surface.Rotate(1, 2, -3, 2 * Math.PI, sphere));
        Field plain = SurfaceCompiler.compile(sphere);
        for (double[] p : samples()) {
            assertEquals(Eval.at(plain.distance(), p[0], p[1], p[2]),
                    Eval.at(turned.distance(), p[0], p[1], p[2]), 1e-9);
        }
    }

    @Test
    @DisplayName("a degenerate rotation axis is rejected, like a degenerate plane normal")
    void rotateRejectsDegenerateAxis() {
        assertThrows(IllegalArgumentException.class,
                () -> new Surface.Rotate(0, 0, 0, 1, new Surface.Sphere(0, 0, 0, 1)));
    }

    @Test
    @DisplayName("mirror reflects, and costs nothing but an abs")
    void mirrorReflects() {
        Field field = SurfaceCompiler.compile(
                new Surface.Mirror(true, false, false, new Surface.Sphere(2, 0, 0, 0.5)));

        assertEquals(Field.EXACT, field.lipschitz());
        assertEquals(-0.5, Eval.at(field.distance(), 2, 0, 0), 1e-9);
        assertEquals(-0.5, Eval.at(field.distance(), -2, 0, 0), 1e-9);
        assertEquals(1.5, Eval.at(field.distance(), 0, 0, 0), 1e-9);
    }

    // --- repetition: the neighbour is the test ---

    @Test
    @DisplayName("repeat tiles endlessly along one axis")
    void repeatTiles() {
        Field field = SurfaceCompiler.compile(Surface.Repeat.alongX(
                Surface.Repeat.Axis.every(3.0), new Surface.Sphere(0, 0, 0, 0.5)));

        assertEquals(Field.EXACT, field.lipschitz());
        assertEquals(-0.5, Eval.at(field.distance(), 0, 0, 0), 1e-9);
        assertEquals(-0.5, Eval.at(field.distance(), 6, 0, 0), 1e-9);
        assertEquals(-0.5, Eval.at(field.distance(), -30, 0, 0), 1e-9);
        // Midway between the copy at 3 and the one at 6: 1.5 to either centre, less the radius.
        assertEquals(1.0, Eval.at(field.distance(), 4.5, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("a bounded repeat stops at the last cell instead of running to the horizon")
    void repeatStopsAtItsRange() {
        // Two cells, at x = 0 and x = 3.
        Field field = SurfaceCompiler.compile(Surface.Repeat.alongX(
                Surface.Repeat.Axis.count(3.0, 2), new Surface.Sphere(0, 0, 0, 0.5)));

        assertEquals(-0.5, Eval.at(field.distance(), 0, 0, 0), 1e-9);
        assertEquals(-0.5, Eval.at(field.distance(), 3, 0, 0), 1e-9);
        assertEquals(2.5, Eval.at(field.distance(), 6, 0, 0), 1e-9, "there is no third copy");
        assertEquals(2.5, Eval.at(field.distance(), -3, 0, 0), 1e-9, "and none in the other direction");
    }

    @Test
    @DisplayName("a repeat never reports more distance than the nearest copy — the neighbour fold, checked")
    void repeatNeverOverestimates() {
        // Deliberately fat for its cell: a sphere of radius 0.9 in a cell of 2.0 reaches most of the way to the
        // wall, so the nearest copy is often not the one whose cell the point is in. Folding into that cell alone
        // would read too long here, and a march would step through the surface.
        double period = 2.0;
        Surface child = new Surface.Sphere(0, 0, 0, 0.9);
        Field field = SurfaceCompiler.compile(
                Surface.Repeat.alongX(Surface.Repeat.Axis.every(period), child));
        Field one = SurfaceCompiler.compile(child);

        for (double x = -5; x <= 5; x += 0.037) {
            double got = Eval.at(field.distance(), x, 0.21, -0.13);
            double best = Double.POSITIVE_INFINITY;
            for (int k = -6; k <= 6; k++) {
                best = Math.min(best, Eval.at(one.distance(), x - k * period, 0.21, -0.13));
            }
            assertTrue(got <= best + EPS,
                    "overestimated at x=" + x + ": " + got + " > " + best);
            assertEquals(best, got, 1e-9, "and it should be the true min, not merely under it");
        }
    }

    @Test
    @DisplayName("polar repeat rings the Y axis")
    void polarRepeatRings() {
        // Four copies of a sphere sitting on +Z, so they land on the four cardinal directions.
        Field field = SurfaceCompiler.compile(
                new Surface.PolarRepeat(4, new Surface.Sphere(0, 0, 2, 0.5)));

        assertEquals(Field.EXACT, field.lipschitz());
        assertEquals(-0.5, Eval.at(field.distance(), 0, 0, 2), 1e-6);
        assertEquals(-0.5, Eval.at(field.distance(), 2, 0, 0), 1e-6);
        assertEquals(-0.5, Eval.at(field.distance(), 0, 0, -2), 1e-6);
        assertEquals(-0.5, Eval.at(field.distance(), -2, 0, 0), 1e-6);
    }

    @Test
    @DisplayName("polar repeat never reports more distance than the nearest sector's copy")
    void polarRepeatNeverOverestimates() {
        int count = 6;
        Surface child = new Surface.Sphere(0, 0, 2, 0.55);
        Field field = SurfaceCompiler.compile(new Surface.PolarRepeat(count, child));
        Field one = SurfaceCompiler.compile(child);

        double sector = 2 * Math.PI / count;
        for (double angle = -Math.PI; angle <= Math.PI; angle += 0.017) {
            double r = 2.0;
            double x = r * Math.sin(angle);
            double z = r * Math.cos(angle);
            double got = Eval.at(field.distance(), x, 0.3, z);
            double best = Double.POSITIVE_INFINITY;
            for (int k = -count; k <= count; k++) {
                double t = k * sector;
                best = Math.min(best, Eval.at(one.distance(),
                        Math.cos(t) * x - Math.sin(t) * z, 0.3, Math.sin(t) * x + Math.cos(t) * z));
            }
            assertTrue(got <= best + 1e-6, "overestimated at angle=" + angle + ": " + got + " > " + best);
        }
    }

    // --- deformations: the bound is the whole question ---

    @Test
    @DisplayName("a twist stays marchable, because the compiler divides the stretch back out")
    void twistStaysMarchable() {
        Surface twisted = new Surface.Twist(0.6, 2.5,
                new Surface.Box(0, 0, 0, 1.0, 3.0, 0.4));
        Field field = SurfaceCompiler.compile(twisted);

        assertTrue(field.isMarchable());
        assertGradientBounded(field, 2.5, true);
    }

    @Test
    @DisplayName("a bend stays marchable for the same reason")
    void bendStaysMarchable() {
        Field field = SurfaceCompiler.compile(new Surface.Bend(0.4, 3.0,
                new Surface.Box(0, 0, 0, 3.0, 0.3, 0.3)));

        assertTrue(field.isMarchable());
        assertGradientBounded(field, 3.0, false);
    }

    @Test
    @DisplayName("a gentle twist is bounded linearly, not in quadrature")
    void gentleDeformationsAreStillBounded() {
        // The bound a twist needs grows like 1 + a/2, not sqrt(1 + a^2). The two agree to second order, so a
        // fierce twist hides the difference and a gentle one exposes it: at a = 0.6 the wrong reading is 1.166
        // against a true 1.344, and every march step inside this shape would come out 15% too long.
        Field twist = SurfaceCompiler.compile(
                new Surface.Twist(0.15, 4.0, new Surface.Sphere(3, 0, 0, 0.5)));
        assertGradientBounded(twist, 4.0, true);

        Field bend = SurfaceCompiler.compile(
                new Surface.Bend(0.12, 4.0, new Surface.Sphere(0, -3, 0, 0.5)));
        assertGradientBounded(bend, 4.0, false);
    }

    @Test
    @DisplayName("a twist of nothing is still the thing itself")
    void zeroRateIsIdentity() {
        Surface box = new Surface.Box(0, 0, 0, 1, 1, 1);
        Field twisted = SurfaceCompiler.compile(new Surface.Twist(0, 5, box));
        Field plain = SurfaceCompiler.compile(box);
        for (double[] p : samples()) {
            assertEquals(Eval.at(plain.distance(), p[0], p[1], p[2]),
                    Eval.at(twisted.distance(), p[0], p[1], p[2]), 1e-9);
        }
    }

    @Test
    @DisplayName("a scene of domain operators needs no gradient normalisation")
    void domainOperatorsAreFree() {
        Surface scene = new Surface.Repeat(
                Surface.Repeat.Axis.every(4.0), Surface.Repeat.Axis.NONE, Surface.Repeat.Axis.range(4.0, -2, 2),
                new Surface.Mirror(true, false, true,
                        Surface.Rotate.aboutY(0.3,
                                new Surface.PolarRepeat(5,
                                    new Surface.Twist(0.2, 1.5,
                                            new Surface.Box(0.8, 0, 0, 0.2, 1.0, 0.2))))));
        Field field = SurfaceCompiler.compile(scene);

        assertTrue(field.isMarchable());
        assertEquals(0, countNormalisations(field), "domain operators are not implicits and must not be normalised");
    }

    // --- helpers ---

    /**
     * Finite-difference the field across a grid and insist no gradient is longer than one — but only where the
     * operator promised anything, which is within {@code extent} of its axis of rotation. Outside that radius a
     * twist or a bend can and does overshoot; that is the declared bound doing its job, not a bug, so sampling
     * there would be testing the opposite of what the node claims.
     *
     * @param radiusInXZ {@code true} for a twist, whose radius is measured about {@code Y}; {@code false} for a
     *                   bend, whose radius is measured about {@code Z}
     */
    private static void assertGradientBounded(Field field, double extent, boolean radiusInXZ) {
        double h = 1e-4;
        int checked = 0;
        for (double x = -4; x <= 4; x += 0.5) {
            for (double y = -4; y <= 4; y += 0.5) {
                for (double z = -4; z <= 4; z += 0.5) {
                    if (Math.hypot(x, radiusInXZ ? z : y) > extent) {
                        continue;
                    }
                    double[] g = Eval.numericGradient(field.distance(), x, y, z, h);
                    double len = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
                    assertTrue(len <= 1.0 + 1e-3,
                            "gradient of length " + len + " at (" + x + ", " + y + ", " + z + ")");
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "the sample grid missed the declared region entirely");
    }

    /** Same count as in {@code SurfaceCompilerTest}: the normalisation epsilon appears nowhere else. */
    private static int countNormalisations(Field field) {
        return normalisations(field.distance());
    }

    private static int normalisations(Expr e) {
        int here = e instanceof Expr.ConstFloat c && c.value() == Normalize.DEFAULT_EPSILON ? 1 : 0;
        return here + children(e).stream().mapToInt(DomainTest::normalisations).sum();
    }

    private static List<Expr> children(Expr e) {
        return switch (e) {
            case Expr.Binary b -> List.of(b.lhs(), b.rhs());
            case Expr.Unary u -> List.of(u.operand());
            case Expr.MathCall m -> m.args();
            case Expr.VectorConstruct v -> v.components();
            case Expr.VectorExtract v -> List.of(v.vector());
            default -> List.of();
        };
    }

    private static double[][] samples() {
        return new double[][]{
                {0.37, -0.81, 1.24}, {2.10, 0.55, -1.70}, {-1.31, 2.02, 0.19}, {0.05, 0.02, -0.03}};
    }
}
