package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Reading the arguments back from the UTF-16 command line. The splitting is the real {@code CommandLineToArgvW},
 * handed a line; the native-image-only path is the same splitting on {@code GetCommandLineW}'s line.
 */
class CommandLineTest {

    @Test
    void charactersOutsideTheAnsiPageComeThroughWhole() {
        String line = "vexplore.exe \"C:\\x\\a\u2019b \u7aaf.txt\" --automation=0";
        assertArrayEquals(new String[] {"vexplore.exe", "C:\\x\\a\u2019b \u7aaf.txt", "--automation=0"},
                CommandLine.split(line));
    }

    @Test
    void quotingIsTheCRuntimesQuoting() {
        // What ProcessBuilder writes for an argument with a space, and a backslash run before a quote.
        assertArrayEquals(new String[] {"p.exe", "a b", "c\\\"d", "e\\f"},
                CommandLine.split("p.exe \"a b\" c\\\\\\\"d e\\f"));
    }

    @Test
    void theRereadIsMatchedWithOrWithoutBlanks() {
        String[] reread = {"a\u2019b.txt", "", "--x"};
        assertArrayEquals(reread, CommandLine.match(new String[] {"a?b.txt", "", "--x"}, reread));
        assertArrayEquals(new String[] {"a\u2019b.txt", "--x"}, CommandLine.match(new String[] {"a?b.txt", "--x"}, reread));
    }

    @Test
    void aRereadOfAnotherCountIsNotTheseArguments() {
        String[] given = {"one"};
        assertSame(given, CommandLine.match(given, new String[] {"one", "two"}));
    }

    @Test
    void underTheJvmThePlatformReturnsTheArgumentsAsGiven() {
        String[] given = {"C:\\x\\a.txt"};
        assertSame(given, NativePlatform.current().arguments(given));
    }
}
