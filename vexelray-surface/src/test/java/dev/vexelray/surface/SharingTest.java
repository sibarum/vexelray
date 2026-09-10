package dev.vexelray.surface;

import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1: a design's shader grows with the operators in it, not with the product of their multiplicities.
 *
 * <p>Two mechanisms, and each has its own claim. A domain operator <b>names</b> the point it transforms, so a
 * primitive that reads its point four times reads a local four times instead of the whole transform four
 * times. And a subtree the lowering would otherwise write out several times — the child of a repeat, or a
 * shape the author used twice — is <b>emitted once as a function</b> and called.
 *
 * <p>The claim that makes both trustworthy is {@link #sharingChangesNoAnswer}: the shared program and the
 * inlined expression agree everywhere. Everything else here is about size, and size is worth nothing if the
 * picture changed.
 */
class SharingTest {

    /** Eight spheres in a row, blended — the ladder's first rung, and the base every other rung wraps. */
    private static Surface base() {
        Surface[] eight = new Surface[8];
        for (int i = 0; i < 8; i++) {
            eight[i] = new Surface.Sphere(i * 0.4 - 1.4, 0, 0, 0.5);
        }
        return Surface.smoothUnion(8, eight);
    }

    /** The ladder of docs/framework-requirements.md §2, one operator at a time. */
    private static List<Surface> ladder() {
        Surface base = base();
        Surface repeat3 = new Surface.Repeat(Surface.Repeat.Axis.every(1.5),
                Surface.Repeat.Axis.every(1.5), Surface.Repeat.Axis.every(1.5), base);
        Surface twist = new Surface.Twist(0.6, 4, repeat3);
        Surface bend = new Surface.Bend(0.3, 4, twist);
        Surface polar = new Surface.PolarRepeat(6, bend);
        Surface mirror = new Surface.Mirror(true, false, true, polar);
        return List.of(base, Surface.Repeat.grid(1.5, base), repeat3, twist, bend, polar, mirror,
                Surface.Repeat.grid(20, mirror));
    }

    @Test
    @DisplayName("the ladder runs to its last rung: eight operators still compile")
    void theLadderRunsToItsLastRung() {
        for (Surface rung : ladder()) {
            Field field = SurfaceCompiler.compile(rung);
            assertTrue(field.isMarchable(), "a rung stopped being marchable");
            assertTrue(field.nodes() > 0);
        }
    }

    @Test
    @DisplayName("eight operators cost a small multiple of one, not a power of it")
    void sizeIsAdditiveInTheOperatorCount() {
        List<Surface> ladder = ladder();
        int one = SurfaceCompiler.compile(ladder.get(0)).nodes();
        int eight = SurfaceCompiler.compile(ladder.get(ladder.size() - 1)).nodes();

        // A ratio rather than a size, so the assertion survives the field itself getting cheaper or dearer.
        // Measured at about 1.2x; the bound is generous because what it is defending against is 270x, which
        // is what the last rung that still compiled cost before P1 — and the three rungs after it did not
        // compile at all.
        double ratio = eight / (double) one;
        assertTrue(ratio < 4.0,
                () -> "eight operators cost " + Math.round(ratio * 10) / 10.0 + "x one operator ("
                        + eight + " nodes against " + one + "); sharing has stopped working");
    }

    @Test
    @DisplayName("sharing changes no answer: the shared program and the inlined expression agree")
    void sharingChangesNoAnswer() {
        // The differential that makes the stage trustworthy, and it is cheap because both sides come from
        // one lowering: Field.at is the same field with its declarations substituted back and its calls
        // expanded, which is precisely the tree this compiler emitted before P1.
        //
        // Only the rungs whose inlined form exists. Expansion is multiplicative, so beyond the fifth there
        // is no inlined field to compare against — the fifth was four megabytes and the sixth was refused,
        // which is the whole reason the stage happened. Those rungs are covered by the ladder and spirv-val
        // tests instead, and by the fact that this comparison holds everywhere it can be made.
        for (Surface rung : ladder().subList(0, 5)) {
            Field field = SurfaceCompiler.compile(rung);
            for (double[] p : grid()) {
                double shared = Eval.at(field, p[0], p[1], p[2]);
                double inlined = Eval.at(field.at(Ir.POINT), p[0], p[1], p[2]);
                assertEquals(inlined, shared, 1e-6 * Math.max(1, Math.abs(inlined)),
                        () -> "sharing moved the surface at (" + p[0] + ", " + p[1] + ", " + p[2] + ")");
            }
        }
    }

    @Test
    @DisplayName("a shape used twice is lowered once")
    void aShapeUsedTwiceIsLoweredOnce() {
        Surface arm = new Surface.Translate(0, 1, 0,
                new Surface.Box(0, 0, 0, 0.2, 1, 0.2));
        int one = SurfaceCompiler.compile(arm).nodes();
        Field two = SurfaceCompiler.compile(Surface.union(arm,
                new Surface.Translate(2, 0, 0, arm)));

        assertEquals(1, two.helpers().size(), "the repeated arm should be one function");
        // Two arms for well under two arms' worth of nodes: the second site is a call.
        assertTrue(two.nodes() < 2 * one,
                () -> "two copies cost " + two.nodes() + " nodes against " + one + " for one");
    }

    @Test
    @DisplayName("a repeat is additive in size and multiplicative in work, and says which is which")
    void theTwoBudgetsAreReportedSeparately() {
        Surface child = base();
        Field alone = SurfaceCompiler.compile(child);
        Field repeated = SurfaceCompiler.compile(new Surface.Repeat(Surface.Repeat.Axis.every(1.5),
                Surface.Repeat.Axis.every(1.5), Surface.Repeat.Axis.every(1.5), child));

        // Size: one copy of the child, plus the cell arithmetic and eight calls.
        assertTrue(repeated.nodes() < 2 * alone.nodes(),
                () -> "a three-axis repeat emitted " + repeated.nodes() + " nodes for a child of "
                        + alone.nodes() + "; that is not one copy");

        // Work: the neighbour rule really does evaluate the child eight times per query, and the honest
        // report of that is what P3 will predict against. Sharing moved cost from the module to the GPU
        // deliberately; a stage that claimed otherwise would be hiding the trade.
        assertTrue(repeated.evaluations() > 7 * alone.evaluations(),
                () -> "a three-axis repeat reported " + repeated.evaluations() + " evaluations against "
                        + alone.evaluations() + " for its child; the neighbour rule costs eight");
    }

    @Test
    @DisplayName("a painted subtree is never shared, because a function returns a distance and no colour")
    void colourIsNeverShared() {
        Surface painted = Surface.Stroke.through(0.2, 1, new Surface.Rgb(1, 0, 0),
                -1, 0, 0, 0, 0.5, 0, 1, 0, 0);
        Field field = SurfaceCompiler.compile(Surface.Repeat.grid(4, painted));

        assertTrue(field.helpers().isEmpty(), "a coloured subtree was shared, which would drop its colour");
        assertTrue(field.hasAlbedo(), "the colour survived the repeat");
    }

    @Test
    @DisplayName("a surface with nothing to share and nothing to transform declares nothing")
    void asimpleSurfaceIsStillJustAnExpression() {
        Field field = SurfaceCompiler.compile(new Surface.Sphere(0, 1, 0, 1));

        assertTrue(field.lets().isEmpty(), "a bare primitive should need no declarations");
        assertTrue(field.helpers().isEmpty(), "a bare primitive should need no functions");
        assertEquals(field.nodes(), (int) field.evaluations(),
                "with no calls, what is emitted and what runs are the same program");
    }

    private static List<double[]> grid() {
        List<double[]> points = new ArrayList<>();
        for (double x = -2.3; x <= 2.3; x += 1.15) {
            for (double y = -1.7; y <= 1.7; y += 1.13) {
                for (double z = -2.1; z <= 2.1; z += 1.05) {
                    points.add(new double[]{x, y, z});
                }
            }
        }
        return points;
    }
}
