package dev.vexelray.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Surface.Stroke} into the chain of tapered round cones the compiler actually emits — the whole
 * geometric argument of the node, done in Java on compile-time constants so that none of it reaches the shader.
 *
 * <p>Everything about a stroke is known at compile time: the vertices, the radii, the curvature, the colours.
 * That is worth exploiting rather than deferring. The corner construction below involves a Bézier evaluation per
 * sample and a degeneracy test per piece; done here it is a handful of {@code double} arithmetic, done in the
 * field it would be that arithmetic <em>per ray step, per pixel</em>. So {@link #of} is where the interesting
 * decisions live, and {@link SurfaceCompiler} is left with nothing to do but emit a {@code min} over exact
 * primitives.
 *
 * <h2>Why the corner curve is not a plain quadratic Bézier</h2>
 *
 * <p>The obvious way to round a corner is to cut it: pick handles {@code H-} and {@code H+} on the two adjacent
 * segments and run a quadratic Bézier between them with the vertex {@code V} as its control point. That is what
 * most stroke rounding does, and it has a property this node must not have — <b>the curve never reaches
 * {@code V}</b>. It passes at {@code (H- + 2V + H+)/4}, short of the corner by an amount that grows with how
 * sharply the stroke turns. A vertex placed by a caller would then be a suggestion rather than a position, and
 * the more curvature they asked for the further the shape would drift from the points they specified.
 *
 * <p>So the control point is solved for instead of assumed: {@code P = 2V - (H- + H+)/2} makes
 * {@code B(0.5) = V} identically, for any handles at all. The vertex is on the curve by construction, at its
 * midpoint, at every curvature — and at zero curvature the handles collapse onto {@code V} and the two straight
 * runs meet there in a sharp angle. One parameter, continuous from crease to arc, and the vertex is a fixed
 * point of the whole family.
 *
 * <p>The sampling keeps that promise rather than approximating it: an <b>even</b> number of sub-cones per corner
 * puts a sample exactly at {@code u = 0.5}, so {@code V} is a joint of the emitted chain and not merely close to
 * one. That is why {@link Surface.Stroke} insists the count be even. The ideal curve passing through the vertex
 * would be worth little if the discretised one everybody actually looks at did not.
 *
 * <p>Radius and colour ride the same construction in their own channels — a scalar Bézier whose control value is
 * solved so that the value at {@code u = 0.5} is exactly the vertex's own — then clamped to the hull of the
 * three values it interpolates, because a quadratic through three values can overshoot outside them and neither
 * a negative radius nor a negative colour channel means anything. Position is deliberately <em>not</em> clamped:
 * bulging past the handles is how the curve reaches the vertex in the first place.
 */
final class Spine {

    /**
     * The shortest axis a cone may have, as a fraction of its own radius, before it is treated as a sphere.
     *
     * <p>Generous by sixteen orders of magnitude over the ULP noise it exists to catch, and still ten orders
     * below any axis a caller could mean: a cone a millionth of its own thickness long is the sphere at
     * either end of it, whatever it was meant to be.
     */
    private static final double SHORTEST_AXIS = 1e-6;

    private Spine() {
    }

    /** One end of a cone: where it is, how thick it is there, and what colour ({@code null} for none). */
    record End(double x, double y, double z, double radius, Surface.Rgb colour) {
    }

    /**
     * One tapered round cone: the exact convex hull of the two end spheres, its colour gradienting along the
     * axis between them. A whole stroke is the union of these, and nothing else.
     */
    record Piece(End a, End b) {

        /** Squared length of the axis. */
        double axisLengthSquared() {
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            return dx * dx + dy * dy + dz * dz;
        }

        /**
         * Whether one end sphere swallows the other, so the hull <em>is</em> that sphere. Worth naming because
         * the exact round-cone field divides by {@code |b-a|^2 - (ar-br)^2} and takes its square root: at this
         * point that quantity is zero or negative, and the formula stops being real. The compiler tests for it
         * here, on constants, and emits a plain sphere instead — no branch, no epsilon, no NaN.
         */
        boolean degenerate() {
            double rr = a.radius() - b.radius();
            double l2 = axisLengthSquared();
            if (l2 <= rr * rr) {
                return true;
            }
            // ...and an axis so short that the two end spheres ARE one sphere. Separate from the swallowing
            // test above because that one compares against the radius *difference*, so it never fires when
            // the radii are equal -- and equal radii is the ordinary case for a stroke of uniform thickness.
            //
            // A chain that turns hard enough for a corner to double back lands two Bezier samples one ULP
            // apart: an axis of 1e-17, which is not equal to zero and so escapes every exact test. The
            // round-cone field is carried at scale {@code l2 * l2} (see SurfaceCompiler.roundCone), and
            // 1e-33 squared underflows float32 to zero, so the field becomes 0/0 on the GPU while staying
            // finite in double on the CPU. Every ray then reports a hit and the whole scene renders as
            // inside-the-surface -- a stroke that vanishes, with nothing anywhere saying why.
            double widest = Math.max(a.radius(), b.radius());
            double shortest = SHORTEST_AXIS * widest;
            return l2 <= shortest * shortest;
        }

        /** The end whose sphere a {@link #degenerate()} piece collapses to. */
        End swallowing() {
            return a.radius() >= b.radius() ? a : b;
        }
    }

    /**
     * The cones a stroke lowers to, in order along it: a straight run per segment (shortened at each end by
     * however much curvature the neighbouring corner consumed) and a fan of sub-cones per rounded corner.
     *
     * <p>Never empty — a single vertex is a sphere, which is the right answer for a stroke with nowhere to go.
     */
    static List<Piece> of(Surface.Stroke stroke) {
        List<Surface.Stroke.Vertex> v = stroke.through();
        int n = v.size();
        List<Piece> pieces = new ArrayList<>();

        if (n == 1) {
            return List.of(new Piece(end(v.get(0)), end(v.get(0))));
        }

        // The running end of the last thing emitted: where the next straight run starts. Begins at the first
        // vertex and is left at each corner's outgoing handle.
        End cursor = end(v.get(0));

        for (int i = 1; i < n - 1; i++) {
            Surface.Stroke.Vertex prev = v.get(i - 1);
            Surface.Stroke.Vertex here = v.get(i);
            Surface.Stroke.Vertex next = v.get(i + 1);

            // Handles at half of each adjacent segment at full curvature. Half, not more: two corners sharing a
            // segment then meet at its midpoint at worst, so a rounded corner can never eat past its neighbour's
            // and the straight run between them never runs backwards.
            double t = 0.5 * here.curvature();
            double[] hMinus = lerp(channels(here), channels(prev), t);
            double[] hPlus = lerp(channels(here), channels(next), t);

            add(pieces, cursor, end(hMinus, here.colour() != null));

            if (t > 0) {
                double[] middle = channels(here);
                // The control point that puts the vertex on its own curve, solved rather than assumed.
                double[] ctrl = new double[CHANNELS];
                double[] lo = new double[CHANNELS];
                double[] hi = new double[CHANNELS];
                for (int c = 0; c < CHANNELS; c++) {
                    ctrl[c] = 2 * middle[c] - 0.5 * (hMinus[c] + hPlus[c]);
                    lo[c] = Math.min(middle[c], Math.min(hMinus[c], hPlus[c]));
                    hi[c] = Math.max(middle[c], Math.max(hMinus[c], hPlus[c]));
                }

                int steps = stroke.segmentsPerCorner();
                double[] from = hMinus;
                for (int k = 1; k <= steps; k++) {
                    // The two samples the promise rests on are written down rather than computed. B(0.5) is the
                    // vertex and B(1) is the outgoing handle *in exact arithmetic*; evaluating the polynomial
                    // for them in floating point would land an ulp or two away, and "the vertex is on the
                    // surface, give or take an ulp" is a weaker claim than this node is meant to make.
                    double[] to;
                    if (k == steps) {
                        to = hPlus;
                    } else if (2 * k == steps) {
                        to = middle;
                    } else {
                        to = bezier(hMinus, ctrl, hPlus, (double) k / steps, lo, hi);
                    }
                    add(pieces, end(from, here.colour() != null), end(to, here.colour() != null));
                    from = to;
                }
            }
            cursor = end(hPlus, here.colour() != null);
        }

        add(pieces, cursor, end(v.get(n - 1)));

        if (pieces.isEmpty()) {
            // Every vertex coincided at the same radius. Still a shape: the sphere they all sit on.
            return List.of(new Piece(end(v.get(0)), end(v.get(0))));
        }
        return pieces;
    }

    // --- interpolation, over one flat array of channels ---

    /** Position, radius, then colour: everything that varies along a stroke, interpolated the same way. */
    private static final int CHANNELS = 7;

    private static double[] channels(Surface.Stroke.Vertex v) {
        Surface.Rgb c = v.colour() == null ? new Surface.Rgb(0, 0, 0) : v.colour();
        return new double[]{v.x(), v.y(), v.z(), v.radius(), c.r(), c.g(), c.b()};
    }

    private static End end(Surface.Stroke.Vertex v) {
        return new End(v.x(), v.y(), v.z(), v.radius(), v.colour());
    }

    private static End end(double[] ch, boolean coloured) {
        return new End(ch[0], ch[1], ch[2], ch[3],
                coloured ? new Surface.Rgb(ch[4], ch[5], ch[6]) : null);
    }

    /** {@code from} a fraction {@code t} of the way toward {@code to}, every channel together. */
    private static double[] lerp(double[] from, double[] to, double t) {
        double[] out = new double[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            out[i] = from[i] + t * (to[i] - from[i]);
        }
        return out;
    }

    /**
     * The quadratic Bézier at {@code u}. Position runs free — the bulge past the handles is what reaches the
     * vertex — while radius and colour are held inside the hull of the three values they span.
     */
    private static double[] bezier(double[] a, double[] c, double[] b, double u, double[] lo, double[] hi) {
        double w0 = (1 - u) * (1 - u);
        double w1 = 2 * u * (1 - u);
        double w2 = u * u;
        double[] out = new double[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            out[i] = w0 * a[i] + w1 * c[i] + w2 * b[i];
            if (i >= 3) {
                out[i] = Math.min(hi[i], Math.max(lo[i], out[i]));
            }
        }
        return out;
    }

    /**
     * Append a cone, unless it is the piece a zero-curvature corner leaves behind — the same point at the same
     * radius twice, which contributes nothing the neighbouring pieces do not already cover.
     */
    private static void add(List<Piece> pieces, End a, End b) {
        if (a.equals(b)) {
            return;
        }
        pieces.add(new Piece(a, b));
    }
}
