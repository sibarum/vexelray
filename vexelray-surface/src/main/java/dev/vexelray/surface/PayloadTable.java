package dev.vexelray.surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the payload channel's numbers mean: slot → the node that owns the surface there.
 *
 * <p>The channel carries one float, because that is what a shader can carry cheaply and read back exactly —
 * a 32-bit float holds every integer up to 2²⁴, which is more nodes than a design will ever have. What it
 * carries is a <b>slot</b>, and this is what turns a slot back into a {@link NodeId}.
 *
 * <p><b>A slot is not an identity and must not escape as one.</b> It is assigned by walk order, so it changes
 * whenever the tree changes shape, and a host that stored one would find it pointing at a different shape
 * after the next edit. The same rule {@link ParamBlock} follows for the same reason: publish identity, keep
 * the encoding inside. Look a slot up the moment it comes back from the GPU, and keep the {@link NodeId}.
 *
 * <h2>Which nodes get one</h2>
 *
 * <p>The ones that <em>originate</em> a payload: the primitives, a stroke, an implicit. A combinator does not
 * own a point — it chooses which of its children owns it — and a domain transform does not either, it moves
 * where the question is asked. So a click resolves to the shape under the cursor rather than to the union it
 * happens to be inside, and the tree view is what selects a group.
 *
 * <p>The channel has room for the other payload R7 wants — a material — which is why this is a table of rows
 * rather than an array of ids. A material vocabulary can arrive without the channel changing.
 */
public final class PayloadTable {

    /** The value the channel carries where nothing was hit — every real slot is zero or greater. */
    public static final double NOTHING = -1.0;

    private final List<NodeId> nodes;
    private final Map<NodeId, Integer> slots;

    private PayloadTable(List<NodeId> nodes) {
        this.nodes = List.copyOf(nodes);
        this.slots = new LinkedHashMap<>();
        for (int i = 0; i < this.nodes.size(); i++) {
            slots.put(this.nodes.get(i), i);
        }
    }

    /** The table for {@code surface}: every node that can own a point, in walk order. */
    public static PayloadTable of(Surface surface) {
        List<NodeId> nodes = new ArrayList<>();
        collect(surface, nodes);
        return new PayloadTable(nodes);
    }

    private static void collect(Surface surface, List<NodeId> into) {
        if (Scalars.children(surface).isEmpty()) {
            into.add(surface.id());                 // a leaf owns its points
            return;
        }
        for (Surface child : Scalars.children(surface)) {
            collect(child, into);
        }
    }

    /** How many slots — one per node that can own a point. */
    public int size() {
        return nodes.size();
    }

    /** The nodes, in slot order. */
    public List<NodeId> nodes() {
        return nodes;
    }

    /**
     * Which node the channel meant.
     *
     * @param slot the value read back from the payload channel, rounded to an integer
     * @return the node, or empty for {@link #NOTHING} or any slot this table does not have — a value from a
     *         stale readback, which is a thing to ignore rather than to throw over
     */
    public java.util.Optional<NodeId> nodeAt(int slot) {
        return slot < 0 || slot >= nodes.size()
                ? java.util.Optional.empty()
                : java.util.Optional.of(nodes.get(slot));
    }

    /**
     * The slot {@code id} was given.
     *
     * @throws IllegalArgumentException by name if this node owns no points — a combinator or a transform,
     *                                 which is a question about the tree rather than about the surface
     */
    public int slotOf(NodeId id) {
        Integer slot = slots.get(id);
        if (slot == null) {
            throw new IllegalArgumentException(
                    "no payload slot for " + id + "; only the nodes that own their points get one (a "
                            + "primitive, a stroke, an implicit) — a combinator chooses between children "
                            + "rather than owning a point");
        }
        return slot;
    }

    /** Whether {@code id} owns points, and so has a slot. */
    public boolean holds(NodeId id) {
        return slots.containsKey(id);
    }

    @Override
    public String toString() {
        return "PayloadTable" + nodes;
    }
}
