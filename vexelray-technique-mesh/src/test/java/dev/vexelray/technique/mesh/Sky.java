package dev.vexelray.technique.mesh;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Prototype (test scope): a procedural skybox, as one {@code core} IR function of a unit direction. The same
 * function paints the background ({@link SkyTechnique}) and answers every reflected ray that gets clear
 * ({@link MengerShellShader.Mirror}), so a reflection of the sky is the sky.
 *
 * <p>A zenith-to-horizon gradient with a haze band at the horizon, a darker band below it, and a sun where the
 * scene's key light comes from ({@code Shadings.defaultKeyLight}), so the highlights and the sun agree.
 */
final class Sky {

    /** Toward the sun: {@code Shadings.defaultKeyLight}'s light direction. */
    private static final double[] SUN = normalised(0.575, 0.766, -0.287);

    /** {@code vec3 sky(vec3 direction)}. A module that calls {@link #colour} adds this function. */
    static final Function FUNCTION = function();

    private Sky() {
    }

    /** The sky's colour along unit direction {@code dir}. */
    static Expr colour(Expr dir) {
        return new Expr.Call(FUNCTION, List.of(dir));
    }

    private static Function function() {
        Expr dir = new Expr.Param(0, Ir.V3);
        LocalVar up = new LocalVar("up", Ir.F32);
        LocalVar sun = new LocalVar("sun", Ir.F32);
        LocalVar base = new LocalVar("base", Ir.V3);

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(up, Ir.y(dir)));
        body.add(new Statement.DeclareVar(sun, Ir.clamp(Ir.dot(dir, Ir.v3(SUN[0], SUN[1], SUN[2])),
                Ir.f(0.0), Ir.f(1.0))));
        // Above the horizon: haze at the horizon, deepening to the zenith on a square-root curve.
        Expr above = Expr.MathCall.sqrt(Ir.clamp(new Expr.Read(up), Ir.f(0.0), Ir.f(1.0)));
        Expr sky = Ir.mix(Ir.v3(0.86, 0.84, 0.80), Ir.v3(0.20, 0.36, 0.66), Ir.v3(above, above, above));
        // Below it: a grey-blue that darkens downward, for reflections that look under the ground's edge.
        Expr below = Ir.clamp(Ir.mul(Ir.neg(new Expr.Read(up)), Ir.f(4.0)), Ir.f(0.0), Ir.f(1.0));
        Expr under = Ir.mix(Ir.v3(0.62, 0.62, 0.64), Ir.v3(0.30, 0.31, 0.34), Ir.v3(below, below, below));
        Expr isAbove = Ir.step(Ir.f(0.0), new Expr.Read(up));
        body.add(new Statement.DeclareVar(base, Ir.mix(under, sky, Ir.v3(isAbove, isAbove, isAbove))));
        // The sun: a hot disc and a warm glow round it.
        Expr disc = Ir.mul(Expr.MathCall.pow(new Expr.Read(sun), Ir.f(900.0)), Ir.f(6.0));
        Expr glow = Ir.mul(Expr.MathCall.pow(new Expr.Read(sun), Ir.f(12.0)), Ir.f(0.35));
        Expr light = Ir.add(disc, glow);
        body.add(new Statement.Return(Ir.add(new Expr.Read(base),
                Ir.mul(Ir.v3(1.0, 0.86, 0.62), Ir.v3(light, light, light)))));
        return new Function("sky", new Type.FunctionType(Ir.V3, List.of(Ir.V3)), new Region(body));
    }

    private static double[] normalised(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }
}
