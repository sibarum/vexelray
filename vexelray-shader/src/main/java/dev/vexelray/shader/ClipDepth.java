package dev.vexelray.shader;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Statement;
import dev.vexelray.ir.Ir;

/**
 * <b>The projection convention two techniques must share to occlude each other.</b> A near and a far plane,
 * and the one mapping from a camera-space distance to the {@code [0,1]} clip depth a Vulkan depth attachment
 * stores.
 *
 * <h2>Why this is a type rather than two numbers in a shader</h2>
 *
 * <p>A shared depth attachment does not make two techniques agree about depth; it only makes them write to the
 * same place. Agreement is a <em>convention</em> — the same near plane, the same far plane, the same curve
 * between them, and the same answer to whether "depth" means distance to the eye or distance to the image
 * plane. Two techniques that each picked their own would produce a picture where one consistently wins, and it
 * would read as a bug in whichever one lost rather than as the disagreement it is.
 *
 * <p>So the convention is a value, passed to whatever needs it, and this javadoc is the place it is written
 * down. A marched technique builds its {@code gl_FragDepth} from {@link #ofRadial}; a rasterising technique
 * builds its projection matrix from the same {@link #near} and {@link #far}. Neither invents a number.
 *
 * <h2>Planar, not radial — the mistake this type exists to prevent</h2>
 *
 * <p>A sphere-tracer's {@code t} is the distance travelled along a <em>unit</em> ray, so it is the radial
 * distance from the eye. A rasteriser's depth is the view-space z: the distance to the plane through the
 * camera's forward axis. Those are the same number only at the exact centre of the screen, and they diverge
 * with the angle off the forward axis — by 1/cos, which at the corner of a 90° frame is a factor of about 1.4.
 *
 * <p>Writing {@code t} into a depth buffer a rasteriser also writes therefore does not merely add error, it
 * adds error <em>shaped like a lens</em>: a flat marched wall bulges toward the camera at the edges of the
 * screen and pokes through a rasterised one that is genuinely in front of it. The failure looks like bad
 * modelling, or like a near-plane problem, and it is neither. {@link #ofRadial} takes the cosine so that this
 * conversion is a parameter a caller must supply rather than a step they can forget.
 *
 * <h2>The curve</h2>
 *
 * <p>Standard Vulkan perspective depth, which is {@code z_ndc = A - B/z_view} with {@code A = far/(far-near)}
 * and {@code B = far*near/(far-near)} — zero at the near plane, one at the far plane, and hyperbolic between
 * them, so precision concentrates near the camera. Both coefficients fold to constants at compose time, so the
 * per-pixel cost is one divide and one subtract.
 *
 * <p>Not reversed-Z, deliberately. Reversed-Z is the better use of a float depth buffer and this engine will
 * probably want it, but it is a change to the clear value, the compare op, and this curve <em>together</em> —
 * {@code DepthAttachment.CLEAR_DEPTH} says the same thing from the other side, in the module that owns
 * the image. Half of it is worse than neither half.
 *
 * @param near distance to the near plane. Anything nearer clamps to 0 and is drawn in front of everything;
 *             the hyperbolic curve means this number, not {@link #far}, is what governs depth precision
 * @param far  distance to the far plane, mapping to 1. For a marched scene this should be the march's own
 *             {@code farPlane}: a ray that gave up is exactly a ray that reached the far plane, and if the two
 *             numbers disagree then the sky is at a depth no geometry could have occupied
 */
public record ClipDepth(double near, double far) {

    /**
     * The values a scene gets when it does not say: 0.05 near, 120 far.
     *
     * <p>The far plane is {@code MarchSettings.DEFAULT.farPlane()}, which is not a coincidence and is the
     * point of the field's javadoc above. The near plane is a judgement: small enough that a first-person
     * camera can put its face against a wall without the wall vanishing, large enough that the hyperbolic
     * curve is not spending all its precision in the first few centimetres.
     */
    public static final ClipDepth DEFAULT = new ClipDepth(0.05, 120.0);

    /** The clip depth of the far plane, and so what a ray that hit nothing writes. */
    public static final double FAR_DEPTH = 1.0;

    public ClipDepth {
        if (!(near > 0) || !Double.isFinite(near)) {
            throw new IllegalArgumentException("near must be finite and positive, got " + near);
        }
        if (!(far > near) || !Double.isFinite(far)) {
            throw new IllegalArgumentException("far must be finite and greater than near " + near + ", got " + far);
        }
    }

    /** {@code far / (far - near)} — the constant the curve approaches as the view distance grows. */
    private double a() {
        return far / (far - near);
    }

    /** {@code far * near / (far - near)} — the numerator of the hyperbolic term. */
    private double b() {
        return far * near / (far - near);
    }

