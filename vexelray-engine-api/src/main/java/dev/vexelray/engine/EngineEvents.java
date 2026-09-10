package dev.vexelray.engine;

import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;

/**
 * What the engine publishes, and the topics it publishes on — the answer to "is the engine a publisher?", which
 * is <b>yes</b>.
 *
 * <h2>Why a bus and not a listener interface</h2>
 *
 * <p>{@link VexelEngine.FrameCallback} is one callback with one caller, and it is the wrong shape to grow into
 * a bus: a second interested party has to be chained onto the first by whoever holds it, ordering between them
 * is whatever the chaining code happened to do, and nothing can subscribe without the code that owns the
 * callback knowing it exists. That is exactly the property event-based scripting needs and cannot get from a
 * callback — a script attaches to a running engine it did not construct.
 *
 * <p>Atchung! is already the input plumbing on the other side of the same application (Tactroller devices →
 * the {@code tactroller-atchung} bridge → the bus → Fathom's simulation). Publishing engine events onto that
 * same bus means input and rendering meet in one place, with one set of delivery modes, one backpressure
 * policy, and one thing to bridge across a process boundary when a replay or a remote viewer wants both.
 * A second, engine-shaped observer mechanism beside it would be a second thing to learn and a second thing to
 * bridge.
 *
 * <h2>Delivery, and what that costs</h2>
 *
 * <p>Every event below is published from the <b>render thread</b> (see {@link RenderTechnique}'s threading
 * contract). So:
 *
 * <ul>
 *   <li>{@link Atchung#subscribe inline} subscribers run <em>on the render thread, inside the frame</em>. Their
 *       cost is frame time. Use inline only for something that must observe the frame as it happens and is
 *       measured in microseconds.</li>
 *   <li>{@link Atchung#subscribeAsync async} subscribers run on your executor — the right default for logging,
 *       telemetry, or anything that talks to a network or a disk.</li>
 *   <li>A {@link Atchung#pump() pumped} subscription queues and is drained on whatever thread calls
 *       {@code drain()} — the right shape for a simulation or UI thread that wants engine events folded into
 *       its own tick.</li>
 * </ul>
 *
 * <p>Events are published unconditionally, even with nothing subscribed. A publish to an empty topic is a map
 * lookup and a counter increment, and that counter is deliberate: "the event never arrived" is a whole class of
 * bug, and the bus's no-subscriber count is what makes it visible from the publishing end. Gating publication
 * on {@link Atchung#subscriberCount} would save one small record per frame and delete that signal.
 *
 * <p>Passing no bus to {@link VexelEngine#run(RenderPipeline, Atchung, VexelEngine.FrameCallback)} publishes
 * nothing at all, which is what the two-argument overload does.
 *
 * <h2>Stability</h2>
 *
 * <p>Topic names are part of the wire format the moment an {@code ElektroBridge} carries one across a process
 * boundary, so they are spelled out as constants here and are not derived from class names. Adding a topic is
 * additive; renaming one is a breaking change to anything that bridged it.
 */
public final class EngineEvents {

    private EngineEvents() {
    }

    /** Published once per {@link VexelEngine#run}, after every technique has realised and before frame zero. */
    public static final Topic<RunStarted> RUN_STARTED =
            Topic.of("vexelray.engine.run.started", RunStarted.class);

    /**
     * Published once per {@link VexelEngine#run}, as the last thing before it returns — including when it is
     * returning because something threw, which is the case a subscriber most needs to hear about.
     */
    public static final Topic<RunEnded> RUN_ENDED =
            Topic.of("vexelray.engine.run.ended", RunEnded.class);

    /** Published before the frame callback runs, once per frame. */
    public static final Topic<FrameStarted> FRAME_STARTED =
            Topic.of("vexelray.engine.frame.started", FrameStarted.class);

    /**
     * Published when the framebuffer extent differs from the previous frame's — not on every frame, and not
     * from inside the platform's resize loop as a stream of intermediate sizes it did not present.
     */
    public static final Topic<Resized> RESIZED =
            Topic.of("vexelray.engine.resized", Resized.class);

