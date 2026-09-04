package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

import java.util.List;

/**
 * A surface, as <em>data</em>: a sealed tree of records describing shape, not a Java class computing it. This is
 * the input to {@link SurfaceCompiler}, which lowers it to {@code core} IR.
 *
 * <p>Being records buys three things the design depends on (docs/surface-compiler.md §3):
 * <ul>
 *   <li><b>structural equality is free</b>, so a whole surface serves as its own shader-cache fingerprint and two
 *       identical scenes collapse onto one compiled pipeline without any hand-written key code;</li>
 *   <li><b>it serialises</b> — a surface can arrive from a file, a socket, or an editor buffer, which is the
 *       entire point of making it data rather than a compiled {@code ShapeField};</li>
 *   <li><b>each node knows its own Lipschitz story</b>. Every case below except {@link Implicit} is a proper
 *       distance field (or a combinator that preserves the property), so it lowers to exactly the IR a
 *       hand-written scene would produce. Only {@code Implicit} pays for the generality.</li>
 * </ul>
 *
 * <p>Coordinates are world space and every distance is Euclidean, so the tree carries no units of its own.
 */
public sealed interface Surface {

    // --- primitives: exact, 1-Lipschitz signed distance fields ---

    /** Sphere of radius {@code radius} centred at {@code (cx, cy, cz)}. */
    record Sphere(double cx, double cy, double cz, double radius) implements Surface {
        public Sphere {
            requirePositive(radius, "radius");
        }
    }

    /** Axis-aligned box centred at {@code (cx, cy, cz)} with half-extents {@code (hx, hy, hz)}. */
    record Box(double cx, double cy, double cz, double hx, double hy, double hz) implements Surface {
        public Box {
            requirePositive(hx, "hx");
            requirePositive(hy, "hy");
            requirePositive(hz, "hz");
        }
    }

    /**
     * Half-space {@code dot(p, n) + offset <= 0}. The normal is normalised on construction, because an
     * un-normalised one silently breaks the 1-Lipschitz promise this node makes to the compiler.
     */
    record Plane(double nx, double ny, double nz, double offset) implements Surface {
        public Plane {
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) {
                throw new IllegalArgumentException("plane normal must be non-degenerate");
            }
            nx /= len;
            ny /= len;
            nz /= len;
        }

