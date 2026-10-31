package dev.vexelray.technique.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FieldSpace#worldToField} against the long way round: a field point carried to the world by the placements'
 * own matrices must come back to itself through the eight floats the shader gets, applied as the shader applies
 * them ({@code k * rotate(q, world) + o}).
 */
class FieldSpaceTest {

    @Test
    void aFieldInTheObjectRoundTrips() {
        Random random = new Random(11);
        for (int trial = 0; trial < 200; trial++) {
            Placement object = random(random);
            Placement field = random(random);
            double[] f = {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()};
            double[] world = apply(object, apply(field, f));
            assertRoundTrip(FieldSpace.inObject(field), object, world, f);
        }
    }

    @Test
    void aFieldInTheWorldIgnoresTheObject() {
        Random random = new Random(12);
        for (int trial = 0; trial < 200; trial++) {
            Placement field = random(random);
            double[] f = {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()};
            assertRoundTrip(FieldSpace.inWorld(field), random(random), apply(field, f), f);
        }
    }

    @Test
    void theScaleIsTheInverseOfTheComposedOne() {
        float[] out = new float[FieldSpace.FLOATS];
        FieldSpace.inObject(Placement.ORIGIN.scaled(0.5)).worldToField(Placement.ORIGIN.scaled(4), out, 0);
        assertEquals(0.5, out[7], 1e-6);
    }

    private static void assertRoundTrip(FieldSpace space, Placement object, double[] world, double[] expected) {
        float[] m = new float[FieldSpace.FLOATS];
        space.worldToField(object, m, 0);
        double[] u = {m[0], m[1], m[2]};
        double w = m[3];
        double[] uv = cross(u, world);
        double[] uuv = cross(u, uv);
        for (int i = 0; i < 3; i++) {
            double rotated = world[i] + 2 * (w * uv[i] + uuv[i]);
            assertEquals(expected[i], m[7] * rotated + m[4 + i], 2e-4);
        }
    }

    private static Placement random(Random random) {
        return new Placement(random.nextGaussian() * 3, random.nextGaussian() * 3, random.nextGaussian() * 3,
                random.nextDouble() * 7 - 3.5, random.nextDouble() * 3 - 1.5, random.nextDouble() * 7 - 3.5,
                0.3 + random.nextDouble() * 2);
    }

    private static double[] apply(Placement placement, double[] p) {
        float[] m = new float[Placement.MATRIX_FLOATS];
        placement.modelMatrix(m, 0);
        return new double[]{
                m[0] * p[0] + m[3] * p[1] + m[6] * p[2] + m[9],
                m[1] * p[0] + m[4] * p[1] + m[7] * p[2] + m[10],
                m[2] * p[0] + m[5] * p[1] + m[8] * p[2] + m[11]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
