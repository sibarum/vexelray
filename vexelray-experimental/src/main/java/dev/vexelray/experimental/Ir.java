package dev.vexelray.experimental;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.type.Type;

import java.util.List;

/**
 * Tiny {@code core}-IR authoring helpers shared by the experimental {@link ShapeField}s and the {@link Raymarcher}
 * — the same terse vocabulary the demo uses inline ({@code f}, {@code v3}, {@code add}, {@code mul}, component
 * extracts), gathered in one place so a field is a few readable lines of math. Everything emits fresh IR per call.
 */
public final class Ir {

    public static final Type.Float F32 = Type.float32();
    public static final Type.Vector V2 = new Type.Vector(F32, 2);
    public static final Type.Vector V3 = new Type.Vector(F32, 3);
    public static final Type.Vector V4 = new Type.Vector(F32, 4);

    private Ir() {
    }

    public static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    public static Expr v2(double a, double b) {
        return new Expr.VectorConstruct(V2, List.of(f(a), f(b)));
    }

    public static Expr v3(double x, double y, double z) {
        return new Expr.VectorConstruct(V3, List.of(f(x), f(y), f(z)));
    }

    public static Expr x(Expr v) {
        return new Expr.VectorExtract(v, 0);
    }

    public static Expr y(Expr v) {
        return new Expr.VectorExtract(v, 1);
    }

    public static Expr z(Expr v) {
        return new Expr.VectorExtract(v, 2);
    }

    public static Expr add(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
    }

    public static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.SUB, a, b);
    }

    public static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.MUL, a, b);
    }

    public static Expr div(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.DIV, a, b);
    }

    /** {@code vec2 * scalar} via broadcast (vector*scalar is not a core primitive). */
    public static Expr mulS2(Expr vec, Expr s) {
        return mul(vec, new Expr.VectorConstruct(V2, List.of(s, s)));
    }

    /** {@code vec3 * scalar} via broadcast. */
    public static Expr mulS3(Expr vec, Expr s) {
        return mul(vec, new Expr.VectorConstruct(V3, List.of(s, s, s)));
    }

    /** The 2D {@code xz} slice of a {@code vec3} point — the domain of a heightfield. */
    public static Expr xz(Expr point) {
        return new Expr.VectorConstruct(V2, List.of(x(point), z(point)));
    }
}
