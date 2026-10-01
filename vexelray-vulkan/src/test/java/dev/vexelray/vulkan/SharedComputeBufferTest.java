package dev.vexelray.vulkan;

import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.tools.GpuContext;
import dev.supirvast.vastir.type.Type;
import dev.supirvast.vulkan.ComputeSupport;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.present.BoundStorageBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A buffer the compute side owns, read by a draw on the same device — the reason there is one Vulkan binding.
 *
 * <p>A {@link GpuContext} lent the device holds its state in a device-local buffer; a fragment shader reads that
 * very buffer through a {@link BoundStorageBuffer}; and the colour that comes out is what the compute side wrote.
 * Nothing is copied through the host between the two, which is the point: two devices could not do this at all.
 *
 * <p>The second test changes the buffer from the compute side and draws again. A draw that had snapshotted the
 * first contents would repeat them, so it is what tells <em>shared</em> from <em>copied</em>.
 */
class SharedComputeBufferTest {

    private static final int SIZE = 8;

    /** The device, made as an application that both draws and computes would make it. */
    private record Rig(VulkanInstance instance, VulkanDevice device, GpuContext context) implements AutoCloseable {
        @Override
        public void close() {
            context.close();
            device.close();
            instance.close();
        }
    }

    private static Rig rigOrSkip() {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("shared compute buffer test", List.of());
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no Vulkan loader: " + e.getMessage());
            throw e;
        }
        Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
        if (selection.isEmpty()) {
            instance.close();
            assumeTrue(false, "no device with a graphics queue");
        }
        VulkanInstance.DeviceSelection chosen = selection.orElseThrow();
        boolean alsoCompute = instance.deviceInfos().stream().anyMatch(d ->
                d.physicalDevice().equals(chosen.physicalDevice()) && d.computeQueueFamily() == chosen.queueFamilyIndex());
        if (!alsoCompute) {
            instance.close();
            assumeTrue(false, "the graphics queue family does not run compute here");
        }
        ComputeSupport support = ComputeSupport.query(instance, chosen.physicalDevice());
        VulkanDevice device = new VulkanDevice(instance.handle(), chosen,
                VulkanDevice.Request.headlessCompute(support, 1));
        return new Rig(instance, device, GpuContext.on(instance, device));
    }

    /** A fragment stage whose colour is the first four floats of the buffer at binding 0. */
    private static byte[] colourFromBuffer() {
        Type.Float f32 = Type.float32();
        Type.Vector vec4 = new Type.Vector(f32, 4);
        Buffer shared = new Buffer("shared", 0, f32);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, vec4);
        Expr colour = new Expr.VectorConstruct(vec4, List.of(
                load(shared, 0), load(shared, 1), load(shared, 2), load(shared, 3)));
        Region body = Region.of(new Statement.InterfaceWrite(fragColor, colour), new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    private static Expr load(Buffer buffer, int index) {
        return new Expr.BufferLoad(buffer, new Expr.ConstInt(Type.int32(), index));
    }

    private static int[] words(float... values) {
        int[] words = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            words[i] = Float.floatToRawIntBits(values[i]);
        }
        return words;
    }

    private static int[] centre(byte[] rgba) {
        int i = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
        return new int[] {rgba[i] & 0xFF, rgba[i + 1] & 0xFF, rgba[i + 2] & 0xFF, rgba[i + 3] & 0xFF};
    }

    private static void assertColour(int[] expected, int[] actual) {
        for (int c = 0; c < 4; c++) {
            assertTrue(Math.abs(expected[c] - actual[c]) <= 1,
                    "channel " + c + " expected " + expected[c] + " got " + actual[c]);
        }
    }

    private static byte[] draw(Rig rig, BoundStorageBuffer bound, byte[] vertex, byte[] fragment) {
        // The compute side's work is finished before the draw is submitted: the wait is the dependency.
        rig.context().finish();
        return OffscreenRenderer.render(rig.device(), SIZE, SIZE, vertex, "main", fragment, "main", 3,
                0f, 0f, 0f, 1f, null, new long[] {bound.descriptorSetLayout()},
                new long[] {bound.descriptorSet()});
    }

    @Test
    void aDrawReadsTheBufferTheComputeContextWrote() {
        try (Rig rig = rigOrSkip()) {
            GpuContext.DeviceBuffer state = rig.context().allocateBuffer(4);
            try {
                rig.context().write(state, words(0.25f, 0.5f, 0.75f, 1.0f));
                try (BoundStorageBuffer bound = new BoundStorageBuffer(rig.device(), state.vkBuffer(), 0)) {
                    byte[] rgba = draw(rig, bound, Fullscreen.triangleVertexWithUvSpirv(), colourFromBuffer());
                    assertColour(new int[] {64, 128, 191, 255}, centre(rgba));
                }
            } finally {
                state.close();
            }
        }
    }

    @Test
    void aChangeFromTheComputeSideIsWhatTheNextDrawSees() {
        try (Rig rig = rigOrSkip()) {
            GpuContext.DeviceBuffer state = rig.context().allocateBuffer(4);
            try {
                byte[] vertex = Fullscreen.triangleVertexWithUvSpirv();
                byte[] fragment = colourFromBuffer();
                try (BoundStorageBuffer bound = new BoundStorageBuffer(rig.device(), state.vkBuffer(), 0)) {
                    rig.context().write(state, words(1.0f, 0.0f, 0.0f, 1.0f));
                    assertColour(new int[] {255, 0, 0, 255}, centre(draw(rig, bound, vertex, fragment)));

                    rig.context().write(state, words(0.0f, 0.0f, 1.0f, 1.0f));
                    assertColour(new int[] {0, 0, 255, 255}, centre(draw(rig, bound, vertex, fragment)));
                }
            } finally {
                state.close();
            }
        }
    }
}
