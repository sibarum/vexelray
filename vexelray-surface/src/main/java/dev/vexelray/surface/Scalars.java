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
     * The children of {@code surface}, in declaration order — empty for a leaf.
     *
     * <p>One switch that several passes borrow instead of writing their own. A case added to {@link Surface}
     * and missed here is missed by all of them at once, which is a worse failure than missing it in one; the
     * defence is that this switch is exhaustive over a sealed type, so the compiler will not let it be
     * missed at all.
     */
    static List<Surface> children(Surface surface) {
        return switch (surface) {
            case Surface.Sphere ignored -> List.of();
            case Surface.Box ignored -> List.of();
            case Surface.Plane ignored -> List.of();
            case Surface.Capsule ignored -> List.of();
            case Surface.Torus ignored -> List.of();
            case Surface.Stroke ignored -> List.of();
            case Surface.Implicit ignored -> List.of();
            case Surface.Translate t -> List.of(t.of());
            case Surface.Scale s -> List.of(s.of());
            case Surface.Rotate r -> List.of(r.of());
            case Surface.Mirror m -> List.of(m.of());
            case Surface.Repeat r -> List.of(r.of());
            case Surface.PolarRepeat r -> List.of(r.of());
            case Surface.Twist t -> List.of(t.of());
            case Surface.Bend b -> List.of(b.of());
            case Surface.Shell s -> List.of(s.of());
            case Surface.Round r -> List.of(r.of());
            case Surface.Difference d -> List.of(d.from(), d.remove());
            case Surface.SmoothDifference d -> List.of(d.from(), d.remove());
            case Surface.Union u -> u.of();
            case Surface.Intersection i -> i.of();
            case Surface.SmoothUnion s -> s.of();
            case Surface.SmoothIntersection s -> s.of();
        };
    }

    /**
     * {@code surface} with {@code f} applied to every number in it, <b>keeping every node's identity</b>.
     *
     * <p>Identity is preserved because an edit that changes a number does not make a different object: the
     * sphere whose radius moved is the sphere that was selected, and it must still be after (R13). Only
     * {@link #canonical} deliberately throws identity away, and only to make a key.
     */
    static Surface map(Surface surface, UnaryOperator<Scalar> f) {
        return map(surface, f, id -> id);
    }

    /**
     * {@code surface} with its numbers mapped and <b>every identity replaced by the node's position</b> in
     * the walk — the form {@link Surface#shaderKey()} compares.
     *
     * <p>Position rather than nothing, because the alternative is worse in both directions: keeping the ids
     * makes two identical designs two shaders, and dropping them entirely would make a tree and its own
     * subtree indistinguishable to a reader of the key.
     */
    static Surface canonical(Surface surface, UnaryOperator<Scalar> f) {
        long[] next = {0};
        return map(surface, f, id -> NodeId.of(next[0]++));
    }

    /**
     * {@code surface} with every number and every identity mapped, depth-first, a node before its children.
     *
     * <p>Untouched subtrees are rebuilt rather than shared, which costs nothing at the sizes this is called
     * on — a whole tree, once, to make a key. Sharing is P5's business.
     */
    static Surface map(Surface surface, UnaryOperator<Scalar> f, UnaryOperator<NodeId> ids) {
        return switch (surface) {
            case Surface.Sphere s -> new Surface.Sphere(ids.apply(s.id()),
                    f.apply(s.cx()), f.apply(s.cy()), f.apply(s.cz()), f.apply(s.radius()));
            case Surface.Box b -> new Surface.Box(ids.apply(b.id()),
                    f.apply(b.cx()), f.apply(b.cy()), f.apply(b.cz()),
                    f.apply(b.hx()), f.apply(b.hy()), f.apply(b.hz()));
            case Surface.Plane p ->
                    new Surface.Plane(ids.apply(p.id()), p.nx(), p.ny(), p.nz(), f.apply(p.offset()));
            case Surface.Capsule c -> new Surface.Capsule(ids.apply(c.id()),
                    f.apply(c.ax()), f.apply(c.ay()), f.apply(c.az()),
                    f.apply(c.bx()), f.apply(c.by()), f.apply(c.bz()), f.apply(c.radius()));
            case Surface.Torus t -> new Surface.Torus(ids.apply(t.id()),
                    f.apply(t.cx()), f.apply(t.cy()), f.apply(t.cz()),
                    f.apply(t.major()), f.apply(t.minor()));
            case Surface.Stroke s -> new Surface.Stroke(ids.apply(s.id()), s.through(), s.segmentsPerCorner());
            case Surface.Translate t -> new Surface.Translate(ids.apply(t.id()),
                    f.apply(t.dx()), f.apply(t.dy()), f.apply(t.dz()), map(t.of(), f, ids));
            case Surface.Scale s ->
                    new Surface.Scale(ids.apply(s.id()), f.apply(s.factor()), map(s.of(), f, ids));
            case Surface.Rotate r -> new Surface.Rotate(ids.apply(r.id()),
                    r.ax(), r.ay(), r.az(), f.apply(r.angle()), map(r.of(), f, ids));
            case Surface.Mirror m ->
                    new Surface.Mirror(ids.apply(m.id()), m.x(), m.y(), m.z(), map(m.of(), f, ids));
            case Surface.Repeat r -> new Surface.Repeat(ids.apply(r.id()),
                    axis(r.x(), f), axis(r.y(), f), axis(r.z(), f), map(r.of(), f, ids));
            case Surface.PolarRepeat r ->
                    new Surface.PolarRepeat(ids.apply(r.id()), r.count(), map(r.of(), f, ids));
            case Surface.Twist t -> new Surface.Twist(ids.apply(t.id()),
                    f.apply(t.rate()), f.apply(t.radius()), map(t.of(), f, ids));
            case Surface.Bend b -> new Surface.Bend(ids.apply(b.id()),
                    f.apply(b.rate()), f.apply(b.extent()), map(b.of(), f, ids));
            case Surface.Union u -> new Surface.Union(ids.apply(u.id()), mapAll(u.of(), f, ids));
            case Surface.Intersection i ->
                    new Surface.Intersection(ids.apply(i.id()), mapAll(i.of(), f, ids));
            case Surface.Difference d -> new Surface.Difference(ids.apply(d.id()),
                    map(d.from(), f, ids), map(d.remove(), f, ids));
            case Surface.SmoothUnion s -> new Surface.SmoothUnion(ids.apply(s.id()),
                    f.apply(s.sharpness()), mapAll(s.of(), f, ids));
            case Surface.SmoothIntersection s -> new Surface.SmoothIntersection(ids.apply(s.id()),
                    f.apply(s.sharpness()), mapAll(s.of(), f, ids));
            case Surface.SmoothDifference s -> new Surface.SmoothDifference(ids.apply(s.id()),
                    f.apply(s.sharpness()), map(s.from(), f, ids), map(s.remove(), f, ids));
            case Surface.Shell s ->
                    new Surface.Shell(ids.apply(s.id()), f.apply(s.thickness()), map(s.of(), f, ids));
            case Surface.Round r ->
                    new Surface.Round(ids.apply(r.id()), f.apply(r.radius()), map(r.of(), f, ids));
            case Surface.Implicit i ->
                    new Surface.Implicit(ids.apply(i.id()), i.f(), i.lipschitzBound());
        };
    }

    private static Surface.Repeat.Axis axis(Surface.Repeat.Axis axis, UnaryOperator<Scalar> f) {
        // An axis that does not repeat carries a zero period, and rewriting that into a parameter would turn
        // "does not repeat" into "repeats by an amount the host chooses", which is a different node.
        return axis.repeats()
                ? new Surface.Repeat.Axis(f.apply(axis.period()), axis.from(), axis.to())
                : axis;
    }

    private static List<Surface> mapAll(List<Surface> children, UnaryOperator<Scalar> f,
                                        UnaryOperator<NodeId> ids) {
        List<Surface> mapped = new ArrayList<>(children.size());
        for (Surface child : children) {
            mapped.add(map(child, f, ids));
        }
        return mapped;
    }
}
