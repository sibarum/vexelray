package dev.vexelray.shader;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.lighting.LightingModel;

/**
 * A {@link LightingModel} that can actually emit its shading as {@code core} IR — the half of the lighting
 * contract that could not live in {@code vexelray-core}.
 *
 * <p>{@code LightingModel}'s javadoc has been holding a place for this: <em>"the IR-emitting method is
 * intentionally absent from this first-cut interface … and will land with the first concrete composer."</em>
 * This is that method, and it lands here rather than there because {@code vexelray-core} is deliberately free of
 * any SupirVast dependency, so it cannot so much as name an {@code Expr}. Splitting the interface along that
 * line keeps a model <em>configurable</em> from the binding-agnostic layer while its IR is composed at the
 * SupirVast seam.
 *
 * <p>A composer requires a {@code Shading}, not a bare {@code LightingModel}: a model that only describes itself
 * can be named in a pipeline configuration, but cannot be compiled into one.
 */
public interface Shading extends LightingModel {

    /**
     * The outgoing linear-RGB radiance at {@code point}, as a {@code vec3} expression.
     *
     * <p>Use {@code bindings} for any value referenced more than once. Expressions are immutable value trees and
     * nothing downstream eliminates common subexpressions, so a repeated expression is repeated <em>work</em> —
     * and in a ray-march the operands reachable from a shading point are field evaluations, not registers. See
     * {@link Bindings} for what that cost me the first time.
     *
     * <p>Must be deterministic in {@code point}: two calls with structurally equal points must produce
     * structurally equal IR, or {@link ShaderKey} will hand out a cached shader that does not match its key.
     */
    Expr shade(ShadingPoint point, Bindings bindings);
}
