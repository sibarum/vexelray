package dev.vexelray.shadelet.codegen;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.StyleLayout;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles {@code @Style} records with the real {@code javac} and {@link StyleProcessor}, loads the generated
 * layouts, and holds them to what a hand-written {@link StyleLayout} and the float order say they must be.
 */
class StyleProcessorTest {

    private static final String GLOW = """
            package demo;
            import dev.vexelray.canvas.Color;
            import dev.vexelray.canvas.shadelet.PerInstance;
            import dev.vexelray.canvas.shadelet.Style;
            @Style
            public record GlowStyle(float radius, Color tint, @PerInstance float t) {}
            """;

    @Test
    void layoutMatchesTheHandWrittenOneAndTheWritersPutFloatsInItsOrder() throws Exception {
        ClassLoader loader = loader(compile(Map.of("demo.GlowStyle", GLOW)));
        Class<?> style = loader.loadClass("demo.GlowStyle");
        Class<?> generated = loader.loadClass("demo.GlowStyleLayout");

        StyleLayout expected = StyleLayout.of(
                StyleLayout.shared("radius", 1),
                StyleLayout.shared("tint", 4),
                StyleLayout.perInstance("t", 1));
        StyleLayout layout = (StyleLayout) generated.getField("LAYOUT").get(null);
        assertEquals(expected, layout);
        assertEquals(expected.hash(), layout.hash());
        assertEquals(5, layout.sharedFloats());
        assertEquals(1, layout.instanceFloats());

        Object value = style.getConstructor(float.class, Color.class, float.class)
                .newInstance(3f, new Color(0.1f, 0.2f, 0.3f, 0.4f), 0.75f);

        float[] shared = (float[]) generated.getMethod("shared", style).invoke(null, value);
        assertArrayEquals(new float[] {3f, 0.1f, 0.2f, 0.3f, 0.4f}, shared);

        float[] record = {-1f, -1f, -1f, -1f};
        generated.getMethod("instance", style, float[].class, int.class).invoke(null, value, record, 2);
        assertArrayEquals(new float[] {-1f, -1f, 0.75f, -1f}, record, "written at 'at' and nowhere else");

        Look.Use use = (Look.Use) generated.getMethod("use", String.class, style).invoke(null, "glow", value);
        assertEquals(Look.Use.of("glow", 3f, 0.1f, 0.2f, 0.3f, 0.4f), use);
    }

    @Test
    void lerpMixesFloatsLinearlyAndColoursPremultiplied() throws Exception {
        ClassLoader loader = loader(compile(Map.of("demo.GlowStyle", GLOW)));
        Class<?> style = loader.loadClass("demo.GlowStyle");
        Class<?> generated = loader.loadClass("demo.GlowStyleLayout");
        var make = style.getConstructor(float.class, Color.class, float.class);
        Object from = make.newInstance(2f, Color.TRANSPARENT, 0f);
        Object to = make.newInstance(6f, new Color(1f, 0.5f, 0f, 1f), 1f);
        var lerp = generated.getMethod("lerp", style, style, float.class);

        assertEquals(from, lerp.invoke(null, from, to, 0f), "at 0, the first value");
        assertEquals(to, lerp.invoke(null, from, to, 1f), "at 1, the second");
        Object half = lerp.invoke(null, from, to, 0.25f);
        assertEquals(3f, (float) style.getMethod("radius").invoke(half), 1e-6f);
        assertEquals(0.25f, (float) style.getMethod("t").invoke(half), 1e-6f);
        Color tint = (Color) style.getMethod("tint").invoke(half);
        // Premultiplied, transparent black has no say in the hue: a quarter of the way is the destination's colour at
        // a quarter of its alpha, not a quarter of the way from black.
        assertEquals(new Color(1f, 0.5f, 0f, 0.25f), tint);
    }

    @Test
    void anEmptyStyleHasAnEmptyLayout() throws Exception {
        ClassLoader loader = loader(compile(Map.of("demo.PlainStyle", """
                package demo;
                @dev.vexelray.canvas.shadelet.Style
                public record PlainStyle() {}
                """)));
        Class<?> style = loader.loadClass("demo.PlainStyle");
        Class<?> generated = loader.loadClass("demo.PlainStyleLayout");

        assertEquals(StyleLayout.NONE, generated.getField("LAYOUT").get(null));
        Object value = style.getConstructor().newInstance();
        assertArrayEquals(new float[0], (float[]) generated.getMethod("shared", style).invoke(null, value));
        float[] record = {7f};
        generated.getMethod("instance", style, float[].class, int.class).invoke(null, value, record, 0);
        assertArrayEquals(new float[] {7f}, record);
    }

