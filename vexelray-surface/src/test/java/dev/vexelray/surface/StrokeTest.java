package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Surface.Stroke}: does it go through the points it was given, is it as thick as it was told to be, and is
 * what comes out still safe to march?
 *
 * <p>The first of those carries the weight. A rounded polyline that merely <em>approaches</em> its vertices is
 * the easy thing to build and the wrong thing to ship, so the guarantee is tested twice over — once on the ideal
 * chain {@link Spine} lays out (a joint exactly on each vertex, bit for bit) and once on the compiled field
 * (at least a full radius of material at each vertex), because a curve that passes through a point is worth
 * nothing if the geometry sampled from it does not.
 */
class StrokeTest {

    /** Curvatures spanning the whole family: crease, part-way, full arc. */
    private static final double[] CURVATURES = {0.0, 0.15, 0.5, 1.0};

    // --- the guarantee ---

    @Test
    @DisplayName("every vertex is a joint of the emitted chain, exactly, at every curvature")
    void verticesAreJointsOfTheChain() {
        for (double c : CURVATURES) {
            Surface.Stroke stroke = new Surface.Stroke(List.of(
                    new Surface.Stroke.Vertex(-2, 0, 0, 0.3, c),
                    new Surface.Stroke.Vertex(0, 0, 0, 0.5, c),
                    new Surface.Stroke.Vertex(0, 3, 0, 0.4, c),
                    new Surface.Stroke.Vertex(2.5, 3, 1, 0.2, c)));

            List<Spine.Piece> pieces = Spine.of(stroke);
            for (Surface.Stroke.Vertex v : stroke.through()) {
                assertTrue(isJoint(pieces, v),
                        "curvature " + c + ": no joint sits on (" + v.x() + ", " + v.y() + ", " + v.z() + ")");
            }
        }
    }

    @Test
    @DisplayName("the compiled field is a full radius deep at every vertex, at every curvature")
    void compiledFieldReachesEveryVertex() {
        for (double c : CURVATURES) {
            // A tight zig-zag: the case where corner rounding that cuts the corner would drift furthest.
            Surface.Stroke stroke = new Surface.Stroke(List.of(
                    new Surface.Stroke.Vertex(0, 0, 0, 0.25, c),
                    new Surface.Stroke.Vertex(1, 2, 0, 0.25, c),
                    new Surface.Stroke.Vertex(2, 0, 0, 0.25, c),
                    new Surface.Stroke.Vertex(3, 2, 0, 0.25, c)));
            Field field = SurfaceCompiler.compile(stroke);

            for (Surface.Stroke.Vertex v : stroke.through()) {
                double d = Eval.at(field.distance(), v.x(), v.y(), v.z());
                assertTrue(d <= -v.radius() + 1e-9,
                        "curvature " + c + ": vertex (" + v.x() + ", " + v.y() + ") reads " + d
                                + ", shallower than its own radius " + v.radius());
            }
        }
    }

    @Test
    @DisplayName("curvature only rounds the corner; it never moves the stroke off its vertices")
    void curvatureRoundsWithoutDrifting() {
        Surface.Stroke sharp = corner(0.0);
        Surface.Stroke round = corner(1.0);
        Field sharpField = SurfaceCompiler.compile(sharp);
        Field roundField = SurfaceCompiler.compile(round);

        // Both hold the corner vertex...
        assertTrue(Eval.at(sharpField.distance(), 0, 0, 0) <= -0.2 + 1e-9);
        assertTrue(Eval.at(roundField.distance(), 0, 0, 0) <= -0.2 + 1e-9);

        // ...and they are genuinely different shapes: the crease fills the inside of the right angle, the arc
        // bulges past it. Sampled on the bisector, a little outside the corner.
        double outside = Eval.at(roundField.distance(), 0.6, 0.6, 0)
                - Eval.at(sharpField.distance(), 0.6, 0.6, 0);
        assertTrue(outside < -1e-3, "a full arc should put material outside the crease, saw " + outside);
    }

    // --- thickness ---

