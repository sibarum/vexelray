package dev.vexelray.os;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void exitIsAlwaysTheLastEntry() {
        BackgroundRun.addMenuItem("Something", () -> { });
        java.util.List<BackgroundRun.MenuItem> menu = BackgroundRun.menu("Demo");
        assertEquals(BackgroundRun.EXIT_LABEL, menu.get(menu.size() - 1).label());
        assertEquals("Something", menu.get(menu.size() - 2).label());
    }

    @Test
    void stoppingSaysSoAndLeavesTheSessionANote(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.nio.file.Path note = BackgroundRun.stoppedByUser(dir, "abc-123", "Vex",
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
        String line = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(line.contains(BackgroundRun.STOPPED_BY_USER) && line.contains("intentionally shut down by the user")
                && line.contains("Vex"), line);
        assertEquals(dir.resolve("abc-123"), note);
        assertTrue(java.nio.file.Files.readString(note).startsWith(BackgroundRun.STOPPED_BY_USER));
    }

    @Test
    void aSessionIdCannotNameAPathElsewhere(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path note = BackgroundRun.stoppedByUser(dir, "../../evil", "x",
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(dir, note.getParent());
    }

    @Test
    void askingForTheBackgroundOutranksEverything() {
        assertTrue(BackgroundRun.decide(Map.of(), null, "true"));
        assertTrue(BackgroundRun.decide(Map.of("VEXELRAY_FOREGROUND", "1"), "true", "true"));
    }
}
