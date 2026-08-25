package dev.vexelray.technique.sdf;

/**
 * How hard the sphere-tracer tries, and how it protects itself from the ways sphere-tracing goes wrong.
 *
 * <p>These are not arbitrary knobs; each one is a specific failure the demo and the comparison harness already
 * ran into, and the defaults are the values that fixed them. Raising the step budget is the only change that is
 * purely a quality/cost trade — every other field is guarding against an artifact.
 *
 * @param steps            sphere-trace iterations per pixel; the direct quality/cost dial
 * @param maxStep          largest distance a single step may advance. A field is only conservative if it never
 *                         over-reports, and a normalised implicit is only <em>locally</em> conservative
 *                         (surface-compiler.md §2.1), so a clamp stops one over-long step from leaping a thin
 *                         feature and leaving a seam
 * @param farPlane         distance past which the ray gives up and takes the sky; also what stops an unbounded
 *                         or periodic implicit from marching forever
 * @param hitEpsilon       base surface-hit threshold
 * @param hitEpsilonSlope  growth of the hit threshold with distance. A distant pixel covers more world per
 *                         pixel, so a fixed threshold makes far surfaces shimmer as rays land inconsistently
 * @param normalEpsilon    base finite-difference width for the shading normal
 * @param normalEpsilonSlope growth of that width with distance. This one has teeth: a far hit point's
 *                         neighbours sampled at a near-field epsilon differ by float noise, so
 *                         {@code normalize} amplifies the noise and the normal flips sign — the black scribbles
 *                         on distant grazing slopes that Fathom hit. Widening with distance makes the normal
 *                         describe the surface at the pixel's actual scale
 */
public record MarchSettings(int steps, double maxStep, double farPlane,
                            double hitEpsilon, double hitEpsilonSlope,
                            double normalEpsilon, double normalEpsilonSlope) {

    /** The values Fathom and the comparison harness settled on. */
    public static final MarchSettings DEFAULT =
            new MarchSettings(128, 0.4, 120.0, 0.008, 0.001, 0.03, 0.006);

    public MarchSettings {
        if (steps < 1) {
            throw new IllegalArgumentException("steps must be at least 1, got " + steps);
        }
        requirePositive(maxStep, "maxStep");
        requirePositive(farPlane, "farPlane");
        requirePositive(hitEpsilon, "hitEpsilon");
        requirePositive(normalEpsilon, "normalEpsilon");
        requireNonNegative(hitEpsilonSlope, "hitEpsilonSlope");
        requireNonNegative(normalEpsilonSlope, "normalEpsilonSlope");
    }

    /** The same settings with a different step budget — the harness renders a cheap and a reference version. */
    public MarchSettings withSteps(int steps) {
        return new MarchSettings(steps, maxStep, farPlane, hitEpsilon, hitEpsilonSlope,
                normalEpsilon, normalEpsilonSlope);
    }

    private static void requirePositive(double v, String name) {
        if (!(v > 0) || !Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite and positive, got " + v);
        }
    }

    private static void requireNonNegative(double v, String name) {
        if (!(v >= 0) || !Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite and non-negative, got " + v);
        }
    }
}
