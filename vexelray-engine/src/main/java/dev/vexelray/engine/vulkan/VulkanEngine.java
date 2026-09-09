package dev.vexelray.engine.vulkan;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.vulkan.present.DepthAttachment;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Vulkan runtime behind {@link VexelEngine} — the module that did not exist, and whose absence is why every
 * feature in this repository grew its own path to the screen.
 *
 * <p>What it owns: the window, the Vulkan instance and device, the surface, the swapchain, the shared render pass
 * and its depth attachment, and the frame loop. What it does not own, and never names: a technique. It drives an
 * ordered {@link RenderPipeline} whose members it knows only as {@link RenderTechnique}, which is what makes
 * "SDF raymarch" and "polygon raster" additions rather than modes.
 *
 * <h2>What one frame is</h2>
 *
 * <p>The application callback runs first (input, CPU simulation, per-frame data pushed to techniques through
 * their own APIs), then the runtime acquires an image, begins the shared render pass once, and lets each
 * technique record into that one command buffer in list order. Order is the composition: earlier techniques are
 * behind later ones where depth does not decide it, and where depth does, they interleave per pixel.
 *
 * <p>The frame loop is {@code WindowedPresenter}'s, driven through its
 * {@link WindowedPresenter.Recorder} seam. Nothing about acquire, sync, submit, or present is reimplemented
 * here — that division is the whole reason the seam was cut.
 *
 * <h2>Lifecycle, and why realise is not in the constructor</h2>
 *
 * <p>An engine is created from an {@link EngineConfig}, which knows nothing about a target; the target arrives
 * with a pipeline, at {@link #run}. So the device is built at construction and everything sized to an extent —
 * window, surface, swapchain, render pass, depth — is built when a pipeline is realised, and torn down when
 * {@code run} returns. The engine survives to run another pipeline, which is exactly why the surface could not
 * stay on the config.
 */
public final class VulkanEngine implements VexelEngine {

    /** Diagnostic key for a target asking for a colour format this runtime does not honour. */
    private static final String DIAG_COLOR_FORMAT = "engine.target.colorFormat";

    private final EngineConfig config;
    private final NativePlatform platform;

    private boolean closed;

    public VulkanEngine(EngineConfig config) {
        this.config = config;
        this.platform = NativePlatform.current();
    }

    public EngineConfig config() {
        return config;
    }

    @Override
    public void run(RenderPipeline pipeline, FrameCallback onFrame) {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
        Target target = pipeline.target();
        if (target.kind() != Target.Kind.WINDOWED) {
            // Refused, not quietly windowed. An offscreen target that silently opened a window would be a
            // headless test that passes on a developer's desktop and hangs in CI with no window server.
            throw new UnsupportedOperationException(
                    "this runtime presents to a window; Target.Kind." + target.kind() + " is not implemented yet");
        }
        runWindowed(pipeline, target, onFrame);
    }

    private void runWindowed(RenderPipeline pipeline, Target target, FrameCallback onFrame) {
        List<RenderTechnique> techniques = pipeline.techniques();
        int depthFormat = target.hasDepth() ? DepthAttachment.FORMAT : VulkanRenderPass.NO_DEPTH;

        try (NativeWindow window = platform.createWindow(
                new WindowConfig(target.title(), target.width(), target.height(), true));
             VulkanInstance instance = new VulkanInstance(config.applicationName(),
                     platform.requiredVulkanInstanceExtensions())) {

            long surface = window.createVulkanSurface(instance.handleAddress(),
                    VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present capable device"));

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 VulkanRenderPass renderPass = new VulkanRenderPass(device,
                         colorFormat(target, swapchain), Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR, depthFormat);
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         window, depthFormat)) {

                realizeAll(techniques, device, target, swapchain, renderPass);
                RuntimeException primary = null;
                try {
                    loop(techniques, presenter, swapchain, onFrame);
                } catch (RuntimeException e) {
                    primary = e;
                    throw e;
                } finally {
                    // The GPU must be idle before a technique frees anything it may still be reading.
                    device.waitIdle();
                    List<Throwable> failures = closeAll(techniques);
                    if (!failures.isEmpty()) {
                        // Suppression rather than a log line, and rather than the first failure winning: a
                        // technique that throws on close must not strand the ones after it, must not replace an
                        // exception already unwinding, and must not vanish when there is no such exception.
                        // This is what try-with-resources would do if the resources were known statically.
                        if (primary != null) {
                            failures.forEach(primary::addSuppressed);
                        } else {
                            IllegalStateException failed = new IllegalStateException(
                                    failures.size() + " technique(s) failed to release GPU objects");
                            failures.forEach(failed::addSuppressed);
                            throw failed;
                        }
                    }
                }
            }
        }
    }

    /**
     * Realise every technique against the shared target, and undo the ones that succeeded if a later one throws.
     *
     * <p>Without the rollback, a pipeline whose third technique fails to compose its shader leaves two
     * techniques holding live GPU objects that nothing will ever close, and the exception that surfaces is about
     * the third one — so the leak is invisible and attributed elsewhere.
     */
    private void realizeAll(List<RenderTechnique> techniques, VulkanDevice device, Target target,
                            VulkanSwapchain swapchain, VulkanRenderPass renderPass) {
        VulkanTechniqueContext ctx = new VulkanTechniqueContext(device, target.colorFormat(),
                target.depthFormat(), swapchain.width(), swapchain.height(), renderPass.handle());
        List<RenderTechnique> realized = new ArrayList<>(techniques.size());
        try {
            for (RenderTechnique technique : techniques) {
                technique.realize(ctx);
                realized.add(technique);
            }
        } catch (RuntimeException | Error e) {
            closeAll(realized).forEach(e::addSuppressed);
            throw e;
        }
    }

    /**
     * Close every technique, in reverse order, and return what threw rather than deciding what to do about it.
     *
     * <p>Reverse for the reason destruction usually is: a later technique may have been built against something
     * an earlier one owns. Returning the failures instead of logging them is what lets the caller attach them
     * to whichever exception is actually travelling — a close failure is an error, not a dropped capability, so
     * {@link Diagnostics} is the wrong channel for it.
     */
    private List<Throwable> closeAll(List<RenderTechnique> techniques) {
        List<Throwable> failures = new ArrayList<>();
        for (int i = techniques.size() - 1; i >= 0; i--) {
            try {
                techniques.get(i).close();
            } catch (RuntimeException | Error e) {
                failures.add(e);
            }
        }
        return failures;
    }

    /**
     * The frame loop: application callback, then every technique records into one begun render pass.
     *
     * <p>{@code pushConstantBytes} is zero because push constants belong to techniques now, not to the
     * presenter — each pushes its own layout inside {@code record}, which is what let the presenter stop
     * knowing what a camera is.
     */
    private void loop(List<RenderTechnique> techniques, WindowedPresenter presenter, VulkanSwapchain swapchain,
                      FrameCallback onFrame) {
        long[] frameIndex = {0};
        double[] elapsed = {0};
        boolean[] running = {true};

        WindowedPresenter.Frame perFrame = (dt, push) -> {
            elapsed[0] += dt;
            if (onFrame != null && !onFrame.onFrame(new FrameInfo(frameIndex[0], elapsed[0], dt,
                    swapchain.width(), swapchain.height()))) {
                running[0] = false;
            }
        };

        WindowedPresenter.Recorder recorder = (cmd, width, height) -> {
            FrameContext frame = new FrameContext(cmd, frameIndex[0], elapsed[0], 0, width, height);
            for (RenderTechnique technique : techniques) {
                technique.record(frame);
            }
        };

        while (running[0] && presenter.frame(0, perFrame, recorder)) {
            frameIndex[0]++;
        }
    }

    /**
     * The {@code VkFormat} the colour attachment is created with.
     *
     * <p>{@link AttachmentFormat#SWAPCHAIN} means "whatever the surface presents," which is the only choice that
     * is guaranteed to be presentable, and so the only one this runtime honours for a windowed target. Anything
     * else is a request it cannot meet — and it says so instead of substituting, because a frame drawn in a
     * format the caller did not ask for looks like a colour-management bug rather than a dropped capability
     * (the fault vexelray-diagnostics exists for).
     */
    private int colorFormat(Target target, VulkanSwapchain swapchain) {
        if (target.colorFormat() != AttachmentFormat.SWAPCHAIN) {
            Diagnostics.dropped(DIAG_COLOR_FORMAT,
                    "the windowed target's colour format " + target.colorFormat(),
                    "a windowed target is presented in the surface's own format, which is the only format "
                            + "guaranteed presentable; drawing in " + AttachmentFormat.SWAPCHAIN + " instead");
        }
        return swapchain.format();
    }

    /** The depth format a target's declaration resolves to, for anything that needs to report it. */
    public static Optional<AttachmentFormat> depthFormatOf(Target target) {
        return target.depthFormat();
    }

    @Override
    public void close() {
        closed = true;
    }
}
