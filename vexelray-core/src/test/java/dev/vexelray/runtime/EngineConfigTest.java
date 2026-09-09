package dev.vexelray.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link EngineConfig} is allowed to hold, and why the defaults are the numbers they are.
 *
 * <p>Most of this is ordinary validation. One test is not: {@link #holdsNothingAboutTheSurface()} asserts the
 * shape of the record rather than its behaviour, because the fault it guards against is a <em>re-addition</em>.
 * This record used to carry the surface, two types described the frame's size, and nothing said which the
 * runtime believed. That is not a bug a behavioural test can catch — the code compiles and runs either way — so
 * the guard has to be structural, and it has to say so out loud or the next person deletes it as tautology.
 */
class EngineConfigTest {

    @Test
    void namesTheApplicationAndDefaultsToOneFrameInFlight() {
        EngineConfig config = EngineConfig.of("Fathom");

        assertEquals("Fathom", config.applicationName());
        assertTrue(config.validation(), "a development default loads validation layers");
        // Pinned deliberately. WindowedPresenter waits on a single fence, so one frame is what the runtime
        // actually does; raising this default without raising the runtime's sync objects would be a config that
        // promises depth of pipelining the engine does not have. If the presenter grows frames-in-flight, this
        // assertion is the reminder that the default may follow it -- not before.
        assertEquals(1, config.framesInFlight());
    }

    @Test
    void holdsNothingAboutTheSurface() {
        List<String> components = Arrays.stream(EngineConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("applicationName", "validation", "framesInFlight"), components,
                "EngineConfig is the pre-device half of the split; anything describing where or how big the "
                        + "frame is belongs to Target, which arrives with a pipeline");
    }

    @Test
    void refusesAnApplicationWithoutAName() {
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig(null, true, 1));
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig("", true, 1));
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig("   ", true, 1));
    }

    @Test
    void refusesAFrameCountTheRuntimeCannotSize() {
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig("Fathom", true, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new EngineConfig("Fathom", true, EngineConfig.MAX_FRAMES_IN_FLIGHT + 1));
    }

    @Test
    void withersChangeOneThingAndKeepTheRest() {
        EngineConfig release = EngineConfig.of("Fathom").withoutValidation();
        assertFalse(release.validation());
        assertEquals("Fathom", release.applicationName());
        assertEquals(1, release.framesInFlight());

        EngineConfig deeper = release.withFramesInFlight(2);
        assertEquals(2, deeper.framesInFlight());
        assertFalse(deeper.validation(), "withFramesInFlight must not quietly restore a dropped default");
    }
}
