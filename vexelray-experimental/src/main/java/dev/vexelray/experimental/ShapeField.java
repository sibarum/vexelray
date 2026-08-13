package dev.vexelray.experimental;

import dev.supirvast.vastir.core.Expr;

import static dev.vexelray.experimental.Ir.v3;

/**
 * A candidate way to <em>define a shape</em> as a signed-distance field, for head-to-head comparison in the
 * {@link ComparisonHarness}. Each implementation contributes only the field math; the harness wraps every field
 * in the <em>same</em> {@link Raymarcher}, so differences in the rendered image, its cost, and its CPU-evaluation
 * cost are attributable to the shape definition alone — not to differing march or shading code.
 *
 * <p>Contract: {@link #sdf} must return a valid signed-distance <em>estimate</em> — a conservative
 * (never-overshooting) lower bound on the true Euclidean distance — because the shared sphere-tracer relies on
 * that to converge without artifacts. Any Lipschitz scaling a technique needs is part of its own definition here,
 * so the harness march stays identical across candidates. The expression must be pure {@code core} IR that lowers
 * to both SPIR-V and the CPU (Truffle) backend, so the same field can be rendered on the GPU and queried on the
 * CPU — the render==sim property the harness also measures.
 */
public interface ShapeField {

    /** Short unique name for report tables, montage labels, and capture filenames. */
    String name();

    /**
     * The field as {@code core} IR: given a {@code vec3} point expression, return the signed distance estimate at
     * that point. Fresh IR per call (it is emitted both inline into the fragment and into a standalone CPU
     * function). Must be a conservative distance estimate (see the type contract).
     */
    Expr sdf(Expr point);

    /**
     * One-line qualitative notes for the "general applicability" column of the comparison — e.g. what shapes it
     * can express, whether it bounds cleanly, closed vs heightfield, authoring cost. Free-form.
     */
    String applicability();

    /**
     * The surface albedo (linear RGB, a {@code vec3}) at a hit point — evaluated only at the shading point, not
     * per march step. Defaults to a neutral grey so shape-only fields render as before; fields that carry
     * materials (e.g. blended primitives) override this to return their per-material / blended colour.
     */
    default Expr material(Expr point) {
        return v3(0.8, 0.8, 0.8);
    }
}
