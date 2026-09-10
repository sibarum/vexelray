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
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * A technique that fills every pixel with one pushed RGBA colour — the simplest thing whose output a test can
 * assert exactly.
 *
 * <h2>Why not {@link TintTechnique}</h2>
 *
 * <p>{@code TintTechnique} draws a rounded box and is the right shape for the question it was written for:
 * whether two independently-built pipelines can record into one pass. But its picture is a soft-edged
 * distance field, so "how many pixels are this colour" has no exact answer, and it writes its colour over the
 * whole frame with only the alpha varying — which is legible to a person and ambiguous to a comparison.
 *
 * <p>Now that {@link dev.vexelray.vulkan.present.OffscreenPresenter} can hand a frame back as bytes, a
 * technique whose output is <em>countable</em> is worth more than one that is pretty: every pixel is either
 * exactly the pushed colour or exactly the clear, so an assertion about the picture is an equality rather
 * than a tolerance, and a failure says which pixel and what it was instead of "close enough, probably".
 *
 * <p>Test scope, like {@code TintTechnique} and for the same reason: the engine must not depend on a
 * technique module in order to be exercised, or it is not an engine that names no technique.
 */
final class SolidTechnique implements RenderTechnique {

    /** {@code r, g, b, a} — this technique's push-constant layout, which the runtime never learns. */
    private static final int PUSH_BYTES = 4 * Float.BYTES;

    private final float[] rgba;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    private int realizeCount;
    private int recordCount;
    private int closeCount;

    SolidTechnique(float r, float g, float b, float a) {
        this.rgba = new float[]{r, g, b, a};
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
        for (int i = 0; i < rgba.length; i++) {
            push.setAtIndex(JAVA_FLOAT, i, rgba[i]);
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

    /** {@code fragColor = vec4(r, g, b, a)}, straight from push constants — authored as core IR, per the rule. */
    private static byte[] fragment() {
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("r", Ir.F32), new PushConstants.Member("g", Ir.F32),
                new PushConstants.Member("b", Ir.F32), new PushConstants.Member("a", Ir.F32)));

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4,
                        List.of(pc.read(0), pc.read(1), pc.read(2), pc.read(3)))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }
}
