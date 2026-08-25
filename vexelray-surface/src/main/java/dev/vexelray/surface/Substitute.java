package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites {@link Ir#POINT} to some other point expression throughout an expression tree — how a domain transform
 * is applied.
 *
 * <p>An {@link Surface.Implicit} is authored against the sample point directly, so putting one inside a
 * {@code Translate} or a {@code Scale} means evaluating it at a moved point instead. Because expressions are
 * immutable records, "moving" it is a rebuild rather than a mutation, and the rebuilt tree keeps the structural
 * equality the shader cache keys on.
 *
 * <p>Substituting into an <em>already normalised</em> implicit is deliberate and load-bearing: the gradient is
 * taken in the surface's own frame, where the correction is 1-Lipschitz, and only then is the frame changed by a
 * transform that preserves that property. Normalising after the substitution instead would fold the transform's
 * scale into the gradient and then have {@code Scale} multiply it back in — breaking the bound the compiler just
 * promised.
 */
final class Substitute {

    private Substitute() {
    }

    /** {@code e} with every occurrence of the sample point replaced by {@code point}. */
    static Expr point(Expr e, Expr point) {
        if (Ir.POINT.equals(point)) {
            return e;   // identity transform: leave the tree untouched so output stays byte-identical
        }
        return rewrite(e, point);
    }

    private static Expr rewrite(Expr e, Expr point) {
        return switch (e) {
            case Expr.Param p when p.index() == 0 && Ir.V3.equals(p.type()) -> point;
            case Expr.Binary b -> new Expr.Binary(b.op(), rewrite(b.lhs(), point), rewrite(b.rhs(), point));
            case Expr.Unary u -> new Expr.Unary(u.op(), rewrite(u.operand(), point));
            case Expr.MathCall m -> new Expr.MathCall(m.fn(), m.type(), rewriteAll(m.args(), point));
            case Expr.VectorConstruct v ->
                    new Expr.VectorConstruct(v.type(), rewriteAll(v.components(), point));
            case Expr.VectorExtract v -> new Expr.VectorExtract(rewrite(v.vector(), point), v.index());
            case Expr.Convert c -> new Expr.Convert(rewrite(c.operand(), point), c.type());
            case Expr.Bitcast b -> new Expr.Bitcast(rewrite(b.operand(), point), b.type());
            case Expr.MatrixTimesVector m ->
                    new Expr.MatrixTimesVector(m.matrix(), rewrite(m.vector(), point));
            case Expr.Call c -> new Expr.Call(c.callee(), rewriteAll(c.arguments(), point));
            case Expr.BufferLoad b -> new Expr.BufferLoad(b.buffer(), rewrite(b.index(), point));
            case Expr.SampleTexture s -> new Expr.SampleTexture(s.texture(), rewrite(s.uv(), point));
            default -> e;   // leaves: constants, other parameters, interface / push-constant / builtin reads
        };
    }

    private static List<Expr> rewriteAll(List<Expr> exprs, Expr point) {
        List<Expr> out = new ArrayList<>(exprs.size());
        for (Expr e : exprs) {
            out.add(rewrite(e, point));
        }
        return out;
    }
}
