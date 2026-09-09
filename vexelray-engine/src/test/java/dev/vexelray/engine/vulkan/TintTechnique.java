package dev.vexelray.engine.vulkan;

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
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The first thing to implement {@link RenderTechnique} — a technique that fills a rounded region of the frame
 * with one colour, over a fullscreen triangle.
 *
 * <h2>Why a trivial technique is the important one</h2>
 *
 * <p>{@code RenderTechnique} existed for some time with <b>zero</b> implementations, which meant the interface
 * was a hypothesis: nothing had ever been asked to build a pipeline from a {@link TechniqueContext}, push its
 * own constants, or draw inside a render pass someone else began. An interface in that state is not validated
 * by being reviewed; the first implementation is the review. This one is deliberately the smallest thing that
 * exercises every method — so what it finds is a fault in the contract, not in the geometry.
 *
 * <p>It lives in test scope, and that placement is also the point. The engine must not depend on a technique
 * module to be exercised, or it would not be an engine that names no technique.
 *
 * <h2>What it proves, and what it does not</h2>
 *
 * <p>Two of these in one pipeline prove that several pipelines record into one render pass in list order, that
 * each pushes its own constant layout without the runtime knowing the layout, and — when the target declares
 * depth — that a pass with two attachments, framebuffers with two views, and pipelines carrying depth-stencil
 * state all build and draw. It does <b>not</b> prove per-pixel interleaving: both draw a fullscreen triangle at
 * the same depth, so ordering alone explains the picture. Proving occlusion needs two techniques at different
 * depths, which needs a vertex stage that varies z, and that arrives with the first technique that has real
 * geometry.
 */
final class TintTechnique implements RenderTechnique {

    /** {@code centreX, centreY, halfW, halfH, radius, r, g, b} — this technique's own push-constant layout. */
    private static final int PUSH_BYTES = 8 * Float.BYTES;

    private final float[] colour;
    private final float inset;

    private VulkanDevice device;
    private GraphicsPipeline pipeline;
    private MethodHandle bindPipeline;
    private MethodHandle pushConstants;
    private MethodHandle draw;
    private MethodHandle setViewport;
    private MethodHandle setScissor;
    private Arena arena;
    private MemorySegment push;
    private MemorySegment viewport;
    private MemorySegment scissor;

    private int realizeCount;
    private int recordCount;
    private int closeCount;

    /**
     * @param colour linear RGB to fill with
     * @param inset  fraction of the half-extent to shrink the filled box by, so a second technique's region is
     *               visibly inside the first's and "did the second one draw" is answerable by looking
     */
    TintTechnique(float[] colour, float inset) {
        this.colour = colour.clone();
        this.inset = inset;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        realizeCount++;
        // The cast the SPI documents (D3). A technique that only needed formats and a render-pass handle would
        // not do this; one that must create a pipeline needs the device, and this is where that is admitted.
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        this.device = vk.device();

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true)
                // Depth state exactly when the shared pass has depth. Getting this wrong in either direction
                // is invisible: state without an attachment is invalid usage the loader may not report, and an
                // attachment without state means draws land in submission order and look mis-ordered.
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main", fragment(), "main", config);

