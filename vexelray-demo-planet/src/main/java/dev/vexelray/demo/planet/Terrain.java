package dev.vexelray.demo.planet;

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
 * The planet's height field: octaves of 3D value noise over the unit sphere, in kilometres above sea level, as
 * {@code core} IR for the GPU and as plain Java for the CPU, the two computing the same function.
 *
 * <h2>Bounds, proven from the octave list</h2>
 *
 * <p>Everything a march needs to be safe is a sum over the octaves, so it is computed, not measured:
 * <ul>
 *   <li>{@link #maxHeight}: no point of the surface is further than this from the sea-level sphere, above or
 *       below. The noise is in {@code [-1, 1]}, the octaves are normalised to sum to one, and the result is
 *       clamped, so the bound is exact by construction.</li>
 *   <li>{@link #slopeBound}: how steep the surface can be, as the most its height can change per kilometre
 *       travelled across the unit sphere. Each octave contributes its amplitude times its frequency times the
 *       noise's own steepest slope ({@link #NOISE_SLOPE}).</li>
 * </ul>
 * The planet's field {@code |p| - R - h(p/|p|)} then changes by at most {@link #lipschitz(double)} per kilometre,
 * and a march that steps by the field over that never crosses the surface.
 *
 * <p>The sea is the height clamped at zero: the field sees a flat ocean, and {@code h < 0} below it is the depth.
 */
final class Terrain {

    /** Octave {@code i} has frequency {@code BASE_FREQUENCY * 2^i} cycles per unit-sphere radius. */
    static final int OCTAVES = 11;
    static final double BASE_FREQUENCY = 1.6;

    /** Amplitude falls by this per octave, before normalising. */
    static final double GAIN = 0.5;

    /** The normalised sum is stretched by this, then clamped to {@code [-1, 1]}, to use the whole range. */
    static final double STRETCH = 1.7;

    /**
     * The steepest a {@link #noise} value can change per unit of its input: three axes, each at most the value
     * range (two) times smoothstep's steepest slope (one and a half), so {@code 3 * sqrt(3)}.
     */
    static final double NOISE_SLOPE = 3 * Math.sqrt(3);

    /** Highest land and deepest sea, in kilometres from sea level. */
    final double maxHeight;

    private final double[] amplitudes = new double[OCTAVES];
    private final double[] frequencies = new double[OCTAVES];

    /** {@code float height(vec3 unitDirection)}, kilometres, ocean depth negative. */
    final Function height;

    Terrain(double maxHeight) {
        this.maxHeight = maxHeight;
        double sum = 0;
        for (int i = 0; i < OCTAVES; i++) {
            amplitudes[i] = Math.pow(GAIN, i);
            frequencies[i] = BASE_FREQUENCY * Math.pow(2, i);
            sum += amplitudes[i];
        }
        for (int i = 0; i < OCTAVES; i++) {
            amplitudes[i] /= sum;
        }
        this.height = heightFunction();
    }

    /**
     * The most the height can change per unit of arc on the unit sphere, in kilometres: the clamp's stretch times
     * {@code maxHeight} times the sum of amplitude times frequency, times the noise's own slope.
     */
    double slopeBound() {
        double sum = 0;
        for (int i = 0; i < OCTAVES; i++) {
            sum += amplitudes[i] * frequencies[i];
        }
        return maxHeight * STRETCH * sum * NOISE_SLOPE;
    }

    /**
     * The field {@code |p| - radius - max(h, 0)} changes by at most this per kilometre anywhere at least
     * {@code radius - maxHeight} from the centre: radially by one, across by the slope over the radius there.
     */
    double lipschitz(double radius) {
        double across = slopeBound() / (radius - maxHeight);
        return Math.sqrt(1 + across * across);
    }

    // ---- the CPU twin -------------------------------------------------------------------------------------

    /** The height in kilometres along unit direction {@code (x, y, z)}: what {@link #height} computes. */
    double heightAt(double x, double y, double z) {
        double sum = 0;
        for (int i = 0; i < OCTAVES; i++) {
            double f = frequencies[i];
            sum += amplitudes[i] * noise(x * f + offset(i, 0), y * f + offset(i, 1), z * f + offset(i, 2));
        }
        return maxHeight * Math.clamp(sum * STRETCH, -1, 1);
    }

    /** Value noise in {@code [-1, 1]}: a hash on the integer lattice, smoothstep-interpolated. */
    static double noise(double x, double y, double z) {
        double ix = Math.floor(x);
        double iy = Math.floor(y);
        double iz = Math.floor(z);
        double ux = smooth(x - ix);
        double uy = smooth(y - iy);
        double uz = smooth(z - iz);
        double x00 = lerp(hash(ix, iy, iz), hash(ix + 1, iy, iz), ux);
        double x10 = lerp(hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz), ux);
        double x01 = lerp(hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1), ux);
        double x11 = lerp(hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1), ux);
        return 2 * lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz) - 1;
    }

    /** Dave Hoskins's hash of a lattice point: multiplies, adds and fracts of small numbers, as on the GPU. */
    static double hash(double x, double y, double z) {
        double px = fract(x * 0.1031);
        double py = fract(y * 0.1030);
        double pz = fract(z * 0.0973);
        double dot = px * (py + 33.33) + py * (pz + 33.33) + pz * (px + 33.33);
        px += dot;
        py += dot;
        pz += dot;
        return fract((px + py) * pz);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double fract(double v) {
        return v - Math.floor(v);
    }

    /** Each octave's own shift, so octaves do not share a lattice corner at the origin. */
    private static double offset(int octave, int axis) {
        return 17.31 * (octave + 1) + 5.17 * axis;
    }

    // ---- the GPU half -------------------------------------------------------------------------------------

    /** {@code float hash(vec3 lattice)}: {@link #hash(double, double, double)} in IR. */
    static final Function HASH = hashFunction();

    /** {@code float noise(vec3 p)}: {@link #noise(double, double, double)} in IR. */
    static final Function NOISE = noiseFunction();

    /** Every function a module calling {@link #height} must add, callees first. */
    List<Function> functions() {
        return List.of(HASH, NOISE, height);
    }

    private static Function hashFunction() {
        Expr i = new Expr.Param(0, Ir.V3);
        LocalVar p3 = new LocalVar("p3", Ir.V3);
        LocalVar dot = new LocalVar("dot", Ir.F32);
        Expr p = new Expr.Read(p3);
        return new Function("hash", new Type.FunctionType(Ir.F32, List.of(Ir.V3)), Region.of(
                new Statement.DeclareVar(p3, Expr.MathCall.fract(Ir.mul(i, Ir.v3(0.1031, 0.1030, 0.0973)))),
                new Statement.DeclareVar(dot, Ir.dot(p, Ir.add(Ir.v3(Ir.y(p), Ir.z(p), Ir.x(p)),
                        Ir.v3(33.33, 33.33, 33.33)))),
                new Statement.Assign(p3, Ir.add(p, Ir.v3(new Expr.Read(dot), new Expr.Read(dot),
                        new Expr.Read(dot)))),
                new Statement.Return(Expr.MathCall.fract(Ir.mul(Ir.add(Ir.x(p), Ir.y(p)), Ir.z(p))))));
    }

    private static Function noiseFunction() {
        Expr p = new Expr.Param(0, Ir.V3);
        LocalVar cell = new LocalVar("cell", Ir.V3);
        LocalVar u = new LocalVar("u", Ir.V3);
        Expr c = new Expr.Read(cell);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(cell, Expr.MathCall.floor(p)));
        LocalVar f = new LocalVar("f", Ir.V3);
        body.add(new Statement.DeclareVar(f, Ir.sub(p, c)));
        Expr fr = new Expr.Read(f);
        body.add(new Statement.DeclareVar(u, Ir.mul(Ir.mul(fr, fr), Ir.sub(Ir.v3(3, 3, 3), Ir.scale(fr, Ir.f(2.0))))));
        Expr ux = Ir.x(new Expr.Read(u));
        Expr uy = Ir.y(new Expr.Read(u));
        Expr uz = Ir.z(new Expr.Read(u));
        Expr x00 = Ir.mix(corner(c, 0, 0, 0), corner(c, 1, 0, 0), ux);
        Expr x10 = Ir.mix(corner(c, 0, 1, 0), corner(c, 1, 1, 0), ux);
        Expr x01 = Ir.mix(corner(c, 0, 0, 1), corner(c, 1, 0, 1), ux);
        Expr x11 = Ir.mix(corner(c, 0, 1, 1), corner(c, 1, 1, 1), ux);
        Expr value = Ir.mix(Ir.mix(x00, x10, uy), Ir.mix(x01, x11, uy), uz);
        body.add(new Statement.Return(Ir.sub(Ir.mul(value, Ir.f(2.0)), Ir.f(1.0))));
        return new Function("noise", new Type.FunctionType(Ir.F32, List.of(Ir.V3)), new Region(body));
    }

    private static Expr corner(Expr cell, int x, int y, int z) {
        return new Expr.Call(HASH, List.of(Ir.add(cell, Ir.v3(x, y, z))));
    }

    private Function heightFunction() {
        Expr dir = new Expr.Param(0, Ir.V3);
        LocalVar sum = new LocalVar("sum", Ir.F32);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(sum, Ir.f(0.0)));
        for (int i = 0; i < OCTAVES; i++) {
            Expr at = Ir.add(Ir.scale(dir, Ir.f(frequencies[i])),
                    Ir.v3(offset(i, 0), offset(i, 1), offset(i, 2)));
            body.add(new Statement.Assign(sum, Ir.add(new Expr.Read(sum),
                    Ir.mul(Ir.f(amplitudes[i]), new Expr.Call(NOISE, List.of(at))))));
        }
        body.add(new Statement.Return(Ir.mul(Ir.f(maxHeight),
                Ir.clamp(Ir.mul(new Expr.Read(sum), Ir.f(STRETCH)), Ir.f(-1.0), Ir.f(1.0)))));
        return new Function("height", new Type.FunctionType(Ir.F32, List.of(Ir.V3)), new Region(body));
    }
}
