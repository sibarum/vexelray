package dev.vexelray.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Strokes flattened to the round cones they are made of — the same chain {@link SurfaceCompiler} emits, handed
 * out as <em>numbers</em> instead of being compiled into a shader.
 *
 * <h2>Why this is public and {@link Spine} is not</h2>
 *
 * <p>{@code Spine} owns the geometric argument of a stroke: where a corner's control point has to be for the
 * vertex to lie on the curve, why the sub-cone count must be even, how radius and colour ride the same Bézier.
 * None of that is a caller's business and all of it is liable to be refined, so it stays package-private. What a
 * caller can reasonably depend on is the <em>result</em>: a stroke is a union of tapered round cones and nothing
 * else. This exposes exactly that, and nothing about how it was arrived at.
 *
 * <p>The reason it is wanted is a renderer that reads its geometry from a buffer rather than having it compiled
 * in. A scene lowered into a shader means new geometry is new SPIR-V and a new pipeline, which is the slowest
 * thing a ray-marching viewport does; the same cones in a storage buffer make the shader independent of what it
 * draws. That renderer needs the chain as floats, which is what {@link #flatten} produces.
 *
 * <h2>A sphere is a cone with both ends in the same place</h2>
 *
 * <p>{@code Spine} distinguishes a degenerate piece — one end sphere swallowing the other, where the round-cone
 * formula has no real value — and {@code SurfaceCompiler} emits a sphere primitive for it. This does not, and
 * deliberately: it emits the swallowing end as a cone whose two ends coincide. A shader evaluating the standard
 * formula with its axis length clamped away from zero returns exactly {@code |p - a| - r} for such a cone, so
 * the degenerate case costs a clamp rather than a second primitive and a branch. One primitive is the whole
 * point of putting the geometry in a buffer: the loop that reads it has no cases.
 */
public final class Cones {

    /** Floats per cone in {@link #flatten}'s output: {@code ax, ay, az, ar, bx, by, bz, br}. */
    public static final int FLOATS = 8;

    private Cones() {
    }

    /**
     * One tapered round cone: the convex hull of the sphere of radius {@code ar} at {@code a} and the sphere of
     * radius {@code br} at {@code b}. Coincident ends are a sphere — see the class note.
     */
    public record Cone(double ax, double ay, double az, double ar,
                       double bx, double by, double bz, double br) {
    }

    /** The cones one stroke is made of, in order along it. */
    public static List<Cone> of(Surface.Stroke stroke) {
        List<Cone> out = new ArrayList<>();
        add(stroke, out);
        return out;
    }

    /** The cones a whole list of strokes is made of, concatenated — the order between strokes is the list's. */
    public static List<Cone> of(List<Surface.Stroke> strokes) {
        List<Cone> out = new ArrayList<>();
        for (Surface.Stroke stroke : strokes) {
            add(stroke, out);
        }
        return out;
    }

    private static void add(Surface.Stroke stroke, List<Cone> out) {
        for (Spine.Piece piece : Spine.of(stroke)) {
            if (piece.degenerate()) {
                Spine.End end = piece.swallowing();
                out.add(new Cone(end.x(), end.y(), end.z(), end.radius(),
                        end.x(), end.y(), end.z(), end.radius()));
            } else {
                Spine.End a = piece.a();
                Spine.End b = piece.b();
                out.add(new Cone(a.x(), a.y(), a.z(), a.radius(), b.x(), b.y(), b.z(), b.radius()));
            }
        }
    }

    /**
     * {@code cones} as {@link #FLOATS} floats each, ready to be copied into a storage buffer.
     *
     * <p>{@code float} rather than {@code double} because that is what the shader reads, and narrowing here
     * rather than at the copy keeps the buffer's element type and this array's the same thing.
     */
    public static float[] flatten(List<Cone> cones) {
        float[] out = new float[cones.size() * FLOATS];
        int at = 0;
        for (Cone c : cones) {
            out[at++] = (float) c.ax();
            out[at++] = (float) c.ay();
            out[at++] = (float) c.az();
            out[at++] = (float) c.ar();
            out[at++] = (float) c.bx();
            out[at++] = (float) c.by();
            out[at++] = (float) c.bz();
            out[at++] = (float) c.br();
        }
        return out;
    }
}
