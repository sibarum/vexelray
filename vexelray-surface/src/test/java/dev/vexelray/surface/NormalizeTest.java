package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static dev.vexelray.ir.Ir.POINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim this module exists to make good on: an expression whose zero set is a surface can be turned into
 * something a sphere-tracer may safely step by.
 *
 * <p>"Safely" has a precise meaning and it is what these tests check — the reported distance must never
 * <em>exceed</em> the true distance. Over-reporting is what puts holes in a render: the ray steps past the
 * surface and the pixel misses. Under-reporting only costs iterations.
 */
class NormalizeTest {

    /** {@code x² + y² + z² - 1} — the unit sphere, written the way someone would actually type it. */
    private static final Expr QUADRIC = Ir.sub(Ir.dot(POINT, POINT), Ir.f(1.0));

    /** Radii to probe, from just outside the surface to well away from it. */
    private static final double[] RADII = {1.001, 1.05, 1.2, 1.5, 2.0, 3.0, 6.0, 10.0};

    @Test
    @DisplayName("the raw implicit overshoots — this is the bug being fixed")
    void rawImplicitOverstatesDistance() {
        // At radius 3 the expression reads 8 where the true distance is 2: a march would step four times too far
        // and sail straight through the sphere.
        assertEquals(8.0, Eval.at(QUADRIC, 3, 0, 0), 1e-12);
        assertTrue(Eval.at(QUADRIC, 3, 0, 0) > 2.0 * 3.9, "the raw field should be badly over-long here");
    }

    @Test
    @DisplayName("the normalised implicit never reports more distance than there is")
    void normalisedNeverOverstatesDistance() {
        Expr normalised = Normalize.lipschitz(QUADRIC).distance();
        for (double r : RADII) {
            double reported = Eval.at(normalised, r, 0, 0);
            double actual = r - 1.0;
            assertTrue(reported <= actual + 1e-12,
                    "at radius " + r + " reported " + reported + " but the surface is only " + actual + " away");
            assertTrue(reported > 0, "outside the sphere the field must stay positive, got " + reported);
        }
    }

    @Test
    @DisplayName("and it tightens onto the true distance as the surface is approached")
    void normalisedIsAccurateNearTheSurface() {
        Expr normalised = Normalize.lipschitz(QUADRIC).distance();
        // 5% out from the surface, the estimate should already be within 3% of the truth — near the surface is
        // where a march spends its steps, so this is the accuracy that decides whether it converges quickly.
        double reported = Eval.at(normalised, 1.05, 0, 0);
        assertEquals(0.05, reported, 0.05 * 0.03);
    }

    @Test
    @DisplayName("a linear implicit normalises to the exact distance")
    void linearImplicitsAreExact() {
        // 3x + 4y - 10 = 0 is a plane whose gradient has length 5; dividing by it recovers true distance.
        Expr plane = Ir.sub(Ir.add(Ir.mul(Ir.f(3.0), Ir.x(POINT)), Ir.mul(Ir.f(4.0), Ir.y(POINT))), Ir.f(10.0));
        Expr normalised = Normalize.lipschitz(plane).distance();
        assertEquals(0.0, Eval.at(normalised, 2, 1, 0), 1e-12);      // 6 + 4 - 10 = 0, on the plane
        assertEquals(1.0, Eval.at(normalised, 2 + 0.6, 1 + 0.8, 0), 1e-12);   // one unit along the unit normal
        assertEquals(-2.0, Eval.at(normalised, 2 - 1.2, 1 - 1.6, 0), 1e-12);  // two units the other way
    }

    @Test
    @DisplayName("a vanishing gradient gives a big step, not a NaN")
    void criticalPointsDoNotBlowUp() {
        // At the origin grad(r² - 1) is zero. Without the epsilon floor the field divides by zero and the march
        // takes an infinite or NaN step, which is unrecoverable; with it, the step is merely large.
        double atCriticalPoint = Eval.at(Normalize.lipschitz(QUADRIC).distance(), 0, 0, 0);
        assertTrue(Double.isFinite(atCriticalPoint), "field returned " + atCriticalPoint + " at a critical point");
        assertTrue(atCriticalPoint < 0, "the origin is inside the sphere");
    }

    @Test
    @DisplayName("normalising by a known constant needs no gradient in the shader at all")
    void constantNormalisationIsCheaper() {
        Field byConstant = Normalize.byConstant(Ir.mul(Ir.f(5.0), Ir.x(POINT)), 5.0);
        assertEquals(2.0, Eval.at(byConstant.distance(), 2, 0, 0), 1e-12);
        assertTrue(byConstant.isMarchable());
        // An already-1-Lipschitz field is passed through untouched rather than divided by one.
        Expr field = Ir.x(POINT);
        assertEquals(field, Normalize.byConstant(field, 1.0).distance());
    }

    @Test
    @DisplayName("normalised fields are marked marchable; the bounds are validated")
    void boundsAreChecked() {
        assertTrue(Normalize.lipschitz(QUADRIC).isMarchable());
        assertThrows(IllegalArgumentException.class, () -> Normalize.lipschitz(QUADRIC, 0));
        assertThrows(IllegalArgumentException.class, () -> Normalize.byConstant(QUADRIC, -1));
        assertThrows(IllegalArgumentException.class, () -> new Field(QUADRIC, 0));
        assertTrue(new Field(QUADRIC, Field.UNKNOWN).isMarchable() == false);
    }
}
