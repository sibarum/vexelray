package dev.vexelray.engine;

/**
 * The engine's one extension point: a unit of rendering that writes colour (and optionally depth) into a shared
 * {@link Target}, composited with other techniques in a {@link RenderPipeline}'s declared order. "SDF raymarch,"
 * "polygon raster," and "Gaussian splat" are all <em>techniques</em> — first- or third-party — never built-in
 * modes. The engine core knows only "techniques that write colour and depth, in order"; it never names SDF.
 * Adding a renderable kind means publishing a module that implements this interface; the runtime is untouched.
 *
 * <p>Lifecycle, mirroring realise-once / record-per-frame:
 * <ul>
 *   <li>{@link #realize(TechniqueContext)} — one-time setup against the shared target: compose the technique's
 *       shader(s), build its graphics/compute pipeline against {@link TechniqueContext#renderPass()}, and
 *       allocate any resources. Called once when the pipeline is realised (or after a target rebuild).</li>
 *   <li>{@link #record(FrameContext)} — per frame: bind the pipeline, push per-frame data, and issue draws into
 *       a render pass the <em>runtime</em> has already begun on {@link FrameContext#commandBuffer()}. A technique
 *       never touches the swapchain, sync objects, or the render-pass begin/end — only its own draws.</li>
 *   <li>{@link #close()} — release the GPU objects created in {@code realize}.</li>
 * </ul>
 *
 * <p><b>Per-frame data (see docs/refactor-decisions.md D5).</b> A technique owns its own push-constant / uniform
 * <em>layout</em>, so the application feeds it through the technique's own API (e.g. {@code sdf.camera(x, y, z)})
 * from the {@link VexelEngine} run callback, not as raw bytes. {@link #record} then writes that state. This keeps
 * techniques independent and composable — each manages its per-frame data without the app knowing byte offsets.
 *
 * <p><b>What a resize does, and does not, invalidate.</b> A window resize rebuilds the swapchain images and the
 * framebuffers over them. It does <em>not</em> recreate the render pass, because a render pass depends on the
 * attachments' <em>formats</em> and those do not change with extent — so the handle a technique built its
 * pipeline against at {@link #realize} stays valid, and {@link #realize} is <b>not</b> called again. What does
 * change is the extent, every frame and without warning, which is why it is on {@link FrameContext} rather than
 * only on {@link TechniqueContext}: a technique must build its pipeline with dynamic viewport and scissor and
 * read the size from the frame. Caching {@code ctx.width()} from realise time and drawing to it is the bug this
 * paragraph exists to prevent — it survives every test that never resizes the window.
 *
 * <p>{@link #realize} is called again only when the target is genuinely rebuilt beneath the pipeline — a format
 * change, or a new target — and it is paired with a {@link #close()} first. A technique therefore never has to
 * make {@code realize} tolerate being called twice over live objects.
 *
 * <p><b>Device access.</b> {@link TechniqueContext} exposes the target's formats, extent, render-pass handle, and
 * resource manager as substrate-light values. A technique that must create Vulkan objects casts the context to
 * the runtime-provided Vulkan-bearing subtype (deferred backend abstraction, YAGNI — see D3).
 *
 * <h2>Threading contract</h2>
 *
 * <p><b>A technique is called only from the render thread, and the render thread is the thread that called
 * {@link VexelEngine#run}.</b> That is the whole of it, and it is stated rather than left to be inferred
 * because the alternative was observed to be worse than any particular answer: with nothing written down,
 * every technique assumes something slightly different, the assumptions disagree silently, and the first
 * symptom is a corrupted push constant on somebody else's machine.
 *
 * <p>Concretely:
 *
 * <ul>
 *   <li><b>{@link #realize}, {@link #record} and {@link #close} all run on that one thread</b>, and so does the
 *       {@link VexelEngine.FrameCallback}. They are never concurrent with each other and never nested: the
 *       runtime refuses a re-entrant frame rather than queueing one.</li>
 *   <li><b>Order within a frame is: callback, then every technique's {@code record} in pipeline order.</b> So a
 *       technique's content API — {@code sdf.camera(...)}, {@code canvas.draw(...)} — is called from the
 *       callback, on the render thread, a few microseconds before the {@code record} that reads it. No
 *       handshake is needed between them, and none is provided.</li>
 *   <li><b>A frame can arrive from inside the platform's event pump.</b> During a Win32 modal move or resize,
 *       or a macOS live resize, the host's loop is suspended inside the OS's own and the platform pulls frames
 *       instead. That is still the same thread — but {@code record} may run at a moment when nothing else in
 *       the application is making progress, so a technique must not block in {@code record} waiting on work
 *       the host loop would have driven. This is the one rule that is not simply "single-threaded".</li>
 *   <li><b>Fields are therefore plain.</b> No {@code volatile}, no lock, no atomic — every technique in this
 *       repository is written that way deliberately, and adding synchronisation to satisfy an imagined caller
 *       would only hide the day the contract really changes.</li>
 *   <li><b>Touching a technique from another thread is the application's problem, not the engine's.</b> A
 *       technique may publish a thread-safe content API if it wants one (a worker thread building next frame's
 *       geometry, say) but it must say so in its own javadoc and provide its own memory barrier. Unless a
 *       technique states otherwise, assume every method on it is render-thread-only.</li>
 *   <li><b>{@code close} never races a frame in flight.</b> The runtime waits for the device to go idle before
 *       calling it, so a technique frees GPU objects knowing nothing is still reading them. It is also called
 *       exactly once per successful {@code realize}, including on the rollback path when a <em>later</em>
 *       technique's {@code realize} throws.</li>
 * </ul>
 *
 * <p><b>What would change this, and what would not.</b> Raising frames-in-flight above one does not: it
 * duplicates per-frame GPU resources, not threads. Recording techniques in parallel into secondary command
 * buffers would — and that is a different interface, opted into, not this one quietly acquiring a second
 * caller. Write to the contract above; if it is ever widened, the widening will be a method you had to
 * implement, not a race you had to notice.
 */
public interface RenderTechnique extends AutoCloseable {

    /**
     * One-time setup against the shared target. Compose shaders, build the pipeline against
     * {@code ctx.renderPass()}, allocate resources. Must be idempotent per realised pipeline: called once per
     * realise, paired with exactly one {@link #close()}.
     *
     * <p>Render thread, before the first frame.
     */
    void realize(TechniqueContext ctx);

    /**
     * Record this technique's draws for one frame into {@code frame.commandBuffer()}. The runtime has already
     * begun the shared render pass and set the dynamic viewport and scissor to the frame's extent; nothing else
     * is bound. The technique binds its own pipeline, pushes its per-frame data, and draws. It must not
     * begin/end the render pass or submit.
     *
     * <p>Render thread, once per frame, after the frame callback and never concurrently with another
     * technique's {@code record}. Do no CPU work here that could have happened in the callback, and never
     * block — a frame pulled by the platform during a window drag runs with the host's loop suspended.
     */
    void record(FrameContext frame);

    /**
     * Release the GPU objects created in {@link #realize}. Idempotent.
     *
     * <p>Render thread, after the device has been waited idle, so nothing freed here is still in flight.
     */
    @Override
    void close();
}
