package vexelray.supirvast.sdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A mutable CSG scene document — an ordered list of {@link Primitive}s folded into one
 * signed-distance field, plus a global edge {@code rounding}. This is the shared model
 * between the scene editor (which mutates it) and both consumers: the dasum-vis renderer
 * bridge and the {@code vexelray-supirvast} shader codegen.
 *
 * <p>Not thread-safe; mutated from the UI thread.
 */
public final class SdfScene {

    private final List<Primitive> primitives = new ArrayList<>();
    private float rounding;

    public SdfScene() {
        this(0f);
    }

    public SdfScene(float rounding) {
        this.rounding = rounding;
    }

    /** Immutable view of the current op list, in fold order. */
    public List<Primitive> primitives() {
        return Collections.unmodifiableList(primitives);
    }

    public int size() { return primitives.size(); }

    public boolean isEmpty() { return primitives.isEmpty(); }

    public float rounding() { return rounding; }

    public void setRounding(float r) { this.rounding = Math.max(0f, r); }

    public void add(Primitive p) {
        if (p == null) throw new IllegalArgumentException("primitive != null");
        primitives.add(p);
    }

    /** Remove the last primitive; no-op if empty. Returns true if one was removed. */
    public boolean removeLast() {
        if (primitives.isEmpty()) return false;
        primitives.remove(primitives.size() - 1);
        return true;
    }

    public void clear() {
        primitives.clear();
    }
}
