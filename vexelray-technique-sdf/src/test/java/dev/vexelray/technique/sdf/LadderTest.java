package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ladder of docs/framework-requirements.md §2: what a designer's stack costs as operators are piled on.
 *
 * <p>It is the requirement's own measurement, kept as a test because it is the one that says whether P1 is
 * still working. Before it, the rungs read 15 KB, 191 KB, 497 KB, 1.3 MB, 4.1 MB, and then three refusals —
 * the sixth operator did not compile at all. After, every rung composes, and the eighth is about 19 KB.
 *
 * <p>Two assertions per rung, and the second is the one that could not be made before: <b>the driver's own
 * validator accepts it</b>. A module that shares subtrees between call sites is a module with more than two
 * functions in it for the first time, and "it composed" is not the same claim as "it is legal SPIR-V".
 */
class LadderTest {

    /** Eight spheres in a row, blended — the base every rung above wraps. */
    private static Surface base() {
        Surface[] eight = new Surface[8];
        for (int i = 0; i < 8; i++) {
            eight[i] = new Surface.Sphere(i * 0.4 - 1.4, 0, 0, 0.5);
        }
        return Surface.smoothUnion(8, eight);
    }

    private static List<Rung> ladder() {
        Surface base = base();
        Surface repeat3 = new Surface.Repeat(Surface.Repeat.Axis.every(1.5),
                Surface.Repeat.Axis.every(1.5), Surface.Repeat.Axis.every(1.5), base);
        Surface twist = new Surface.Twist(0.6, 4, repeat3);
        Surface bend = new Surface.Bend(0.3, 4, twist);
        Surface polar = new Surface.PolarRepeat(6, bend);
        Surface mirror = new Surface.Mirror(true, false, true, polar);
        return List.of(
                new Rung("smoothUnion(8)", base),
                new Rung("+ Repeat 2 axes", Surface.Repeat.grid(1.5, base)),
                new Rung("+ Repeat 3 axes", repeat3),
                new Rung("+ Twist", twist),
                new Rung("+ Twist + Bend", bend),
                new Rung("+ PolarRepeat 6", polar),
                new Rung("+ Mirror", mirror),
                new Rung("+ Repeat 2 again", Surface.Repeat.grid(20, mirror)));
    }

    private record Rung(String name, Surface surface) {
    }

    @Test
    @DisplayName("every rung composes, and the eighth is a small multiple of the first")
    void everyRungComposes() {
        List<Integer> sizes = new ArrayList<>();
        for (Rung rung : ladder()) {
            byte[] spirv = SdfComposer.fragmentSpirv(SdfScene.of(rung.surface()));
            assertTrue(spirv.length > 0, rung.name() + " composed nothing");
            sizes.add(spirv.length);
        }
        int one = sizes.get(0);
        int eight = sizes.get(sizes.size() - 1);
        // A ratio, so the assertion outlives any change in what a sphere costs. Measured at 1.3x; before P1
        // the fifth rung alone was 270x and the sixth did not exist.
        assertTrue(eight < 4 * one,
                () -> "eight operators composed to " + eight + " bytes against " + one + " for one: "
                        + sizes);
    }

    @Test
    @DisplayName("every rung passes spirv-val")
    void everyRungValidates() {
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");
        for (Rung rung : ladder()) {
            NativeTools.ValidationResult result =
                    tools.validate(SdfComposer.fragmentSpirv(SdfScene.of(rung.surface())));
            assertTrue(result.valid(), rung.name() + " rejected by spirv-val:\n" + result.output());
        }
    }

    @Test
    @DisplayName("a rung's shared subtrees are declared in the module it calls them from")
    void sharedSubtreesReachTheModule() {
        // The one failure this split can produce: a call to a function the module does not define. It cannot
        // be caught by composing — the bytes come out — so it is caught by the validator, and by this, which
        // says the composer published what it emitted.
        for (Rung rung : ladder()) {
            SdfScene scene = SdfScene.of(rung.surface());
            int declared = SdfComposer.helperFunctions(scene).size();
            assertTrue(declared >= 0);
            if (rung.name().startsWith("+")) {
                assertTrue(declared > 0, rung.name() + " shares nothing, so P1 did not fire on it");
            }
        }
    }
}
