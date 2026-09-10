package dev.vexelray.surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which subtrees the lowering would write out more than once — decided before any of it is written.
 *
 * <p>Two kinds of repetition, and they look nothing alike in the tree:
 *
 * <ul>
 *   <li><b>An operator that samples its child several times.</b> A {@link Surface.Repeat} folds a point into
 *       its own cell <em>and</em> into the nearer neighbour along every repeated axis, so it lowers its child
 *       2ⁿ times; a {@link Surface.PolarRepeat} lowers its child twice, for the same reason. Nothing in the
 *       authored tree says so — the child appears once and is written eight times.</li>
 *   <li><b>A subtree the author used twice.</b> Records have structural equality, so this costs a lookup to
 *       notice, and the same lowering serves both sites.</li>
 * </ul>
 *
 * <p>So the count here is not how many times a node appears; it is how many times it would be
 * <b>lowered</b> — each occurrence multiplied by the sampling of every operator above it. Anything counted
 * twice or more becomes a function.
 *
 * <h2>What is left out, and why</h2>
 *
 * <p>A subtree carrying colour is never shared. A function returns a distance and nothing else, so abstracting
 * a coloured child would drop the colour on the floor — silently, since the resulting field is perfectly
 * valid. Colour selection also needs each site's distance bound into the colour program by name (see
 * {@link Lets}), which is a per-site thing where a function is a one-copy thing. Painted strokes are the
 * surfaces that carry colour today, and they are rarely the ones inside a repeat; when they are, they lower
 * exactly as they did before P1.
 *
 * <h2>Identity, not equality, on the way out</h2>
 *
 * <p>Counting is structural — that is the point of the second case — but the answer is a set of the actual
 * node <em>instances</em> in the tree. The compiler asks "is this node shared?" at every site it visits, and
 * a structural lookup there would re-hash a whole subtree each time, which is quadratic in a deep tree. The
 * sharing survives that: two equal-but-distinct instances both land in the set, and one structural lookup in
 * the compiler's own memo gives them one function.
 */
final class Shared {

    private Shared() {
    }

    /**
     * Which nodes to emit as functions, and what each one's shape is.
     *
     * <p>Two answers rather than one because the compiler needs both, and they are asked by different keys.
     * "Is this node shared?" is asked of an instance — a node is or is not one of the ones this walk picked
     * out. "Have I emitted this already?" is asked of a <em>shape</em>, because two separately authored
     * copies of one subtree are two instances that must reach one function; keyed by instance, each would
     * get its own, which is a size regression with nothing to fail.
     *
     * @param shared the node instances worth emitting once and calling
     * @param shapes each node paired with itself minus its identities, for the compiler's memo to key on
     */
    record Sharing(Set<Surface> shared, Map<Surface, Surface> shapes) {

        boolean shares(Surface node) {
            return shared.contains(node);
        }

        /** This node as a shape — equal to any other node that lowers to the same thing. */
        Surface shapeOf(Surface node) {
            return shapes.get(node);
        }
    }

