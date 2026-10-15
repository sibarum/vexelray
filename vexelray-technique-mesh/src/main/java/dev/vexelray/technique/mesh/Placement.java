package dev.vexelray.technique.mesh;

/**
 * Where a mesh stands in the world: a position, a turn, and a size.
 *
 * <p>{@link #yaw} and {@link #pitch} mean here exactly what they mean on {@code SdfRaymarchTechnique.camera}:
 * the same axes, the same signs, the same order (pitch about x, then yaw about y). A mesh's own {@code +z}
 * therefore points the way a camera with the same yaw and pitch looks, which is the property that makes "turn this
 * to face that" one line rather than a derivation. {@link #roll} is about the mesh's own z axis, applied first, so
 * yaw and pitch keep meaning what they mean.
 *
 * <p>The rotation is spelled out the same way {@code Panel} spells its own, longhand, so the two can be read side
 * by side: a panel's canvas {@code +x} and a mesh's {@code +x} are the same world direction for the same three
 * angles.
 *
 * @param x     world position of the mesh's origin
 * @param y     world position of the mesh's origin
 * @param z     world position of the mesh's origin
 * @param yaw   rotation about the world y axis, radians, as the camera means it
 * @param pitch rotation about the world x axis, radians, as the camera means it
 * @param roll  rotation about the mesh's own z axis, radians, applied before pitch and yaw
 * @param scale uniform scale. Uniform on purpose: it keeps a normal transformed by the model matrix
 *              perpendicular to its surface, which a per-axis scale does not
 */
public record Placement(double x, double y, double z, double yaw, double pitch, double roll, double scale) {

    /** How many floats {@link #modelMatrix} writes: three scaled axes and a translation, three each. */
    public static final int MATRIX_FLOATS = 12;

    /** At the world origin, unturned, at its own size. */
    public static final Placement ORIGIN = new Placement(0, 0, 0, 0, 0, 0, 1);

    public Placement {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(roll)) {
            throw new IllegalArgumentException("a placement must be finite");
        }
        if (!(scale > 0) || !Double.isFinite(scale)) {
            throw new IllegalArgumentException("scale must be finite and positive, got " + scale);
        }
    }

    /** At {@code (x, y, z)}, unturned, at its own size. */
    public static Placement at(double x, double y, double z) {
        return new Placement(x, y, z, 0, 0, 0, 1);
    }

    /** The same placement moved to {@code (x, y, z)}. */
    public Placement moved(double x, double y, double z) {
        return new Placement(x, y, z, yaw, pitch, roll, scale);
    }

    /** The same placement turned to {@code (yaw, pitch)}, keeping its roll. */
    public Placement turned(double yaw, double pitch) {
        return new Placement(x, y, z, yaw, pitch, roll, scale);
    }

    /** The same placement rolled to {@code roll}. */
    public Placement rolled(double roll) {
        return new Placement(x, y, z, yaw, pitch, roll, scale);
    }

    /** The same placement at {@code scale} times the mesh's own size. */
    public Placement scaled(double scale) {
        return new Placement(x, y, z, yaw, pitch, roll, scale);
    }

    /**
     * The mesh-to-world map as twelve floats: the world vectors of the mesh's {@code +x}, {@code +y} and {@code +z}
     * (scaled), then the world position of its origin.
     *
     * <p>Allocates nothing: it is called once per instance per frame from a technique's record path.
     *
     * @param out receives {@link #MATRIX_FLOATS} floats at {@code at}
     */
    public void modelMatrix(float[] out, int at) {
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);

        // R = yaw * pitch * roll, the columns written out: R(1,0,0), R(0,1,0), R(0,0,1). The first two are the
        // panel's right and (negated) down axes; the third is the camera's forward for the same yaw and pitch.
        out[at] = (float) ((cr * cy + sr * sp * sy) * scale);
        out[at + 1] = (float) ((sr * cp) * scale);
        out[at + 2] = (float) ((sr * sp * cy - cr * sy) * scale);

        out[at + 3] = (float) ((cr * sp * sy - sr * cy) * scale);
        out[at + 4] = (float) ((cr * cp) * scale);
        out[at + 5] = (float) ((cr * sp * cy + sr * sy) * scale);

        out[at + 6] = (float) ((cp * sy) * scale);
        out[at + 7] = (float) (-sp * scale);
        out[at + 8] = (float) ((cp * cy) * scale);

        out[at + 9] = (float) x;
        out[at + 10] = (float) y;
        out[at + 11] = (float) z;
    }
}
