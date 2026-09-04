package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;

import java.util.List;

/**
 * A lowered surface: the distance expression, plus what the compiler knows about how fast it changes.
 *
 * <p>The bound is the whole point. Sphere-tracing steps by the field's own value, so it converges only if the
 * field never reports more distance than there really is — which holds exactly when {@code |grad d| <= 1}. Every
 * primitive and combinator in {@link Surface} preserves that; an {@link Surface.Implicit} does not, and is
 * normalised on the way in. Carrying the bound explicitly is what lets the compiler tell those cases apart and
 * charge only the second one (docs/surface-compiler.md §3).
 *
 * <h2>Colour</h2>
 *
 * <p>A field may also carry an <b>albedo</b> expression — the surface's linear-RGB colour at the same point.
 * It is {@code null} unless something in the tree actually specified a colour, and that absence is load-bearing:
 * a scene of uncoloured surfaces lowers to precisely the IR it lowered to before colour existed, and the
 * composer emits precisely the shader it emitted before. Generality costs nothing when it is not used, here as
 * everywhere else in this module.
 *
 * <p>Note where the cost lands when it <em>is</em> used. The distance is called once per march step, tens of
 * times per pixel; the albedo is called <b>once</b>, at the hit point, after the march has finished. So the
 * selection arithmetic that picks a colour out of a union — which is roughly a second copy of the distance
 * field — is paid once per pixel rather than per step. That asymmetry is why colour can be a separate function
 * rather than a channel threaded through the march.
 *
 * @param distance  signed distance at {@link Ir#POINT}: negative inside, zero on the surface
 * @param lipschitz an upper bound on {@code |grad distance|} — {@code 1.0} for a true distance field, larger for
 *                  a field that would overshoot, {@link Double#POSITIVE_INFINITY} when nothing is known
 * @param albedo      linear-RGB colour at {@link Ir#POINT} ({@code vec3}), or {@code null} if nothing in the
 *                    surface specified one. May contain {@link Ir#SCENE_ALBEDO} where a surface had no colour
 *                    of its own but shares a combinator with one that did, and may read locals declared by
 *                    {@code albedoLets}
 * @param albedoLets  declarations {@code albedo} reads, in the order they must be emitted. Empty unless the
 *                    colour had something to select between; see {@link Lets} for why selecting without them
 *                    grew as the square of the child count
 */
public record Field(Expr distance, double lipschitz, Expr albedo, List<Statement> albedoLets) {

    /** The bound a true signed-distance field carries. */
    public static final double EXACT = 1.0;

    /** The bound for an expression the compiler cannot vouch for — an un-normalised implicit. */
    public static final double UNKNOWN = Double.POSITIVE_INFINITY;

    public Field {
        if (distance == null) {
            throw new IllegalArgumentException("distance expression must not be null");
        }
        if (!Ir.F32.equals(distance.type())) {
            throw new IllegalArgumentException("a distance must be a scalar float, got " + distance.type());
        }
        if (Double.isNaN(lipschitz) || lipschitz <= 0) {
            throw new IllegalArgumentException("lipschitz bound must be positive, got " + lipschitz);
        }
        if (albedo != null && !Ir.V3.equals(albedo.type())) {
            throw new IllegalArgumentException("an albedo must be a vec3, got " + albedo.type());
        }
        albedoLets = albedoLets == null ? List.of() : List.copyOf(albedoLets);
        if (albedo == null && !albedoLets.isEmpty()) {
            throw new IllegalArgumentException("declarations with no colour to read them");
        }
    }

    /** A colourless field — the shape only, which is what every surface but a painted one produces. */
    public Field(Expr distance, double lipschitz) {
        this(distance, lipschitz, null, List.of());
    }

    /** A field whose colour needs no declarations — a single flat colour, and nothing to select between. */
    public Field(Expr distance, double lipschitz, Expr albedo) {
        this(distance, lipschitz, albedo, List.of());
    }

    /** A field the compiler knows to be a true distance field. */
    public static Field exact(Expr distance) {
        return new Field(distance, EXACT);
    }

    /** Whether anything in the surface specified a colour. */
    public boolean hasAlbedo() {
        return albedo != null;
    }

    /** This field with a different albedo — how a combinator rebuilds one around its children's colours. */
    public Field withAlbedo(Expr albedo) {
        return new Field(distance, lipschitz, albedo, albedoLets);
    }

    /** This field carrying the declarations its colour reads — attached once, when lowering finishes. */
    public Field withAlbedoLets(List<Statement> lets) {
        return new Field(distance, lipschitz, albedo, lets);
    }

    /** Whether this can be sphere-traced as-is without overshooting. */
    public boolean isMarchable() {
        return lipschitz <= EXACT + 1e-9;
    }

    /**
     * This field's distance expression evaluated at some other point expression — the field relocated into a
     * caller's frame. Lets a compiled surface be dropped into IR that was authored around a different variable,
     * which is how it reaches the research harness and anything else that names its own sample point.
     */
    public Expr at(Expr point) {
        return Substitute.point(distance, point);
    }

    /**
     * This field as a standalone {@code float sdf(vec3)} function — the form both backends consume: the fragment
     * shader calls it (once, rather than inlining the field at all eight of its use sites — D12), and the CPU
     * side lowers the same function to query the same surface. One definition, two targets: render == sim.
     */
    public Function asFunction(String name) {
        return new Function(name, new Type.FunctionType(Ir.F32, List.of(Ir.V3)),
                Region.of(new Statement.Return(distance)));
    }

    /**
     * The albedo as a standalone {@code vec3 albedo(vec3)} function, with {@code fallback} filled in wherever a
     * surface without a colour of its own could be the one showing.
     *
     * <p>A function for the same reason the distance is one — it is called from more than one place and inlining
     * it would duplicate a tree that is already the size of the field. Unlike the distance, it is called once
     * per pixel rather than once per march step.
     *
     * @throws IllegalStateException if this field carries no colour; check {@link #hasAlbedo()} first
     */
    public Function albedoFunction(String name, Expr fallback) {
        if (albedo == null) {
            throw new IllegalStateException("this field carries no colour");
        }
        List<Statement> body = new java.util.ArrayList<>(albedoLets.size() + 1);
        for (Statement let : albedoLets) {
            body.add(Substitute.sceneAlbedo(let, fallback));
        }
        body.add(new Statement.Return(Substitute.sceneAlbedo(albedo, fallback)));
        return new Function(name, new Type.FunctionType(Ir.V3, List.of(Ir.V3)), new Region(body));
    }
}
