package dev.vexelray.surface;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The identity of a parameter, independent of anything a person calls it.
 *
 * <p>A value type rather than a {@code String}, and the distinction is the whole point. A parameter has two
 * names: the one shown in a slider's label, which a designer renames freely, and the one everything else refers
 * to it by. Collapsing those into one string means a rename silently becomes a different parameter — the value
 * held off its default is dropped, the document that referred to it no longer resolves, and the shader cache
 * misses. Keeping them apart costs one record.
 *
 * <p>Identity survives three things it has to survive: a pipeline swap (a value is carried across by id, never
 * by slot or byte offset), a structural edit that moves a node in the tree, and a round trip through a file —
 * which is why the id is a plain {@code long} rather than an object address, and why {@link #of} exists at all.
 *
 * <p>Ids minted by {@link #fresh()} are unique within a JVM run. Reading one back with {@link #of} advances the
 * counter past it, so a document loaded into a session that has already minted ids cannot collide with one
 * minted afterwards.
 */
public record ParamId(long value) {

    private static final AtomicLong NEXT = new AtomicLong();

    public ParamId {
        if (value < 0) {
            throw new IllegalArgumentException("a parameter id must not be negative, got " + value);
        }
        NEXT.updateAndGet(next -> Math.max(next, value + 1));
    }

    /** An id no other parameter in this run holds. */
    public static ParamId fresh() {
        return new ParamId(NEXT.getAndIncrement());
    }

    /** The id with this value — how one arrives back from a file. */
    public static ParamId of(long value) {
        return new ParamId(value);
    }

    @Override
    public String toString() {
        return "param#" + value;
    }
}