    @Test
    @DisplayName("radius tapers linearly along a straight run")
    void radiusTapers() {
        Field field = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(0, 0, 0, 1.0, 0),
                new Surface.Stroke.Vertex(4, 0, 0, 0.2, 0))));

        assertEquals(-1.0, Eval.at(field.distance(), 0, 0, 0), 1e-9);
        assertEquals(-0.2, Eval.at(field.distance(), 4, 0, 0), 1e-9);
        // Half way along, the tube is half way between the two radii — measured across the axis, where the
        // taper's slope does not enter.
        assertEquals(-0.6, Eval.at(field.distance(), 2, 0, 0), 0.02);
    }

    @Test
    @DisplayName("a straight uniform stroke is exactly the capsule it ought to be")
    void straightStrokeMatchesACapsule() {
        Field stroke = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(-1, 0.5, 0, 0.35, 0),
                new Surface.Stroke.Vertex(2, 0.5, 0, 0.35, 0))));
        Field capsule = SurfaceCompiler.compile(new Surface.Capsule(-1, 0.5, 0, 2, 0.5, 0, 0.35));

        for (double[] p : samples()) {
            assertEquals(Eval.at(capsule.distance(), p[0], p[1], p[2]),
                    Eval.at(stroke.distance(), p[0], p[1], p[2]), 1e-9);
        }
    }

    @Test
    @DisplayName("a one-vertex stroke is a sphere")
    void oneVertexIsASphere() {
        Field stroke = SurfaceCompiler.compile(
                new Surface.Stroke(List.of(new Surface.Stroke.Vertex(0.5, -1, 2, 0.75, 0))));
        Field sphere = SurfaceCompiler.compile(new Surface.Sphere(0.5, -1, 2, 0.75));

        for (double[] p : samples()) {
            assertEquals(Eval.at(sphere.distance(), p[0], p[1], p[2]),
                    Eval.at(stroke.distance(), p[0], p[1], p[2]), 1e-9);
        }
    }

    @Test
    @DisplayName("a taper steep enough to swallow one end reads as the sphere that swallowed it")
    void degenerateTaperIsTheLargerSphere() {
        // The far end sits well inside the near one, so the hull is the near sphere and the round-cone formula
        // has no real value. Compiling must still produce that sphere rather than a NaN.
        Field stroke = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(0, 0, 0, 1.0, 0),
                new Surface.Stroke.Vertex(0.1, 0, 0, 0.05, 0))));
        Field sphere = SurfaceCompiler.compile(new Surface.Sphere(0, 0, 0, 1.0));

        for (double[] p : samples()) {
            double d = Eval.at(stroke.distance(), p[0], p[1], p[2]);
            assertTrue(Double.isFinite(d), "degenerate taper produced " + d);
            assertEquals(Eval.at(sphere.distance(), p[0], p[1], p[2]), d, 1e-9);
        }
    }

    // --- still marchable ---

    @Test
    @DisplayName("a stroke is exact, so it costs no normalisation")
    void strokeIsExact() {
        assertEquals(Field.EXACT, SurfaceCompiler.compile(corner(0.7)).lipschitz());
    }

    @Test
    @DisplayName("the field never grows faster than one unit per unit, which is what the march needs")
    void fieldStaysOneLipschitz() {
        Field field = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(-1.5, 0, 0, 0.4, 0.8),
                new Surface.Stroke.Vertex(0, 1.2, 0.3, 0.25, 0.8),
                new Surface.Stroke.Vertex(1.4, 0, -0.6, 0.5, 0.8),
                new Surface.Stroke.Vertex(2.2, 1.6, 0.4, 0.3, 0.8))));

        for (double x = -3; x <= 3.5; x += 0.37) {
            for (double y = -2; y <= 3; y += 0.41) {
                for (double z = -2; z <= 2; z += 0.43) {
                    double[] g = Eval.numericGradient(field.distance(), x, y, z, 1e-5);
                    double len = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
                    assertTrue(len <= 1.0 + 1e-3,
                            "gradient of length " + len + " at (" + x + ", " + y + ", " + z + ")");
                }
            }
        }
    }

    // --- colour ---

    private static final Surface.Rgb RED = new Surface.Rgb(1, 0, 0);
    private static final Surface.Rgb BLUE = new Surface.Rgb(0, 0, 1);

    @Test
    @DisplayName("an uncoloured surface carries no albedo at all, and so costs the composer nothing")
    void uncolouredSurfacesCarryNoAlbedo() {
        assertFalse(SurfaceCompiler.compile(corner(0.5)).hasAlbedo());
        assertFalse(SurfaceCompiler.compile(new Surface.Sphere(0, 0, 0, 1)).hasAlbedo());
        assertFalse(SurfaceCompiler.compile(Surface.union(
                new Surface.Sphere(0, 0, 0, 1), corner(0.5))).hasAlbedo());
    }

    @Test
    @DisplayName("colour gradients along a stroke and reads exactly at the vertices")
    void colourGradientsAlongTheStroke() {
        Field field = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(0, 0, 0, 0.3, 0).painted(RED),
                new Surface.Stroke.Vertex(4, 0, 0, 0.3, 0).painted(BLUE))));

        assertTrue(field.hasAlbedo());
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(field, 0, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0, 1}, albedoAt(field, 4, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0.5, 0, 0.5}, albedoAt(field, 2, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0.25, 0, 0.75}, albedoAt(field, 3, 0, 0), 1e-9);

        // Past either end the caps take the colour of the end they belong to, rather than extrapolating.
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(field, -2, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0, 1}, albedoAt(field, 9, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("the gradient follows the arc around a rounded corner, and holds the vertex colour at it")
    void colourFollowsARoundedCorner() {
        Field field = SurfaceCompiler.compile(new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(-2, 0, 0, 0.2, 1).painted(RED),
                new Surface.Stroke.Vertex(0, 0, 0, 0.2, 1).painted(Surface.Rgb.grey(0.5)),
                new Surface.Stroke.Vertex(0, 2, 0, 0.2, 1).painted(BLUE))));

        // The corner vertex is a joint of the chain, so its own colour is what is read there — the same
        // guarantee the geometry makes, in the colour channels.
        assertArrayEquals(new double[]{0.5, 0.5, 0.5}, albedoAt(field, 0, 0, 0), 1e-9);
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(field, -2, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0, 1}, albedoAt(field, 0, 2, 0), 1e-9);
    }

    @Test
    @DisplayName("a union takes the colour of whichever surface is nearer, and defers for the uncoloured one")
    void unionSelectsTheNearerColour() {
        Surface scene = Surface.union(
                Surface.Stroke.through(0.3, 0, RED, -3, 0, 0, -1, 0, 0),
                new Surface.Sphere(3, 0, 0, 0.5));
        Field field = SurfaceCompiler.compile(scene);

        assertTrue(field.hasAlbedo());
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(field, -2, 0, 0), 1e-9);
        // The sphere named no colour, so it reads the placeholder the composer fills in from the scene.
        assertArrayEquals(new double[]{0.25, 0.5, 0.75}, albedoAt(field, 3, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("colour survives the domain transforms, in the frame the surface was authored in")
    void colourSurvivesTransforms() {
        Surface painted = Surface.Stroke.through(0.3, 0, RED, 0, 0, 0, 2, 0, 0);
        Field field = SurfaceCompiler.compile(
                new Surface.Translate(0, 5, 0, Surface.Rotate.aboutY(Math.PI / 2, painted)));

        // The stroke ran along +X; a quarter turn about +Y puts it on -Z, then it moves up by five.
        assertTrue(Eval.at(field.distance(), 0, 5, -1) <= -0.3 + 1e-9);
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(field, 0, 5, -1), 1e-9);
    }

    @Test
    @DisplayName("a stroke is either wholly coloured or wholly not")
    void partlyColouredStrokesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(0, 0, 0, 1, 0).painted(RED),
                new Surface.Stroke.Vertex(1, 0, 0, 1, 0))));
    }

    // --- input that should be refused ---

    @Test
    @DisplayName("an odd corner count is refused, because it puts no sample on the vertex")
    void oddSegmentCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Surface.Stroke(
                List.of(Surface.Stroke.Vertex.round(0, 0, 0, 1)), 7));
    }

    @Test
    @DisplayName("empty strokes, non-positive radii and out-of-range curvature are refused")
    void malformedStrokesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Surface.Stroke(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Surface.Stroke.Vertex(0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Surface.Stroke.Vertex(0, 0, 0, 1, 1.5));
        assertThrows(IllegalArgumentException.class, () -> Surface.Stroke.through(1, 0, 0, 0));
    }

    @Test
    @DisplayName("a stroke is charged for the cones it lowers to, not for the one node it looks like")
    void limitsChargeForTheExpansion() {
        Surface.Stroke big = Surface.Stroke.through(0.1, 1.0, new double[3 * 200]);
        assertTrue(big.coneBound() > 1_000, "expected the bound to reflect the expansion, got " + big.coneBound());
        assertThrows(SurfaceLimits.SurfaceTooLargeException.class,
                () -> SurfaceCompiler.compile(big, new SurfaceLimits(100, 64)));
    }

    // --- helpers ---

    /** A right angle in the XY plane, turning through the origin, at the given curvature. */
    private static Surface.Stroke corner(double curvature) {
        return new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(-2, 0, 0, 0.2, curvature),
                new Surface.Stroke.Vertex(0, 0, 0, 0.2, curvature),
                new Surface.Stroke.Vertex(0, 2, 0, 0.2, curvature)));
    }

    /** Whether some piece begins or ends exactly on {@code v}, radius included. */
    private static boolean isJoint(List<Spine.Piece> pieces, Surface.Stroke.Vertex v) {
        for (Spine.Piece piece : pieces) {
            if (sits(piece.a(), v) || sits(piece.b(), v)) {
                return true;
            }
        }
        return false;
    }

    /** Whether one end of a cone is exactly a vertex — position, radius and colour alike. */
    private static boolean sits(Spine.End end, Surface.Stroke.Vertex v) {
        return end.x() == v.x() && end.y() == v.y() && end.z() == v.z()
                && end.radius() == v.radius()
                && java.util.Objects.equals(end.colour(), v.colour());
    }

    /**
     * The albedo at a point, with the scene placeholder resolved to a colour no test uses for anything else —
     * so a surface that wrongly defers to the scene shows up as that colour rather than blending in.
     */
    private static double[] albedoAt(Field field, double x, double y, double z) {
        Expr fallback = Ir.v3(0.25, 0.5, 0.75);
        java.util.List<dev.supirvast.vastir.core.Statement> lets = new java.util.ArrayList<>();
        for (dev.supirvast.vastir.core.Statement s : field.albedoLets()) {
            lets.add(Substitute.sceneAlbedo(s, fallback));
        }
        return Eval.withLets(Substitute.sceneAlbedo(field.albedo(), fallback), lets, x, y, z);
    }

    private static double[][] samples() {
        return new double[][]{
                {0.37, -0.81, 1.24}, {2.10, 0.55, -1.70}, {-1.31, 2.02, 0.19}, {0.05, 0.02, -0.03},
                {3.4, 2.2, -2.6}, {-0.9, 0.1, 0.8}};
    }
}
