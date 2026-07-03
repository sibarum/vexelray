package vexelray.supirvast.sdf;

/** A simple immutable 3D float vector for the SDF scene model (no external math dependency). */
public record Vec3f(float x, float y, float z) {

    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);

    public Vec3f add(Vec3f o) { return new Vec3f(x + o.x, y + o.y, z + o.z); }
}
