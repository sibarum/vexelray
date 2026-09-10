package dev.vexelray.shader;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

/**
 * Everything a {@link Shading} is given about the point being shaded, as {@code core} IR.
 *
 * <p>Deliberately says nothing about <em>how</em> the point was found. A ray-marcher arrives here with a
 * finite-difference normal and a marched position; a rasteriser arrives with an interpolated normal and a
 * varying. Both fill the same record, which is what lets one lighting model serve both — the property
 * {@link dev.vexelray.lighting.LightingModel} promises but could not express, because {@code vexelray-core}
 * holds no SupirVast dependency and so cannot name an {@link Expr}.
 *
 * @param position  world-space position of the surface point ({@code vec3})
 * @param normal    unit surface normal ({@code vec3})
 * @param view      unit direction from the surface <em>toward the eye</em> ({@code vec3}) — note the sign; it is
 *                  the negated ray direction, not the ray direction
 * @param albedo    linear-RGB base colour ({@code vec3})
 * @param roughness perceptual roughness in [0,1] ({@code float}); 1 for a purely diffuse surface
 * @param metallic  metalness in [0,1] ({@code float}); 0 for a dielectric
 * @param payload   which surface this is ({@code float}) — a slot the host resolves to a node, or
 *                  {@link #NO_PAYLOAD} where the renderer carried none. A model may key off it to shade one
 *                  object differently from another that is otherwise identical: a selection highlight, a
 *                  per-object tint, eventually a material. It is a <em>name</em>: compare it, never
 *                  interpolate it and never do arithmetic on it
 */
public record ShadingPoint(Expr position, Expr normal, Expr view, Expr albedo,
                           Expr roughness, Expr metallic, Expr payload) {

    /**
     * What {@link #payload} says where the renderer carries no identity — the display path unless it was
     * asked for one, and every rasteriser until it grows a channel of its own.
     *
     * <p>Negative, because a slot never is, so a model tells "nothing was carried" from "slot zero" with a
     * comparison rather than a convention.
     */
    public static final Expr NO_PAYLOAD = Ir.f(-1.0);

    public ShadingPoint {
        requireVec3(position, "position");
        requireVec3(normal, "normal");
        requireVec3(view, "view");
        requireVec3(albedo, "albedo");
        requireScalar(roughness, "roughness");
        requireScalar(metallic, "metallic");
        requireScalar(payload, "payload");
    }

    /**
     * A purely diffuse dielectric point: roughness 1, metallic 0. What a shape-only surface supplies until
     * materials carry PBR channels of their own.
     */
    public static ShadingPoint diffuse(Expr position, Expr normal, Expr view, Expr albedo) {
        return diffuse(position, normal, view, albedo, NO_PAYLOAD);
    }

    /** The same, from a renderer that knows which surface it hit. */
    public static ShadingPoint diffuse(Expr position, Expr normal, Expr view, Expr albedo, Expr payload) {
        return new ShadingPoint(position, normal, view, albedo, Ir.f(1.0), Ir.f(0.0), payload);
    }

    private static void requireVec3(Expr e, String name) {
        if (e == null || !Ir.V3.equals(e.type())) {
            throw new IllegalArgumentException(name + " must be a vec3, got " + (e == null ? "null" : e.type()));
        }
    }

    private static void requireScalar(Expr e, String name) {
        if (e == null || !Ir.F32.equals(e.type())) {
            throw new IllegalArgumentException(name + " must be a float, got " + (e == null ? "null" : e.type()));
        }
    }
}