    /** The node instances worth emitting once and calling, with the shape of each. */
    static Sharing of(Surface root) {
        // Counted on the tree with its identities flattened away, because identity is exactly what the
        // distance field does not depend on. Two spheres of one radius are two objects and one shape, and it
        // is the shape that decides whether a function is worth emitting. Without this, adding NodeId in P2
        // would have turned every shared subtree back into a copy — silently, as a size regression.
        Map<Surface, Surface> shapes = shapesOf(root);

        Map<Key, Long> lowerings = new HashMap<>();
        List<Surface> nodes = new ArrayList<>();
        count(root, 1, lowerings, nodes, shapes);

        Set<Surface> shared = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Surface node : nodes) {
            long count = lowerings.getOrDefault(new Key(shapes.get(node)), 0L);
            if (worthSharing(node, count) && !carriesColour(node)) {
                shared.add(node);
            }
        }
        return new Sharing(shared, shapes);
    }

    /**
     * Each node of {@code root} paired with the same node as a <em>shape</em>: identities flattened to one
     * value, so that structural equality answers "is this the same shape" rather than "is this the same
     * object".
     *
     * <p>Built in one pass by mapping the whole tree and then walking the two in lockstep, rather than
     * canonicalising each subtree separately — the second is the same answer and quadratic.
     */
    private static Map<Surface, Surface> shapesOf(Surface root) {
        Surface flattened = Scalars.map(root, scalar -> scalar, id -> ANONYMOUS);
        Map<Surface, Surface> shapes = new IdentityHashMap<>();
        pair(root, flattened, shapes);
        return shapes;
    }

    private static void pair(Surface node, Surface shape, Map<Surface, Surface> shapes) {
        shapes.put(node, shape);
        List<Surface> children = Scalars.children(node);
        List<Surface> shapeChildren = Scalars.children(shape);
        for (int i = 0; i < children.size(); i++) {
            pair(children.get(i), shapeChildren.get(i), shapes);
        }
    }

    /** The one identity every node wears while it is being compared as a shape. */
    private static final NodeId ANONYMOUS = NodeId.of(0);

    /**
     * Whether a function is worth what it costs.
     *
     * <p>A function is not free: a definition in the module, a call at each site, and a call boundary a
     * driver may or may not see through. A <em>sphere</em> written twice is twelve nodes saved against all of
     * that, and a design is mostly leaves — so a leaf pays only when it is written four times or more, which
     * is what a repeat does to it. Anything with a child of its own pays at two, because its size grows with
     * the design while a leaf's does not.
     *
     * <p>Deliberately a rule about the authored tree rather than about the lowered size, which is not known
     * until the lowering has happened and so cannot decide whether to do it differently.
     */
    private static boolean worthSharing(Surface node, long lowerings) {
        if (lowerings < 2) {
            return false;
        }
        return lowerings >= 4 || !isLeaf(node);
    }

    private static boolean isLeaf(Surface node) {
        return switch (node) {
            case Surface.Sphere ignored -> true;
            case Surface.Box ignored -> true;
            case Surface.Plane ignored -> true;
            case Surface.Capsule ignored -> true;
            case Surface.Torus ignored -> true;
            // Not a leaf in any sense that matters here: one node in the tree and thousands in the IR.
            case Surface.Stroke ignored -> false;
            case Surface.Implicit ignored -> false;
            default -> false;
        };
    }

    private static void count(Surface surface, long lowerings, Map<Key, Long> counts, List<Surface> nodes,
                              Map<Surface, Surface> shapes) {
        counts.merge(new Key(shapes.get(surface)), lowerings, Shared::saturating);
        nodes.add(surface);                     // every instance, since the answer is by identity
        long inner = lowerings * multiplier(surface);
        for (Surface child : Scalars.children(surface)) {
            count(child, inner, counts, nodes, shapes);
        }
    }

    /** How many times this node <em>writes out</em> each of its children. */
    private static long multiplier(Surface surface) {
        return switch (surface) {
            // The neighbour rule: the nearest cell and the nearer neighbour along every repeated axis.
            case Surface.Repeat r -> 1L << activeAxes(r);
            // The sector the point is in, and the one it leans toward.
            case Surface.PolarRepeat ignored -> 2;
            // A soft blend lowers its child once and writes it twice: log-sum-exp is stated relative to the
            // hard extremum, so every child appears in the min (or max) chain and again in its own
            // exponential. Not a repetition anyone authored, and invisible in the tree — but it is two copies
            // in the module, which is the only thing this count is about.
            case Surface.SmoothUnion ignored -> 2;
            case Surface.SmoothIntersection ignored -> 2;
            case Surface.SmoothDifference ignored -> 2;
            default -> 1;
        };
    }

    private static int activeAxes(Surface.Repeat r) {
        int active = 0;
        for (Surface.Repeat.Axis axis : List.of(r.x(), r.y(), r.z())) {
            if (axis.repeats()) {
                active++;
            }
        }
        return active;
    }

    /**
     * Saturating, because the count only ever answers "more than once".
     *
     * <p>Six nested three-axis repeats is 2⁵⁴ lowerings — a number the surface will never reach, since
     * {@link SurfaceLimits} refuses it long before, but one that overflows a {@code long} at eight. Answering
     * "many" rather than wrapping to a negative is the difference between a refusal and a subtree that
     * silently stops being shared at exactly the size where sharing matters most.
     */
    private static long saturating(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** Whether anything in this subtree names a colour of its own. */
    private static boolean carriesColour(Surface surface) {
        if (surface instanceof Surface.Stroke s && s.hasColour()) {
            return true;
        }
        for (Surface child : Scalars.children(surface)) {
            if (carriesColour(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A surface keyed by structure, with its hash computed once.
     *
     * <p>A record's {@code hashCode} recurses into its components, so hashing a node is linear in the subtree
     * under it and hashing every node of a tree is quadratic in the tree. Counting a few thousand nodes would
     * then cost more than the lowering it is trying to make cheap. Computing it once per key and keeping it is
     * the whole of the fix; equality still compares structurally, which is what makes two separately authored
     * copies of a shape one function.
     */
    private record Key(Surface surface, int hash) {

        Key(Surface surface) {
            this(surface, surface.hashCode());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && hash == key.hash && surface.equals(key.surface);
        }
    }
}
