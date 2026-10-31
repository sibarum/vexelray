package dev.vexelray.demo.planet;

import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.Lens;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * The planet, its air and the stars: one fullscreen draw of {@link PlanetShader}. It writes depth in the lens's own
 * convention, so whatever is drawn after it (the saucer, one day) is hidden behind the ground and shows in front of
 * the sky.
 *
 * <p>The camera is the march's: an eye, a yaw and a pitch, in kilometres. Only the eye is sent; the planet's own
 * frame turns under it by {@link #spin}.
 */
final class PlanetTechnique implements RenderTechnique {

    private static final int PUSH_BYTES = PlanetShader.PUSH.length * Float.BYTES;

    private final Lens lens;
    private final PlanetShader shader;

    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double yaw;
    private double pitch;
    private double spin;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    PlanetTechnique(Lens lens, PlanetShader shader) {
        this.lens = lens;
        this.shader = shader;
    }

    void camera(double x, double y, double z, double yaw, double pitch) {
        this.eyeX = x;
        this.eyeY = y;
        this.eyeZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** How far the planet has turned about its axis, radians. */
    void spin(double spin) {
        this.spin = spin;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true)
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main", shader.fragment(lens.clipDepth()).spirv(), "main",
                config);
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        float[] values = {(float) eyeX, (float) eyeY, (float) eyeZ, (float) yaw, (float) pitch,
                frame.height() == 0 ? 1f : (float) frame.width() / frame.height(), (float) lens.focalLength(),
                (float) spin};
        MemorySegment.copy(values, 0, push, JAVA_FLOAT, 0, values.length);
        cmds.bindPipeline(cmd, pipeline);
        cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);
        cmds.draw(cmd, 3);
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
        }
        if (cmds != null) {
            cmds.close();
        }
    }
}
