package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

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
        return rewrite(e, point, 0);
    }

    /**
     * {@code e} with every read of {@link Ir#SCENE_ALBEDO} replaced by {@code value} — the last step of lowering
     * a colour, where "whatever the scene says" becomes an actual colour.
     *
     * <p>Deliberately the same rewrite, one parameter over. The two substitutions cannot race: a domain
     * transform only ever touches parameter zero, so a colour may sit inside any number of transforms and still
     * be resolved afterwards, in either order.
     */
    static Expr sceneAlbedo(Expr e, Expr value) {
        return rewrite(e, value, 1);
    }

    /**
     * The same, through one declaration of a colour program — the placeholder can be read from a bound
     * subexpression as easily as from the returned one.
     */
    static dev.supirvast.vastir.core.Statement sceneAlbedo(
            dev.supirvast.vastir.core.Statement s, Expr value) {
        if (s instanceof dev.supirvast.vastir.core.Statement.DeclareVar d) {
            return new dev.supirvast.vastir.core.Statement.DeclareVar(
                    d.variable(), rewrite(d.initializer(), value, 1));
        }
        // Lets emits nothing else, and a colour program is only ever built by Lets.
        throw new IllegalStateException("unexpected statement in a colour program: " + s);
    }

    private static Expr rewrite(Expr e, Expr point, int index) {
        return switch (e) {
            case Expr.Param p when p.index() == index && Ir.V3.equals(p.type()) -> point;
            case Expr.Binary b ->
                    new Expr.Binary(b.op(), rewrite(b.lhs(), point, index), rewrite(b.rhs(), point, index));
            case Expr.Unary u -> new Expr.Unary(u.op(), rewrite(u.operand(), point, index));
            case Expr.MathCall m -> new Expr.MathCall(m.fn(), m.type(), rewriteAll(m.args(), point, index));
            case Expr.VectorConstruct v ->
                    new Expr.VectorConstruct(v.type(), rewriteAll(v.components(), point, index));
            case Expr.VectorExtract v -> new Expr.VectorExtract(rewrite(v.vector(), point, index), v.index());
            case Expr.Convert c -> new Expr.Convert(rewrite(c.operand(), point, index), c.type());
            case Expr.Bitcast b -> new Expr.Bitcast(rewrite(b.operand(), point, index), b.type());
            case Expr.MatrixTimesVector m ->
                    new Expr.MatrixTimesVector(m.matrix(), rewrite(m.vector(), point, index));
            case Expr.Call c -> new Expr.Call(c.callee(), rewriteAll(c.arguments(), point, index));
            case Expr.BufferLoad b -> new Expr.BufferLoad(b.buffer(), rewrite(b.index(), point, index));
            case Expr.SampleTexture s -> new Expr.SampleTexture(s.texture(), rewrite(s.uv(), point, index));
            default -> e;   // leaves: constants, other parameters, interface / push-constant / builtin reads
        };
    }

    private static List<Expr> rewriteAll(List<Expr> exprs, Expr point, int index) {
        List<Expr> out = new ArrayList<>(exprs.size());
        for (Expr e : exprs) {
            out.add(rewrite(e, point, index));
        }
        return out;
    }
}
