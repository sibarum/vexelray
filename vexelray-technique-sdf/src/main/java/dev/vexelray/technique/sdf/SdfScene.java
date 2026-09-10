package dev.vexelray.technique.sdf;

import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Shading;
import dev.vexelray.surface.Surface;

/**
 * Everything {@link SdfComposer} needs to generate a renderer: what the geometry is, how it is lit, how hard to
 * march, and how wide the lens is.
 *
 * <p>A record of records all the way down — {@link Surface} is a record tree, {@link Shading} models are
 * records, {@link MarchSettings} and {@link Surface.Rgb} are records — so structural equality holds for the whole scene
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
 * @param albedo       the scene's surface colour: what anything that did not name a colour of its own is shaded
 *                     as. A {@link Surface.Stroke} may carry per-vertex colour and gradient between them, and a
 *                     combinator hands the point to whichever child is showing — so this is the default rather
 *                     than the only colour. Giving <em>any</em> surface its own material still waits on the
 *                     material matrix of docs/vexel-world.md §2
 * @param sky          colour returned when a ray reaches {@link MarchSettings#farPlane} without hitting anything
 * @param focalLength  distance from eye to image plane in the ray basis: larger is a longer lens, narrower field
 *                     of view. The camera's only compile-time property; everything else about it is a push
 *                     constant
 * @param nearPlane    the near plane of the depth this scene writes — see {@link #clipDepth()}. Here rather
 *                     than in {@link MarchSettings} because it guards nothing about marching: no ray is
 *                     clipped against it and no step consults it. It exists solely so a hit distance can
 *                     become a clip depth, and it is baked into the shader, which is why it belongs to the
 *                     scene that keys the shader cache
 */
public record SdfScene(Surface surface, Shading shading, MarchSettings march,
                       Surface.Rgb albedo, Surface.Rgb sky, double focalLength, double nearPlane) {

    public SdfScene {
        if (surface == null || shading == null || march == null || albedo == null || sky == null) {
            throw new IllegalArgumentException("every part of a scene must be present");
        }
        if (!(focalLength > 0) || !Double.isFinite(focalLength)) {
            throw new IllegalArgumentException("focalLength must be finite and positive, got " + focalLength);
        }
        if (!(nearPlane > 0) || !Double.isFinite(nearPlane)) {
            throw new IllegalArgumentException("nearPlane must be finite and positive, got " + nearPlane);
        }
        if (nearPlane >= march.farPlane()) {
            throw new IllegalArgumentException("nearPlane " + nearPlane + " must be nearer than the march's "
                    + "farPlane " + march.farPlane());
        }
    }

    /**
     * <b>The projection convention this scene writes depth in</b>, and therefore the one anything sharing its
     * depth attachment must be given.
     *
     * <p>Derived rather than stored, so the far plane cannot disagree with the march's: they are the same
     * plane seen from two sides. {@link MarchSettings#farPlane} is where a ray gives up and takes the sky, and
     * {@link ClipDepth#far} is the distance that maps to a depth of 1 — a scene where those differed would
     * either clamp real geometry to the same depth as the sky, or reserve depth precision for a distance no
     * ray is allowed to reach. Two fields could drift apart; one field and a derivation cannot.
     *
     * <p>Hand this to a second technique that must occlude against this one. That the application is what
     * carries it between them is a statement about how much camera the engine models today — which is none —
     * rather than about where it ought to live for ever.
     */
    public ClipDepth clipDepth() {
        return new ClipDepth(nearPlane, march.farPlane());
    }

    /** A scene with the defaults the demo uses: one key light, a neutral surface, a cool sky, a 1.4 lens. */
    public static SdfScene of(Surface surface) {
        return new SdfScene(surface, dev.vexelray.shader.Shadings.defaultKeyLight(), MarchSettings.DEFAULT,
                new Surface.Rgb(0.8, 0.8, 0.8), new Surface.Rgb(0.10, 0.12, 0.16), 1.4,
                ClipDepth.DEFAULT.near());
    }

    public SdfScene withShading(Shading shading) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength, nearPlane);
    }

    public SdfScene withMarch(MarchSettings march) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength, nearPlane);
    }

    public SdfScene withAlbedo(Surface.Rgb albedo) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength, nearPlane);
    }

    /** The near plane of {@link #clipDepth()} — the one number of the convention a scene chooses. */
    public SdfScene withNearPlane(double nearPlane) {
        return new SdfScene(surface, shading, march, albedo, sky, focalLength, nearPlane);
    }
}
