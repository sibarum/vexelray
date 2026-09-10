package dev.vexelray.surface;

import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2, second half: the field says <b>what</b> it hit, not only how far away it is.
 *
 * <p>One channel, selected by the same comparison that selects the distance, carrying a slot that
 * {@link PayloadTable} turns back into a {@link NodeId}. Two things ride it — identity now (R6.1) and a
 * material when there is a vocabulary for one (R7) — and building the channel is the requirement; what
 * travels on it can arrive later.
 *
 * <p>The claim that makes it worth having is {@link #theNearerShapeOwnsThePoint}: at a point, the payload
 * names the shape a person would say is there. The claim that makes it affordable is
 * {@link #theDisplayPathPaysNothing}: without asking, the field is the one it always was.
 */
class PayloadTest {

    /** Two spheres side by side, unioned — the plan's own test case. */
    private static final Surface LEFT = new Surface.Sphere(-1, 0, 0, 0.8);
    private static final Surface RIGHT = new Surface.Sphere(1, 0, 0, 0.8);

    @Test
    @DisplayName("the nearer shape owns the point")
    void theNearerShapeOwnsThePoint() {
        Surface union = Surface.union(LEFT, RIGHT);
        PayloadTable table = PayloadTable.of(union);
        Field field = SurfaceCompiler.compileWithPayload(union);

        assertEquals(table.slotOf(LEFT.id()), (int) Eval.payloadAt(field, -1.9, 0, 0));
        assertEquals(table.slotOf(RIGHT.id()), (int) Eval.payloadAt(field, 1.9, 0, 0));

        // And back the other way, which is the direction a host actually reads it.
        assertEquals(LEFT.id(), table.nodeAt((int) Eval.payloadAt(field, -1.5, 0, 0)).orElseThrow());
        assertEquals(RIGHT.id(), table.nodeAt((int) Eval.payloadAt(field, 1.5, 0, 0)).orElseThrow());
    }

    @Test
    @DisplayName("an intersection reports the arm that decided the answer")
    void anIntersectionReportsTheDecidingArm() {
        Surface a = new Surface.Sphere(-0.4, 0, 0, 1);
        Surface b = new Surface.Sphere(0.4, 0, 0, 1);
        Surface overlap = Surface.intersection(a, b);
        PayloadTable table = PayloadTable.of(overlap);
        Field field = SurfaceCompiler.compileWithPayload(overlap);

        // An intersection is a max, so the arm it reports is the one whose boundary is <em>farther</em> —
        // the constraint that is binding. Standing off to the left, the far sphere is b, so b is the reason
        // the point is outside, and b is what the point belongs to.
        assertEquals(table.slotOf(b.id()), (int) Eval.payloadAt(field, -1.8, 0, 0));
        assertEquals(table.slotOf(a.id()), (int) Eval.payloadAt(field, 1.8, 0, 0));
    }

    @Test
    @DisplayName("a carve reports the shape being carved, not the tool")
    void aCarveReportsTheShapeNotTheTool() {
        Surface body = new Surface.Sphere(0, 0, 0, 1);
        Surface tool = new Surface.Sphere(1, 0, 0, 0.5);
        Surface carved = new Surface.Difference(body, tool);
        PayloadTable table = PayloadTable.of(carved);
        Field field = SurfaceCompiler.compileWithPayload(carved);

        assertEquals(table.slotOf(body.id()), (int) Eval.payloadAt(field, -0.5, 0, 0),
                "the solid far side belongs to the body");
    }

    @Test
    @DisplayName("a smooth blend chooses a shape rather than blending two names")
    void aSmoothBlendChoosesRatherThanBlends() {
        Surface blended = Surface.smoothUnion(4.0, LEFT, RIGHT);
        PayloadTable table = PayloadTable.of(blended);
        Field field = SurfaceCompiler.compileWithPayload(blended);

        Set<Integer> slots = Set.of(table.slotOf(LEFT.id()), table.slotOf(RIGHT.id()));
        for (double x = -2.5; x <= 2.5; x += 0.1) {
            double payload = Eval.payloadAt(field, x, 0.1, -0.05);
            assertEquals(payload, Math.rint(payload), 0.0,
                    "the payload at x=" + x + " is " + payload + ", which is between two names");
            assertTrue(slots.contains((int) payload), "at x=" + x + " the payload named nothing in the tree");
        }
    }

    @Test
    @DisplayName("the payload survives a twist over a repeat over a smooth union")
    void thePayloadSurvivesTheOperatorStack() {
        // The plan's test, and the one the operators could break: a repeat lowers its child eight times, a
        // twist deforms the domain, and neither is allowed to lose track of which shape is being reported.
        Surface a = new Surface.Sphere(-0.3, 0, 0, 0.3);
        Surface b = new Surface.Box(0.3, 0, 0, 0.2, 0.2, 0.2);
        Surface stack = new Surface.Twist(0.4, 3,
                Surface.Repeat.grid(2.0, Surface.smoothUnion(8.0, a, b)));
        PayloadTable table = PayloadTable.of(stack);
        Field field = SurfaceCompiler.compileWithPayload(stack);

        Set<Integer> slots = Set.of(table.slotOf(a.id()), table.slotOf(b.id()));
        Set<Integer> seen = new HashSet<>();
        for (double x = -2.4; x <= 2.4; x += 0.15) {
            for (double z = -2.4; z <= 2.4; z += 0.15) {
                int slot = (int) Eval.payloadAt(field, x, 0.05, z);
                assertTrue(slots.contains(slot),
                        "at (" + x + ", " + z + ") the payload was " + slot + ", which is in no cell");
                seen.add(slot);
            }
        }
        assertEquals(slots, seen, "both shapes should own points somewhere in the tiling");
    }

    @Test
    @DisplayName("every cell of a repeat reports the one node that was authored")
    void aRepeatReportsItsChild() {
        // One authored sphere, tiled: clicking any copy selects the sphere, because there is only one to
        // select. The 2^n cells share one function precisely because they are one node.
        Surface sphere = new Surface.Sphere(0, 0, 0, 0.4);
        Surface tiled = Surface.Repeat.grid(1.5, sphere);
        PayloadTable table = PayloadTable.of(tiled);
        Field field = SurfaceCompiler.compileWithPayload(tiled);

        assertEquals(1, table.size(), "one authored shape, one slot");
        for (double x = -3; x <= 3; x += 0.35) {
            assertEquals(table.slotOf(sphere.id()), (int) Eval.payloadAt(field, x, 0, 0.2));
        }
    }

    @Test
    @DisplayName("two separately authored copies of one shape report different nodes")
    void lookalikesAreDistinguished() {
        // Why the payload lowering shares by node rather than by shape. On the display path these two are
        // one function, because a distance field cannot tell them apart and does not need to. Here they must
        // be told apart, so they are two.
        Surface left = new Surface.Translate(-1.5, 0, 0, new Surface.Sphere(0, 0, 0, 0.6));
        Surface right = new Surface.Translate(1.5, 0, 0, new Surface.Sphere(0, 0, 0, 0.6));
        Surface both = Surface.union(left, right);
        PayloadTable table = PayloadTable.of(both);
        Field field = SurfaceCompiler.compileWithPayload(both);

        int leftSlot = (int) Eval.payloadAt(field, -2.5, 0, 0);
        int rightSlot = (int) Eval.payloadAt(field, 2.5, 0, 0);
        assertNotEquals(leftSlot, rightSlot, "two copies reported one node, so a click cannot tell them apart");
        assertNotEquals(table.nodeAt(leftSlot).orElseThrow(), table.nodeAt(rightSlot).orElseThrow());
    }

    @Test
    @DisplayName("the display path pays nothing: the same distance, and no channel at all")
    void theDisplayPathPaysNothing() {
        Surface scene = Surface.smoothUnion(6.0,
                Surface.Plane.ground(),
                new Surface.Translate(0, 1, 0, new Surface.Sphere(0, 0, 0, 1)),
                new Surface.Difference(new Surface.Box(2, 1, 0, 0.5, 0.5, 0.5),
                        new Surface.Sphere(2, 1.5, 0, 0.4)));

        Field display = SurfaceCompiler.compile(scene);
        assertFalse(display.hasPayload());
        assertThrows(IllegalStateException.class, () -> display.asPayloadFunction("hit"));

        // The distance is the same field either way — same answers, to f32 tolerance, everywhere. The
        // payload variant costs bindings and narrower sharing; it does not cost a different surface.
        Field withPayload = SurfaceCompiler.compileWithPayload(scene);
        assertTrue(withPayload.hasPayload());
        for (double[] p : grid()) {
            assertEquals(Eval.at(display, p[0], p[1], p[2]), Eval.at(withPayload, p[0], p[1], p[2]), 1e-9,
                    "the payload lowering moved the surface at " + List.of(p[0], p[1], p[2]));
        }
    }

    @Test
    @DisplayName("only the nodes that own their points get a slot, and asking for another says so")
    void slotsBelongToTheShapes() {
        Surface union = Surface.union(LEFT, RIGHT);
        PayloadTable table = PayloadTable.of(union);

        assertEquals(2, table.size());
        assertTrue(table.holds(LEFT.id()));
        assertFalse(table.holds(union.id()), "a union chooses between children rather than owning a point");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> table.slotOf(union.id()));
        assertTrue(thrown.getMessage().contains("own their points"), thrown.getMessage());
    }

    @Test
    @DisplayName("a slot from a stale readback names nothing rather than throwing")
    void anUnknownSlotIsEmpty() {
        PayloadTable table = PayloadTable.of(Surface.union(LEFT, RIGHT));

        assertTrue(table.nodeAt((int) PayloadTable.NOTHING).isEmpty(), "the miss value names nothing");
        assertTrue(table.nodeAt(99).isEmpty(), "a slot from another tree names nothing here");
    }

    @Test
    @DisplayName("both halves come out of one function, distance in x and payload in y")
    void oneFunctionCarriesBoth() {
        Field field = SurfaceCompiler.compileWithPayload(Surface.union(LEFT, RIGHT));
        var function = field.asPayloadFunction("hit");

        assertEquals(Ir.V2, function.signature().returnType());
        assertEquals(List.of(Ir.V3), function.signature().parameterTypes());
    }

    private static List<double[]> grid() {
        List<double[]> points = new java.util.ArrayList<>();
        for (double x = -2.2; x <= 2.2; x += 1.1) {
            for (double y = -1.5; y <= 2.5; y += 1.3) {
                for (double z = -2.0; z <= 2.0; z += 1.0) {
                    points.add(new double[]{x, y, z});
                }
            }
        }
        return points;
    }
}
