package dev.vexelray.trace.weave;

import dev.vexelray.trace.Site;
import dev.vexelray.trace.TraceSites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.probe.Probe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weave, in a JVM with the probe off: what it finds and tables, and that a woven method behaves exactly as it
 * did, writing nothing. {@link TracedRunTest} is the same fixtures with the probe on.
 */
class WeaverTest {

    @Test
    void eachSiteIsFoundAndTabled(@TempDir Path dir) throws Exception {
        Weaver.Result r = Woven.weave(Woven.copy(dir, Woven.PLAYER, Woven.MODE));
        assertEquals(3, r.woven());
        Map<String, Weaver.Found> byMethod = r.sites().stream()
                .collect(Collectors.toMap(Weaver.Found::method, Function.identity()));

        Weaver.Found seek = byMethod.get("seek");
        assertEquals(Weaver.hash(Woven.PLAYER + ".seek(J)J"), seek.id(), "hashed, as serialVersionUID is");
        assertEquals("Player.java", seek.file());
        assertTrue(seek.line() > 0);
        assertEquals(0x5EEDL, byMethod.get("mode").id(), "pinned");
        assertEquals("mode", byMethod.get("mode").label(), "no label: the method's name");

        // The generated table, through the interface ServiceLoader finds it by, reads back as the weaver saw it.
        assertEquals(List.of(r.sitesClass()), Files.readAllLines(dir.resolve(Weaver.SERVICE)));
        ClassLoader woven = Woven.loader(dir);
        TraceSites table = (TraceSites) woven.loadClass(r.sitesClass()).getDeclaredConstructor().newInstance();
        Map<String, Site> sites = Site.parse(table.table()).stream()
                .collect(Collectors.toMap(Site::method, Function.identity()));
        assertEquals(List.of(new Site.Arg("mode", "enum:" + Woven.MODE), new Site.Arg("name", "string"),
                new Site.Arg("rate", "double")), sites.get("mode").args());
        assertEquals(List.of(new Site.Arg("title", "text")), sites.get("open").args());
        assertTrue(sites.get("open").timed());
        assertEquals(Woven.PLAYER + ".seek(Player.java:" + seek.line() + ")", sites.get("seek").where());
    }

    @Test
    void aWovenMethodWithTheProbeOffDoesWhatItDidAndWritesNothing(@TempDir Path dir) throws Exception {
        assertFalse(Probe.ON, "this run is the untraced one");
        Woven.weave(Woven.copy(dir, Woven.PLAYER, Woven.MODE));
        Class<?> player = Woven.loader(dir).loadClass(Woven.PLAYER);
        Object p = player.getDeclaredConstructor().newInstance();
        Class<?> mode = player.getClassLoader().loadClass(Woven.MODE);

        assertEquals(42L, player.getMethod("seek", long.class).invoke(p, 42L));
        assertEquals("PAUSE hi 1.5", player.getMethod("mode", mode, String.class, double.class)
                .invoke(null, mode.getEnumConstants()[1], "hi", 1.5));
        Method open = player.getMethod("open", String.class);
        assertEquals(5, open.invoke(p, "title"));
        assertEquals(-1, open.invoke(p, ""), "every return of a timed site still returns its own value");
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> open.invoke(p, "boom"));
        assertTrue(thrown.getCause() instanceof IllegalStateException, "and a throw is still the method's own");
        assertEquals(8L, player.getMethod("untraced", long.class).invoke(p, 7L));
    }

    @Test
    void weavingAgainChangesNothing(@TempDir Path dir) throws Exception {
        Woven.copy(dir, Woven.PLAYER, Woven.MODE);
        Weaver.Result first = Woven.weave(dir);
        byte[] once = Files.readAllBytes(dir.resolve(Woven.PLAYER.replace('.', '/') + ".class"));

        // An incremental build: javac left the woven class alone, and the weaver runs over it again.
        Weaver.Result second = Woven.weave(dir);
        assertEquals(0, second.woven(), "nothing woven twice");
        assertEquals(first.sites().size(), second.sites().size(), "but every site is still in the table");
        assertArrayEquals(once, Files.readAllBytes(dir.resolve(Woven.PLAYER.replace('.', '/') + ".class")));
        assertEquals(List.of(first.sitesClass()), Files.readAllLines(dir.resolve(Weaver.SERVICE)),
                "and the service file lists the table once");
    }
}
