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
        // Not a default any more but the only value: a frame is not redrawn while the one before it is still
        // being drawn (EngineConfig.MAX_FRAMES_IN_FLIGHT says why).
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
    void refusesEveryFrameCountButOne() {
        assertEquals(1, EngineConfig.MAX_FRAMES_IN_FLIGHT);
        assertThrows(IllegalArgumentException.class, () -> new EngineConfig("Fathom", true, 0));
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class,
                () -> new EngineConfig("Fathom", true, 2));
        assertTrue(two.getMessage().contains("not redrawn while the one before it is still being drawn"),
                () -> "a refused frame count should say why, not just what: " + two.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EngineConfig.of("Fathom").withFramesInFlight(3));
    }

    @Test
    void withersChangeOneThingAndKeepTheRest() {
        EngineConfig release = EngineConfig.of("Fathom").withoutValidation();
        assertFalse(release.validation());
        assertEquals("Fathom", release.applicationName());
        assertEquals(1, release.framesInFlight());

        EngineConfig same = release.withFramesInFlight(1);
        assertEquals(1, same.framesInFlight());
        assertFalse(same.validation(), "withFramesInFlight must not quietly restore a dropped default");
    }
}
