package dev.vexelray.canvas;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.clamp;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.lt;
import static dev.vexelray.ir.Ir.max;
import static dev.vexelray.ir.Ir.mix;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.step;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v4;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.y;
import static dev.vexelray.ir.Ir.z;

/** How the image painter scales a picture: the filter a vertex names, applied to its texel. */
public final class ImageFilters {

    private ImageFilters() {
    }

    /**
     * Replace {@code texel}, the sampler's bilinear read at {@code uv}, with the image scaled by the filter the
     * vertex names: {@code filter} is {@code (ImageFilter.ordinal() + 1, textureWidth, textureHeight,
     * texelsPerPixel)}, and 0 in its first component leaves the bilinear read alone, which is what every image
     * drawn without a filter carries.
     *
     * <p>{@link ImageFilter#NEAREST} and {@link ImageFilter#SHARP_BILINEAR} move the one read: to the nearest texel's
     * centre, or, for sharp bilinear, to where a blend across a texel edge happens only within the screen pixel that
     * edge falls in. The filters with a kernel read a square of texels and weigh each by
     * {@link ImageFilter#kernel}, with premultiplied alpha. Enlarging, the taps are the texels themselves, so it is
     * exact. Shrinking, they spread to the texels-per-pixel and each reads a bilinear blend, which keeps the tap
     * count fixed and is an approximation: a resample on the CPU is exact.
     */
    public static Statement filtered(Texture image, Expr uv, Expr filter, LocalVar texel) {
        // A linear-light texture adds Canvas.LINEAR_FLAG to the mode: take it off, and encode the result at the end.
        Expr linear = step(f(Canvas.LINEAR_FLAG / 2.0), x(filter));
        Expr mode = sub(x(filter), mul(linear, f(Canvas.LINEAR_FLAG)));
        Expr sizeW = y(filter);
        Expr sizeH = z(filter);
        Expr rate = w(filter);
        // Texel coordinates, with whole numbers at texel centres.
        Expr px = sub(mul(x(uv), sizeW), f(0.5));
        Expr py = sub(mul(y(uv), sizeH), f(0.5));
        // Shrinking, the two filters that move the one read leave it to the mip chain, which averages what they
        // would skip. Enlarging, they read level 0 by name: their coordinates jump at every texel's edge, and a
        // level chosen from those jumps would be a blurred one, in lines.
        Expr enlarging = lt(rate, f(1.0));

        // Nearest: the centre of the texel the point falls in.
        Expr nearestUv = v2(
                div(add(Expr.MathCall.floor(add(px, f(0.5))), f(0.5)), sizeW),
                div(add(Expr.MathCall.floor(add(py, f(0.5))), f(0.5)), sizeH));
        Region nearest = Region.of(new Statement.If(enlarging,
                Region.of(new Statement.Assign(texel, new Expr.SampleTexture(image, nearestUv, f(0.0)))),
                Region.of()));

        // Sharp bilinear: within a texel, flat; across its edge, a blend one screen pixel wide.
        Expr pixelsPerTexel = max(f(1.0), div(f(1.0), max(rate, f(1e-6))));
        Expr sharpUv = v2(div(sharpAxis(px, pixelsPerTexel), sizeW), div(sharpAxis(py, pixelsPerTexel), sizeH));
        Region sharp = Region.of(new Statement.If(enlarging,
                Region.of(new Statement.Assign(texel, new Expr.SampleTexture(image, sharpUv, f(0.0)))),
                Region.of()));

        // Mode is ordinal + 1, so a filter is "mode < ordinal + 1.5" once the ones before it have been passed. The
        // kernels' chain is built from the last back, each one's else the chain of those after it.
        ImageFilter[] filters = ImageFilter.values();
        Region kernels = Region.of();
        for (int i = filters.length - 1; i >= 0; i--) {
            ImageFilter fl = filters[i];
            if (fl.taps() < 2) {
                break;   // bilinear and before: the special paths below, and the sampler's own
            }
            kernels = Region.of(new Statement.If(lt(mode, f(fl.ordinal() + 1.5)),
                    kernelTaps(image, fl, px, py, sizeW, sizeH, rate, texel), kernels));
        }
        Statement scale = new Statement.If(lt(mode, f(0.5)), Region.of(), Region.of(
                new Statement.If(lt(mode, f(ImageFilter.NEAREST.ordinal() + 1.5)), nearest, Region.of(
                        new Statement.If(lt(mode, f(ImageFilter.SHARP_BILINEAR.ordinal() + 1.5)), sharp, Region.of(
                                new Statement.If(lt(mode, f(ImageFilter.BILINEAR.ordinal() + 1.5)), Region.of(),
                                        kernels)))))));
        // Linear light out, sRGB again: what the frame is blended in.
        Expr t = new Expr.Read(texel);
        Statement encode = new Statement.If(lt(linear, f(0.5)), Region.of(), Region.of(new Statement.Assign(texel,
                v4(srgb(x(t)), srgb(y(t)), srgb(z(t)), w(t)))));
        return new Statement.If(lt(x(filter), f(0.5)), Region.of(), Region.of(scale, encode));
    }

