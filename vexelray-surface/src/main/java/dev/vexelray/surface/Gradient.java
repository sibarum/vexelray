package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Forward-mode symbolic differentiation of {@code core} IR with respect to the sample point {@link Ir#POINT}.
 * Given a scalar field expression it returns {@code grad f} as a {@code vec3} — itself {@code core} IR, so it
 * lowers to SPIR-V and to the CPU (Truffle) backend like everything else, and render==sim survives the pass.
 *
 * <p>This is the machinery behind {@link Normalize}: knowing {@code |grad f|} is what lets an arbitrary implicit
 * surface be rescaled into something a sphere-tracer can march (docs/surface-compiler.md §2.1).
 *
 * <p><b>Why symbolic, not finite differences.</b> The marcher already takes finite differences for the shading
 * normal, and could do the same here — but the step size that makes a good normal is a poor derivative, the error
 * is worst exactly where the field is interesting, and it costs six extra field evaluations <em>per march step</em>
 * rather than a one-off at compile time. Symbolic differentiation is exact and paid for once.
 *
 * <p><b>How.</b> {@code Expr} is a sealed interface of records, so the pass is an exhaustive {@code switch} — the
 * compiler, not a test, is what guarantees no case is forgotten. Each rule maps an expression to its
 * <em>tangent</em>: the derivative along one seed direction, carrying the same type as the expression it
 * differentiates (scalar to scalar, vector to vector). Running it with the three axis seeds gives the gradient.
 *
 * <p><b>Known cost, measured.</b> {@code grad f} runs <b>6x to 16x</b> the node count of {@code f} (sphere 6.4x,
 * torus 10.9x, box 13.0x, capsule 15.6x — the test pins the ceiling). Two effects compound: the three seeds each
 * re-emit the primal, and individual rules embed primal operands again on top of that (the quotient rule uses the
 * divisor twice, {@code length} needs both the vector and its length, and a {@code min}/{@code max} select needs
 * both operands to build the {@code step}). Nothing downstream does common-subexpression elimination, so all of
 * it survives to the GPU — and D12 is the record of what duplicated structure did to shader size once already.
 *
 * <p>The fix is one pass carrying all three partials at once, so the primal is emitted once; a mechanical rewrite
 * of this class. It is deferred rather than dismissed: the multiplier only bites once a normalised implicit is
 * actually in a shader, and D12's remedy (emit the field as one called function, not inlined at all eight use
 * sites) already keeps it from being multiplied again on top.
 *
 * <p>Expressions that are not differentiable with respect to the point — texture samples, buffer loads, calls into
 * other functions, local-variable reads — throw rather than silently returning something wrong. A surface that
 * cannot be differentiated cannot be safely normalised, and pretending otherwise produces holes in the render.
 */
public final class Gradient {

    private static final double LN2 = 0.6931471805599453;

    private Gradient() {
    }

    /**
     * {@code grad f} as a {@code vec3}, for a scalar {@code f} expressed in terms of {@link Ir#POINT}.
     *
     * @throws IllegalArgumentException if {@code f} is not a scalar float expression
     * @throws UnsupportedOperationException if {@code f} contains a construct with no derivative
     */
    public static Expr of(Expr f) {
        if (!Ir.F32.equals(f.type())) {
            throw new IllegalArgumentException("can only differentiate a scalar float field, got " + f.type());
        }
        return Ir.v3(
                directional(f, Ir.v3(1, 0, 0)),
                directional(f, Ir.v3(0, 1, 0)),
                directional(f, Ir.v3(0, 0, 1)));
    }

    /**
     * The derivative of {@code f} along {@code seed} — the directional derivative when {@code seed} is a unit
     * vector. Exposed because a march only ever needs the derivative along the ray, which is one third the work
     * of a full gradient; the empty-space-skipping pass will want it.
     */
    public static Expr directional(Expr f, Expr seed) {
        if (!Ir.V3.equals(seed.type())) {
            throw new IllegalArgumentException("seed must be a vec3, got " + seed.type());
        }
        return tangent(f, seed);
    }

