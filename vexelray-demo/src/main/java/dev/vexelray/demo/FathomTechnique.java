package dev.vexelray.demo;

import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Fathom's world as a {@link RenderTechnique} — a technique written by the <em>application</em>, not by the
 * engine or by a {@code vexelray-technique-*} module.
 *
 * <h2>Why Fathom does not use SdfRaymarchTechnique</h2>
 *
 * <p>It could not without giving something up. {@code SdfRaymarchTechnique} renders an {@link
 * dev.vexelray.technique.sdf.SdfScene} — a {@code Surface} tree compiled by {@code SurfaceCompiler} — and
 * composes its own march around the resulting field. Fathom's scene is hand-authored {@code core} IR, and its
 * march is a {@code core} {@code Function} that is lowered to <b>both</b> SPIR-V and Truffle so the stepping
 * loop the GPU draws with is the identical loop the CPU raycasts with. Fathom's own comment records what that
 * property cost: the GPU marcher used to be inline IR and the CPU marcher a hand-written Java loop, and they
 * silently diverged.
 *
 * <p>Rewriting the scene as a {@code Surface} would move the march into {@code SdfComposer}, which emits it
 * inline in the fragment and does not hand it back as a function — so the CPU side would need a second marcher
 * again. Trading a proven render==sim property for the reuse of a pipeline builder is the wrong way round.
 *
 * <p>So this class exists, and its existence is the point being demonstrated: <b>the extension point is open.</b>
 * An application with its own shader, its own field and its own march composites in the same frame as any
 * first-party technique, and the runtime cannot tell the difference. That claim was in
 * {@code RenderTechnique}'s javadoc from the start; this is the first thing to test it from outside the engine's
 * own modules.
 *
 * <h2>What it owns, and what it stopped owning</h2>
 *
 * <p>It owns a pipeline built from Fathom's composed SPIR-V, and a five-float push constant that is its layout
 * and nobody else's. It no longer owns an instance, a device, a surface, a swapchain, a render pass, a
 * presenter, or a frame loop — which is the whole of what was deleted from {@link Fathom} to make this.
 */
final class FathomTechnique implements RenderTechnique {

    /** {@code camX, camY, camZ, yaw, pitch} — the layout {@code Fathom.raymarchFragment} declares. */
    static final int PUSH_BYTES = 5 * Float.BYTES;

    private final byte[] vertexSpirv;
    private final byte[] fragmentSpirv;

    private float camX;
    private float camY = 1.2f;
    private float camZ = -3.0f;
    private float yaw;
    private float pitch;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    FathomTechnique(byte[] vertexSpirv, byte[] fragmentSpirv) {
        this.vertexSpirv = vertexSpirv;
        this.fragmentSpirv = fragmentSpirv;
    }

    /**
     * Where the eye is and where it looks — the technique's own per-frame API (D5).
     *
     * <p>Fathom's sim writes here after it has moved the camera and resolved collision against the CPU field.
     * The runtime is not involved and never sees these five numbers as anything but bytes.
     */
    FathomTechnique camera(float x, float y, float z, float yaw, float pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
        return this;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();

        // Dynamic viewport, because the extent is the frame's and not this pipeline's — Fathom's window is
        // resizable, and a baked viewport is the bug RenderTechnique's resize note describes.
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                vertexSpirv, "main", fragmentSpirv, "main", config);

        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        // Dynamic viewport and scissor are already set to this frame's extent by the runtime, which is what
        // makes the resizable window above cost this class nothing.
        cmds.bindPipeline(cmd, pipeline);

        push.setAtIndex(JAVA_FLOAT, 0, camX);
        push.setAtIndex(JAVA_FLOAT, 1, camY);
        push.setAtIndex(JAVA_FLOAT, 2, camZ);
        push.setAtIndex(JAVA_FLOAT, 3, yaw);
        push.setAtIndex(JAVA_FLOAT, 4, pitch);
        cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);

        cmds.draw(cmd, 3);
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
}
