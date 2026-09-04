package dev.vexelray.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Bounds}: is the box actually around the surface?
 *
 * <p>That is the only question worth asking, and the way to ask it is not to compare the box against another
 * box someone wrote down. It is to compile the surface and check the <em>field</em>: outside a correct box there
 * is no geometry, so every sample beyond it must read a positive distance. A box that is too large fails
 * nothing; a box that is too small — the failure that puts geometry outside the shot, which is what this class
 * exists to prevent — shows up immediately as a sample outside the box sitting inside the surface.
 *
 * <p>The per-node tests below are the cheap half, pinning what a box says for shapes whose answer is obvious.
 * The containment sweep is the half that would catch a genuinely wrong rule.
 */
class BoundsTest {

    /** Surfaces spanning every bounded rule, each one something a box could plausibly get wrong. */
    private static List<Surface> bounded() {
        return List.of(
                new Surface.Sphere(1, -2, 0.5, 0.75),
                new Surface.Box(0, 1, 0, 0.5, 0.25, 2),
                new Surface.Capsule(-1, 0, 0, 1, 2, 0.5, 0.3),
                new Surface.Torus(0.5, 0, -1, 1.2, 0.35),
                zigzag(4, 1.0),
                zigzag(3, 0.0),
                new Surface.Translate(3, -1, 2, new Surface.Sphere(0, 0, 0, 0.6)),
                new Surface.Scale(2.5, new Surface.Sphere(0.4, 0, 0, 0.3)),
                new Surface.Rotate(1, 1, 0, 0.7, new Surface.Box(1, 0, 0, 0.6, 0.2, 0.2)),
                Surface.Rotate.aboutY(Math.PI / 3, zigzag(4, 1.0)),
                new Surface.Mirror(true, false, true, new Surface.Sphere(1.5, 0.5, 1.5, 0.4)),
                new Surface.Repeat(Surface.Repeat.Axis.range(1.5, -1, 2), Surface.Repeat.Axis.NONE,
                        Surface.Repeat.Axis.NONE, new Surface.Sphere(0, 0, 0, 0.4)),
                new Surface.PolarRepeat(5, new Surface.Box(1.2, 0, 0, 0.3, 0.5, 0.2)),
                new Surface.Twist(0.4, 1.5, new Surface.Box(0, 0, 0, 1.0, 0.8, 0.4)),
                new Surface.Bend(0.3, 2.0, new Surface.Box(0, 0, 0, 1.5, 0.3, 0.3)),
                Surface.union(new Surface.Sphere(-1, 0, 0, 0.5), new Surface.Sphere(1.5, 1, 0, 0.4)),
                Surface.smoothUnion(6.0, new Surface.Sphere(-0.6, 0, 0, 0.5),
                        new Surface.Sphere(0.6, 0, 0, 0.5)),
                new Surface.Difference(new Surface.Box(0, 0, 0, 1, 1, 1), new Surface.Sphere(0, 1, 0, 0.7)),
                Surface.intersection(new Surface.Sphere(0, 0, 0, 1), new Surface.Box(0.5, 0, 0, 1, 1, 1)),
                new Surface.Shell(0.15, new Surface.Sphere(0, 0, 0, 1)),
                new Surface.Round(0.25, new Surface.Box(0, 0, 0, 0.8, 0.4, 0.4)));
    }

    @Test
    @DisplayName("no geometry lies outside the box, for any surface that has one")
    void nothingEscapesTheBox() {
        for (Surface surface : bounded()) {
            Bounds box = Bounds.of(surface).orElseThrow(
                    () -> new AssertionError("expected a box for " + surface.getClass().getSimpleName()));
            Field field = SurfaceCompiler.compile(surface);

            int outside = 0;
            for (double[] p : grid(box)) {
                if (box.contains(p[0], p[1], p[2])) {
                    continue;
                }
                outside++;
                double d = Eval.at(field.distance(), p[0], p[1], p[2]);
                assertTrue(d > 0,
                        surface.getClass().getSimpleName() + ": (" + p[0] + ", " + p[1] + ", " + p[2]
                                + ") is outside the box but reads " + d + " — the box is too small, which is"
                                + " the one way this can be wrong that matters");
            }
            assertTrue(outside > 0, "the sweep never sampled outside the box, so it proved nothing");
        }
    }

