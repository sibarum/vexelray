package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.diag.Diagnostics;
import dev.vexelray.engine.EngineEvents;
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
import dev.vexelray.vulkan.present.FrameUpdate;
import dev.vexelray.vulkan.present.OffscreenPresenter;
import dev.vexelray.vulkan.present.Recorder;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.DeviceLostException;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

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
 * <p>The frame loop is the presenter's, driven through the {@link Recorder} seam. Nothing about acquire, sync,
 * submit, or present is reimplemented here — that division is the whole reason the seam was cut.
 *
 * <h2>Two targets, one frame loop</h2>
 *
 * <p>{@link Target.Kind#WINDOWED} runs on a {@link WindowedPresenter} over a swapchain;
 * {@link Target.Kind#OFFSCREEN} runs on an {@link OffscreenPresenter} over an image, and the frame is
 * afterwards readable through {@link #lastFrameRgba()}. Everything between the two — realise, the frame loop,
 * the per-frame events, the {@code Recorder} that walks the techniques, release — is the same code, and
 * {@link #driveTechniques} is where that is enforced rather than intended. It has to be: a headless capture
 * is only evidence about what a window would show while the two runs differ in the presenter and nowhere
 * else.
 *
 * <p>The one behavioural difference is how a run ends. A windowed run ends when the window closes; an
 * offscreen run has nothing of the sort, so the frame callback is the only thing that can stop it and
 * {@link #run} refuses an offscreen pipeline without one.
 *
 * <h2>Lifecycle, and why realise is not in the constructor</h2>
 *
 * <p>An engine is created from an {@link EngineConfig}, which knows nothing about a target; the target arrives
 * with a pipeline, at {@link #run}. So the device is built at construction and everything sized to an extent —
 * window, surface, swapchain, render pass, depth — is built when a pipeline is realised, and torn down when
 * {@code run} returns. The engine survives to run another pipeline, which is exactly why the surface could not
 * stay on the config.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #run} is the render thread: it creates the window on the caller's thread, pumps that window's
 * events there, and calls every technique's {@code realize}, {@code record} and {@code close} — and the
 * application callback — from it. Nothing here starts a thread. {@link RenderTechnique} states the contract
 * techniques are written against; this class is what makes it true, and the only field that crosses threads
 * is {@link #windowHandle}.
 *
 * <h2>What it publishes</h2>
 *
 * <p>Given a bus, it publishes {@link EngineEvents}: the run starting and ending, each frame, each resize,
 * each technique realised and closed, and device loss. All from the render thread, so an inline subscriber's
 * cost is frame time. Given no bus it publishes nothing and builds no event objects at all — the
 * {@link Events} holder below is the one place that knows which of those is happening.
 */
public final class VulkanEngine implements VexelEngine {

    /** Diagnostic key for a windowed target asking for a colour format this runtime does not honour. */
    private static final String DIAG_COLOR_FORMAT = "engine.target.colorFormat";

    /**
     * The same fault for an offscreen target, under its own key.
     *
     * <p>Separate because {@link Diagnostics} warns once per key: sharing one would mean whichever kind of
     * run happened first silenced the other, and the two have different reasons and different advice.
     */
    private static final String DIAG_COLOR_FORMAT_OFFSCREEN = "engine.target.colorFormat.offscreen";

    /** What {@link EngineEvents.RunStarted} reports as the runtime that drew — the provider's own name. */
    static final String ENGINE_NAME = "vexelray-engine (Vulkan, Panama)";

    private final EngineConfig config;
    private final NativePlatform platform;

    /**
     * The running window's OS handle, or 0.
     *
     * <p>Volatile because {@link #windowHandle()} is the one method here an application may legitimately call
     * from a thread that is not the render thread — an input or watchdog thread asking whether there is a
     * window yet. Everything else in this class, and every technique it drives, is single-threaded (see
     * {@link RenderTechnique}'s threading contract); this field is the exception, and it is a {@code long}
     * written once and cleared once so volatile is the whole of what it needs.
     */
    private volatile long windowHandle;

    /**
     * The pixels the last offscreen run finished on, or null before one has.
     *
     * <p>Not volatile, unlike {@link #windowHandle}: it is written on the render thread at the end of a run
     * and read by whoever called {@code run}, which is the same thread — the whole point of
     * {@link #lastFrameRgba()} being valid only after {@code run} returns is that there is no concurrency to
     * arrange.
     */
    private byte[] lastFrame;

    private boolean closed;

    public VulkanEngine(EngineConfig config) {
        this.config = config;
        this.platform = NativePlatform.current();
    }

    public EngineConfig config() {
        return config;
    }

    @Override
    public void run(RenderPipeline pipeline, Atchung bus, FrameCallback onFrame) {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
        Target target = pipeline.target();
        Events events = new Events(bus);
        switch (target.kind()) {
            case WINDOWED -> guarded(events, () -> presentWindowed(pipeline, target, events, onFrame));
            case OFFSCREEN -> {
                if (onFrame == null) {
                    // Refused before anything is created, because the alternative is a hang rather than a
                    // failure. A windowed run ends when the window closes; an offscreen one has no window, no
                    // swapchain that can go out of date, and nothing else that can say stop — so the frame
                    // callback is the only thing that can end it, and a run without one never returns.
                    throw new IllegalArgumentException("an offscreen run needs a frame callback: there is no "
                            + "window to close, so returning false from it is the only way the loop ends");
                }
                guarded(events, () -> renderOffscreen(pipeline, target, events, onFrame));
            }
        }
    }

    /**
     * The parts of a run that are the same whether it went to a window or to an image: device loss told apart
     * from every other failure, the window handle cleared, and {@code RUN_ENDED} published on every path
     * including the throwing ones.
     */
    private void guarded(Events events, Runnable body) {
        RuntimeException failure = null;
        try {
            body.run();
        } catch (DeviceLostException e) {
            // Told apart from every other failure because the recovery is different in kind: a subscriber
            // hearing this must rebuild the engine, not retry the frame. It is published *and* rethrown —
            // an event is a notification, never a way of swallowing an exception.
            failure = e;
            events.publish(EngineEvents.DEVICE_LOST, new EngineEvents.DeviceLost(e.call(), e));
            throw e;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            windowHandle = 0;
            // Last thing before returning, on every path including the throwing ones — the case a subscriber
            // most needs to hear about is the run that stopped without being asked to.
            events.publish(EngineEvents.RUN_ENDED, new EngineEvents.RunEnded(events.frames, failure));
        }
    }

    private void presentWindowed(RenderPipeline pipeline, Target target, Events events, FrameCallback onFrame) {
        List<RenderTechnique> techniques = pipeline.techniques();
        int depthFormat = target.hasDepth() ? DepthAttachment.FORMAT : VulkanRenderPass.NO_DEPTH;

        try (NativeWindow window = platform.createWindow(
                new WindowConfig(target.title(), target.width(), target.height(), true));
             VulkanInstance instance = new VulkanInstance(config.applicationName(),
                     platform.requiredVulkanInstanceExtensions())) {

            // Published before any technique is realised and any frame runs, so an application can attach input
            // to it on frame zero; cleared in the finally below, because a handle that outlives its window is
            // one an application will eventually pass to something.
            this.windowHandle = window.osHandle();

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

                // The extent is the swapchain's and it is read every frame, not captured: a drag-resize
                // changes it without re-realising anything.
                driveTechniques(techniques, device, target, renderPass, events, new FrameTarget() {
                    @Override
                    public boolean frame(FrameUpdate perFrameUpdate, Recorder recorder) {
                        return presenter.frame(0, perFrameUpdate, recorder);
                    }

                    @Override
                    public int width() {
                        return swapchain.width();
                    }

                    @Override
                    public int height() {
                        return swapchain.height();
                    }
                }, onFrame);
            }
        }
    }

    /**
     * The headless path: no window, no surface, no swapchain, no present — a colour image the techniques
     * composite into, and {@link OffscreenPresenter#readRgba()} at the end of it.
     *
     * <p>Everything above the presenter is the same code the windowed path runs: the same {@code Recorder}
     * carrying the same techniques through the same shared render pass. That is the property the whole thing
     * is for. A headless capture is only evidence about what a window would show if the two paths differ in
     * the presenter and nowhere else, and the moment a technique has to ask which one it is under, a passing
     * offscreen test stops meaning anything about the interactive one.
     *
     * <p>The instance is created with <b>no</b> extensions, where the windowed path asks the platform for its
     * surface pair: a headless run needs neither, and requesting {@code VK_KHR_surface} on a machine with no
     * window system is how a test that should have run fails at instance creation instead. (Validation still
     * gets its extension — {@code VulkanInstance} adds that itself when it is asked for.)
     */
    private void renderOffscreen(RenderPipeline pipeline, Target target, Events events, FrameCallback onFrame) {
        List<RenderTechnique> techniques = pipeline.techniques();
        int depthFormat = target.hasDepth() ? DepthAttachment.FORMAT : VulkanRenderPass.NO_DEPTH;

        try (VulkanInstance instance = new VulkanInstance(config.applicationName(), List.of())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 // TRANSFER_SRC_OPTIMAL rather than PRESENT_SRC_KHR is the whole difference in the pass: the
                 // frame's next reader is a copy, not a presentation engine. VulkanRenderPass adds the
                 // outgoing subpass dependency for it without being told which of the two this is.
                 VulkanRenderPass renderPass = new VulkanRenderPass(device, offscreenColorFormat(target),
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, depthFormat);
                 OffscreenPresenter presenter = new OffscreenPresenter(device, renderPass.handle(),
                         target.width(), target.height(), depthFormat)) {

                RuntimeException primary = null;
                try {
                    driveTechniques(techniques, device, target, renderPass, events, new FrameTarget() {
                        @Override
                        public boolean frame(FrameUpdate perFrameUpdate, Recorder recorder) {
                            return presenter.frame(0, perFrameUpdate, recorder);
                        }

                        @Override
                        public int width() {
                            return presenter.width();
                        }

                        @Override
                        public int height() {
                            return presenter.height();
                        }
                    }, onFrame);
                } catch (RuntimeException e) {
                    primary = e;
                    throw e;
                } finally {
                    // Before the presenter is closed and after the techniques are, on the failing path too: a
                    // run that threw on frame 40 is exactly the run whose last frame someone wants to look at.
                    // Guarded on a frame having happened, because reading an image nothing has drawn into is
                    // not a blank picture, it is undefined contents in a layout no copy may read.
                    if (presenter.frameCount() > 0) {
                        try {
                            lastFrame = presenter.readRgba();
                        } catch (RuntimeException e) {
                            // A failed capture must not replace the failure that caused it. On a device that
                            // has just been lost, the read is the *second* thing to notice and the first
                            // thing to finish unwinding — so it would win, and the report would name a
                            // readback rather than whatever actually went wrong two frames earlier.
                            if (primary != null) {
                                primary.addSuppressed(e);
                            } else {
                                throw e;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Realise the techniques, run the frame loop, and release them — the lifecycle both present paths share,
     * down to which failure wins when a close throws while an exception is already travelling.
     *
     * <p>Shared rather than copied because every line of it is a promise {@code RenderTechnique} makes, and a
     * second copy is a second place for one of those promises to quietly become false for headless runs only.
     */
    private void driveTechniques(List<RenderTechnique> techniques, VulkanDevice device, Target target,
                                 VulkanRenderPass renderPass, Events events, FrameTarget frameTarget,
                                 FrameCallback onFrame) {
        realizeAll(techniques, device, target, frameTarget.width(), frameTarget.height(), renderPass, events);
        events.publish(EngineEvents.RUN_STARTED, new EngineEvents.RunStarted(
                config.applicationName(), ENGINE_NAME, frameTarget.width(), frameTarget.height(),
                target.hasDepth(), techniques.size()));
        RuntimeException primary = null;
        try {
            loop(techniques, frameTarget, events, onFrame);
        } catch (RuntimeException e) {
            primary = e;
            throw e;
        } finally {
            // The GPU must be idle before a technique frees anything it may still be reading.
            device.waitIdle();
            List<Throwable> failures = closeAll(techniques, events);
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

    /**
     * What {@link #loop} needs of a presenter: draw one frame, and say what extent that frame has.
     *
     * <p>Two methods and one caller, and it still earns its name. The alternative was a second copy of the
     * frame loop for the headless path, and the frame loop is where the engine's per-frame promises live —
     * the resize event before the frame event, the callback before the recording, one {@code FrameContext}
     * shared by every technique in a frame. Those are not promises to make twice.
     *
     * <p>The extent is a method rather than a pair of numbers because for a window it changes: a resize moves
     * it without re-realising anything, so the loop must ask every frame rather than be told once.
     */
    private interface FrameTarget {
        boolean frame(FrameUpdate perFrame, Recorder recorder);

        int width();

        int height();
    }

    /**
     * Realise every technique against the shared target, and undo the ones that succeeded if a later one throws.
     *
     * <p>Without the rollback, a pipeline whose third technique fails to compose its shader leaves two
     * techniques holding live GPU objects that nothing will ever close, and the exception that surfaces is about
     * the third one — so the leak is invisible and attributed elsewhere.
     */
    private void realizeAll(List<RenderTechnique> techniques, VulkanDevice device, Target target,
                            int width, int height, VulkanRenderPass renderPass, Events events) {
        SharedTargetContext ctx = new SharedTargetContext(device, target.colorFormat(),
                target.depthFormat(), width, height, renderPass.handle());
        List<RenderTechnique> realized = new ArrayList<>(techniques.size());
        try {
            for (RenderTechnique technique : techniques) {
                technique.realize(ctx);
                events.publish(EngineEvents.TECHNIQUE_REALIZED,
                        new EngineEvents.TechniqueRealized(technique, realized.size()));
                realized.add(technique);
            }
        } catch (RuntimeException | Error e) {
            closeAll(realized, events).forEach(e::addSuppressed);
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
    private List<Throwable> closeAll(List<RenderTechnique> techniques, Events events) {
        List<Throwable> failures = new ArrayList<>();
        for (int i = techniques.size() - 1; i >= 0; i--) {
            RenderTechnique technique = techniques.get(i);
            Throwable failed = null;
            try {
                technique.close();
            } catch (RuntimeException | Error e) {
                failed = e;
                failures.add(e);
            }
            events.publish(EngineEvents.TECHNIQUE_CLOSED,
                    new EngineEvents.TechniqueClosed(technique, i, failed));
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
    private void loop(List<RenderTechnique> techniques, FrameTarget frameTarget,
                      Events events, FrameCallback onFrame) {
        long[] frameIndex = {0};
        double[] elapsed = {0};
        double[] delta = {0};
        boolean[] running = {true};
        int[] lastExtent = {frameTarget.width(), frameTarget.height()};

        FrameUpdate perFrame = (dt, push) -> {
            elapsed[0] += dt;
            delta[0] = dt;
            int width = frameTarget.width();
            int height = frameTarget.height();
            if (events.on()) {
                // Resize first, so a subscriber that reflows on RESIZED has done it before the FrameStarted
                // that carries the new extent — and before the callback that may read the reflowed result.
                if (width != lastExtent[0] || height != lastExtent[1]) {
                    events.publish(EngineEvents.RESIZED,
                            new EngineEvents.Resized(width, height, lastExtent[0], lastExtent[1]));
                }
                events.publish(EngineEvents.FRAME_STARTED,
                        new EngineEvents.FrameStarted(frameIndex[0], elapsed[0], dt, width, height));
            }
            lastExtent[0] = width;
            lastExtent[1] = height;
            if (onFrame != null && !onFrame.onFrame(new FrameInfo(frameIndex[0], elapsed[0], dt,
                    width, height))) {
                running[0] = false;
            }
        };

        Recorder recorder = (cmd, width, height) -> {
            FrameContext frame = new FrameContext(cmd, frameIndex[0], elapsed[0], delta[0], width, height);
            for (RenderTechnique technique : techniques) {
                technique.record(frame);
            }
        };

        while (running[0] && frameTarget.frame(perFrame, recorder)) {
            frameIndex[0]++;
            events.frames = frameIndex[0];
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

    /**
     * The {@code VkFormat} an offscreen target's colour attachment is created with.
     *
     * <p>{@link OffscreenPresenter#FORMAT} is the only one implemented, so this is really the mirror image of
     * {@link #colorFormat}: there, a target asking for anything but {@link AttachmentFormat#SWAPCHAIN} cannot
     * be honoured because only the surface's own format is presentable; here, anything but
     * {@link AttachmentFormat#RGBA8_UNORM} cannot be honoured because the readback promises those bytes.
     *
     * <p>{@link AttachmentFormat#SWAPCHAIN} is reported like the rest rather than treated as a special case
     * of "no preference". It means "whatever the surface presents", and there is no surface — so a pipeline
     * moved from a window to an image is asking for something that has stopped existing, and quietly
     * answering would hide the one difference between the two runs that could change the pixels.
     */
    private int offscreenColorFormat(Target target) {
        if (target.colorFormat() != AttachmentFormat.RGBA8_UNORM) {
            Diagnostics.dropped(DIAG_COLOR_FORMAT_OFFSCREEN,
                    "the offscreen target's colour format " + target.colorFormat(),
                    "an offscreen target is drawn in " + AttachmentFormat.RGBA8_UNORM + ", the only format "
                            + "this runtime can read back; drawing in that instead");
        }
        return OffscreenPresenter.FORMAT;
    }

    /** The depth format a target's declaration resolves to, for anything that needs to report it. */
    public static Optional<AttachmentFormat> depthFormatOf(Target target) {
        return target.depthFormat();
    }

    @Override
    public long windowHandle() {
        return windowHandle;
    }

    @Override
    public byte[] lastFrameRgba() {
        if (lastFrame == null) {
            throw new IllegalStateException("no offscreen run has completed on this engine — a windowed run "
                    + "presents to a screen and captures nothing, and an offscreen one is readable only after "
                    + "run(...) returns");
        }
        // A copy per call, so a caller that mutates what it reads (unpacking in place, thresholding) does not
        // quietly change what the next caller sees. Captures are small and read rarely; the alternative is a
        // shared mutable array handed out under a name that sounds like a value.
        return lastFrame.clone();
    }

    @Override
    public void close() {
        closed = true;
    }

    /**
     * This run's publishing, and the frame count {@link EngineEvents.RunEnded} reports.
     *
     * <p>One place that knows the bus may be absent, so no call site has to. {@link #on()} exists for the
     * per-frame publishes only: {@link #publish} already tolerates no bus, but the <em>argument</em> to it is
     * a record that would be built and thrown away every frame, and a run with no bus should cost exactly
     * nothing. Where a bus is present the record is built whether or not anything subscribed — that is
     * deliberate, and {@link EngineEvents} says why.
     */
    private static final class Events {

        private final Atchung bus;
        private long frames;

        Events(Atchung bus) {
            this.bus = bus;
        }

        boolean on() {
            return bus != null;
        }

        <T> void publish(Topic<T> topic, T event) {
            if (bus != null) {
                bus.publish(topic, event);
            }
        }
    }
}
