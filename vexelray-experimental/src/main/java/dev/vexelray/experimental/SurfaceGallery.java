package dev.vexelray.experimental;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Surface;

import java.nio.file.Path;
import java.util.List;

/**
 * Renders the surfaces the design has been arguing about, so they can be looked at instead of reasoned about.
 *
 * <p>Every entry is a {@link Surface} — data, not code — pushed through the harness's shared {@link Raymarcher}
 * at the same camera as every other candidate. Run:
 * {@code mvn -pl vexelray-experimental -am compile exec:exec -Dexp.mainClass=dev.vexelray.experimental.SurfaceGallery}
 *
 * <p>The camera sits at {@code (0, 1.2, -3)} looking down {@code +z}, so everything here is positioned to be in
 * front of it.
 */
public final class SurfaceGallery {

    private static final Expr X = Ir.x(Ir.POINT);
    private static final Expr Y = Ir.y(Ir.POINT);
    private static final Expr Z = Ir.z(Ir.POINT);

    private SurfaceGallery() {
    }

    public static void main(String[] args) {
        Path outDir = Path.of("target", "gallery");
        List<ShapeField> gallery = List.of(
                schwarzP(), spindle(), hardBooleans(), smoothBooleans(), everything());
        ComparisonHarness.standard().run(gallery, outDir);
        System.out.println("gallery -> " + outDir.toAbsolutePath());
    }

    /**
     * {@code sin(x) + sin(y) + sin(z)} — the Schwarz P minimal surface, translated. Infinite in every direction
     * and triply periodic, from an expression with three terms.
     *
     * <p>Given its exact global bound rather than a derived one. {@code |grad f| = |(cos x, cos y, cos z)|}, so
     * {@code sqrt(3)} holds everywhere; derived pointwise, the same surface overshoots by 3496x where all three
     * cosines vanish together.
     */
    private static ShapeField schwarzP() {
        double w = 1.5;   // f(camera) > 0, so the eye sits in a void pocket looking at the labyrinth wall
        Expr f = Ir.add(Ir.add(
                Expr.MathCall.sin(Ir.mul(X, Ir.f(w))),
                Expr.MathCall.sin(Ir.mul(Y, Ir.f(w)))),
                Expr.MathCall.sin(Ir.mul(Z, Ir.f(w))));
        return new SurfaceField("schwarz-p",
                Surface.Implicit.bounded(f, w * Math.sqrt(3)),
                "infinite triply-periodic surface from 3 terms; exact global Lipschitz bound sqrt(3)");
    }

    /**
     * {@code 5*sqrt(length(x,y) + z^2) - 10} — a spindle of revolution with cone points at its poles. The radial
     * term is {@code length}, not {@code sqrt(x*x + y*y)}: the latter differentiates to 0/0 along the entire
     * axis of symmetry, which runs straight through the middle of the shape.
     */
    private static ShapeField spindle() {
        Expr radial = Ir.length(Ir.xz(Ir.POINT));
        Expr f = Ir.sub(Ir.mul(Ir.f(5.0), Ir.sqrt(Ir.add(radial, Ir.mul(Y, Y)))), Ir.f(10.0));
        return new SurfaceField("spindle",
                new Surface.Translate(0.0, 1.15, 2.0, new Surface.Scale(0.30, new Surface.Implicit(f))),
                "algebraic surface of revolution; gradient-normalised, accurate near the surface");
    }

    /** Hard CSG: intersection and difference with creased edges, for comparison with the soft pair below. */
    private static ShapeField hardBooleans() {
        return new SurfaceField("booleans-hard", csg(false),
                "min/max booleans: exact, conservative, creased at every seam");
    }

    /** The same scene through soft-min and soft-max, so every seam becomes a fillet. */
    private static ShapeField smoothBooleans() {
        return new SurfaceField("booleans-smooth", csg(true),
                "soft-min/soft-max booleans: same shapes, filleted seams, still 1-Lipschitz");
    }

    /** Hard and soft CSG over the same three primitives, so the pair differs only in the operators. */
    private static Surface csg(boolean smooth) {
        double k = 12.0;
        Surface ball = new Surface.Sphere(-1.15, 1.0, 1.6, 0.75);
        Surface cube = new Surface.Box(-1.15, 1.0, 1.6, 0.58, 0.58, 0.58);
        Surface rod = new Surface.Capsule(1.15, 1.0, 0.8, 1.15, 1.0, 2.6, 0.30);
        Surface block = new Surface.Box(1.15, 1.0, 1.6, 0.62, 0.62, 0.62);

        Surface lens = smooth ? Surface.smoothIntersection(k, ball, cube) : Surface.intersection(ball, cube);
        Surface carved = smooth ? new Surface.SmoothDifference(k, block, rod)
                                : new Surface.Difference(block, rod);
        return Surface.union(Surface.Plane.ground(), lens, carved);
    }

    /**
     * The whole vocabulary at once: primitives blended into a ground plane, a carve, and a bounded implicit —
     * one distance field, one shader, no stored geometry anywhere in it.
     */
    private static ShapeField everything() {
        Expr wave = Ir.add(Ir.add(
                Expr.MathCall.sin(Ir.mul(X, Ir.f(2.2))),
                Expr.MathCall.sin(Ir.mul(Y, Ir.f(2.2)))),
                Expr.MathCall.sin(Ir.mul(Z, Ir.f(2.2))));
        Surface gyroid = new Surface.Intersection(List.of(
                Surface.Implicit.bounded(wave, 2.2 * Math.sqrt(3)),
                new Surface.Sphere(0.95, 1.15, 2.0, 0.80)));

        Surface scene = Surface.smoothUnion(5.0,
                Surface.Plane.ground(),
                new Surface.Torus(-1.15, 0.55, 1.9, 0.55, 0.18),
                new Surface.Difference(
                        new Surface.Round(0.06, new Surface.Box(-0.15, 0.75, 2.3, 0.42, 0.75, 0.42)),
                        new Surface.Sphere(-0.15, 1.5, 1.85, 0.42)),
                gyroid);
        return new SurfaceField("everything", scene,
                "primitives + smooth union + carve + a periodic implicit clipped to a ball, in one field");
    }
}
