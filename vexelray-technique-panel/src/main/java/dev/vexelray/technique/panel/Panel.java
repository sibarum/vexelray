package dev.vexelray.technique.panel;

/**
 * Where a canvas hangs in the world: the plane its coordinates live on, and how big one of them is.
 *
 * <p>A {@code Canvas} is authored in canvas units and knows nothing about the world. A {@code Panel} says what
 * one of those units <em>is</em> out there — {@link #unitsPerPixel} world units across — and where the sheet of
 * them is. Everything else follows: a canvas of 800x450 at {@code unitsPerPixel = 0.002} is a screen 1.6 by 0.9
 * metres, hanging centred on {@link #x}, {@link #y}, {@link #z}.
 *
 * <h2>Orientation is the camera's own vocabulary, deliberately</h2>
 *
 * <p>{@link #yaw} and {@link #pitch} mean here exactly what they mean on {@code SdfRaymarchTechnique.camera}:
 * the same axes, the same signs, the same order (pitch about x, then yaw about y). A panel at the camera's own
 * yaw and pitch therefore faces it square on — which is what {@link #facing} is, and why it is one line rather
 * than a billboarding routine. Two rotation conventions in one frame is a bug that looks like a modelling
 * mistake, and the cheapest way not to have one is not to invent a second.
 *
 * <p>At zero rotation the panel's {@code +x} runs along world {@code +x}, its {@code +y} — canvas y, which
 * points <em>down</em> — runs along world {@code -y}, and its face looks back along {@code -z}, toward a camera
 * at the origin looking down {@code +z}. {@link #roll} turns it in its own plane, about its normal, and is
 * applied first so that yaw and pitch keep meaning what they mean.
 *
 * <h2>A plane, not a quad</h2>
 *
 * <p>Nothing here describes a rectangle. The panel places a coordinate system, and what gets drawn in it is
 * whatever the canvas drew — which may cover the sheet, or be a single line, or leave most of it transparent.
 * There is no quad geometry anywhere in this module, which is the property that makes the drawing crisp at any
 * distance: the shapes are the geometry.
 *
 * @param x             world x of the canvas's centre
 * @param y             world y of the canvas's centre
 * @param z             world z of the canvas's centre
 * @param yaw           rotation about the world y axis, radians, as the camera means it
 * @param pitch         rotation about the world x axis, radians, as the camera means it
 * @param roll          rotation in the panel's own plane, radians, applied before yaw and pitch
 * @param unitsPerPixel world units spanned by one canvas unit; the panel's scale
 */
public record Panel(double x, double y, double z, double yaw, double pitch, double roll, double unitsPerPixel) {

    /** How many floats {@link #viewBasis} writes: origin, right, down, three each. */
    public static final int BASIS_FLOATS = 9;

    public Panel {
        if (!(unitsPerPixel > 0) || !Double.isFinite(unitsPerPixel)) {
            throw new IllegalArgumentException("unitsPerPixel must be finite and positive, got " + unitsPerPixel);
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("a panel's centre must be finite, got " + x + ", " + y + ", " + z);
        }
    }

    /** A panel centred at {@code (x,y,z)}, unrotated — facing a camera that has not turned. */
    public static Panel at(double x, double y, double z, double unitsPerPixel) {
        return new Panel(x, y, z, 0, 0, 0, unitsPerPixel);
    }

    /** The same panel moved to {@code (x,y,z)}. */
    public Panel at(double x, double y, double z) {
        return new Panel(x, y, z, yaw, pitch, roll, unitsPerPixel);
    }

    /** The same panel turned to {@code (yaw, pitch)}, keeping its roll. */
    public Panel turned(double yaw, double pitch) {
        return new Panel(x, y, z, yaw, pitch, roll, unitsPerPixel);
    }

    /** The same panel rolled in its own plane. */
    public Panel rolled(double roll) {
        return new Panel(x, y, z, yaw, pitch, roll, unitsPerPixel);
    }

    /** The same panel at a different scale. */
    public Panel scaled(double unitsPerPixel) {
        return new Panel(x, y, z, yaw, pitch, roll, unitsPerPixel);
    }

    /**
     * The same panel turned square on to a camera at {@code (camYaw, camPitch)} — a billboard, for this frame.
     *
     * <p>One line because the two conventions are the same one; see the class note.
     */
    public Panel facing(double camYaw, double camPitch) {
        return new Panel(x, y, z, camYaw, camPitch, roll, unitsPerPixel);
    }

