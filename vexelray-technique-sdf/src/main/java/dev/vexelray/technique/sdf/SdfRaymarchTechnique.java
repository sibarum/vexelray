package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.Fullscreen;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamId;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The SDF ray-march as a {@link RenderTechnique} — the first real technique, and the one the whole pipeline
 * model was designed around.
 *
 * <p>What it is, mechanically, is the thing every march smoke and Fathom itself already did by hand: compose a
 * scene's fragment shader with {@link SdfComposer}, build a pipeline over a fullscreen triangle, push a camera,
 * and draw three vertices. What is new is that it does not own a window, a device, a swapchain, or a frame loop,
 * so it can appear in a frame beside something else.
 *
 * <h2>Per-frame data is this class's own API (D5)</h2>
 *
 * <p>The runtime never learns what a camera is. An application calls {@link #camera} and {@link #parameter}
 * from the engine's frame callback, and {@link #record} turns whatever was last set into the byte layout
 * {@code SdfComposer} declared. That is why the presenter could stop taking a {@code pushConstantBytes} count:
 * the layout belongs to the technique that authored it, and no two techniques need to agree on one.
 *
 * <h2>Depth: declared honestly as absent</h2>
 *
 * <p>This technique uses {@link GraphicsPipeline.Config.Depth#NONE} even when the shared target has depth, and
 * that is not an oversight. The march runs over a fullscreen triangle whose vertices carry one fixed z, so the
 * only depth it could write is that constant — which is not the distance to the surface it actually hit, and
 * would occlude a mesh at a plane rather than at the geometry. Writing a plausible wrong number is worse than
 * writing none: a cross-occlusion bug that looks like a modelling mistake.
 *
 * <p>Making this participate in depth means the fragment writing {@code gl_FragDepth} from the march's own hit
 * distance, which is a real feature with a real cost (it defeats early-z for this pipeline) and belongs in its
 * own change. Until then a marched scene composites by order, and this note is what stops the absence from
 * looking like a decision nobody made.
 */
public final class SdfRaymarchTechnique implements RenderTechnique {

    private final SdfScene scene;
    private final ParamBlock params;

    private double camX;
    private double camY;
    private double camZ;
    private double yaw;
    private double pitch;

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
    private int pushBytes;

    /**
     * A technique that marches {@code scene}.
     *
     * <p>The scene is fixed for this technique's life, because its <em>shape</em> is what the shader was
     * compiled from — changing it is a new pipeline, which is the distinction 79ed04c drew between a slider and
     * a shape. Numbers the scene declared as {@link dev.vexelray.surface.Scalar.Param} are driven through
     * {@link #parameter} without recompiling anything.
     */
    public SdfRaymarchTechnique(SdfScene scene) {
        this.scene = scene;
        this.params = SdfComposer.paramBlock(scene);
    }

    /** Where the eye is and where it looks, in world space. Called from the engine's frame callback. */
    public SdfRaymarchTechnique camera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
        return this;
    }

    /** Drive one of the scene's declared parameters — a push, not a recompile. */
    public SdfRaymarchTechnique parameter(ParamId id, double value) {
        params.write(id, value);
        return this;
    }

    /** The scene this technique marches, for a caller that needs its shading or march settings back. */
    public SdfScene scene() {
        return scene;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        VulkanDevice device = vk.device();

        List<ComposedShader> composed = new SdfComposer().compose(scene);
        this.pushBytes = SdfComposer.pushBytes(scene);

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, pushBytes, true);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                composed.get(0).spirv(), "main", composed.get(1).spirv(), "main", config);

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
        this.push = arena.allocate(pushBytes);
        this.viewport = arena.allocate(JAVA_FLOAT, 6);
        this.scissor = arena.allocate(JAVA_INT, 4);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        int width = frame.width();
        int height = frame.height();

        invoke(bindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());

        viewport.setAtIndex(JAVA_FLOAT, 2, width);
        viewport.setAtIndex(JAVA_FLOAT, 3, height);
        viewport.setAtIndex(JAVA_FLOAT, 5, 1.0f);
        invoke(setViewport, cmd, 0, 1, viewport);
        scissor.setAtIndex(JAVA_INT, 2, width);
        scissor.setAtIndex(JAVA_INT, 3, height);
        invoke(setScissor, cmd, 0, 1, scissor);

        // Aspect is the frame's, never the caller's. A camera API that took it would make every application
        // responsible for noticing a resize, and one that used the realise-time extent would stretch the scene
        // for the rest of the run.
        double aspect = height == 0 ? 1.0 : (double) width / height;
        byte[] bytes = SdfComposer.pushConstantBytes(scene, camX, camY, camZ, yaw, pitch, aspect, params);
        MemorySegment.copy(bytes, 0, push, JAVA_BYTE, 0, bytes.length);
        invoke(pushConstants, cmd, pipeline.pipelineLayout(), Vk.SHADER_STAGE_FRAGMENT_BIT, 0, pushBytes, push);

        invoke(draw, cmd, 3, 1, 0, 0);
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    private static void invoke(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException("downcall failed", t);
        }
    }
}
