package dev.vexelray.canvas;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.type.Type;

/**
 * How an image is scaled: which source pixels make each pixel drawn, and how much of each. One list for the GPU, which
 * scales an image as it is drawn ({@link Canvas#image}), and for the CPU, which resamples one once
 * ({@code Resample} in vexelray-codec), so the two cannot offer different things or compute them differently.
 *
 * <p>Each filter is a weight as a function of distance, {@link #weight}, in source pixels from the point being made,
 * given the step between output pixels in source pixels (below 1 when enlarging). A filter that smooths widens with the
 * step when shrinking, so every source pixel under an output pixel is weighed and none is skipped.
 *
 * <ul>
 *   <li>{@link #NEAREST}: each output pixel one source pixel, unblended. Squares, hard-edged and stair-stepped.</li>
 *   <li>{@link #SHARP_BILINEAR}: each source pixel a square, and each output pixel the share of each square it
 *       covers. Square pixels with antialiased edges, at any zoom; an even average when shrinking.</li>
 *   <li>{@link #BILINEAR}: a straight blend of the nearest pixels. Soft, cheap, what a GPU does by itself.</li>
 *   <li>{@link #GAUSSIAN}: a bell, half a source pixel wide. No ringing, no pixel grid and no aliasing; soft.</li>
 *   <li>{@link #B_SPLINE}: the cubic B-spline, a bell that ends. Like Gaussian, a touch softer, cheaper.</li>
 *   <li>{@link #MITCHELL}: Mitchell-Netravali (B = C = 1/3), the usual balance of sharp, ringing and smooth.</li>
 *   <li>{@link #LANCZOS}: Lanczos-3. Sharpest, with a ring at hard edges and the pixel grid showing through.</li>
 * </ul>
 *
 * <p>On the GPU the last four are a loop of taps in the shader ({@link #kernel}, {@link #taps}): exact when enlarging,
 * and when shrinking the taps spread out to the step and each reads a bilinear blend, which is an approximation; the
 * CPU's resample is exact at any scale.
 */
public enum ImageFilter {

    NEAREST("Nearest", 0) {
        @Override
        public double weight(double t, double step) {
            return Math.abs(t) < Math.max(0.5, step / 2) ? 1 : 0;
        }

        @Override
        public double reach(double step) {
            return Math.max(0.5, step / 2);
        }

        @Override
        Expr kernel(Expr t) {
            return sub(one(), step(f(0.5), abs(t)));
        }
    },
    SHARP_BILINEAR("Sharp bilinear", 0) {
        /** The share of the source pixel centred {@code t} away that the output pixel's footprint covers. */
        @Override
        public double weight(double t, double step) {
            double h = Math.max(step, 1e-6) / 2;
            return Math.max(0, Math.min(t + 0.5, h) - Math.max(t - 0.5, -h));
        }

        @Override
        public double reach(double step) {
            return 0.5 + step / 2;
        }

        @Override
        Expr kernel(Expr t) {
            return Expr.MathCall.max(sub(one(), abs(t)), f(0.0));
        }
    },
    BILINEAR("Bilinear", 1) {
        @Override
        public double weight(double t, double step) {
            double s = Math.max(1, step);
            return Math.max(0, 1 - Math.abs(t / s));
        }

        @Override
        public double reach(double step) {
            return Math.max(1, step);
        }

        @Override
        Expr kernel(Expr t) {
            return Expr.MathCall.max(sub(one(), abs(t)), f(0.0));
        }
    },
    GAUSSIAN("Gaussian", 2) {
        /** Sigma half a source pixel: wide enough to hide the grid, no wider. */
        @Override
        public double weight(double t, double step) {
            double u = t / Math.max(1, step);
            return Math.abs(u) >= 2 ? 0 : Math.exp(-2 * u * u);
        }

        @Override
        public double reach(double step) {
            return 2 * Math.max(1, step);
        }

        @Override
        Expr kernel(Expr t) {
            return mul(Expr.MathCall.exp(mul(f(-2.0), mul(t, t))), sub(one(), step(f(2.0), abs(t))));
        }
    },
    B_SPLINE("B-spline", 2) {
        @Override
        public double weight(double t, double step) {
            return cubic(t / Math.max(1, step), 1, 0);
        }

        @Override
        public double reach(double step) {
            return 2 * Math.max(1, step);
        }

        @Override
        Expr kernel(Expr t) {
            return cubic(t, 1, 0);
        }
    },
    MITCHELL("Mitchell", 2) {
        @Override
        public double weight(double t, double step) {
            return cubic(t / Math.max(1, step), 1.0 / 3, 1.0 / 3);
        }

        @Override
        public double reach(double step) {
            return 2 * Math.max(1, step);
        }

        @Override
        Expr kernel(Expr t) {
            return cubic(t, 1.0 / 3, 1.0 / 3);
        }
    },
    LANCZOS("Lanczos", 3) {
        @Override
        public double weight(double t, double step) {
            double u = Math.abs(t / Math.max(1, step));
            if (u < 1e-9) {
                return 1;
            }
            if (u >= 3) {
                return 0;
            }
            double pu = Math.PI * u;
            return 3 * Math.sin(pu) * Math.sin(pu / 3) / (pu * pu);
        }

        @Override
        public double reach(double step) {
            return 3 * Math.max(1, step);
        }

        @Override
        Expr kernel(Expr t) {
            Expr a = abs(t);
            Expr pu = mul(f(Math.PI), a);
            Expr sinc = div(mul(f(3.0), mul(Expr.MathCall.sin(pu), Expr.MathCall.sin(div(pu, f(3.0))))),
                    Expr.MathCall.max(mul(pu, pu), f(1e-8)));
            // 1 at the centre, where the formula is 0/0; nothing from 3 out.
            Expr centred = Expr.MathCall.mix(f(1.0), sinc, step(f(1e-4), a));
            return mul(centred, sub(one(), step(f(3.0), a)));
        }
    };