    /** One channel of linear light, {@code 0..1}, as sRGB. */
    private static Expr srgb(Expr linear) {
        Expr c = clamp(linear, f(0.0), f(1.0));
        Expr low = mul(c, f(12.92));
        Expr high = sub(mul(f(1.055), Expr.MathCall.pow(c, f(1 / 2.4))), f(0.055));
        return mix(low, high, step(f(0.0031308), c));
    }

    /** One axis of sharp bilinear: texel coordinate {@code p} to the coordinate to read, before dividing by size. */
    private static Expr sharpAxis(Expr p, Expr pixelsPerTexel) {
        Expr i = Expr.MathCall.floor(p);
        Expr fr = sub(p, i);
        Expr g = clamp(add(mul(sub(fr, f(0.5)), pixelsPerTexel), f(0.5)), f(0.0), f(1.0));
        return add(add(i, g), f(0.5));
    }

    /**
     * {@code filter}'s loop of taps: {@code 2 * taps} a side. Enlarging (fewer than one texel a pixel) they are the
     * texels round the point, weighed by their distance; shrinking they spread {@code rate} texels apart about it.
     */
    private static Region kernelTaps(Texture image, ImageFilter filter, Expr px, Expr py, Expr sizeW, Expr sizeH,
                                     Expr rate, LocalVar texel) {
        String n = filter.name().toLowerCase(java.util.Locale.ROOT);
        int r = filter.taps();
        LocalVar scale = new LocalVar(n + "Scale", F32);
        LocalVar spread = new LocalVar(n + "Spread", F32);
        LocalVar baseX = new LocalVar(n + "BaseX", F32);
        LocalVar baseY = new LocalVar(n + "BaseY", F32);
        LocalVar jy = new LocalVar(n + "Jy", F32);
        LocalVar jx = new LocalVar(n + "Jx", F32);
        LocalVar posX = new LocalVar(n + "PosX", F32);
        LocalVar posY = new LocalVar(n + "PosY", F32);
        LocalVar wy = new LocalVar(n + "Wy", F32);
        LocalVar wt = new LocalVar(n + "W", F32);
        LocalVar tap = new LocalVar(n + "Tap", V4);
        LocalVar accR = new LocalVar(n + "R", F32);
        LocalVar accG = new LocalVar(n + "G", F32);
        LocalVar accB = new LocalVar(n + "B", F32);
        LocalVar accA = new LocalVar(n + "A", F32);
        LocalVar sum = new LocalVar(n + "Sum", F32);

        Expr s = new Expr.Read(scale);
        Expr d = new Expr.Read(spread);
        // Enlarging, spread 1 and the taps on texel centres; shrinking, spread s about the point itself.
        Expr shrinking = step(f(1.0001), s);
        Expr bx = mix(add(Expr.MathCall.floor(px), f(1 - r)), sub(px, mul(f(r - 0.5), s)), shrinking);
        Expr by = mix(add(Expr.MathCall.floor(py), f(1 - r)), sub(py, mul(f(r - 0.5), s)), shrinking);

        Expr tapUv = v2(div(add(new Expr.Read(posX), f(0.5)), sizeW), div(add(new Expr.Read(posY), f(0.5)), sizeH));
        Expr t = new Expr.Read(tap);
        Expr weight = new Expr.Read(wt);
        Expr wa = mul(weight, w(t));
        Region inner = Region.of(
                new Statement.Assign(posX, add(new Expr.Read(baseX), mul(new Expr.Read(jx), d))),
                new Statement.Assign(wt, mul(new Expr.Read(wy), filter.kernel(div(sub(new Expr.Read(posX), px), s), rate))),
                new Statement.Assign(tap, new Expr.SampleTexture(image, tapUv)),
                new Statement.Assign(accR, add(new Expr.Read(accR), mul(wa, x(t)))),
                new Statement.Assign(accG, add(new Expr.Read(accG), mul(wa, y(t)))),
                new Statement.Assign(accB, add(new Expr.Read(accB), mul(wa, z(t)))),
                new Statement.Assign(accA, add(new Expr.Read(accA), wa)),
                new Statement.Assign(sum, add(new Expr.Read(sum), weight)),
                new Statement.Assign(jx, add(new Expr.Read(jx), f(1.0))));
        Region outer = Region.of(
                new Statement.Assign(posY, add(new Expr.Read(baseY), mul(new Expr.Read(jy), d))),
                new Statement.Assign(wy, filter.kernel(div(sub(new Expr.Read(posY), py), s), rate)),
                new Statement.Assign(jx, f(0.0)),
                new Statement.While(lt(new Expr.Read(jx), f(2.0 * r)), inner),
                new Statement.Assign(jy, add(new Expr.Read(jy), f(1.0))));

        // Out of premultiplied: colour over coverage, coverage over the weights. Lobes below zero can overshoot.
        Expr a = new Expr.Read(accA);
        Expr cover = max(a, f(1e-6));
        Expr result = v4(
                clamp(div(new Expr.Read(accR), cover), f(0.0), f(1.0)),
                clamp(div(new Expr.Read(accG), cover), f(0.0), f(1.0)),
                clamp(div(new Expr.Read(accB), cover), f(0.0), f(1.0)),
                clamp(div(a, max(new Expr.Read(sum), f(1e-6))), f(0.0), f(1.0)));
        return Region.of(
                new Statement.DeclareVar(scale, max(f(1.0), rate)),
                new Statement.DeclareVar(spread, mix(f(1.0), s, shrinking)),
                new Statement.DeclareVar(baseX, bx),
                new Statement.DeclareVar(baseY, by),
                new Statement.DeclareVar(posX, f(0.0)),
                new Statement.DeclareVar(posY, f(0.0)),
                new Statement.DeclareVar(wy, f(0.0)),
                new Statement.DeclareVar(wt, f(0.0)),
                new Statement.DeclareVar(tap, v4(0.0, 0.0, 0.0, 0.0)),
                new Statement.DeclareVar(accR, f(0.0)),
                new Statement.DeclareVar(accG, f(0.0)),
                new Statement.DeclareVar(accB, f(0.0)),
                new Statement.DeclareVar(accA, f(0.0)),
                new Statement.DeclareVar(sum, f(0.0)),
                new Statement.DeclareVar(jx, f(0.0)),
                new Statement.DeclareVar(jy, f(0.0)),
                new Statement.While(lt(new Expr.Read(jy), f(2.0 * r)), outer),
                new Statement.Assign(texel, result));
    }
}
