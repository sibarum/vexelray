package dev.vexelray.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stroke that turns hard enough for a corner to double back on itself, which is where a cone of no length
 * gets emitted and the field stops being a field.
 *
 * <p>The case that found this was a plot of {@code 0^(wx)} — a unit helix about the x axis, sixty degrees of
 * turn at every one of twenty-five joints. It drew nothing at all, on screen or in a capture, while every
 * check upstream passed: the values were right, the geometry was right, the compiled field was finite,
 * one-Lipschitz and exact, and the march ran and reported a shader.
 *
 * <p>What escaped was one ULP. Two Bézier samples either side of such a corner land at the same point to
 * within a last bit, so the axis between them is about {@code 1e-17} — not zero, so no exact test caught it,
 * and the radii are equal, so {@link Spine.Piece#degenerate()}'s comparison against the radius
 * <em>difference</em> did not either. {@code SurfaceCompiler.roundCone} carries the field at scale
 * {@code l2 * l2}, and {@code 1e-33} squared underflows float32 to zero: the field goes to {@code 0/0} on the
 * GPU while staying perfectly finite in double on the CPU, so every ray reports a hit and the scene renders
 * as the inside of a surface. Nothing anywhere failed.
 *
 * <p>So these assert on the emitted chain rather than on a picture: no piece may be short enough to be
 * unrepresentable, whatever the geometry handed in.
 */
class DoublingBackStrokeTest {

    /** Below this, an axis squared underflows float32 once the field squares it. */
    private static final double UNREPRESENTABLE = 1e-19;

    /** Curvatures spanning the whole family: crease, part-way, full arc. */
    private static final double[] CURVATURES = {0.0, 0.15, 0.5, 1.0};

    /**
     * A chain of {@code n} vertices spiralling about the x axis, turning {@code deg} at each joint. At six
     * vertices and sixty degrees this emits two zero-length pieces; at five and at seven it emits none, which
     * is why the failure looked like nothing in particular and bisected to nothing.
     */
    private static Surface.Stroke coil(int n, double deg) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = -1 + 2.0 * i / (n - 1);
            double a = Math.toRadians(deg) * (i - (n - 1) / 2.0);
            vs.add(new Surface.Stroke.Vertex(x, Math.cos(a), Math.sin(a), 0.022, 1));
        }
        return new Surface.Stroke(vs, 2);
    }

    @Test
    @DisplayName("no cone is too short for the field that divides by its length, at any turn or joint count")
    void noPieceIsUnrepresentablyShort() {
        for (int n = 2; n <= 30; n++) {
            for (double deg : new double[]{1, 15, 21, 22.5, 24, 30, 45, 60, 90, 120, 179}) {
                for (Spine.Piece p : Spine.of(coil(n, deg))) {
                    double l2 = p.axisLengthSquared();
                    assertTrue(l2 == 0 || l2 > UNREPRESENTABLE || p.degenerate(),
                            "n=" + n + " deg=" + deg + ": a piece of length^2 " + l2
                                    + " is neither zero, representable, nor treated as a sphere");
                }
            }
        }
    }

    @Test
    @DisplayName("a doubled-back corner collapses to a sphere rather than a cone of no length")
    void theShortPieceIsCalledDegenerate() {
        List<Spine.Piece> pieces = Spine.of(coil(6, 60));
        int shorts = 0;
        for (Spine.Piece p : pieces) {
            if (p.axisLengthSquared() < UNREPRESENTABLE) {
                shorts++;
                assertTrue(p.degenerate(), "a piece of length^2 " + p.axisLengthSquared()
                        + " has to be a sphere; as a cone its field divides by that");
            }
        }
        assertEquals(2, shorts, "the six-vertex coil is the case that emitted two of them");
    }

    @Test
    @DisplayName("the field stays a field where the corner doubles back")
    void fieldIsSaneAroundTheDegenerateCorner() {
        Field wide = SurfaceCompiler.compile(coil(25, 60));
        // Far outside the coil the distance must be large and positive; the failure made it read as inside.
        double eye = Eval.at(wide.distance(), 2.2, 2.9, 2.0);
        assertTrue(eye > 1.0, "outside the coil the field reads " + eye + ", which is not outside");
        // And at the doubled-back corner itself, a full radius of material -- on the coil that has one.
        Field six = SurfaceCompiler.compile(coil(6, 60));
        double atCorner = Eval.at(six.distance(), -0.4, 0.4330127018922194, -0.75);
        assertTrue(atCorner <= -0.022 + 1e-6,
                "the corner reads " + atCorner + ", shallower than the radius that should be there");
    }

    /** Whether one end of a cone is exactly a vertex -- position, radius and colour alike. */
    private static boolean sits(Spine.End end, Surface.Stroke.Vertex v) {
        return end.x() == v.x() && end.y() == v.y() && end.z() == v.z()
                && end.radius() == v.radius()
                && java.util.Objects.equals(end.colour(), v.colour());
    }
}
