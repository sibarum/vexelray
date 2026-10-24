package dev.vexelray.trace.weave;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fixtures copied out of the test classes, woven in a directory of their own, and loaded from there: a test weaves
 * exactly the classes it names, and runs the woven copies, never the originals beside them.
 */
final class Woven {

    private Woven() {
    }

    /** Copy the classes named (binary names, so none of them is loaded here) into {@code dir}. */
    static Path copy(Path dir, String... classes) throws IOException {
        for (String name : classes) {
            String resource = name.replace('.', '/') + ".class";
            Path out = dir.resolve(resource);
            Files.createDirectories(out.getParent());
            try (InputStream in = Woven.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("no test class " + name);
                }
                Files.write(out, in.readAllBytes());
            }
        }
        return dir;
    }

    /** Weave {@code dir} as the module {@code fixtures}, seeing it and the test class path. */
    static Weaver.Result weave(Path dir) throws IOException, Weaver.WeaveException {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                Woven.class.getClassLoader())) {
            return Weaver.weave(dir, "fixtures", loader);
        }
    }

    /**
     * A loader that takes the fixtures' packages from {@code dir} first, so the woven copies are the ones run, and
     * everything else (the trace runtime, the probe) from the test class path, so there is one of each.
     */
    static ClassLoader loader(Path dir) throws IOException {
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, Woven.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("dev.vexelray.trace.weave.fixture.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
        };
    }

    static final String PLAYER = "dev.vexelray.trace.weave.fixture.Player";
    static final String MODE = "dev.vexelray.trace.weave.fixture.Player$Mode";
}
