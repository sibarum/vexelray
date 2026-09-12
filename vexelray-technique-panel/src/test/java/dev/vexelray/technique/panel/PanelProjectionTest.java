package dev.vexelray.technique.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A panel lands where the march would see it</b> — the projection convention, checked on the CPU against the
 * one {@code SdfComposer} builds its primary ray from.
 *
 * <h2>Why this can be tested without a device</h2>
 *
 * <p>The claim is not about pixels, it is about agreement between two formulas. The march turns a pixel into a
 * ray: {@code sx = (2u-1)*aspect}, {@code sy = 1-2v}, direction {@code (sx, sy, focal)} in the camera's frame.
 * The panel's vertex stage turns a view-space point into a pixel. If the two are inverses, then for every point
 * the panel draws, the ray through the pixel it drew at <em>points at that point</em> — and that is a statement
 * about arithmetic, testable here, rather than about a picture, testable only with a GPU.
 *
 * <p>It is also the check that catches the failure this whole module is most likely to have: a sign. Canvas y
 * runs down, world y runs up, Vulkan's device y runs down, and the march's screen y runs up. Four conventions
 * meet in {@link Panel#viewBasis} and the vertex stage, and three of the four ways to get it wrong still produce
 * a plausible picture — a panel that is simply upside down, or mirrored, or rotated the wrong way with the
 * camera. A test that renders one would not obviously fail. This one does.
 */
class PanelProjectionTest {

    private static final double FOCAL = 1.4;
    private static final double EPSILON = 1e-6;

    /**
     * The panel's vertex stage, in Java: a view-space point to a normalised device coordinate.
     *
     * <p>Written out rather than shared with the shader, because the shader is IR and this is the second
     * opinion. Two implementations of one formula is what makes the comparison below mean anything.
     */
    private static double[] project(double vx, double vy, double vz, double aspect) {
        return new double[]{FOCAL * vx / (aspect * vz), -FOCAL * vy / vz};
    }

    /** The march's primary ray for the pixel at a normalised device coordinate — {@code SdfComposer}'s formula. */
    private static double[] marchRay(double ndcX, double ndcY, double aspect) {
        double u = (ndcX + 1) * 0.5;
        double v = (ndcY + 1) * 0.5;
        double sx = (2 * u - 1) * aspect;
        double sy = 1 - 2 * v;
        return new double[]{sx, sy, FOCAL};
    }

    /**
     * For a canvas point on a panel, the march's ray through the pixel it projects to points back at it.
     *
     * <p>Checked at the corners as well as the centre, and with the camera and the panel both turned, because a
     * rotation that is applied in the wrong order or transposed is exact at the centre of the screen and wrong
     * everywhere else.
     */
    @Test
    void theRayThroughAPanelPixelPointsAtThePanelPoint() {
        double canvasW = 800;
        double canvasH = 450;
        Panel panel = new Panel(1.5, 0.8, 7, 0.3, -0.2, 0.15, 0.004);

        double camX = -0.5;
        double camY = 1.2;
        double camZ = 0.4;
        double camYaw = 0.25;
        double camPitch = 0.1;
        double aspect = 16.0 / 9.0;

        float[] basis = new float[Panel.BASIS_FLOATS];
        panel.viewBasis(canvasW, canvasH, camX, camY, camZ, camYaw, camPitch, basis);

        double[][] points = {{0, 0}, {canvasW, 0}, {0, canvasH}, {canvasW, canvasH},
                {canvasW / 2, canvasH / 2}, {123, 456}};
        for (double[] p : points) {
            double u = p[0];
            double v = p[1];
            double vx = basis[0] + u * basis[3] + v * basis[6];
            double vy = basis[1] + u * basis[4] + v * basis[7];
            double vz = basis[2] + u * basis[5] + v * basis[8];
            assertTrue(vz > 0, "canvas point " + u + "," + v + " landed behind the eye at z=" + vz);

            double[] ndc = project(vx, vy, vz, aspect);
            double[] ray = marchRay(ndc[0], ndc[1], aspect);

            // Parallel and same-signed: the ray is the point scaled by focal/vz, so the cross product vanishes
            // and the dot product is positive. A mirrored axis passes the first check and fails the second.
            double cx = ray[1] * vz - ray[2] * vy;
            double cy = ray[2] * vx - ray[0] * vz;
            double cz = ray[0] * vy - ray[1] * vx;
            double scale = Math.max(1, Math.abs(vx) + Math.abs(vy) + Math.abs(vz));
            assertEquals(0, cx / scale, 1e-5, "ray and point are not parallel in x at " + u + "," + v);
            assertEquals(0, cy / scale, 1e-5, "ray and point are not parallel in y at " + u + "," + v);
            assertEquals(0, cz / scale, 1e-5, "ray and point are not parallel in z at " + u + "," + v);
            assertTrue(ray[0] * vx + ray[1] * vy + ray[2] * vz > 0,
                    "the ray points away from the panel point at " + u + "," + v);
        }
    }

    /**
     * A panel placed dead ahead, unrotated, is centred on the screen — and its canvas origin is up and to the
     * left, which is the assertion that pins canvas y as <em>down</em>.
     */
    @Test
    void theCanvasOriginIsTheTopLeftCorner() {
        Panel panel = Panel.at(0, 0, 5, 0.01);
        float[] basis = new float[Panel.BASIS_FLOATS];
        panel.viewBasis(200, 100, 0, 0, 0, 0, 0, basis);

        // The centre of the canvas is the centre of the screen: the panel is centred on the view axis.
        double cx = basis[0] + 100 * basis[3] + 50 * basis[6];
        double cy = basis[1] + 100 * basis[4] + 50 * basis[7];
        assertEquals(0, cx, EPSILON, "the canvas centre is off the view axis horizontally");
        assertEquals(0, cy, EPSILON, "the canvas centre is off the view axis vertically");

        // The origin corner: left of centre (negative x) and above it (positive y, since world y is up).
        assertTrue(basis[0] < 0, "canvas (0,0) is not to the left");
        assertTrue(basis[1] > 0, "canvas (0,0) is not above centre — canvas y must run down the panel");

        // One canvas unit is unitsPerPixel across, and the sheet is what the canvas says it is.
        assertEquals(0.01, basis[3], 1e-7, "one canvas unit along +x is not unitsPerPixel wide");
        assertEquals(-0.01, basis[7], 1e-7, "one canvas unit along +y does not run down by unitsPerPixel");
        assertEquals(2.0, panel.worldSpan(200), 1e-9);
    }

    /**
     * A panel turned to {@link Panel#facing} a camera is square on to it, whatever the camera is doing.
     *
     * <p>The measurement is the panel's normal in view space, {@code right x down}, which must come out along
     * {@code -z} — pointing back at an eye that looks along {@code +z}. This is the check that the panel's
     * rotation and the camera's inverse are actually inverses: get either one transposed and a billboard tilts.
     */
    @Test
    void aFacingPanelIsSquareOnToTheCamera() {
        double[][] cameras = {{0, 0}, {0.7, 0}, {0, -0.5}, {2.2, 0.4}, {-1.1, -0.9}};
        for (double[] cam : cameras) {
            double camYaw = cam[0];
            double camPitch = cam[1];
            Panel panel = Panel.at(2, -1, 9, 0.005).facing(camYaw, camPitch);

            float[] b = new float[Panel.BASIS_FLOATS];
            panel.viewBasis(300, 200, 0.3, 0.6, -1.0, camYaw, camPitch, b);

            double nx = b[4] * b[8] - b[5] * b[7];
            double ny = b[5] * b[6] - b[3] * b[8];
            double nz = b[3] * b[7] - b[4] * b[6];
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            assertEquals(0, nx / len, 1e-5, "a facing panel is tilted in x at yaw " + camYaw);
            assertEquals(0, ny / len, 1e-5, "a facing panel is tilted in y at pitch " + camPitch);
            assertEquals(-1, nz / len, 1e-5, "a facing panel does not face the eye at " + camYaw + "," + camPitch);
        }
    }

    /** A panel's scale is its own: turning it does not resize it. */
    @Test
    void rotationIsRigid() {
        Panel panel = new Panel(0, 0, 4, 0.9, -0.3, 1.1, 0.02);
        float[] b = new float[Panel.BASIS_FLOATS];
        panel.viewBasis(100, 100, 0, 0, 0, 0.4, 0.2, b);

        double right = Math.sqrt(b[3] * b[3] + b[4] * b[4] + b[5] * b[5]);
        double down = Math.sqrt(b[6] * b[6] + b[7] * b[7] + b[8] * b[8]);
        double dot = b[3] * b[6] + b[4] * b[7] + b[5] * b[8];
        assertEquals(0.02, right, 1e-7, "the panel's x axis changed length under rotation");
        assertEquals(0.02, down, 1e-7, "the panel's y axis changed length under rotation");
        assertEquals(0, dot / (right * down), 1e-6, "the panel's axes are no longer perpendicular");
    }
}