    private static Expr tangent(Expr e, Expr seed) {
        return switch (e) {
            // Constants and anything uniform over the field's domain: no dependence on the point.
            case Expr.ConstFloat c -> Ir.zero(c.type());
            case Expr.ConstInt c -> Ir.zero(c.type());
            case Expr.InterfaceRead r -> Ir.zero(r.type());
            case Expr.PushConstantRead r -> Ir.zero(r.type());
            case Expr.BuiltinRead r -> Ir.zero(r.type());
            case Expr.InvocationId id -> Ir.zero(id.type());

            case Expr.ConstBool ignored -> throw undifferentiable("a boolean constant");

            // The point itself, and any other parameter (which is constant with respect to it).
            case Expr.Param p -> p.index() == 0 && Ir.V3.equals(p.type()) ? seed : Ir.zero(p.type());

            // Structure: differentiate componentwise / through the extract.
            case Expr.VectorConstruct vc -> {
                List<Expr> parts = new ArrayList<>(vc.components().size());
                for (Expr component : vc.components()) {
                    parts.add(tangent(component, seed));
                }
                yield new Expr.VectorConstruct(vc.type(), parts);
            }
            case Expr.VectorExtract ve -> new Expr.VectorExtract(tangent(ve.vector(), seed), ve.index());

            case Expr.Unary u -> switch (u.op()) {
                case NEGATE -> Fold.neg(tangent(u.operand(), seed));
                case NOT, LOGICAL_NOT -> throw undifferentiable("a logical operator");
            };

            case Expr.Binary b -> binary(b, seed);
            case Expr.MathCall m -> mathCall(m, seed);

            // A linear map with constant coefficients: differentiate the vector it acts on.
            case Expr.MatrixTimesVector mv ->
                    new Expr.MatrixTimesVector(mv.matrix(), tangent(mv.vector(), seed));

            // Float-to-float conversions are the identity on the derivative; anything landing in an integer is a
            // step function, whose derivative is zero almost everywhere and a lie at the steps.
            case Expr.Convert c -> c.type() instanceof Type.Float && c.operand().type() instanceof Type.Float
                    ? new Expr.Convert(tangent(c.operand(), seed), c.type())
                    : Ir.zero(c.type());

            case Expr.Read ignored -> throw undifferentiable(
                    "a local-variable read (differentiate the expression before binding it to a variable)");
            case Expr.Call ignored -> throw undifferentiable(
                    "a call into another function (inline the callee, or differentiate it separately)");
            case Expr.BufferLoad ignored -> throw undifferentiable("a buffer load");
            case Expr.SampleTexture ignored -> throw undifferentiable("a texture sample");
            case Expr.Bitcast ignored -> throw undifferentiable("a bitcast");
        };
    }

    private static Expr binary(Expr.Binary b, Expr seed) {
        Expr a = b.lhs();
        Expr c = b.rhs();
        Expr ta = tangent(a, seed);
        Expr tc = tangent(c, seed);
        return switch (b.op()) {
            case ADD -> Fold.add(ta, tc);
            case SUB -> Fold.sub(ta, tc);
            case MUL -> Fold.add(Fold.mul(ta, c), Fold.mul(a, tc));               // product rule
            case DIV -> Fold.div(Fold.sub(Fold.mul(ta, c), Fold.mul(a, tc)),      // quotient rule
                    Ir.mul(c, c));
            // x mod k, for a constant k, is a sawtooth: slope 1 between the jumps.
            case MOD -> Fold.isZero(tc) ? ta : throwFor("a modulus with a varying divisor");
            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT -> throw undifferentiable("a bitwise operator");
            case LESS_THAN, GREATER_THAN, EQUAL, LOGICAL_AND, LOGICAL_OR ->
                    throw undifferentiable("a comparison (its value is a boolean, not a distance)");
        };
    }

