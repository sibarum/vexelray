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

    /** Vertex stage: pass clip-space position through and forward every per-vertex attribute as a varying. */
    public static ComposedShader vertex() {
        InterfaceVar inPos = InterfaceVar.input("inPos", CanvasVertex.LOC_POS, V2);
        InterfaceVar inColor = InterfaceVar.input("inColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar inUv = InterfaceVar.input("inUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar inKind = InterfaceVar.input("inKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar inLocal = InterfaceVar.input("inLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar inShape = InterfaceVar.input("inShape", CanvasVertex.LOC_SHAPE, V4);

        InterfaceVar vColor = InterfaceVar.output("vColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar vUv = InterfaceVar.output("vUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar vKind = InterfaceVar.output("vKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar vLocal = InterfaceVar.output("vLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar vShape = InterfaceVar.output("vShape", CanvasVertex.LOC_SHAPE, V4);

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
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return ComposedShader.lower(ShaderStage.VERTEX,
                new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX)), "main");
    }

    /** Fragment stage: branch on {@code kind} — rounded-box SDF for shapes, MSDF median for glyphs. */
    public static ComposedShader fragment() {
        InterfaceVar vColor = InterfaceVar.input("vColor", CanvasVertex.LOC_COLOR, V4);
        InterfaceVar vUv = InterfaceVar.input("vUv", CanvasVertex.LOC_UV, V2);
        InterfaceVar vKind = InterfaceVar.input("vKind", CanvasVertex.LOC_KIND, F32);
        InterfaceVar vLocal = InterfaceVar.input("vLocal", CanvasVertex.LOC_LOCAL, V2);
        InterfaceVar vShape = InterfaceVar.input("vShape", CanvasVertex.LOC_SHAPE, V4);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        Texture atlas = new Texture("uAtlas", ATLAS_SET, ATLAS_BINDING);

        Expr color = new Expr.InterfaceRead(vColor);
        Expr rgb = new Expr.VectorConstruct(V3, List.of(x(color), y(color), z(color)));
        Expr alpha = w(color);
        Expr shape = new Expr.InterfaceRead(vShape);

        // --- shape branch: rounded-box SDF coverage ---
        Expr local = new Expr.InterfaceRead(vLocal);
        Expr half = new Expr.VectorConstruct(V2, List.of(x(shape), y(shape)));
        Expr r = z(shape);
        Expr aa = w(shape);
        Expr q = add(sub(Expr.MathCall.abs(local), half), new Expr.VectorConstruct(V2, List.of(r, r)));
        Expr outside = Expr.MathCall.length(Expr.MathCall.max(q, v2(0.0, 0.0)));
        Expr inside = Expr.MathCall.min(Expr.MathCall.max(x(q), y(q)), f(0.0));
        Expr d = sub(add(outside, inside), r);
        Expr shapeCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, d));
        Expr shapeOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, shapeCov)));

        // --- glyph branch: MSDF median + screenPxRange ---
        Expr msd = new Expr.SampleTexture(atlas, new Expr.InterfaceRead(vUv));
        Expr median = Expr.MathCall.max(Expr.MathCall.min(x(msd), y(msd)),
                Expr.MathCall.min(Expr.MathCall.max(x(msd), y(msd)), z(msd)));
        Expr spr = x(shape);
        Expr glyphCov = Expr.MathCall.clamp(
                add(mul(spr, sub(median, f(0.5))), f(0.5)), f(0.0), f(1.0));
        Expr glyphOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, glyphCov)));

        Region shapeRegion = Region.of(new Statement.InterfaceWrite(fragColor, shapeOut));
        Region glyphRegion = Region.of(new Statement.InterfaceWrite(fragColor, glyphOut));
        Expr isShape = new Expr.Binary(BinaryOp.LESS_THAN, new Expr.InterfaceRead(vKind), f(0.5));
        Region body = Region.of(
                new Statement.If(isShape, shapeRegion, glyphRegion),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return ComposedShader.lower(ShaderStage.FRAGMENT,
                new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)), "main");
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

    private static Expr neg(Expr a) {
        return new Expr.Binary(BinaryOp.SUB, f(0.0), a);
    }
}
