package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.windows.sys.User32;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows always has an answer to "reduce motion": "Animation effects", read through
 * {@code SPI_GETCLIENTAREAANIMATION}. Which answer depends on the machine, so this holds the query down rather than
 * the setting: it answers, and it answers the opposite of the switch it reads.
 */
class ReducedMotionTest {

    @Test
    void windowsAlwaysSaysAndSaysTheOppositeOfAnimationEffects() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        Optional<Boolean> reduced = NativePlatform.current().reducedMotion();
        assertTrue(reduced.isPresent(), "SystemParametersInfoW is on every Windows this runs on");
        assertEquals(!User32.clientAreaAnimation(), reduced.get());
    }
}
