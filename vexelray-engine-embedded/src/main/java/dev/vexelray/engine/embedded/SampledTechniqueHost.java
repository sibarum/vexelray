package dev.vexelray.engine.embedded;

import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.vulkan.present.Recorder;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An ordered {@link RenderTechnique} list composited into a {@link SampledColorTarget} — a technique host for a
 * target somebody else owns and presents.
 *
 * <p>The engine's own runtime owns a window, a swapchain and a frame loop, which is the right shape for an
 * application whose entire surface is the scene. An application whose scene is a <em>region</em> of a GUI window
 * is the other shape: it gets its target from the host ({@code GuiApp.viewport}), and it presents by being
 * sampled — the GUI draws the image as a box, with chrome laid out around and over it. Both are frames; only one
 * of them could run a technique, and this is the other one.
 *
 * <pre>{@code
 * // Phase WINDOW: the device exists, so a target can be minted.
 * target = app.viewport(w, h);
 * host = new SampledTechniqueHost(target, List.of(march));
 *
 * // FrameStage.APP: the one place the GPU is touched.
 * shell.hooks().add(FrameStage.APP, () -> {
 *     march.camera(eyeX, eyeY, eyeZ);   // per-frame data by the technique's own API (D5)
 *     host.render(sky.r(), sky.g(), sky.b(), 1f);
 * });
 * }</pre>
 *
 * <h2>What this host does not own</h2>
 *
 * <p>Not the device, not the target, and not the loop. It realises techniques against a target it was handed and
 * records them into a pass {@link SampledColorTarget#renderInto} begins — so the thing that decides when a frame
 * happens is still the host application's frame hook, and the thing that owns the pixels is still whoever minted
 * the target. {@link #close()} closes the techniques and nothing else, for the same reason: the target outlives
 * any particular list of things drawn into it.
 *
 * <h2>Depth, and what its absence costs</h2>
 *
 * <p>A {@link SampledColorTarget} is colour-only, so {@link TechniqueContext#depthFormat()} is empty here and
 * {@link TechniqueContext#hasDepth()} is false. Techniques composite by <b>submission order alone</b>: a march
 * then chrome over it is exactly right, and opaque geometry then sprites meant to interleave with it by depth is
 * not expressible yet. That is a property of the target rather than of this host — it reads {@code hasDepth} from
 * whatever it was given, and a first-party technique already branches on it ({@code SdfRaymarchTechnique} builds
 * {@code Depth.NONE} and the same shader, writing a {@code gl_FragDepth} that goes nowhere). Growing a depth
 * attachment on the target is what turns that branch on, and nothing here has to change when it does.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link RenderTechnique}'s contract, unchanged and inherited: every method here is for the thread that owns
 * the target's device — on this stack, the frame thread, from a {@code FrameStage.APP} hook. Realise, record and
 * close all happen on the caller's thread, never concurrently, and this class holds no synchronisation because
 * adding some would only hide the day that stops being true.
 */
public final class SampledTechniqueHost implements AutoCloseable {

    private final List<RenderTechnique> techniques;

    /**
     * Bound once rather than per frame: a capturing lambda in {@link #render} would allocate inside the budget.
     *
     * <p>{@link Recorder} is the presenters' own interface, not one of this module's. Taking it is what puts
     * this host on the same contract the windowed and offscreen paths honour — including the promise that
     * viewport and scissor are already set when a technique records, without which a pipeline built with
     * dynamic viewport state draws nothing.
     */
    private final Recorder recorder = this::recordAll;

    private SampledColorTarget target;

    /** How many of {@link #techniques} are currently realised, and therefore how many are owed a close. */
    private int realized;

    private long frameIndex;
    private long startNanos;
    private long lastNanos;

    /**
     * @param target     the target to composite into; not closed by this host
     * @param techniques the techniques, in the order they record into the frame — the order <em>is</em> the
     *                   composition, exactly as it is for {@code RenderPipeline}
     */
    public SampledTechniqueHost(SampledColorTarget target, List<RenderTechnique> techniques) {
        this.target = Objects.requireNonNull(target, "target");
        this.techniques = List.copyOf(techniques);
        if (this.techniques.isEmpty()) {
            throw new IllegalArgumentException("a host must have at least one technique");
        }
    }

    /**
     * Point this host at a target that has replaced the one it was built on, re-realising every technique
     * against it.
     *
     * <p>This is not an edge case on this stack, it is the common one: a viewport re-mints its target when the
     * box it is shown in outgrows it, and the pipelines built against the old render pass do not carry over.
     * {@link RenderTechnique} states the rule from its own side — realise runs again <i>"only when the target is
     * genuinely rebuilt beneath the pipeline… and it is paired with a {@link RenderTechnique#close()} first"</i>
     * — so a technique never has to tolerate a second realise over live objects, and this is the method that
     * keeps that promise.
     *
     * <p>Passing the target it already holds is a no-op rather than a rebuild, because the caller that re-mints
     * on a growth threshold would otherwise have to remember which side of the threshold it was on.
     */
    public void retarget(SampledColorTarget target) {
        Objects.requireNonNull(target, "target");
        if (target == this.target) {
            return;
        }
        closeRealized();
        this.target = target;
    }

    /**
     * Record one frame: every technique in order, into one render pass, cleared to {@code (cr,cg,cb,ca)} first.
     *
     * <p>Realises on the first call rather than in the constructor, so that a host may be built in whatever phase
     * assembles the application and still do its GPU work on the frame thread. When it returns, the target's
     * image is in {@code SHADER_READ_ONLY} and the next presented frame samples it — which is why announcing a
     * finished picture belongs after this call and not before it.
     */
    public void render(float cr, float cg, float cb, float ca) {
        realize();
        target.renderInto(recorder, cr, cg, cb, ca);
        frameIndex++;
    }

    /**
     * Close every realised technique. The target, the device and anything else handed in are untouched.
     *
     * <p>Register it with the host application's disposer <em>after</em> whatever owns the device, so it runs
     * before it: a technique frees GPU objects, and the thing they belong to has to outlive them.
     */
    @Override
    public void close() {
        closeRealized();
    }

    private void realize() {
        if (realized == techniques.size()) {
            return;
        }
        Context ctx = new Context(target);
        try {
            while (realized < techniques.size()) {
                techniques.get(realized).realize(ctx);
                // Incremented only after realise returns, so a technique that threw is not one this host
                // believes it owns a close for -- RenderTechnique promises close exactly once per *successful*
                // realise, including on this rollback path.
                realized++;
            }
        } catch (RuntimeException | Error e) {
            closeRealized();
            throw e;
        }
        if (startNanos == 0) {
            startNanos = System.nanoTime();
            lastNanos = startNanos;
        }
    }

    private void closeRealized() {
        // Reverse order: a technique realised later may have been built against something an earlier one made.
        RuntimeException failure = null;
        while (realized > 0) {
            realized--;
            try {
                techniques.get(realized).close();
            } catch (RuntimeException e) {
                // Keep closing. One technique that throws on the way down must not strand the rest, which is
                // how a teardown leaks the pipeline the probe's ledger then reports live at exit.
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** The recorder bound in {@link #recorder}: every technique, in order, into the begun pass. */
    private void recordAll(MemorySegment commandBuffer, int width, int height) {
        long now = System.nanoTime();
        double elapsed = (now - startNanos) / 1e9;
        double delta = (now - lastNanos) / 1e9;
        lastNanos = now;
        FrameContext frame = new FrameContext(commandBuffer, frameIndex, elapsed, delta, width, height);
        // Indexed rather than for-each: this runs inside the host's frame budget, and an iterator per frame is
        // an allocation the hook contract above this one does not allow.
        for (int i = 0; i < techniques.size(); i++) {
            techniques.get(i).record(frame);
        }
    }

    /**
     * What a technique is realised against: the sampled target's shape, as the SPI's own vocabulary.
     *
     * <p>Six accessors, four of them the target's verbatim. That it is this thin is the finding this module was
     * written for — the sampled path was already a technique host in everything but the interface, and the only
     * reason {@link SampledColorTarget} does not implement this itself is that
     * {@code vexelray-engine-vulkan-api} depends on {@code vexelray-vulkan} and the reverse would be a cycle.
     */
    private static final class Context implements VulkanTechniqueContext {

        private final SampledColorTarget target;

        Context(SampledColorTarget target) {
            this.target = target;
        }

        @Override
        public AttachmentFormat colorFormat() {
            // SampledColorTarget creates its image and its render pass as FORMAT_R8G8B8A8_UNORM, fixed.
            return AttachmentFormat.RGBA8_UNORM;
        }

        @Override
        public Optional<AttachmentFormat> depthFormat() {
            return Optional.empty();
        }

        @Override
        public int width() {
            return target.width();
        }

        @Override
        public int height() {
            return target.height();
        }

        @Override
        public long renderPass() {
            return target.renderPass();
        }

        @Override
        public VulkanDevice device() {
            return target.device();
        }
    }
}
