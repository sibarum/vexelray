package dev.vexelray.shadelets;

import com.oracle.truffle.api.CallTarget;
import dev.supirvast.vast.CoreToTruffle;
import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.tools.NativeTools;
import dev.supirvast.vastir.tools.VulkanCompute;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Fragment;
import dev.vexelray.canvas.shadelet.PaintContexts;
import dev.vexelray.canvas.shadelet.Shadelet;
import dev.vexelray.canvas.shadelet.StyleLayout;
import dev.vexelray.canvas.shadelet.StyleView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.V2;
import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.i;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v4;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The pure shadelets on both backends: each role built into a compute kernel whose {@link Fragment} and
 * {@link StyleView} read set-0 buffers this test fills, one fragment and one style per invocation, run by vast's
 * Truffle backend on the CPU and as SPIR-V on the GPU, and the two compared with each other and with the role's
 * arithmetic written out in Java.
 *
 * <p>The CPU's math calls ({@code smoothstep}, {@code length}, {@code mix}) are evaluated in double and rounded once,
 * the GPU's in float, so the two are compared within a tolerance, not bit for bit; the largest difference seen is
 * printed.
 */
class ShadeletAgreementTest {

    static final Buffer OUT = new Buffer("out", 0, F32);
    static final Buffer FRAGMENTS = new Buffer("fragments", 1, F32);
    static final Buffer STYLES = new Buffer("styles", 2, F32);
    static final List<Buffer> BUFFERS = List.of(OUT, FRAGMENTS, STYLES);

    static final int N = 256;
    /** Per invocation: colour 4, uv 2, local 2, shape 4, filter 4, aa, ppu, distance 2, offset 2. */
    static final int STRIDE = 22;
    static final int COLOR = 0;
    static final int UV = 4;
    static final int LOCAL = 6;
    static final int SHAPE = 8;
    static final int FILTER = 12;
    static final int AA = 16;
    static final int PPU = 17;
    static final int DISTANCE = 18;
    static final int OFFSET = 20;

    /** Absolute agreement asked of the backends with each other and with the Java arithmetic. */
    static final double TOLERANCE = 1e-4;
    static final boolean REQUIRE_GPU = Boolean.getBoolean("supirvast.requireGpu");

    // --- the tests ---

    @Test
    void weightAgrees() {
        Random random = new Random(1);
        float[] fragments = fragments(random);
        float[] styles = new float[N];
        for (int k = 0; k < N; k++) {
            styles[k] = range(random, -4, 4);
        }
        StyleView view = view(WeightStyleLayout.LAYOUT);
        Expr out = Effects.WEIGHT.modify(vec2(DISTANCE), fragment(), view);
        float[][] backends = run("weight", vectorKernel(out, V2), 2, fragments, styles);
        float[] expected = new float[N * 2];
        for (int k = 0; k < N; k++) {
            float[] d = at(fragments, k, DISTANCE, 2);
            expected[k * 2] = d[0] + styles[k];
            expected[k * 2 + 1] = d[1] - styles[k];
        }
        compare("weight", expected, backends);
    }

    @Test
    void outlineAgrees() {
        Random random = new Random(2);
        float[] fragments = fragments(random);
        float[] styles = new float[N * 5];
        for (int k = 0; k < N; k++) {
            for (int c = 0; c < 4; c++) {
                styles[k * 5 + c] = range(random, 0, 1);
            }
            styles[k * 5 + 4] = range(random, 0.5f, 10f);
        }
        float[][] backends = run("outline", painterKernel(Effects.OUTLINE, view(OutlineStyleLayout.LAYOUT)), 4,
                fragments, styles);
        float[] expected = new float[N * 4];
        for (int k = 0; k < N; k++) {
            double aa = fragments[k * STRIDE + AA];
            double fromBand = Math.abs(fragments[k * STRIDE + DISTANCE]) - styles[k * 5 + 4] * 0.5;
            double cov = 1 - smoothstep(-aa, aa, fromBand);
            premultiplied(expected, k, styles, k * 5, cov);
        }
        compare("outline", expected, backends);
    }

