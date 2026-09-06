package dev.vexelray.surface;

/**
 * A number in a {@link Surface}: either written down, or driven from outside the shader.
 *
 * <p>Every numeric a designer would reach for a slider on is one of these, and that is the entire mechanism by
 * which a value can change without the surface being recompiled. A {@link Lit} is baked into the IR exactly as
 * the same {@code double} always was — a scene of literals lowers to byte-identical IR, which is the acceptance
 * test for this type existing. A {@link Param} lowers to a read of a slot the host writes, so sweeping it costs
 * one write and no {@code vkCreateGraphicsPipelines}.
 *
 * <h2>The range is not optional</h2>
 *
 * <p>A {@link Param} declares {@code [min, max]} because three things downstream need it and none of them can
 * derive it:
 *
 * <ul>
 *   <li><b>validation</b> — {@code Sphere} rejects a radius that is not positive, and with a parameter that
 *       question is asked of the whole range rather than of today's value. A radius sweeping through zero is a
 *       surface that stops being a surface partway along the slider, and it is caught at construction rather
 *       than at the frame where it happens;</li>
 *   <li><b>bounds</b> — {@link Bounds} has to contain the geometry at <em>every</em> value the parameter can
 *       take, or it is not a containment claim at all;</li>
 *   <li><b>the shader key</b> — a range is compile-time, so it may inform the lowering (a quantised packing
 *       would need exactly this), where a value may not.</li>
 * </ul>
 *
 * <p>{@link Param#initial} is what the host writes into the block before anything has moved it. It is
 * deliberately <em>not</em> the live value: the live value lives in a {@link ParamBlock} and never in the tree,
 * which is what keeps the tree — and therefore the shader key — still when a slider moves.
 */
public sealed interface Scalar {

    /** A number written down. Lowers to the constant it is, and folds like one. */
    record Lit(double value) implements Scalar {
        public Lit {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("a literal must be finite, got " + value);
            }
        }
    }

    /**
     * A number the host drives, within a declared range.
     *
     * @param id      identity, stable across renames, edits and reloads
     * @param min     smallest value it may take
     * @param max     largest value it may take
     * @param initial where it starts, within {@code [min, max]}
     */
    record Param(ParamId id, double min, double max, double initial) implements Scalar {
        public Param {
            if (id == null) {
                throw new IllegalArgumentException("a parameter needs an id");
            }
            if (!Double.isFinite(min) || !Double.isFinite(max)) {
                throw new IllegalArgumentException("a parameter range must be finite, got [" + min + ", " + max + "]");
            }
            if (min > max) {
                throw new IllegalArgumentException("a parameter range must not be empty, got [" + min + ", " + max + "]");
            }
            if (!Double.isFinite(initial) || initial < min || initial > max) {
                throw new IllegalArgumentException(
                        "a parameter's initial value must lie in its range: " + initial + " is outside ["
                                + min + ", " + max + "]");
            }
        }

        /** A parameter over {@code [min, max]}, starting in the middle, under a fresh identity. */
        public static Param over(double min, double max) {
            return new Param(ParamId.fresh(), min, max, (min + max) / 2);
        }

        /** A parameter over {@code [min, max]} starting at {@code initial}, under a fresh identity. */
        public static Param over(double min, double max, double initial) {
            return new Param(ParamId.fresh(), min, max, initial);
        }
    }

    /** The number {@code value}, written down. */
    static Scalar of(double value) {
        return new Lit(value);
    }

    /** The smallest value this can take — itself, for a literal. */
    default double min() {
        return switch (this) {
            case Lit l -> l.value();
            case Param p -> p.min();
        };
    }

    /** The largest value this can take — itself, for a literal. */
    default double max() {
        return switch (this) {
            case Lit l -> l.value();
            case Param p -> p.max();
        };
    }

    /** The value the host starts at: the literal, or the parameter's initial. */
    default double initial() {
        return switch (this) {
            case Lit l -> l.value();
            case Param p -> p.initial();
        };
    }

    /** Whether this is a number the compiler may compute with. */
    default boolean isLit() {
        return this instanceof Lit;
    }

    /**
     * The literal value.
     *
     * <p>Called only where the compiler has already established that every input to a piece of arithmetic is
     * literal — a rotation matrix, a stretch bound — so that the constant path emits precisely the IR it emitted
     * before parameters existed.
     *
     * @throws IllegalStateException if this is a parameter
     */
    default double literal() {
        if (this instanceof Lit l) {
            return l.value();
        }
        throw new IllegalStateException("not a literal: " + this);
    }

    /** Whether every one of {@code scalars} is a literal, and so may be folded in Java. */
    static boolean allLit(Scalar... scalars) {
        for (Scalar s : scalars) {
            if (!s.isLit()) {
                return false;
            }
        }
        return true;
    }
}
