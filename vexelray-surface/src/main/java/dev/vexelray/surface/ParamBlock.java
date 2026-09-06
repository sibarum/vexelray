package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.PushConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every parameter in a surface, in a fixed order, with somewhere to put their values.
 *
 * <p>Built by walking the tree ({@link Scalars#forEach}) and keeping the distinct {@link ParamId}s in the order
 * they are first met. That order is the slot assignment, and it is deterministic for a given tree shape — which
 * is what lets two identically-shaped surfaces share one compiled pipeline, and what lets a value written
 * before a recompile land in the right place after it.
 *
 * <h2>It publishes a writer, not an offset</h2>
 *
 * <p>{@link #write(ParamId, double)} is the whole of the host-facing API, and the absence of
 * {@code offsetOf(ParamId)} is deliberate. A byte offset is this class's encoding leaking through its boundary,
 * and it commits every caller to one storage class and one representation. Three changes that are otherwise
 * invisible would each break such a caller:
 *
 * <ul>
 *   <li><b>P0b</b> moves the backing from push constants to a storage buffer, where a slot is an index rather
 *       than an offset;</li>
 *   <li><b>quantised packing</b> would put two ranged parameters in one 32-bit slot, so an offset would no
 *       longer identify a value at all;</li>
 *   <li><b>N-buffering</b> — needed the day {@code renderInto} stops waiting on its own fence — would make the
 *       offset depend on which frame is in flight.</li>
 * </ul>
 *
 * <p>The same argument reaches disk: R5's format has to carry parameter declarations, and an offset that
 * reached a file would make any of the three above a format version.
 *
 * <h2>Values are here, not in the tree</h2>
 *
 * <p>A {@link Scalar.Param} carries its <em>initial</em> value and never its live one. Moving a slider mutates
 * this object, not the {@link Surface} — so the tree, its structural equality, and therefore the shader key are
 * all unmoved by a value change. That is the mechanism behind R1: a sweep is writes, not compiles.
 */
public final class ParamBlock {

    private final List<Scalar.Param> params;
    private final Map<ParamId, Integer> slots;
    private final double[] values;

    private ParamBlock(List<Scalar.Param> params) {
        this.params = List.copyOf(params);
        this.slots = new LinkedHashMap<>();
        this.values = new double[params.size()];
        for (int i = 0; i < this.params.size(); i++) {
            slots.put(this.params.get(i).id(), i);
            values[i] = this.params.get(i).initial();
        }
    }

    /**
     * The parameters of {@code surface}, in slot order.
     *
     * @throws IllegalArgumentException by name if one id is used with two different ranges — two sliders over
     *                                  one value, disagreeing about what it may be, is an authoring mistake
     *                                  that would otherwise show up as a silently clamped parameter
     */
    public static ParamBlock of(Surface surface) {
        Map<ParamId, Scalar.Param> distinct = new LinkedHashMap<>();
        Scalars.forEach(surface, scalar -> {
            if (scalar instanceof Scalar.Param p) {
                Scalar.Param seen = distinct.putIfAbsent(p.id(), p);
                if (seen != null && (seen.min() != p.min() || seen.max() != p.max())) {
                    throw new IllegalArgumentException(
                            "parameter " + p.id() + " is used with two different ranges: [" + seen.min() + ", "
                                    + seen.max() + "] and [" + p.min() + ", " + p.max() + "]");
                }
            }
        });
        return new ParamBlock(new ArrayList<>(distinct.values()));
    }

    /** How many parameters — the number of slots a backing store must provide. */
    public int size() {
        return params.size();
    }

    /** The declarations, in slot order: what a host builds sliders from. */
    public List<Scalar.Param> params() {
        return params;
    }

    /** Whether this block declares {@code id}. */
    public boolean holds(ParamId id) {
        return slots.containsKey(id);
    }

    /**
     * Which slot {@code id} occupies — its position in the walk, not a byte offset, and not something to encode
     * with. {@link Surface#shaderKey()} is what it is for: the number a parameter contributes to the normal
     * form, in place of its identity.
     *
     * @throws IllegalArgumentException by name if this block does not declare it
     */
    public int slotOf(ParamId id) {
        return slot(id);
    }

    /**
     * The current value of {@code id}.
     *
     * @throws IllegalArgumentException by name if this block does not declare it
     */
    public double read(ParamId id) {
        return values[slot(id)];
    }

    /**
     * Set {@code id}'s value, clamped to its declared range.
     *
     * <p>Clamped rather than refused, because the range is the parameter's declared domain and every other part
     * of the system is entitled to rely on it: {@link Bounds} contains the geometry only over that interval, and
     * validation such as "a radius is positive" was checked across it and nowhere else. A host dragging a slider
     * would otherwise have to re-implement the same clamp, and the one that forgot would put geometry outside
     * the box the camera framed on.
     *
     * @throws IllegalArgumentException by name if this block does not declare {@code id}, or if {@code value} is
     *                                  not finite
     */
    public void write(ParamId id, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("a parameter value must be finite, got " + value);
        }
        int slot = slot(id);
        Scalar.Param p = params.get(slot);
        values[slot] = Math.min(Math.max(value, p.min()), p.max());
    }

    /** Every value back to the initial the surface declared. */
    public void reset() {
        for (int i = 0; i < values.length; i++) {
            values[i] = params.get(i).initial();
        }
    }

    /**
     * Carry values across a recompile, <b>by identity</b>.
     *
     * <p>R4's rule, and the reason slots are never the currency: a parameter that survived a structural edit
     * keeps the value it was holding even if it moved slot, and one the edit deleted simply drops. A block that
     * kept values by position would hand a radius the value of an unrelated twist rate.
     */
    public void carryFrom(ParamBlock previous) {
        for (Scalar.Param p : params) {
            if (previous.holds(p.id())) {
                write(p.id(), previous.read(p.id()));
            }
        }
    }

    /** The values in slot order, as the floats a block or a buffer is filled with. */
    public float[] floats() {
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        return out;
    }

    /**
     * Whether {@code other} declares the same parameters in the same slots — the check a host makes before
     * writing values through a block it did not just build from the surface it is about to render.
     */
    public boolean sameLayout(ParamBlock other) {
        if (other == null || other.params.size() != params.size()) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            if (!params.get(i).equals(other.params.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A store reading these parameters out of {@code block}, whose members from {@code firstMember} on are the
     * parameters in slot order.
     *
     * <p>The block is passed in rather than built here because the composer owns it: a module declares one
     * push-constant block (the SPIR-V rule), and the composer's camera is already in it.
     */
    public ParamStore inPushConstants(PushConstants block, int firstMember) {
        if (block.members().size() < firstMember + params.size()) {
            throw new IllegalArgumentException(
                    "push-constant block has " + block.members().size() + " members; " + params.size()
                            + " parameters from member " + firstMember + " need "
                            + (firstMember + params.size()));
        }
        return new ParamStore() {

            @Override
            public Expr read(ParamId id) {
                return block.read(firstMember + slot(id));
            }

            @Override
            public boolean issued(PushConstants candidate) {
                return block.equals(candidate);
            }
        };
    }

    private int slot(ParamId id) {
        Integer slot = slots.get(id);
        if (slot == null) {
            throw new IllegalArgumentException(
                    "no such parameter in this block: " + id + "; it declares " + slots.keySet());
        }
        return slot;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ParamBlock[");
        for (int i = 0; i < params.size(); i++) {
            Scalar.Param p = params.get(i);
            out.append(i > 0 ? ", " : "").append(i).append(':').append(p.id()).append('=').append(values[i]);
        }
        return out.append(']').toString();
    }
}
