package dev.vexelray.engine.vulkan.runtime;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * A technique that fills the frame with one colour at a <b>clip depth that ramps across the screen</b> —
 * the smallest thing that can prove per-pixel interleaving rather than mere ordering.
 *
 * <h2>Why a ramp, and not two flat depths</h2>
 *
 * <p>Two techniques at two constant depths establish only that a depth test happened: whichever is nearer wins
 * everywhere, which is a picture that submission order alone could also have produced by swapping them. What
 * ordering cannot produce is <em>one</em> technique winning part of a frame and losing the rest of it. So this
 * writes {@code mix(left, right, u)} — a depth that sweeps from one edge of the screen to the other — and a
 * flat-depth technique drawn against it is beaten on one side and beats it on the other, in a single frame, at
 * a boundary whose pixel is arithmetic rather than judgement.
 *
 * <p>{@code left == right} makes it the flat-depth technique too, so both roles in that test are this class
 * and neither can drift from the other.
 *
 * <h2>What it deliberately is not</h2>
 *
 * <p>Not a march. This is testing the depth <em>plumbing</em> — that {@code Builtin.FRAG_DEPTH} lowers, that
 * {@code DepthReplacing} reaches the driver, that a shared depth attachment resolves two pipelines per pixel —
 * and a march would add a distance field, a projection, and a camera to the list of things that could be
 * wrong. The march writing a <em>correct</em> depth is a different claim with its own check.
 *
 * <p>The depths here are clip depths, written directly, so it does not use {@link ClipDepth#ofRadial}; it does
 * use {@link ClipDepth#FAR_DEPTH} as the value that means "nothing here", because a test that invented its own
 * spelling of the far plane would pass while disagreeing with the convention.
 */
final class DepthRampTechnique implements RenderTechnique {

    /** {@code r, g, b, depthLeft, depthRight} — this technique's push-constant layout, and nobody else's. */
    private static final int PUSH_BYTES = 5 * Float.BYTES;

    private final float[] push5;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    private int realizeCount;
    private int recordCount;
    private int closeCount;

    /**
     * @param depthLeft  clip depth at the left edge of the frame
     * @param depthRight clip depth at the right edge; equal to {@code depthLeft} for a flat depth
     */
    DepthRampTechnique(float r, float g, float b, float depthLeft, float depthRight) {
        this.push5 = new float[]{r, g, b, depthLeft, depthRight};
    }

    /** A technique at one depth everywhere. */
    static DepthRampTechnique flat(float r, float g, float b, float depth) {
        return new DepthRampTechnique(r, g, b, depth, depth);
    }

    @Override
    public void realize(TechniqueContext ctx) {
        realizeCount++;
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true)
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main", fragment(), "main", config);
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        recordCount++;
        MemorySegment cmd = frame.commandBuffer();
        cmds.bindPipeline(cmd, pipeline);
        for (int i = 0; i < push5.length; i++) {
            push.setAtIndex(JAVA_FLOAT, i, push5[i]);
        }
        cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);
        cmds.draw(cmd, 3);
    }

    @Override
    public void close() {
        closeCount++;
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
    }

    int realizeCount() {
        return realizeCount;
    }

    int recordCount() {
        return recordCount;
    }

    int closeCount() {
        return closeCount;
    }

    /** {@code fragColor = vec4(r, g, b, 1); gl_FragDepth = mix(depthLeft, depthRight, vUv.x);} */
    private static byte[] fragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("r", Ir.F32), new PushConstants.Member("g", Ir.F32),
                new PushConstants.Member("b", Ir.F32),
                new PushConstants.Member("depthLeft", Ir.F32),
                new PushConstants.Member("depthRight", Ir.F32)));

        Expr u = Ir.x(new Expr.InterfaceRead(vUv));
        Expr depth = Ir.mix(pc.read(3), pc.read(4), u);

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4,
                        List.of(pc.read(0), pc.read(1), pc.read(2), Ir.f(1.0)))),
                // Unconditional, and this technique has no other path — which is the easy case of the rule
                // Builtin.FRAG_DEPTH states: write it everywhere the shader can reach, or the value is
                // undefined where it did not.
                new Statement.BuiltinWrite(dev.supirvast.vastir.core.Builtin.FRAG_DEPTH, depth),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }
}
