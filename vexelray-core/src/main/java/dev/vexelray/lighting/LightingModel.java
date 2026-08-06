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
 * <p>The IR-emitting method is intentionally absent from this first-cut interface: its exact shape (the shading
 * context it reads — interpolated normal, view vector, light list, sampled material channels — and the
 * {@code core.Expr}/{@code core.Region} it returns) is being designed against real material composition and
 * will land with the first concrete composer. For now a model is identified and described so pipelines can be
 * configured, cached ({@link dev.vexelray.shader.ShaderKey}), and swapped.
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
