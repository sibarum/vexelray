package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static dev.vexelray.ir.Ir.POINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The symbolic derivative against central differences of the same expression.
 *
 * <p>Points are chosen away from the kinks of {@code min}/{@code max}/{@code abs}, where a derivative does not
 * exist and finite differences straddle two branches — the pass picks one side there, which is the correct
 * behaviour for a march but is not something a numeric check can adjudicate.
 */
class GradientTest {

    private static final double H = 1e-6;
    private static final double TOLERANCE = 1e-6;

    /** Points to check every expression at: off-axis, off-origin, mixed signs, and one far out. */
    private static final double[][] SAMPLES = {
            {0.37, -0.81, 1.24}, {2.10, 0.55, -1.70}, {-1.31, 2.02, 0.19}, {5.00, -3.30, 4.10}};

    static Stream<Object[]> fields() {
        return Stream.of(
                new Object[]{"sphere sdf", SurfaceCompiler.compile(
                        new Surface.Sphere(1, 2, 3, 0.5)).distance()},
                new Object[]{"plane sdf", SurfaceCompiler.compile(
                        new Surface.Plane(1, 2, 3, -0.25)).distance()},
                new Object[]{"capsule sdf", SurfaceCompiler.compile(
                        new Surface.Capsule(-1, 0, 0, 1, 0.5, 0.25, 0.3)).distance()},
                new Object[]{"torus sdf", SurfaceCompiler.compile(
                        new Surface.Torus(0.1, -0.2, 0.3, 1.5, 0.4)).distance()},
                new Object[]{"quadratic implicit", quadric()},
                new Object[]{"trig sum", Ir.add(Ir.add(sin(Ir.x(POINT)), sin(Ir.y(POINT))), sin(Ir.z(POINT)))},
                new Object[]{"exp/log mix", Ir.add(
                        Ir.mul(Expr.MathCall.exp(Ir.neg(Ir.x(POINT))), Ir.y(POINT)),
                        Expr.MathCall.log(Ir.add(Ir.f(8.0), Ir.z(POINT))))},
                new Object[]{"power of a distance", Expr.MathCall.pow(Ir.length(POINT), Ir.f(1.5))},
                new Object[]{"normalize then project", Ir.dot(
                        Expr.MathCall.normalize(Ir.add(POINT, Ir.v3(0.5, 0.5, 0.5))), Ir.v3(0, 1, 0))},
                new Object[]{"smooth union of spheres", SurfaceCompiler.compile(
                        Surface.smoothUnion(4.0,
                                new Surface.Sphere(-0.6, 0, 0, 0.8),
                                new Surface.Sphere(0.6, 0, 0, 0.8))).distance()},
                new Object[]{"scaled, translated box", SurfaceCompiler.compile(
                        new Surface.Translate(0.2, 0.3, 0.4,
                                new Surface.Scale(1.7, new Surface.Box(0, 0, 0, 0.5, 0.9, 0.3)))).distance()});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    @DisplayName("symbolic gradient matches central differences")
    void gradientMatchesFiniteDifferences(String name, Expr f) {
        Expr gradient = Gradient.of(f);
        for (double[] p : SAMPLES) {
            double[] symbolic = Eval.vecAt(gradient, p[0], p[1], p[2]);
            double[] numeric = Eval.numericGradient(f, p[0], p[1], p[2], H);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(numeric[axis], symbolic[axis], TOLERANCE + Math.abs(numeric[axis]) * 1e-4,
                        () -> name + " at " + List.of(p[0], p[1], p[2]));
            }
        }
    }

