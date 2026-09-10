package dev.vexelray.surface;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Which node this is — the thing a click selects, a handle drags, and an edit leaves alone.
 *
 * <p><b>Not derived from structure, and that is the whole point.</b> Two spheres of the same radius in the
 * same place are one shape and two objects: selecting one must not select the other, dragging one must not
 * drag both, and an edit to one must leave the other exactly where it was. Structural equality — which
 * everything else in {@link Surface} leans on — cannot tell them apart, so identity is carried rather than
 * computed.
 *
 * <p>It is minted at construction and preserved by every edit that does not replace the node, which is what
 * makes it worth anything: a value held off its default survives a recompile (R4), a selection survives an
 * edit to a sibling (R13), and a document reopens with its selections intact (R5). That is also why it is a
 * plain {@code long} rather than an object address — an address does not survive a file.
 *
 * <h2>What it costs, and what pays it back</h2>
 *
 * <p>Carrying identity in the record means two independently authored copies of one shape are no longer
 * {@code equal}. Three things leaned on that equality: the shader cache, which collapsed identical scenes
 * onto one pipeline; {@link Surface#shaderKey()}, which decides when two designs are one shader; and
 * {@code Shared}, which emits a repeated subtree once. All three now ask for the <em>identity-erased</em>
 * form ({@link Surface#shaderKey()}) instead, so they collapse exactly as they did before — a sphere is a
 * sphere to a compiler, and two objects to a person.
 */
public record NodeId(long value) {

    private static final AtomicLong NEXT = new AtomicLong();

    public NodeId {
        if (value < 0) {
            throw new IllegalArgumentException("a node id must not be negative, got " + value);
        }
        NEXT.updateAndGet(next -> Math.max(next, value + 1));
    }

    /** An id no other node in this run holds. */
    public static NodeId fresh() {
        return new NodeId(NEXT.getAndIncrement());
    }

    /** The id with this value — how one arrives back from a file. */
    public static NodeId of(long value) {
        return new NodeId(value);
    }

    @Override
    public String toString() {
        return "node#" + value;
    }
}
