package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.surface.NodeId;
import dev.vexelray.surface.PayloadTable;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 at the composer: a scene can be marched so that the shading model is told <b>what</b> it hit, and a
 * scene that does not ask is not charged for it.
 *
 * <p>Both halves matter. The channel is worth nothing if the module it produces is not legal SPIR-V
 * ({@link #theIdentityVariantValidates}), and the split is worth nothing if the display path drifts into
 * paying for it ({@link #theDisplayPathIsUntouched}).
 */
class IdentityCompositionTest {

    private static final Surface LEFT = new Surface.Sphere(-1.2, 1, 3, 0.8);
    private static final Surface RIGHT = new Surface.Sphere(1.2, 1, 3, 0.8);

    private static SdfScene scene() {
        return SdfScene.of(Surface.union(Surface.Plane.ground(), LEFT, RIGHT));
    }

    @Test
    @DisplayName("the identity variant composes, and its SPIR-V passes spirv-val")
    void theIdentityVariantValidates() {
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");

        byte[] spirv = SdfComposer.identityFragmentSpirv(scene());
        NativeTools.ValidationResult result = tools.validate(spirv);
        assertTrue(result.valid(), "the identity fragment was rejected:\n" + result.output());
    }

    @Test
    @DisplayName("the display path is untouched: same bytes, and no vec2 field in it")
    void theDisplayPathIsUntouched() {
        SdfScene scene = scene();
        assertArrayEquals(SdfComposer.fragmentSpirv(scene), SdfComposer.fragmentSpirv(scene()),
                "the ordinary path should still be deterministic");

        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-tools not bundled for this platform");
        String display = tools.disassemble(SdfComposer.fragmentSpirv(scene));
        assertFalse(display.contains("= OpFunction %v2float "),
                "the display path emitted an identity function it will never call");

        // And the variant that does carry one really does carry it, so the assertion above is about a
        // difference rather than about neither module having it.
        String identity = tools.disassemble(SdfComposer.identityFragmentSpirv(scene));
        assertTrue(identity.contains("= OpFunction %v2float "),
                "the identity variant should emit vec2 hit(vec3)");
    }

    @Test
    @DisplayName("the march still samples the field eight times, identity or not")
    void identityCostsOneCallNotNine() {
        // What the second lowering mode is for: the payload is read at the hit point, once, and the march
        // is the march. Were the channel threaded through the march instead, every one of those eight
        // samples would carry it — nine per pixel per step, for a number only the last one needs.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-tools not bundled for this platform");

        String identity = tools.disassemble(SdfComposer.identityFragmentSpirv(scene()));
        long distanceCalls = identity.lines()
                .filter(line -> line.contains("OpFunctionCall %float"))
                .count();
        long identityCalls = identity.lines()
                .filter(line -> line.contains("OpFunctionCall %v2float"))
                .count();

        assertEquals(8, distanceCalls,
                "the march should still take its eight samples through the float wrapper");
        assertEquals(2, identityCalls,
                "vec2 hit(vec3) should be called from exactly two places: once inside the wrapper the "
                        + "march goes through, and once at the hit point where the payload is read");
    }

    @Test
    @DisplayName("the table turns the channel's numbers back into nodes")
    void theTableResolvesTheChannel() {
        SdfScene scene = scene();
        PayloadTable table = SdfComposer.payloadTable(scene);

        assertEquals(3, table.size(), "a ground plane and two spheres own points; the union does not");
        assertEquals(List.of(table.nodes().get(0), table.nodes().get(1), table.nodes().get(2)),
                table.nodes());
        assertEquals(LEFT.id(), table.nodeAt(table.slotOf(LEFT.id())).orElseThrow());
        assertEquals(RIGHT.id(), table.nodeAt(table.slotOf(RIGHT.id())).orElseThrow());

        // A number from somewhere else names nothing, rather than naming the wrong shape.
        assertTrue(table.nodeAt(7).isEmpty());
        assertFalse(table.holds(NodeId.fresh()));
    }
}