    @Test
    @DisplayName("a true distance field has unit gradient, which is what makes it marchable")
    void distanceFieldsHaveUnitGradient() {
        Expr sphere = SurfaceCompiler.compile(new Surface.Sphere(0, 0, 0, 1)).distance();
        Expr gradient = Gradient.of(sphere);
        for (double[] p : SAMPLES) {
            double[] g = Eval.vecAt(gradient, p[0], p[1], p[2]);
            assertEquals(1.0, Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]), 1e-9);
        }
    }

    @Test
    @DisplayName("an implicit's gradient is not unit — which is exactly why it needs normalising")
    void implicitsDoNotHaveUnitGradient() {
        Expr gradient = Gradient.of(quadric());
        double[] g = Eval.vecAt(gradient, 3, 0, 0);       // grad(r^2 - 1) = 2p, so |grad| = 6 at r = 3
        assertEquals(6.0, Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]), 1e-9);
    }

    @Test
    @DisplayName("no identity arithmetic survives into the derivative")
    void derivativeCarriesNoIdentityTerms() {
        // Every constant in a source expression contributes a zero to its derivative. Unfolded, those zeros
        // reach the GPU as real instructions; D12 is the record of what duplicated structure costs there.
        for (Object[] field : fields().toList()) {
            Expr gradient = Gradient.of((Expr) field[1]);
            assertTrue(identityOps(gradient) == 0,
                    field[0] + ": derivative kept " + identityOps(gradient) + " identity operations");
        }
    }

    @Test
    @DisplayName("derivative size stays proportional to the field it came from")
    void derivativeDoesNotExplode() {
        // Measured today: 6.4x (sphere) through 15.6x (capsule) to 31x (a normalize), from three seeds re-emitting
        // the primal plus rules that embed primal operands again. Worse, it COMPOUNDS through nesting (~4x per
        // nested normalize), which is why SurfaceLimits bounds compiled output and not only input. Pinned here
        // so a per-rule regression surfaces as a test failure rather than a multi-megabyte shader (D12).
        for (Object[] field : fields().toList()) {
            Expr f = (Expr) field[1];
            double ratio = nodes(Gradient.of(f)) / (double) Math.max(1, nodes(f));
            assertTrue(ratio <= 35.0,
                    () -> field[0] + ": derivative is " + Math.round(ratio) + "x the field's node count");
        }
    }

    @Test
    @DisplayName("a vector that vanishes over a whole region differentiates to zero, not NaN")
    void degenerateLengthsDoNotProduceNaN() {
        // length(max(q, 0)) is the outside term of the standard box SDF, and max(q, 0) is identically zero
        // everywhere INSIDE the box — so an unguarded d|v| = dot(v,tv)/|v| returns 0/0 across the entire
        // interior. Not an isolated singularity that a march would step over: a region, in which a ray is lost
        // rather than mis-stepped. Where the operand is constant-zero in a neighbourhood, zero is also the
        // mathematically correct derivative.
        Expr boxOutsideTerm = Ir.length(Ir.max(
                Ir.sub(Ir.abs(POINT), Ir.v3(1, 1, 1)), Ir.v3(0, 0, 0)));
        Expr gradient = Gradient.of(boxOutsideTerm);
        for (double[] p : new double[][]{{0, 0, 0}, {0.5, -0.25, 0.1}, {-0.9, 0.9, -0.9}}) {
            double[] g = Eval.vecAt(gradient, p[0], p[1], p[2]);
            for (double component : g) {
                assertTrue(Double.isFinite(component),
                        "gradient was " + component + " inside the box at " + List.of(p[0], p[1], p[2]));
            }
        }
        // Outside, where the vector is genuinely non-zero, the guard must not have changed the answer.
        double[] outside = Eval.vecAt(gradient, 2.0, 0.0, 0.0);
        assertEquals(1.0, outside[0], 1e-9);
    }

    @Test
    @DisplayName("what cannot be differentiated is refused, not guessed at")
    void refusesUndifferentiableExpressions() {
        // A local-variable read has no derivative available to a pure-expression pass: the pass cannot see what
        // was assigned to it. Returning zero here would silently produce a field that marches through surfaces.
        Expr readsALocal = Ir.add(Ir.x(POINT),
                new Expr.Read(new dev.supirvast.vastir.core.LocalVar("t", Ir.F32)));
        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> Gradient.of(readsALocal));
        assertTrue(thrown.getMessage().contains("local-variable read"), thrown.getMessage());
    }

    /** Count of {@code x+0}, {@code 0+x}, {@code x-0}, {@code 0*x}, {@code 1*x}, {@code x/1} left in a tree. */
    private static int identityOps(Expr e) {
        int here = e instanceof Expr.Binary b && switch (b.op()) {
            case ADD, SUB -> Fold.isZero(b.lhs()) || Fold.isZero(b.rhs());
            case MUL -> Fold.isZero(b.lhs()) || Fold.isZero(b.rhs()) || Fold.isOne(b.lhs()) || Fold.isOne(b.rhs());
            case DIV -> Fold.isZero(b.lhs()) || Fold.isOne(b.rhs());
            default -> false;
        } ? 1 : 0;
        return here + children(e).stream().mapToInt(GradientTest::identityOps).sum();
    }

    private static int nodes(Expr e) {
        return 1 + children(e).stream().mapToInt(GradientTest::nodes).sum();
    }

    private static List<Expr> children(Expr e) {
        return switch (e) {
            case Expr.Binary b -> List.of(b.lhs(), b.rhs());
            case Expr.Unary u -> List.of(u.operand());
            case Expr.MathCall m -> m.args();
            case Expr.VectorConstruct v -> v.components();
            case Expr.VectorExtract v -> List.of(v.vector());
            case Expr.Convert c -> List.of(c.operand());
            default -> List.of();
        };
    }

    /** {@code x² + y² + z² - 1}: the unit sphere as an implicit, and a field that overshoots badly. */
    private static Expr quadric() {
        return Ir.sub(Ir.dot(POINT, POINT), Ir.f(1.0));
    }

    private static Expr sin(Expr e) {
        return Expr.MathCall.sin(e);
    }
}