    @Test
    void boxMorphAgrees() {
        Random random = new Random(3);
        float[] fragments = fragments(random);
        float[] styles = new float[N * 4];
        for (int k = 0; k < N; k++) {
            float hw = range(random, 8, 50);
            float hh = range(random, 8, 50);
            styles[k * 4] = hw;
            styles[k * 4 + 1] = hh;
            styles[k * 4 + 2] = range(random, 0, Math.min(hw, hh));
            styles[k * 4 + 3] = k % 4 == 0 ? 0f : k % 4 == 1 ? 1f : range(random, 0, 1);
        }
        Expr out = Effects.BOX_MORPH.distance(fragment(), vec2(OFFSET), view(BoxMorphStyleLayout.LAYOUT));
        float[][] backends = run("boxMorph", vectorKernel(out, V2), 2, fragments, styles);
        float[] expected = new float[N * 2];
        for (int k = 0; k < N; k++) {
            float[] local = at(fragments, k, LOCAL, 2);
            float[] offset = at(fragments, k, OFFSET, 2);
            float[] shape = at(fragments, k, SHAPE, 4);
            double lx = local[0] + offset[0];
            double ly = local[1] + offset[1];
            double a = -boxSdf(lx, ly, shape[0], shape[1], shape[2], shape[3]);
            double r = styles[k * 4 + 2];
            double b = -boxSdf(lx, ly, styles[k * 4], styles[k * 4 + 1], r, r);
            expected[k * 2] = (float) (a + (b - a) * styles[k * 4 + 3]);
            expected[k * 2 + 1] = 1e9f;
        }
        compare("boxMorph", expected, backends);
    }

    @Test
    void fillAgrees() {
        Random random = new Random(4);
        float[] fragments = fragments(random);
        float[] styles = new float[1];   // the fill has no style; the buffer is bound all the same
        float[][] backends = run("fill", painterKernel(CoreShadelets.FILL, view(StyleLayout.NONE)), 4, fragments,
                styles);
        float[] expected = new float[N * 4];
        for (int k = 0; k < N; k++) {
            double aa = fragments[k * STRIDE + AA];
            double cov = 1 - smoothstep(-aa, aa, -fragments[k * STRIDE + DISTANCE]);
            premultiplied(expected, k, fragments, k * STRIDE + COLOR, cov);
        }
        compare("fill", expected, backends);
    }

    // --- the kernels ---

