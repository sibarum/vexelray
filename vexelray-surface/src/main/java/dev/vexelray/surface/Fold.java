package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

/**
 * Arithmetic constructors that fold away identities as they build. Not an optimisation — a necessity: a symbolic
 * derivative is mostly zeros (every constant in the source contributes one), so building it with raw
 * {@code Expr.Binary} nodes produces an expression tens of times larger than the answer, made almost entirely of
 * {@code 0*x} and {@code x+0}. Folding at construction keeps the derivative the size it ought to be.
 *
 * <p>This matters more here than it looks. D12 in docs/refactor-decisions.md records a field whose shader reached
 * 22 MB of SPIR-V purely through duplicated structure; nothing downstream of this module does common-subexpression
 * elimination, so unfolded terms survive all the way to the GPU.
 *
 * <p>Only exact identities are applied ({@code x+0}, {@code 0*x}, {@code 1*x}, {@code x/1}, {@code -0}). Nothing
 * here reassociates or reorders floating-point arithmetic, so a folded expression evaluates bit-identically to
 * the unfolded one on both backends — which is what keeps render==sim honest.
 */
final class Fold {

    private Fold() {
    }

    static Expr add(Expr a, Expr b) {
        if (isZero(a)) {
            return b;
        }
        if (isZero(b)) {
            return a;
        }
        return Ir.add(a, b);
    }

    static Expr sub(Expr a, Expr b) {
        if (isZero(b)) {
            return a;
        }
        if (isZero(a)) {
            return neg(b);
        }
        return Ir.sub(a, b);
    }

    static Expr mul(Expr a, Expr b) {
        if (isZero(a) || isZero(b)) {
            return Ir.zero(a.type());
        }
        if (isOne(a)) {
            return b;
        }
        if (isOne(b)) {
            return a;
        }
        return Ir.mul(a, b);
    }

    static Expr div(Expr a, Expr b) {
        if (isZero(a)) {
            return Ir.zero(a.type());
        }
        if (isOne(b)) {
            return a;
        }
        return Ir.div(a, b);
    }

    static Expr neg(Expr a) {
        if (isZero(a)) {
            return a;
        }
        return Ir.neg(a);
    }

    /** {@code v * s} with {@code s} broadcast to {@code v}'s type, folding the identities first. */
    static Expr scale(Expr v, Expr s) {
        if (isZero(v) || isZero(s)) {
            return Ir.zero(v.type());
        }
        if (isOne(s)) {
            return v;
        }
        return Ir.mul(v, Ir.broadcast(s, v.type()));
    }

    /** True for an exact zero — a zero constant, or a vector built entirely of them. */
    static boolean isZero(Expr e) {
        return isConstant(e, 0.0);
    }

    /** True for an exact one — a one constant, or a vector built entirely of them. */
    static boolean isOne(Expr e) {
        return isConstant(e, 1.0);
    }

    private static boolean isConstant(Expr e, double value) {
        return switch (e) {
            case Expr.ConstFloat c -> c.value() == value;
            case Expr.ConstInt c -> c.value() == (long) value;
            case Expr.VectorConstruct vc -> vc.components().stream().allMatch(c -> isConstant(c, value));
            default -> false;
        };
    }
}
