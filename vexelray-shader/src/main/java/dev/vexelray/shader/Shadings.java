package dev.vexelray.shader;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;

/**
 * Built-in {@link Shading} models — the IR-emitting counterparts to
 * {@link dev.vexelray.lighting.LightingModels}, which describes the same models without being able to compile
 * them.
 *
 * <p><b>Lights are model parameters here, not a scene resource.</b> A directional light is baked into the model
 * instance, so changing the sun recompiles the shader. That is the right trade while there is one light: it
 * costs nothing at runtime and avoids inventing a light-buffer binding ABI before there is a second light to
 * shape it. When a light <em>list</em> arrives it becomes a buffer read, {@code usesLights()} starts earning its
 * keep, and this class gains a model that reads it — none of which changes the {@link Shading} interface.
 */
public final class Shadings {

    private Shadings() {
    }

    /** Albedo straight through, with no light interaction. */
    public static Shading unlit() {
        return new Unlit();
    }

    /**
     * Lambertian diffuse from one directional light: {@code albedo * (max(0, N·L) * intensity + ambient)}.
     *
     * @param dirX      direction <em>toward</em> the light; normalised on construction, because an
     *                  un-normalised one silently scales every surface in the scene
     * @param intensity multiplier on the diffuse term
     * @param ambient   floor added everywhere, so surfaces facing away are not pure black
     */
    public static Shading lambert(double dirX, double dirY, double dirZ, double intensity, double ambient) {
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1e-12) {
            throw new IllegalArgumentException("light direction must be non-degenerate");
        }
        if (intensity < 0 || ambient < 0) {
            throw new IllegalArgumentException("intensity and ambient must be non-negative");
        }
        return new Lambert(dirX / length, dirY / length, dirZ / length, intensity, ambient);
    }

    /** The light Fathom has been using since v0, kept so the demo's look survives the move onto a composer. */
    public static Shading defaultKeyLight() {
        return lambert(0.575, 0.766, -0.287, 0.92, 0.08);
    }

    private record Unlit() implements Shading {
        @Override
        public String id() {
            return "unlit";
        }

        @Override
        public boolean usesLights() {
            return false;
        }

        @Override
        public Expr shade(ShadingPoint point, Bindings bindings) {
            return point.albedo();
        }
    }

    private record Lambert(double dirX, double dirY, double dirZ, double intensity, double ambient)
            implements Shading {

        @Override
        public String id() {
            // The id carries the parameters because LightingModel's contract is that equal ids emit equal IR.
            // Two differently-lit Lamberts sharing the id "lambert" would collide in the shader cache and one
            // scene would render with the other's sun.
            return "lambert(" + dirX + "," + dirY + "," + dirZ + "," + intensity + "," + ambient + ")";
        }

        @Override
        public boolean usesLights() {
            return true;
        }

        @Override
        public Expr shade(ShadingPoint point, Bindings bindings) {
            Expr toLight = Ir.v3(dirX, dirY, dirZ);
            Expr diffuse = Ir.max(Ir.dot(point.normal(), toLight), Ir.f(0.0));
            // Bound, not inlined. Ir.scale broadcasts this into three colour channels, and everything reachable
            // from point.normal() would be copied along with it — six calls into the distance field, per channel.
            Expr lit = bindings.bind("lit", Ir.clamp(
                    Ir.add(Ir.mul(diffuse, Ir.f(intensity)), Ir.f(ambient)), Ir.f(0.0), Ir.f(1.0)));
            return Ir.scale(point.albedo(), lit);
        }
    }

}
