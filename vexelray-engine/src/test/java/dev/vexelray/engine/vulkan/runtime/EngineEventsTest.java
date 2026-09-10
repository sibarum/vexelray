package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.EngineEvents;
import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That the engine is a publisher, and publishes the truth.
 *
 * <p>The events are easy to add and easy to get subtly wrong in ways nothing notices: a run that ends by
 * throwing and reports {@code clean()}, a technique-closed event that never fires because the close threw, a
 * frame count off by one. Each of those is a lie a subscriber would act on, so each is asserted rather than
 * eyeballed.
 *
 * <p>Every topic is also asserted <em>distinct</em> without a device, because a copy-paste in
 * {@link EngineEvents} that gave two topics one name would silently deliver frames to a resize subscriber and
 * is invisible until something downstream behaves strangely.
 */
class EngineEventsTest {

    private static final int FRAMES = 6;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyTopicHasItsOwnName() {
        List<String> names = List.of(
                EngineEvents.RUN_STARTED.name(), EngineEvents.RUN_ENDED.name(),
                EngineEvents.FRAME_STARTED.name(), EngineEvents.RESIZED.name(),
                EngineEvents.TECHNIQUE_REALIZED.name(), EngineEvents.TECHNIQUE_CLOSED.name(),
                EngineEvents.DEVICE_LOST.name());

        assertEquals(names.size(), names.stream().distinct().count(),
                "two engine topics share a name, so their subscribers will receive each other's events: "
                        + names);
        assertTrue(names.stream().allMatch(n -> n.startsWith("vexelray.engine.")),
                "engine topic names are a wire format once bridged; keep them namespaced: " + names);
    }

    @Test
    void aRunWithNoBusStillRuns() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        // The null-bus path is the default overload's path, and it is the one every existing caller takes.
        TintTechnique tint = new TintTechnique(new float[]{0.2f, 0.4f, 0.9f}, 0.0f);
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("no-bus"))) {
            engine.run(pipelineOf(tint), frame -> frame.frameIndex() < FRAMES - 1);
        }
        assertTrue(tint.recordCount() > 0, "the two-argument run overload did not record a frame");
    }

    @Test
    void aRunPublishesItsLifecycle() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        Atchung bus = Atchung.create();
        AtomicReference<EngineEvents.RunStarted> started = new AtomicReference<>();
        AtomicReference<EngineEvents.RunEnded> ended = new AtomicReference<>();
        AtomicInteger frames = new AtomicInteger();
        List<EngineEvents.TechniqueRealized> realized = new ArrayList<>();
        List<EngineEvents.TechniqueClosed> closed = new ArrayList<>();

        bus.subscribe(EngineEvents.RUN_STARTED, started::set);
        bus.subscribe(EngineEvents.RUN_ENDED, ended::set);
        bus.subscribe(EngineEvents.FRAME_STARTED, e -> frames.incrementAndGet());
        bus.subscribe(EngineEvents.TECHNIQUE_REALIZED, realized::add);
        bus.subscribe(EngineEvents.TECHNIQUE_CLOSED, closed::add);

        TintTechnique tint = new TintTechnique(new float[]{0.9f, 0.3f, 0.1f}, 0.0f);
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("bus"))) {
            engine.run(pipelineOf(tint), bus, frame -> frame.frameIndex() < FRAMES - 1);
        }

        assertNotNull(started.get(), "RUN_STARTED was never published");
        assertEquals("bus", started.get().applicationName());
        assertEquals(1, started.get().techniques());
        assertTrue(started.get().width() > 0 && started.get().height() > 0,
                "RUN_STARTED reported a zero extent: " + started.get());

        assertEquals(1, realized.size(), "one technique realised, so one TECHNIQUE_REALIZED");
        assertSame(tint, realized.get(0).technique());
        assertEquals(0, realized.get(0).index());

        assertEquals(1, closed.size(), "one technique closed, so one TECHNIQUE_CLOSED");
        assertNull(closed.get(0).failure(), "the technique released cleanly but the event says it did not");

        assertNotNull(ended.get(), "RUN_ENDED was never published");
        assertTrue(ended.get().clean(), "the run was asked to stop and did; it must not report a failure");
        assertEquals(FRAMES, ended.get().frames(),
                "RUN_ENDED must report the frames the loop completed, not one more or fewer");

        // FRAME_STARTED fires before the callback, so there is one for the frame that asked to stop too. It
        // can exceed the loop count when the platform pulls extra frames during a resize, never fall short.
        assertTrue(frames.get() >= FRAMES,
                "FRAME_STARTED fired " + frames.get() + " times for " + FRAMES + " frames");
    }

    @Test
    void aRunThatThrowsSaysSoInRunEnded() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        Atchung bus = Atchung.create();
        AtomicReference<EngineEvents.RunEnded> ended = new AtomicReference<>();
        bus.subscribe(EngineEvents.RUN_ENDED, ended::set);

        IllegalStateException thrown = new IllegalStateException("the application blew up");
        TintTechnique tint = new TintTechnique(new float[]{0.1f, 0.9f, 0.4f}, 0.0f);
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("failing"))) {
            engine.run(pipelineOf(tint), bus, frame -> {
                throw thrown;
            });
            throw new AssertionError("the callback's exception did not escape run");
        } catch (IllegalStateException e) {
            assertSame(thrown, e, "run swallowed the callback's exception and raised its own");
        }

        assertNotNull(ended.get(), "RUN_ENDED must fire on the throwing path — that is the path a subscriber "
                + "most needs to hear about");
        assertSame(thrown, ended.get().failure());
        assertTrue(!ended.get().clean(), "a run that ended by throwing reported itself clean");
    }

    private static RenderPipeline pipelineOf(TintTechnique tint) {
        return RenderPipeline.builder()
                .target(Target.windowed("engine events", 320, 240).color(AttachmentFormat.SWAPCHAIN))
                .technique(tint)
                .build();
    }
}
