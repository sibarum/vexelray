package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.diag.Diagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.clamp;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.xyz;
import static dev.vexelray.ir.Ir.y;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CompositionTest {

    /** {@code d + weight}: bolder for a positive weight, the reach shrinking by as much. */
    static final Shadelet.Modifier WEIGHT = new Shadelet.Modifier() {
        @Override
        public String name() {
            return "weight";
        }

        @Override
        public StyleLayout style() {
            return StyleLayout.of(StyleLayout.shared("weight", 1));
        }

        @Override
        public Expr modify(Expr d, Fragment fragment, StyleView style) {
            Expr weight = style.scalar("weight");
            return v2(add(x(d), weight), sub(y(d), weight));
        }
    };

    /** The band {@code |d| < width/2} in a colour of its own, the width per instance so it can animate. */
    static final Shadelet.Painter OUTLINE = new Shadelet.Painter() {
        @Override
        public String name() {
            return "outline";
        }

        @Override
        public StyleLayout style() {
            return StyleLayout.of(StyleLayout.shared("colour", 4), StyleLayout.perInstance("width", 1));
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr colour = style.vector("colour");
            Expr half = mul(style.scalar("width"), f(0.5));
            Expr band = sub(half, dev.vexelray.ir.Ir.abs(x(c.distance())));
            Expr cov = clamp(add(band, f(0.5)), f(0.0), f(1.0));
            return Region.of(new Statement.Assign(layer, Layers.premultiplied(xyz(colour), mul(w(colour), cov))));
        }
    };

    @AfterEach
    void forget() {
        Diagnostics.reset();
    }

    private static NativeTools tools() {
        NativeTools tools = new NativeTools();
        assumeTrue(tools.isAvailable(), "native SPIR-V toolchain not bundled for this platform");
        return tools;
    }

    private static void assertValid(NativeTools tools, CoreModule module, String what) {
        byte[] spirv = new CoreToSpirv().lower(module).toByteArray();
        NativeTools.ValidationResult result = tools.validate(spirv);
        assertTrue(result.valid(), () -> "spirv-val rejected " + what + ":\n" + result.output());
    }

    @Test
    void theCanvasShaderValidates() {
        NativeTools tools = tools();
        assertValid(tools, new CanvasShader.Vertex().module(), "the canvas vertex stage");
        assertValid(tools, new CanvasShader.Fragment().module(), "the canvas fragment stage");
    }

    @Test
    void aCompositionWithStyledModifiersAndPaintersValidates() {
        NativeTools tools = tools();
        Composition composition = CoreShadelets.composition().with(List.of(WEIGHT, OUTLINE));
        assertValid(tools, CanvasShader.fragmentModule(composition), "a composition with a weight and an outline");
    }

    @Test
    void aLookIsWrittenAsTheTableLayoutSays() {
        Composition composition = CoreShadelets.composition().with(List.of(WEIGHT, OUTLINE));
        Manifest manifest = composition.manifest();
        LookTable table = CoreLooks.table(manifest);
        int before = table.floats().length;
        Look look = new Look(Look.Use.of("box"), List.of(Look.Use.of("weight", 2f)),
                List.of(Look.Use.of("outline", 1f, 0.5f, 0f, 1f), Look.Use.of("fill")));
        int at = table.look(look);
        float[] t = table.floats();

        // Styles first: the weight's one float, then the outline's colour.
        assertEquals(2f, t[before]);
        assertArrayEquals(new float[] {1f, 0.5f, 0f, 1f}, java.util.Arrays.copyOfRange(t, before + 1, before + 5));
        int box = manifest.find("box").orElseThrow().id();
        int weight = manifest.find("weight").orElseThrow().id();
        int outline = manifest.find("outline").orElseThrow().id();
        int fill = manifest.find("fill").orElseThrow().id();
        assertArrayEquals(new float[] {
                box, before, 1,                     // the box's style is empty, at where the styles start;
                                                    // instance fields start after the record's look
                1, weight, before, 1,               // one modifier
                2, outline, before + 1, 1,          // two painters: the outline's width is the record's float 1
                fill, before + 5, 2},               // the fill's (none) would follow it
                java.util.Arrays.copyOfRange(t, at, t.length));
        assertEquals(at, table.look(look), "a look seen twice is written once");
    }

    @Test
    void thePrimitivesRecordsAreTheSameInEveryTable() {
        Manifest withMore = CoreShadelets.composition().with(List.of(WEIGHT, OUTLINE)).manifest();
        LookTable table = CoreLooks.table(withMore);
        float[] t = table.floats();
        for (CoreLooks.Primitive primitive : CoreLooks.Primitive.values()) {
            int record = CoreLooks.record(primitive);
            int look = (int) t[record];
            assertEquals(primitive.look().source().shadelet(),
                    nameOf(withMore, Manifest.Role.SOURCE, (int) t[look]), primitive.name());
        }
    }

    @Test
    void aCompositionThatDoesNotStartWithTheCoreSetIsRefusedATable() {
        List<Shadelet> backwards = new java.util.ArrayList<>(CoreShadelets.ALL);
        java.util.Collections.reverse(backwards);
        Manifest reordered = Composition.of(backwards).manifest();
        // The same shadelets, in another order: the looks are as long, but name other ids. Records still line up,
        // so what is refused is a core set that is missing or different in size, not merely reordered.
        assertEquals(CoreLooks.table(reordered).floats().length, CoreLooks.coreTables().length);
        Manifest partial = Composition.of(List.of(CoreShadelets.BOX, CoreShadelets.FILL)).manifest();
        assertThrows(IllegalArgumentException.class, () -> CoreLooks.table(partial));
    }

    @Test
    void anEffectTheShaderLacksIsDroppedAndSaidOnce() {
        LookTable table = CoreLooks.table(CoreShadelets.composition().manifest());
        Look withSparkle = Look.of(Look.Use.of("box"), Look.Use.of("sparkle", 4f), Look.Use.of("fill"));
        table.look(withSparkle);
        table.look(Look.of(Look.Use.of("box"), Look.Use.of("sparkle", 8f), Look.Use.of("fill")));
        List<String> said = Diagnostics.recorded();
        assertEquals(1, said.stream().filter(s -> s.contains("sparkle")).count(), () -> said.toString());
    }

    @Test
    void aLookTheShaderCannotDrawIsRefused() {
        LookTable table = CoreLooks.table(CoreShadelets.composition().with(List.of(OUTLINE)).manifest());
        assertThrows(IllegalArgumentException.class,
                () -> table.look(Look.of(Look.Use.of("sparkles"), Look.Use.of("fill"))), "an unknown source");
        assertThrows(IllegalArgumentException.class,
                () -> table.look(Look.of(Look.Use.of("box"), Look.Use.of("outline", 1f))), "a short style");
        Look.Use[] nine = new Look.Use[9];
        java.util.Arrays.fill(nine, Look.Use.of("fill"));
        assertThrows(IllegalArgumentException.class, () -> table.look(Look.of(Look.Use.of("box"), nine)),
                "nine painters");
    }

    @Test
    void aCompositionNamesEachShadeletOnce() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreShadelets.composition().with(List.of(CoreShadelets.FILL)));
        assertThrows(IllegalArgumentException.class, () -> Composition.of(List.of(CoreShadelets.BOX)));
    }

    @Test
    void theCoreShaderIsPrecompiledUnderTheHashOfWhatItHolds() {
        CanvasShader.Fragment core = new CanvasShader.Fragment();
        assertTrue(dev.supirvast.vastir.shader.Shaders.isPrecompiled(core.name(), core.getClass().getClassLoader()),
                () -> core.name() + " was not pre-compiled by the build");

        CanvasShader.Composed more = new CanvasShader.Composed(
                CoreShadelets.composition().with(List.of(WEIGHT, OUTLINE))) { };
        assertTrue(!more.name().equals(core.name()), "another composition has another name");
        // The same shadelets with a changed layout: what an application built against an older framework holds.
        Shadelet.Modifier widerWeight = new Shadelet.Modifier() {
            @Override
            public String name() {
                return "weight";
            }

            @Override
            public StyleLayout style() {
                return StyleLayout.of(StyleLayout.shared("weight", 2));
            }

            @Override
            public Expr modify(Expr d, Fragment fragment, StyleView style) {
                return d;
            }
        };
        CanvasShader.Composed changed = new CanvasShader.Composed(
                CoreShadelets.composition().with(List.of(widerWeight, OUTLINE))) { };
        assertTrue(!changed.name().equals(more.name()), "a changed layout is another name");
    }

    @Test
    void theManifestReadsBackAsWritten() {
        Manifest manifest = CoreShadelets.composition().with(List.of(WEIGHT, OUTLINE)).manifest();
        assertEquals(manifest, Manifest.parse(manifest.toText()));
    }

    private static String nameOf(Manifest manifest, Manifest.Role role, int id) {
        return manifest.entries().stream().filter(e -> e.role() == role && e.id() == id)
                .findFirst().orElseThrow().name();
    }
}
