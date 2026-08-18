package dev.vexelray.canvas;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.supirvast.vastir.shader.ShaderSource;
import dev.supirvast.vastir.shader.Shaders;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.shader.ComposedShader;

import java.util.List;

/**
 * The Canvas uber-shader, authored as SupirVast {@code core} IR. One vertex + fragment pair draws every primitive
 * in the {@link CanvasVertex} format; the fragment branches on {@code kind}:
 *
 * <ul>
 *   <li><b>shape</b> ({@link CanvasVertex#KIND_SHAPE}) — an analytic rounded-box signed distance evaluated in the
 *       primitive's local pixel frame ({@code local}), with {@code (halfW, halfH, cornerRadius, aa)} in
 *       {@code shape}. A rect is radius 0; a circle is a square with radius = half-size; a line is a thin rounded
 *       rect. Anti-aliased with a {@code smoothstep} across {@code aa} pixels (geometry is authored 1:1 in pixels).</li>
 *   <li><b>glyph</b> ({@link CanvasVertex#KIND_GLYPH}) — MSDF: median of the atlas texel, converted to coverage
 *       with a per-vertex {@code screenPxRange} ({@code shape.x}). Colour and range are per-vertex, so mixed text
 *       sizes and colours coexist in one draw.</li>
 * </ul>
 *
 * The atlas is a combined image sampler at set 0, binding 0 (bound even for shape-only canvases; shapes ignore it).
 */
public final class CanvasShader {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V3 = new Type.Vector(F32, 3);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

    public static final int ATLAS_SET = 0;
    public static final int ATLAS_BINDING = 0;

    private CanvasShader() {
    }

    /**
     * Vertex stage: pass clip-space position through and forward every per-vertex attribute as a varying.
     *
     * <p>Loads the SPIR-V pre-compiled at build time by the supirvast-maven-plugin (see {@link Vertex}); only a
     * build without the plugin (IDE, partial classpath) falls back to lowering the IR in-process.
     */
    public static ComposedShader vertex() {
        return new ComposedShader(ShaderStage.VERTEX, Shaders.loadOrLower(new Vertex()), "main");
    }

    /** As {@link #vertex()} for the fragment stage: pre-compiled by the build, lowered in-process only as fallback. */
    public static ComposedShader fragment() {
        return new ComposedShader(ShaderStage.FRAGMENT, Shaders.loadOrLower(new Fragment()), "main");
    }

    /** The canvas vertex stage as a build-discoverable {@link ShaderSource} ({@code canvas.vert.spv} in the jar). */
    public static final class Vertex implements ShaderSource {
        @Override
        public String name() {
            return "canvas.vert";
        }

        @Override
        public CoreModule module() {
            return vertexModule();
        }
    }

    /** The canvas fragment stage as a build-discoverable {@link ShaderSource} ({@code canvas.frag.spv} in the jar). */
    public static final class Fragment implements ShaderSource {
        @Override
        public String name() {
            return "canvas.frag";
        }

        @Override
        public CoreModule module() {
            return fragmentModule();
        }
    }

