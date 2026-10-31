package dev.vexelray.technique.mesh;

import java.util.Objects;

/**
 * Prototype (test scope): where a shell's field stands. The field has a space of its own, placed either <b>in the
 * object</b>, so it moves, turns and scales with the shell as one solid, or <b>in the world</b>, so the shell
 * moves through a field that stays put. Either way it carries its own {@link Placement}, which can change frame
 * by frame on its own: a field slid through a still object, or turned inside a turning one.
 *
 * <h2>Solid</h2>
 *
 * <p>A placement is a rotation, a translation and one scale, never a shear or an uneven stretch, so a distance in
 * field space is a distance in the world times one number. A march that steps by the field's distance divided by
 * that number never overshoots, however the object and its field are placed.
 *
 * <h2>What the shader gets</h2>
 *
 * <p>Object and field are composed on the CPU into one map from the world to the field, sent as
 * {@link #FLOATS} floats: a unit quaternion {@code q}, an offset {@code o} and a scale {@code k}, so that
 * {@code field = k * rotate(q, world) + o}. A ray stays a ray under it, so a fragment maps its entry point and
 * direction once and the march itself costs nothing more.
 */
record FieldSpace(Placement placement, boolean inWorld) {

    /** Floats {@link #worldToField} writes: quaternion (x, y, z, w), offset (x, y, z), scale. */
    static final int FLOATS = 8;

    /** The field in the object, standing exactly where the object does. */
    static final FieldSpace OBJECT = inObject(Placement.ORIGIN);

    FieldSpace {
        Objects.requireNonNull(placement, "placement");
    }

    /** The field placed in the object's own coordinates: it moves with the object. */
    static FieldSpace inObject(Placement placement) {
        return new FieldSpace(placement, false);
    }

    /** The field placed in the world: the object moves through it. */
    static FieldSpace inWorld(Placement placement) {
        return new FieldSpace(placement, true);
    }

    /** The same space, moved, turned or scaled to {@code placement}. */
    FieldSpace placed(Placement placement) {
        return new FieldSpace(placement, inWorld);
    }

    /**
     * The world-to-field map for this field in an object standing at {@code object}: {@link #FLOATS} floats into
     * {@code out} at {@code at}. Called per shell per frame; it allocates a few small arrays, which a prototype
     * can afford.
     */
    void worldToField(Placement object, float[] out, int at) {
        float[] f = new float[Placement.MATRIX_FLOATS];
        placement.modelMatrix(f, 0);
        double[] a = new double[9];
        double[] o = new double[3];
        if (inWorld) {
            for (int i = 0; i < 9; i++) {
                a[i] = f[i];
            }
            o[0] = f[9];
            o[1] = f[10];
            o[2] = f[11];
        } else {
            // Field to world is object-to-world after field-to-object: A = Ao * Af, o = Ao * of + oo.
            float[] m = new float[Placement.MATRIX_FLOATS];
            object.modelMatrix(m, 0);
            for (int col = 0; col < 3; col++) {
                for (int row = 0; row < 3; row++) {
                    a[col * 3 + row] = m[row] * f[col * 3] + m[3 + row] * f[col * 3 + 1]
                            + m[6 + row] * f[col * 3 + 2];
                }
            }
            for (int row = 0; row < 3; row++) {
                o[row] = m[row] * f[9] + m[3 + row] * f[10] + m[6 + row] * f[11] + m[9 + row];
            }
        }
        // A = s * R with R a rotation. Its inverse is R^T / s: field = (1/s) R^T world - (1/s) R^T o.
        double s = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double k = 1.0 / s;
        // q[row][col] of Q = R^T; R's columns are A's columns over s, so Q's rows are.
        double[][] q = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                q[row][col] = a[row * 3 + col] * k;
            }
        }
        for (int row = 0; row < 3; row++) {
            out[at + 4 + row] = (float) (-k * (q[row][0] * o[0] + q[row][1] * o[1] + q[row][2] * o[2]));
        }
        out[at + 7] = (float) k;
        quaternion(q, out, at);
    }

    /** The unit quaternion of rotation {@code q}, as (x, y, z, w): Shepperd's method, stable for every angle. */
    private static void quaternion(double[][] q, float[] out, int at) {
        double trace = q[0][0] + q[1][1] + q[2][2];
        double x;
        double y;
        double z;
        double w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2;
            w = 0.25 * s;
            x = (q[2][1] - q[1][2]) / s;
            y = (q[0][2] - q[2][0]) / s;
            z = (q[1][0] - q[0][1]) / s;
        } else if (q[0][0] > q[1][1] && q[0][0] > q[2][2]) {
            double s = Math.sqrt(1.0 + q[0][0] - q[1][1] - q[2][2]) * 2;
            w = (q[2][1] - q[1][2]) / s;
            x = 0.25 * s;
            y = (q[0][1] + q[1][0]) / s;
            z = (q[0][2] + q[2][0]) / s;
        } else if (q[1][1] > q[2][2]) {
            double s = Math.sqrt(1.0 + q[1][1] - q[0][0] - q[2][2]) * 2;
            w = (q[0][2] - q[2][0]) / s;
            x = (q[0][1] + q[1][0]) / s;
            y = 0.25 * s;
            z = (q[1][2] + q[2][1]) / s;
        } else {
            double s = Math.sqrt(1.0 + q[2][2] - q[0][0] - q[1][1]) * 2;
            w = (q[1][0] - q[0][1]) / s;
            x = (q[0][2] + q[2][0]) / s;
            y = (q[1][2] + q[2][1]) / s;
            z = 0.25 * s;
        }
        out[at] = (float) x;
        out[at + 1] = (float) y;
        out[at + 2] = (float) z;
        out[at + 3] = (float) w;
    }
}
