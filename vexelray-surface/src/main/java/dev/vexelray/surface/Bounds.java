package dev.vexelray.surface;

import java.util.List;
import java.util.Optional;

/**
 * An axis-aligned box a surface is known to fit inside — computed on the tree as data, in Java, before anything
 * is lowered.
 *
 * <p>It exists because a renderer has to point a camera somewhere. A surface derived from an expression is
 * mapped into a fixed world box on the way in, so a camera aimed at that box always finds it. A surface handed
 * in already built has been through no such pass: it sits wherever its own coordinates put it, and a stroke
 * authored a hundred units from the origin marches perfectly and appears nowhere. Asking the geometry where it
 * is, is what lets a host frame it instead of requiring callers to hand-place everything inside a volume nobody
 * told them about.
 *
 * <h2>Conservative, and only where it can be</h2>
 *
 * <p>Every box here <b>contains</b> its surface; none claims to be tight. Where tightness is expensive the
 * answer is simply larger — a rotation takes the box of the rotated corners rather than the rotated box, a
 * polar repeat takes the whole swept cylinder. Being loose costs a little framing margin. Being wrong would put
 * geometry outside the shot, which is the failure this is here to prevent.
 *
 * <p>Some surfaces have no box at all, and say so with an empty {@link Optional} rather than a made-up number:
 *
 * <ul>
 *   <li>a {@link Surface.Plane} is a half-space and genuinely unbounded;</li>
 *   <li>a {@link Surface.Repeat} with no cell range tiles to the horizon;</li>
 *   <li>an {@link Surface.Implicit} holds an arbitrary expression, and bounding one means interval arithmetic
 *       over the IR — the pass docs/surface-compiler.md §2.2 wants for the vexel work anyway, and not something
 *       to guess at here.</li>
 * </ul>
 *
 * <p>An empty answer is a real answer: a host that gets one should say it cannot frame the surface rather than
 * inventing a box and cutting the geometry in half.
 *
 * <h2>Two questions, and they are not the same question</h2>
 *
 * <p>{@link #of} asks <em>where is everything</em> — a containment guarantee, safe to cull against.
 * {@link #subject} asks <em>what am I looking at</em> — a framing hint, and not a containment claim at all.
 *
 * <p>They differ on exactly one rule, at {@code Union}, and the difference is not academic. Add a ground plane
 * to a scene and {@code of} correctly reports no box, because a half-space reaches forever. A host that framed
 * on that answer framed on nothing, left the stroke where it was authored, and rendered an empty frame — which
 * looked for all the world like a broken renderer. Scenery should not decide the shot, so {@code subject} skips
 * the children that have no size and frames on the ones that do.
 */