    private static CoreModule vertexModule() {
        InterfaceVar inPos = InterfaceVar.input("inPos", CanvasVertex.LOC_POS, V2);
        InterfaceVar inColor = InterfaceVar.input("inColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar inUv = InterfaceVar.input("inUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar inKind = InterfaceVar.input("inKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar inLocal = InterfaceVar.input("inLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar inShape = InterfaceVar.input("inShape", CanvasVertex.LOC_SHAPE, V4);
        InterfaceVar inClipBox = InterfaceVar.input("inClipBox", CanvasVertex.LOC_CLIPBOX, V4);
        InterfaceVar inClipRs = InterfaceVar.input("inClipRs", CanvasVertex.LOC_CLIPRS, V4);

        InterfaceVar vColor = InterfaceVar.output("vColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar vUv = InterfaceVar.output("vUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar vKind = InterfaceVar.output("vKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar vLocal = InterfaceVar.output("vLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar vShape = InterfaceVar.output("vShape", CanvasVertex.LOC_SHAPE, V4);
        InterfaceVar vClipBox = InterfaceVar.output("vClipBox", CanvasVertex.LOC_CLIPBOX, V4);
        InterfaceVar vClipRs = InterfaceVar.output("vClipRs", CanvasVertex.LOC_CLIPRS, V4);

        Expr pos = new Expr.InterfaceRead(inPos);
        Expr clip = new Expr.VectorConstruct(V4, List.of(
                new Expr.VectorExtract(pos, 0), new Expr.VectorExtract(pos, 1), f(0.0), f(1.0)));
        Region body = Region.of(
                new Statement.BuiltinWrite(Builtin.POSITION, clip),
                new Statement.InterfaceWrite(vColor, new Expr.InterfaceRead(inColor)),
                new Statement.InterfaceWrite(vUv, new Expr.InterfaceRead(inUv)),
                new Statement.InterfaceWrite(vKind, new Expr.InterfaceRead(inKind)),
                new Statement.InterfaceWrite(vLocal, new Expr.InterfaceRead(inLocal)),
                new Statement.InterfaceWrite(vShape, new Expr.InterfaceRead(inShape)),
                new Statement.InterfaceWrite(vClipBox, new Expr.InterfaceRead(inClipBox)),
                new Statement.InterfaceWrite(vClipRs, new Expr.InterfaceRead(inClipRs)),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
    }

    /** Fragment stage: branch on {@code kind} — one rounded-box SDF, several transfer functions; MSDF for glyphs. */
    private static CoreModule fragmentModule() {
        InterfaceVar vColor = InterfaceVar.input("vColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar vUv = InterfaceVar.input("vUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar vKind = InterfaceVar.input("vKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar vLocal = InterfaceVar.input("vLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar vShape = InterfaceVar.input("vShape", CanvasVertex.LOC_SHAPE, V4);
        InterfaceVar vClipBox = InterfaceVar.input("vClipBox", CanvasVertex.LOC_CLIPBOX, V4);
        InterfaceVar vClipRs = InterfaceVar.input("vClipRs", CanvasVertex.LOC_CLIPRS, V4);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        Texture atlas = new Texture("uAtlas", ATLAS_SET, ATLAS_BINDING);

        Expr color = new Expr.InterfaceRead(vColor);
        Expr rgb = new Expr.VectorConstruct(V3, List.of(x(color), y(color), z(color)));

        // --- clip: rounded-box SDF coverage at the fragment's screen position, multiplied into alpha ---
        Expr clipBox = new Expr.InterfaceRead(vClipBox);
        Expr clipRs = new Expr.InterfaceRead(vClipRs);
        Expr clipCenter = new Expr.VectorConstruct(V2, List.of(x(clipBox), y(clipBox)));
        Expr clipHalf = new Expr.VectorConstruct(V2, List.of(z(clipBox), w(clipBox)));
        Expr fragScreen = new Expr.VectorConstruct(V2, List.of(x(clipRs), y(clipRs)));
        Expr clipR = z(clipRs);
        Expr clipAa = w(clipRs);
        Expr cl = sub(fragScreen, clipCenter);
        Expr qc = add(sub(Expr.MathCall.abs(cl), clipHalf), new Expr.VectorConstruct(V2, List.of(clipR, clipR)));
        Expr outsideC = Expr.MathCall.length(Expr.MathCall.max(qc, v2(0.0, 0.0)));
        Expr insideC = Expr.MathCall.min(Expr.MathCall.max(x(qc), y(qc)), f(0.0));
        Expr dc = sub(add(outsideC, insideC), clipR);
        Expr clipCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(clipAa), clipAa, dc));

        Expr alpha = mul(w(color), clipCov);
        Expr shape = new Expr.InterfaceRead(vShape);
        Expr uv = new Expr.InterfaceRead(vUv);

        // --- shape kinds: one analytic rounded-box SDF, several transfer functions over its distance ---
        Expr local = new Expr.InterfaceRead(vLocal);
        Expr half = new Expr.VectorConstruct(V2, List.of(x(shape), y(shape)));
        Expr rTop = z(shape);
        Expr rBottom = w(shape);
        // AA is a constant of the system (geometry is authored 1:1 in pixels), not per-vertex data — which is
        // what freed shape.w to carry the second radius.
        Expr aa = f(1.0);
        Expr d = roundedBoxSdf(local, half, rTop, rBottom);

        // KIND_SHAPE — flat fill.
        Expr shapeCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, d));
        Expr shapeOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, shapeCov)));

        // KIND_SHADOW — coverage is a soft falloff over uv.x blur px around the edge; squared, so the tail eases
        // out gaussian-ish instead of stopping dead at the smoothstep edge. Also an outer glow when tinted.
        Expr blur = Expr.MathCall.max(x(uv), aa);
        Expr shadowS = sub(f(1.0), Expr.MathCall.smoothstep(neg(blur), blur, d));
        Expr shadowCov = mul(shadowS, shadowS);
        Expr shadowOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, shadowCov)));

        // KIND_STROKE — a ring of width uv.x hugging the inside of the edge: abs(d + w/2) - w/2 re-centres the
        // zero level set onto the ring, then the normal AA coverage applies.
        Expr halfWStroke = mul(x(uv), f(0.5));
        Expr dRing = sub(Expr.MathCall.abs(add(d, halfWStroke)), halfWStroke);
        Expr strokeCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, dRing));
        Expr strokeOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, strokeCov)));

        // KIND_LIT — fill coverage, colour modulated by light. The emboss trick: evaluate the same SDF a second
        // time at the fragment shifted toward a fixed top-left light; the difference (bounded by the shift, since
        // an SDF is 1-Lipschitz) is +1 on light-facing edges and -1 on shaded ones. A band mask confines it to
        // uv.x bevel px inside the edge so the interior stays flat. uv.y adds a vertical luminance gradient.
        Expr bevel = Expr.MathCall.max(x(uv), f(1.0));
        Expr lightOff = f(1.5);
        Expr shift = mul(lightOff, f(0.7071));   // unit top-left light dir scaled to the offset
        Expr local2 = sub(local, new Expr.VectorConstruct(V2, List.of(shift, shift)));
        Expr d2 = roundedBoxSdf(local2, half, rTop, rBottom);
        Expr light = div(sub(d2, d), lightOff);                        // in [-1, 1]: an SDF is 1-Lipschitz
        Expr band = Expr.MathCall.clamp(add(f(1.0), div(d, bevel)), f(0.0), f(1.0));
        Expr grad = y(uv);
        Expr gy = Expr.MathCall.clamp(
                div(add(y(local), y(half)), mul(f(2.0), Expr.MathCall.max(y(half), f(1.0)))),
                f(0.0), f(1.0));
        Expr brightness = mul(add(f(1.0), mul(mul(light, band), f(0.45))),
                Expr.MathCall.mix(add(f(1.0), grad), sub(f(1.0), grad), gy));
        Expr litRgb = Expr.MathCall.clamp(
                new Expr.VectorConstruct(V3, List.of(mul(x(rgb), brightness), mul(y(rgb), brightness),
                        mul(z(rgb), brightness))),
                new Expr.VectorConstruct(V3, List.of(f(0.0), f(0.0), f(0.0))),
                new Expr.VectorConstruct(V3, List.of(f(1.0), f(1.0), f(1.0))));
        Expr litOut = new Expr.VectorConstruct(V4,
                List.of(x(litRgb), y(litRgb), z(litRgb), mul(alpha, shapeCov)));

        // KIND_GLYPH — MSDF median + screenPxRange.
        Expr msd = new Expr.SampleTexture(atlas, uv);
        Expr median = Expr.MathCall.max(Expr.MathCall.min(x(msd), y(msd)),
                Expr.MathCall.min(Expr.MathCall.max(x(msd), y(msd)), z(msd)));
        Expr spr = x(shape);
        Expr glyphCov = Expr.MathCall.clamp(
                add(mul(spr, sub(median, f(0.5))), f(0.5)), f(0.0), f(1.0));
        Expr glyphOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, glyphCov)));

        // Dispatch: kind < 0.5 shape, < 1.5 glyph, < 2.5 shadow, < 3.5 stroke, else lit.
        Expr kind = new Expr.InterfaceRead(vKind);
        Region litRegion = Region.of(new Statement.InterfaceWrite(fragColor, litOut));
        Region strokeRegion = Region.of(new Statement.InterfaceWrite(fragColor, strokeOut));
        Region shadowRegion = Region.of(new Statement.InterfaceWrite(fragColor, shadowOut));
        Region glyphRegion = Region.of(new Statement.InterfaceWrite(fragColor, glyphOut));
        Region shapeRegion = Region.of(new Statement.InterfaceWrite(fragColor, shapeOut));
        Region body = Region.of(
                new Statement.If(lt(kind, 0.5), shapeRegion, Region.of(
                        new Statement.If(lt(kind, 1.5), glyphRegion, Region.of(
                                new Statement.If(lt(kind, 2.5), shadowRegion, Region.of(
                                        new Statement.If(lt(kind, 3.5), strokeRegion, litRegion))))))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /**
     * The analytic rounded-box SDF at {@code local}, for half-extents {@code half}, with the corner radius
     * selected by vertical half: {@code rTop} above the centre line, {@code rBottom} below (local is y-down, so
     * top is negative y). A uniform box passes the same value twice; a tab passes {@code (r, 0)}.
     */
    private static Expr roundedBoxSdf(Expr local, Expr half, Expr rTop, Expr rBottom) {
        Expr r = Expr.MathCall.mix(rTop, rBottom, Expr.MathCall.step(f(0.0), y(local)));
        Expr q = add(sub(Expr.MathCall.abs(local), half), new Expr.VectorConstruct(V2, List.of(r, r)));
        Expr outside = Expr.MathCall.length(Expr.MathCall.max(q, v2(0.0, 0.0)));
        Expr inside = Expr.MathCall.min(Expr.MathCall.max(x(q), y(q)), f(0.0));
        return sub(add(outside, inside), r);
    }

    // --- tiny IR helpers ---
    private static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr v2(double a, double b) {
        return new Expr.VectorConstruct(V2, List.of(f(a), f(b)));
    }

    private static Expr x(Expr v) {
        return new Expr.VectorExtract(v, 0);
    }

    private static Expr y(Expr v) {
        return new Expr.VectorExtract(v, 1);
    }

    private static Expr z(Expr v) {
        return new Expr.VectorExtract(v, 2);
    }

    private static Expr w(Expr v) {
        return new Expr.VectorExtract(v, 3);
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

    private static Expr neg(Expr a) {
        return new Expr.Binary(BinaryOp.SUB, f(0.0), a);
    }

    private static Expr lt(Expr a, double b) {
        return new Expr.Binary(BinaryOp.LESS_THAN, a, f(b));
    }
}
