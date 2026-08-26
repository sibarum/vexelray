package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.ir.Ir.POINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceCompilerTest {

    private static final double[][] SAMPLES = {
            {0.37, -0.81, 1.24}, {2.10, 0.55, -1.70}, {-1.31, 2.02, 0.19}, {0.05, 0.02, -0.03}};

    @Test
    @DisplayName("a primitive lowers to the expression a person would have written by hand")
    void primitivesLowerToTheObviousForm() {
        // The invariant the whole design rests on: making surfaces data costs nothing at the leaves. If this
        // ever fails, generality has started charging rent on scenes that do not use it.
        assertEquals(
                Ir.sub(Ir.length(Ir.sub(POINT, Ir.v3(1, 2, 3))), Ir.f(0.5)),
                SurfaceCompiler.compile(new Surface.Sphere(1, 2, 3, 0.5)).distance());
    }

    @Test
    @DisplayName("union is a plain min, with no wrapper structure around it")
    void unionIsMin() {
        Surface a = new Surface.Sphere(0, 0, 0, 1);
        Surface b = new Surface.Sphere(2, 0, 0, 1);
        assertEquals(
                Ir.min(SurfaceCompiler.compile(a).distance(), SurfaceCompiler.compile(b).distance()),
                SurfaceCompiler.compile(Surface.union(a, b)).distance());
    }

    @Test
    @DisplayName("every surface built from primitives is marchable without normalisation")
    void primitiveScenesNeedNoNormalisation() {
        Surface scene = Surface.smoothUnion(6.0,
                new Surface.Translate(0, -1, 0, Surface.union(
                        new Surface.Box(0, 0, 0, 2, 0.2, 2),
                        new Surface.Torus(0, 0, 0, 1.0, 0.2))),
                new Surface.Scale(1.5, new Surface.Shell(0.05, new Surface.Sphere(0, 1, 0, 0.6))),
                new Surface.Difference(
                        new Surface.Round(0.1, new Surface.Box(2, 0, 0, 0.5, 0.5, 0.5)),
                        new Surface.Capsule(2, -1, 0, 2, 1, 0, 0.2)));
        Field field = SurfaceCompiler.compile(scene);
        assertEquals(Field.EXACT, field.lipschitz());
        assertTrue(field.isMarchable());
        assertEquals(0, normalisations(field.distance()),
                "a scene of proper distance fields must not pay for gradient normalisation");
    }

    @Test
    @DisplayName("an implicit is normalised on the way in, so the scene around it stays marchable")
    void implicitsAreNormalised() {
        Surface scene = Surface.union(
                new Surface.Sphere(3, 0, 0, 1),
                new Surface.Implicit(Ir.sub(Ir.dot(POINT, POINT), Ir.f(1.0))));
        Field field = SurfaceCompiler.compile(scene);
        assertEquals(Field.EXACT, field.lipschitz());
        assertEquals(1, normalisations(field.distance()), "the implicit should have been normalised exactly once");
    }

    @Test
    @DisplayName("translate moves the surface without changing distances")
    void translateMovesTheSurface() {
        Field moved = SurfaceCompiler.compile(
                new Surface.Translate(1, 0, 0, new Surface.Sphere(0, 0, 0, 1)));
        assertEquals(0.0, Eval.at(moved.distance(), 2, 0, 0), 1e-12);     // on the surface
        assertEquals(1.0, Eval.at(moved.distance(), 3, 0, 0), 1e-12);     // one unit outside
        assertEquals(-1.0, Eval.at(moved.distance(), 1, 0, 0), 1e-12);    // at the centre
    }

    @Test
    @DisplayName("scale scales the distance along with the shape")
    void scaleScalesDistances() {
        Field scaled = SurfaceCompiler.compile(new Surface.Scale(2, new Surface.Sphere(0, 0, 0, 1)));
        assertEquals(0.0, Eval.at(scaled.distance(), 2, 0, 0), 1e-12);
        assertEquals(1.0, Eval.at(scaled.distance(), 3, 0, 0), 1e-12);
    }

    @Test
    @DisplayName("a transform applies through an implicit, not around it")
    void transformsReachIntoImplicits() {
        // The implicit is authored about the origin; translating it must move where its zero set lies.
        Surface unitSphereAsImplicit = new Surface.Implicit(Ir.sub(Ir.dot(POINT, POINT), Ir.f(1.0)));
        Field moved = SurfaceCompiler.compile(new Surface.Translate(5, 0, 0, unitSphereAsImplicit));
        assertEquals(0.0, Eval.at(moved.distance(), 6, 0, 0), 1e-9);
        assertTrue(Eval.at(moved.distance(), 5, 0, 0) < 0, "the centre should be inside");
    }

    @Test
    @DisplayName("difference carves the second surface out of the first")
    void differenceCarvesOut() {
        Field field = SurfaceCompiler.compile(new Surface.Difference(
                new Surface.Sphere(0, 0, 0, 1),
                new Surface.Sphere(1, 0, 0, 0.5)));
        assertTrue(Eval.at(field.distance(), -0.5, 0, 0) < 0, "far side stays solid");
        assertTrue(Eval.at(field.distance(), 0.8, 0, 0) > 0, "the bite is empty");
    }

    @Test
    @DisplayName("smooth union never reports more distance than a hard union would")
    void smoothUnionIsConservative() {
        Surface a = new Surface.Sphere(-0.6, 0, 0, 0.8);
        Surface b = new Surface.Sphere(0.6, 0, 0, 0.8);
        Expr smooth = SurfaceCompiler.compile(Surface.smoothUnion(4.0, a, b)).distance();
        Expr hard = SurfaceCompiler.compile(Surface.union(a, b)).distance();
        for (double[] p : SAMPLES) {
            // Overshooting is what puts holes in a render; a soft union may only ever be shorter than min.
            assertTrue(Eval.at(smooth, p[0], p[1], p[2]) <= Eval.at(hard, p[0], p[1], p[2]) + 1e-12,
                    "smooth union overshot min at " + List.of(p[0], p[1], p[2]));
        }
    }

    @Test
    @DisplayName("smooth union does not depend on the order its children were written in")
    void smoothUnionIsOrderIndependent() {
        // D13: a left-fold of pairwise smin is not associative, so grouping would leak into the surface wherever
        // three children meet. The N-ary soft-min is symmetric, and this is the test that says so.
        Surface a = new Surface.Sphere(-0.6, 0, 0, 0.8);
        Surface b = new Surface.Sphere(0.6, 0, 0, 0.8);
        Surface c = new Surface.Sphere(0, 0.7, 0, 0.5);
        Expr abc = SurfaceCompiler.compile(Surface.smoothUnion(4.0, a, b, c)).distance();
        Expr cba = SurfaceCompiler.compile(Surface.smoothUnion(4.0, c, b, a)).distance();
        for (double[] p : SAMPLES) {
            assertEquals(Eval.at(abc, p[0], p[1], p[2]), Eval.at(cba, p[0], p[1], p[2]), 1e-12,
                    "order changed the surface at " + List.of(p[0], p[1], p[2]));
        }
    }

    @Test
    @DisplayName("smooth union keeps its exponents negative, so a camera inside the world sees a finite field")
    void smoothUnionDoesNotOverflowInside() {
        // Written naively as -log(sum exp(-k*d))/k, a point 12 units inside at k=8 exponentiates to ~1e41 —
        // past the range of a 32-bit float, so the field returns infinity exactly where the player is standing.
        // The shifted form keeps every exponent at or below zero. Checked structurally: doubles here would hide
        // the overflow that float32 on the GPU would not.
        Expr field = SurfaceCompiler.compile(Surface.smoothUnion(8.0,
                new Surface.Sphere(-4, 0, 0, 12),
                new Surface.Sphere(4, 0, 0, 12))).distance();
        List<Expr> exponents = new ArrayList<>();
        collectExpArguments(field, exponents);
        assertTrue(exponents.size() >= 2, "expected the soft-min's exponentials");
        for (Expr exponent : exponents) {
            assertTrue(Eval.at(exponent, 0, 0, 0) <= 0.0,
                    "exponent " + Eval.at(exponent, 0, 0, 0) + " deep inside would overflow a float");
        }
    }

    @Test
    @DisplayName("an oversized surface is refused before any lowering happens")
    void limitsRejectHostileInput() {
        Surface deep = deepOf();
        assertThrows(SurfaceLimits.SurfaceTooLargeException.class,
                () -> SurfaceCompiler.compile(deep, new SurfaceLimits(1000, 8)));
        assertThrows(SurfaceLimits.SurfaceTooLargeException.class,
                () -> SurfaceCompiler.compile(deep, new SurfaceLimits(4, 256)));
        // ...and the same surface compiles fine under the real limits.
        assertTrue(SurfaceCompiler.compile(deep).isMarchable());
    }

    @Test
    @DisplayName("smooth intersection reports MORE than a hard one, and is still safe to march")
    void smoothIntersectionExceedsMaxButStaysLipschitz() {
        // Soft-max is >= max, so a smooth intersection reports more distance than the hard operator it softens.
        // That looks like the overshoot that puts holes in a render, and is not: conservatism never came from
        // being under max, it comes from the field being 1-Lipschitz, which bounds it by the true distance to
        // its own (rounder) zero set. Both halves of that are checked here.
        Surface a = new Surface.Sphere(-0.3, 0, 0, 1);
        Surface b = new Surface.Box(0.3, 0, 0, 0.8, 0.8, 0.8);
        Expr smooth = SurfaceCompiler.compile(Surface.smoothIntersection(4.0, a, b)).distance();
        Expr hard = SurfaceCompiler.compile(Surface.intersection(a, b)).distance();
        for (double[] p : SAMPLES) {
            assertTrue(Eval.at(smooth, p[0], p[1], p[2]) >= Eval.at(hard, p[0], p[1], p[2]) - 1e-12,
                    "soft-max should sit at or above max at " + List.of(p[0], p[1], p[2]));
        }
        assertGradientNeverExceedsOne(smooth, "smooth intersection");
    }

    @Test
    @DisplayName("every combinator keeps the gradient at or under one — the promise Field.EXACT makes")
    void combinatorsStayOneLipschitz() {
        // This is the property the compiler asserts by marking a field EXACT, checked directly rather than
        // trusted: a field whose gradient never exceeds 1 never reports more distance than there is, so a
        // sphere-tracer stepping by it cannot pass through the surface.
        //
        // Implicit is deliberately absent. Its normalisation is a local correction, not a proof, so it is the
        // one node that can exceed this — see docs/surface-compiler.md §7. Including it here would either fail
        // or quietly weaken the tolerance until it passed.
        Surface a = new Surface.Sphere(-0.3, 0.1, 0, 1);
        Surface b = new Surface.Box(0.3, 0, 0.1, 0.8, 0.9, 0.7);
        Surface c = new Surface.Capsule(-1, -0.5, 0, 1, 0.6, 0.4, 0.35);
        assertGradientNeverExceedsOne(SurfaceCompiler.compile(Surface.union(a, b, c)).distance(), "union");
        assertGradientNeverExceedsOne(SurfaceCompiler.compile(Surface.intersection(a, b)).distance(),
                "intersection");
        assertGradientNeverExceedsOne(SurfaceCompiler.compile(new Surface.Difference(a, b)).distance(),
                "difference");
        assertGradientNeverExceedsOne(SurfaceCompiler.compile(Surface.smoothUnion(4.0, a, b, c)).distance(),
                "smooth union");
        assertGradientNeverExceedsOne(
                SurfaceCompiler.compile(Surface.smoothIntersection(4.0, a, b)).distance(), "smooth intersection");
        assertGradientNeverExceedsOne(
                SurfaceCompiler.compile(new Surface.SmoothDifference(4.0, a, b)).distance(), "smooth difference");
        assertGradientNeverExceedsOne(SurfaceCompiler.compile(
                new Surface.Scale(1.7, new Surface.Shell(0.05, new Surface.Round(0.1, b)))).distance(),
                "scale/shell/round");
    }

    @Test
    @DisplayName("as sharpness grows, the smooth operators converge on the hard ones they soften")
    void smoothOperatorsApproachTheirHardForm() {
        Surface a = new Surface.Sphere(-0.3, 0, 0, 1);
        Surface b = new Surface.Box(0.3, 0, 0, 0.8, 0.8, 0.8);
        Expr hardIntersection = SurfaceCompiler.compile(Surface.intersection(a, b)).distance();
        Expr hardDifference = SurfaceCompiler.compile(new Surface.Difference(a, b)).distance();
        Expr sharpIntersection = SurfaceCompiler.compile(Surface.smoothIntersection(400.0, a, b)).distance();
        Expr sharpDifference = SurfaceCompiler.compile(new Surface.SmoothDifference(400.0, a, b)).distance();
        for (double[] p : SAMPLES) {
            assertEquals(Eval.at(hardIntersection, p[0], p[1], p[2]),
                    Eval.at(sharpIntersection, p[0], p[1], p[2]), 0.01);
            assertEquals(Eval.at(hardDifference, p[0], p[1], p[2]),
                    Eval.at(sharpDifference, p[0], p[1], p[2]), 0.01);
        }
    }

    @Test
    @DisplayName("smooth intersection is order-free too, and keeps its exponents negative")
    void smoothIntersectionIsOrderFreeAndStable() {
        Surface a = new Surface.Sphere(-0.3, 0, 0, 1);
        Surface b = new Surface.Box(0.3, 0, 0, 0.8, 0.8, 0.8);
        Surface c = new Surface.Sphere(0, 0.4, 0, 1.1);
        Expr abc = SurfaceCompiler.compile(Surface.smoothIntersection(4.0, a, b, c)).distance();
        Expr cba = SurfaceCompiler.compile(Surface.smoothIntersection(4.0, c, b, a)).distance();
        for (double[] p : SAMPLES) {
            assertEquals(Eval.at(abc, p[0], p[1], p[2]), Eval.at(cba, p[0], p[1], p[2]), 1e-12);
        }
        // Shifted by the max rather than the min this time, but the same guard: every exponent at or below zero,
        // so a point far outside the geometry cannot overflow a 32-bit float.
        List<Expr> exponents = new ArrayList<>();
        collectExpArguments(SurfaceCompiler.compile(Surface.smoothIntersection(8.0,
                new Surface.Sphere(-4, 0, 0, 1), new Surface.Sphere(4, 0, 0, 1))).distance(), exponents);
        assertTrue(exponents.size() >= 2);
        for (Expr exponent : exponents) {
            assertTrue(Eval.at(exponent, 0, 20, 0) <= 0.0, "exponent far outside would overflow a float");
        }
    }

    @Test
    @DisplayName("a small implicit that expands enormously is caught on the way out, not just on the way in")
    void compiledSizeIsBoundedToo() {
        // Gradient duplication compounds through nesting — each nested normalize multiplies the derivative by
        // about four. This expression is 35 nodes, comfortably inside any input limit worth setting, and
        // differentiates to over 50,000. Bounding the input alone would let it through.
        Expr nested = Ir.add(POINT, Ir.v3(0.5, 0.5, 0.5));
        for (int i = 0; i < 4; i++) {
            nested = Expr.MathCall.normalize(Ir.add(nested, Ir.v3(0.1, 0.2, 0.3)));
        }
        Surface surface = new Surface.Implicit(Ir.dot(nested, Ir.v3(0, 1, 0)));

        SurfaceCompiler.compile(surface);   // fine under the default budget

        SurfaceLimits generousInputTightOutput = new SurfaceLimits(100_000, 256, 10_000);
        assertThrows(SurfaceLimits.SurfaceTooLargeException.class,
                () -> SurfaceCompiler.compile(surface, generousInputTightOutput));
    }

    @Test
    @DisplayName("identical surfaces are equal, so the shader cache can collapse them")
    void structuralEqualityHolds() {
        // ShaderKey fingerprints a description by its own equals(); this is what makes that work for free.
        Surface one = Surface.smoothUnion(4.0,
                new Surface.Sphere(0, 0, 0, 1), new Surface.Box(1, 1, 1, 0.5, 0.5, 0.5));
        Surface other = Surface.smoothUnion(4.0,
                new Surface.Sphere(0, 0, 0, 1), new Surface.Box(1, 1, 1, 0.5, 0.5, 0.5));
        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
        assertEquals(SurfaceCompiler.compile(one).distance(), SurfaceCompiler.compile(other).distance());
    }

    @Test
    @DisplayName("degenerate surfaces are rejected at construction, not at render time")
    void degenerateSurfacesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Surface.Sphere(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Surface.Plane(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Surface.SmoothUnion(0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Surface.Implicit(POINT));   // vec3, not a scalar
    }

    /**
     * Assert {@code |grad d| <= 1} over a grid — the definition of a field that cannot overshoot. Uses the
     * symbolic derivative rather than finite differences so the check is exact away from kinks.
     */
    private static void assertGradientNeverExceedsOne(Expr distance, String what) {
        Expr gradient = Gradient.of(distance);
        for (double x = -2.1; x <= 2.1; x += 0.37) {
            for (double y = -2.1; y <= 2.1; y += 0.41) {
                for (double z = -2.1; z <= 2.1; z += 0.43) {
                    double[] g = Eval.vecAt(gradient, x, y, z);
                    double magnitude = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
                    assertTrue(magnitude <= 1.0 + 1e-6,
                            what + ": |grad| = " + magnitude + " at " + List.of(x, y, z)
                                    + " — this field would overshoot and put holes in the render");
                }
            }
        }
    }

    /** A tree deeper than the tight limit used above. */
    private static Surface deepOf() {
        Surface s = new Surface.Sphere(0, 0, 0, 1);
        for (int i = 0; i < 40; i++) {
            s = new Surface.Translate(0.1, 0, 0, s);
        }
        return s;
    }

    /**
     * How many gradient normalisations are present. Every one emits {@code max(length(grad), epsilon)}, and the
     * epsilon constant appears nowhere else, so counting it counts them.
     */
    private static int normalisations(Expr e) {
        int here = e instanceof Expr.ConstFloat c && c.value() == Normalize.DEFAULT_EPSILON ? 1 : 0;
        return here + children(e).stream().mapToInt(SurfaceCompilerTest::normalisations).sum();
    }

    private static void collectExpArguments(Expr e, List<Expr> out) {
        if (e instanceof Expr.MathCall m && m.fn() == dev.supirvast.vastir.core.MathFn.EXP) {
            out.add(m.args().get(0));
        }
        children(e).forEach(child -> collectExpArguments(child, out));
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
}