    @Test
    void twoStylesInOneCompilationEachGetTheirOwnLayout() throws Exception {
        ClassLoader loader = loader(compile(Map.of(
                "demo.GlowStyle", GLOW,
                "demo.other.FillStyle", """
                        package demo.other;
                        import dev.vexelray.canvas.Color;
                        import dev.vexelray.canvas.shadelet.PerInstance;
                        import dev.vexelray.canvas.shadelet.Style;
                        @Style
                        public record FillStyle(@PerInstance Color color, @PerInstance float alpha) {}
                        """)));

        StyleLayout glow = (StyleLayout) loader.loadClass("demo.GlowStyleLayout").getField("LAYOUT").get(null);
        StyleLayout fill = (StyleLayout) loader.loadClass("demo.other.FillStyleLayout").getField("LAYOUT").get(null);
        assertEquals(3, glow.fields().size());
        assertEquals(StyleLayout.of(StyleLayout.perInstance("color", 4), StyleLayout.perInstance("alpha", 1)), fill);

        Class<?> style = loader.loadClass("demo.other.FillStyle");
        Class<?> generated = loader.loadClass("demo.other.FillStyleLayout");
        Object value = style.getConstructor(Color.class, float.class).newInstance(new Color(1f, 2f, 3f, 4f), 5f);
        assertArrayEquals(new float[0], (float[]) generated.getMethod("shared", style).invoke(null, value));
        float[] record = new float[6];
        generated.getMethod("instance", style, float[].class, int.class).invoke(null, value, record, 1);
        assertArrayEquals(new float[] {0f, 1f, 2f, 3f, 4f, 5f}, record);
    }

    @Test
    void aClassIsRefused() throws Exception {
        String errors = errors(Map.of("demo.NotRecord", """
                package demo;
                @dev.vexelray.canvas.shadelet.Style
                public class NotRecord {}
                """));
        assertTrue(errors.contains("@Style NotRecord is a class; a style is a record"), errors);
    }

    @Test
    void aNestedRecordIsRefused() throws Exception {
        String errors = errors(Map.of("demo.Outer", """
                package demo;
                public class Outer {
                    @dev.vexelray.canvas.shadelet.Style
                    public record InnerStyle(float a) {}
                }
                """));
        assertTrue(errors.contains("@Style InnerStyle is nested"), errors);
    }

    @Test
    void theUnnamedPackageIsRefused() throws Exception {
        String errors = errors(Map.of("LooseStyle", """
                @dev.vexelray.canvas.shadelet.Style
                public record LooseStyle(float a) {}
                """));
        assertTrue(errors.contains("@Style LooseStyle is in the unnamed package"), errors);
    }

    @Test
    void aComponentOtherThanFloatOrColorIsRefused() throws Exception {
        String errors = errors(Map.of("demo.BadStyle", """
                package demo;
                @dev.vexelray.canvas.shadelet.Style
                public record BadStyle(float ok, int count, String label) {}
                """));
        assertTrue(errors.contains("@Style BadStyle component 'count' is int; a style component is a float or a "
                + "dev.vexelray.canvas.Color"), errors);
    }

    /** One bad style is reported against itself; a good one in the same compilation still generates. */
    @Test
    void aBadStyleSkipsOnlyItself() throws Exception {
        Compilation c = compile(Map.of(
                "demo.GlowStyle", GLOW,
                "demo.BadStyle", """
                        package demo;
                        @dev.vexelray.canvas.shadelet.Style
                        public record BadStyle(double wide) {}
                        """));
        assertFalse(c.ok());
        assertTrue(c.errors().contains("component 'wide' is double"), c.errors());
        assertTrue(Files.exists(c.gen().resolve("demo/GlowStyleLayout.java")));
        assertFalse(Files.exists(c.gen().resolve("demo/BadStyleLayout.java")));
    }

    // --- helpers ----------------------------------------------------------------

    private record Compilation(boolean ok, Path out, Path gen, String errors) {
    }

    private static ClassLoader loader(Path out) throws IOException {
        return new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, StyleProcessorTest.class.getClassLoader());
    }

    /** Compiles expecting failure, and returns the errors javac reported. */
    private static String errors(Map<String, String> sources) throws IOException {
        Compilation c = compile(sources);
        assertFalse(c.ok(), "expected compilation to fail, but it succeeded");
        return c.errors();
    }

    /** Compiles {@code sources} with the real processor into fresh directories; a test that expects success checks. */
    private static Compilation compile(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a full JDK (not JRE) is required to run this test");

        Path srcDir = Files.createTempDirectory("shadelet-src");
        Path outDir = Files.createTempDirectory("shadelet-out");
        Path genDir = Files.createTempDirectory("shadelet-gen");

        List<Path> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = srcDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            sourceFiles.add(file);
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        String classpath = System.getProperty("java.class.path");
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(genDir.toFile()));
            List<String> options = List.of(
                    "-classpath", classpath,
                    "-processorpath", classpath,
                    "-processor", StyleProcessor.class.getName(),
                    "-Xlint:all", "-Werror");
            ok = compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(sourceFiles))
                    .call();
        }
        String errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING
                        || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        return new Compilation(ok, outDir, genDir, errors);
    }

    private static ClassLoader loader(Compilation c) throws IOException {
        assertTrue(c.ok(), () -> "compilation with the processor failed:\n" + c.errors());
        return loader(c.out());
    }
}
