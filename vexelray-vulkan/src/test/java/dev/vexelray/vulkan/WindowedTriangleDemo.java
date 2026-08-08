package dev.vexelray.vulkan;

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
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SwapchainFramebuffers;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Manual smoke check (not a unit test): the shaded triangle in a live window. Composes the UV-gradient fragment
 * as SupirVast core IR, builds a {@link GraphicsPipeline} over the swapchain format, and runs the present loop
 * drawing it through a render pass into per-image framebuffers. Run with {@code --enable-native-access=ALL-UNNAMED};
 * a window shows the gradient for a few seconds. (Static viewport — live resize would need a dynamic viewport.)
 */
public final class WindowedTriangleDemo {

    private static final GroupLayout CI = MemoryLayout.structLayout(   // Semaphore / Fence create info (same shape)
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("CreateInfo");

    private static final GroupLayout POOL_CI = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout CMD_ALLOC = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout CMD_BEGIN = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout RENDER_PASS_BEGIN = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("renderPass"), JAVA_LONG.withName("framebuffer"),
            JAVA_INT.withName("area_x"), JAVA_INT.withName("area_y"),
            JAVA_INT.withName("area_w"), JAVA_INT.withName("area_h"),
            JAVA_INT.withName("clearValueCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pClearValues")
    ).withName("VkRenderPassBeginInfo");