    /** Published after each technique's {@code realize}, in pipeline order. */
    public static final Topic<TechniqueRealized> TECHNIQUE_REALIZED =
            Topic.of("vexelray.engine.technique.realized", TechniqueRealized.class);

    /** Published after each technique's {@code close}, in reverse pipeline order, failure or not. */
    public static final Topic<TechniqueClosed> TECHNIQUE_CLOSED =
            Topic.of("vexelray.engine.technique.closed", TechniqueClosed.class);

    /**
     * Published when the device is lost — a GPU reset, a driver crash, a hot-unplugged eGPU.
     *
     * <p>Distinct from the failure carried by {@link RunEnded} because the recovery is different in kind:
     * everything made from the device is gone and no amount of retrying the frame helps, whereas an ordinary
     * failure may be a bug in one technique. A subscriber's correct response is to tear the engine down and
     * build a new one, or to tell the user.
     */
    public static final Topic<DeviceLost> DEVICE_LOST =
            Topic.of("vexelray.engine.device.lost", DeviceLost.class);

    /**
     * The pipeline is realised and the first frame is about to run.
     *
     * @param applicationName the name from {@code EngineConfig}
     * @param engineName      the {@link EngineProvider} that served this run, so a log says which runtime drew
     * @param width           the framebuffer width at realise time
     * @param height          the framebuffer height at realise time
     * @param hasDepth        whether the shared target carries a depth attachment
     * @param techniques      how many techniques composite into the frame
     */
    public record RunStarted(String applicationName, String engineName, int width, int height,
                             boolean hasDepth, int techniques) {
    }

    /**
     * The run loop has finished.
     *
     * @param frames  how many frames the loop completed
     * @param failure the exception that ended the run, or {@code null} if it ended because the callback asked
     *                it to or the window was closed
     */
    public record RunEnded(long frames, RuntimeException failure) {

        /** Whether this run ended the way it was asked to, rather than by throwing. */
        public boolean clean() {
            return failure == null;
        }
    }

    /**
     * A frame is about to be simulated and recorded. The same numbers the {@link VexelEngine.FrameCallback}
     * receives, published so that something which is not the callback can see them.
     *
     * @param frameIndex   monotonically increasing frame count since the run began
     * @param timeSeconds  seconds since the run began
     * @param deltaSeconds seconds since the previous frame
     * @param width        current framebuffer width
     * @param height       current framebuffer height
     */
    public record FrameStarted(long frameIndex, double timeSeconds, double deltaSeconds, int width, int height) {
    }

    /**
     * The framebuffer changed size.
     *
     * <p>Note what this does <em>not</em> mean: techniques are not re-realised, and the render pass is
     * unchanged, because a pass depends on the attachments' formats and those do not change with extent. A
     * subscriber that rebuilds something size-dependent of its own is the intended audience.
     *
     * @param width          the new framebuffer width
     * @param height         the new framebuffer height
     * @param previousWidth  the width of the previous frame
     * @param previousHeight the height of the previous frame
     */
    public record Resized(int width, int height, int previousWidth, int previousHeight) {
    }

    /**
     * A technique finished realising against the shared target.
     *
     * @param technique the technique itself — a subscriber that knows the concrete type may use its content API
     * @param index     its position in the pipeline, which is also its compositing order
     */
    public record TechniqueRealized(RenderTechnique technique, int index) {
    }

    /**
     * A technique was closed.
     *
     * @param technique the technique
     * @param index     its position in the pipeline
     * @param failure   what its {@code close} threw, or {@code null} if it released cleanly. A close failure
     *                  does not stop the ones after it, so this can arrive several times in one teardown.
     */
    public record TechniqueClosed(RenderTechnique technique, int index, Throwable failure) {
    }

    /**
     * The device was lost.
     *
     * @param call  the native call that reported it, for a message that says where rather than only what
     * @param cause the exception carrying it, which is also about to unwind out of {@link VexelEngine#run}
     */
    public record DeviceLost(String call, RuntimeException cause) {
    }
}
