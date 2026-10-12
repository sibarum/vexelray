package dev.vexelray.shadelets;

import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EffectsCompositionTest {

    static final Composition WITH_EFFECTS = CoreShadelets.composition().with(Effects.ALL);

    @Test
    void theCoreSetWithEveryEffectValidates() {
        NativeTools tools = new NativeTools();
        assumeTrue(tools.isAvailable(), "native SPIR-V toolchain not bundled for this platform");
        byte[] spirv = new CoreToSpirv().lower(CanvasShader.fragmentModule(WITH_EFFECTS)).toByteArray();
        NativeTools.ValidationResult result = tools.validate(spirv);
        assertTrue(result.valid(), () -> "spirv-val rejected the effects composition:\n" + result.output());
    }

    @Test
    void aLookKnowsHowFarItReachesAndWhatItCarries() {
        LookTable table = new LookTable(WITH_EFFECTS);
        Look outlined = new Look(Look.Use.of("box"),
                List.of(WeightStyleLayout.use("weight", new WeightStyle(2f))),
                List.of(OutlineStyleLayout.use("outline", new OutlineStyle(Color.rgb(1f, 0f, 0f), 6f)),
                        Look.Use.of("fill")));
        assertEquals(5f, table.reach(outlined), "the outline's half width of 3 past the edge the weight moved out by 2");
        assertEquals(0, table.instanceFloats(outlined));

        Look morphing = Look.of(Look.Use.of("boxMorph"), Look.Use.of("fill"));
        assertEquals(4, table.instanceFloats(morphing), "the second box and t, all per instance");
    }

    @Test
    void aTableForTheEffectsShaderStillHoldsTheKinds() {
        LookTable table = CoreLooks.table(WITH_EFFECTS.manifest());
        assertEquals(CoreLooks.coreTables().length, table.floats().length);
    }
}
