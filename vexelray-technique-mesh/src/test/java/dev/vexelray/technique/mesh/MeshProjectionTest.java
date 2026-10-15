package dev.vexelray.technique.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>A mesh vertex lands where the march would see it</b>, and a placement's angles mean the camera's angles —
 * both checked on the CPU against the formulas {@code SdfComposer} builds its primary ray from.
 *
 * <p>The same argument as {@code PanelProjectionTest}: this is agreement between two formulas, and the failure it
 * is there to catch is a sign or a transposed rotation, which is exact at the centre of the screen and plausible
 * everywhere else. So the camera is turned, the mesh is turned, and the points checked include corners.
 */
class MeshProjectionTest {

    private static final double FOCAL = 1.4;
    private static final double EPSILON = 1e-5;

    /** {@code MeshShader}'s vertex stage, in Java: a world point to a normalised device coordinate. */
    private static double[] project(double wx, double wy, double wz,
                                    double camX, double camY, double camZ, double yaw, double pitch,
                                    double aspect) {
        double rx = wx - camX;
        double ry = wy - camY;
        double rz = wz - camZ;
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double ax = rx * cy - rz * sy;
        double az = rx * sy + rz * cy;
        double vy = ry * cp + az * sp;
        double vz = az * cp - ry * sp;
        return new double[]{FOCAL * ax / (aspect * vz), -FOCAL * vy / vz};
    }

    /** {@code SdfComposer.primaryRay}, unnormalised, for the pixel at a normalised device coordinate. */
    private static double[] marchRay(double ndcX, double ndcY, double yaw, double pitch, double aspect) {
        double u = (ndcX + 1) * 0.5;
        double v = (ndcY + 1) * 0.5;
        double sx = (2 * u - 1) * aspect;
        double sy = 1 - 2 * v;
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double py = sy * cp - FOCAL * sp;
        double pz = sy * sp + FOCAL * cp;
        double cy = Math.cos(yaw);
        double syw = Math.sin(yaw);
        return new double[]{sx * cy + pz * syw, py, pz * cy - sx * syw};
    }

    @Test
    void theRayThroughAMeshVertexPixelPointsAtTheVertex() {
        double camX = -0.5;
        double camY = 1.2;
        double camZ = 0.4;
        double yaw = 0.25;
        double pitch = 0.1;
        double aspect = 16.0 / 9.0;

        Placement placement = new Placement(1.5, 0.8, 7, 0.3, -0.2, 0.15, 1.7);
        float[] m = new float[Placement.MATRIX_FLOATS];
        placement.modelMatrix(m, 0);

        Mesh box = Mesh.box(2, 1, 0.5);
        float[] v = box.vertices();
        for (int i = 0; i < box.vertexCount(); i++) {
            int at = i * Mesh.FLOATS_PER_VERTEX;
            double lx = v[at];
            double ly = v[at + 1];
            double lz = v[at + 2];
            double wx = m[0] * lx + m[3] * ly + m[6] * lz + m[9];
            double wy = m[1] * lx + m[4] * ly + m[7] * lz + m[10];
            double wz = m[2] * lx + m[5] * ly + m[8] * lz + m[11];

            double[] ndc = project(wx, wy, wz, camX, camY, camZ, yaw, pitch, aspect);
            double[] ray = marchRay(ndc[0], ndc[1], yaw, pitch, aspect);
            double[] to = {wx - camX, wy - camY, wz - camZ};

            // Parallel and the same way round: the cross product vanishes and the dot product is positive.
            double rl = Math.sqrt(ray[0] * ray[0] + ray[1] * ray[1] + ray[2] * ray[2]);
            double tl = Math.sqrt(to[0] * to[0] + to[1] * to[1] + to[2] * to[2]);
            double dot = (ray[0] * to[0] + ray[1] * to[1] + ray[2] * to[2]) / (rl * tl);
            assertEquals(1.0, dot, EPSILON, "vertex " + i + ": the march's ray through its pixel misses it");
        }
    }

    /**
     * A placement with the camera's yaw and pitch has its {@code +z} along the camera's forward ray — the
     * property that makes "face the way the camera faces" a placement rather than a derivation.
     */
    @Test
    void aPlacementsZAxisIsTheCamerasForwardForTheSameAngles() {
        double yaw = -0.7;
        double pitch = 0.35;
        float[] m = new float[Placement.MATRIX_FLOATS];
        new Placement(0, 0, 0, yaw, pitch, 0.4, 1).modelMatrix(m, 0);

        double[] forward = marchRay(0, 0, yaw, pitch, 1);
        double length = Math.sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2]);
        assertEquals(forward[0] / length, m[6], EPSILON);
        assertEquals(forward[1] / length, m[7], EPSILON);
        assertEquals(forward[2] / length, m[8], EPSILON);
    }

    @Test
    void theRotationIsOrthonormalAndScaled() {
        float[] m = new float[Placement.MATRIX_FLOATS];
        double scale = 2.5;
        new Placement(1, 2, 3, 1.1, -0.6, 2.2, scale).modelMatrix(m, 0);
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                double dot = m[a * 3] * m[b * 3] + m[a * 3 + 1] * m[b * 3 + 1] + m[a * 3 + 2] * m[b * 3 + 2];
                assertEquals(a == b ? scale * scale : 0.0, dot, EPSILON);
            }
        }
        // x cross y = z in a rotation, whichever hand the frame is: a reflection would flip it.
        double cx = m[1] * m[5] - m[2] * m[4];
        double cy = m[2] * m[3] - m[0] * m[5];
        double cz = m[0] * m[4] - m[1] * m[3];
        assertEquals(m[6] * scale, cx, EPSILON);
        assertEquals(m[7] * scale, cy, EPSILON);
        assertEquals(m[8] * scale, cz, EPSILON);
        assertEquals(1f, m[9]);
        assertEquals(2f, m[10]);
        assertEquals(3f, m[11]);
    }
}
