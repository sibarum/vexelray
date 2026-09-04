package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;

import java.util.List;

/**
 * A direct interpreter for the subset of {@code core} IR this module emits — test scaffolding only.
 *
 * <p>SupirVast can already run {@code core} on the CPU through its Truffle backend, and that is the real
 * render==sim path. This is deliberately not that: it is a few dozen lines of obvious arithmetic with no JIT, no
 * GraalVM requirement, and no shared code with the compiler under test, so a gradient can be checked against
 * finite differences in a plain unit test. Comparing symbolic derivatives to numeric ones through the same
 * evaluator is still a real check — an error in {@link Gradient} does not cancel, because only one side of the
 * comparison goes through it.
 *
 * <p>Values are {@code double[]}: length 1 for a scalar, 2–4 for a vector. Doubles, not floats, so the finite
 * differences are limited by the step size rather than by the arithmetic.
 */
final class Eval {

    private Eval() {
    }

    /** Evaluate a scalar expression at a point. */
    static double at(Expr e, double x, double y, double z) {
        double[] v = eval(e, Env.at(x, y, z));
        if (v.length != 1) {
            throw new IllegalArgumentException("expected a scalar, got " + v.length + " components");
        }
        return v[0];
    }

    /** Evaluate a vector expression at a point. */
    static double[] vecAt(Expr e, double x, double y, double z) {
        return eval(e, Env.at(x, y, z));
    }

    /** Central-difference gradient of a scalar expression — the reference {@link Gradient} is checked against. */
    static double[] numericGradient(Expr f, double x, double y, double z, double h) {
        return new double[]{
                (at(f, x + h, y, z) - at(f, x - h, y, z)) / (2 * h),
                (at(f, x, y + h, z) - at(f, x, y - h, z)) / (2 * h),
                (at(f, x, y, z + h) - at(f, x, y, z - h)) / (2 * h)};
    }

    /**
     * A point, and any locals a colour program declared on the way to its answer.
     *
     * <p>The compiler binds subexpressions rather than repeating them (see {@link Lets}), so evaluating a
     * colour means running its declarations first and then reading them by name — which is what a shader does
     * too. Without this the interpreter meets a {@code Read} of a local and has nothing to look it up in.
     */
    record Env(double[] point, java.util.Map<dev.supirvast.vastir.core.LocalVar, double[]> locals) {
        static Env at(double x, double y, double z) {
            return new Env(new double[]{x, y, z}, java.util.Map.of());
        }
    }

    /** Evaluate a colour: its declarations in order, then the expression that reads them. */
    static double[] withLets(Expr e, java.util.List<dev.supirvast.vastir.core.Statement> lets,
                             double x, double y, double z) {
        java.util.Map<dev.supirvast.vastir.core.LocalVar, double[]> locals = new java.util.LinkedHashMap<>();
        Env env = new Env(new double[]{x, y, z}, locals);
        for (dev.supirvast.vastir.core.Statement s : lets) {
            var d = (dev.supirvast.vastir.core.Statement.DeclareVar) s;
            locals.put(d.variable(), eval(d.initializer(), env));
        }
        return eval(e, env);
    }

    private static double[] eval(Expr e, Env p) {
        return switch (e) {
            case Expr.ConstFloat c -> new double[]{c.value()};
            case Expr.ConstInt c -> new double[]{c.value()};
            case Expr.Read r -> p.locals().get(r.variable()).clone();
            case Expr.Param param -> param.index() == 0 ? p.point().clone() : unsupported(e);
            case Expr.Unary u -> map(eval(u.operand(), p), v -> switch (u.op()) {
                case NEGATE -> -v;
                default -> throw new UnsupportedOperationException(u.op().toString());
            });
            case Expr.Binary b -> zip(eval(b.lhs(), p), eval(b.rhs(), p), (l, r) -> switch (b.op()) {
                case ADD -> l + r;
                case SUB -> l - r;
                case MUL -> l * r;
                case DIV -> l / r;
                case MOD -> l % r;
                default -> throw new UnsupportedOperationException(b.op().toString());
            });
            case Expr.VectorConstruct vc -> {
                double[] out = new double[vc.components().size()];
                for (int i = 0; i < out.length; i++) {
                    double[] component = eval(vc.components().get(i), p);
                    out[i] = component[0];
                }
                yield out;
            }
            case Expr.VectorExtract ve -> new double[]{eval(ve.vector(), p)[ve.index()]};
            case Expr.MathCall m -> math(m, p);
            default -> unsupported(e);
        };
    }

