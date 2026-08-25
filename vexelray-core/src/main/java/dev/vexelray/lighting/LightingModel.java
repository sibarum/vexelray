package dev.vexelray.lighting;

/**
 * A pluggable lighting model — the rule that turns surface parameters (albedo, normal, roughness, …) and the
 * scene's lights into an outgoing colour. In VexelRay a lighting model is not a runtime branch inside a fixed
 * shader; it is a <em>participant in shader composition</em>. A {@link dev.vexelray.shader.ShaderComposer} asks
 * the model to contribute the shading portion of a fragment's {@code core} IR, so choosing Lambert vs
 * Cook-Torrance vs a custom BRDF changes which IR is generated and therefore the compiled SPIR-V itself.
 *
 * <p>Because composition is the seam, a lighting model can be as cheap (unlit passthrough) or as elaborate
 * (multi-light energy-conserving PBR) as it likes without the others paying for it — the code that isn't chosen
 * is never emitted.
 *
 * <p><b>The IR-emitting method is deliberately not here — it is one layer up.</b> This module is
 * binding-agnostic and carries no SupirVast dependency, so it cannot name an {@code Expr} at all. The shading
 * method therefore lives on {@code dev.vexelray.shader.Shading}, which extends this interface and adds
 * {@code Expr shade(ShadingPoint)}; {@code ShadingPoint} is the context it reads (position, normal, view
 * direction, albedo, and PBR channels), and it is written to be filled equally by a ray-marcher's
 * finite-difference normal or a rasteriser's interpolated one, so a model serves both.
 *
 * <p>What that split buys: a pipeline can be <em>configured</em> from here — a model identified, described,
 * cached ({@link dev.vexelray.shader.ShaderKey}), and swapped — without the configuring layer depending on the
 * shader IR. A composer, which does, requires the {@code Shading} half. A model that implements only this
 * interface can be named in a configuration but not compiled into one.
 */
public interface LightingModel {

    /** Stable identifier, part of a shader's cache key — models with the same id must emit the same shading IR. */
    String id();

    /**
     * Whether this model consumes scene lights. Unlit models ignore the light list, letting the composer skip
     * emitting light-buffer reads entirely.
     */
    boolean usesLights();
}
