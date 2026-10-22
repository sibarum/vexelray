package dev.vexelray.os.windows;

import dev.vexelray.os.windows.sys.Kernel32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standard error for a process that has none: the installed build is a GUI-subsystem program, which starts without
 * one, and a native image's crash report is written to whatever {@code GetStdHandle} returns.
 */
class StandardErrorRedirectTest {

    @Test
    void aProcessWithAStandardErrorKeepsIt(@TempDir Path dir) {
        Path file = dir.resolve("logs").resolve("t-stderr-1.log");
        assertFalse(new WindowsPlatform().redirectStandardError(file), "surefire gives this JVM one");
        assertFalse(Files.exists(file));
    }

    @Test
    void aProcessWithoutOneIsGivenTheFile(@TempDir Path dir) {
        MemorySegment real = Kernel32.getStdHandle(Kernel32.STD_ERROR_HANDLE);
        Path file = dir.resolve("logs").resolve("t-stderr-1.log");
        try {
            // What a GUI-subsystem process starts with.
            assertTrue(Kernel32.setStdHandle(Kernel32.STD_ERROR_HANDLE, MemorySegment.NULL));
            assertTrue(new WindowsPlatform().redirectStandardError(file));
            assertTrue(Files.exists(file), "created, directory and all");
            MemorySegment now = Kernel32.getStdHandle(Kernel32.STD_ERROR_HANDLE);
            assertNotEquals(MemorySegment.NULL, now);
            assertNotEquals(real, now);
        } finally {
            Kernel32.setStdHandle(Kernel32.STD_ERROR_HANDLE, real);
        }
    }
}