    private final String label;
    private final int taps;

    ImageFilter(String label, int taps) {
        this.label = label;
        this.taps = taps;
    }

    /** What to call it in a list of them. */
    public String label() {
        return label;
    }

    /**
     * How much the source pixel {@code t} source pixels from the point being made counts, when output pixels are
     * {@code step} source pixels apart. Not normalised: a resampler divides by the sum.
     */
    public abstract double weight(double t, double step);

    /** How far from the point being made a source pixel can count, in source pixels, at {@code step}. */
    public abstract double reach(double step);

    /**
     * The weight in the shader at {@code t}, in units of the filter's scale (source pixels when enlarging): the same
     * curve as {@link #weight}. Read by the shader for the filters it runs as a loop of taps.
     */
    abstract Expr kernel(Expr t);

    /** How many taps either side the shader's loop reads: the filter's radius at scale 1. 0 for the special paths. */
    int taps() {
        return taps;
    }

    /** The Mitchell-Netravali cubic at {@code t}, with its {@code b} and {@code c}. */
    static double cubic(double t, double b, double c) {
        double a = Math.abs(t);
        if (a < 1) {
            return ((12 - 9 * b - 6 * c) * a * a * a + (-18 + 12 * b + 6 * c) * a * a + (6 - 2 * b)) / 6;
        }
        if (a < 2) {
            return ((-b - 6 * c) * a * a * a + (6 * b + 30 * c) * a * a + (-12 * b - 48 * c) * a + (8 * b + 24 * c))
                    / 6;
        }
        return 0;
    }

    /** The same in the shader. */
    static Expr cubic(Expr t, double b, double c) {
        Expr a = abs(t);
        Expr a2 = mul(a, a);
        Expr a3 = mul(a2, a);
        Expr inner = div(add(add(mul(f(12 - 9 * b - 6 * c), a3), mul(f(-18 + 12 * b + 6 * c), a2)), f(6 - 2 * b)),
                f(6.0));
        Expr outer = div(add(add(add(mul(f(-b - 6 * c), a3), mul(f(6 * b + 30 * c), a2)), mul(f(-12 * b - 48 * c), a)),
                f(8 * b + 24 * c)), f(6.0));
        return mul(Expr.MathCall.mix(inner, outer, step(f(1.0), a)), sub(one(), step(f(2.0), a)));
    }

    // --- IR helpers, scalar float ---
    private static final Type.Float F32 = Type.float32();

    static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr one() {
        return f(1.0);
    }

    private static Expr abs(Expr v) {
        return Expr.MathCall.abs(v);
    }

    /** 1 where {@code x >= edge}, else 0. */
    private static Expr step(Expr edge, Expr x) {
        return Expr.MathCall.step(edge, x);
    }

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
    }

    private static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.SUB, a, b);
    }

    private static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.MUL, a, b);
    }

    private static Expr div(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.DIV, a, b);
    }
}
