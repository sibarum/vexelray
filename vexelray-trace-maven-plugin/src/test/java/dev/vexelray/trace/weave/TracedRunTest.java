package dev.vexelray.trace.weave;

import dev.vexelray.trace.Site;
import dev.vexelray.trace.Sites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.probe.Cause;
import sibarum.probe.CsvView;
import sibarum.probe.Probe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The woven fixtures in a JVM started recording the trace lane (the pom's {@code traced} execution): each call
 * writes its site's row, with its arguments, in its caller's cause, and the recording names its own sites.
 */
class TracedRunTest {

    private static final Path LOG = Path.of(System.getProperty("probe.out"));

    @Test
    void aTracedCallWritesItsSiteItsArgumentsAndItsCallersCause(@TempDir Path dir) throws Exception {
        assertTrue(Probe.ON, "this run records: -Dprobe=trace");
        Weaver.Result r = Woven.weave(Woven.copy(dir, Woven.PLAYER, Woven.MODE));
        ClassLoader woven = Woven.loader(dir);
        // Where the trace runtime finds the tables, as an application's own loader would be.
        Thread.currentThread().setContextClassLoader(woven);
        Class<?> player = woven.loadClass(Woven.PLAYER);
        Object p = player.getDeclaredConstructor().newInstance();
        Class<?> mode = woven.loadClass(Woven.MODE);
        Method open = player.getMethod("open", String.class);

        long cause = Cause.source("test.trace", Cause.Kind.COMMAND).next();
        Cause.within(cause, () -> {
            try {
                player.getMethod("seek", long.class).invoke(p, 42L);
                player.getMethod("mode", mode, String.class, double.class)
                        .invoke(null, mode.getEnumConstants()[1], "hello", 1.5);
                open.invoke(p, "title");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        assertThrows(InvocationTargetException.class, () -> open.invoke(p, "boom"));

        List<CsvView.Row> rows = CsvView.filter(CsvView.read(LOG), "trace", null, null, null);
        long seekId = r.sites().stream().filter(s -> s.method().equals("seek")).findFirst().orElseThrow().id();
        String seekKey = HexFormat.of().toHexDigits(seekId);

        // The recording names its sites, so it reads without the table beside it.
        CsvView.Row described = rows.stream().filter(row -> row.kind().equals("trace.site")
                && row.detail().startsWith(seekKey + " seek ")).findFirst().orElseThrow();
        assertTrue(described.detail().matches(".*" + Woven.PLAYER.replace(".", "\\.")
                + "\\.seek\\(Player\\.java:\\d+\\) position:long"), described.detail());
        assertEquals("", described.cause(), "what the build holds is not the first call's doing");

        CsvView.Row seek = only(rows, "trace.seek");
        assertEquals(seekKey + " 42", seek.detail());
        assertEquals(Cause.format(cause), seek.cause(), "in its caller's cause, which nobody passed it");

        CsvView.Row m = only(rows, "trace.mode");
        String[] f = m.detail().split(" ");
        assertEquals("0000000000005eed", f[0], "the pinned id");
        Site site = Sites.byId(0x5EEDL);
        assertEquals("PAUSE", site.args().get(0).format(Long.parseLong(f[1])), "an enum, read back by its ordinal");
        assertEquals("5 chars", site.args().get(1).format(Long.parseLong(f[2])), "a string, by its length");
        assertEquals("1.5", site.args().get(2).format(Long.parseLong(f[3])), "a double, by its bits");

        CsvView.Row text = rows.stream().filter(row -> row.kind().equals("trace.text")).findFirst().orElseThrow();
        assertTrue(text.detail().endsWith(" title"), "content = true: the string itself, on its own row");
        List<CsvView.Row> exits = rows.stream().filter(row -> row.kind().equals("trace.exit")).toList();
        assertEquals(2, exits.size(), exits.toString());
        assertTrue(!exits.get(0).detail().endsWith("threw") && exits.get(1).detail().endsWith(" threw"),
                "timed: a return, then a throw, each with its duration: " + exits);
        assertEquals("", exits.get(1).cause(), "the call outside any cause is in none");
    }

    private static CsvView.Row only(List<CsvView.Row> rows, String kind) {
        List<CsvView.Row> of = rows.stream().filter(r -> r.kind().equals(kind)).toList();
        assertEquals(1, of.size(), kind + ": " + of);
        return of.get(0);
    }
}
