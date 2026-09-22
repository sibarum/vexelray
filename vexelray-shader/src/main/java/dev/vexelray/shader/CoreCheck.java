package dev.vexelray.shader;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * A type check over a composed {@code core} module, run before it is lowered.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Because the alternative, measured, is a JVM crash. {@code Expr.Binary} takes an {@code lhs} and an
 * {@code rhs} and checks nothing, reporting {@code lhs.type()} as its own type — so
 * {@code mul(vec2, float)} constructs happily, claims to be a {@code vec2}, and lowers to an {@code OpFMul}
 * whose operands disagree. Nothing between authoring and the driver looks: composition accepted it, lowering
 * accepted it, and the first thing to notice was NVIDIA's shader compiler, which faulted inside
 * {@code nvgpucomp64.dll} and took the process with it. A hostile input would not have to try hard; an
 * ordinary typo got there on the first attempt.
 *
 * <p>The engine's premise is that shaders are <b>composed at runtime</b>, which means malformed IR is a
 * runtime possibility rather than a compile-time one, and the only place it can be caught before the driver
 * sees it is here. {@link ComposedShader}'s javadoc has described its output as "validated SPIR-V" since it was
 * written; this is what makes that true rather than aspirational.
 *
 * <h2>What it checks, and what it deliberately does not</h2>
 *
 * <p>Every rule here is one core's own types already determine, so a violation is unambiguously a fault and not
 * a style. Arithmetic and comparison operands must agree; a vector's components must sum to its arity in its
 * element type; an extract must be in range; a return must match its signature; a write must match what it
 * writes to; a call must match its callee; a condition must be a bool.
 *
 * <p>{@link Expr.MathCall} is <b>not</b> checked, and the omission is deliberate rather than pending. GLSL's
 * built-ins are genuinely polymorphic in ways core does not record — {@code mix} takes a scalar factor with
 * vector operands, {@code length} and {@code dot} return a scalar from vectors, {@code clamp} accepts both
 * shapes — so checking them needs a per-function arity and shape table, and a wrong table is worse than none:
 * it rejects correct shaders, gets switched off, and then catches nothing at all. Naming the gap is what stops
 * a later reader assuming coverage this does not have.
 *
 * <p>It is a check, not a proof. It cannot see a shader that is well-typed and wrong.
 */
public final class CoreCheck {

    private CoreCheck() {
    }

    /**
     * Every type fault in {@code module}, in the order found, or an empty list if it is well-typed.
     *
     * <p>All of them rather than the first, because a composer that got one type wrong has usually got the same
     * thing wrong in several places, and finding them one build at a time is the slow way.
     */
    public static List<String> faults(CoreModule module) {
        List<String> faults = new ArrayList<>();
        // functions() alone, and not also entryPoints(): CoreModule.addEntryPoint adds the entry function to
        // functions as well, so walking both reports every fault in an entry point twice. Caught by the tests
        // asserting an exact fault count rather than "at least one" — which is why they assert an exact count.
        for (Function function : module.functions()) {
            checkFunction(function, faults);
        }
        return faults;
    }

    /**
     * Throw if {@code module} has any type fault — the form {@link ComposedShader#lower} uses.
     *
     * @throws IllegalArgumentException listing every fault, because the message is the whole value of the check
     */
    public static void require(CoreModule module, String what) {
        List<String> faults = faults(module);
        if (!faults.isEmpty()) {
            StringBuilder message = new StringBuilder(what + " is not well-typed core IR, so lowering it would "
                    + "produce SPIR-V the driver may fault on rather than reject (" + faults.size()
                    + " fault" + (faults.size() == 1 ? "" : "s") + "):");
            for (String fault : faults) {
                message.append("\n  - ").append(fault);
            }
            throw new IllegalArgumentException(message.toString());
        }
    }

    private static void checkFunction(Function function, List<String> faults) {
        Type returns = function.signature().returnType();
        checkRegion(function.body(), function, returns, faults);
    }

    private static void checkRegion(Region region, Function in, Type returns, List<String> faults) {
        for (Statement statement : region.statements()) {
            checkStatement(statement, in, returns, faults);
        }
    }

    private static void checkStatement(Statement statement, Function in, Type returns, List<String> faults) {
        switch (statement) {
            case Statement.Return r -> {
                checkExpr(r.value(), in, faults);
                mustMatch(returns, r.value(), in, "return value", faults);
            }
            case Statement.ReturnVoid ignored -> {
            }
            case Statement.StoreResult s -> checkExpr(s.value(), in, faults);
            case Statement.BufferStore s -> {
                checkExpr(s.index(), in, faults);
                checkExpr(s.value(), in, faults);
            }
            // An atomic's operand types are checked where it is built, so only the operands' insides are left.
            case Statement.AtomicUpdate s -> {
                checkExpr(s.index(), in, faults);
                checkExpr(s.value(), in, faults);
            }
            case Statement.AtomicCompareExchange s -> {
                checkExpr(s.index(), in, faults);
                checkExpr(s.expected(), in, faults);
                checkExpr(s.desired(), in, faults);
            }
            case Statement.BuiltinWrite s -> checkExpr(s.value(), in, faults);
            case Statement.InterfaceWrite s -> {
                checkExpr(s.value(), in, faults);
                mustMatch(s.variable().type(), s.value(), in,
                        "write to interface variable '" + s.variable().name() + "'", faults);
            }
            case Statement.DeclareVar s -> {
                if (s.initializer() != null) {
                    checkExpr(s.initializer(), in, faults);
                    mustMatch(s.variable().type(), s.initializer(), in,
                            "initializer of local '" + s.variable().name() + "'", faults);
                }
            }
            case Statement.Assign s -> {
                checkExpr(s.value(), in, faults);
                mustMatch(s.variable().type(), s.value(), in,
                        "assignment to local '" + s.variable().name() + "'", faults);
            }
            case Statement.If s -> {
                checkExpr(s.condition(), in, faults);
                mustBeBool(s.condition(), in, "an if condition", faults);
                checkRegion(s.thenRegion(), in, returns, faults);
                if (s.elseRegion() != null) {
                    checkRegion(s.elseRegion(), in, returns, faults);
                }
            }
            case Statement.While s -> {
                checkExpr(s.condition(), in, faults);
                mustBeBool(s.condition(), in, "a while condition", faults);
                checkRegion(s.body(), in, returns, faults);
            }
        }
    }

