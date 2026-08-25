package dev.vexelray.surface;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.supirvast.vastir.core.UnaryOp;
import dev.supirvast.vastir.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Terse {@code core}-IR authoring vocabulary — the shared bottom layer for surface lowering, the derivative
 * pass, and normalisation. Everything emits fresh IR per call and nothing here is stateful, so an expression
 * built through these helpers is a pure value with structural equality (which is what lets a whole surface serve
 * as its own cache key).
 *
 * <p>Two rules that the {@code core} algebra imposes and that callers keep tripping over, handled here:
 * a binary operator's operands must have the <em>same</em> type (there is no vector-times-scalar primitive, so
 * scalars are broadcast — see {@link #scale}), and a "zero" must be built at a specific type (see {@link #zero}),
 * which the derivative pass needs constantly.
 */
public final class Ir {

    public static final Type.Float F32 = Type.float32();
    public static final Type.Vector V2 = new Type.Vector(F32, 2);
    public static final Type.Vector V3 = new Type.Vector(F32, 3);
    public static final Type.Vector V4 = new Type.Vector(F32, 4);

    /** The distinguished sample point every surface is a function of: {@code sdf(vec3 p)}'s parameter. */
    public static final Expr POINT = new Expr.Param(0, V3);

    private Ir() {
    }

    // --- constants ---

    public static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    public static Expr v2(Expr a, Expr b) {
        return new Expr.VectorConstruct(V2, List.of(a, b));
    }

    public static Expr v3(double x, double y, double z) {
        return new Expr.VectorConstruct(V3, List.of(f(x), f(y), f(z)));
    }

    public static Expr v3(Expr x, Expr y, Expr z) {
        return new Expr.VectorConstruct(V3, List.of(x, y, z));
    }

    /**
     * The additive identity at {@code type} — scalar {@code 0.0} or a vector of them. The derivative pass leans on
     * this for every constant subexpression, and the type must match exactly or lowering rejects the operand pair.
     */
    public static Expr zero(Type type) {
        if (type instanceof Type.Vector vec) {
            List<Expr> components = new ArrayList<>(vec.count());
            for (int i = 0; i < vec.count(); i++) {
                components.add(zero(vec.component()));
            }
            return new Expr.VectorConstruct(vec, components);
        }
        if (type instanceof Type.Float flt) {
            return new Expr.ConstFloat(flt, 0.0);
        }
        if (type instanceof Type.Int intType) {
            return new Expr.ConstInt(intType, 0);
        }
        throw new IllegalArgumentException("no zero for type " + type);
    }

    /**
     * A scalar broadcast to {@code type} — {@code s} unchanged if it already has that type, a filled vector if
     * not. The already-matching case is not an optimisation: without it, broadcasting a value that is
     * <em>already</em> a vector nests it inside another one, producing malformed IR that silently reads only its
     * first component. That is how a componentwise {@code max} loses two thirds of its derivative.
     */
    public static Expr broadcast(Expr s, Type type) {
        if (type.equals(s.type())) {
            return s;
        }
        if (type instanceof Type.Vector vec) {
            List<Expr> components = new ArrayList<>(vec.count());
            for (int i = 0; i < vec.count(); i++) {
                components.add(broadcast(s, vec.component()));
            }
            return new Expr.VectorConstruct(vec, components);
        }
        return s;
    }

    // --- arithmetic ---

    public static Expr add(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
    }

    public static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.SUB, a, b);
    }

    public static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.MUL, a, b);
    }

    public static Expr div(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.DIV, a, b);
    }

    public static Expr neg(Expr a) {
        return new Expr.Unary(UnaryOp.NEGATE, a);
    }

    /** {@code v * s} for a vector (or scalar) {@code v} and scalar {@code s}, broadcasting {@code s} to match. */
    public static Expr scale(Expr v, Expr s) {
        return mul(v, broadcast(s, v.type()));
    }

    // --- component access ---

    public static Expr x(Expr v) {
        return new Expr.VectorExtract(v, 0);
    }

    public static Expr y(Expr v) {
        return new Expr.VectorExtract(v, 1);
    }

    public static Expr z(Expr v) {
        return new Expr.VectorExtract(v, 2);
    }

    // --- math ---

    public static Expr length(Expr v) {
        return Expr.MathCall.length(v);
    }

    public static Expr dot(Expr a, Expr b) {
        return Expr.MathCall.dot(a, b);
    }

    public static Expr abs(Expr v) {
        return Expr.MathCall.abs(v);
    }

    public static Expr sqrt(Expr v) {
        return Expr.MathCall.sqrt(v);
    }

    public static Expr min(Expr a, Expr b) {
        return Expr.MathCall.min(a, b);
    }

    public static Expr max(Expr a, Expr b) {
        return Expr.MathCall.max(a, b);
    }

    public static Expr clamp(Expr v, Expr lo, Expr hi) {
        return Expr.MathCall.clamp(v, lo, hi);
    }

    public static Expr mix(Expr a, Expr b, Expr t) {
        return Expr.MathCall.mix(a, b, t);
    }

    /** {@code step(edge, x)} — {@code 1.0} where {@code x >= edge}, else {@code 0.0}. */
    public static Expr step(Expr edge, Expr v) {
        return Expr.MathCall.step(edge, v);
    }

    /** A {@code MathFn} call at an explicit result type, for the cases the static factories do not cover. */
    public static Expr call(MathFn fn, Type type, Expr... args) {
        return new Expr.MathCall(fn, type, List.of(args));
    }

    /** True when {@code e} is a float constant equal to {@code v} — lets passes fold away trivial operands. */
    public static boolean isConst(Expr e, double v) {
        return e instanceof Expr.ConstFloat c && c.value() == v;
    }
}