    /** {@code out[gid * n + c] = value[c]}: a role that yields a value, a source's or a modifier's. */
    static Function vectorKernel(Expr value, Type.Vector type) {
        LocalVar result = new LocalVar("result", type);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(result, value));
        storeComponents(body, result, type.count());
        return main(body);
    }

    /** A painter into a layer the kernel declares, and the layer stored: {@code out[gid * 4 + c] = layer[c]}. */
    static Function painterKernel(Shadelet.Painter painter, StyleView view) {
        LocalVar layer = new LocalVar("layer", V4);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(layer, v4(f(0.0), f(0.0), f(0.0), f(0.0))));
        body.addAll(painter.paint(PaintContexts.of(fragment(), vec2(DISTANCE)), view, layer).statements());
        storeComponents(body, layer, 4);
        return main(body);
    }

    static void storeComponents(List<Statement> body, LocalVar value, int n) {
        Expr read = new Expr.Read(value);
        for (int c = 0; c < n; c++) {
            Expr component = n == 1 ? read : new Expr.VectorExtract(read, c);
            body.add(new Statement.BufferStore(OUT, add(mul(new Expr.InvocationId(), i(n)), i(c)), component));
        }
    }

    static Function main(List<Statement> body) {
        body.add(new Statement.ReturnVoid());
        return new Function("main", new Type.FunctionType(Type.VOID, List.of()),
                new dev.supirvast.vastir.core.Region(body));
    }

    /** The invocation's fragment, every field a load from {@link #FRAGMENTS}. */
    static Fragment fragment() {
        return new Fragment(vec4(COLOR), vec2(UV), vec2(LOCAL), vec4(SHAPE), vec4(FILTER), load(AA), load(PPU));
    }

    /**
     * The invocation's own style: shared fields at {@code gid * sharedFloats}, per-instance ones after all the shared,
     * at {@code gid * instanceFloats}, in the one {@link #STYLES} buffer, as the canvas's tables hold them in one.
     */
    static StyleView view(StyleLayout layout) {
        Expr gid = new Expr.InvocationId();
        Expr shared = mul(gid, i(layout.sharedFloats()));
        Expr instance = add(i(N * layout.sharedFloats()), mul(gid, i(layout.instanceFloats())));
        return new StyleView(layout, STYLES, shared, instance);
    }

    static Expr load(int k) {
        return new Expr.BufferLoad(FRAGMENTS, add(mul(new Expr.InvocationId(), i(STRIDE)), i(k)));
    }

    static Expr vec2(int k) {
        return v2(load(k), load(k + 1));
    }

    static Expr vec4(int k) {
        return v4(load(k), load(k + 1), load(k + 2), load(k + 3));
    }

    // --- running it ---

    /** The kernel on the CPU, then the GPU: {@code [cpu, gpu]}, or {@code [cpu, null]} with no device. */
    static float[][] run(String name, Function kernel, int outPerInvocation, float[] fragments, float[] styles) {
        byte[] spirv = new CoreToSpirv().lower(new CoreModule().addEntryPoint(EntryPoint.compute(kernel, 1, 1, 1)))
                .toByteArray();
        NativeTools tools = new NativeTools();
        if (tools.isAvailable()) {
            NativeTools.ValidationResult result = tools.validate(spirv);
            assertTrue(result.valid(), () -> "spirv-val rejected the " + name + " kernel:\n" + result.output());
        }

        CallTarget cpu = new CoreToTruffle().lowerKernel(kernel, BUFFERS);
        int[][] cpuBuffers = {new int[N * outPerInvocation], bits(fragments), bits(styles)};
        for (int k = 0; k < N; k++) {
            cpu.call(k, cpuBuffers);
        }
        float[] cpuOut = floats(cpuBuffers[0]);

        VulkanCompute vulkan = new VulkanCompute();
        if (!vulkan.isAvailable()) {
            return new float[][]{cpuOut, null};
        }
        int[][] gpuBuffers = {new int[N * outPerInvocation], bits(fragments), bits(styles)};
        float[] gpuOut = floats(vulkan.executeKernel(spirv, "main", gpuBuffers, N)[0]);
        return new float[][]{cpuOut, gpuOut};
    }

    /** CPU against the Java arithmetic, then GPU against CPU; skipped (or failed, if required) without a GPU. */
    static void compare(String name, float[] expected, float[][] backends) {
        float[] cpu = backends[0];
        Agreement cpuVsJava = Agreement.of(expected, cpu);
        assertTrue(cpuVsJava.maxAbs <= TOLERANCE, () -> name + ": CPU disagrees with the Java arithmetic, "
                + cpuVsJava + "\n at " + cpuVsJava.worst + ": java " + expected[cpuVsJava.worst] + ", cpu "
                + cpu[cpuVsJava.worst]);
        float[] gpu = backends[1];
        if (gpu == null) {
            if (REQUIRE_GPU) {
                fail(name + ": -Dsupirvast.requireGpu=true but no Vulkan compute device");
            }
            assumeTrue(false, name + ": no Vulkan compute device; the CPU side agreed with Java (" + cpuVsJava + ")");
        }
        Agreement gpuVsCpu = Agreement.of(cpu, gpu);
        System.out.printf("[agreement] %-8s CPU vs Java %s; GPU vs CPU %s%n", name, cpuVsJava, gpuVsCpu);
        assertTrue(gpuVsCpu.maxAbs <= TOLERANCE, () -> name + ": GPU and CPU disagree, " + gpuVsCpu
                + "\n at " + gpuVsCpu.worst + ": cpu " + cpu[gpuVsCpu.worst] + ", gpu " + gpu[gpuVsCpu.worst]);
    }

    /** How closely two float arrays agree: the largest absolute difference, the largest in ULPs, how many are exact. */
    record Agreement(double maxAbs, long maxUlps, int exact, int count, int worst) {
        static Agreement of(float[] a, float[] b) {
            double maxAbs = 0;
            long maxUlps = 0;
            int exact = 0;
            int worst = 0;
            for (int k = 0; k < a.length; k++) {
                if (Float.floatToIntBits(a[k]) == Float.floatToIntBits(b[k])) {
                    exact++;
                }
                double d = Math.abs((double) a[k] - b[k]);
                if (Float.isNaN(a[k]) != Float.isNaN(b[k])) {
                    d = Double.POSITIVE_INFINITY;
                }
                if (d > maxAbs) {
                    maxAbs = d;
                    worst = k;
                }
                maxUlps = Math.max(maxUlps, ulps(a[k], b[k]));
            }
            return new Agreement(maxAbs, maxUlps, exact, a.length, worst);
        }

        @Override
        public String toString() {
            return String.format("max |diff| %.3g, max %d ulp, %d of %d bit-exact", maxAbs, maxUlps, exact, count);
        }
    }

    static long ulps(float a, float b) {
        if (a == b) {
            return 0;
        }
        long ia = ordered(a);
        long ib = ordered(b);
        return Math.abs(ia - ib);
    }

    private static long ordered(float v) {
        int bits = Float.floatToIntBits(v);
        return bits < 0 ? (long) Integer.MIN_VALUE - bits : bits;
    }

    // --- inputs ---

    /**
     * {@link #N} fragments about rounded boxes of several sizes, their distances within a few pixels of an edge so
     * the soft edges are exercised, not only their saturated ends.
     */
    static float[] fragments(Random random) {
        float[] data = new float[N * STRIDE];
        for (int k = 0; k < N; k++) {
            int o = k * STRIDE;
            data[o + COLOR] = range(random, 0, 1);
            data[o + COLOR + 1] = range(random, 0, 1);
            data[o + COLOR + 2] = range(random, 0, 1);
            data[o + COLOR + 3] = range(random, 0.25f, 1);
            data[o + UV] = range(random, 0, 1);
            data[o + UV + 1] = range(random, 0, 1);
            float hw = range(random, 8, 50);
            float hh = range(random, 8, 50);
            data[o + LOCAL] = range(random, -hw - 8, hw + 8);
            data[o + LOCAL + 1] = range(random, -hh - 8, hh + 8);
            data[o + SHAPE] = hw;
            data[o + SHAPE + 1] = hh;
            data[o + SHAPE + 2] = range(random, 0, Math.min(hw, hh));
            data[o + SHAPE + 3] = range(random, 0, Math.min(hw, hh));
            data[o + AA] = k % 2 == 0 ? 1f : range(random, 0.25f, 3f);
            data[o + PPU] = 1f;
            data[o + DISTANCE] = range(random, -8, 8);
            data[o + DISTANCE + 1] = k % 3 == 0 ? 1e9f : range(random, 2, 30);
            data[o + OFFSET] = k % 2 == 0 ? 0f : range(random, -3, 3);
            data[o + OFFSET + 1] = k % 2 == 0 ? 0f : range(random, -3, 3);
        }
        return data;
    }

    static float range(Random random, float lo, float hi) {
        return lo + random.nextFloat() * (hi - lo);
    }

    static float[] at(float[] data, int k, int field, int n) {
        float[] out = new float[n];
        System.arraycopy(data, k * STRIDE + field, out, 0, n);
        return out;
    }

    static int[] bits(float[] floats) {
        int[] out = new int[floats.length];
        for (int k = 0; k < floats.length; k++) {
            out[k] = Float.floatToRawIntBits(floats[k]);
        }
        return out;
    }

    static float[] floats(int[] bits) {
        float[] out = new float[bits.length];
        for (int k = 0; k < bits.length; k++) {
            out[k] = Float.intBitsToFloat(bits[k]);
        }
        return out;
    }

    // --- the arithmetic, in Java ---

    static double smoothstep(double e0, double e1, double v) {
        double t = Math.min(Math.max((v - e0) / (e1 - e0), 0), 1);
        return t * t * (3 - 2 * t);
    }

    /** As {@code CoreShadelets.boxSdf}: positive outside, the radius by vertical half (y down, top negative). */
    static double boxSdf(double lx, double ly, double hw, double hh, double rTop, double rBottom) {
        double r = ly >= 0 ? rBottom : rTop;
        double qx = Math.abs(lx) - hw + r;
        double qy = Math.abs(ly) - hh + r;
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
        double inside = Math.min(Math.max(qx, qy), 0);
        return outside + inside - r;
    }

    /** {@code (rgb * a * cov, a * cov)} of the straight-alpha colour at {@code src[at..at+4)}, into invocation k. */
    static void premultiplied(float[] out, int k, float[] src, int at, double cov) {
        double alpha = src[at + 3] * cov;
        for (int c = 0; c < 3; c++) {
            out[k * 4 + c] = (float) (src[at + c] * alpha);
        }
        out[k * 4 + 3] = (float) alpha;
    }
}
