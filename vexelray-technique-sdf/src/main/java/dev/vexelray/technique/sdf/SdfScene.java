package dev.vexelray.technique.sdf;

import dev.vexelray.shader.Shading;
import dev.vexelray.surface.Surface;

/**
 * Everything {@link SdfComposer} needs to generate a renderer: what the geometry is, how it is lit, how hard to
 * march, and how wide the lens is.
 *
 * <p>A record of records all the way down — {@link Surface} is a record tree, {@link Shading} models are
 * records, {@link MarchSettings} and {@link Rgb} are records — so structural equality holds for the whole scene
 * and {@code ShaderKey}'s default fingerprint is correct with nothing written to support it. Two scenes built
 * independently but describing the same thing share one compiled shader set.
 *
 * <p>What is <em>not</em> here is anything that changes per frame. Camera position and orientation, and the
 * viewport aspect, are push constants (see {@link SdfComposer#cameraBytes}), because baking them would mean
 * recompiling a shader every time the player turns their head or the window is resized.
 *
 * @param surface      the geometry
 * @param shading      how a hit point becomes a colour
 * @param march        sphere-trace budget and artifact guards
 * @param albedo       surface colour, uniform across the scene for now. Per-primitive materials arrive with the
 *                     material matrix of docs/vexel-world.md §2; until then a scene is one colour, which is
 *                     enough to see shape and shading but is a real narrowing versus the harness's blended
 *                     per-primitive colours
 * @param sky          colour returned when a ray reaches {@link MarchSettings#farPlane} without hitting anything
 * @param focalLength  distance from eye to image plane in the ray basis: larger is a longer lens, narrower field
 *                     of view. The camera's only compile-time property; everything else about it is a push
 *                     constant
 */
public record SdfScene(Surface surface, Shading shading, MarchSettings march,
                       Rgb albedo, Rgb sky, double focalLength) {

    public SdfScene {
        if (surface == null || shading == null || march == null || albedo == null || sky == null) {
            throw new IllegalArgumentException("every part of a scene must be present");
        }
        if (!(focalLength > 0) || !Double.isFinite(focalLength)) {
            throw new IllegalArgumentException("focalLength must be finite and positive, got " + focalLength);
        }
    }

    /** A scene with the defaults the demo uses: one key light, a neutral surface, a cool sky, a 1.4 lens. */
    public static SdfScene of(Surface surface) {
        return new SdfScene(surface, dev.vexelray.shader.Shadings.defaultKeyLight(), MarchSettings.DEFAULT,
                new Rgb(0.8, 0.8, 0.8), new Rgb(0.10, 0.12, 0.16), 1.4);
    }

    public SdfScene withShading(Shading shading) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength);
    }

    public SdfScene withMarch(MarchSettings march) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength);
    }

    public SdfScene withAlbedo(Rgb albedo) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength);
    }

    /** A linear-RGB colour. Linear, not sRGB: shading arithmetic is only correct in a linear space. */
    public record Rgb(double r, double g, double b) {
    }
}
