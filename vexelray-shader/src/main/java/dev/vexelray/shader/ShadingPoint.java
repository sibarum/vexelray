package dev.vexelray.shader;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.type.Type;

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
 */
public record ShadingPoint(Expr position, Expr normal, Expr view, Expr albedo,
                           Expr roughness, Expr metallic) {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V3 = new Type.Vector(F32, 3);

    public ShadingPoint {
        requireVec3(position, "position");
        requireVec3(normal, "normal");
        requireVec3(view, "view");
        requireVec3(albedo, "albedo");
        requireScalar(roughness, "roughness");
        requireScalar(metallic, "metallic");
    }

    /**
     * A purely diffuse dielectric point: roughness 1, metallic 0. What a shape-only surface supplies until
     * materials carry PBR channels of their own.
     */
    public static ShadingPoint diffuse(Expr position, Expr normal, Expr view, Expr albedo) {
        return new ShadingPoint(position, normal, view, albedo,
                new Expr.ConstFloat(F32, 1.0), new Expr.ConstFloat(F32, 0.0));
    }

    private static void requireVec3(Expr e, String name) {
        if (e == null || !V3.equals(e.type())) {
            throw new IllegalArgumentException(name + " must be a vec3, got " + (e == null ? "null" : e.type()));
        }
    }

    private static void requireScalar(Expr e, String name) {
        if (e == null || !F32.equals(e.type())) {
            throw new IllegalArgumentException(name + " must be a float, got " + (e == null ? "null" : e.type()));
        }
    }
}
