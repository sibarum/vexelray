package dev.vexelray.trace.weave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mistakes a build refuses (docs/trace.md, step 2): each is a build error naming the site, never a surprise in
 * a recording, and a refused weave writes nothing.
 */
class ChecksTest {

    private static final String BAD = "dev.vexelray.trace.weave.bad.BadSites$";

    private static String refused(Path dir, String fixture) throws Exception {
        Woven.copy(dir, BAD + fixture);
        Path file = dir.resolve((BAD + fixture).replace('.', '/') + ".class");
        byte[] before = Files.readAllBytes(file);
        Weaver.WeaveException e = assertThrows(Weaver.WeaveException.class, () -> Woven.weave(dir));
        assertArrayEquals(before, Files.readAllBytes(file), "a refused weave leaves the class as javac wrote it");
        assertFalse(Files.exists(dir.resolve(Weaver.SERVICE)), "and writes no table");
        return e.getMessage();
    }

    @Test
    void anObjectArgumentIsRefused(@TempDir Path dir) throws Exception {
        String m = refused(dir, "ObjectArgument");
        assertTrue(m.contains("BadSites$ObjectArgument.add(") && m.contains("'items'") && m.contains("java.util.List")
                && m.contains("primitives, enums and strings only"), m);
    }

    @Test
    void anArgumentThatIsNoParameterIsRefused(@TempDir Path dir) throws Exception {
        String m = refused(dir, "NoSuchParameter");
        assertTrue(m.contains("'nope'") && m.contains("[value]"), "it names the parameters there are: " + m);
    }

    @Test
    void moreThanFourArgumentsAreRefused(@TempDir Path dir) throws Exception {
        assertTrue(refused(dir, "TooMany").contains("at most " + Weaver.MAX_ARGS), "five");
    }

    @Test
    void twoSitesWithOneIdAreRefused(@TempDir Path dir) throws Exception {
        String m = refused(dir, "SharedId");
        assertTrue(m.contains("share the id 0000000000000007") && m.contains(".one(") && m.contains(".two("), m);
    }

    @Test
    void contentWithNoStringIsRefused(@TempDir Path dir) throws Exception {
        assertTrue(refused(dir, "ContentWithoutText").contains("content = true"));
    }
}