    @Test
    @DisplayName("a sphere's box is its own, exactly")
    void sphereIsExact() {
        Bounds b = Bounds.of(new Surface.Sphere(1, -2, 0.5, 0.75)).orElseThrow();
        assertEquals(0.25, b.minX(), 1e-12);
        assertEquals(-2.75, b.minY(), 1e-12);
        assertEquals(1.75, b.maxX(), 1e-12);
        assertArray(new double[]{1, -2, 0.5}, b.centre());
        assertArray(new double[]{0.75, 0.75, 0.75}, b.halfExtent());
    }

    @Test
    @DisplayName("a stroke's box is around the cones it draws, not around the vertices it was given")
    void strokeBoundsTheRenderedShape() {
        // A rounded corner bulges past its vertex — that is the construction working — so a box drawn around
        // the control points alone would clip exactly the part that curvature added.
        Surface.Stroke round = zigzag(3, 1.0);
        Bounds b = Bounds.of(round).orElseThrow();
        Field field = SurfaceCompiler.compile(round);
        for (Surface.Stroke.Vertex v : round.through()) {
            assertTrue(b.contains(v.x(), v.y(), v.z()),
                    "vertex (" + v.x() + ", " + v.y() + ") is outside its own stroke's box");
        }
        // And the bulge itself is inside the box: sample along the outward bisector of the middle corner.
        for (double t = 0; t < 0.6; t += 0.05) {
            double x = 0;
            double y = 1.0 + t;
            if (Eval.at(field.distance(), x, y, 0) <= 0) {
                assertTrue(b.contains(x, y, 0), "material at (0, " + y + ") is outside the box");
            }
        }
    }

    @Test
    @DisplayName("what has no box says so, instead of inventing one")
    void unboundedSurfacesReportNothing() {
        assertEquals(Optional.empty(), Bounds.of(Surface.Plane.ground()));
        assertEquals(Optional.empty(), Bounds.of(new Surface.Implicit(
                Ir2.sub(Ir2.dot(dev.vexelray.ir.Ir.POINT, dev.vexelray.ir.Ir.POINT), Ir2.f(1.0)))));
        assertEquals(Optional.empty(), Bounds.of(Surface.Repeat.grid(2.0, new Surface.Sphere(0, 0, 0, 0.5))));
        // A union is only as bounded as its least bounded child.
        assertEquals(Optional.empty(), Bounds.of(Surface.union(
                new Surface.Sphere(0, 0, 0, 1), Surface.Plane.ground())));
    }

    @Test
    @DisplayName("a ground plane does not defeat framing, and this is the bug that made a frame come out empty")
    void sceneryDoesNotDecideTheShot() {
        // The case that broke: union a stroke with a ground plane and `of` correctly reports no box, because a
        // half-space reaches forever. A host framing on that framed on nothing, left the stroke where it was
        // authored, and rendered empty space. Scenery is not the subject.
        Surface.Stroke stroke = zigzag(3, 1.0);
        Surface scene = Surface.union(stroke, Surface.Plane.ground());

        assertEquals(Optional.empty(), Bounds.of(scene), "containment must still refuse: the plane is infinite");

        Bounds subject = Bounds.subject(scene).orElseThrow(
                () -> new AssertionError("framing must find the stroke behind the plane"));
        assertEquals(Bounds.of(stroke).orElseThrow(), subject,
                "framing a stroke behind scenery should give exactly the stroke's own box");
    }

