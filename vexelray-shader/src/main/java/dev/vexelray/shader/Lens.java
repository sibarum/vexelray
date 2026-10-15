package dev.vexelray.shader;

import java.util.Objects;

/**
 * <b>How a frame projects</b>: the pinhole's focal length and the {@link ClipDepth} its depth is written in,
 * carried as one value so that every technique sharing a colour and depth target projects through the same
 * lens.
 *
 * <p>The two numbers travel together because they are only ever wrong together. A technique given the scene's
 * depth convention but its own focal length puts its geometry at the right depth and the wrong place on screen;
 * one given the scene's focal length and its own convention draws in the right place and loses or wins every
 * depth test it should not. Either reads as a placement bug in whichever technique looks out of line. Taking a
 * {@code Lens} rather than the two separately means a rasterising technique is handed the scene's lens, not
 * asked to agree with it — {@code new MeshTechnique(scene.lens(), ...)} cannot mix one scene's focal length with
 * another's far plane.
 *
 * <p>The camera's <em>pose</em> is not here: position and orientation change every frame and reach each
 * technique through its own {@code camera(...)} call (D5). A lens is what is fixed when the shaders are built.
 *
 * @param focalLength distance from eye to image plane in the ray basis, where the screen spans
 *                    {@code [-aspect, aspect] x [-1, 1]}: larger is a longer lens and a narrower field of view
 * @param clipDepth   the near/far convention depth is written in
 */
public record Lens(double focalLength, ClipDepth clipDepth) {

    public Lens {
        if (!(focalLength > 0) || !Double.isFinite(focalLength)) {
            throw new IllegalArgumentException("focalLength must be finite and positive, got " + focalLength);
        }
        Objects.requireNonNull(clipDepth, "clipDepth");
    }
}
