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
 * <p><b>Device access.</b> {@link TechniqueContext} exposes the target's formats, extent, render-pass handle, and
 * resource manager as substrate-light values. A technique that must create Vulkan objects casts the context to
 * the runtime-provided Vulkan-bearing subtype (deferred backend abstraction, YAGNI — see D3).
 */
public interface RenderTechnique extends AutoCloseable {

    /**
     * One-time setup against the shared target. Compose shaders, build the pipeline against
     * {@code ctx.renderPass()}, allocate resources. Must be idempotent per realised pipeline: called once per
     * realise, paired with exactly one {@link #close()}.
     */
    void realize(TechniqueContext ctx);

    /**
     * Record this technique's draws for one frame into {@code frame.commandBuffer()}. The runtime has already
     * begun the shared render pass and bound nothing; the technique binds its own pipeline, pushes its per-frame
     * data, and draws. It must not begin/end the render pass or submit.
     */
    void record(FrameContext frame);

    /** Release the GPU objects created in {@link #realize}. Idempotent. */
    @Override
    void close();
}