        /** The ground plane {@code y = 0}, solid below. */
        public static Plane ground() {
            return new Plane(0, 1, 0, 0);
        }
    }

    /** Round-ended segment from {@code a} to {@code b} with radius {@code radius}. */
    record Capsule(double ax, double ay, double az, double bx, double by, double bz,
                   double radius) implements Surface {
        public Capsule {
            requirePositive(radius, "radius");
        }
    }

    /** Torus in the XZ plane centred at {@code (cx, cy, cz)}: ring radius {@code major}, tube {@code minor}. */
    record Torus(double cx, double cy, double cz, double major, double minor) implements Surface {
        public Torus {
            requirePositive(major, "major");
            requirePositive(minor, "minor");
        }
    }

    // --- domain transforms ---

    /** {@code of}, moved by {@code (dx, dy, dz)}. Distance-preserving. */
    record Translate(double dx, double dy, double dz, Surface of) implements Surface {
        public Translate {
            requireNonNull(of);
        }
    }

    /** {@code of}, uniformly scaled about the origin. Distances scale with it, so the field stays exact. */
    record Scale(double factor, Surface of) implements Surface {
        public Scale {
            requirePositive(factor, "factor");
            requireNonNull(of);
        }
    }

    /**
     * {@code of}, rotated about the axis {@code (ax, ay, az)} through the origin by {@code angle} radians,
     * counter-clockwise looking down the axis. Distance-preserving, like every isometry.
     *
     * <p>The axis is normalised on construction for the same reason {@link Plane}'s normal is: an un-normalised
     * one is not a rotation, and would quietly scale the field along with turning it. Rotation about anything
     * other than the origin is this composed with {@link Translate}, which is also how {@link Scale} handles it.
     *
     * <p>Costs three dot products at the leaves, not a matrix: the compiler evaluates Rodrigues' formula in Java
     * and emits the nine resulting numbers as constants.
     */
    record Rotate(double ax, double ay, double az, double angle, Surface of) implements Surface {
        public Rotate {
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len < 1e-12) {
                throw new IllegalArgumentException("rotation axis must be non-degenerate");
            }
            if (!Double.isFinite(angle)) {
                throw new IllegalArgumentException("rotation angle must be finite, got " + angle);
            }
            ax /= len;
            ay /= len;
            az /= len;
            requireNonNull(of);
        }

        /** Rotation about {@code +Y} — the turn that matters most in a world with a ground plane. */
        public static Rotate aboutY(double angle, Surface of) {
            return new Rotate(0, 1, 0, angle, of);
        }
    }

    /**
     * {@code of}, reflected through the coordinate planes named by {@code x}/{@code y}/{@code z} — the cheapest
     * symmetry there is, and the one that halves (or eighths) the work of authoring a symmetric object.
     *
     * <p>Folding the domain with {@code abs} keeps the field 1-Lipschitz, so unlike {@link Repeat} this needs no
     * neighbour sampling and costs one {@code abs} per folded axis. What it gives up is control: only the part of
     * {@code of} on the positive side of a folded axis survives, and the negative side becomes its mirror. That
     * is a fact about which shape you get, not a hole in the field.
     *
     * <p>Mirroring about a plane elsewhere is this wrapped in {@link Translate}.
     */
    record Mirror(boolean x, boolean y, boolean z, Surface of) implements Surface {
        public Mirror {
            requireNonNull(of);
        }
    }

    /**
     * {@code of}, tiled through space on one, two, or three axes — the operator that turns a single column into a
     * colonnade, or one brick into a wall, for close to the cost of the column.
     *
     * <p>Each {@link Axis} either does not repeat ({@link Axis#NONE}) or names a period and an inclusive range of
     * cell indices, so a finite row and an endless lattice are the same node.
     *
     * <p><b>Why this is not simply a modulo.</b> Folding {@code p} into its own cell is a piecewise isometry, so
     * it is 1-Lipschitz within a cell — but it is <em>discontinuous</em> at the cell walls, and a discontinuous
     * field can report more distance than there really is, which is precisely what punches holes through a
     * sphere-traced surface. Any instance reaching over its own cell wall would do it. So the compiler folds into
     * the nearest cell <em>and</em> into the nearer neighbour along every repeated axis, and takes the
     * {@code min}: correct whether or not the child fits, at a cost of {@code 2^n} copies of the child for
     * {@code n} repeated axes. Repeats nest multiplicatively; {@link SurfaceLimits#maxCompiledNodes} is what
     * stops that running away.
     */
    record Repeat(Axis x, Axis y, Axis z, Surface of) implements Surface {

        /**
         * How one axis repeats: cells {@code period} apart, indices {@code from}..{@code to} inclusive, with cell
         * {@code 0} sitting on the original.
         *
         * @param period spacing, or {@code 0} for an axis that does not repeat
         */
        public record Axis(double period, long from, long to) {

            /** An axis that does not repeat. */
            public static final Axis NONE = new Axis(0, 0, 0);

            public Axis {
                if (!Double.isFinite(period) || period < 0) {
                    throw new IllegalArgumentException("period must be finite and non-negative, got " + period);
                }
                if (period == 0 && (from != 0 || to != 0)) {
                    throw new IllegalArgumentException("an axis with no period cannot have a cell range");
                }
                if (from > to) {
                    throw new IllegalArgumentException("cell range is empty: " + from + ".." + to);
                }
            }

            /** Endlessly, {@code period} apart. */
            public static Axis every(double period) {
                requirePositive(period, "period");
                return new Axis(period, Long.MIN_VALUE, Long.MAX_VALUE);
            }

            /** Cells {@code from}..{@code to} inclusive, {@code period} apart. */
            public static Axis range(double period, long from, long to) {
                requirePositive(period, "period");
                return new Axis(period, from, to);
            }

            /** {@code count} cells, starting on the original and running in the {@code +} direction. */
            public static Axis count(double period, long count) {
                if (count < 1) {
                    throw new IllegalArgumentException("count must be at least 1, got " + count);
                }
                return range(period, 0, count - 1);
            }

            /** Whether this axis repeats at all. */
            public boolean repeats() {
                return period > 0;
            }

            /** Whether the cell range is finite, and so needs clamping. */
            public boolean bounded() {
                return from != Long.MIN_VALUE || to != Long.MAX_VALUE;
            }
        }

        public Repeat {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "every axis must be given; use Axis.NONE for one that does not repeat");
            }
            requireNonNull(of);
        }

        /** Repeat on {@code X} alone. */
        public static Repeat alongX(Axis axis, Surface of) {
            return new Repeat(axis, Axis.NONE, Axis.NONE, of);
        }

        /** Repeat on {@code Y} alone. */
        public static Repeat alongY(Axis axis, Surface of) {
            return new Repeat(Axis.NONE, axis, Axis.NONE, of);
        }

        /** Repeat on {@code Z} alone. */
        public static Repeat alongZ(Axis axis, Surface of) {
            return new Repeat(Axis.NONE, Axis.NONE, axis, of);
        }

        /** The endless lattice on the ground plane: {@code X} and {@code Z} at one period, {@code Y} untouched. */
        public static Repeat grid(double period, Surface of) {
            return new Repeat(Axis.every(period), Axis.NONE, Axis.every(period), of);
        }
    }

    /**
     * {@code of}, repeated {@code count} times around the {@code Y} axis — the rotational counterpart of
     * {@link Repeat}, and what turns one blade into a fan or one column into a rotunda.
     *
     * <p>Folding the angle into a sector is an isometry within the sector, so the bound survives; and it is
     * discontinuous at the sector walls for exactly the reason {@link Repeat} is, so the compiler samples the
     * nearer neighbouring sector too and takes the {@code min}. Two copies of the child, always — the sectors
     * close into a ring, so there is no bounded case to clamp.
     *
     * <p>Around another axis, wrap this in {@link Rotate}. Note that a child straddling a sector wall is cut by
     * it rather than repeated whole: the sector is where the geometry has to live.
     */
    record PolarRepeat(int count, Surface of) implements Surface {
        public PolarRepeat {
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1, got " + count);
            }
            requireNonNull(of);
        }
    }

    /**
     * {@code of}, twisted about the {@code Y} axis: the domain turns by {@code rate} radians per unit of height.
     *
     * <p>The first node here that is <b>not</b> an isometry, and it says so in its own signature. A twist
     * stretches the domain in proportion to how far a point is from the axis — the Jacobian's largest singular
     * value is {@code (a + sqrt(a^2 + 4)) / 2} at {@code a = |rate| * r}, which is {@code 1 + a/2} for a gentle
     * twist and grows without bound for a fierce one — so distances read too long by that factor, and a field
     * that reads too long is one that marches straight through its own surface. The compiler divides the result
     * by the factor at {@code radius}, which restores the 1-Lipschitz promise.
     *
     * <p>That is why {@code radius} is asked for rather than inferred. It is the same trade
     * {@link Implicit#bounded} makes: a declared global bound is safe everywhere and costs one divide, where a
     * bound derived pointwise is only locally correct and several times the size. The contract is yours to keep —
     * <b>within {@code radius} of the axis the field is conservative; outside it, it can overshoot.</b> Keep the
     * geometry inside, or intersect with something that does.
     *
     * <p>The divide is also what a twist costs in march steps: at {@code rate*radius = 1} every step is under
     * two-thirds of the distance it could have been, and the penalty is linear in {@code rate*radius} after that.
     * Twist tightly and locally, not across a world.
     */
    record Twist(double rate, double radius, Surface of) implements Surface {
        public Twist {
            if (!Double.isFinite(rate)) {
                throw new IllegalArgumentException("twist rate must be finite, got " + rate);
            }
            requirePositive(radius, "radius");
            requireNonNull(of);
        }
    }

    /**
     * {@code of}, bent in the {@code XY} plane: the domain turns about {@code Z} by {@code rate} radians per unit
     * of {@code X}, so a straight beam along {@code X} curls into an arc of radius {@code 1/rate}.
     *
     * <p>The same argument as {@link Twist}, one axis over, and it comes out slightly worse: {@code 1 + |rate|*d}
     * at distance {@code d} from the {@code Z} axis, against the twist's {@code (a + sqrt(a^2 + 4))/2}. A twist
     * slides along the axis it turns about, so its stretch acts across the direction of travel; a bend turns and
     * travels in the same plane, so on the inside of the curve the two add outright. The compiler divides by the
     * value at {@code extent} to keep the field marchable: within {@code extent} of the {@code Z} axis the field
     * is conservative, and outside it, it can overshoot.
     */
    record Bend(double rate, double extent, Surface of) implements Surface {
        public Bend {
            if (!Double.isFinite(rate)) {
                throw new IllegalArgumentException("bend rate must be finite, got " + rate);
            }
            requirePositive(extent, "extent");
            requireNonNull(of);
        }
    }

    // --- combinators ---

    /** Everything in {@code of} — a pointwise {@code min}. Conservative, associative, order-free. */
    record Union(List<Surface> of) implements Surface {
        public Union {
            of = requireNonEmpty(of);
        }
    }

    /** The overlap of everything in {@code of} — a pointwise {@code max}. Conservative but not exact near edges. */
    record Intersection(List<Surface> of) implements Surface {
        public Intersection {
            of = requireNonEmpty(of);
        }
    }

    /** {@code from} with {@code remove} carved out of it — {@code max(from, -remove)}. */
    record Difference(Surface from, Surface remove) implements Surface {
        public Difference {
            requireNonNull(from);
            requireNonNull(remove);
        }
    }

    /**
     * A union whose interfaces blend into fillets instead of creases, as a <b>numerically stable exponential
     * soft-min over all children at once</b>: {@code m - log(sum exp(-k*(d - m)))/k}.
     *
     * <p>N-ary deliberately. A left-fold of pairwise {@code smin} is <em>not</em> associative (D13), so
     * grouping — an artifact of how the tree happened to be built — would leak into the rendered surface
     * wherever three or more children meet. The soft-min above is a symmetric function of every local distance
     * simultaneously, so there is no grouping to be ambiguous about. It is also {@code <= min} and 1-Lipschitz,
     * which is what keeps the march conservative.
     *
     * <p>Trade-off, stated plainly: unlike a polynomial {@code smin}, this blend has <em>global</em> support —
     * every child influences every point, however distant. Fine for a handful of children; at world scale it is
     * the octree's job to keep the local set small (docs/vexel-world.md §2), not this node's.
     *
     * @param sharpness larger is crisper; as it grows the blend approaches a hard {@link Union}
     */
    record SmoothUnion(double sharpness, List<Surface> of) implements Surface {
        public SmoothUnion {
            requirePositive(sharpness, "sharpness");
            of = requireNonEmpty(of);
        }
    }

    /**
     * An intersection whose edges are filleted instead of creased — the N-ary <b>soft-max</b>,
     * {@code m + log(sum exp(k*(d - m)))/k}, the exact mirror of {@link SmoothUnion}.
     *
     * <p>Where a hard {@link Intersection} leaves a sharp rim wherever two solids' boundaries cross, this rounds
     * it, taking a little material off. Note the direction: soft-max is {@code >= max}, so it reports
     * <em>more</em> distance than the hard intersection — which sounds like the overshoot that puts holes in a
     * render, and is not. Conservatism does not come from being under {@code max}; it comes from the field being
     * 1-Lipschitz, and a soft-max of 1-Lipschitz fields is one too (its gradient is a convex combination of
     * theirs, so it cannot be longer than the longest). A 1-Lipschitz field never reports more than the true
     * distance to its own zero set, which is exactly the surface being drawn.
     *
     * @param sharpness larger is crisper; as it grows the fillet approaches a hard {@link Intersection}
     */
    record SmoothIntersection(double sharpness, List<Surface> of) implements Surface {
        public SmoothIntersection {
            requirePositive(sharpness, "sharpness");
            of = requireNonEmpty(of);
        }
    }

    /**
     * {@code from} with {@code remove} carved out, the cut filleted where it meets the surface — the difference
     * that usually makes CSG read as moulded rather than sliced.
     *
     * <p>Exactly {@link SmoothIntersection} of {@code from} with the inverse of {@code remove}, since a hard
     * {@link Difference} is already {@code max(from, -remove)} and negating a 1-Lipschitz field leaves it
     * 1-Lipschitz. Binary for the same reason {@code Difference} is: to subtract several things, remove their
     * {@link Union} rather than nesting differences.
     *
     * @param sharpness larger is crisper; as it grows the fillet approaches a hard {@link Difference}
     */
    record SmoothDifference(double sharpness, Surface from, Surface remove) implements Surface {
        public SmoothDifference {
            requirePositive(sharpness, "sharpness");
            requireNonNull(from);
            requireNonNull(remove);
        }
    }

    /** The hollow shell of {@code of}, {@code thickness} thick — {@code |d| - thickness}. */
    record Shell(double thickness, Surface of) implements Surface {
        public Shell {
            requirePositive(thickness, "thickness");
            requireNonNull(of);
        }
    }

    /** {@code of}, inflated by {@code radius} — rounds its edges by the same amount. */
    record Round(double radius, Surface of) implements Surface {
        public Round {
            requirePositive(radius, "radius");
            requireNonNull(of);
        }
    }

    /**
     * A thick line through space: a run of vertices, each with its own radius, joined either by creases or by
     * curves — the node for a pipe, a cable, a stroke of a 3D pen, a swept trail, a wireframe edge with weight.
     *
     * <p>It lowers to a {@link Union} of tapered round cones, each the exact convex hull of the two spheres at
     * its ends. Every one of those is a true signed-distance field, and {@code min} preserves that, so a stroke
     * costs the march exactly what an equivalent hand-written union of capsules would: no normalisation, no
     * stretch divide, nothing charged for the generality.
     *
     * <h2>The guarantee</h2>
     *
     * <p><b>Every vertex you name lies on the centre line of the rendered shape, at every curvature.</b> That is
     * the whole reason this node is shaped the way it is, and it is not what rounded polylines usually do — the
     * standard corner fillet runs a quadratic Bézier between two handles with the vertex as the control point,
     * and such a curve <em>misses</em> the vertex, by more the harder the stroke turns. Here the control point is
     * solved for instead, so the curve passes through the vertex at its own midpoint; and the corner is sampled
     * an even number of times so that the vertex is a joint of the emitted chain rather than merely near one.
     * See {@link Spine} for the derivation.
     *
     * <p>So the vertices are positions, not hints: place one and the surface is centred there, whether the joint
     * is a crease or a full arc. {@link Vertex#curvature()} moves continuously between those two — {@code 0} is a
     * sharp angle, {@code 1} is the roundest arc that still cannot reach past the midpoints of its neighbouring
     * segments, and everything between is the same curve family with shorter handles.
     *
     * <p>What is <em>not</em> promised: that the shape's volumetric centroid lies inside it. No polyline node can
     * promise that — a stroke bent back on itself has its centroid in the gap, the way a horseshoe does.
     *
     * @param through           the vertices, in order; at least one
     * @param segmentsPerCorner sub-cones emitted per rounded corner. Even, so that a sample lands on the vertex.
     *                          Higher is smoother and linearly more IR: a stroke costs about
     *                          {@code vertices * (1 + segmentsPerCorner)} cones, and each cone is a few dozen
     *                          nodes. Corners at zero curvature emit none of them.
     */
    record Stroke(List<Vertex> through, int segmentsPerCorner) implements Surface {

        /** The default corner sampling: smooth enough to read as a curve, cheap enough to spend on every joint. */
        public static final int DEFAULT_SEGMENTS_PER_CORNER = 8;

        /**
         * One point on the stroke, its thickness there, and how the stroke turns through it.
         *
         * @param radius    half-thickness at this vertex; the stroke tapers linearly between neighbours
         * @param curvature {@code 0} for a sharp angle, {@code 1} for the fullest arc, anything between for a
         *                  partial one. Ignored on the first and last vertex, which have nothing to turn through.
         */
        public record Vertex(double x, double y, double z, double radius, double curvature, Rgb colour) {

            /** A vertex of the given shape, taking whatever colour the scene supplies. */
            public Vertex(double x, double y, double z, double radius, double curvature) {
                this(x, y, z, radius, curvature, null);
            }

            public Vertex {
                requirePositive(radius, "radius");
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    throw new IllegalArgumentException("vertex position must be finite");
                }
                if (!(curvature >= 0) || !(curvature <= 1)) {
                    throw new IllegalArgumentException("curvature must be in [0, 1], got " + curvature);
                }
            }

            /** A vertex the stroke turns sharply through. */
            public static Vertex sharp(double x, double y, double z, double radius) {
                return new Vertex(x, y, z, radius, 0);
            }

            /** A vertex the stroke curves through as roundly as it can. */
            public static Vertex round(double x, double y, double z, double radius) {
                return new Vertex(x, y, z, radius, 1);
            }

            /** This vertex, coloured. The stroke's colour gradients between neighbouring vertices. */
            public Vertex painted(Rgb colour) {
                if (colour == null) {
                    throw new IllegalArgumentException("colour must not be null; drop the call instead");
                }
                return new Vertex(x, y, z, radius, curvature, colour);
            }
        }

        public Stroke {
            if (through == null || through.isEmpty()) {
                throw new IllegalArgumentException("a stroke needs at least one vertex");
            }
            through = List.copyOf(through);     // defensive + keeps structural equality honest
            for (Vertex v : through) {
                if (v == null) {
                    throw new IllegalArgumentException("stroke vertex must not be null");
                }
            }
            // All or nothing, because a colour gradient between a colour and an absence has no meaning. A stroke
            // that wants the scene's colour for part of its length is two strokes.
            long painted = through.stream().filter(v -> v.colour() != null).count();
            if (painted != 0 && painted != through.size()) {
                throw new IllegalArgumentException(
                        "either every vertex carries a colour or none does; " + painted + " of "
                                + through.size() + " do");
            }
            if (segmentsPerCorner < 2 || segmentsPerCorner % 2 != 0) {
                throw new IllegalArgumentException(
                        "segmentsPerCorner must be even and at least 2 — an odd count puts no sample on the "
                                + "vertex, which is the one thing a stroke promises; got " + segmentsPerCorner);
            }
        }

        /** A stroke at the default corner sampling. */
        public Stroke(List<Vertex> through) {
            this(through, DEFAULT_SEGMENTS_PER_CORNER);
        }

        /** A stroke of uniform radius and curvature through {@code xyz}, read three numbers at a time. */
        public static Stroke through(double radius, double curvature, double... xyz) {
            if (xyz.length == 0 || xyz.length % 3 != 0) {
                throw new IllegalArgumentException(
                        "coordinates must come in whole triples, got " + xyz.length);
            }
            List<Vertex> vertices = new java.util.ArrayList<>(xyz.length / 3);
            for (int i = 0; i < xyz.length; i += 3) {
                vertices.add(new Vertex(xyz[i], xyz[i + 1], xyz[i + 2], radius, curvature));
            }
            return new Stroke(vertices);
        }

        /** A stroke of uniform radius, curvature and colour through {@code xyz}. */
        public static Stroke through(double radius, double curvature, Rgb colour, double... xyz) {
            List<Vertex> vertices = new java.util.ArrayList<>();
            for (Vertex v : through(radius, curvature, xyz).through()) {
                vertices.add(v.painted(colour));
            }
            return new Stroke(vertices);
        }

        /**
         * Whether any vertex named a colour. False for a shape-only stroke, which then lowers to exactly the IR
         * it did before colour existed and costs the composer nothing.
         */
        public boolean hasColour() {
            for (Vertex v : through) {
                if (v.colour() != null) {
                    return true;
                }
            }
            return false;
        }

        /**
         * An upper bound on the cones this lowers to — one straight run per segment, plus a corner's worth per
         * interior vertex. What {@link SurfaceLimits} charges for the node, since the tree says one node and the
         * IR says thousands.
         */
        public int coneBound() {
            int corners = Math.max(0, through.size() - 2);
            return through.size() + corners * segmentsPerCorner;
        }
    }

    // --- the escape hatch ---

    /**
     * An arbitrary implicit surface: any scalar expression of {@link Ir#POINT}, whose zero set is the surface.
     * This is the node that makes the module worth building and the one that carries all its risk.
     *
     * <p>Such an expression is generally <b>not</b> a distance field — {@code x²+y²+z²-1} reads 8 where the true
     * distance is 2 — so marching it directly punches holes through the surface. {@link SurfaceCompiler}
     * therefore normalises it by its own symbolic gradient before letting it near a march. That is a local
     * correction, not a proof (docs/surface-compiler.md §7); interval arithmetic is what will eventually
     * guarantee it.
     */
    record Implicit(Expr f, double lipschitzBound) implements Surface {

        /**
         * An implicit whose bound is unknown, so the compiler derives one pointwise from the expression's own
         * gradient. Correct for anything, and only locally correct — see the class note above.
         */
        public Implicit(Expr f) {
            this(f, Field.UNKNOWN);
        }

        public Implicit {
            requireNonNull(f);
            if (!Ir.F32.equals(f.type())) {
                throw new IllegalArgumentException("an implicit surface must be a scalar float expression, got "
                        + f.type());
            }
            if (Double.isNaN(lipschitzBound) || lipschitzBound <= 0) {
                throw new IllegalArgumentException("lipschitz bound must be positive, got " + lipschitzBound);
            }
        }

        /**
         * An implicit with a <em>known global</em> Lipschitz bound — every {@code |grad f| <= bound} everywhere.
         *
         * <p>Strictly better than the derived form when you have it, in three ways at once. It is safe
         * <em>globally</em> rather than locally, so the field cannot overshoot anywhere. It puts no derivative
         * in the shader at all, where the pointwise form multiplies the expression six- to sixteen-fold. And it
         * is one divide.
         *
         * <p>{@code sin(x)+sin(y)+sin(z)} is the standing example: its gradient is
         * {@code (cos x, cos y, cos z)}, so the bound is exactly {@code sqrt(3)}. Derived pointwise instead, the
         * same surface overshoots by 3496x at the cell centres, where all three cosines vanish at once and the
         * epsilon floor takes over.
         */
        public static Implicit bounded(Expr f, double lipschitzBound) {
            if (!Double.isFinite(lipschitzBound)) {
                throw new IllegalArgumentException("a known bound must be finite, got " + lipschitzBound);
            }
            return new Implicit(f, lipschitzBound);
        }
    }

    /**
     * A linear-RGB colour. Linear, not sRGB: shading arithmetic is only correct in a linear space, and a colour
     * that reaches the compiler has already stopped being something a human picked in a colour wheel.
     *
     * <p>Lives here rather than downstream because a surface that carries colour has to name it, and
     * {@code vexelray-surface} sits below anything that knows what a renderer is.
     */
    record Rgb(double r, double g, double b) {
        public Rgb {
            if (!Double.isFinite(r) || !Double.isFinite(g) || !Double.isFinite(b)) {
                throw new IllegalArgumentException("colour components must be finite");
            }
        }

        /** A grey of the given intensity. */
        public static Rgb grey(double v) {
            return new Rgb(v, v, v);
        }
    }

    // --- conveniences (varargs spellings of the list-taking cases) ---

    static Surface union(Surface... of) {
        return new Union(List.of(of));
    }

    static Surface intersection(Surface... of) {
        return new Intersection(List.of(of));
    }

    static Surface smoothUnion(double sharpness, Surface... of) {
        return new SmoothUnion(sharpness, List.of(of));
    }

    static Surface smoothIntersection(double sharpness, Surface... of) {
        return new SmoothIntersection(sharpness, List.of(of));
    }

    private static void requireNonNull(Surface s) {
        if (s == null) {
            throw new IllegalArgumentException("child surface must not be null");
        }
    }

    private static void requireNonNull(Expr e) {
        if (e == null) {
            throw new IllegalArgumentException("expression must not be null");
        }
    }

    private static void requirePositive(double v, String name) {
        if (!(v > 0) || !Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite and positive, got " + v);
        }
    }

    private static List<Surface> requireNonEmpty(List<Surface> of) {
        if (of == null || of.isEmpty()) {
            throw new IllegalArgumentException("combinator needs at least one child surface");
        }
        List<Surface> copy = List.copyOf(of);   // defensive + guarantees structural equality holds
        for (Surface s : copy) {
            requireNonNull(s);
        }
        return copy;
    }
}
