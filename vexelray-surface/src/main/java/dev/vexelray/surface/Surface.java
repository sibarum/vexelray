package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;

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
    record Implicit(Expr f) implements Surface {
        public Implicit {
            requireNonNull(f);
            if (!Ir.F32.equals(f.type())) {
                throw new IllegalArgumentException("an implicit surface must be a scalar float expression, got "
                        + f.type());
            }
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