    private static Expr mathCall(Expr.MathCall m, Expr seed) {
        List<Expr> a = m.args();
        Type type = m.type();
        return switch (m.fn()) {
            // --- rewrites: express the function in terms of ones already handled, then differentiate that ---
            case CLAMP -> tangent(Ir.max(Ir.min(a.get(0), a.get(2)), a.get(1)), seed);
            case DISTANCE -> tangent(Ir.length(Ir.sub(a.get(0), a.get(1))), seed);
            case FMA -> tangent(Ir.add(Ir.mul(a.get(0), a.get(1)), a.get(2)), seed);
            case REFLECT -> tangent(Ir.sub(a.get(0),
                    Fold.scale(a.get(1), Ir.mul(Ir.f(2.0), Ir.dot(a.get(1), a.get(0))))), seed);
            case SMOOTHSTEP -> {
                Expr u = Ir.clamp(Ir.div(Ir.sub(a.get(2), a.get(0)), Ir.sub(a.get(1), a.get(0))),
                        Ir.f(0.0), Ir.f(1.0));
                yield tangent(Ir.mul(Ir.mul(u, u), Ir.sub(Ir.f(3.0), Ir.mul(Ir.f(2.0), u))), seed);
            }
            case MIX -> {   // a + (b - a) * t
                Expr lo = a.get(0);
                Expr hi = a.get(1);
                Expr t = a.get(2);
                yield tangent(Ir.add(lo, Ir.mul(Ir.sub(hi, lo), Ir.broadcast(t, type))), seed);
            }

            // --- selection: the derivative follows whichever branch is live ---
            // step(edge, x) is 1 where x >= edge, so step(a, b) selects b's branch exactly when a <= b.
            case MIN -> select(tangent(a.get(1), seed), tangent(a.get(0), seed), a.get(0), a.get(1), type);
            case MAX -> select(tangent(a.get(0), seed), tangent(a.get(1), seed), a.get(0), a.get(1), type);

            // --- piecewise-constant: zero almost everywhere ---
            case SIGN, STEP, FLOOR, CEIL, ROUND, ROUND_EVEN, TRUNC -> Ir.zero(type);
            case FRACT -> tangent(a.get(0), seed);   // slope 1 between the jumps

            // --- vector geometry ---
            case DOT -> {
                Expr ta = tangent(a.get(0), seed);
                Expr tb = tangent(a.get(1), seed);
                yield Fold.add(
                        Fold.isZero(ta) ? Ir.f(0.0) : Ir.dot(ta, a.get(1)),
                        Fold.isZero(tb) ? Ir.f(0.0) : Ir.dot(a.get(0), tb));
            }
            case CROSS -> {
                Expr ta = tangent(a.get(0), seed);
                Expr tb = tangent(a.get(1), seed);
                yield Fold.add(
                        Fold.isZero(ta) ? Ir.zero(type) : Ir.call(MathFn.CROSS, type, ta, a.get(1)),
                        Fold.isZero(tb) ? Ir.zero(type) : Ir.call(MathFn.CROSS, type, a.get(0), tb));
            }
            case LENGTH -> {
                Expr v = a.get(0);
                Expr tv = tangent(v, seed);
                yield Fold.isZero(tv) ? Ir.f(0.0) : Fold.div(Ir.dot(v, tv), Ir.length(v));
            }
            case NORMALIZE -> {
                // d(v/|v|) = (tv - v * dot(v, tv) / dot(v, v)) / |v|
                Expr v = a.get(0);
                Expr tv = tangent(v, seed);
                if (Fold.isZero(tv)) {
                    yield Ir.zero(type);
                }
                Expr radial = Fold.scale(v, Ir.div(Ir.dot(v, tv), Ir.dot(v, v)));
                yield Fold.scale(Ir.sub(tv, radial), Ir.div(Ir.f(1.0), Ir.length(v)));
            }

            // --- elementary functions: chain rule ---
            case ABS -> Fold.mul(Ir.call(MathFn.SIGN, type, a.get(0)), tangent(a.get(0), seed));
            case SQRT -> Fold.div(tangent(a.get(0), seed), Ir.mul(Ir.f(2.0), Ir.sqrt(a.get(0))));
            case INVERSE_SQRT -> Fold.mul(Ir.f(-0.5),
                    Fold.div(tangent(a.get(0), seed),
                            Ir.mul(a.get(0), Ir.sqrt(a.get(0)))));
            case EXP -> Fold.mul(m, tangent(a.get(0), seed));
            case EXP2 -> Fold.mul(Ir.mul(Ir.f(LN2), m), tangent(a.get(0), seed));
            case LOG -> Fold.div(tangent(a.get(0), seed), a.get(0));
            case LOG2 -> Fold.div(tangent(a.get(0), seed), Ir.mul(Ir.f(LN2), a.get(0)));
            case POW -> power(m, seed);
            case SIN -> Fold.mul(Ir.call(MathFn.COS, type, a.get(0)), tangent(a.get(0), seed));
            case COS -> Fold.neg(Fold.mul(Ir.call(MathFn.SIN, type, a.get(0)), tangent(a.get(0), seed)));
            case TAN -> Fold.div(tangent(a.get(0), seed), square(Ir.call(MathFn.COS, type, a.get(0))));
            case ASIN -> Fold.div(tangent(a.get(0), seed), Ir.sqrt(oneMinusSquare(a.get(0))));
            case ACOS -> Fold.neg(Fold.div(tangent(a.get(0), seed), Ir.sqrt(oneMinusSquare(a.get(0)))));
            case ATAN -> Fold.div(tangent(a.get(0), seed), Ir.add(Ir.f(1.0), square(a.get(0))));
            case ATAN2 -> {   // d atan2(y, x) = (x*ty - y*tx) / (x^2 + y^2)
                Expr yy = a.get(0);
                Expr xx = a.get(1);
                yield Fold.div(Fold.sub(Fold.mul(xx, tangent(yy, seed)), Fold.mul(yy, tangent(xx, seed))),
                        Ir.add(square(xx), square(yy)));
            }
            case SINH -> Fold.mul(Ir.call(MathFn.COSH, type, a.get(0)), tangent(a.get(0), seed));
            case COSH -> Fold.mul(Ir.call(MathFn.SINH, type, a.get(0)), tangent(a.get(0), seed));
            case TANH -> Fold.mul(Ir.sub(Ir.f(1.0), square(m)), tangent(a.get(0), seed));
            case ASINH -> Fold.div(tangent(a.get(0), seed), Ir.sqrt(Ir.add(square(a.get(0)), Ir.f(1.0))));
            case ACOSH -> Fold.div(tangent(a.get(0), seed), Ir.sqrt(Ir.sub(square(a.get(0)), Ir.f(1.0))));
            case ATANH -> Fold.div(tangent(a.get(0), seed), oneMinusSquare(a.get(0)));
            case RADIANS -> Fold.mul(Ir.f(Math.PI / 180.0), tangent(a.get(0), seed));
            case DEGREES -> Fold.mul(Ir.f(180.0 / Math.PI), tangent(a.get(0), seed));

            case REFRACT, FACE_FORWARD -> throw undifferentiable("'" + m.fn() + "'");
        };
    }

