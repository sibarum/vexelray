package dev.vexelray.surface;

import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2, first half: every node knows which node it is.
 *
 * <p>The tension the whole stage is about lives in the first two tests. Two spheres of one radius in one
 * place are <b>two objects</b> — a click selects one of them, a drag moves one of them, an edit deletes one
 * of them — and they are <b>one shape</b>, which must compile once. Identity says the first;
 * {@link Surface#shaderKey()} says the second; and everything downstream has to be told which of the two
 * questions it is asking.
 *
 * <p>The rest of this class is the list of things that were quietly leaning on structural equality before
 * identity existed, each now asking the normal form instead.
 */
class IdentityTest {

    @Test
    @DisplayName("two identical spheres are two objects")
    void identicalShapesAreDistinctObjects() {
        Surface one = new Surface.Sphere(0, 0, 0, 1);
        Surface other = new Surface.Sphere(0, 0, 0, 1);

        assertNotEquals(one, other);
        assertNotEquals(one.id(), other.id());
    }

    @Test
    @DisplayName("two identical spheres are one shape")
    void identicalShapesAreOneShader() {
        Surface one = new Surface.Sphere(0, 0, 0, 1);
        Surface other = new Surface.Sphere(0, 0, 0, 1);

        assertEquals(one.shaderKey(), other.shaderKey());
        assertEquals(SurfaceCompiler.compile(one).at(Ir.POINT),
                SurfaceCompiler.compile(other).at(Ir.POINT));
    }

    @Test
    @DisplayName("an id is minted per node, and no two nodes share one")
    void everyNodeGetsItsOwn() {
        Surface tree = Surface.union(
                new Surface.Sphere(0, 0, 0, 1),
                new Surface.Translate(2, 0, 0, new Surface.Box(0, 0, 0, 1, 1, 1)),
                new Surface.Difference(new Surface.Sphere(4, 0, 0, 1), new Surface.Sphere(4, 1, 0, 1)));

        Set<NodeId> seen = new HashSet<>();
        collect(tree, seen);
        assertEquals(7, seen.size(), "seven nodes, seven identities");
    }

    @Test
    @DisplayName("changing a number does not change which node it is")
    void editingAValueKeepsIdentity() {
        // The precondition for R13 and for a selection that survives a slider: an edit that does not replace
        // the node must leave its identity alone, or every drag would deselect the thing being dragged.
        Surface before = new Surface.Translate(1, 0, 0, new Surface.Sphere(0, 0, 0, 1));
        Surface after = Scalars.map(before, scalar -> Scalar.of(scalar.min() + 1));

        assertNotEquals(before, after, "the numbers really did change");
        assertEquals(before.id(), after.id());
        assertEquals(((Surface.Translate) before).of().id(), ((Surface.Translate) after).of().id());
    }

    @Test
    @DisplayName("the shader key numbers nodes by position, so it says shape and never identity")
    void theKeyCarriesPositionsRatherThanIdentities() {
        Surface tree = Surface.union(new Surface.Sphere(0, 0, 0, 1), new Surface.Sphere(3, 0, 0, 1));
        Surface key = tree.shaderKey();

        Set<NodeId> ids = new HashSet<>();
        collect(key, ids);
        assertEquals(Set.of(NodeId.of(0), NodeId.of(1), NodeId.of(2)), ids);

        // And it is a normal form rather than a copy: keying twice gives the same answer.
        assertEquals(key, tree.shaderKey());
    }

    @Test
    @DisplayName("sharing still fires across two separately authored copies of one shape")
    void sharingSurvivesIdentity() {
        // The interaction that would have failed silently. P1 emits a subtree once and calls it; identity
        // makes two copies of that subtree unequal. If Shared had kept counting by structural equality, this
        // would compile two functions instead of one — a size regression with no failing test anywhere.
        Surface arm = new Surface.Translate(0, 1, 0, new Surface.Box(0, 0, 0, 0.2, 1, 0.2));
        Surface twin = new Surface.Translate(0, 1, 0, new Surface.Box(0, 0, 0, 0.2, 1, 0.2));
        assertNotEquals(arm, twin);

        Field field = SurfaceCompiler.compile(Surface.union(arm,
                new Surface.Translate(2, 0, 0, twin)));
        assertEquals(1, field.helpers().size(),
                "two authorings of one arm should still be one function");
    }

    @Test
    @DisplayName("an id read back from a file cannot collide with one minted afterwards")
    void readingAnIdReservesIt() {
        NodeId loaded = NodeId.of(NodeId.fresh().value() + 1000);
        NodeId minted = NodeId.fresh();

        assertNotEquals(loaded, minted);
        assertTrue(minted.value() > loaded.value(), "minting should have skipped past the loaded id");
    }

    @Test
    @DisplayName("a node handed an identity keeps the one it was handed")
    void anIdentityCanBeSupplied() {
        // How a document reopens as the same document: the reader supplies the ids it read rather than
        // minting new ones, and every selection, parameter binding and reference still resolves.
        NodeId id = NodeId.fresh();
        Surface.Sphere sphere = new Surface.Sphere(id, Scalar.of(0), Scalar.of(0), Scalar.of(0), Scalar.of(1));

        assertSame(id, sphere.id());
        assertEquals(sphere, new Surface.Sphere(id, Scalar.of(0), Scalar.of(0), Scalar.of(0), Scalar.of(1)),
                "same identity and same numbers is the same node");
    }

    private static void collect(Surface surface, Set<NodeId> into) {
        into.add(surface.id());
        for (Surface child : Scalars.children(surface)) {
            collect(child, into);
        }
    }
}
