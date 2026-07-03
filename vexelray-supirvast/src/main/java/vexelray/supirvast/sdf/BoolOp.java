package vexelray.supirvast.sdf;

/**
 * How a primitive combines into the running signed-distance field — a sequential
 * left-fold {@code d = op(d, prim)}. Names mirror dasum-vis {@code CsgBox.Op} so the
 * editor's renderer bridge is a 1:1 mapping; the codegen lowers each to the matching
 * min/max/smin/smax in {@code core} IR.
 *
 * <p>The first op in a scene should be a {@link #UNION} (it combines against empty
 * space); a leading subtract/intersect folds against nothing and yields nothing.
 */
public enum BoolOp {
    UNION,
    SUBTRACT,
    INTERSECT,
    SMOOTH_UNION,
    SMOOTH_SUBTRACT,
    SMOOTH_INTERSECT;

    public boolean smooth() {
        return this == SMOOTH_UNION || this == SMOOTH_SUBTRACT || this == SMOOTH_INTERSECT;
    }
}
