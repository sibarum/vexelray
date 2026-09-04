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

    /**
     * Declarations the colour program needs, accumulated as the tree is lowered.
     *
     * <p>An instance field, which is why this class has instances at all: the accumulator has to be reachable
     * from every combinator without being threaded through a signature that has nothing else to do with
     * colour. It stays empty for a surface that named none, and the compiler is created per compile, so
     * nothing is shared between them.
     */
    private final Lets lets = new Lets();

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
        SurfaceCompiler compiler = new SurfaceCompiler();
        Field lowered = compiler.lower(surface, Ir.POINT);
        Field field = lowered.hasAlbedo() ? lowered.withAlbedoLets(compiler.lets.statements()) : lowered;
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

    private Field lower(Surface surface, Expr p) {
        return switch (surface) {
            case Surface.Sphere s -> Field.exact(sphere(p, s));

            case Surface.Box b -> Field.exact(box(p, b));

            case Surface.Plane pl -> Field.exact(
                    Fold.add(Ir.dot(p, Ir.v3(pl.nx(), pl.ny(), pl.nz())), Ir.f(pl.offset())));

            case Surface.Capsule c -> Field.exact(capsule(p, c));

            case Surface.Torus t -> Field.exact(torus(p, t));

            case Surface.Stroke s -> stroke(p, s);

            // Moving the domain moves the surface; distances are unaffected.
            case Surface.Translate t ->
                    lower(t.of(), Fold.sub(p, Ir.v3(t.dx(), t.dy(), t.dz())));

            // Uniform scale: evaluate in the shrunken frame, then scale the distance back out. Both the field and
            // its gradient scale together, so the bound survives.
            case Surface.Scale s -> {
                Field inner = lower(s.of(), Fold.div(p, Ir.broadcast(Ir.f(s.factor()), Ir.V3)));
                yield new Field(Fold.mul(inner.distance(), Ir.f(s.factor())),
                        inner.lipschitz(), inner.albedo());
            }

            // Rotating the object rotates the domain the other way, so the child is lowered at R^T p. The matrix
            // is nine compile-time constants and Fold drops whichever of them are exactly zero, so an
            // axis-aligned turn costs little more than a swizzle.
            case Surface.Rotate r -> lower(r.of(), rotate(p, rodrigues(r.ax(), r.ay(), r.az(), r.angle())));

            // Folding with abs is 1-Lipschitz, so a mirror is as free as a translate.
            case Surface.Mirror m -> lower(m.of(), Ir.v3(
                    m.x() ? Ir.abs(Fold.component(p, 0)) : Fold.component(p, 0),
                    m.y() ? Ir.abs(Fold.component(p, 1)) : Fold.component(p, 1),
                    m.z() ? Ir.abs(Fold.component(p, 2)) : Fold.component(p, 2)));

            case Surface.Repeat r -> repeat(r, p);

            case Surface.PolarRepeat r -> polarRepeat(r, p);

            // A twist is not an isometry: it stretches the domain by up to sqrt(1 + (rate*r)^2) at radius r. The
            // child is lowered in the twisted frame and the result divided by that factor at the declared radius,
            // which is what puts the field back under the bound the march needs.
            case Surface.Twist t -> {
                Expr px = Fold.component(p, 0);
                Expr py = Fold.component(p, 1);
                Expr pz = Fold.component(p, 2);
                Expr angle = Fold.mul(Ir.f(t.rate()), py);
                Expr c = Expr.MathCall.cos(angle);
                Expr s = Expr.MathCall.sin(angle);
                Expr q = Ir.v3(
                        Ir.sub(Ir.mul(c, px), Ir.mul(s, pz)),
                        py,
                        Ir.add(Ir.mul(s, px), Ir.mul(c, pz)));
                yield deform(t.of(), q, twistStretch(t.rate(), t.radius()));
            }

            // Identical reasoning to Twist, turning about Z by an angle proportional to x instead.
            case Surface.Bend b -> {
                Expr px = Fold.component(p, 0);
                Expr py = Fold.component(p, 1);
                Expr pz = Fold.component(p, 2);
                Expr angle = Fold.mul(Ir.f(b.rate()), px);
                Expr c = Expr.MathCall.cos(angle);
                Expr s = Expr.MathCall.sin(angle);
                Expr q = Ir.v3(
                        Ir.sub(Ir.mul(c, px), Ir.mul(s, py)),
                        Ir.add(Ir.mul(s, px), Ir.mul(c, py)),
                        pz);
                yield deform(b.of(), q, bendStretch(b.rate(), b.extent()));
            }

            case Surface.Union u -> combine(u.of(), p, true);

            case Surface.Intersection i -> combine(i.of(), p, false);

            // Carving is intersecting with the inverse: the far surface of the two is the one on show, so the
            // colour follows the same rule as an intersection's.
            case Surface.Difference d -> pick(
                    Coloured.of(lower(d.from(), p)),
                    Coloured.of(invert(lower(d.remove(), p))), false).field();

            case Surface.SmoothUnion s -> softBlend(s.sharpness(), lowerAll(s.of(), p), Blend.SOFT_MIN);

            case Surface.SmoothIntersection s ->
                    softBlend(s.sharpness(), lowerAll(s.of(), p), Blend.SOFT_MAX);

            // Carving is intersecting with the inverse, and negation leaves a field 1-Lipschitz — so this needs
            // no rule of its own beyond flipping the sign of what is being removed.
            case Surface.SmoothDifference s -> softBlend(s.sharpness(),
                    List.of(lower(s.from(), p), invert(lower(s.remove(), p))), Blend.SOFT_MAX);

            case Surface.Shell s -> {
                Field inner = lower(s.of(), p);
                yield new Field(Fold.sub(Ir.abs(inner.distance()), Ir.f(s.thickness())),
                        inner.lipschitz(), inner.albedo());
            }

            case Surface.Round r -> {
                Field inner = lower(r.of(), p);
                yield new Field(Fold.sub(inner.distance(), Ir.f(r.radius())), inner.lipschitz(), inner.albedo());
            }

            // The one case that cannot vouch for itself. Normalise in the surface's own frame first, then move it
            // into the caller's — see Substitute for why that order is not interchangeable.
            case Surface.Implicit i -> {
                // A known global bound is both safer and cheaper than a derived one: safe everywhere rather than
                // locally, and one divide rather than a symbolic gradient several times the size of the field.
                Field normalised = Double.isFinite(i.lipschitzBound())
                        ? Normalize.byConstant(i.f(), i.lipschitzBound())
                        : Normalize.lipschitz(i.f());
                yield new Field(Substitute.point(normalised.distance(), p), normalised.lipschitz());
            }
        };
    }

    /**
     * A domain deformation that is not an isometry: lower {@code of} in the deformed frame {@code q}, then divide
     * the distance by an upper bound on how much the deformation stretches the domain.
     *
     * <p>Dividing is the whole trick, and it is the same one {@link Normalize} plays on an implicit. If the map
     * {@code q} has {@code |Dq| <= stretch}, then {@code d(q(p))} has gradient no longer than
     * {@code stretch * |grad d|}, so the composed field can report up to {@code stretch} times too much distance —
     * and reporting too much is what makes a march step through the surface it was meant to stop at. Scaling the
     * whole field down by {@code stretch} costs march steps and buys back the bound.
     */
    private Field deform(Surface of, Expr q, double stretch) {
        Field inner = lower(of, q);
        return new Field(Fold.div(inner.distance(), Ir.f(stretch)), inner.lipschitz(), inner.albedo());
    }

    /**
     * The largest singular value of a twist's Jacobian anywhere within {@code radius} of the axis.
     *
     * <p>Worth deriving rather than guessing, because the obvious guess is wrong. Writing the twist as
     * {@code q = R(rate*y) p} and factoring out the rotation — which changes no lengths — leaves
     * {@code I + u e_y^T} with {@code |u| = a}, {@code a = |rate| * radius}: the identity, plus the arc a point at
     * that radius sweeps as it slides along the axis. The tempting reading is that the two are perpendicular and
     * the norm is {@code sqrt(1 + a^2)}, which is {@code 1 + a^2/2} for small {@code a} — but a rank-one update
     * does not add in quadrature, and the true norm is {@code 1 + a/2}, <em>linear</em> in the twist. Underneath
     * a real bound is the one failure mode this whole module exists to prevent, so:
     * {@code (a + sqrt(a^2 + 4)) / 2}, exactly.
     */
    private double twistStretch(double rate, double radius) {
        double a = Math.abs(rate) * radius;
        return 0.5 * (a + Math.sqrt(a * a + 4));
    }

    /**
     * The same quantity for a bend, which comes out slightly worse: {@code 1 + |rate| * extent}.
     *
     * <p>The difference is that a twist slides along the axis it turns about, so the rank-one update is
     * perpendicular to the direction it acts in; a bend turns about {@code Z} while travelling along {@code X},
     * in the same plane, so the update has a component along its own direction. That component is largest on the
     * {@code -Y} side of the axis, where the bend's inner and outer radii disagree most, and there the two
     * contributions add outright.
     */
    private double bendStretch(double rate, double extent) {
        return 1 + Math.abs(rate) * extent;
    }

    /**
     * {@link Surface.Repeat}, lowered as a {@code min} over the nearest cell and the nearer neighbour on every
     * repeated axis.
     *
     * <p>The neighbour is the part that is easy to leave out and expensive to leave out. Folding into the nearest
     * cell alone gives a field that is 1-Lipschitz inside each cell and <em>discontinuous</em> at the walls, and
     * across a wall it can report the distance to the near copy while a nearer one sits just over the boundary —
     * an overestimate, which is what a sphere trace cannot survive. Including the neighbour restores the true
     * {@code min} for any child that stays within a cell of its own wall, which covers everything anyone tiles.
     */
    private Field repeat(Surface.Repeat r, Expr p) {
        Surface.Repeat.Axis[] axes = {r.x(), r.y(), r.z()};
        List<Integer> active = new java.util.ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            if (axes[i].repeats()) {
                active.add(i);
            }
        }
        if (active.isEmpty()) {
            return lower(r.of(), p);
        }

        // Two candidate cell indices per repeated axis: the one this point falls in, and the one it leans toward.
        Expr[][] cells = new Expr[active.size()][2];
        for (int a = 0; a < active.size(); a++) {
            Surface.Repeat.Axis axis = axes[active.get(a)];
            Expr t = Ir.div(Fold.component(p, active.get(a)), Ir.f(axis.period()));
            Expr nearest = Expr.MathCall.round(t);
            Expr neighbour = Ir.add(nearest, Expr.MathCall.sign(Ir.sub(t, nearest)));
            cells[a][0] = clampCell(nearest, axis);
            cells[a][1] = clampCell(neighbour, axis);
        }

        Coloured folded = null;
        for (int mask = 0; mask < (1 << active.size()); mask++) {
            Expr[] q = {Fold.component(p, 0), Fold.component(p, 1), Fold.component(p, 2)};
            for (int a = 0; a < active.size(); a++) {
                int i = active.get(a);
                q[i] = Ir.sub(q[i], Ir.mul(Ir.f(axes[i].period()), cells[a][(mask >> a) & 1]));
            }
            Coloured cell = Coloured.of(lower(r.of(), Ir.v3(q[0], q[1], q[2])));
            folded = folded == null ? cell : pick(folded, cell, true);
        }
        return folded.field();
    }

    /** Hold a cell index inside a bounded range, so the tiling stops rather than running to the horizon. */
    private Expr clampCell(Expr cell, Surface.Repeat.Axis axis) {
        if (!axis.bounded()) {
            return cell;
        }
        // Far outside anything a scene addresses, and well inside what a 32-bit float represents exactly enough
        // for a cell index to survive the round-trip.
        double lo = axis.from() == Long.MIN_VALUE ? -1e9 : axis.from();
        double hi = axis.to() == Long.MAX_VALUE ? 1e9 : axis.to();
        return Ir.clamp(cell, Ir.f(lo), Ir.f(hi));
    }

    /**
     * {@link Surface.PolarRepeat}, lowered as a {@code min} over the sector the point is in and the one it leans
     * toward — the angular form of the neighbour argument in {@link #repeat}.
     */
    private Field polarRepeat(Surface.PolarRepeat r, Expr p) {
        double sector = 2 * Math.PI / r.count();
        Expr px = Fold.component(p, 0);
        Expr py = Fold.component(p, 1);
        Expr pz = Fold.component(p, 2);

        // Measured from +Z so that sector 0 straddles it, which is where a single authored instance wants to sit.
        Expr t = Ir.div(Expr.MathCall.atan2(px, pz), Ir.f(sector));
        Expr nearest = Expr.MathCall.round(t);
        Expr[] sectors = {nearest, Ir.add(nearest, Expr.MathCall.sign(Ir.sub(t, nearest)))};

        Coloured folded = null;
        for (Expr index : sectors) {
            Expr theta = Ir.mul(index, Ir.f(sector));
            Expr c = Expr.MathCall.cos(theta);
            Expr s = Expr.MathCall.sin(theta);
            Coloured one = Coloured.of(lower(r.of(), Ir.v3(
                    Ir.sub(Ir.mul(c, px), Ir.mul(s, pz)),
                    py,
                    Ir.add(Ir.mul(s, px), Ir.mul(c, pz)))));
            folded = folded == null ? one : pick(folded, one, true);
        }
        return folded.field();
    }

    /**
     * Rodrigues' formula, evaluated in Java: the rotation matrix about a unit axis, row-major.
     * {@code R = I cos(t) + sin(t) [k]x + (1 - cos(t)) k k^T}.
     */
    private double[] rodrigues(double kx, double ky, double kz, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        double t = 1 - c;
        return new double[]{
                t * kx * kx + c, t * kx * ky - s * kz, t * kx * kz + s * ky,
                t * kx * ky + s * kz, t * ky * ky + c, t * ky * kz - s * kx,
                t * kx * kz - s * ky, t * ky * kz + s * kx, t * kz * kz + c};
    }

    /** {@code R^T p} — the domain turned opposite to the object, as three folded dot products. */
    private Expr rotate(Expr p, double[] m) {
        Expr[] out = new Expr[3];
        for (int i = 0; i < 3; i++) {
            Expr sum = null;
            for (int j = 0; j < 3; j++) {
                Expr term = Fold.mul(Ir.f(m[j * 3 + i]), Fold.component(p, j));
                sum = sum == null ? term : Fold.add(sum, term);
            }
            out[i] = sum;
        }
        return Ir.v3(out[0], out[1], out[2]);
    }

    /** Fold children with a pointwise combinator that preserves the Lipschitz bound ({@code min} / {@code max}). */
    private Field combine(List<Surface> children, Expr p, boolean nearest) {
        Coloured folded = null;
        for (Surface child : children) {
            Coloured one = Coloured.of(lower(child, p));
            folded = folded == null ? one : pick(folded, one, nearest);
        }
        return folded.field();
    }

    /** A field turned inside out — what a subtraction intersects with. Negation leaves it 1-Lipschitz. */
    private Field invert(Field f) {
        return new Field(Ir.neg(f.distance()), f.lipschitz(), f.albedo());
    }

    /** Two fields' distances combined pointwise, with no colour in it — {@code min}, or {@code max}. */
    private Field pick(Field a, Field b, boolean nearest) {
        Expr distance = nearest
                ? Ir.min(a.distance(), b.distance())
                : Ir.max(a.distance(), b.distance());
        return new Field(distance, Math.max(a.lipschitz(), b.lipschitz()));
    }

    /**
     * The same combination, done where a colour is live, with every comparison bound to a name.
     *
     * <p>This is the whole of the fix {@link Lets} exists for, and the shape is worth reading carefully. Each
     * side brings a <b>handle</b>: a local, inside the colour program, already holding that subtree's distance.
     * The combination compares two handles, binds the mixed colour, and binds a fresh handle holding the
     * combined distance — so the next combination up compares two names rather than two trees.
     *
     * <p>Written the obvious way instead, the fold re-reads {@code a.distance()} at every level, and since
     * {@code a} is the accumulated field that re-reads the entire chain built so far. Same answer, size
     * quadratic in the child count rather than linear: 114 cones took the colour from 55k nodes to 9.4M, and
     * the 21 MB module that came out locked the driver compiling it.
     *
     * <p>The returned {@link Field}'s own distance is unaffected — it stays the pure {@code min}/{@code max}
     * tree the march walks, with no locals in it. The bindings live only in the colour program, which is
     * emitted as its own function and called once per pixel.
     */
    private Coloured pick(Coloured a, Coloured b, boolean nearest) {
        Field combined = pick(a.field(), b.field(), nearest);
        if (!a.field().hasAlbedo() && !b.field().hasAlbedo()) {
            // Neither child named a colour, so there is nothing to select and nothing to bind. This is the
            // ordinary case, and it must stay free: an uncoloured scene lowers to byte-identical IR whether or
            // not colour exists as a feature.
            return Coloured.of(combined);
        }
        Expr da = a.handle(lets);
        Expr db = b.handle(lets);
        // 1 selects b: for a union when b is the nearer, for an intersection when b is the farther. A child
        // with no colour of its own contributes SCENE_ALBEDO, so unioning a painted stroke with a bare sphere
        // leaves the sphere the scene's colour rather than the stroke's.
        Expr takeB = nearest ? Ir.step(db, da) : Ir.step(da, db);
        Expr albedo = lets.bind("albedo", Ir.mix(
                a.field().hasAlbedo() ? a.field().albedo() : Ir.SCENE_ALBEDO,
                b.field().hasAlbedo() ? b.field().albedo() : Ir.SCENE_ALBEDO,
                Ir.broadcast(takeB, Ir.V3)));
        Expr handle = lets.bind("d", nearest ? Ir.min(da, db) : Ir.max(da, db));
        return new Coloured(combined.withAlbedo(albedo), handle);
    }

    /**
     * A lowered field, plus the local its colour program uses for its own distance.
     *
     * <p>Two things rather than one because they belong to two different programs. The {@link Field} is what
     * the march gets: a pure expression tree, no locals, unchanged from before colour existed. The handle is a
     * name valid only inside the colour function, and it exists so that combining colours costs a comparison
     * rather than a copy of everything underneath.
     *
     * @param handle a read of a local holding {@link #field}'s distance, or {@code null} if this subtree has no
     *               colour and so has never needed one
     */
    private record Coloured(Field field, Expr handle) {

        /** A field with no colour anywhere in it, which therefore needs no handle unless something asks. */
        static Coloured of(Field field) {
            return new Coloured(field, null);
        }

        /**
         * This subtree's distance as a name inside the colour program, binding it if it has not been bound.
         *
         * <p>The lazy half matters: an uncoloured subtree unioned with a coloured one is bound here, once, and
         * that one binding is the only copy of its field the colour program ever holds.
         */
        Expr handle(Lets lets) {
            return handle != null ? handle : lets.bind("d", field.distance());
        }
    }

    /** Which end of the range a {@link #softBlend} rounds off. */
    private enum Blend {
        /** Soft-min: a union whose seams become fillets. Sits at or below {@code min}. */
        SOFT_MIN(-1.0),
        /** Soft-max: an intersection whose rims become fillets. Sits at or above {@code max}. */
        SOFT_MAX(1.0);

        final double sign;

        Blend(double sign) {
            this.sign = sign;
        }

        Expr extremum(Expr a, Expr b) {
            return this == SOFT_MIN ? Ir.min(a, b) : Ir.max(a, b);
        }
    }

    /**
     * The N-ary exponential soft-min / soft-max, in its numerically stable form:
     * {@code m + s*log(sum exp(s*k*(d - m)))/k}, where {@code m} is the plain extremum and {@code s} is
     * {@code -1} for a soft-min or {@code +1} for a soft-max.
     *
     * <p>Subtracting {@code m} before exponentiating is not a nicety. Written directly, the exponent grows
     * without bound as a point moves deep into (or far from) the geometry — at {@code k = 8} a depth of 12
     * already overflows 32-bit float, and the field returns infinity in exactly the region a camera inside the
     * world is looking at. Shifted, every exponent is {@code <= 0} and the sum is bounded by the child count.
     *
     * <p><b>Why both directions stay safe to march.</b> A soft-min is {@code <= min} and a soft-max is
     * {@code >= max}, so only one of them is bounded by the hard operator it softens — but that was never what
     * made either conservative. What does is that both are 1-Lipschitz: the gradient of a log-sum-exp is a
     * convex combination of its terms' gradients, so it can be no longer than the longest of them. A
     * 1-Lipschitz field never reports more than the true distance to its own zero set, which is the surface
     * actually being drawn — a rounder one than the hard operator would have given.
     */
    private Field softBlend(double k, List<Field> fields, Blend blend) {
        double lipschitz = Field.EXACT;
        for (Field field : fields) {
            lipschitz = Math.max(lipschitz, field.lipschitz());
        }

        Expr extremum = fields.get(0).distance();
        for (int i = 1; i < fields.size(); i++) {
            extremum = blend.extremum(extremum, fields.get(i).distance());
        }
        if (fields.size() == 1) {
            return new Field(extremum, lipschitz, fields.get(0).albedo());
        }

        Expr sum = null;
        for (Field field : fields) {
            Expr term = Expr.MathCall.exp(Ir.mul(Ir.f(blend.sign * k), Ir.sub(field.distance(), extremum)));
            sum = sum == null ? term : Ir.add(sum, term);
        }
        Expr correction = Ir.div(Expr.MathCall.log(sum), Ir.f(k));
        Expr blended = blend == Blend.SOFT_MIN
                ? Ir.sub(extremum, correction)
                : Ir.add(extremum, correction);

        // Colour follows the *hard* extremum, not the blend: whichever child would have won a plain min/max
        // owns the point. So a fillet between two differently coloured children still switches colour abruptly
        // at the crossover — weighting colours by the same exponential the distance uses is the material-matrix
        // work of docs/vexel-world.md, not something to bolt on here.
        Expr albedo = null;
        if (fields.stream().anyMatch(Field::hasAlbedo)) {
            Coloured selected = Coloured.of(fields.get(0));
            for (int i = 1; i < fields.size(); i++) {
                selected = pick(selected, Coloured.of(fields.get(i)), blend == Blend.SOFT_MIN);
            }
            albedo = selected.field().albedo();
        }
        return new Field(blended, lipschitz, albedo);
    }

    /** Lower every child against the same point. */
    private List<Field> lowerAll(List<Surface> children, Expr p) {
        List<Field> fields = new java.util.ArrayList<>(children.size());
        for (Surface child : children) {
            fields.add(lower(child, p));
        }
        return fields;
    }

    /** Exact box: distance to the nearest face outside, the largest signed face distance inside. */
    private Expr box(Expr p, Surface.Box b) {
        Expr q = Fold.sub(Ir.abs(Fold.sub(p, Ir.v3(b.cx(), b.cy(), b.cz()))), Ir.v3(b.hx(), b.hy(), b.hz()));
        Expr outside = Ir.length(Ir.max(q, Ir.v3(0, 0, 0)));
        Expr inside = Ir.min(Ir.max(Ir.x(q), Ir.max(Ir.y(q), Ir.z(q))), Ir.f(0.0));
        return Ir.add(outside, inside);
    }

    /** Exact capsule: distance to the segment, less the radius. */
    private Expr capsule(Expr p, Surface.Capsule c) {
        Expr a = Ir.v3(c.ax(), c.ay(), c.az());
        Expr ba = Ir.v3(c.bx() - c.ax(), c.by() - c.ay(), c.bz() - c.az());
        Expr pa = Fold.sub(p, a);
        Expr h = Ir.clamp(Ir.div(Ir.dot(pa, ba), Ir.dot(ba, ba)), Ir.f(0.0), Ir.f(1.0));
        return Fold.sub(Ir.length(Fold.sub(pa, Fold.scale(ba, h))), Ir.f(c.radius()));
    }

    /**
     * A stroke: the {@code min} over the cones {@link Spine} laid out along it.
     *
     * <p>All the geometry happened in Java, on constants. What reaches the IR is a flat union of exact
     * primitives — no curve evaluation, no branch on curvature, nothing per-step that a hand-written chain of
     * capsules would not also pay. A corner at zero curvature contributes no cones of its own at all, so the
     * sharp case really is as cheap as it looks.
     */
    private Field stroke(Expr p, Surface.Stroke s) {
        boolean coloured = s.hasColour();
        Coloured folded = null;
        for (Spine.Piece piece : Spine.of(s)) {
            // One end sphere swallowing the other is not a rounding problem to be nudged past: the round-cone
            // formula has no real value there. Caught on constants, and the hull it names emitted instead.
            Expr distance;
            Expr albedo;
            if (piece.degenerate()) {
                Spine.End end = piece.swallowing();
                distance = sphere(p, new Surface.Sphere(end.x(), end.y(), end.z(), end.radius()));
                albedo = coloured ? rgb(end.colour()) : null;
            } else {
                distance = roundCone(p, piece);
                albedo = coloured ? gradientAlong(p, piece) : null;
            }
            // A coloured cone binds its own distance up front rather than leaving it to be copied by the
            // combination above: it is used twice — once by the comparison, once by the running minimum — and
            // this is the one place where the number of children is large enough for that to matter.
            Field one = new Field(distance, Field.EXACT, albedo);
            Coloured next = coloured
                    ? new Coloured(one, lets.bind("d", distance))
                    : Coloured.of(one);
            folded = folded == null ? next : pick(folded, next, true);
        }
        return folded.field();
    }

    /**
     * The colour partway along one cone: its two end colours mixed by the axial projection of the sample point,
     * clamped to the cone's own span so the caps take the colour of the end they belong to.
     *
     * <p>Per cone rather than per stroke, which is what makes the gradient follow the curve: the corner samples
     * carry colours interpolated the same way their positions were (see {@link Spine}), so a colour crosses a
     * rounded corner along the arc rather than across the chord.
     *
     * <p>A cone whose ends agree emits the constant instead of a {@code mix} of a value with itself, which is
     * the common case — a stroke of one colour should not pay per cone for a gradient it does not have.
     */
    private Expr gradientAlong(Expr p, Spine.Piece piece) {
        Spine.End a = piece.a();
        Spine.End b = piece.b();
        if (a.colour().equals(b.colour())) {
            return rgb(a.colour());
        }
        Expr ba = Ir.v3(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
        Expr pa = Fold.sub(p, Ir.v3(a.x(), a.y(), a.z()));
        Expr h = Ir.clamp(Fold.mul(Ir.dot(pa, ba), Ir.f(1.0 / piece.axisLengthSquared())),
                Ir.f(0.0), Ir.f(1.0));
        return Ir.mix(rgb(a.colour()), rgb(b.colour()), Ir.broadcast(h, Ir.V3));
    }

    /** A colour as a constant {@code vec3}. */
    private Expr rgb(Surface.Rgb c) {
        return Ir.v3(c.r(), c.g(), c.b());
    }

    /**
     * The exact distance to a tapered round cone — the convex hull of a sphere of radius {@code ar} at {@code a}
     * and one of radius {@code br} at {@code b} (Quílez). Exact, so 1-Lipschitz, so a stroke needs no
     * normalisation and no stretch divide.
     *
     * <p>Three regions: the two spherical caps, and the tangent cone between them. Which one a point is in
     * depends on where it sits, so unlike everything else in this file the choice cannot be made at compile
     * time — it is selected with {@code step}/{@code mix} rather than branched, both because the IR has no
     * branches and because a divergent branch is worth nothing on a GPU anyway.
     *
     * <p>Everything that does <em>not</em> depend on the point is folded in Java first: the axis, its squared
     * length, the radius difference, and the {@code a2 = |b-a|^2 - (ar-br)^2} that governs the tangent. That
     * constant is guaranteed positive here because {@link Spine.Piece#degenerate()} already diverted the case
     * where it is not, which is what lets the {@code sqrt} below stand without an epsilon under it. The
     * remaining {@code max(·, 0)}s guard only against a sum of squares landing a bit under zero in float.
     */
    private Expr roundCone(Expr p, Spine.Piece s) {
        double bax = s.b().x() - s.a().x();
        double bay = s.b().y() - s.a().y();
        double baz = s.b().z() - s.a().z();
        double l2 = s.axisLengthSquared();
        double rr = s.a().radius() - s.b().radius();
        double a2 = l2 - rr * rr;
        double il2 = 1.0 / l2;

        Expr ba = Ir.v3(bax, bay, baz);
        Expr pa = Fold.sub(p, Ir.v3(s.a().x(), s.a().y(), s.a().z()));
        Expr y = Ir.dot(pa, ba);
        Expr z = Fold.sub(y, Ir.f(l2));

        // Squared distance from the axis, carried at scale l2^2 so that the three regions share one square root
        // budget and the division happens once, at the end.
        Expr perp = Ir.sub(Fold.scale(pa, Ir.f(l2)), Fold.scale(ba, y));
        Expr x2 = Ir.dot(perp, perp);
        Expr y2 = Fold.mul(Ir.mul(y, y), Ir.f(l2));
        Expr z2 = Fold.mul(Ir.mul(z, z), Ir.f(l2));

        // The tangent point, as a threshold on x2: which side of it y and z fall on says which region holds p.
        Expr k = Fold.mul(Ir.f(Math.signum(rr) * rr * rr), x2);

        Expr capA = Fold.sub(Fold.mul(Ir.sqrt(nonNegative(Ir.add(x2, y2))), Ir.f(il2)), Ir.f(s.a().radius()));
        Expr capB = Fold.sub(Fold.mul(Ir.sqrt(nonNegative(Ir.add(x2, z2))), Ir.f(il2)), Ir.f(s.b().radius()));
        Expr cone = Fold.sub(
                Fold.mul(Ir.add(Ir.sqrt(nonNegative(Fold.mul(x2, Ir.f(a2 * il2)))), Fold.mul(y, Ir.f(rr))),
                        Ir.f(il2)),
                Ir.f(s.a().radius()));

        Expr pastB = Ir.step(k, signed(z, a2, z2));      // sign(z)*a2*z2 >= k : beyond the b cap
        Expr beforeA = Ir.step(signed(y, a2, y2), k);    // sign(y)*a2*y2 <= k : behind the a cap
        return Ir.mix(Ir.mix(cone, capA, beforeA), capB, pastB);
    }

    /** {@code sign(v) * a2 * m} — the side test, written the way the region boundaries are stated. */
    private Expr signed(Expr v, double a2, Expr m) {
        return Fold.mul(Fold.mul(Expr.MathCall.sign(v), Ir.f(a2)), m);
    }

    /** Clamp a quantity that is a sum of squares in exact arithmetic, and might not be in float. */
    private Expr nonNegative(Expr e) {
        return Ir.max(e, Ir.f(0.0));
    }

    /** Exact sphere: the primitive, reachable from {@link #stroke} as well as from the tree. */
    private Expr sphere(Expr p, Surface.Sphere s) {
        return Fold.sub(Ir.length(Fold.sub(p, Ir.v3(s.cx(), s.cy(), s.cz()))), Ir.f(s.radius()));
    }

    /** Exact torus: distance in the (radial, axial) plane of the ring, less the tube radius. */
    private Expr torus(Expr p, Surface.Torus t) {
        Expr local = Fold.sub(p, Ir.v3(t.cx(), t.cy(), t.cz()));
        Expr radial = Fold.sub(Ir.length(Ir.v2(Ir.x(local), Ir.z(local))), Ir.f(t.major()));
        return Fold.sub(Ir.length(Ir.v2(radial, Ir.y(local))), Ir.f(t.minor()));
    }

}