    private static final GroupLayout SUBMIT = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private static final GroupLayout PRESENT = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            JAVA_INT.withName("swapchainCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSwapchains"),
            ADDRESS.withName("pImageIndices"), ADDRESS.withName("pResults")).withName("VkPresentInfoKHR");

    public static void main(String[] args) {
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        byte[] vertexSpirv = Fullscreen.triangleVertexWithUvSpirv();
        byte[] fragmentSpirv = uvGradientFragment();
        NativePlatform platform = NativePlatform.current();

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay triangle", 800, 600, true));
             VulkanInstance instance = new VulkanInstance("VexelRay triangle",
                     platform.requiredVulkanInstanceExtensions())) {

            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, swapchain.format(),
                         Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR, swapchain.width(), swapchain.height(),
                         vertexSpirv, "main", fragmentSpirv, "main", 0);
                 Arena a = Arena.ofShared()) {

                SwapchainFramebuffers framebuffers = new SwapchainFramebuffers(device, swapchain, pipeline.renderPass());
                int presented = renderLoop(device, a, swapchain, pipeline, framebuffers, window, maxFrames);
                device.waitIdle();
                framebuffers.close();
                System.out.println("presented " + presented + " shaded frames");
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    private static int renderLoop(VulkanDevice device, Arena a, VulkanSwapchain swapchain, GraphicsPipeline pipeline,
                                  SwapchainFramebuffers framebuffers, NativeWindow window, int maxFrames) {
        MemorySegment dev = device.handle();
        MethodHandle createSem = device.command("vkCreateSemaphore", C4);
        MethodHandle createFence = device.command("vkCreateFence", C4);
        MethodHandle createPool = device.command("vkCreateCommandPool", C4);
        MethodHandle allocCmd = device.command("vkAllocateCommandBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle waitFences = device.command("vkWaitForFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        MethodHandle resetFences = device.command("vkResetFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle acquire = device.command("vkAcquireNextImageKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
        MethodHandle present = device.command("vkQueuePresentKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle submitCmd = device.command("vkQueueSubmit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        MethodHandle beginCmd = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle endCmd = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle beginRp = device.command("vkCmdBeginRenderPass", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        MethodHandle bindPipe = device.command("vkCmdBindPipeline", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        MethodHandle draw = device.command("vkCmdDraw", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        MethodHandle endRp = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        MethodHandle destroySem = device.command("vkDestroySemaphore", DL);
        MethodHandle destroyFence = device.command("vkDestroyFence", DL);
        MethodHandle destroyPool = device.command("vkDestroyCommandPool", DL);

        long imageAvailable = create(a, createSem, dev, semInfo(a), "vkCreateSemaphore");
        long renderFinished = create(a, createSem, dev, semInfo(a), "vkCreateSemaphore");
        long inFlight = create(a, createFence, dev, fenceSignaled(a), "vkCreateFence");
        long pool = createPool(a, createPool, dev, device.queueFamilyIndex());
        MemorySegment cmd = allocCommandBuffer(a, allocCmd, dev, pool);

        MemorySegment pImageIndex = a.allocate(JAVA_INT);
        MemorySegment pFence = a.allocate(JAVA_LONG);
        pFence.set(JAVA_LONG, 0, inFlight);
        MemorySegment waitSems = a.allocate(JAVA_LONG);
        waitSems.set(JAVA_LONG, 0, imageAvailable);
        MemorySegment signalSems = a.allocate(JAVA_LONG);
        signalSems.set(JAVA_LONG, 0, renderFinished);
        MemorySegment waitStages = a.allocate(JAVA_INT);
        waitStages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        MemorySegment pCmd = a.allocate(ADDRESS);
        pCmd.set(ADDRESS, 0, cmd);
        MemorySegment pSwapchains = a.allocate(JAVA_LONG);

        MemorySegment submit = a.allocate(SUBMIT);
        si(submit, SUBMIT, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
        si(submit, SUBMIT, "waitSemaphoreCount", 1);
        sa(submit, SUBMIT, "pWaitSemaphores", waitSems);
        sa(submit, SUBMIT, "pWaitDstStageMask", waitStages);
        si(submit, SUBMIT, "commandBufferCount", 1);
        sa(submit, SUBMIT, "pCommandBuffers", pCmd);
        si(submit, SUBMIT, "signalSemaphoreCount", 1);
        sa(submit, SUBMIT, "pSignalSemaphores", signalSems);

        MemorySegment presentInfo = a.allocate(PRESENT);
        si(presentInfo, PRESENT, "sType", Vk.STRUCTURE_TYPE_PRESENT_INFO_KHR);
        si(presentInfo, PRESENT, "waitSemaphoreCount", 1);
        sa(presentInfo, PRESENT, "pWaitSemaphores", signalSems);
        si(presentInfo, PRESENT, "swapchainCount", 1);
        sa(presentInfo, PRESENT, "pSwapchains", pSwapchains);
        sa(presentInfo, PRESENT, "pImageIndices", pImageIndex);

        MemorySegment clear = a.allocate(JAVA_FLOAT, 4);   // covered by the triangle, but declared
        MemorySegment beginInfo = a.allocate(CMD_BEGIN);
        si(beginInfo, CMD_BEGIN, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        MemorySegment rpBegin = a.allocate(RENDER_PASS_BEGIN);
        si(rpBegin, RENDER_PASS_BEGIN, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
        sl(rpBegin, RENDER_PASS_BEGIN, "renderPass", pipeline.renderPass());
        si(rpBegin, RENDER_PASS_BEGIN, "area_w", swapchain.width());
        si(rpBegin, RENDER_PASS_BEGIN, "area_h", swapchain.height());
        si(rpBegin, RENDER_PASS_BEGIN, "clearValueCount", 1);
        sa(rpBegin, RENDER_PASS_BEGIN, "pClearValues", clear);

        int frame = 0;
        while (window.pumpEvents() && frame < maxFrames) {
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            int acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable, 0L, pImageIndex);
            if (acq == Vk.ERROR_OUT_OF_DATE_KHR) {
                framebuffers = recreate(device, swapchain, pipeline, framebuffers, window);
                continue;
            }
            check(invoke(resetFences, dev, 1, pFence), "vkResetFences");
            int imageIndex = pImageIndex.get(JAVA_INT, 0);

            check(invoke(beginCmd, cmd, beginInfo), "vkBeginCommandBuffer");
            sl(rpBegin, RENDER_PASS_BEGIN, "framebuffer", framebuffers.framebuffer(imageIndex));
            invokeVoid(beginRp, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(bindPipe, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            invokeVoid(draw, cmd, 3, 1, 0, 0);
            invokeVoid(endRp, cmd);
            check(invoke(endCmd, cmd), "vkEndCommandBuffer");

            check(invoke(submitCmd, device.queue(), 1, submit, inFlight), "vkQueueSubmit");
            pSwapchains.set(JAVA_LONG, 0, swapchain.handle());
            int res = invoke(present, device.queue(), presentInfo);
            if (res == Vk.ERROR_OUT_OF_DATE_KHR || res == Vk.SUBOPTIMAL_KHR) {
                framebuffers = recreate(device, swapchain, pipeline, framebuffers, window);
            }
            frame++;
        }

        device.waitIdle();
        invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
        invokeVoid(destroyFence, dev, inFlight, MemorySegment.NULL);
        invokeVoid(destroySem, dev, renderFinished, MemorySegment.NULL);
        invokeVoid(destroySem, dev, imageAvailable, MemorySegment.NULL);
        return frame;
    }

    private static SwapchainFramebuffers recreate(VulkanDevice device, VulkanSwapchain swapchain,
                                                  GraphicsPipeline pipeline, SwapchainFramebuffers old,
                                                  NativeWindow window) {
        device.waitIdle();
        old.close();
        swapchain.recreate(window.width(), window.height());
        return new SwapchainFramebuffers(device, swapchain, pipeline.renderPass());
    }

    private static byte[] uvGradientFragment() {
        Type.Float f32 = Type.float32();
        Type.Vector vec2 = new Type.Vector(f32, 2);
        Type.Vector vec4 = new Type.Vector(f32, 4);
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, vec2);
        Expr uv = new Expr.InterfaceRead(vUv);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, vec4);
        Expr color = new Expr.VectorConstruct(vec4, List.of(
                new Expr.VectorExtract(uv, 0), new Expr.VectorExtract(uv, 1),
                new Expr.ConstFloat(f32, 0.0), new Expr.ConstFloat(f32, 1.0)));
        Region body = Region.of(new Statement.InterfaceWrite(fragColor, color), new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    private static MemorySegment semInfo(Arena a) {
        MemorySegment s = a.allocate(CI);
        si(s, CI, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        return s;
    }

    private static MemorySegment fenceSignaled(Arena a) {
        MemorySegment s = a.allocate(CI);
        si(s, CI, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
        si(s, CI, "flags", 0x1);   // VK_FENCE_CREATE_SIGNALED_BIT
        return s;
    }

    private static long create(Arena a, MethodHandle createFn, MemorySegment dev, MemorySegment info, String name) {
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(createFn, dev, info, MemorySegment.NULL, p), name);
        return p.get(JAVA_LONG, 0);
    }

    private static long createPool(Arena a, MethodHandle createPool, MemorySegment dev, int queueFamily) {
        MemorySegment info = a.allocate(POOL_CI);
        si(info, POOL_CI, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        si(info, POOL_CI, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        si(info, POOL_CI, "queueFamilyIndex", queueFamily);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(createPool, dev, info, MemorySegment.NULL, p), "vkCreateCommandPool");
        return p.get(JAVA_LONG, 0);
    }

    private static MemorySegment allocCommandBuffer(Arena a, MethodHandle allocCmd, MemorySegment dev, long pool) {
        MemorySegment info = a.allocate(CMD_ALLOC);
        si(info, CMD_ALLOC, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        sl(info, CMD_ALLOC, "commandPool", pool);
        si(info, CMD_ALLOC, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
        si(info, CMD_ALLOC, "commandBufferCount", 1);
        MemorySegment p = a.allocate(ADDRESS);
        check(invoke(allocCmd, dev, info, p), "vkAllocateCommandBuffers");
        return p.get(ADDRESS, 0);
    }

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor DL = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
}
