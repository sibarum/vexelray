package dev.vexelray.engine.embedded;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueBinding;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.FeedSlots;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A technique that declares what it reads (vexelray-gui's docs/plans/device-feed.md, 4a): the host makes the set's
 * layout from the declaration, the technique builds its pipeline with it, and each frame binds the set the host wrote
 * with what it was told, so the picture a technique draws is the one the host bound, and binding another changes it
 * with nothing rebuilt. On a real device; skipped where there is no Vulkan.
 */
class TechniqueBindingsTest {

    /** Draws the picture bound to {@code "picture"} over the whole target, texel for texel. */
    private static final class Show implements RenderTechnique {
        GraphicsPipeline pipeline;
        DrawCommands commands;
        long layoutSeen;
        long boundSeen;

        @Override
        public List<TechniqueBinding> bindings() {
            return List.of(TechniqueBinding.picture("picture"));
        }

        @Override
        public void realize(TechniqueContext ctx) {
            layoutSeen = ctx.bindingLayout();
            VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
            pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                    Fullscreen.triangleVertexWithUvSpirv(), "main", fragment(), "main",
                    new GraphicsPipeline.Config(0, List.of(), new long[] {ctx.bindingLayout()}, false,
                            Vk.SHADER_STAGE_FRAGMENT_BIT, 0, true));
            commands = new DrawCommands(device);
        }

        @Override
        public void record(FrameContext frame) {
            boundSeen = frame.bindings();
            commands.bindPipeline(frame.commandBuffer(), pipeline);
            commands.bindDescriptorSet(frame.commandBuffer(), pipeline, bindingSet(), frame.bindings());
            commands.draw(frame.commandBuffer(), 3);
        }

        @Override
        public void close() {
            if (pipeline != null) {
                pipeline.close();
                commands.close();
                pipeline = null;
            }
        }

        private static byte[] fragment() {
            InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
            InterfaceVar color = InterfaceVar.output("fragColor", 0, Ir.V4);
            Texture picture = new Texture("picture", 0, 0);
            Region body = Region.of(
                    new Statement.InterfaceWrite(color, new Expr.SampleTexture(picture, new Expr.InterfaceRead(vUv))),
                    new Statement.ReturnVoid());
            Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
            return ComposedShader.lower(ShaderStage.FRAGMENT,
                    new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)), "main").spirv();
        }
    }

    @Test
    void theTechniqueDrawsWhatTheHostBoundAndAnotherBindingChangesIt() {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("technique bindings test", List.of());
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no Vulkan loader: " + e.getMessage());
            throw e;
        }
        Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
        if (selection.isEmpty()) {
            instance.close();
            assumeTrue(false, "no device with a graphics queue");
        }
        VulkanDevice device = new VulkanDevice(instance.handle(), selection.orElseThrow(),
                new VulkanDevice.Request(false, null, 1));
        try (FeedSlots pictures = new FeedSlots(device, 4, 4, 2, false);
             SampledColorTarget target = new SampledColorTarget(device, 4, 4)) {
            byte[] gradient = gradient();
            byte[] flat = new byte[4 * 4 * 4];
            for (int i = 0; i < flat.length; i += 4) {
                flat[i] = 10;
                flat[i + 1] = (byte) 200;
                flat[i + 2] = 30;
                flat[i + 3] = (byte) 255;
            }
            MemorySegment.copy(MemorySegment.ofArray(gradient), 0, pictures.staging(0), 0, gradient.length);
            MemorySegment.copy(MemorySegment.ofArray(flat), 0, pictures.staging(1), 0, flat.length);
            FeedSlots.oneShot(device, cmd -> {
                pictures.land(cmd, 0);
                pictures.land(cmd, 1);
            });

            Show show = new Show();
            SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(show));
            try {
                assertThrows(IllegalStateException.class, () -> host.render(0f, 0f, 0f, 1f),
                        "a declared binding never bound is refused by name, not drawn from nothing");
                TechniqueBindings bound = host.bindings(show);
                assertThrows(IllegalArgumentException.class, () -> bound.buffer("picture", 1L, 0L, 4L),
                        "a picture is not bound as a buffer");
                assertThrows(IllegalArgumentException.class, () -> bound.picture("nothing", 1L, 1L));

                bound.picture("picture", pictures.view(0), pictures.sampler());
                host.render(0f, 0f, 0f, 1f);
                assertNotEquals(0L, show.layoutSeen, "realise was given the layout the declaration made");
                assertEquals(bound.layout(), show.layoutSeen);
                assertNotEquals(0L, show.boundSeen, "record was given the set the host wrote");
                assertArrayEquals(gradient, target.read(), "the picture the host bound, texel for texel");

                bound.picture("picture", pictures.view(1), pictures.sampler());
                host.render(0f, 0f, 0f, 1f);
                assertArrayEquals(flat, target.read(), "another binding, and nothing rebuilt");
            } finally {
                host.close();
            }
        } finally {
            device.close();
            instance.close();
        }
    }

    private static byte[] gradient() {
        byte[] px = new byte[4 * 4 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int i = (y * 4 + x) * 4;
                px[i] = (byte) (x * 60);
                px[i + 1] = (byte) (y * 60);
                px[i + 2] = (byte) 128;
                px[i + 3] = (byte) 255;
            }
        }
        return px;
    }
}
