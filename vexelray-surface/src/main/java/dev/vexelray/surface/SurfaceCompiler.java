package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Where a {@link Scalar.Param} is read from — supplied by whoever declared the block, and
     * {@link ParamStore#NONE} when nobody did.
     */
    private final ParamStore params;

    /**
     * The declarations of the program being emitted, innermost last.
     *
     * <p>A stack rather than a list because a shared subtree becomes a function with a body of its own, and a
     * point bound while lowering that subtree belongs to <em>its</em> body — a local declared in one function
     * and read in another is not a program. The bottom of the stack is the {@code sdf} function itself.
     */
    private final Deque<Scope> scopes = new ArrayDeque<>();

    /** Functions the emitted program calls, callees before callers — see {@link #helperFor}. */
    private final List<Function> helpers = new ArrayList<>();

    /** Subtrees already emitted as functions, so the second site calls the first site's function. */
    private final Map<Surface, Helper> memo = new HashMap<>();

    /** Which subtrees to emit as functions, and the shape each one is keyed by — {@link Shared#of}. */
    private final Shared.Sharing sharing;

    /**
     * The payload channel's slots, or {@code null} when this compile is not carrying one.
     *
     * <p>The whole of the second lowering mode is this field being non-null. A display path compiles without
     * it and pays nothing: no comparison bound, no channel selected, no {@code vec2} anywhere — byte for
     * byte the module it composed before payloads existed. A pick pass compiles with it, and pays for what
     * it is going to read.
     */
    private final PayloadTable table;

    /** Names every local in this compile, so two functions cannot declare the same one. */
    private int nextLocal;

    private SurfaceCompiler(ParamStore params, Shared.Sharing sharing, PayloadTable table) {
        this.params = params;
        this.sharing = sharing;
        this.table = table;
        this.scopes.push(new Scope());
    }

    /** A shared subtree's function, and the bound its body carries. */
    private record Helper(Function function, double lipschitz) {
    }

    /** The declarations of one function's body, in the order they must be emitted. */
    private final class Scope {

        private final List<Statement> statements = new ArrayList<>();

        Expr bind(String name, Expr value) {
            LocalVar variable = new LocalVar(name + "_" + nextLocal++, value.type());
            statements.add(new Statement.DeclareVar(variable, value));
            return new Expr.Read(variable);
        }

        List<Statement> statements() {
            return List.copyOf(statements);
        }
    }

    /**
     * Compile with {@link SurfaceLimits#DEFAULT} and no parameter block.
     *
     * <p>A surface holding a {@link Scalar.Param} fails here, by name: there is nowhere for its value to come
     * from. Compile through {@code SdfComposer}, or pass a store from {@link ParamBlock}.
     */
    public static Field compile(Surface surface) {
        return compile(surface, SurfaceLimits.DEFAULT, ParamStore.NONE);
    }

    /** Compile with {@link SurfaceLimits#DEFAULT}, reading parameters from {@code params}. */
    public static Field compile(Surface surface, ParamStore params) {
        return compile(surface, SurfaceLimits.DEFAULT, params);
    }

    /** Compile against {@code limits} and no parameter block. */
    public static Field compile(Surface surface, SurfaceLimits limits) {
        return compile(surface, limits, ParamStore.NONE);
    }

    /**
     * Compile {@code surface} into a marchable distance field.
     *
     * <p>Limits are checked first, before any lowering: a surface may have come from outside the program, and
     * lowering an oversized one is exactly the work worth not starting (see {@link SurfaceLimits}).
     *
     * @param params where the shader reads driven values from; every {@link Scalar.Param} in the tree is
     *               resolved through it, and it is also what says which push-constant block an
     *               {@link Surface.Implicit} may legitimately read
     * @throws SurfaceLimits.SurfaceTooLargeException if the surface exceeds {@code limits}
     * @throws UnsupportedOperationException if an implicit surface contains something that cannot be differentiated
     */
    public static Field compile(Surface surface, SurfaceLimits limits, ParamStore params) {
        return compile(surface, limits, params, null);
    }

    /**
     * Compile {@code surface} into a field that says <b>what</b> it hit as well as how far away it is — the
     * second lowering mode (P2).
     *
     * <p>Two modes rather than one channel that is always there, because the two things a payload costs are
     * both real and neither is paid by a scene that does not read it:
     *
     * <ul>
     *   <li><b>Every combinator binds its arms' distances</b>, so that the comparison which picks the
     *       distance can also pick the payload. That is the colour program's cost moved into the distance
     *       program, and the distance program runs nine times per pixel per march step.</li>
     *   <li><b>Sharing narrows from shapes to nodes.</b> A shared function carries the payload of the subtree
     *       it was emitted from, and two separately authored copies of one shape are two answers to "what did
     *       I click on" — so they cannot be one function here. A repeat's 2ⁿ cells still are: they are one
     *       authored node, and clicking any cell should select it.</li>
     * </ul>
     *
     * <p>The field's distance is unchanged by any of it — the same expression, marched the same way — and
     * {@link Field#asPayloadFunction} is how both come out: {@code vec2(distance, payload)}. What the payload
     * <em>means</em> is {@link PayloadTable}, published on the field, because a slot is an encoding and must
     * not escape as an identity.
     */
    public static Field compileWithPayload(Surface surface, SurfaceLimits limits, ParamStore params) {
        return compile(surface, limits, params, PayloadTable.of(surface));
    }

    /** Compile with a payload channel and the default limits. */
    public static Field compileWithPayload(Surface surface, ParamStore params) {
        return compileWithPayload(surface, SurfaceLimits.DEFAULT, params);
    }

    /** Compile with a payload channel, the default limits, and no parameter block. */
    public static Field compileWithPayload(Surface surface) {
        return compileWithPayload(surface, SurfaceLimits.DEFAULT, ParamStore.NONE);
    }

    private static Field compile(Surface surface, SurfaceLimits limits, ParamStore params,
                                 PayloadTable table) {
        limits.check(surface);
        SurfaceCompiler compiler = new SurfaceCompiler(params,
                Shared.of(surface, table == null ? Shared.Keying.SHAPE : Shared.Keying.NODE), table);
        Field lowered = compiler.lower(surface, Ir.POINT)
                .withProgram(compiler.scopes.peek().statements(), compiler.helpers);
        Field field = lowered.hasAlbedo() ? lowered.withAlbedoLets(compiler.lets.statements()) : lowered;
        // Checked again on the way out, not only on the way in: normalisation expands an implicit by a factor
        // that compounds with nesting, so passing the input limits says nothing about the output size.
        limits.checkCompiled(field);
        if (!field.isMarchable()) {
            throw new IllegalStateException(
                    "lowered surface is not marchable (lipschitz " + field.lipschitz() + "); this is a compiler "
                            + "bug — every case is meant to either preserve the bound or normalise");
        }
        return field;
    }

    /**
     * Lower {@code surface} at {@code p} — as a call, if this subtree is written more than once.
     *
     * <p>This is P1's whole shape. {@link Shared#of} decided, before any lowering, which subtrees the walk
     * would otherwise emit repeatedly: the child of a {@code Repeat} (2ⁿ cells), the child of a
     * {@code PolarRepeat} (two sectors), and anything the author used twice. Each of those is emitted once as
     * {@code float f(vec3)} and called from every site, so a module grows with the operators in a design
     * rather than with the product of their multiplicities.
     *
     * <p>The point is the call's argument, which is also what binds it: an argument is evaluated once however
     * many times the body reads it.
     */
    private Field lower(Surface surface, Expr p) {
        if (sharing.shares(surface)) {
            Helper helper = helperFor(surface);
            Expr call = new Expr.Call(helper.function(), List.of(p));
            if (table == null) {
                return new Field(call, helper.lipschitz());
            }
            // A payload-carrying function returns both, so the call is named: reading .x and .y off the
            // expression itself would put two calls in the tree and evaluate the whole subtree twice.
            Expr both = scopes.peek().bind("hit", call);
            return new Field(Ir.x(both), helper.lipschitz(), null, Ir.y(both));
        }
        return lowerHere(surface, p);
    }

    /**
     * This subtree as a function, lowered once against {@link Ir#POINT} and remembered.
     *
     * <p>Lowered against the sample point rather than against the caller's, which is what makes one body serve
     * every site: {@code Ir.POINT} is {@code Expr.Param(0)}, and a function's first parameter is the same
     * expression, so the body a subtree produces at the origin of its own frame <em>is</em> the function body,
     * with nothing to substitute.
     */
    private Helper helperFor(Surface surface) {
        // Keyed by whatever "the same subtree" means in this mode: by shape on the display path, so two
        // separately authored copies reach one function; by node when a payload is being carried, since two
        // authored nodes are two answers to what was clicked. See Shared.Keying.
        Surface shape = sharing.shapeOf(surface);
        Helper existing = memo.get(shape);
        if (existing != null) {
            return existing;
        }
        Scope scope = new Scope();
        scopes.push(scope);
        Field body;
        try {
            // lowerHere, not lower: this is the site that emits it, and going through lower would ask for a
            // function to emit the function.
            body = lowerHere(surface, Ir.POINT);
        } finally {
            scopes.pop();
        }
        List<Statement> statements = new ArrayList<>(scope.statements());
        // A payload-carrying subtree returns both, in one function, for the reason Field.asPayloadFunction
        // gives: the comparison that picked the distance is the one that picked the payload.
        statements.add(new Statement.Return(
                table == null ? body.distance() : Ir.v2(body.distance(), body.payload())));
        Function function = new Function("f" + helpers.size(),
                new Type.FunctionType(table == null ? Ir.F32 : Ir.V2, List.of(Ir.V3)),
                new Region(statements));
        // Added after its own body was lowered, so a helper that calls another is emitted after the one it
        // calls — the order a module wants.
        helpers.add(function);
        Helper helper = new Helper(function, body.lipschitz());
        memo.put(shape, helper);
        return helper;
    }

    /**
     * The transformed point, named.
     *
     * <p>P1 step 1, and the cheap half of the stage — cheap to write, and worth more than it looks. A domain
     * operator lowers its child against a <em>point expression</em>, and a primitive reads its point several
     * times: a box reads it four times, so a rotation under a box is written four times, and a twist over a
     * repeat over a smooth union multiplies through every one of those. Naming the point costs one
     * declaration and turns each of those copies into a read. The measured difference on the ladder's sixth
     * rung is four megabytes against a few kilobytes.
     *
     * <p>A point that is already a name — the sample point itself, or a local bound by the operator above —
     * is returned unchanged, so an identity transform still emits nothing.
     */
    private Expr bindPoint(Expr q) {
        if (q instanceof Expr.Param || q instanceof Expr.Read) {
            return q;
        }
        return scopes.peek().bind("p", q);
    }

    /**
     * The payload a node originates: its slot in the table, as a constant — or {@code null} on the display
     * path, where there is no channel to put it in.
     *
     * <p>A constant, because which node owns a shape's points is known when the shape is lowered. Only a
     * combinator has to choose at runtime, and it chooses between two constants.
     */
    private Expr slot(Surface node) {
        return table == null ? null : Ir.f(table.slotOf(node.id()));
    }

    /** A field whose points belong to {@code node} — every primitive, and nothing else. */
    private Field owned(Expr distance, Surface node) {
        return new Field(distance, Field.EXACT, null, slot(node));
    }

    /** A scalar used more than once by the operator computing it — a cell index, a folded angle. */
    private Expr bindScalar(String name, Expr value) {
        if (value instanceof Expr.Param || value instanceof Expr.Read || value instanceof Expr.ConstFloat) {
            return value;
        }
        return scopes.peek().bind(name, value);
    }

    private Field lowerHere(Surface surface, Expr p) {
        return switch (surface) {
            case Surface.Sphere s -> owned(sphere(p, s), s);

            case Surface.Box b -> owned(box(p, b), b);

            case Surface.Plane pl -> owned(
                    Fold.add(Ir.dot(p, Ir.v3(pl.nx(), pl.ny(), pl.nz())), expr(pl.offset())), pl);

            case Surface.Capsule c -> owned(capsule(p, c), c);

            case Surface.Torus t -> owned(torus(p, t), t);

            case Surface.Stroke s -> stroke(p, s);

            // Moving the domain moves the surface; distances are unaffected.
            case Surface.Translate t ->
                    lower(t.of(), bindPoint(Fold.sub(p, Ir.v3(expr(t.dx()), expr(t.dy()), expr(t.dz())))));

            // Uniform scale: evaluate in the shrunken frame, then scale the distance back out. Both the field and
            // its gradient scale together, so the bound survives.
            case Surface.Scale s -> {
                Expr factor = expr(s.factor());
                Field inner = lower(s.of(), bindPoint(Fold.div(p, Ir.broadcast(factor, Ir.V3))));
                yield new Field(Fold.mul(inner.distance(), factor), inner.lipschitz(), inner.albedo(),
                        inner.payload());
            }

            // Rotating the object rotates the domain the other way, so the child is lowered at R^T p. For a
            // literal angle the matrix is nine compile-time constants and Fold drops whichever of them are
            // exactly zero, so an axis-aligned turn costs little more than a swizzle.
            case Surface.Rotate r ->
                    lower(r.of(), bindPoint(rotate(p, rodrigues(r.ax(), r.ay(), r.az(), r.angle()))));

            // Folding with abs is 1-Lipschitz, so a mirror is as free as a translate.
            case Surface.Mirror m -> lower(m.of(), bindPoint(Ir.v3(
                    m.x() ? Ir.abs(Fold.component(p, 0)) : Fold.component(p, 0),
                    m.y() ? Ir.abs(Fold.component(p, 1)) : Fold.component(p, 1),
                    m.z() ? Ir.abs(Fold.component(p, 2)) : Fold.component(p, 2))));

            case Surface.Repeat r -> repeat(r, p);

            case Surface.PolarRepeat r -> polarRepeat(r, p);

            // A twist is not an isometry: it stretches the domain by up to sqrt(1 + (rate*r)^2) at radius r. The
            // child is lowered in the twisted frame and the result divided by that factor at the declared radius,
            // which is what puts the field back under the bound the march needs.
            case Surface.Twist t -> {
                Expr px = Fold.component(p, 0);
                Expr py = Fold.component(p, 1);
                Expr pz = Fold.component(p, 2);
                Expr angle = Fold.mul(expr(t.rate()), py);
                Expr c = Expr.MathCall.cos(angle);
                Expr s = Expr.MathCall.sin(angle);
                Expr q = bindPoint(Ir.v3(
                        Ir.sub(Ir.mul(c, px), Ir.mul(s, pz)),
                        py,
                        Ir.add(Ir.mul(s, px), Ir.mul(c, pz))));
                yield deform(t.of(), q, twistStretch(t.rate(), t.radius()));
            }

            // Identical reasoning to Twist, turning about Z by an angle proportional to x instead.
            case Surface.Bend b -> {
                Expr px = Fold.component(p, 0);
                Expr py = Fold.component(p, 1);
                Expr pz = Fold.component(p, 2);
                Expr angle = Fold.mul(expr(b.rate()), px);
                Expr c = Expr.MathCall.cos(angle);
                Expr s = Expr.MathCall.sin(angle);
                Expr q = bindPoint(Ir.v3(
                        Ir.sub(Ir.mul(c, px), Ir.mul(s, py)),
                        Ir.add(Ir.mul(s, px), Ir.mul(c, py)),
                        pz));
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
                yield new Field(Fold.sub(Ir.abs(inner.distance()), expr(s.thickness())),
                        inner.lipschitz(), inner.albedo(), inner.payload());
            }

            case Surface.Round r -> {
                Field inner = lower(r.of(), p);
                yield new Field(Fold.sub(inner.distance(), expr(r.radius())), inner.lipschitz(),
                        inner.albedo(), inner.payload());
            }

            // The one case that cannot vouch for itself. Normalise in the surface's own frame first, then move it
            // into the caller's — see Substitute for why that order is not interchangeable.
            case Surface.Implicit i -> {
                screen(i.f());
                // A known global bound is both safer and cheaper than a derived one: safe everywhere rather than
                // locally, and one divide rather than a symbolic gradient several times the size of the field.
                Field normalised = Double.isFinite(i.lipschitzBound())
                        ? Normalize.byConstant(i.f(), i.lipschitzBound())
                        : Normalize.lipschitz(i.f());
                yield new Field(Substitute.point(normalised.distance(), p), normalised.lipschitz(),
                        null, slot(i));
            }
        };
    }

    /**
     * A number in the tree, as IR: the constant it is, or a read of the slot the host writes it into.
     *
     * <p>The literal case emits precisely what a {@code double} field emitted before parameters existed, which
     * is what keeps a scene of literals lowering to byte-identical IR — the acceptance test for the whole
     * migration.
     */
    private Expr expr(Scalar s) {
        return switch (s) {
            case Scalar.Lit l -> Ir.f(l.value());
            case Scalar.Param param -> params.read(param.id());
        };
    }

    /**
     * Refuse an {@link Surface.Implicit} that reads a push-constant block the composer never issued — R1.3, and
     * the one screen in this file that catches a mistake the IR cannot.
     *
     * <p>Measured before it was written: such a read <b>compiles without complaint</b> today. A module emits one
     * push-constant block, the foreign one's members are never part of it, and only the member <em>index</em>
     * survives the lowering — so a read of member 0 of some block a caller built by hand silently becomes a read
     * of the camera's {@code camX}, and the surface moves when the camera does. The bounds check that exists
     * does not catch it, because the index is validated against the foreign block, where it is perfectly valid.
     *
     * <p><b>The limit this leaves.</b> An implicit's expression cannot carry a parameter of its own, because
     * raw {@code core} IR has no way to spell one: the only expression that reads a driven value is a read of
     * the composer's block at a slot, and neither the block nor the slot exists when a surface is authored.
     * So a driven implicit is driven from the outside — a {@link Scalar.Param} on the {@link Surface.Translate},
     * {@link Surface.Rotate}, {@link Surface.Scale} or {@link Surface.Twist} around it, which is a domain
     * transform and lowers the same way. Recorded as a known limit; giving {@code Implicit} a parameter of its
     * own means a marker the compiler substitutes, and that is a decision about the IR rather than about this
     * screen.
     */
    private void screen(Expr e) {
        switch (e) {
            case Expr.PushConstantRead read -> {
                if (!params.issued(read.block())) {
                    throw new IllegalArgumentException(
                            "implicit surface reads member " + read.member() + " of a push-constant block the "
                                    + "composer did not issue; that read would silently resolve to whatever "
                                    + "member " + read.member() + " of the emitted block happens to be (the "
                                    + "camera). Drive it from outside instead: a Scalar.Param on the Surface "
                                    + "nodes around this implicit — raw IR cannot spell a parameter");
                }
            }
            case Expr.Binary b -> {
                screen(b.lhs());
                screen(b.rhs());
            }
            case Expr.Unary u -> screen(u.operand());
            case Expr.MathCall m -> m.args().forEach(this::screen);
            case Expr.VectorConstruct v -> v.components().forEach(this::screen);
            case Expr.VectorExtract v -> screen(v.vector());
            case Expr.Convert c -> screen(c.operand());
            case Expr.Bitcast b -> screen(b.operand());
            case Expr.MatrixTimesVector m -> screen(m.vector());
            case Expr.Call c -> c.arguments().forEach(this::screen);
            case Expr.BufferLoad b -> screen(b.index());
            case Expr.SampleTexture s -> screen(s.uv());
            default -> {
                // A leaf with nothing to say: a constant, a parameter, an interface or builtin read.
            }
        }
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
    private Field deform(Surface of, Expr q, Expr stretch) {
        Field inner = lower(of, q);
        return new Field(Fold.div(inner.distance(), stretch), inner.lipschitz(), inner.albedo(),
                inner.payload());
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
    private Expr twistStretch(Scalar rate, Scalar radius) {
        if (Scalar.allLit(rate, radius)) {
            double a = Math.abs(rate.literal()) * radius.literal();
            return Ir.f(0.5 * (a + Math.sqrt(a * a + 4)));
        }
        // Driven, so the same formula moves into the shader. It is evaluated once per field call rather than
        // per march step, and it is the price of a rate a slider can reach: the alternative — bounding the
        // stretch at the range's worst value instead — divides the field by that bound at every value, and a
        // twist rate whose range is generous would then march at a fraction of the step it could take.
        Expr a = Ir.mul(Ir.abs(expr(rate)), expr(radius));
        return Ir.mul(Ir.f(0.5), Ir.add(a, Ir.sqrt(Ir.add(Ir.mul(a, a), Ir.f(4.0)))));
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
    private Expr bendStretch(Scalar rate, Scalar extent) {
        if (Scalar.allLit(rate, extent)) {
            return Ir.f(1 + Math.abs(rate.literal()) * extent.literal());
        }
        return Ir.add(Ir.f(1.0), Ir.mul(Ir.abs(expr(rate)), expr(extent)));
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

        // Two candidate cell indices per repeated axis: the one this point falls in, and the one it leans
        // toward. Both are read by half the cells below, so both are named — 2^n copies of a round and a sign
        // is the same duplication one level down from the one bindPoint removes.
        Expr[][] cells = new Expr[active.size()][2];
        for (int a = 0; a < active.size(); a++) {
            Surface.Repeat.Axis axis = axes[active.get(a)];
            Expr t = bindScalar("t", Ir.div(Fold.component(p, active.get(a)), expr(axis.period())));
            Expr nearest = bindScalar("cell", Expr.MathCall.round(t));
            Expr neighbour = Ir.add(nearest, Expr.MathCall.sign(Ir.sub(t, nearest)));
            cells[a][0] = bindScalar("cell", clampCell(nearest, axis));
            cells[a][1] = bindScalar("cell", clampCell(neighbour, axis));
        }

        Coloured folded = null;
        for (int mask = 0; mask < (1 << active.size()); mask++) {
            Expr[] q = {Fold.component(p, 0), Fold.component(p, 1), Fold.component(p, 2)};
            for (int a = 0; a < active.size(); a++) {
                int i = active.get(a);
                q[i] = Ir.sub(q[i], Ir.mul(expr(axes[i].period()), cells[a][(mask >> a) & 1]));
            }
            Coloured cell = Coloured.of(lower(r.of(), bindPoint(Ir.v3(q[0], q[1], q[2]))));
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
        Expr t = bindScalar("t", Ir.div(Expr.MathCall.atan2(px, pz), Ir.f(sector)));
        Expr nearest = bindScalar("sector", Expr.MathCall.round(t));
        Expr[] sectors = {nearest, Ir.add(nearest, Expr.MathCall.sign(Ir.sub(t, nearest)))};

        Coloured folded = null;
        for (Expr index : sectors) {
            Expr theta = bindScalar("theta", Ir.mul(index, Ir.f(sector)));
            Expr c = bindScalar("c", Expr.MathCall.cos(theta));
            Expr s = bindScalar("s", Expr.MathCall.sin(theta));
            Coloured one = Coloured.of(lower(r.of(), bindPoint(Ir.v3(
                    Ir.sub(Ir.mul(c, px), Ir.mul(s, pz)),
                    py,
                    Ir.add(Ir.mul(s, px), Ir.mul(c, pz))))));
            folded = folded == null ? one : pick(folded, one, true);
        }
        return folded.field();
    }

    /**
     * Rodrigues' formula, evaluated in Java: the rotation matrix about a unit axis, row-major.
     * {@code R = I cos(t) + sin(t) [k]x + (1 - cos(t)) k k^T}.
     */
    private Expr[] rodrigues(double kx, double ky, double kz, Scalar angle) {
        if (angle.isLit()) {
            double c = Math.cos(angle.literal());
            double s = Math.sin(angle.literal());
            double t = 1 - c;
            double[] m = {
                    t * kx * kx + c, t * kx * ky - s * kz, t * kx * kz + s * ky,
                    t * kx * ky + s * kz, t * ky * ky + c, t * ky * kz - s * kx,
                    t * kx * kz - s * ky, t * ky * kz + s * kx, t * kz * kz + c};
            Expr[] out = new Expr[9];
            for (int i = 0; i < 9; i++) {
                out[i] = Ir.f(m[i]);
            }
            return out;
        }
        // A driven angle: the same formula, with the two trigonometric calls in the shader and the axis still
        // nine constants. Fold keeps the axis-aligned cases cheap here too — about +Y, six of the nine products
        // involve a zero component and disappear, leaving the swizzle and two multiplies per axis.
        //
        // Named, because the nine entries read them: unnamed, a turn about +Y emitted four cosines and two
        // sines for what is one of each. Same reasoning as bindPoint, one level down.
        Expr c = bindScalar("cos", Expr.MathCall.cos(expr(angle)));
        Expr s = bindScalar("sin", Expr.MathCall.sin(expr(angle)));
        Expr t = bindScalar("versin", Ir.sub(Ir.f(1.0), c));
        return new Expr[]{
                axisTerm(kx * kx, t, c), skew(kx * ky, t, -kz, s), skew(kx * kz, t, ky, s),
                skew(kx * ky, t, kz, s), axisTerm(ky * ky, t, c), skew(ky * kz, t, -kx, s),
                skew(kx * kz, t, -ky, s), skew(ky * kz, t, kx, s), axisTerm(kz * kz, t, c)};
    }

    /** A diagonal entry of Rodrigues' matrix: {@code k_i^2 * t + c}. */
    private Expr axisTerm(double kk, Expr t, Expr c) {
        return Fold.add(Fold.mul(Ir.f(kk), t), c);
    }

    /** An off-diagonal entry: {@code k_i k_j * t + k_l * s}, with the sign already in {@code k}. */
    private Expr skew(double kk, Expr t, double k, Expr s) {
        return Fold.add(Fold.mul(Ir.f(kk), t), Fold.mul(Ir.f(k), s));
    }

    /** {@code R^T p} — the domain turned opposite to the object, as three folded dot products. */
    private Expr rotate(Expr p, Expr[] m) {
        Expr[] out = new Expr[3];
        for (int i = 0; i < 3; i++) {
            Expr sum = null;
            for (int j = 0; j < 3; j++) {
                Expr term = Fold.mul(m[j * 3 + i], Fold.component(p, j));
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
        // The payload passes through a negation: carving does not change which shape is being carved.
        return new Field(Ir.neg(f.distance()), f.lipschitz(), f.albedo(), f.payload());
    }

    /** Two fields' distances combined pointwise, with no colour in it — {@code min}, or {@code max}. */
    private Field pick(Field a, Field b, boolean nearest) {
        double lipschitz = Math.max(a.lipschitz(), b.lipschitz());
        if (table == null) {
            Expr distance = nearest
                    ? Ir.min(a.distance(), b.distance())
                    : Ir.max(a.distance(), b.distance());
            return new Field(distance, lipschitz);
        }

        // Carrying a payload, so the two arms' distances are each read twice — once to combine, once to say
        // which arm won — and are named rather than written twice. This is the cost the second lowering mode
        // exists to keep off the display path.
        Expr da = bindScalar("d", a.distance());
        Expr db = bindScalar("d", b.distance());
        Expr distance = nearest ? Ir.min(da, db) : Ir.max(da, db);
        // 1 selects b: for a union when b is the nearer, for an intersection when b is the farther — the
        // same rule the colour selection follows, and for the same reason. Both ends are exactly 0 or 1, so
        // a mix of two slots is one of the two slots and never a number between them: a payload is a name,
        // and half of one name and half of another is not a name.
        Expr takeB = nearest ? Ir.step(db, da) : Ir.step(da, db);
        return new Field(distance, lipschitz, null, Ir.mix(a.payload(), b.payload(), takeB));
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
    private Field softBlend(Scalar sharpness, List<Field> fields, Blend blend) {
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

        // The signed sharpness is one constant for a literal, and a negation of a read for a driven soft-min —
        // never a multiply by a constant -1, which would leave a product of two constants nothing downstream
        // folds and so would stop a literal scene lowering to the IR it lowered to before.
        Expr k = expr(sharpness);
        Expr signedK = sharpness.isLit()
                ? Ir.f(blend.sign * sharpness.literal())
                : (blend == Blend.SOFT_MIN ? Ir.neg(k) : k);

        Expr sum = null;
        for (Field field : fields) {
            Expr term = Expr.MathCall.exp(Ir.mul(signedK, Ir.sub(field.distance(), extremum)));
            sum = sum == null ? term : Ir.add(sum, term);
        }
        Expr correction = Ir.div(Expr.MathCall.log(sum), k);
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

        // The payload follows the hard extremum too, and for a stronger reason than colour does: a fillet
        // between two shapes belongs to one of them, and a click in the middle of it has to answer with a
        // node. Blending two slots would answer with a number that names neither.
        Expr payload = null;
        if (table != null) {
            Field selected = fields.get(0);
            for (int i = 1; i < fields.size(); i++) {
                selected = pick(selected, fields.get(i), blend == Blend.SOFT_MIN);
            }
            payload = selected.payload();
        }
        return new Field(blended, lipschitz, albedo, payload);
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
        Expr q = Fold.sub(Ir.abs(Fold.sub(p, Ir.v3(expr(b.cx()), expr(b.cy()), expr(b.cz())))),
                Ir.v3(expr(b.hx()), expr(b.hy()), expr(b.hz())));
        Expr outside = Ir.length(Ir.max(q, Ir.v3(0, 0, 0)));
        Expr inside = Ir.min(Ir.max(Ir.x(q), Ir.max(Ir.y(q), Ir.z(q))), Ir.f(0.0));
        return Ir.add(outside, inside);
    }

    /**
     * Exact capsule: distance to the segment, less the radius.
     *
     * <p>The axis {@code b - a} is subtracted in Java where both ends are literal, because
     * {@code Ir.v3(bx - ax, …)} is one constant vector where {@code Fold.sub(b, a)} is a subtraction of two —
     * an identity {@link Fold} does not apply (it folds structure, never arithmetic on constants) and nothing
     * downstream does either. Driven, there is no choice, and the subtraction is three instructions outside the
     * march's inner work.
     */
    private Expr capsule(Expr p, Surface.Capsule c) {
        Expr a = Ir.v3(expr(c.ax()), expr(c.ay()), expr(c.az()));
        Expr ba = Scalar.allLit(c.ax(), c.ay(), c.az(), c.bx(), c.by(), c.bz())
                ? Ir.v3(c.bx().literal() - c.ax().literal(),
                        c.by().literal() - c.ay().literal(),
                        c.bz().literal() - c.az().literal())
                : Ir.sub(Ir.v3(expr(c.bx()), expr(c.by()), expr(c.bz())), a);
        Expr pa = Fold.sub(p, a);
        Expr h = Ir.clamp(Ir.div(Ir.dot(pa, ba), Ir.dot(ba, ba)), Ir.f(0.0), Ir.f(1.0));
        return Fold.sub(Ir.length(Fold.sub(pa, Fold.scale(ba, h))), expr(c.radius()));
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
            // Every cone of a stroke reports the stroke: one authored node, however many pieces the spine
            // was solved into, so clicking anywhere along it selects the thing that was drawn.
            Field one = new Field(distance, Field.EXACT, albedo, slot(s));
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
        return Fold.sub(Ir.length(Fold.sub(p, Ir.v3(expr(s.cx()), expr(s.cy()), expr(s.cz())))),
                expr(s.radius()));
    }

    /** Exact torus: distance in the (radial, axial) plane of the ring, less the tube radius. */
    private Expr torus(Expr p, Surface.Torus t) {
        Expr local = Fold.sub(p, Ir.v3(expr(t.cx()), expr(t.cy()), expr(t.cz())));
        Expr radial = Fold.sub(Ir.length(Ir.v2(Ir.x(local), Ir.z(local))), expr(t.major()));
        return Fold.sub(Ir.length(Ir.v2(radial, Ir.y(local))), expr(t.minor()));
    }

}
