package dev.vexelray.experimental;

import dev.supirvast.vastir.core.Expr;

import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.scale;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.v3;

/**
 * Primitive signed-distance functions and combinators, as {@code core} IR — the atoms of a bounded-primitive
 * world (see docs/vexel-world.md). Everything here is a proper distance field (or a conservative smooth-union of
 * them), so a scene assembled from these sphere-traces cleanly, with none of the heightfield overshoot seams.
 * Pure IR, lowers to both backends (render == sim).
 */
public final class Sdf {

    private Sdf() {
    }

    /** Sphere of radius {@code r} centred at {@code (cx,cy,cz)}. */
    public static Expr sphere(Expr p, double cx, double cy, double cz, double r) {
        return sub(Expr.MathCall.length(sub(p, v3(cx, cy, cz))), f(r));
    }

    /** Capsule (round-ended segment) from {@code a} to {@code b} with radius {@code r}. */
    public static Expr capsule(Expr p, double ax, double ay, double az,
                               double bx, double by, double bz, double r) {
        Expr a = v3(ax, ay, az);
        Expr ba = v3(bx - ax, by - ay, bz - az);
        Expr pa = sub(p, a);
        Expr h = Expr.MathCall.clamp(div(Expr.MathCall.dot(pa, ba), Expr.MathCall.dot(ba, ba)), f(0.0), f(1.0));
        return sub(Expr.MathCall.length(sub(pa, scale(ba, h))), f(r));
    }

    /**
     * Polynomial smooth-min (Quilez): a union of {@code a} and {@code b} that blends over radius {@code k} into a
     * smooth fillet instead of a hard crease. {@code smin <= min}, so it stays a conservative distance estimate
     * (never overshoots). Larger {@code k} = softer/meltier interface; {@code k -> 0} approaches a hard union.
     */
    public static Expr smin(Expr a, Expr b, double k) {
        Expr h = Expr.MathCall.clamp(add(f(0.5), mul(f(0.5), div(sub(b, a), f(k)))), f(0.0), f(1.0));
        return sub(Expr.MathCall.mix(b, a, h), mul(f(k), mul(h, sub(f(1.0), h))));
    }
}
