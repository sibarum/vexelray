package dev.vexelray.shader;

import dev.supirvast.vastir.core.Expr;

/**
 * Lets a {@link Shading} name a value it needs more than once, instead of repeating the expression that computes
 * it.
 *
 * <p>Without this, a model that uses one value twice emits it twice, because {@code core} expressions are
 * immutable value trees and nothing downstream performs common-subexpression elimination. That is not a
 * micro-optimisation. A diffuse term broadcast into three colour channels appears three times, and it transitively
 * contains the surface normal — which in a ray-march is six calls into the distance field. Written naively, one
 * Lambert light turned eight field evaluations per pixel into twenty. D12 in {@code docs/refactor-decisions.md}
 * records the same mechanism reaching 22 MB of SPIR-V by a different route.
 *
 * <p>So the shading interface hands the model a way to bind. The composer supplies the implementation, splices
 * the declarations in ahead of their uses, and the model never sees a statement list.
 *
 * <p>Bind anything used more than once, and anything expensive; leave single-use expressions alone, where a
 * binding only adds a name.
 */
public interface Bindings {

    /**
     * Bind {@code value} to a local and return a read of it. The returned expression may be used any number of
     * times and the value is computed once.
     *
     * @param name a readable hint; implementations make it unique, so callers need not
     */
    Expr bind(String name, Expr value);
}