        this.bindPipeline = device.command("vkCmdBindPipeline",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        this.pushConstants = device.command("vkCmdPushConstants",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        this.draw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        FunctionDescriptor setVs = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
        this.setViewport = device.command("vkCmdSetViewport", setVs);
        this.setScissor = device.command("vkCmdSetScissor", setVs);

        this.arena = Arena.ofShared();
        this.push = arena.allocate(PUSH_BYTES);
        this.viewport = arena.allocate(JAVA_FLOAT, 6);
        this.scissor = arena.allocate(JAVA_INT, 4);
    }

    @Override
    public void record(FrameContext frame) {
        recordCount++;
        MemorySegment cmd = frame.commandBuffer();
        // The extent comes from the frame, never from realise time — the resize rule the SPI states, obeyed by
        // the implementation that would have discovered it the hard way.
        int width = frame.width();
        int height = frame.height();

        Ffm.invokeVoid(bindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());

        viewport.setAtIndex(JAVA_FLOAT, 2, width);
        viewport.setAtIndex(JAVA_FLOAT, 3, height);
        viewport.setAtIndex(JAVA_FLOAT, 5, 1.0f);
        Ffm.invokeVoid(setViewport, cmd, 0, 1, viewport);
        scissor.setAtIndex(JAVA_INT, 2, width);
        scissor.setAtIndex(JAVA_INT, 3, height);
        Ffm.invokeVoid(setScissor, cmd, 0, 1, scissor);

        float halfW = width * 0.5f * (1 - inset);
        float halfH = height * 0.5f * (1 - inset);
        push.setAtIndex(JAVA_FLOAT, 0, width * 0.5f);
        push.setAtIndex(JAVA_FLOAT, 1, height * 0.5f);
        push.setAtIndex(JAVA_FLOAT, 2, halfW);
        push.setAtIndex(JAVA_FLOAT, 3, halfH);
        push.setAtIndex(JAVA_FLOAT, 4, Math.min(halfW, halfH) * 0.25f);
        push.setAtIndex(JAVA_FLOAT, 5, colour[0]);
        push.setAtIndex(JAVA_FLOAT, 6, colour[1]);
        push.setAtIndex(JAVA_FLOAT, 7, colour[2]);
        Ffm.invokeVoid(pushConstants, cmd, pipeline.pipelineLayout(), Vk.SHADER_STAGE_FRAGMENT_BIT, 0,
                PUSH_BYTES, push);

        Ffm.invokeVoid(draw, cmd, 3, 1, 0, 0);
    }

    @Override
    public void close() {
        closeCount++;
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (arena != null) {
            arena.close();
            arena = null;
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

    /** A rounded box in pixel space, filled with the pushed colour; everything outside it is discarded. */
    private static byte[] fragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("cx", Ir.F32), new PushConstants.Member("cy", Ir.F32),
                new PushConstants.Member("hw", Ir.F32), new PushConstants.Member("hh", Ir.F32),
                new PushConstants.Member("radius", Ir.F32),
                new PushConstants.Member("r", Ir.F32), new PushConstants.Member("g", Ir.F32),
                new PushConstants.Member("b", Ir.F32)));

        // The fragment's pixel coordinate, from UV times the box the caller pushed. Deriving position from the
        // pushed centre rather than a resolution member keeps this technique's layout entirely its own — the
        // runtime never learns what any of these eight floats mean, which is the property being tested.
        Expr uv = new Expr.InterfaceRead(vUv);
        Expr centre = Ir.v2(pc.read(0), pc.read(1));
        Expr half = Ir.v2(pc.read(2), pc.read(3));
        // scale, not mul: Ir.mul is a raw OpFMul with no broadcast, so mul(vec2, float) lowers to a
        // type-mismatched instruction. It does not fail to compose and it does not fail to lower — the first
        // thing that saw a problem was NVIDIA's shader compiler, which faulted and took the JVM with it.
        Expr res = Ir.scale(centre, Ir.f(2.0));
        Expr p = Ir.sub(Ir.mul(uv, res), centre);

        // Rounded-box SDF: |p| - half + r, clamped at zero, length, minus r.
        Expr q = Ir.sub(Ir.abs(p), Ir.sub(half, Ir.broadcast(pc.read(4), Ir.V2)));
        Expr outside = Ir.length(Ir.max(q, Ir.zero(Ir.V2)));
        Expr inside = Ir.min(Ir.max(Ir.x(q), Ir.y(q)), Ir.f(0.0));
        Expr d = Ir.sub(Ir.add(outside, inside), pc.read(4));

        Expr colour = Ir.v3(pc.read(5), pc.read(6), pc.read(7));
        Expr alpha = Ir.sub(Ir.f(1.0), Ir.clamp(Ir.mul(d, Ir.f(0.5)), Ir.f(0.0), Ir.f(1.0)));

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor,
                        new Expr.VectorConstruct(Ir.V4,
                                List.of(Ir.x(colour), Ir.y(colour), Ir.z(colour), alpha))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    /** The one downcall helper this needs, kept local so the test technique borrows nothing package-private. */
    private static final class Ffm {
        static void invokeVoid(MethodHandle handle, Object... args) {
            try {
                handle.invokeWithArguments(args);
            } catch (Throwable t) {
                throw new IllegalStateException("downcall failed", t);
            }
        }

        private Ffm() {
        }
    }
}
