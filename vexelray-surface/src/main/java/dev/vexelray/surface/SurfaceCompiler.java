package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

import java.util.List;

/**
 * Lowers a {@link Surface} to {@code core} IR, tracking as it goes whether the result can actually be marched.
 *
 * <p>The tracking is the interesting half. Every primitive here is a true signed-distance field and every
 * combinator preserves that property, so an ordinary scene lowers to precisely the IR someone would have written
 * by hand — the same {@code length(p - c) - r}, the same {@code min}. Only {@link Surface.Implicit}, which can
 * hold any expression at all, needs the gradient normalisation of {@link Normalize}, and only it pays for it.
 * That is the invariant worth defending: <em>generality costs nothing when it is not used</em>, and the parity
 * test in docs/surface-compiler.md §6 exists to catch the day it stops being true.
 *
 * <p>Domain transforms are applied by lowering a child against a <em>different point expression</em> rather than
 * by rewriting the result afterwards, so a transform costs a few nodes at the leaves instead of a pass over the
 * tree.
 */
public final class SurfaceCompiler {

    private SurfaceCompiler() {
    }

    /** Compile with {@link SurfaceLimits#DEFAULT}. */
    public static Field compile(Surface surface) {
        return compile(surface, SurfaceLimits.DEFAULT);
    }

    /**
     * Compile {@code surface} into a marchable distance field.
     *
     * <p>Limits are checked first, before any lowering: a surface may have come from outside the program, and
     * lowering an oversized one is exactly the work worth not starting (see {@link SurfaceLimits}).
     *
     * @throws SurfaceLimits.SurfaceTooLargeException if the surface exceeds {@code limits}
     * @throws UnsupportedOperationException if an implicit surface contains something that cannot be differentiated
     */
    public static Field compile(Surface surface, SurfaceLimits limits) {
        limits.check(surface);
        Field field = lower(surface, Ir.POINT);
        // Checked again on the way out, not only on the way in: normalisation expands an implicit by a factor
        // that compounds with nesting, so passing the input limits says nothing about the output size.
        limits.checkCompiled(field.distance());
        if (!field.isMarchable()) {
            throw new IllegalStateException(
                    "lowered surface is not marchable (lipschitz " + field.lipschitz() + "); this is a compiler "
                            + "bug — every case is meant to either preserve the bound or normalise");
        }
        return field;
    }

    private static Field lower(Surface surface, Expr p) {
        return switch (surface) {
            case Surface.Sphere s -> Field.exact(
                    Fold.sub(Ir.length(Fold.sub(p, Ir.v3(s.cx(), s.cy(), s.cz()))), Ir.f(s.radius())));

            case Surface.Box b -> Field.exact(box(p, b));

            case Surface.Plane pl -> Field.exact(
                    Fold.add(Ir.dot(p, Ir.v3(pl.nx(), pl.ny(), pl.nz())), Ir.f(pl.offset())));

            case Surface.Capsule c -> Field.exact(capsule(p, c));

            case Surface.Torus t -> Field.exact(torus(p, t));

            // Moving the domain moves the surface; distances are unaffected.
            case Surface.Translate t ->
                    lower(t.of(), Fold.sub(p, Ir.v3(t.dx(), t.dy(), t.dz())));

            // Uniform scale: evaluate in the shrunken frame, then scale the distance back out. Both the field and
            // its gradient scale together, so the bound survives.
            case Surface.Scale s -> {
                Field inner = lower(s.of(), Fold.div(p, Ir.broadcast(Ir.f(s.factor()), Ir.V3)));
                yield new Field(Fold.mul(inner.distance(), Ir.f(s.factor())), inner.lipschitz());
            }

            case Surface.Union u -> combine(u.of(), p, Ir::min);

            case Surface.Intersection i -> combine(i.of(), p, Ir::max);

            case Surface.Difference d -> {
                Field from = lower(d.from(), p);
                Field remove = lower(d.remove(), p);
                yield new Field(Ir.max(from.distance(), Ir.neg(remove.distance())),
                        Math.max(from.lipschitz(), remove.lipschitz()));
            }

            case Surface.SmoothUnion s -> smoothUnion(s, p);

            case Surface.Shell s -> {
                Field inner = lower(s.of(), p);
                yield new Field(Fold.sub(Ir.abs(inner.distance()), Ir.f(s.thickness())), inner.lipschitz());
            }

            case Surface.Round r -> {
                Field inner = lower(r.of(), p);
                yield new Field(Fold.sub(inner.distance(), Ir.f(r.radius())), inner.lipschitz());
            }

            // The one case that cannot vouch for itself. Normalise in the surface's own frame first, then move it
            // into the caller's — see Substitute for why that order is not interchangeable.
            case Surface.Implicit i -> {
                Field normalised = Normalize.lipschitz(i.f());
                yield new Field(Substitute.point(normalised.distance(), p), normalised.lipschitz());
            }
        };
    }

