package dev.vexelray.os.windows;

import dev.supirvast.ffi.NativeException;
import dev.vexelray.os.windows.sys.Kernel32;
import dev.vexelray.os.windows.sys.Shell32;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The process's arguments read back from its UTF-16 command line: {@code GetCommandLineW}, split by
 * {@code CommandLineToArgvW}, which splits by the same rules the C runtime does. See
 * {@link WindowsPlatform#arguments} for why.
 */
final class CommandLine {

    private CommandLine() {
    }

    /**
     * {@code given} re-read from the command line in a native image, matched as {@link WindowsPlatform#arguments}
     * says; {@code given} itself under the JVM, whose command line also carries the launcher's options and class.
     */
    static String[] arguments(String[] given) {
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return given;
        }
        try {
            String[] all = split(Kernel32.getCommandLineW());
            return all == null || all.length == 0 ? given : match(given, Arrays.copyOfRange(all, 1, all.length));
        } catch (NativeException e) {
            return given;   // reading the arguments again must never be why an application does not start
        }
    }

    /**
     * {@code reread} if it is {@code given}'s arguments, with or without the blank ones {@code given} may have had
     * dropped; otherwise {@code given}. Counting is the whole test: the re-read differs only in the characters the
     * ANSI decoding lost.
     */
    static String[] match(String[] given, String[] reread) {
        if (reread.length == given.length) {
            return reread;
        }
        String[] nonBlank = Arrays.stream(reread).filter(s -> !s.isBlank()).toArray(String[]::new);
        return nonBlank.length == given.length ? nonBlank : given;
    }

    /** {@code commandLine} split as Windows splits a process's command line, program first; null if it cannot. */
    static String[] split(String commandLine) {
        try (Arena a = Arena.ofConfined()) {
            return split(a.allocateFrom(commandLine, StandardCharsets.UTF_16LE));
        }
    }

    private static String[] split(MemorySegment line) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment argc = a.allocate(JAVA_INT);
            MemorySegment argv = Shell32.commandLineToArgvW(line, argc);
            if (argv.equals(MemorySegment.NULL)) {
                return null;
            }
            String[] out = strings(argv, argc.get(JAVA_INT, 0));
            Kernel32.localFree(argv);
            return out;
        }
    }

    /**
     * The {@code n} UTF-16 strings an argv array points at.
     *
     * <p>Each length comes from {@code lstrlenW} and the characters are copied in one piece, rather than through
     * {@code getString}, whose terminator scan is a loop of its own: native-image 25.0.3 crashed compiling that
     * scan inlined into this loop (a GraalError, an NPE in its loop analysis), in an application image though not
     * in MainFrame's.
     */
    private static String[] strings(MemorySegment argv, int n) {
        MemorySegment slots = argv.reinterpret(n * ADDRESS.byteSize());
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            MemorySegment s = slots.getAtIndex(ADDRESS, i);
            long bytes = 2L * Kernel32.lstrlenW(s);
            out[i] = new String(s.reinterpret(bytes).toArray(JAVA_CHAR_UNALIGNED));
        }
        return out;
    }
}
