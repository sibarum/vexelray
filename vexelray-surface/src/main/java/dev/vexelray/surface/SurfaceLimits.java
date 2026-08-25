package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;

/**
 * Size limits checked before a surface is lowered.
 *
 * <p>The point of this module is that a surface can arrive from outside the program — a file, an editor buffer, a
 * socket. That makes it <b>compiler input</b>, and compiler input is adversarial by default. Nothing downstream
 * is defensive on its behalf: lowering is recursive (so a deep enough tree overflows the stack before any budget
 * is consulted), {@link Gradient} multiplies node count, and the SPIR-V lowering and driver compile that follow
 * are the expensive part. A surface a thousand levels deep is not a scene; it is a way to stall the frame loop.
 *
 * <p>So the check happens once, up front, on the tree as data — before any of that work starts. The defaults are
 * far above anything hand-authoring produces and far below anything that hurts.
 *
 * <p><b>Bounding the input is not sufficient, and this is measured, not theoretical.</b> {@link Gradient}
 * duplicates its operands per rule, and the duplication <em>compounds</em> through nesting: each nested
 * {@code normalize} multiplies the derivative by roughly four. A 35-node expression — four levels of nesting,
 * far below any input limit worth setting — differentiates to 51,736 nodes, and ten levels would reach millions.
 * An input cap alone would wave that straight through. So {@link #maxCompiledNodes} bounds the <em>output</em>
 * as well, which is the size that actually determines what the SPIR-V lowering and the driver have to chew on.
 *
 * @param maxNodes         maximum input nodes, counting the expression inside an {@link Surface.Implicit}
 * @param maxDepth         maximum input nesting depth, which is what bounds the recursion
 * @param maxCompiledNodes maximum nodes in the lowered field, after any gradient normalisation has expanded it
 */
public record SurfaceLimits(int maxNodes, int maxDepth, int maxCompiledNodes) {

    /** Generous for authored content, strict enough that a hostile surface fails fast instead of hanging. */
    public static final SurfaceLimits DEFAULT = new SurfaceLimits(100_000, 256, 1_000_000);

    public SurfaceLimits {
        if (maxNodes < 1 || maxDepth < 1 || maxCompiledNodes < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    /** Input limits with the default output budget. */
    public SurfaceLimits(int maxNodes, int maxDepth) {
        this(maxNodes, maxDepth, DEFAULT_COMPILED_NODES);
    }

    private static final int DEFAULT_COMPILED_NODES = 1_000_000;

    /**
     * @throws SurfaceTooLargeException if the lowered field exceeds {@link #maxCompiledNodes}
     */
    public void checkCompiled(Expr compiled) {
        Counter counter = new Counter();
        countCompiled(compiled, counter);
    }

    private void countCompiled(Expr e, Counter counter) {
        if (++counter.nodes > maxCompiledNodes) {
            throw new SurfaceTooLargeException(
                    "lowered surface exceeds " + maxCompiledNodes + " nodes; gradient normalisation compounds "
                            + "through nesting, so a small implicit can expand past this");
        }
        switch (e) {
            case Expr.Binary b -> {
                countCompiled(b.lhs(), counter);
                countCompiled(b.rhs(), counter);
            }
            case Expr.Unary u -> countCompiled(u.operand(), counter);
            case Expr.MathCall m -> m.args().forEach(arg -> countCompiled(arg, counter));
            case Expr.VectorConstruct v -> v.components().forEach(c -> countCompiled(c, counter));
            case Expr.VectorExtract v -> countCompiled(v.vector(), counter);
            case Expr.Convert c -> countCompiled(c.operand(), counter);
            default -> {
                // A leaf.
            }
        }
    }

    /** @throws SurfaceTooLargeException if {@code surface} exceeds either limit */
    public void check(Surface surface) {
        Counter counter = new Counter();
        walk(surface, 1, counter);
    }

    private void walk(Surface surface, int depth, Counter counter) {
        if (depth > maxDepth) {
            throw new SurfaceTooLargeException("surface nests deeper than " + maxDepth + " levels");
        }
        count(counter);
        switch (surface) {
            case Surface.Sphere ignored -> {
            }
            case Surface.Box ignored -> {
            }
            case Surface.Plane ignored -> {
            }
            case Surface.Capsule ignored -> {
            }
            case Surface.Torus ignored -> {
            }
            case Surface.Translate t -> walk(t.of(), depth + 1, counter);
            case Surface.Scale s -> walk(s.of(), depth + 1, counter);
            case Surface.Shell s -> walk(s.of(), depth + 1, counter);
            case Surface.Round r -> walk(r.of(), depth + 1, counter);
            case Surface.Difference d -> {
                walk(d.from(), depth + 1, counter);
                walk(d.remove(), depth + 1, counter);
            }
            case Surface.Union u -> u.of().forEach(child -> walk(child, depth + 1, counter));
            case Surface.Intersection i -> i.of().forEach(child -> walk(child, depth + 1, counter));
            case Surface.SmoothUnion s -> s.of().forEach(child -> walk(child, depth + 1, counter));
            case Surface.Implicit i -> walkExpr(i.f(), depth + 1, counter);
        }
    }

    private void walkExpr(Expr e, int depth, Counter counter) {
        if (depth > maxDepth) {
            throw new SurfaceTooLargeException("implicit expression nests deeper than " + maxDepth + " levels");
        }
        count(counter);
        switch (e) {
            case Expr.Binary b -> {
                walkExpr(b.lhs(), depth + 1, counter);
                walkExpr(b.rhs(), depth + 1, counter);
            }
            case Expr.Unary u -> walkExpr(u.operand(), depth + 1, counter);
            case Expr.MathCall m -> m.args().forEach(arg -> walkExpr(arg, depth + 1, counter));
            case Expr.VectorConstruct v -> v.components().forEach(c -> walkExpr(c, depth + 1, counter));
            case Expr.VectorExtract v -> walkExpr(v.vector(), depth + 1, counter);
            case Expr.Convert c -> walkExpr(c.operand(), depth + 1, counter);
            case Expr.Bitcast b -> walkExpr(b.operand(), depth + 1, counter);
            case Expr.MatrixTimesVector m -> walkExpr(m.vector(), depth + 1, counter);
            case Expr.Call c -> c.arguments().forEach(arg -> walkExpr(arg, depth + 1, counter));
            case Expr.BufferLoad b -> walkExpr(b.index(), depth + 1, counter);
            case Expr.SampleTexture s -> walkExpr(s.uv(), depth + 1, counter);
            default -> {
                // A leaf: constant, parameter, interface/push-constant/builtin read.
            }
        }
    }

    private void count(Counter counter) {
        if (++counter.nodes > maxNodes) {
            throw new SurfaceTooLargeException("surface exceeds " + maxNodes + " nodes");
        }
    }

    private static final class Counter {
        int nodes;
    }

    /** Thrown when a surface is too large or too deep to compile safely. */
    public static final class SurfaceTooLargeException extends IllegalArgumentException {
        public SurfaceTooLargeException(String message) {
            super(message);
        }
    }
}
