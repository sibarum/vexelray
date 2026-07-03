package vexelray.supirvast.sdf;

/**
 * One primitive shape combined into the SDF scene by a {@link BoolOp}.
 *
 * <p>{@link Kind#BOX} is the only kind today — it is the one shape dasum-vis's live
 * VexelRay renderer can boolean-combine (CSG_BOXES). Other kinds (sphere, torus, …)
 * become available once the scene is rendered through a generated shader (the
 * {@code vexelray-supirvast} codegen path) rather than the fixed-function renderer.
 *
 * @param kind        shape kind
 * @param op          how this primitive folds into the field
 * @param center      shape center, in field-local space (bounding-cube center at origin)
 * @param halfExtents box half-sizes per axis (all &gt; 0)
 * @param smoothK     blend radius for SMOOTH_* ops (world units; &gt; 0 for smooth ops)
 */
public record Primitive(Kind kind, BoolOp op, Vec3f center, Vec3f halfExtents, float smoothK) {

    public enum Kind { BOX }

    public Primitive {
        if (kind == null) throw new IllegalArgumentException("kind != null");
        if (op == null) throw new IllegalArgumentException("op != null");
        if (center == null) throw new IllegalArgumentException("center != null");
        if (halfExtents == null) throw new IllegalArgumentException("halfExtents != null");
        if (!(halfExtents.x() > 0f) || !(halfExtents.y() > 0f) || !(halfExtents.z() > 0f)) {
            throw new IllegalArgumentException("halfExtents must be > 0 per axis");
        }
        if (op.smooth() && !(smoothK > 0f)) {
            throw new IllegalArgumentException("smoothK > 0 required for smooth ops");
        }
    }

    /** A box primitive. {@code smoothK} is ignored by hard ops. */
    public static Primitive box(BoolOp op, Vec3f center, Vec3f halfExtents, float smoothK) {
        return new Primitive(Kind.BOX, op, center, halfExtents, smoothK);
    }

    public Primitive withOp(BoolOp newOp)          { return new Primitive(kind, newOp, center, halfExtents, smoothK); }
    public Primitive withCenter(Vec3f c)           { return new Primitive(kind, op, c, halfExtents, smoothK); }
    public Primitive withHalfExtents(Vec3f h)      { return new Primitive(kind, op, center, h, smoothK); }
}