    private static void checkExpr(Expr expr, Function in, List<String> faults) {
        switch (expr) {
            case Expr.Binary b -> {
                checkExpr(b.lhs(), in, faults);
                checkExpr(b.rhs(), in, faults);
                // The rule that would have caught the crash. Shifts are excluded because SPIR-V permits a shift
                // amount of a different width from the value being shifted, and requiring agreement there would
                // reject correct IR.
                if (b.op() != BinaryOp.SHIFT_LEFT && b.op() != BinaryOp.SHIFT_RIGHT
                        && !b.lhs().type().equals(b.rhs().type())) {
                    faults.add(where(in) + b.op() + " has operands of different types: "
                            + b.lhs().type() + " and " + b.rhs().type()
                            + " (core does not broadcast — use Ir.scale or Ir.broadcast)");
                }
            }
            case Expr.Unary u -> checkExpr(u.operand(), in, faults);
            case Expr.VectorConstruct v -> {
                int scalars = 0;
                for (Expr component : v.components()) {
                    checkExpr(component, in, faults);
                    scalars += arity(component.type());
                    Type element = elementOf(component.type());
                    if (!element.equals(v.type().component())) {
                        faults.add(where(in) + "a " + v.type() + " is built from a component of element type "
                                + element);
                    }
                }
                if (scalars != v.type().count()) {
                    faults.add(where(in) + "a " + v.type() + " is built from " + scalars + " components");
                }
            }
            case Expr.VectorExtract e -> {
                checkExpr(e.vector(), in, faults);
                if (!(e.vector().type() instanceof Type.Vector vector)) {
                    faults.add(where(in) + "a component is extracted from " + e.vector().type()
                            + ", which is not a vector");
                } else if (e.index() < 0 || e.index() >= vector.count()) {
                    faults.add(where(in) + "component " + e.index() + " is extracted from a " + vector);
                }
            }
            case Expr.Call c -> {
                List<Type> parameters = c.callee().signature().parameterTypes();
                if (parameters.size() != c.arguments().size()) {
                    faults.add(where(in) + "call to '" + c.callee().name() + "' passes "
                            + c.arguments().size() + " arguments to a function taking " + parameters.size());
                }
                for (int i = 0; i < c.arguments().size(); i++) {
                    Expr argument = c.arguments().get(i);
                    checkExpr(argument, in, faults);
                    if (i < parameters.size()) {
                        mustMatch(parameters.get(i), argument, in,
                                "argument " + i + " of call to '" + c.callee().name() + "'", faults);
                    }
                }
            }
            case Expr.MatrixTimesVector m -> {
                checkExpr(m.matrix(), in, faults);
                checkExpr(m.vector(), in, faults);
            }
            case Expr.SampleTexture s -> checkExpr(s.uv(), in, faults);
            case Expr.BufferLoad l -> checkExpr(l.index(), in, faults);
            case Expr.Bitcast b -> checkExpr(b.operand(), in, faults);
            case Expr.Convert c -> checkExpr(c.operand(), in, faults);
            // Not checked: see the class note on MathCall. Its arguments still are, because a mismatch nested
            // inside one is the same fault wherever it sits.
            case Expr.MathCall m -> m.args().forEach(a -> checkExpr(a, in, faults));
            default -> {
                // Leaves: constants, reads, params, invocation id, builtin and push-constant reads. Nothing to
                // check — their types are declared rather than derived.
            }
        }
    }

    private static void mustMatch(Type expected, Expr actual, Function in, String what, List<String> faults) {
        if (!expected.equals(actual.type())) {
            faults.add(where(in) + what + " is " + actual.type() + " where " + expected + " is required");
        }
    }

    private static void mustBeBool(Expr condition, Function in, String what, List<String> faults) {
        if (!Type.BOOL.equals(condition.type())) {
            faults.add(where(in) + what + " is " + condition.type() + " rather than bool");
        }
    }

    /** Components a type occupies when used to build a vector: a vector's arity, or one for a scalar. */
    private static int arity(Type type) {
        return type instanceof Type.Vector vector ? vector.count() : 1;
    }

    /** The scalar type inside a type: a vector's element, or the type itself. */
    private static Type elementOf(Type type) {
        return type instanceof Type.Vector vector ? vector.component() : type;
    }

    private static String where(Function in) {
        return "in '" + in.name() + "': ";
    }
}
