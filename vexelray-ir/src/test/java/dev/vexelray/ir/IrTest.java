package dev.vexelray.ir;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.type.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The type rules {@code core} imposes, which this module exists to keep in one place.
 *
 * <p>Not a test of arithmetic — {@code add} constructing an {@code ADD} node needs no proof. What is worth
 * pinning is the handful of places where getting the <em>type</em> wrong produces IR that still compiles and
 * still runs and is quietly incorrect.
 */
class IrTest {

    @Test
    @DisplayName("broadcasting a value that is already the target type leaves it alone")
    void broadcastIsIdempotent() {
        // The bug this prevents: without the identity case, broadcasting a vec3 to vec3 nested it inside another
        // vec3 — VectorConstruct(V3, [v, v, v]) where v is itself a vector. That is malformed IR which reads
        // only its first component, and it cost a componentwise max two thirds of its derivative before anyone
        // noticed. It fails silently, so it gets a test.
        Expr vector = Ir.v3(1, 2, 3);
        assertSame(vector, Ir.broadcast(vector, Ir.V3));

        Expr scalar = Ir.f(2.5);
        assertEquals(new Expr.VectorConstruct(Ir.V3, List.of(scalar, scalar, scalar)),
                Ir.broadcast(scalar, Ir.V3));
        assertSame(scalar, Ir.broadcast(scalar, Ir.F32));
    }

    @Test
    @DisplayName("scale broadcasts to the operand's own type, whatever that is")
    void scaleMatchesOperandWidth() {
        // core has no vector-times-scalar primitive, so the scalar has to be widened to match — and to the
        // operand's width, not an assumed vec3. A vec2 heightfield domain scaled by a vec3 would not lower.
        Expr s = Ir.f(3.0);
        assertEquals(Ir.mul(Ir.v2(1, 2), new Expr.VectorConstruct(Ir.V2, List.of(s, s))),
                Ir.scale(Ir.v2(1, 2), s));
        assertEquals(Ir.mul(Ir.v3(1, 2, 3), new Expr.VectorConstruct(Ir.V3, List.of(s, s, s))),
                Ir.scale(Ir.v3(1, 2, 3), s));
        assertEquals(Ir.mul(Ir.f(4.0), s), Ir.scale(Ir.f(4.0), s));
    }

    @Test
    @DisplayName("zero is built at the type asked for, and refuses types that have none")
    void zeroIsTyped() {
        assertEquals(new Expr.ConstFloat(Ir.F32, 0.0), Ir.zero(Ir.F32));
        assertEquals(Ir.v3(0, 0, 0), Ir.zero(Ir.V3));
        assertEquals(new Expr.ConstInt(Type.int32(), 0), Ir.zero(Type.int32()));
        assertThrows(IllegalArgumentException.class, () -> Ir.zero(Type.VOID));
    }

    @Test
    @DisplayName("expressions are values, so equal constructions are equal and hash alike")
    void expressionsAreValues() {
        // Everything downstream leans on this: it is why a scene can be its own shader-cache key, and why the
        // surface compiler can compare a lowered primitive against a hand-written expression.
        assertEquals(Ir.sub(Ir.length(Ir.sub(Ir.POINT, Ir.v3(1, 2, 3))), Ir.f(0.5)),
                Ir.sub(Ir.length(Ir.sub(Ir.POINT, Ir.v3(1, 2, 3))), Ir.f(0.5)));
        assertEquals(Ir.v3(1, 2, 3).hashCode(), Ir.v3(1, 2, 3).hashCode());
    }

    @Test
    @DisplayName("the sample point is the first parameter of f(vec3), which the derivative pass keys on")
    void pointIsTheFirstParameter() {
        assertEquals(new Expr.Param(0, Ir.V3), Ir.POINT);
        assertEquals(Ir.V3, Ir.POINT.type());
    }

    @Test
    @DisplayName("xz takes the ground-plane slice, not the first two components")
    void xzIsTheGroundSlice() {
        assertEquals(Ir.v2(Ir.x(Ir.POINT), Ir.z(Ir.POINT)), Ir.xz(Ir.POINT));
    }
}
