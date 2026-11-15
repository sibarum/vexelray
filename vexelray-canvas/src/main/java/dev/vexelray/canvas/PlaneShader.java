package dev.vexelray.canvas;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Builtin;
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
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ComposedShader;

import java.util.List;

/**
 * The fragment stage that turns a frame's planes into RGB (vexelray-vulkan's {@code FeedSlots}, landing a planar slot): one
 * full-screen triangle over the slot's level 0, each texel sampled from the luma plane and the chroma at the same place,
 * brought to {@code [0, 1]}, offset and scaled for the range, and put through the matrix. It writes the encoded
 * {@code R'G'B'} the decoder meant, through a UNORM view of the sRGB image, so what is stored is exactly what an sRGB
 * texel holds and nothing is decoded or encoded on the way.
 *
 * <p>Set 0 holds the planes: binding 0 luma, 1 Cb (or Cb and Cr interleaved), 2 Cr (the interleaved plane again, for
 * two planes). The constants are {@code PlaneLayout.constants()}, ten floats, pushed to the fragment stage.
 */
public final class PlaneShader {

    /** Where the vertex stage hands the fragment its place across the slot, in [0, 1] each way. */
    private static final int UV_LOCATION = 0;

    /** The push-constant bytes: {@code PlaneLayout.constants()}' ten floats. */
    public static final int PUSH_BYTES = 10 * Float.BYTES;

    private PlaneShader() {
    }

    /**
     * The vertex stage: three vertices and no input, a triangle over the whole target built from the vertex index, with
     * {@code vUv} running from 0 to 1 across what is visible. {@code (0, 0)} is the target's top left, the first texel of
     * every plane.
     */
    public static byte[] vertexSpirv() {
        Expr idx = new Expr.BuiltinRead(Builtin.VERTEX_INDEX);
        Expr two = new Expr.ConstInt(Type.int32(), 2);
        Expr one = new Expr.ConstInt(Type.int32(), 1);
        Expr ux = new Expr.Convert(new Expr.Binary(BinaryOp.BIT_AND,
                new Expr.Binary(BinaryOp.SHIFT_LEFT, idx, one), two), Ir.F32);   // 0 or 2
        Expr uy = new Expr.Convert(new Expr.Binary(BinaryOp.BIT_AND, idx, two), Ir.F32);
        Expr clip = new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.sub(Ir.mul(ux, Ir.f(2.0)), Ir.f(1.0)), Ir.sub(Ir.mul(uy, Ir.f(2.0)), Ir.f(1.0)),
                Ir.f(0.0), Ir.f(1.0)));
        InterfaceVar vUv = InterfaceVar.output("vUv", UV_LOCATION, Ir.V2);
        Region body = Region.of(
                new Statement.BuiltinWrite(Builtin.POSITION, clip),
                new Statement.InterfaceWrite(vUv, new Expr.VectorConstruct(Ir.V2, List.of(ux, uy))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
        return ComposedShader.lower(ShaderStage.VERTEX, module, "main").spirv();
    }

    /** The fragment stage, lowered to SPIR-V; {@link #vertexSpirv} is its vertex stage. */
    public static byte[] fragmentSpirv() {
        InterfaceVar vUv = InterfaceVar.input("vUv", UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("scale", Ir.F32),
                new PushConstants.Member("yOff", Ir.F32),
                new PushConstants.Member("yMul", Ir.F32),
                new PushConstants.Member("cOff", Ir.F32),
                new PushConstants.Member("cMul", Ir.F32),
                new PushConstants.Member("kr", Ir.F32),
                new PushConstants.Member("kb", Ir.F32),
                new PushConstants.Member("kgb", Ir.F32),
                new PushConstants.Member("kgr", Ir.F32),
                new PushConstants.Member("interleaved", Ir.F32)));
        Texture luma = new Texture("luma", 0, 0);
        Texture cb = new Texture("cb", 0, 1);
        Texture cr = new Texture("cr", 0, 2);

        Expr uv = new Expr.InterfaceRead(vUv);
        Expr scale = pc.read(0);
        Expr chroma = new Expr.SampleTexture(cb, uv);
        Expr y = Ir.mul(Ir.x(new Expr.SampleTexture(luma, uv)), scale);
        Expr u = Ir.mul(Ir.x(chroma), scale);
        // Cr is the third plane's, or the interleaved plane's second channel.
        Expr v = Ir.mul(Ir.mix(Ir.x(new Expr.SampleTexture(cr, uv)), Ir.y(chroma), pc.read(9)), scale);

        Expr yy = Ir.mul(Ir.sub(y, pc.read(1)), pc.read(2));
        Expr pb = Ir.mul(Ir.sub(u, pc.read(3)), pc.read(4));
        Expr pr = Ir.mul(Ir.sub(v, pc.read(3)), pc.read(4));
        Expr r = Ir.add(yy, Ir.mul(pc.read(5), pr));
        Expr g = Ir.sub(Ir.sub(yy, Ir.mul(pc.read(7), pb)), Ir.mul(pc.read(8), pr));
        Expr b = Ir.add(yy, Ir.mul(pc.read(6), pb));

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                        Ir.clamp(r, Ir.f(0.0), Ir.f(1.0)),
                        Ir.clamp(g, Ir.f(0.0), Ir.f(1.0)),
                        Ir.clamp(b, Ir.f(0.0), Ir.f(1.0)),
                        Ir.f(1.0)))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }
}
