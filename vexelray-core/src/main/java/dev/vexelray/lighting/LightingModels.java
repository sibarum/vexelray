package dev.vexelray.lighting;

/**
 * Built-in {@link LightingModel}s as <em>descriptions</em> — identity and light usage, for configuring and
 * keying a pipeline from a layer that has no SupirVast dependency.
 *
 * <p>The compilable counterparts live in {@code dev.vexelray.shader.Shadings}, which returns models that also
 * emit their shading IR. Reach for those when composing a shader; reach for these when naming a model in a
 * configuration. {@code cookTorrance()} is still description-only on both sides — it is intended to compose onto
 * SupirVast's {@code vastir-pbr} rather than re-derive the BRDF here.
 */
public final class LightingModels {

    private LightingModels() {
    }

    /** Emit surface albedo directly — no light interaction. The cheapest path; composes no light-buffer reads. */
    public static LightingModel unlit() {
        return new Simple("unlit", false);
    }

    /** Lambertian diffuse: {@code albedo * max(0, N·L)} summed over lights. A minimal lit baseline. */
    public static LightingModel lambert() {
        return new Simple("lambert", true);
    }

    /**
     * Cook-Torrance metallic/roughness PBR. Intended to compose onto SupirVast's {@code vastir-pbr} authoring
     * (which already generates a validated Cook-Torrance vertex+fragment pair) rather than re-deriving the BRDF.
     */
    public static LightingModel cookTorrance() {
        return new Simple("cook-torrance", true);
    }

    /** A model identified only by id + light usage, pending its IR-emitting implementation. */
    private record Simple(String id, boolean usesLights) implements LightingModel {
    }
}
