/**
 * Surfaces as data, compiled into distance fields.
 *
 * <p>A {@link dev.vexelray.surface.Surface} is a sealed record tree — primitives, transforms, combinators, and an
 * {@link dev.vexelray.surface.Surface.Implicit} escape hatch holding an arbitrary expression.
 * {@link dev.vexelray.surface.SurfaceCompiler} lowers one to SupirVast {@code core} IR, and the result is a
 * {@link dev.vexelray.surface.Field}: the distance expression together with the bound that says whether it can be
 * sphere-traced.
 *
 * <p>The bound is the reason this is a module and not a helper. An expression whose zero set is a surface is
 * generally <em>not</em> a distance field, and marching one directly punches holes through the geometry — so
 * {@link dev.vexelray.surface.Gradient} differentiates the IR symbolically and
 * {@link dev.vexelray.surface.Normalize} rescales by the result, but only where a subtree admits it cannot vouch
 * for itself. Hand-authored scenes lower to exactly what they always did.
 *
 * <p>Everything here produces IR, not pixels: no Vulkan, no shader composition, no GPU. Turning a field into a
 * ray-march fragment belongs to the SDF {@code ShaderComposer}. Because the output is {@code core} IR, the same
 * compiled surface also runs on the CPU through SupirVast's Truffle backend — so a surface authored at runtime is
 * immediately available to collision and simulation, not only to the renderer.
 *
 * <p>Design and staging: {@code docs/surface-compiler.md}; decision record D14 in {@code docs/refactor-decisions.md}.
 */
package dev.vexelray.surface;
