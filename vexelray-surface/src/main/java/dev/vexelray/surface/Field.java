package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.List;

/**
 * A lowered surface: the distance expression, plus what the compiler knows about how fast it changes.
 *
 * <p>The bound is the whole point. Sphere-tracing steps by the field's own value, so it converges only if the
 * field never reports more distance than there really is — which holds exactly when {@code |grad d| <= 1}. Every
 * primitive and combinator in {@link Surface} preserves that; an {@link Surface.Implicit} does not, and is
 * normalised on the way in. Carrying the bound explicitly is what lets the compiler tell those cases apart and
 * charge only the second one (docs/surface-compiler.md §3).
 *
 * <h2>Colour</h2>
 *
 * <p>A field may also carry an <b>albedo</b> expression — the surface's linear-RGB colour at the same point.
 * It is {@code null} unless something in the tree actually specified a colour, and that absence is load-bearing:
 * a scene of uncoloured surfaces lowers to precisely the IR it lowered to before colour existed, and the
 * composer emits precisely the shader it emitted before. Generality costs nothing when it is not used, here as
 * everywhere else in this module.
 *
 * <p>Note where the cost lands when it <em>is</em> used. The distance is called once per march step, tens of
 * times per pixel; the albedo is called <b>once</b>, at the hit point, after the march has finished. So the
 * selection arithmetic that picks a colour out of a union — which is roughly a second copy of the distance
 * field — is paid once per pixel rather than per step. That asymmetry is why colour can be a separate function
 * rather than a channel threaded through the march.
 *
 * @param distance  signed distance at {@link Ir#POINT}: negative inside, zero on the surface
 * @param lipschitz an upper bound on {@code |grad distance|} — {@code 1.0} for a true distance field, larger for
 *                  a field that would overshoot, {@link Double#POSITIVE_INFINITY} when nothing is known
 * @param albedo      linear-RGB colour at {@link Ir#POINT} ({@code vec3}), or {@code null} if nothing in the
 *                    surface specified one. May contain {@link Ir#SCENE_ALBEDO} where a surface had no colour
 *                    of its own but shares a combinator with one that did, and may read locals declared by
 *                    {@code albedoLets}
 * @param albedoLets  declarations {@code albedo} reads, in the order they must be emitted. Empty unless the
 *                    colour had something to select between; see {@link Lets} for why selecting without them
 *                    grew as the square of the child count
 * @param lets        declarations {@code distance} reads, in the order they must be emitted — the transformed
 *                    points a domain operator computes once and its child reads (P1). Empty for a surface with
 *                    no domain transform in it, which is why a bare primitive still lowers to a bare
 *                    expression. <b>A field with these is a program, not an expression</b>: evaluating
 *                    {@link #distance()} alone would meet a read of a local with nothing to read
 * @param helpers     functions {@code distance} calls, callees before callers, each emitted once and called
 *                    from every site that shares it. A repeated child is one of these rather than 2ⁿ copies,
 *                    and so is any subtree the author used twice. Whoever assembles the module must add them
 *                    to it — {@code SdfComposer} does
 */
