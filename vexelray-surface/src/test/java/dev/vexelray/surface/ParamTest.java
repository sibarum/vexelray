package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.supirvast.vastir.core.PushConstants;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0a: a number in a surface can be driven from outside the shader.
 *
 * <p>The claims under test are the ones the stage is worth doing for. A value sweep is writes and not compiles
 * ({@link #sweepingAValueDoesNotChangeTheShaderKey}); a range is checked once, across the whole range, rather
 * than at whatever value the slider is at ({@link #aRangeThatCrossesZeroIsRefusedAtConstruction}); a scene of
 * literals lowers to exactly the IR it lowered to before parameters existed
 * ({@link #aLiteralAngleStillFoldsToConstants}); and an implicit cannot read a block the composer did not issue
 * ({@link #aForeignPushConstantBlockIsRefused}).
 */
class ParamTest {

    private static final ParamId RADIUS = ParamId.fresh();
    private static final ParamId HEIGHT = ParamId.fresh();

    /** A store standing in for the composer's, over a block of {@code n} bare members. */
    private static ParamStore store(ParamBlock block) {
        List<PushConstants.Member> members = new ArrayList<>();
        for (int i = 0; i < block.size(); i++) {
            members.add(new PushConstants.Member("p" + i, Ir.F32));
        }
        return block.inPushConstants(new PushConstants(members), 0);
    }

    private static Surface spheres(double radius) {
        return new Surface.Union(List.of(
                new Surface.Sphere(Scalar.of(0), Scalar.of(1), Scalar.of(0),
                        new Scalar.Param(RADIUS, 0.1, 4, radius)),
                new Surface.Sphere(Scalar.of(2), new Scalar.Param(HEIGHT, 0, 3, 1), Scalar.of(0),
                        Scalar.of(0.5))));
    }

    @Test
    @DisplayName("sweeping a parameter's value changes neither the tree's shader key nor the lowered field")
    void sweepingAValueDoesNotChangeTheShaderKey() {
        Surface first = spheres(0.5);
        Field lowered = SurfaceCompiler.compile(first, store(ParamBlock.of(first)));

        for (int i = 0; i <= 200; i++) {
            Surface swept = spheres(0.1 + i * (3.9 / 200));
            assertEquals(first.shaderKey(), swept.shaderKey(),
                    "a value moved the shader key, so a sweep would recompile");
            assertEquals(lowered.distance(),
                    SurfaceCompiler.compile(swept, store(ParamBlock.of(swept))).distance(),
                    "a value reached the IR, so a sweep would recompile");
        }
    }

    @Test
    @DisplayName("the same shape under fresh identities is the same shader")
    void identityDoesNotReachTheShaderKey() {
        Surface once = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(0.5, 2));
        Surface again = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(0.5, 2));

        assertNotEquals(once, again, "two authorings are two documents");
        assertEquals(once.shaderKey(), again.shaderKey(), "but they are one pipeline");
    }

    @Test
    @DisplayName("a range does reach the shader key, because it is compile-time")
    void rangeReachesTheShaderKey() {
        Surface narrow = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(0.5, 2));
        Surface wide = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(0.5, 8));

        assertNotEquals(narrow.shaderKey(), wide.shaderKey());
    }

    @Test
    @DisplayName("a literal and a parameter over the same value are not the same shader")
    void aLiteralIsNotAParameter() {
        Surface baked = new Surface.Sphere(0, 0, 0, 1);
        Surface driven = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                Scalar.Param.over(1, 1, 1));

        assertNotEquals(baked.shaderKey(), driven.shaderKey());
    }

    @Test
    @DisplayName("a radius whose range crosses zero fails at construction, not partway along the drag")
    void aRangeThatCrossesZeroIsRefusedAtConstruction() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                        Scalar.Param.over(-1, 2)));
        assertTrue(thrown.getMessage().contains("radius"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("range"), thrown.getMessage());

        // The value it starts at is beside the point: this one is positive today and still refused.
        assertThrows(IllegalArgumentException.class,
                () -> new Surface.Shell(Scalar.Param.over(-0.5, 0.5, 0.25), new Surface.Sphere(0, 0, 0, 1)));
    }

    @Test
    @DisplayName("a parameter compiled with no block anywhere fails by name")
    void compilingWithoutABlockNamesTheParameter() {
        Surface surface = spheres(1);
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> SurfaceCompiler.compile(surface));
        assertTrue(thrown.getMessage().contains(RADIUS.toString()), thrown.getMessage());
    }

    @Test
    @DisplayName("slots are the walk order, distinct by identity, and values start at the declared initial")
    void slotsFollowTheWalk() {
        ParamBlock block = ParamBlock.of(spheres(0.75));

        assertEquals(2, block.size());
        assertEquals(0, block.slotOf(RADIUS));
        assertEquals(1, block.slotOf(HEIGHT));
        assertEquals(0.75, block.read(RADIUS));
        assertEquals(1.0, block.read(HEIGHT));
    }

    @Test
    @DisplayName("one identity used twice is one slot, and disagreeing ranges are refused by name")
    void oneIdentityIsOneSlot() {
        Scalar.Param shared = new Scalar.Param(RADIUS, 0.1, 4, 1);
        Surface twice = new Surface.Union(List.of(
                new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0), shared),
                new Surface.Sphere(Scalar.of(3), Scalar.of(0), Scalar.of(0), shared)));
        assertEquals(1, ParamBlock.of(twice).size());

        Surface disagreeing = new Surface.Union(List.of(
                new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0), shared),
                new Surface.Sphere(Scalar.of(3), Scalar.of(0), Scalar.of(0),
                        new Scalar.Param(RADIUS, 0.1, 9, 1))));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> ParamBlock.of(disagreeing));
        assertTrue(thrown.getMessage().contains(RADIUS.toString()), thrown.getMessage());
    }

    @Test
    @DisplayName("a write is clamped to the declared range, so bounds and validation stay true")
    void writesAreClamped() {
        ParamBlock block = ParamBlock.of(spheres(1));
        block.write(RADIUS, 100);
        assertEquals(4.0, block.read(RADIUS));
        block.write(RADIUS, -100);
        assertEquals(0.1, block.read(RADIUS));
        assertThrows(IllegalArgumentException.class, () -> block.write(RADIUS, Double.NaN));
    }

    @Test
    @DisplayName("values cross a recompile by identity: one that moved slot follows, one that went drops")
    void valuesCarryByIdentity() {
        ParamBlock before = ParamBlock.of(spheres(1));
        before.write(RADIUS, 2.5);
        before.write(HEIGHT, 0.25);

        // The radius survives the edit and moves to slot 1; the height's sphere is gone.
        Surface edited = new Surface.Union(List.of(
                new Surface.Sphere(Scalar.of(9), Scalar.of(0), Scalar.of(0), Scalar.of(1)),
                new Surface.Sphere(Scalar.of(0), Scalar.of(1), Scalar.of(0),
                        new Scalar.Param(RADIUS, 0.1, 4, 1))));
        ParamBlock after = ParamBlock.of(edited);
        after.carryFrom(before);

        assertEquals(2.5, after.read(RADIUS), "a held value did not survive a structural edit");
        assertFalse(after.holds(HEIGHT));
    }

    /**
     * A literal angle costs the shader nothing; a driven one costs one {@code sin} and one {@code cos}.
     *
     * <p>Written at P0a asserting only that the count was a multiple of six, because it was <b>24</b>: nothing
     * downstream eliminates common subexpressions, a domain transform lowered its child against the
     * transformed point <em>expression</em>, and a box reads its point four times — so the whole rotation was
     * written four times, six trigonometric reads apiece. The note then said P1 step 1 would collapse it, and
     * this is that assertion, tightened to the number it predicted.
     *
     * <p>Counted over the program rather than over the distance expression, because that is where the
     * trigonometry now lives: once, in the declaration of the point.
     */
    @Test
    @DisplayName("a literal angle folds to constants; a driven one pays exactly one sin and one cos")
    void aLiteralAngleStillFoldsToConstants() {
        Surface baked = Surface.Rotate.aboutY(0.7, new Surface.Box(0, 0, 0, 1, 1, 1));
        assertEquals(0, trigCalls(SurfaceCompiler.compile(baked)),
                "a literal rotation put trigonometry in the shader");

        Surface driven = Surface.Rotate.aboutY(Scalar.Param.over(0, Math.PI),
                new Surface.Box(0, 0, 0, 1, 1, 1));
        assertEquals(2, trigCalls(SurfaceCompiler.compile(driven, store(ParamBlock.of(driven)))),
                "a turn about +Y is one sin and one cos, and P1 is what makes it one of each");
    }

    @Test
    @DisplayName("a literal twist keeps its stretch bound a constant; a driven one computes it")
    void aLiteralTwistFoldsItsStretch() {
        Surface baked = new Surface.Twist(0.5, 2, new Surface.Box(0, 0, 0, 1, 2, 1));
        Surface driven = new Surface.Twist(Scalar.Param.over(0, 1), Scalar.of(2),
                new Surface.Box(0, 0, 0, 1, 2, 1));

        int bakedSqrts = sqrtCalls(SurfaceCompiler.compile(baked).distance());
        int drivenSqrts = sqrtCalls(
                SurfaceCompiler.compile(driven, store(ParamBlock.of(driven))).distance());

        assertTrue(drivenSqrts > bakedSqrts,
                "a driven twist must compute the stretch bound it can no longer fold: " + bakedSqrts
                        + " square roots either way");
    }

    @Test
    @DisplayName("an implicit reading a push-constant block the composer did not issue is refused by name")
    void aForeignPushConstantBlockIsRefused() {
        PushConstants foreign = PushConstants.of("mine", Ir.F32);
        Surface surface = new Surface.Implicit(
                Ir.sub(Ir.dot(Ir.POINT, Ir.POINT), foreign.read(0)));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> SurfaceCompiler.compile(surface));
        assertTrue(thrown.getMessage().contains("push-constant block"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Scalar.Param"), thrown.getMessage());
    }

    @Test
    @DisplayName("the block the composer did issue may be read from an implicit")
    void theIssuedBlockIsAllowed() {
        ParamBlock block = ParamBlock.of(new Surface.Sphere(0, 0, 0, 1));
        List<PushConstants.Member> members = List.of(new PushConstants.Member("camX", Ir.F32));
        PushConstants issued = new PushConstants(members);
        ParamStore store = block.inPushConstants(issued, 0);

        Surface surface = new Surface.Implicit(Ir.sub(Ir.dot(Ir.POINT, Ir.POINT), issued.read(0)));
        SurfaceCompiler.compile(surface, store);      // does not throw
    }

    @Test
    @DisplayName("bounds contain the geometry at every value the parameter can take")
    void boundsCoverTheWholeRange() {
        Surface driven = new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                new Scalar.Param(RADIUS, 0.5, 3, 0.5));
        Bounds bounds = Bounds.of(driven).orElseThrow();

        assertEquals(-3.0, bounds.minX());
        assertEquals(3.0, bounds.maxX());
        assertTrue(bounds.contains(0, 2.9, 0), "a value the slider can reach lies outside its own box");
    }

    @Test
    @DisplayName("a driven rotation sweeps its child, so the box is the ball the corners reach")
    void aDrivenRotationSweepsItsChild() {
        Surface driven = Surface.Rotate.aboutY(Scalar.Param.over(0, Math.PI),
                new Surface.Box(2, 0, 0, 1, 1, 1));
        Bounds bounds = Bounds.of(driven).orElseThrow();

        double reach = Math.sqrt(3 * 3 + 1 + 1);      // the far corner of the child's box
        assertEquals(reach, bounds.maxX(), 1e-9);
        assertEquals(-reach, bounds.minX(), 1e-9);
    }

    /** Trigonometric calls anywhere in a field's program — its declarations as well as its distance. */
    private static int trigCalls(Field field) {
        int total = trigCalls(field.distance());
        for (var let : field.lets()) {
            total += trigCalls(((dev.supirvast.vastir.core.Statement.DeclareVar) let).initializer());
        }
        return total;
    }

    private static int trigCalls(Expr e) {
        return count(e, MathFn.SIN) + count(e, MathFn.COS);
    }

    private static int sqrtCalls(Expr e) {
        return count(e, MathFn.SQRT);
    }


    private static int count(Expr e, MathFn fn) {
        int here = e instanceof Expr.MathCall call && call.fn() == fn ? 1 : 0;
        return here + switch (e) {
            case Expr.Binary b -> count(b.lhs(), fn) + count(b.rhs(), fn);
            case Expr.Unary u -> count(u.operand(), fn);
            case Expr.MathCall m -> m.args().stream().mapToInt(a -> count(a, fn)).sum();
            case Expr.VectorConstruct v -> v.components().stream().mapToInt(c -> count(c, fn)).sum();
            case Expr.VectorExtract v -> count(v.vector(), fn);
            case Expr.Convert c -> count(c.operand(), fn);
            default -> 0;
        };
    }
}
