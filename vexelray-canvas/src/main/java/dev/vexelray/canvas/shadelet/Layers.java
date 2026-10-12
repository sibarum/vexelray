package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;

import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.v4;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.y;
import static dev.vexelray.ir.Ir.z;

/** What a painter's layer is: a premultiplied {@code vec4}. */
public final class Layers {

    private Layers() {
    }

    /** {@code rgb} at {@code alpha}, premultiplied: {@code (rgb * alpha, alpha)}. */
    public static Expr premultiplied(Expr rgb, Expr alpha) {
        return v4(mul(x(rgb), alpha), mul(y(rgb), alpha), mul(z(rgb), alpha), alpha);
    }
}