    /**
     * The <b>clip-space z</b> a rasterised vertex must emit so that, after the perspective divide by
     * {@code w = viewZ}, its interpolated depth is exactly {@link #ofViewZ}.
     *
     * <p>This is the raster half of the convention the march writes by hand, and it exists because the two
     * halves have to agree or the picture is wrong in a way that reads as a modelling mistake. {@link #ofViewZ}
     * is {@code a - b/viewZ}; multiplying through by {@code viewZ} gives {@code a*viewZ - b}, which is
     * <em>affine</em> in the view position — and an affine function of position is precisely what a rasteriser's
     * interpolation of {@code z/w} reproduces exactly. So a technique that hands this to {@code gl_Position.z}
     * gets the march's depth curve from fixed-function hardware, and keeps early-z: the alternative, writing
     * {@code gl_FragDepth} from an interpolated view depth, computes the same number and forfeits the early
     * test to do it.
     *
     * <p><b>What differs from {@link #ofViewZ}, deliberately.</b> No clamp. A vertex nearer than {@link #near}
     * produces a negative clip z and is <em>clipped</em> by the hardware rather than pinned to the near plane,
     * which is the correct behaviour for geometry and the only one available to a rasteriser. A march has no
     * geometry to clip and so clamps instead; the two do not disagree about any point they can both see.
     *
     * @param viewZ the view-space z of the vertex — the same quantity {@link #ofViewZ} takes, before the divide
     */
    public Expr clipZ(Expr viewZ) {
        return Ir.sub(Ir.mul(Ir.f(a()), viewZ), Ir.f(b()));
    }

    /**
     * Clip depth for a <b>planar</b> distance: the view-space z of a point, measured along the camera's
     * forward axis. What a rasteriser's interpolated depth already is.
     *
     * <p>Clamped to {@code [0,1]}. Vulkan would clamp the written value to the viewport's depth range anyway,
     * so this is not correctness against the hardware — it is correctness against the <em>reader</em>: a
     * caller comparing this expression's value against a threshold of its own, or a future CPU evaluation of
     * the same function, would otherwise see a negative depth for a point in front of the near plane and have
     * to know that the GPU silently fixes it.
     *
     * @param viewZ distance from the camera along its forward axis; must be positive (a point behind the eye
     *              has no depth in this convention, and nothing in a forward march can produce one)
     */
    public Expr ofViewZ(Expr viewZ) {
        Expr depth = Ir.sub(Ir.f(a()), Ir.div(Ir.f(b()), viewZ));
        return Ir.clamp(depth, Ir.f(0.0), Ir.f(FAR_DEPTH));
    }

    /**
     * Clip depth for a <b>radial</b> distance: {@code t} along a unit ray, which is what a sphere-tracer has.
     *
     * <p>{@code cosForward} is the cosine of the angle between that ray and the camera's forward axis, which
     * converts the radial distance to the planar one — see the class note on why leaving it out is a bug that
     * looks like something else. For a pinhole camera whose image plane is at {@code focal} in a basis where
     * the screen coordinate is {@code (sx, sy)}, it is {@code focal / length(vec3(sx, sy, focal))}: a constant
     * per pixel, computed once outside the march.
     *
     * @param t          distance travelled along a unit ray
     * @param cosForward cosine of the ray's angle to the camera's forward axis, in {@code (0,1]}
     */
    public Expr ofRadial(Expr t, Expr cosForward) {
        return ofViewZ(Ir.mul(t, cosForward));
    }

    /**
     * The statement a fragment writes to place itself at a radial distance {@code t}.
     *
     * <p>Offered as a statement, not only an expression, because the built-in it writes carries an obligation
     * that an expression cannot express: <b>a fragment shader that writes {@code gl_FragDepth} on one path
     * must write it on every path it can take</b>, or the value is undefined on the paths that did not. That
     * failure has no validator and no visible symptom until geometry starts occluding intermittently. Having
     * the write be one named call makes the missing branch easier to see — and {@link #missed()} exists so the
     * branch that hit nothing has something to say rather than nothing.
     */
    public Statement write(Expr t, Expr cosForward) {
        return new Statement.BuiltinWrite(Builtin.FRAG_DEPTH, ofRadial(t, cosForward));
    }

    /**
     * The write for a fragment that hit nothing: the far plane.
     *
     * <p>The far plane rather than skipping the write, for the reason {@link #write} gives. It is also the
     * honest value: a ray that reached {@link #far} without hitting anything has established that there is
     * nothing between the eye and the far plane along it, which is exactly what a depth of 1 means.
     */
    public Statement missed() {
        return new Statement.BuiltinWrite(Builtin.FRAG_DEPTH, Ir.f(FAR_DEPTH));
    }
}