    /**
     * {@code pow(base, exponent)}. With a constant exponent this is the schoolbook power rule; with a varying one
     * it needs {@code log(base)}, which is only defined for a positive base — so the two cases are kept apart
     * rather than always emitting the general form.
     */
    private static Expr power(Expr.MathCall m, Expr seed) {
        Expr base = m.args().get(0);
        Expr exponent = m.args().get(1);
        Expr tBase = tangent(base, seed);
        Expr tExponent = tangent(exponent, seed);
        Type type = m.type();
        Expr powerRule = Fold.mul(
                Fold.mul(exponent, Ir.call(MathFn.POW, type, base, Ir.sub(exponent, Ir.f(1.0)))),
                tBase);
        if (Fold.isZero(tExponent)) {
            return powerRule;
        }
        return Fold.add(powerRule, Fold.mul(Fold.mul(m, Ir.call(MathFn.LOG, type, base)), tExponent));
    }

    /** {@code whenGreater} where {@code a > b}, {@code whenAtMost} where {@code a <= b} — a branch-free select. */
    private static Expr select(Expr whenGreater, Expr whenAtMost, Expr a, Expr b, Type type) {
        if (whenGreater.equals(whenAtMost)) {
            return whenAtMost;   // both branches agree; no need to select between them
        }
        return Ir.mix(whenGreater, whenAtMost, Ir.broadcast(Ir.step(a, b), type));
    }

    private static Expr square(Expr e) {
        return Ir.mul(e, e);
    }

    private static Expr oneMinusSquare(Expr e) {
        return Ir.sub(Ir.f(1.0), square(e));
    }

    private static Expr throwFor(String what) {
        throw undifferentiable(what);
    }

    private static UnsupportedOperationException undifferentiable(String what) {
        return new UnsupportedOperationException(
                "cannot differentiate " + what + " with respect to the sample point; a surface that cannot be "
                        + "differentiated cannot be normalised into a marchable distance field");
    }
}
