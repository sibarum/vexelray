package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
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
import dev.vexelray.surface.Surface;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * A flat wall at a fixed <b>view-space</b> depth, filling the frame — the non-radial reference a march is
 * measured against.
 *
 * <h2>What it is for, and why the march cannot be its own control</h2>
 *
 * <p>A sphere-tracer's {@code t} is radial: distance along a unit ray. A depth buffer holds the planar
 * distance, so {@link SdfComposer} multiplies by the cosine of the ray's angle to the camera's forward axis.
 * Drop that multiply and every marched pixel's depth is scaled by {@code 1/cos} — which at the corner of this
 * frame is about 1.42, an enormous error.
 *
 * <p>And <b>two marched techniques cannot detect it</b>. The error is the same factor at the same pixel for
 * both of them, so their comparison is unchanged and the picture looks perfect: {@code MarchDepthTest} would
 * pass with the cosine deleted. Catching it needs something whose depth is planar by construction, which is
 * exactly what a rasteriser is and what this stands in for.
 *
 * <p>So: a fullscreen quad that writes one constant clip depth. Perpendicular to the view axis by definition,
 * because a constant view-space depth <em>is</em> a plane perpendicular to the view axis — and the march's
 * own flat wall must agree with it edge to edge, not only at the centre of the screen.
 *
 * <h2>It computes its depth with {@link ClipDepth}, on purpose</h2>
 *
 * <p>The constant is built by {@link ClipDepth#ofViewZ} rather than worked out by hand, so this technique and
 * the march cannot disagree about the <em>curve</em> — only about the radial-to-planar conversion, which is
 * the one thing under test. A hand-computed literal here would turn any test that used it into a test of two
 * transcriptions of the same formula.
 */
final class ConstantDepthTechnique implements RenderTechnique {

    private final ClipDepth clipDepth;
    private final double viewZ;
    private final Surface.Rgb colour;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;

    /**
     * @param clipDepth the convention shared with whatever this is being compared against
     * @param viewZ     distance along the camera's forward axis this wall sits at
     */
    ConstantDepthTechnique(ClipDepth clipDepth, double viewZ, Surface.Rgb colour) {
        this.clipDepth = clipDepth;
        this.viewZ = viewZ;
        this.colour = colour;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, 0, true)
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main", fragment(), "main", config);
        this.cmds = new DrawCommands(device);
    }

    @Override
    public void record(FrameContext frame) {
        cmds.bindPipeline(frame.commandBuffer(), pipeline);
        cmds.draw(frame.commandBuffer(), 3);
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
    }

    /** {@code fragColor = vec4(colour, 1); gl_FragDepth = <constant>;} — everything baked, no push constants. */
    private byte[] fragment() {
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                        Ir.f(colour.r()), Ir.f(colour.g()), Ir.f(colour.b()), Ir.f(1.0)))),
                new Statement.BuiltinWrite(Builtin.FRAG_DEPTH, clipDepth.ofViewZ(Ir.f(viewZ))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }
}