    /** Fold children with a pointwise combinator that preserves the Lipschitz bound ({@code min} / {@code max}). */
    private static Field combine(List<Surface> children, Expr p, Combinator op) {
        Expr distance = null;
        double lipschitz = Field.EXACT;
        for (Surface child : children) {
            Field field = lower(child, p);
            distance = distance == null ? field.distance() : op.apply(distance, field.distance());
            lipschitz = Math.max(lipschitz, field.lipschitz());
        }
        return new Field(distance, lipschitz);
    }

    /**
     * The N-ary exponential soft-min, in its numerically stable form:
     * {@code m - log(sum exp(-k*(d - m)))/k}, where {@code m} is the plain minimum.
     *
     * <p>Subtracting {@code m} before exponentiating is not a nicety. Written directly as
     * {@code -log(sum exp(-k*d))/k}, the exponent grows without bound as a point moves inside the geometry — at
     * {@code k = 8} a depth of 12 already overflows 32-bit float, and the field returns infinity in exactly the
     * region a camera inside the world is looking at. Shifted, every exponent is {@code <= 0} and the sum is
     * bounded by the number of children.
     */
    private static Field smoothUnion(Surface.SmoothUnion surface, Expr p) {
        List<Field> fields = new java.util.ArrayList<>(surface.of().size());
        double lipschitz = Field.EXACT;
        for (Surface child : surface.of()) {
            Field field = lower(child, p);
            fields.add(field);
            lipschitz = Math.max(lipschitz, field.lipschitz());
        }

        Expr minimum = fields.get(0).distance();
        for (int i = 1; i < fields.size(); i++) {
            minimum = Ir.min(minimum, fields.get(i).distance());
        }
        if (fields.size() == 1) {
            return new Field(minimum, lipschitz);
        }

        double k = surface.sharpness();
        Expr sum = null;
        for (Field field : fields) {
            Expr term = Expr.MathCall.exp(Ir.mul(Ir.f(-k), Ir.sub(field.distance(), minimum)));
            sum = sum == null ? term : Ir.add(sum, term);
        }
        return new Field(Ir.sub(minimum, Ir.div(Expr.MathCall.log(sum), Ir.f(k))), lipschitz);
    }

    /** Exact box: distance to the nearest face outside, the largest signed face distance inside. */
    private static Expr box(Expr p, Surface.Box b) {
        Expr q = Fold.sub(Ir.abs(Fold.sub(p, Ir.v3(b.cx(), b.cy(), b.cz()))), Ir.v3(b.hx(), b.hy(), b.hz()));
        Expr outside = Ir.length(Ir.max(q, Ir.v3(0, 0, 0)));
        Expr inside = Ir.min(Ir.max(Ir.x(q), Ir.max(Ir.y(q), Ir.z(q))), Ir.f(0.0));
        return Ir.add(outside, inside);
    }

    /** Exact capsule: distance to the segment, less the radius. */
    private static Expr capsule(Expr p, Surface.Capsule c) {
        Expr a = Ir.v3(c.ax(), c.ay(), c.az());
        Expr ba = Ir.v3(c.bx() - c.ax(), c.by() - c.ay(), c.bz() - c.az());
        Expr pa = Fold.sub(p, a);
        Expr h = Ir.clamp(Ir.div(Ir.dot(pa, ba), Ir.dot(ba, ba)), Ir.f(0.0), Ir.f(1.0));
        return Fold.sub(Ir.length(Fold.sub(pa, Fold.scale(ba, h))), Ir.f(c.radius()));
    }

    /** Exact torus: distance in the (radial, axial) plane of the ring, less the tube radius. */
    private static Expr torus(Expr p, Surface.Torus t) {
        Expr local = Fold.sub(p, Ir.v3(t.cx(), t.cy(), t.cz()));
        Expr radial = Fold.sub(Ir.length(Ir.v2(Ir.x(local), Ir.z(local))), Ir.f(t.major()));
        return Fold.sub(Ir.length(Ir.v2(radial, Ir.y(local))), Ir.f(t.minor()));
    }

    @FunctionalInterface
    private interface Combinator {
        Expr apply(Expr a, Expr b);
    }
}
