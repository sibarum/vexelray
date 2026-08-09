package dev.vexelray.text;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.shader.ComposedShader;

import java.util.List;

/**
 * The MSDF text shader pair, authored as SupirVast {@code core} IR.
 *
 * <p><b>Vertex</b> takes an interleaved vertex buffer — position (already in clip space, {@code vec2} at
 * location 0) and atlas UV ({@code vec2} at location 1) — writes {@code gl_Position} and forwards UV as a varying.
 *
 * <p><b>Fragment</b> samples the MSDF atlas (a combined image sampler at set 0, binding 0), reconstructs the
 * signed distance with {@code median(r,g,b)}, and converts it to coverage with the classic
 * {@code clamp(screenPxRange·(sd-0.5) + 0.5, 0, 1)}. Because the IR has no {@code fwidth}/derivatives,
 * {@code screenPxRange} is supplied per-draw as a push constant (computed on the CPU by
 * {@link GlyphLayout#screenPxRange(float)}), rather than derived from screen-space UV derivatives. Text colour
 * travels in the same push constant.
 *
 * <p>Push-constant layout (16 bytes, fragment stage), four {@code float}s to avoid {@code vec3} padding ambiguity:
 * {@code r@0, g@4, b@8, screenPxRange@12}.
 */
public final class MsdfShader {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

    /** Vertex-attribute location of the clip-space position ({@code vec2}). */
    public static final int POS_LOCATION = 0;
    /** Vertex-attribute location of the atlas UV ({@code vec2}). */
    public static final int UV_LOCATION = 1;
    /** Descriptor set / binding of the atlas combined image sampler. */
    public static final int ATLAS_SET = 0;
    public static final int ATLAS_BINDING = 0;
    /** Push-constant size in bytes: {@code vec3 color} (as 3 floats) + {@code float screenPxRange}. */
    public static final int PUSH_CONSTANT_BYTES = 16;

    private MsdfShader() {
    }

    /** The vertex stage: pass a clip-space position through and forward the atlas UV. */
    public static ComposedShader vertex() {
        InterfaceVar inPos = InterfaceVar.input("inPos", POS_LOCATION, V2);
        InterfaceVar inUv = InterfaceVar.input("inUv", UV_LOCATION, V2);
        InterfaceVar vUv = InterfaceVar.output("vUv", 0, V2);

        Expr pos = new Expr.InterfaceRead(inPos);
        Expr clip = new Expr.VectorConstruct(V4, List.of(
                new Expr.VectorExtract(pos, 0), new Expr.VectorExtract(pos, 1),
                new Expr.ConstFloat(F32, 0.0), new Expr.ConstFloat(F32, 1.0)));
        Region body = Region.of(
                new Statement.BuiltinWrite(dev.supirvast.vastir.core.Builtin.POSITION, clip),
                new Statement.InterfaceWrite(vUv, new Expr.InterfaceRead(inUv)),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
        return ComposedShader.lower(ShaderStage.VERTEX, module, "main");
    }

    /** The fragment stage: MSDF median reconstruction + screen-pixel-range coverage, output premultiplied by alpha. */
    public static ComposedShader fragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", 0, V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        Texture atlas = new Texture("uAtlas", ATLAS_SET, ATLAS_BINDING);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("colR", F32),
                new PushConstants.Member("colG", F32),
                new PushConstants.Member("colB", F32),
                new PushConstants.Member("screenPxRange", F32)));

        Expr msd = new Expr.SampleTexture(atlas, new Expr.InterfaceRead(vUv));   // vec4 RGBA
        Expr r = new Expr.VectorExtract(msd, 0);
        Expr g = new Expr.VectorExtract(msd, 1);
        Expr b = new Expr.VectorExtract(msd, 2);
        // median(r,g,b) = max(min(r,g), min(max(r,g), b))
        Expr median = Expr.MathCall.max(Expr.MathCall.min(r, g),
                Expr.MathCall.min(Expr.MathCall.max(r, g), b));
        // opacity = clamp(screenPxRange * (median - 0.5) + 0.5, 0, 1)
        Expr signed = new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.SUB, median, new Expr.ConstFloat(F32, 0.5));
        Expr scaled = new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.MUL, pc.read(3), signed);
        Expr opacity = Expr.MathCall.clamp(
                new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.ADD, scaled, new Expr.ConstFloat(F32, 0.5)),
                new Expr.ConstFloat(F32, 0.0), new Expr.ConstFloat(F32, 1.0));

        Expr out = new Expr.VectorConstruct(V4, List.of(pc.read(0), pc.read(1), pc.read(2), opacity));
        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, out),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main");
    }
}