public record Field(Expr distance, double lipschitz, Expr albedo, List<Statement> albedoLets,
                    List<Statement> lets, List<Function> helpers, Expr payload) {

    /** The bound a true signed-distance field carries. */
    public static final double EXACT = 1.0;

    /** The bound for an expression the compiler cannot vouch for — an un-normalised implicit. */
    public static final double UNKNOWN = Double.POSITIVE_INFINITY;

    public Field {
        if (distance == null) {
            throw new IllegalArgumentException("distance expression must not be null");
        }
        if (!Ir.F32.equals(distance.type())) {
            throw new IllegalArgumentException("a distance must be a scalar float, got " + distance.type());
        }
        if (Double.isNaN(lipschitz) || lipschitz <= 0) {
            throw new IllegalArgumentException("lipschitz bound must be positive, got " + lipschitz);
        }
        if (albedo != null && !Ir.V3.equals(albedo.type())) {
            throw new IllegalArgumentException("an albedo must be a vec3, got " + albedo.type());
        }
        albedoLets = albedoLets == null ? List.of() : List.copyOf(albedoLets);
        if (albedo == null && !albedoLets.isEmpty()) {
            throw new IllegalArgumentException("declarations with no colour to read them");
        }
        lets = lets == null ? List.of() : List.copyOf(lets);
        helpers = helpers == null ? List.of() : List.copyOf(helpers);
        if (payload != null && !Ir.F32.equals(payload.type())) {
            throw new IllegalArgumentException("a payload must be a scalar float, got " + payload.type());
        }
    }

    /** A colourless field — the shape only, which is what every surface but a painted one produces. */
    public Field(Expr distance, double lipschitz) {
        this(distance, lipschitz, null, List.of(), List.of(), List.of(), null);
    }

    /** A field whose colour needs no declarations — a single flat colour, and nothing to select between. */
    public Field(Expr distance, double lipschitz, Expr albedo) {
        this(distance, lipschitz, albedo, List.of(), List.of(), List.of(), null);
    }

    /** A field carrying a colour and the declarations it reads, and no program of its own yet. */
    public Field(Expr distance, double lipschitz, Expr albedo, List<Statement> albedoLets) {
        this(distance, lipschitz, albedo, albedoLets, List.of(), List.of(), null);
    }

    /** A field carrying a distance and the payload alongside it — what the identity lowering produces. */
    public Field(Expr distance, double lipschitz, Expr albedo, Expr payload) {
        this(distance, lipschitz, albedo, List.of(), List.of(), List.of(), payload);
    }

    /** Whether this field carries a payload channel — false unless it was lowered for one. */
    public boolean hasPayload() {
        return payload != null;
    }

    /** A field the compiler knows to be a true distance field. */
    public static Field exact(Expr distance) {
        return new Field(distance, EXACT);
    }

    /** Whether anything in the surface specified a colour. */
    public boolean hasAlbedo() {
        return albedo != null;
    }

    /** This field with a different albedo — how a combinator rebuilds one around its children's colours. */
    public Field withAlbedo(Expr albedo) {
        return new Field(distance, lipschitz, albedo, albedoLets, lets, helpers, payload);
    }

    /** This field carrying the declarations its colour reads — attached once, when lowering finishes. */
    public Field withAlbedoLets(List<Statement> albedoLets) {
        return new Field(distance, lipschitz, albedo, albedoLets, lets, helpers, payload);
    }

    /**
     * This field carrying its own program — the point declarations its distance reads and the functions it
     * calls. Attached once, when lowering finishes, for the same reason {@link #withAlbedoLets} is: the
     * accumulators live on the compiler while the tree is being walked, and become part of the answer at the
     * end.
     */
    public Field withProgram(List<Statement> lets, List<Function> helpers) {
        return new Field(distance, lipschitz, albedo, albedoLets, lets, helpers, payload);
    }

    /** Whether this can be sphere-traced as-is without overshooting. */
    public boolean isMarchable() {
        return lipschitz <= EXACT + 1e-9;
    }

    /**
     * This field's distance as one self-contained expression evaluated at some other point — the field
     * relocated into a caller's frame. Lets a compiled surface be dropped into IR that was authored around a
     * different variable, which is how it reaches the research harness and anything else that names its own
     * sample point.
     *
     * <p><b>This is the inlined form, and it pays what P1 stopped paying.</b> A caller asking for a bare
     * expression is asking for one with no declarations in it, so every point this field bound to a local is
     * substituted back into each of its uses. For a stack of domain operators that is exactly the duplication
     * {@link #lets} exists to remove — a Twist over a Repeat went from four megabytes to a few kilobytes by
     * not doing it. Prefer {@link #asFunction}, which keeps the program whole; use this only where an
     * expression is genuinely the required shape.
     *
     * <p>Calls are expanded too, for the same reason: a caller that wanted a call would have taken
     * {@link #asFunction}. What comes back is the field as one tree — <b>precisely the tree this compiler
     * emitted before P1 shared anything</b>, which is what makes it the reference the shared form is
     * differentially tested against, and what keeps {@link Gradient} able to differentiate a compiled field.
     *
     * <p><b>For a nest of repeats this does not fit in memory</b>, and that is the measurement rather than a
     * caveat: expansion is multiplicative, so a three-axis repeat under a polar repeat is sixteen copies of
     * whatever is under it, and the eighth rung of the ladder in {@code SharingTest} expands past anything a
     * heap holds while its shared form is nineteen kilobytes of SPIR-V. Ask for this only where the surface
     * is small or the caller genuinely cannot take a function.
     */
    public Expr at(Expr point) {
        return Substitute.point(inlined(), point);
    }

    /**
     * The distance as one self-contained expression: declarations substituted back into their uses, and every
     * call replaced by its callee's body at the argument.
     */
    private Expr inlined() {
        return expandCalls(inlineLets(distance, lets));
    }

    private static Expr inlineLets(Expr e, List<Statement> declarations) {
        Expr expanded = e;
        // Backwards, so a later declaration reading an earlier one is expanded before that earlier one is
        // substituted into it. Forwards would leave reads of the earlier locals inside the expansion.
        for (int i = declarations.size() - 1; i >= 0; i--) {
            Statement.DeclareVar declaration = (Statement.DeclareVar) declarations.get(i);
            expanded = Substitute.local(expanded, declaration.variable(), declaration.initializer());
        }
        return expanded;
    }

    /** Every call replaced by its callee's own inlined body, evaluated at the call's argument. */
    private static Expr expandCalls(Expr e) {
        return switch (e) {
            case Expr.Call c -> {
                List<Statement> body = c.callee().body().statements();
                Statement.Return returned = (Statement.Return) body.get(body.size() - 1);
                Expr inner = inlineLets(returned.value(), body.subList(0, body.size() - 1));
                yield Substitute.point(expandCalls(inner), expandCalls(c.arguments().get(0)));
            }
            case Expr.Binary b -> new Expr.Binary(b.op(), expandCalls(b.lhs()), expandCalls(b.rhs()));
            case Expr.Unary u -> new Expr.Unary(u.op(), expandCalls(u.operand()));
            case Expr.MathCall m -> new Expr.MathCall(m.fn(), m.type(), m.args().stream()
                    .map(Field::expandCalls).toList());
            case Expr.VectorConstruct v -> new Expr.VectorConstruct(v.type(), v.components().stream()
                    .map(Field::expandCalls).toList());
            case Expr.VectorExtract v -> new Expr.VectorExtract(expandCalls(v.vector()), v.index());
            case Expr.Convert c -> new Expr.Convert(expandCalls(c.operand()), c.type());
            default -> e;
        };
    }

    /**
     * This field as a standalone {@code float sdf(vec3)} function — the form both backends consume: the fragment
     * shader calls it (once, rather than inlining the field at all eight of its use sites — D12), and the CPU
     * side lowers the same function to query the same surface. One definition, two targets: render == sim.
     *
     * <p>The body is this field's {@link #lets} and then its distance. It may call {@link #helpers}, which the
     * caller must add to the same module — a function that is called but never defined is the one failure this
     * split can produce, and it produces it at pipeline creation rather than here.
     */
    public Function asFunction(String name) {
        List<Statement> body = new java.util.ArrayList<>(lets.size() + 1);
        body.addAll(lets);
        body.add(new Statement.Return(distance));
        return new Function(name, new Type.FunctionType(Ir.F32, List.of(Ir.V3)), new Region(body));
    }

    /**
     * This field as {@code vec2 f(vec3)} — the distance in {@code x} and the payload in {@code y}.
     *
     * <p>One function returning both rather than two functions, because <b>the payload is chosen by the
     * distance</b>: which child owns a point is decided by which child is nearer, so the comparison that
     * picks the distance is the comparison that picks the payload. Two functions would run every comparison
     * twice and, worse, leave two programs free to disagree about which child won.
     *
     * <p>Only a field lowered for a payload has one, and that split is the whole design: the display path
     * calls {@link #asFunction} and pays nothing for a channel it does not read. See
     * {@link SurfaceCompiler#compileWithPayload}.
     *
     * @throws IllegalStateException if this field carries no payload; check {@link #hasPayload()} first
     */
    public Function asPayloadFunction(String name) {
        if (payload == null) {
            throw new IllegalStateException(
                    "this field carries no payload; compile it with SurfaceCompiler.compileWithPayload");
        }
        List<Statement> body = new java.util.ArrayList<>(lets.size() + 1);
        body.addAll(lets);
        body.add(new Statement.Return(Ir.v2(distance, payload)));
        return new Function(name, new Type.FunctionType(Ir.V2, List.of(Ir.V3)), new Region(body));
    }

    /**
     * The emitted size: every node of the program that reaches the module, counting a shared function once
     * because that is how many times it is emitted.
     *
     * <p>Half of P1's instrumentation, and the half a budget is set on. The other half is
     * {@link #evaluations()}, which counts the same program the way it <em>runs</em>.
     */
    public int nodes() {
        int total = size(distance);
        for (Statement let : lets) {
            total += size(((Statement.DeclareVar) let).initializer()) + 1;
        }
        for (Function helper : helpers) {
            for (Statement statement : helper.body().statements()) {
                total += switch (statement) {
                    case Statement.DeclareVar d -> size(d.initializer()) + 1;
                    case Statement.Return r -> r.value() == null ? 1 : size(r.value());
                    default -> 1;
                };
            }
        }
        return total;
    }

    /**
     * The executed size: the same program with every call expanded by the number of times it is called.
     *
     * <p>The second of P1's two budgets, and the one that says what sharing did <em>not</em> buy. Emitting a
     * repeated child once and calling it 2ⁿ times makes the module additive in the operator count; it leaves
     * the work multiplicative, because the neighbour-cell {@code min} really does evaluate the child 2ⁿ times
     * per query. Size is what the driver compiles and this is what the GPU runs, and P1 moves cost from the
     * first to the second deliberately. The ratio against {@link #nodes()} is what sharing bought.
     */
    public long evaluations() {
        // Each function's cost is worked out once and reused, which is the difference between counting the
        // executed program and building it: the count of a nest of repeats is astronomical, and a walk that
        // re-entered every callee at every call site would take as long as the number it was computing.
        java.util.Map<Function, Long> costs = new java.util.IdentityHashMap<>();
        long total = executed(distance, costs);
        for (Statement let : lets) {
            total += executed(((Statement.DeclareVar) let).initializer(), costs) + 1;
        }
        return total;
    }

    /** Nodes of {@code e}, with each call site charged the whole cost of its callee. */
    private static long executed(Expr e, java.util.Map<Function, Long> costs) {
        long here = 1;
        return switch (e) {
            case Expr.Call c -> {
                long args = 0;
                for (Expr argument : c.arguments()) {
                    args += executed(argument, costs);
                }
                // Looked up and put back rather than computeIfAbsent, because working out one callee's cost
                // walks its body and meets the calls it makes, which would be a nested write to the map.
                Long cached = costs.get(c.callee());
                if (cached == null) {
                    long body = 0;
                    for (Statement statement : c.callee().body().statements()) {
                        body += switch (statement) {
                            case Statement.DeclareVar d -> executed(d.initializer(), costs) + 1;
                            case Statement.Return r -> r.value() == null ? 1 : executed(r.value(), costs);
                            default -> 1;
                        };
                    }
                    cached = body;
                    costs.put(c.callee(), cached);
                }
                yield here + args + cached;
            }
            case Expr.Binary b -> here + executed(b.lhs(), costs) + executed(b.rhs(), costs);
            case Expr.Unary u -> here + executed(u.operand(), costs);
            case Expr.MathCall m -> {
                long sum = here;
                for (Expr argument : m.args()) {
                    sum += executed(argument, costs);
                }
                yield sum;
            }
            case Expr.VectorConstruct v -> {
                long sum = here;
                for (Expr component : v.components()) {
                    sum += executed(component, costs);
                }
                yield sum;
            }
            case Expr.VectorExtract v -> here + executed(v.vector(), costs);
            case Expr.Convert c -> here + executed(c.operand(), costs);
            default -> here;
        };
    }

    /** Nodes of {@code e}, counting a call as one node — the callee is emitted once, elsewhere. */
    private static int size(Expr e) {
        int here = 1;
        return switch (e) {
            case Expr.Call c -> {
                int sum = here;
                for (Expr argument : c.arguments()) {
                    sum += size(argument);
                }
                yield sum;
            }
            case Expr.Binary b -> here + size(b.lhs()) + size(b.rhs());
            case Expr.Unary u -> here + size(u.operand());
            case Expr.MathCall m -> {
                int sum = here;
                for (Expr argument : m.args()) {
                    sum += size(argument);
                }
                yield sum;
            }
            case Expr.VectorConstruct v -> {
                int sum = here;
                for (Expr component : v.components()) {
                    sum += size(component);
                }
                yield sum;
            }
            case Expr.VectorExtract v -> here + size(v.vector());
            case Expr.Convert c -> here + size(c.operand());
            default -> here;
        };
    }

    /**
     * The albedo as a standalone {@code vec3 albedo(vec3)} function, with {@code fallback} filled in wherever a
     * surface without a colour of its own could be the one showing.
     *
     * <p>A function for the same reason the distance is one — it is called from more than one place and inlining
     * it would duplicate a tree that is already the size of the field. Unlike the distance, it is called once
     * per pixel rather than once per march step.
     *
     * <p><b>The point declarations come first, and they are the distance program's own.</b> Choosing a colour
     * out of a union means comparing the children's distances, so the colour program holds copies of distance
     * expressions — and since P1 those may read a local that a domain operator bound. Two functions cannot
     * share a local, so this one declares them again. They are pure functions of the sample point, so a second
     * copy is a second evaluation and never a second answer, and it is paid once per pixel rather than once
     * per step.
     *
     * @throws IllegalStateException if this field carries no colour; check {@link #hasAlbedo()} first
     */
    public Function albedoFunction(String name, Expr fallback) {
        if (albedo == null) {
            throw new IllegalStateException("this field carries no colour");
        }
        List<Statement> body = new java.util.ArrayList<>(lets.size() + albedoLets.size() + 1);
        body.addAll(lets);
        for (Statement let : albedoLets) {
            body.add(Substitute.sceneAlbedo(let, fallback));
        }
        body.add(new Statement.Return(Substitute.sceneAlbedo(albedo, fallback)));
        return new Function(name, new Type.FunctionType(Ir.V3, List.of(Ir.V3)), new Region(body));
    }
}
