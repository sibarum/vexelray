package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How big a colour gets — the test that would have caught the 21 MB shader.
 *
 * <p>Selecting a colour out of a union means asking, per child, whether it is the nearest. Folded pairwise
 * against the accumulated field, each comparison embeds the whole chain before it and the colour grows as the
 * <b>square</b> of the child count. That is not a slow shader, it is a machine that stops: 114 cones lowered to
 * 9,406,276 nodes of colour against 54,663 of distance, which reached the driver as 21 MB of SPIR-V and froze
 * the display compiling it. The fix is {@link Lets}; this is the guard that keeps it fixed.
 *
 * <p>The assertion is a <em>ratio</em> rather than an absolute size, because the absolute size is allowed to
 * grow with the geometry. What is not allowed is for colour to grow faster than the field it selects over.
 */
class ColourSizeTest {

    /**
     * How much larger than the distance a colour may be. Colour names each child's distance once and adds a
     * mix and a running minimum per child, so it lands a little above parity; anything near this is fine and
     * anything past it means the fold has started copying trees again.
     */
    private static final double BUDGET = 2.0;

    @Test
    @DisplayName("colour stays the same order of size as the field it selects over, at every scale")
    void colourGrowsLinearlyWithTheField() {
        double worst = 0;
        for (int vertices : new int[]{2, 4, 8, 16, 32}) {
            Field field = SurfaceCompiler.compile(coloured(vertices));
            int distance = size(field.distance());
            int colour = colourSize(field);
            double ratio = (double) colour / distance;
            worst = Math.max(worst, ratio);
            assertTrue(ratio <= BUDGET,
                    vertices + " vertices: colour is " + colour + " nodes against " + distance
                            + " of distance (" + Math.round(ratio * 10) / 10.0 + "x). A ratio that climbs with"
                            + " the vertex count is the quadratic fold coming back.");
        }
        // Stated as well as asserted: a quadratic fold at 32 vertices would be several hundred times over.
        assertTrue(worst < BUDGET, "worst ratio " + worst);
    }

    @Test
    @DisplayName("an uncoloured stroke carries no colour program at all")
    void uncolouredStrokesDeclareNothing() {
        Field field = SurfaceCompiler.compile(plain(16));
        assertEquals(List.of(), field.albedoLets());
        assertEquals(null, field.albedo());
    }

    @Test
    @DisplayName("every local a colour reads is declared before the read that reads it")
    void declarationsComeBeforeTheirUses() {
        // The colour program is a statement list, so order is correctness rather than style: the compiler binds
        // in post-order and nothing downstream sorts it. A read of a local declared later is a shader that does
        // not compile, and it would only show up on a GPU.
        Field field = SurfaceCompiler.compile(coloured(8));
        List<Object> declared = new ArrayList<>();
        for (Statement s : field.albedoLets()) {
            Statement.DeclareVar d = (Statement.DeclareVar) s;
            assertReadsAreDeclared(d.initializer(), declared);
            declared.add(d.variable());
        }
        assertReadsAreDeclared(field.albedo(), declared);
    }

    private static void assertReadsAreDeclared(Expr e, List<Object> declared) {
        switch (e) {
            case Expr.Read r -> assertTrue(declared.contains(r.variable()),
                    "reads " + r.variable() + " before it is declared");
            case Expr.Binary b -> {
                assertReadsAreDeclared(b.lhs(), declared);
                assertReadsAreDeclared(b.rhs(), declared);
            }
            case Expr.Unary u -> assertReadsAreDeclared(u.operand(), declared);
            case Expr.MathCall m -> m.args().forEach(a -> assertReadsAreDeclared(a, declared));
            case Expr.VectorConstruct v -> v.components().forEach(c -> assertReadsAreDeclared(c, declared));
            case Expr.VectorExtract v -> assertReadsAreDeclared(v.vector(), declared);
            default -> {
            }
        }
    }

    // --- fixtures ---

    private static Surface.Stroke coloured(int vertices) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            vs.add(new Surface.Stroke.Vertex(i * 0.5, i % 2, 0, 0.2, 1)
                    .painted(new Surface.Rgb(i / (double) vertices, 0.3, 0.6)));
        }
        return new Surface.Stroke(vs);
    }

    private static Surface.Stroke plain(int vertices) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            vs.add(new Surface.Stroke.Vertex(i * 0.5, i % 2, 0, 0.2, 1));
        }
        return new Surface.Stroke(vs);
    }

    /** The whole colour program: the expression, plus everything it reads. */
    private static int colourSize(Field field) {
        int n = size(field.albedo());
        for (Statement s : field.albedoLets()) {
            n += size(((Statement.DeclareVar) s).initializer()) + 1;
        }
        return n;
    }

    private static int size(Expr e) {
        if (e == null) {
            return 0;
        }
        int n = 1;
        switch (e) {
            case Expr.Binary b -> n += size(b.lhs()) + size(b.rhs());
            case Expr.Unary u -> n += size(u.operand());
            case Expr.MathCall m -> {
                for (Expr a : m.args()) {
                    n += size(a);
                }
            }
            case Expr.VectorConstruct v -> {
                for (Expr c : v.components()) {
                    n += size(c);
                }
            }
            case Expr.VectorExtract v -> n += size(v.vector());
            default -> {
            }
        }
        return n;
    }
}
