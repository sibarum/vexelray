package dev.vexelray.lighting;

/**
 * Built-in {@link LightingModel}s. Each is a marker/description today (the runtime shader-generation milestone
 * is API-design-only); the IR each emits lands with the first material composer. Custom models implement
 * {@link LightingModel} directly.
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