    private static double[] math(Expr.MathCall m, Env p) {
        List<Expr> args = m.args();
        double[] a = eval(args.get(0), p);
        return switch (m.fn()) {
            case LENGTH -> new double[]{length(a)};
            case NORMALIZE -> map(a, v -> v / length(a));
            case DOT -> new double[]{dot(a, eval(args.get(1), p))};
            case DISTANCE -> new double[]{length(zip(a, eval(args.get(1), p), (l, r) -> l - r))};
            case CROSS -> {
                double[] b = eval(args.get(1), p);
                yield new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            }
            case REFLECT -> {
                double[] n = eval(args.get(1), p);
                double d = 2 * dot(n, a);
                yield zip(a, n, (i, nn) -> i - d * nn);
            }

            // binary, componentwise
            case MIN -> zip(a, eval(args.get(1), p), Math::min);
            case MAX -> zip(a, eval(args.get(1), p), Math::max);
            case POW -> zip(a, eval(args.get(1), p), Math::pow);
            case ATAN2 -> zip(a, eval(args.get(1), p), Math::atan2);
            case STEP -> zip(a, eval(args.get(1), p), (edge, v) -> v >= edge ? 1.0 : 0.0);

            // ternary, componentwise
            case CLAMP -> {
                double[] lo = eval(args.get(1), p);
                double[] hi = eval(args.get(2), p);
                double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    out[i] = Math.min(Math.max(a[i], at(lo, i)), at(hi, i));
                }
                yield out;
            }
            case MIX -> {
                double[] b = eval(args.get(1), p);
                double[] t = eval(args.get(2), p);
                double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    out[i] = a[i] + (b[i] - a[i]) * at(t, i);
                }
                yield out;
            }
            case SMOOTHSTEP -> {
                double[] e1 = eval(args.get(1), p);
                double[] x = eval(args.get(2), p);
                double[] out = new double[x.length];
                for (int i = 0; i < out.length; i++) {
                    double u = Math.min(Math.max((x[i] - at(a, i)) / (at(e1, i) - at(a, i)), 0.0), 1.0);
                    out[i] = u * u * (3 - 2 * u);
                }
                yield out;
            }
            case FMA -> {
                double[] b = eval(args.get(1), p);
                double[] c = eval(args.get(2), p);
                double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    out[i] = a[i] * b[i] + c[i];
                }
                yield out;
            }

            // unary, componentwise
            case ABS -> map(a, Math::abs);
            case SIGN -> map(a, Math::signum);
            case SQRT -> map(a, Math::sqrt);
            case INVERSE_SQRT -> map(a, v -> 1.0 / Math.sqrt(v));
            case EXP -> map(a, Math::exp);
            case LOG -> map(a, Math::log);
            case EXP2 -> map(a, v -> Math.pow(2, v));
            case LOG2 -> map(a, v -> Math.log(v) / Math.log(2));
            case SIN -> map(a, Math::sin);
            case COS -> map(a, Math::cos);
            case TAN -> map(a, Math::tan);
            case ASIN -> map(a, Math::asin);
            case ACOS -> map(a, Math::acos);
            case ATAN -> map(a, Math::atan);
            case SINH -> map(a, Math::sinh);
            case COSH -> map(a, Math::cosh);
            case TANH -> map(a, Math::tanh);
            case ASINH -> map(a, v -> Math.log(v + Math.sqrt(v * v + 1)));
            case ACOSH -> map(a, v -> Math.log(v + Math.sqrt(v * v - 1)));
            case ATANH -> map(a, v -> 0.5 * Math.log((1 + v) / (1 - v)));
            case FLOOR -> map(a, Math::floor);
            case CEIL -> map(a, Math::ceil);
            case ROUND, ROUND_EVEN -> map(a, Math::rint);
            case TRUNC -> map(a, v -> (double) (long) v);
            case FRACT -> map(a, v -> v - Math.floor(v));
            case RADIANS -> map(a, Math::toRadians);
            case DEGREES -> map(a, Math::toDegrees);

            default -> throw new UnsupportedOperationException("no test evaluator for " + m.fn());
        };
    }

    /** Component {@code i}, or the single value if the operand is a broadcast scalar. */
    private static double at(double[] v, int i) {
        return v.length == 1 ? v[0] : v[i];
    }

    private static double length(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static double[] map(double[] v, java.util.function.DoubleUnaryOperator op) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = op.applyAsDouble(v[i]);
        }
        return out;
    }

    private static double[] zip(double[] a, double[] b, java.util.function.DoubleBinaryOperator op) {
        int n = Math.max(a.length, b.length);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = op.applyAsDouble(at(a, i), at(b, i));
        }
        return out;
    }

    private static double[] unsupported(Expr e) {
        throw new UnsupportedOperationException("no test evaluator for " + e.getClass().getSimpleName());
    }
}