public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public Bounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("a box cannot have a negative extent");
        }
    }

    /**
     * The box <b>containing</b> {@code surface}, or empty if it has none.
     *
     * <p>A guarantee: nothing in the surface lies outside this box. Safe to cull against, skip empty space
     * with, or hand to anything that must not miss geometry. A union containing a half-space has no box at all,
     * because it genuinely reaches forever — see {@link #subject} for the other question.
     */
    public static Optional<Bounds> of(Surface surface) {
        return Optional.ofNullable(box(surface, false));
    }

    /**
     * The box around the part of {@code surface} that <b>has a size</b> — what a camera should frame.
     *
     * <p>A different question from {@link #of}, and the distinction is the whole reason this method exists. Add
     * a ground plane to a scene and {@code of} correctly reports no box: a half-space reaches forever, so no
     * box contains the union. Frame on that answer and you frame on nothing, and the stroke you actually wanted
     * to look at stays wherever it was authored — off screen, indistinguishable from a renderer that does not
     * work. An infinite backdrop is scenery. It should never decide the shot.
     *
     * <p>So this ignores children that have no box of their own rather than being defeated by them, and returns
     * the hull of the rest. It is empty only when <em>nothing</em> in the surface has a size — a bare plane, an
     * implicit, an endless lattice — where there is genuinely nothing to frame on.
     *
     * <p><b>This is not a containment claim.</b> Geometry may well lie outside it; the ground plane certainly
     * does. Never cull against it, never use it to skip space. It answers "what am I looking at", not "where is
     * everything".
     */
    public static Optional<Bounds> subject(Surface surface) {
        return Optional.ofNullable(box(surface, true));
    }

    /** The centre, {@code {x, y, z}}. */
    public double[] centre() {
        return new double[]{(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2};
    }

    /** Half the size on each axis, {@code {x, y, z}} — never negative, and zero for a flat or degenerate box. */
    public double[] halfExtent() {
        return new double[]{(maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2};
    }

    /** The longest half-extent, which is what a single uniform scale has to work from. */
    public double largestHalfExtent() {
        double[] h = halfExtent();
        return Math.max(h[0], Math.max(h[1], h[2]));
    }

    /** Whether a point is inside, which is what a containment test asks. */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** This box grown by {@code margin} on every side — how a rounding or a shell reports its reach. */
    public Bounds expanded(double margin) {
        return new Bounds(minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin);
    }

    /** The smallest box containing both. */
    public Bounds union(Bounds other) {
        return new Bounds(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    /** The overlap of two boxes, or empty if they do not meet. */
    public Optional<Bounds> intersect(Bounds other) {
        double loX = Math.max(minX, other.minX);
        double loY = Math.max(minY, other.minY);
        double loZ = Math.max(minZ, other.minZ);
        double hiX = Math.min(maxX, other.maxX);
        double hiY = Math.min(maxY, other.maxY);
        double hiZ = Math.min(maxZ, other.maxZ);
        return loX > hiX || loY > hiY || loZ > hiZ
                ? Optional.empty()
                : Optional.of(new Bounds(loX, loY, loZ, hiX, hiY, hiZ));
    }

    /** The box around a set of points. */
    private static Bounds around(double[][] points) {
        double loX = Double.POSITIVE_INFINITY;
        double loY = Double.POSITIVE_INFINITY;
        double loZ = Double.POSITIVE_INFINITY;
        double hiX = Double.NEGATIVE_INFINITY;
        double hiY = Double.NEGATIVE_INFINITY;
        double hiZ = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            loX = Math.min(loX, p[0]);
            loY = Math.min(loY, p[1]);
            loZ = Math.min(loZ, p[2]);
            hiX = Math.max(hiX, p[0]);
            hiY = Math.max(hiY, p[1]);
            hiZ = Math.max(hiZ, p[2]);
        }
        return new Bounds(loX, loY, loZ, hiX, hiY, hiZ);
    }

    /** A sphere's box. */
    private static Bounds sphere(double cx, double cy, double cz, double r) {
        return new Bounds(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
    }

    /** The eight corners of a box, for the transforms that have to move them individually. */
    private double[][] corners() {
        return new double[][]{
                {minX, minY, minZ}, {maxX, minY, minZ}, {minX, maxY, minZ}, {maxX, maxY, minZ},
                {minX, minY, maxZ}, {maxX, minY, maxZ}, {minX, maxY, maxZ}, {maxX, maxY, maxZ}};
    }

    /** The largest distance from the {@code Y} axis any corner reaches — the radius a turn about {@code Y} sweeps. */
    private double radiusAboutY() {
        double r = 0;
        for (double[] c : corners()) {
            r = Math.max(r, Math.hypot(c[0], c[2]));
        }
        return r;
    }

    /** The same about {@code Z}, which is the axis a bend turns about. */
    private double radiusAboutZ() {
        double r = 0;
        for (double[] c : corners()) {
            r = Math.max(r, Math.hypot(c[0], c[1]));
        }
        return r;
    }

    private static Bounds box(Surface surface, boolean framing) {
        return switch (surface) {
            case Surface.Sphere s -> sphere(s.cx(), s.cy(), s.cz(), s.radius());

            case Surface.Box b -> new Bounds(b.cx() - b.hx(), b.cy() - b.hy(), b.cz() - b.hz(),
                    b.cx() + b.hx(), b.cy() + b.hy(), b.cz() + b.hz());

            // A half-space reaches forever in every direction but one, so there is no box to give.
            case Surface.Plane ignored -> null;

            case Surface.Capsule c -> sphere(c.ax(), c.ay(), c.az(), c.radius())
                    .union(sphere(c.bx(), c.by(), c.bz(), c.radius()));

            case Surface.Torus t -> new Bounds(
                    t.cx() - t.major() - t.minor(), t.cy() - t.minor(), t.cz() - t.major() - t.minor(),
                    t.cx() + t.major() + t.minor(), t.cy() + t.minor(), t.cz() + t.major() + t.minor());

            // The hull of the end spheres of every cone the stroke lowers to — the same list the compiler
            // emits, so the box is around what is actually drawn rather than around the control points. It
            // differs: a rounded corner bulges past its vertex, which is the whole point of the construction.
            case Surface.Stroke s -> {
                Bounds all = null;
                for (Spine.Piece piece : Spine.of(s)) {
                    Bounds ends = sphere(piece.a().x(), piece.a().y(), piece.a().z(), piece.a().radius())
                            .union(sphere(piece.b().x(), piece.b().y(), piece.b().z(), piece.b().radius()));
                    all = all == null ? ends : all.union(ends);
                }
                yield all;
            }

            case Surface.Translate t -> map(t.of(), framing, b -> new Bounds(
                    b.minX + t.dx(), b.minY + t.dy(), b.minZ + t.dz(),
                    b.maxX + t.dx(), b.maxY + t.dy(), b.maxZ + t.dz()));

            case Surface.Scale s -> map(s.of(), framing, b -> new Bounds(
                    b.minX * s.factor(), b.minY * s.factor(), b.minZ * s.factor(),
                    b.maxX * s.factor(), b.maxY * s.factor(), b.maxZ * s.factor()));

            // The box of the rotated corners, not the rotated box — the second is not axis-aligned and the
            // first is what contains it.
            case Surface.Rotate r -> map(r.of(), framing, b -> {
                double[] m = rotation(r.ax(), r.ay(), r.az(), r.angle());
                double[][] turned = new double[8][3];
                double[][] corners = b.corners();
                for (int i = 0; i < 8; i++) {
                    for (int row = 0; row < 3; row++) {
                        turned[i][row] = m[row * 3] * corners[i][0]
                                + m[row * 3 + 1] * corners[i][1]
                                + m[row * 3 + 2] * corners[i][2];
                    }
                }
                return around(turned);
            });

            // A folded axis reflects whatever survives on the positive side, so the result is symmetric about
            // zero and reaches as far as the child's furthest point on that axis.
            case Surface.Mirror m -> map(m.of(), framing, b -> {
                double x = Math.max(Math.abs(b.minX), Math.abs(b.maxX));
                double y = Math.max(Math.abs(b.minY), Math.abs(b.maxY));
                double z = Math.max(Math.abs(b.minZ), Math.abs(b.maxZ));
                return new Bounds(
                        m.x() ? -x : b.minX, m.y() ? -y : b.minY, m.z() ? -z : b.minZ,
                        m.x() ? x : b.maxX, m.y() ? y : b.maxY, m.z() ? z : b.maxZ);
            });

            case Surface.Repeat r -> repeat(r, framing);

            // Every sector at once: the child swept all the way round the axis.
            case Surface.PolarRepeat r -> map(r.of(), framing, b -> {
                double radius = b.radiusAboutY();
                return new Bounds(-radius, b.minY, -radius, radius, b.maxY, radius);
            });

            // A twist turns about Y by an amount that depends on height, so the image is somewhere in the
            // cylinder the child sweeps — the same envelope a polar repeat gives, for the same reason.
            case Surface.Twist t -> map(t.of(), framing, b -> {
                double radius = b.radiusAboutY();
                return new Bounds(-radius, b.minY, -radius, radius, b.maxY, radius);
            });

            // And a bend turns about Z, so the same argument one axis over.
            case Surface.Bend bend -> map(bend.of(), framing, b -> {
                double radius = b.radiusAboutZ();
                return new Bounds(-radius, -radius, b.minZ, radius, radius, b.maxZ);
            });

            case Surface.Union u -> hull(u.of(), framing);

            // Carving only removes material, so whatever is left is inside what it was cut from.
            case Surface.Difference d -> box(d.from(), framing);

            case Surface.SmoothDifference d -> box(d.from(), framing);

            // An overlap is inside every child, so any one bounded child bounds it — and the smallest box that
            // several of them agree on is smaller still. Children with no box of their own simply say nothing.
            case Surface.Intersection i -> overlap(i.of(), framing);

            case Surface.SmoothIntersection i -> overlap(i.of(), framing);

            // A soft union bulges outward past the hard one by at most log(n)/k, where the blend is strongest.
            // Adding that is what keeps the box containing rather than merely nearly containing.
            case Surface.SmoothUnion s -> {
                Bounds h = hull(s.of(), framing);
                yield h == null ? null : h.expanded(Math.log(s.of().size()) / s.sharpness());
            }

            // A shell reaches a thickness outside the surface as well as inside it.
            case Surface.Shell s -> map(s.of(), framing, b -> b.expanded(s.thickness()));

            case Surface.Round r -> map(r.of(), framing, b -> b.expanded(r.radius()));

            // Bounding an arbitrary implicit is interval arithmetic over the IR, which is its own pass.
            case Surface.Implicit ignored -> null;
        };
    }

    /**
     * The box around a union's children — <b>the one rule where the two questions differ</b>.
     *
     * <p>Containment: a union is only as bounded as its least bounded part, so one child without a box means
     * the union has none. Anything else would be a box that does not contain its surface.
     *
     * <p>Framing: a child without a box contributes nothing and is skipped. That is what keeps a ground plane
     * from deciding the shot — and it is precisely the case that made a stroke unioned with one render as an
     * empty frame, because the plane defeated the framing pass and the stroke was never moved into view.
     */
    private static Bounds hull(List<Surface> children, boolean framing) {
        Bounds all = null;
        for (Surface child : children) {
            Bounds one = box(child, framing);
            if (one == null) {
                if (framing) {
                    continue;
                }
                return null;
            }
            all = all == null ? one : all.union(one);
        }
        return all;
    }

    /** The tightest box every bounded child agrees on, or null if none of them is bounded. */
    private static Bounds overlap(List<Surface> children, boolean framing) {
        Bounds all = null;
        for (Surface child : children) {
            Bounds one = box(child, framing);
            if (one == null) {
                continue;                       // an unbounded child constrains nothing, and that is fine here
            }
            if (all == null) {
                all = one;
            } else {
                // Disjoint children mean the intersection is empty geometry. A degenerate box at the gap is the
                // honest report: there is nothing to frame, and nothing to draw.
                all = all.intersect(one).orElse(new Bounds(all.minX, all.minY, all.minZ,
                        all.minX, all.minY, all.minZ));
            }
        }
        return all;
    }

    private static Bounds repeat(Surface.Repeat r, boolean framing) {
        Bounds child = box(r.of(), framing);
        if (child == null) {
            return null;
        }
        Surface.Repeat.Axis[] axes = {r.x(), r.y(), r.z()};
        double[] lo = {child.minX, child.minY, child.minZ};
        double[] hi = {child.maxX, child.maxY, child.maxZ};
        for (int i = 0; i < 3; i++) {
            if (!axes[i].repeats()) {
                continue;
            }
            if (!axes[i].bounded()) {
                return null;                    // tiles to the horizon; there is no box
            }
            lo[i] += axes[i].from() * axes[i].period();
            hi[i] += axes[i].to() * axes[i].period();
        }
        return new Bounds(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** Rodrigues' formula, row-major — the object's own rotation, which is what moves its corners. */
    private static double[] rotation(double kx, double ky, double kz, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        double t = 1 - c;
        return new double[]{
                t * kx * kx + c, t * kx * ky - s * kz, t * kx * kz + s * ky,
                t * kx * ky + s * kz, t * ky * ky + c, t * ky * kz - s * kx,
                t * kx * kz - s * ky, t * ky * kz + s * kx, t * kz * kz + c};
    }

    /** Apply {@code f} to a child's box, propagating "no box" rather than inventing one. */
    private static Bounds map(Surface child, boolean framing, java.util.function.UnaryOperator<Bounds> f) {
        Bounds b = box(child, framing);
        return b == null ? null : f.apply(b);
    }
}
