package dev.vexelray.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The two traversals every use of {@link Scalar} needs: visit each number in a tree, and rebuild a tree with
 * each number replaced.
 *
 * <p>One switch each, in one place, because the alternative is the same thirty cases written out again in
 * {@link ParamBlock}, in {@link Surface#shaderKey()}, and in whatever comes next — and a node added to
 * {@link Surface} would then be quietly missed by all but the one whose switch the compiler checks.
 *
 * <p>Order is fixed and depth-first: a node's own numbers in declaration order, then its children in theirs.
 * Slot assignment reads off this order, so it is part of the contract rather than an accident of the code —
 * two identically-shaped surfaces must lower to the same block, and that is what makes the shader key work.
 */
final class Scalars {

    private Scalars() {
    }

    /** Visit every number in {@code surface}, depth-first, in declaration order. */
    static void forEach(Surface surface, Consumer<Scalar> visit) {
        switch (surface) {
            case Surface.Sphere s -> {
                visit.accept(s.cx());
                visit.accept(s.cy());
                visit.accept(s.cz());
                visit.accept(s.radius());
            }
            case Surface.Box b -> {
                visit.accept(b.cx());
                visit.accept(b.cy());
                visit.accept(b.cz());
                visit.accept(b.hx());
                visit.accept(b.hy());
                visit.accept(b.hz());
            }
            case Surface.Plane p -> visit.accept(p.offset());
            case Surface.Capsule c -> {
                visit.accept(c.ax());
                visit.accept(c.ay());
                visit.accept(c.az());
                visit.accept(c.bx());
                visit.accept(c.by());
                visit.accept(c.bz());
                visit.accept(c.radius());
            }
            case Surface.Torus t -> {
                visit.accept(t.cx());
                visit.accept(t.cy());
                visit.accept(t.cz());
                visit.accept(t.major());
                visit.accept(t.minor());
            }
            // A stroke's vertices are plain doubles — see Surface.Stroke on why the spine is solved in Java.
            case Surface.Stroke ignored -> {
            }
            case Surface.Translate t -> {
                visit.accept(t.dx());
                visit.accept(t.dy());
                visit.accept(t.dz());
                forEach(t.of(), visit);
            }
            case Surface.Scale s -> {
                visit.accept(s.factor());
                forEach(s.of(), visit);
            }
            case Surface.Rotate r -> {
                visit.accept(r.angle());
                forEach(r.of(), visit);
            }
            case Surface.Mirror m -> forEach(m.of(), visit);
            case Surface.Repeat r -> {
                visit.accept(r.x().period());
                visit.accept(r.y().period());
                visit.accept(r.z().period());
                forEach(r.of(), visit);
            }
            case Surface.PolarRepeat r -> forEach(r.of(), visit);
            case Surface.Twist t -> {
                visit.accept(t.rate());
                visit.accept(t.radius());
                forEach(t.of(), visit);
            }
            case Surface.Bend b -> {
                visit.accept(b.rate());
                visit.accept(b.extent());
                forEach(b.of(), visit);
            }
            case Surface.Union u -> u.of().forEach(child -> forEach(child, visit));
            case Surface.Intersection i -> i.of().forEach(child -> forEach(child, visit));
            case Surface.Difference d -> {
                forEach(d.from(), visit);
                forEach(d.remove(), visit);
            }
            case Surface.SmoothUnion s -> {
                visit.accept(s.sharpness());
                s.of().forEach(child -> forEach(child, visit));
            }
            case Surface.SmoothIntersection s -> {
                visit.accept(s.sharpness());
                s.of().forEach(child -> forEach(child, visit));
            }
            case Surface.SmoothDifference s -> {
                visit.accept(s.sharpness());
                forEach(s.from(), visit);
                forEach(s.remove(), visit);
            }
            case Surface.Shell s -> {
                visit.accept(s.thickness());
                forEach(s.of(), visit);
            }
            case Surface.Round r -> {
                visit.accept(r.radius());
                forEach(r.of(), visit);
            }
            case Surface.Implicit ignored -> {
            }
        }
    }

    /**
     * {@code surface} with {@code f} applied to every number in it, in the same order {@link #forEach} visits
     * them. Untouched subtrees are rebuilt rather than shared, which costs nothing at the one size this is
     * called on — a whole tree, once, to make a key. Sharing is P5's business.
     */
    static Surface map(Surface surface, UnaryOperator<Scalar> f) {
        return switch (surface) {
            case Surface.Sphere s ->
                    new Surface.Sphere(f.apply(s.cx()), f.apply(s.cy()), f.apply(s.cz()), f.apply(s.radius()));
            case Surface.Box b -> new Surface.Box(f.apply(b.cx()), f.apply(b.cy()), f.apply(b.cz()),
                    f.apply(b.hx()), f.apply(b.hy()), f.apply(b.hz()));
            case Surface.Plane p -> new Surface.Plane(p.nx(), p.ny(), p.nz(), f.apply(p.offset()));
            case Surface.Capsule c -> new Surface.Capsule(f.apply(c.ax()), f.apply(c.ay()), f.apply(c.az()),
                    f.apply(c.bx()), f.apply(c.by()), f.apply(c.bz()), f.apply(c.radius()));
            case Surface.Torus t -> new Surface.Torus(f.apply(t.cx()), f.apply(t.cy()), f.apply(t.cz()),
                    f.apply(t.major()), f.apply(t.minor()));
            case Surface.Stroke s -> s;
            case Surface.Translate t -> new Surface.Translate(
                    f.apply(t.dx()), f.apply(t.dy()), f.apply(t.dz()), map(t.of(), f));
            case Surface.Scale s -> new Surface.Scale(f.apply(s.factor()), map(s.of(), f));
            case Surface.Rotate r ->
                    new Surface.Rotate(r.ax(), r.ay(), r.az(), f.apply(r.angle()), map(r.of(), f));
            case Surface.Mirror m -> new Surface.Mirror(m.x(), m.y(), m.z(), map(m.of(), f));
            case Surface.Repeat r -> new Surface.Repeat(
                    axis(r.x(), f), axis(r.y(), f), axis(r.z(), f), map(r.of(), f));
            case Surface.PolarRepeat r -> new Surface.PolarRepeat(r.count(), map(r.of(), f));
            case Surface.Twist t ->
                    new Surface.Twist(f.apply(t.rate()), f.apply(t.radius()), map(t.of(), f));
            case Surface.Bend b -> new Surface.Bend(f.apply(b.rate()), f.apply(b.extent()), map(b.of(), f));
            case Surface.Union u -> new Surface.Union(mapAll(u.of(), f));
            case Surface.Intersection i -> new Surface.Intersection(mapAll(i.of(), f));
            case Surface.Difference d -> new Surface.Difference(map(d.from(), f), map(d.remove(), f));
            case Surface.SmoothUnion s ->
                    new Surface.SmoothUnion(f.apply(s.sharpness()), mapAll(s.of(), f));
            case Surface.SmoothIntersection s ->
                    new Surface.SmoothIntersection(f.apply(s.sharpness()), mapAll(s.of(), f));
            case Surface.SmoothDifference s -> new Surface.SmoothDifference(
                    f.apply(s.sharpness()), map(s.from(), f), map(s.remove(), f));
            case Surface.Shell s -> new Surface.Shell(f.apply(s.thickness()), map(s.of(), f));
            case Surface.Round r -> new Surface.Round(f.apply(r.radius()), map(r.of(), f));
            case Surface.Implicit i -> i;
        };
    }

    private static Surface.Repeat.Axis axis(Surface.Repeat.Axis axis, UnaryOperator<Scalar> f) {
        // An axis that does not repeat carries a zero period, and rewriting that into a parameter would turn
        // "does not repeat" into "repeats by an amount the host chooses", which is a different node.
        return axis.repeats()
                ? new Surface.Repeat.Axis(f.apply(axis.period()), axis.from(), axis.to())
                : axis;
    }

    private static List<Surface> mapAll(List<Surface> children, UnaryOperator<Scalar> f) {
        List<Surface> mapped = new ArrayList<>(children.size());
        for (Surface child : children) {
            mapped.add(map(child, f));
        }
        return mapped;
    }
}
