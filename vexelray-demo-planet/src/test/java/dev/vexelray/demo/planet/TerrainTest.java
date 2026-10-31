package dev.vexelray.demo.planet;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain's two promises, held against its CPU twin at many random points: the surface never leaves the band
 * {@link Terrain#maxHeight} either side of sea level, and never changes faster than {@link Terrain#slopeBound}
 * across the sphere. A march that trusts either is only as safe as these.
 */
class TerrainTest {

    private final Terrain terrain = new Terrain(9.0);

    @Test
    void theHeightStaysInItsBand() {
        Random random = new Random(1);
        double highest = 0;
        for (int n = 0; n < 20_000; n++) {
            double[] d = direction(random);
            double h = terrain.heightAt(d[0], d[1], d[2]);
            assertTrue(Math.abs(h) <= terrain.maxHeight, "height " + h + " outside the band");
            highest = Math.max(highest, Math.abs(h));
        }
        // And the band is used, not a loose bound around a flat world.
        assertTrue(highest > 0.8 * terrain.maxHeight, "heights reach only " + highest);
    }

    @Test
    void theSurfaceIsNoSteeperThanItsBound() {
        Random random = new Random(2);
        double bound = terrain.slopeBound();
        double steepest = 0;
        for (int n = 0; n < 20_000; n++) {
            double[] a = direction(random);
            // A neighbour a small arc away, the scale of the finest octave and finer.
            double arc = 1e-5 + random.nextDouble() * 2e-3;
            double[] b = nudge(a, direction(random), arc);
            double change = Math.abs(terrain.heightAt(a[0], a[1], a[2]) - terrain.heightAt(b[0], b[1], b[2]));
            double angle = Math.acos(Math.clamp(a[0] * b[0] + a[1] * b[1] + a[2] * b[2], -1, 1));
            steepest = Math.max(steepest, change / angle);
        }
        assertTrue(steepest <= bound, "slope " + steepest + " beyond the bound " + bound);
    }

    private static double[] direction(Random random) {
        double x = random.nextGaussian();
        double y = random.nextGaussian();
        double z = random.nextGaussian();
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }

    /** {@code a} moved by about {@code arc} toward the part of {@code toward} across it, and back onto the sphere. */
    private static double[] nudge(double[] a, double[] toward, double arc) {
        double along = a[0] * toward[0] + a[1] * toward[1] + a[2] * toward[2];
        double[] t = {toward[0] - along * a[0], toward[1] - along * a[1], toward[2] - along * a[2]};
        double length = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        double[] b = {a[0] + arc * t[0] / length, a[1] + arc * t[1] / length, a[2] + arc * t[2] / length};
        double lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        return new double[]{b[0] / lb, b[1] / lb, b[2] / lb};
    }
}
