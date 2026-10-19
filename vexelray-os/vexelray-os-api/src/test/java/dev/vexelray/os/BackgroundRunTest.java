package dev.vexelray.os;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who decides a background run: the environment an agent's shell hands down, and only the person overrides it. */
class BackgroundRunTest {

    @Test
    void aPersonsShellIsAForegroundRun() {
        assertFalse(BackgroundRun.decide(Map.of(), null, null));
    }

    @Test
    void anAgentsShellIsABackgroundRun() {
        assertTrue(BackgroundRun.decide(Map.of("CLAUDECODE", "1"), null, null));
        assertTrue(BackgroundRun.decide(Map.of("AI_AGENT", "claude-code"), null, null));
    }

    @Test
    void aClearedVariableIsNoAgent() {
        assertFalse(BackgroundRun.decide(Map.of("CLAUDECODE", ""), null, null));
        assertFalse(BackgroundRun.decide(Map.of("CLAUDECODE", "0"), null, null));
    }

    @Test
    void theForegroundOverrideWins() {
        assertFalse(BackgroundRun.decide(Map.of("CLAUDECODE", "1", "VEXELRAY_FOREGROUND", "1"), null, null));
        assertFalse(BackgroundRun.decide(Map.of("AI_AGENT", "x"), "true", null));
        assertTrue(BackgroundRun.decide(Map.of("CLAUDECODE", "1"), "false", null));
    }

    @Test
    void askingForTheBackgroundOutranksEverything() {
        assertTrue(BackgroundRun.decide(Map.of(), null, "true"));
        assertTrue(BackgroundRun.decide(Map.of("VEXELRAY_FOREGROUND", "1"), "true", "true"));
    }
}