    @Test
    @DisplayName("framing sees through transforms and blends to whatever has a size")
    void framingReachesThroughTheTree() {
        Surface.Stroke stroke = zigzag(3, 1.0);
        // The subject buried under a transform, beside two different kinds of infinity.
        Surface scene = new Surface.Translate(10, 0, 0, Surface.union(
                Surface.Plane.ground(),
                Surface.Repeat.grid(3.0, new Surface.Sphere(0, 0, 0, 0.2)),
                stroke));

        assertEquals(Optional.empty(), Bounds.of(scene));
        Bounds subject = Bounds.subject(scene).orElseThrow();
        Bounds expected = Bounds.of(new Surface.Translate(10, 0, 0, stroke)).orElseThrow();
        assertEquals(expected, subject);
    }

    @Test
    @DisplayName("framing is still empty when nothing at all has a size")
    void framingRefusesWhenThereIsNoSubject() {
        assertEquals(Optional.empty(), Bounds.subject(Surface.Plane.ground()));
        assertEquals(Optional.empty(), Bounds.subject(
                Surface.union(Surface.Plane.ground(), Surface.Repeat.grid(2.0,
                        new Surface.Sphere(0, 0, 0, 0.5)))));
    }

    @Test
    @DisplayName("where a surface is wholly bounded, the two questions give the same answer")
    void framingAndContainmentAgreeOnBoundedSurfaces() {
        for (Surface surface : bounded()) {
            assertEquals(Bounds.of(surface), Bounds.subject(surface),
                    surface.getClass().getSimpleName() + ": the two should only ever differ where something"
                            + " in the tree has no box");
        }
    }

    @Test
    @DisplayName("an intersection is bounded as soon as any one child is")
    void intersectionNeedsOnlyOneBoundedChild() {
        Bounds b = Bounds.of(Surface.intersection(
                Surface.Plane.ground(), new Surface.Sphere(0, 0, 0, 1))).orElseThrow();
        assertTrue(b.largestHalfExtent() <= 1 + 1e-12, "an overlap cannot be larger than its smallest child");
    }

    @Test
    @DisplayName("a difference is bounded by what it was cut from")
    void differenceKeepsTheBoxOfItsSubject() {
        Bounds b = Bounds.of(new Surface.Difference(
                new Surface.Box(0, 0, 0, 1, 1, 1), new Surface.Sphere(0, 5, 0, 9))).orElseThrow();
        assertArray(new double[]{1, 1, 1}, b.halfExtent());
    }

    // --- helpers ---

    /** A shortcut so the implicit case reads without three package-qualified names per line. */
    private static final class Ir2 {
        static dev.supirvast.vastir.core.Expr sub(dev.supirvast.vastir.core.Expr a,
                                                  dev.supirvast.vastir.core.Expr b) {
            return dev.vexelray.ir.Ir.sub(a, b);
        }

        static dev.supirvast.vastir.core.Expr dot(dev.supirvast.vastir.core.Expr a,
                                                  dev.supirvast.vastir.core.Expr b) {
            return dev.vexelray.ir.Ir.dot(a, b);
        }

        static dev.supirvast.vastir.core.Expr f(double v) {
            return dev.vexelray.ir.Ir.f(v);
        }
    }

    private static Surface.Stroke zigzag(int vertices, double curvature) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            vs.add(new Surface.Stroke.Vertex(i - (vertices - 1) / 2.0, (i % 2 == 0 ? 0 : 1), 0,
                    0.2, curvature));
        }
        return new Surface.Stroke(vs, 4);
    }

    /** Samples spanning well past the box on every side, so most of them land outside it. */
    private static List<double[]> grid(Bounds box) {
        double[] c = box.centre();
        double reach = Math.max(0.5, box.largestHalfExtent()) * 2.2;
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            for (int j = 0; j <= 10; j++) {
                for (int k = 0; k <= 10; k++) {
                    points.add(new double[]{
                            c[0] + reach * (i / 5.0 - 1),
                            c[1] + reach * (j / 5.0 - 1),
                            c[2] + reach * (k / 5.0 - 1)});
                }
            }
        }
        return points;
    }

    private static void assertArray(double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-12, "component " + i);
        }
    }
}