    /** World units spanned by a canvas {@code canvasUnits} across — the panel's width, given the canvas's. */
    public double worldSpan(double canvasUnits) {
        return canvasUnits * unitsPerPixel;
    }

    /**
     * Write this panel's affine map from canvas coordinates to <b>view space</b>: the position of the canvas's
     * {@code (0,0)} corner, then the view-space vector of one canvas unit along {@code +x}, then one along
     * {@code +y} (down the canvas). Nine floats, in that order.
     *
     * <p>A canvas point {@code (u,v)} is then {@code origin + u*right + v*down}, which is all the vertex stage
     * needs and all it is given: the rotation is resolved on the CPU, once per frame, rather than as nine
     * transcendental functions per vertex. It is an affine map because a panel is flat — the one property that
     * makes the whole of this module cheaper than a general transform.
     *
     * <p><b>View space</b>, not world: the camera's basis, where the eye is the origin and {@code +z} is
     * forward, which is the space the projection and the depth convention are both written in.
     *
     * <p>Allocates nothing: it is called once per frame from a technique's record path, where the house rule is
     * that nothing allocates, so it writes through {@code out} rather than returning vectors.
     *
     * @param canvasWidth  the canvas's width in canvas units — needed because the panel is centred on its sheet
     * @param canvasHeight the canvas's height in canvas units
     * @param out          receives {@link #BASIS_FLOATS} floats; may be longer, the rest is untouched
     */
    public void viewBasis(double canvasWidth, double canvasHeight,
                          double camX, double camY, double camZ, double camYaw, double camPitch,
                          float[] out) {
        if (out.length < BASIS_FLOATS) {
            throw new IllegalArgumentException("viewBasis writes " + BASIS_FLOATS + " floats and was given "
                    + out.length);
        }
        double cr = Math.cos(roll);
        double sr = Math.sin(roll);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);

        // The panel's own axes, rotated into the world by roll-then-pitch-then-yaw and scaled to the panel's
        // size. Canvas +y is down, so the unrotated down axis is world -y; everything else about the two frames
        // already agrees. Written out longhand from rotate() applied to (1,0,0) and (0,-1,0), because the two
        // constants collapse most of it and a loop over two vectors would need somewhere to put them.
        double rx = (cr * cy + sr * sp * sy) * unitsPerPixel;
        double ry = (sr * cp) * unitsPerPixel;
        double rz = (sr * sp * cy - cr * sy) * unitsPerPixel;
        double dx = (sr * cy - cr * sp * sy) * unitsPerPixel;
        double dy = (-cr * cp) * unitsPerPixel;
        double dz = (-cr * sp * cy - sr * sy) * unitsPerPixel;

        // The centre is where the panel is placed, so the canvas's corner is half a sheet back along each axis.
        // Relative to the eye, because view space has the eye at its origin.
        double hw = 0.5 * canvasWidth;
        double hh = 0.5 * canvasHeight;
        double ox = x - hw * rx - hh * dx - camX;
        double oy = y - hw * ry - hh * dy - camY;
        double oz = z - hw * rz - hh * dz - camZ;

        double ccp = Math.cos(camPitch);
        double csp = Math.sin(camPitch);
        double ccy = Math.cos(camYaw);
        double csy = Math.sin(camYaw);
        toView(ox, oy, oz, ccp, csp, ccy, csy, out, 0);
        toView(rx, ry, rz, ccp, csp, ccy, csy, out, 3);
        toView(dx, dy, dz, ccp, csp, ccy, csy, out, 6);
    }

    /**
     * The camera's rotation, run backwards: a world vector into the camera's frame, written to {@code out[at]}.
     *
     * <p>The inverse of what the march does to a ray, and spelled out rather than shared with it — the march
     * turns a screen direction into the world and this turns the world into the camera's frame. That the two are
     * transposes of each other is the thing worth being able to see side by side.
     */
    private static void toView(double vx, double vy, double vz,
                               double cp, double sp, double cy, double sy, float[] out, int at) {
        double ax = vx * cy - vz * sy;
        double az = vx * sy + vz * cy;
        out[at] = (float) ax;
        out[at + 1] = (float) (vy * cp + az * sp);
        out[at + 2] = (float) (az * cp - vy * sp);
    }
}
