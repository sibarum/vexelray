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
 *   <li>{@link #GAUSSIAN}: a bell, half a source pixel wide, read at points. No ringing; soft, with a trace of the
 *       pixel grid left at the finest detail.</li>
 *   <li>{@link #NORMAL}: the same bell over pixels that are squares, its area taken across each output pixel's whole
 *       footprint rather than its height at the centre. The B-spline's spread exactly, at every scale.</li>
 *   <li>{@link #B_SPLINE}: the cubic B-spline, a bell that ends. No ringing, and no trace of the pixel grid.</li>
 *   <li>{@link #MITCHELL}: Mitchell-Netravali (B = C = 1/3), the usual balance of sharp, ringing and smooth.</li>
 *   <li>{@link #LANCZOS}: Lanczos-3. Sharpest, with a ring at hard edges and the pixel grid showing through.</li>
 * </ul>
 *
 * <p>On the GPU the last five are a loop of taps in the shader ({@link #kernel}, {@link #taps}): exact when enlarging,
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
        Expr kernel(Expr t, Expr rate) {
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
        Expr kernel(Expr t, Expr rate) {
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
        Expr kernel(Expr t, Expr rate) {
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
        Expr kernel(Expr t, Expr rate) {
            return mul(Expr.MathCall.exp(mul(f(-2.0), mul(t, t))), sub(one(), step(f(2.0), abs(t))));
        }
    },
    NORMAL("Normal", 3) {
        /**
         * Each source pixel a square, blurred by a Gaussian, and the share of it over the output pixel's whole
         * footprint: the bell's area across the pixel, edge to edge, not its height at the centre. Sigma is chosen
         * so the whole has exactly the B-spline's spread at every scale: the square's twelfth, the footprint's
         * twelfth, and the bell's sigma squared sum to a third of the scale squared.
         */
        @Override
        public double weight(double t, double step) {
            double sigma = normalSigma(step);
            double h = Math.max(step, 1e-6) / 2;
            double a = t - 0.5;
            double b = t + 0.5;
            if (h < 1e-4) {
                return phiCdf(-a / sigma) - phiCdf(-b / sigma);
            }
            return sigma * (psi((h - a) / sigma) - psi((-h - a) / sigma) - psi((h - b) / sigma)
                    + psi((-h - b) / sigma)) / (2 * h);
        }

        @Override
        public double reach(double step) {
            return 0.5 + Math.max(step, 0) / 2 + 4 * normalSigma(step);
        }

        @Override
        Expr kernel(Expr t, Expr rate) {
            // In the loop's units: source pixels enlarging; the rate's widths shrinking, where the taps spread.
            Expr s = Expr.MathCall.max(rate, one());
            Expr r2 = mul(rate, rate);
            Expr sigmaPx = Expr.MathCall.sqrt(Expr.MathCall.max(
                    sub(sub(div(mul(s, s), f(3.0)), f(1.0 / 12)), div(r2, f(12.0))), f(1e-4)));
            Expr sigma = div(sigmaPx, s);
            Expr h = Expr.MathCall.max(div(mul(f(0.5), rate), s), f(1e-4));
            Expr half = div(f(0.5), s);
            Expr a = sub(t, half);
            Expr b = add(t, half);
            Expr area = add(sub(sub(psi(div(sub(h, a), sigma)), psi(div(sub(neg(h), a), sigma))),
                    psi(div(sub(h, b), sigma))), psi(div(sub(neg(h), b), sigma)));
            return Expr.MathCall.max(div(mul(sigma, area), mul(f(2.0), h)), f(0.0));
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
        Expr kernel(Expr t, Expr rate) {
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
        Expr kernel(Expr t, Expr rate) {
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
        Expr kernel(Expr t, Expr rate) {
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
     * curve as {@link #weight}. Read by the shader for the filters it runs as a loop of taps. {@code rate} is the
     * source pixels across one output pixel, which only a filter that integrates over the pixel needs.
     */
    abstract Expr kernel(Expr t, Expr rate);

    /** {@link #NORMAL}'s sigma at {@code step}: the B-spline's spread, less the source square's and the footprint's. */
    static double normalSigma(double step) {
        double s = Math.max(1, step);
        return Math.sqrt(Math.max(1e-4, s * s / 3 - 1.0 / 12 - step * step / 12));
    }

    /** The standard normal's area to the left of {@code x}. */
    static double phiCdf(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    /** The area under {@link #phiCdf} to the left of {@code x}: {@code x Phi(x) + phi(x)}. */
    static double psi(double x) {
        return x * phiCdf(x) + Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /** The error function, by Abramowitz and Stegun 7.1.26: within 1.5e-7. */
    static double erf(double x) {
        double s = Math.signum(x);
        double a = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * a);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-a * a);
        return s * y;
    }

    /** {@link #psi} in the shader, with the same error function. */
    static Expr psi(Expr x) {
        Expr a = abs(mul(x, f(Math.sqrt(0.5))));
        Expr t = div(one(), add(one(), mul(f(0.3275911), a)));
        Expr poly = add(mul(add(mul(add(mul(add(mul(f(1.061405429), t), f(-1.453152027)), t), f(1.421413741)), t),
                f(-0.284496736)), t), f(0.254829592));
        Expr erfAbs = sub(one(), mul(mul(poly, t), Expr.MathCall.exp(neg(mul(a, a)))));
        Expr erf = mul(Expr.MathCall.sign(x), erfAbs);
        Expr cdf = mul(f(0.5), add(one(), erf));
        Expr pdf = mul(Expr.MathCall.exp(mul(f(-0.5), mul(x, x))), f(1 / Math.sqrt(2 * Math.PI)));
        return add(mul(x, cdf), pdf);
    }

    private static Expr neg(Expr v) {
        return sub(f(0.0), v);
    }

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
