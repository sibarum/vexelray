package dev.vexelray.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The table a module's generated {@code TraceSites} holds, and the merge of every module's. */
class SitesTest {

    private static final Site SEEK = new Site(0x1234L, "dev.example.Player", "seek", "(J)V", "Player.java", 42,
            "seek", false, List.of(new Site.Arg("position", "long")));
    private static final Site OPEN = new Site(0x5EEDL, "dev.example.Player", "open", "(Ljava/lang/String;Z)I",
            "Player.java", 50, "open", true, List.of(new Site.Arg("title", "text"), new Site.Arg("fast", "boolean")));

    @Test
    void aTableReadsBackAsWritten() {
        assertEquals(List.of(SEEK, OPEN), Site.parse(Site.table(List.of(SEEK, OPEN))));
        assertEquals("dev.example.Player.seek(Player.java:42)", SEEK.where());
        assertEquals("trace.seek", SEEK.kind());
        assertEquals("0000000000001234", SEEK.key());
    }

    @Test
    void twoModulesWithOneIdAreRefusedNamingBoth() {
        Site clash = new Site(0x1234L, "dev.other.Thing", "go", "()V", "Thing.java", 7, "go", false, List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Sites.merge(List.of(List.of(SEEK), List.of(clash))));
        assertTrue(e.getMessage().contains("Player.seek(Player.java:42)") && e.getMessage().contains("Thing.go("),
                e.getMessage());
        assertEquals(1, Sites.merge(List.of(List.of(SEEK), List.of(SEEK))).size(),
                "one module on the class path twice is one site, not a clash");
    }

    @Test
    void aRawValueReadsBackByItsType() {
        assertEquals("true", new Site.Arg("b", "boolean").format(1));
        assertEquals("2.5", new Site.Arg("f", "float").format(Float.floatToRawIntBits(2.5f)));
        assertEquals("null", new Site.Arg("s", "string").format(-1));
        assertEquals("NANOSECONDS", new Site.Arg("u", "enum:java.util.concurrent.TimeUnit").format(0));
        assertEquals("Gone#3", new Site.Arg("g", "enum:dev.nowhere.Gone").format(3),
                "a class this reader does not have still reads as its ordinal");
    }
}
