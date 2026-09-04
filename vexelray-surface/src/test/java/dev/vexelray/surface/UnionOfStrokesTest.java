package dev.vexelray.surface;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Statement;
import dev.vexelray.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strokes inside a {@link Surface.Union} — the combination that had no test of its own, and the one a scene is
 * actually built from.
 *
 * <p>Each of the pieces was covered alone: a stroke's geometry, a union's distance, a colour's size. What was
 * not covered was a stroke <em>in</em> a union, which is how anybody uses either, and where two things could go
 * wrong that neither part could show on its own — the colour selection reading a distance from the wrong side,
 * and the colour program's declarations landing out of order once two subtrees each contribute their own.
 */
class UnionOfStrokesTest {

    private static final Surface.Rgb RED = new Surface.Rgb(1, 0, 0);
    private static final Surface.Rgb BLUE = new Surface.Rgb(0, 0, 1);

    /** The colour the composer substitutes for a surface that named none — distinctive, so a leak is obvious. */
    private static final double[] SCENE = {0.25, 0.5, 0.75};

    @Test
    @DisplayName("a union of strokes is exactly the min of the strokes")
    void unionIsTheMinOfItsParts() {
        Surface.Stroke a = stroke(-2, RED);
        Surface.Stroke b = stroke(2, BLUE);
        Field fa = SurfaceCompiler.compile(a);
        Field fb = SurfaceCompiler.compile(b);
        Field union = SurfaceCompiler.compile(Surface.union(a, b));

        for (double x = -4; x <= 5; x += 0.25) {
            for (double y = -1; y <= 2; y += 0.5) {
                double expected = Math.min(Eval.at(fa.distance(), x, y, 0), Eval.at(fb.distance(), x, y, 0));
                assertEquals(expected, Eval.at(union.distance(), x, y, 0), 1e-12,
                        "at (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    @DisplayName("each stroke in a union keeps its own colour")
    void eachStrokeKeepsItsColour() {
        Field union = SurfaceCompiler.compile(Surface.union(stroke(-2, RED), stroke(2, BLUE)));
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(union, -2, 0, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0, 1}, albedoAt(union, 2, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("an uncoloured surface beside a coloured one takes the scene's colour, not its neighbour's")
    void uncolouredNeighboursDeferToTheScene() {
        Field union = SurfaceCompiler.compile(Surface.union(
                stroke(-2, RED), new Surface.Sphere(6, 0, 0, 0.5)));
        assertArrayEquals(new double[]{1, 0, 0}, albedoAt(union, -2, 0, 0), 1e-9);
        assertArrayEquals(SCENE, albedoAt(union, 6, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("a colour program from several subtrees still declares before it reads")
    void declarationsStayOrdered() {
        // Two strokes each bind their own cones, and then the union binds comparisons over both. The list is a
        // program, so order is correctness: a read of a local declared later is a shader that will not compile,
        // and it would only be discovered on a GPU.
        Field union = SurfaceCompiler.compile(Surface.union(
                stroke(-2, RED), stroke(2, BLUE), stroke(6, RED)));
        List<Object> declared = new ArrayList<>();
        for (Statement s : union.albedoLets()) {
            Statement.DeclareVar d = (Statement.DeclareVar) s;
            assertReadsDeclared(d.initializer(), declared, d.variable());
            declared.add(d.variable());
        }
        assertReadsDeclared(union.albedo(), declared, "the returned colour");
    }

    @Test
    @DisplayName("a union of strokes stays exact, so it needs no normalisation")
    void unionStaysExact() {
        assertEquals(Field.EXACT,
                SurfaceCompiler.compile(Surface.union(stroke(-2, RED), stroke(2, BLUE))).lipschitz());
    }

    @Test
    @DisplayName("a union's colour stays the same order of size as its field")
    void unionColourDoesNotBlowUp() {
        // The quadratic fold showed up first inside one stroke; a union folds over subtrees the same way, so it
        // could reintroduce it by a different route.
        List<Surface> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(stroke(i * 3, i % 2 == 0 ? RED : BLUE));
        }
        Field union = SurfaceCompiler.compile(new Surface.Union(many));
        int distance = size(union.distance());
        int colour = size(union.albedo());
        for (Statement s : union.albedoLets()) {
            colour += size(((Statement.DeclareVar) s).initializer()) + 1;
        }
        assertTrue(colour <= 3 * distance,
                "colour is " + colour + " nodes against " + distance + " of distance");
    }

    // --- helpers ---

    private static Surface.Stroke stroke(double x, Surface.Rgb colour) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Surface.Stroke.Vertex v = new Surface.Stroke.Vertex(x + i * 0.5, i % 2, 0, 0.15, 1);
            vs.add(colour == null ? v : v.painted(colour));
        }
        return new Surface.Stroke(vs, 2);
    }

    private static double[] albedoAt(Field field, double x, double y, double z) {
        Expr fallback = Ir.v3(SCENE[0], SCENE[1], SCENE[2]);
        List<Statement> lets = new ArrayList<>();
        for (Statement s : field.albedoLets()) {
            lets.add(Substitute.sceneAlbedo(s, fallback));
        }
        return Eval.withLets(Substitute.sceneAlbedo(field.albedo(), fallback), lets, x, y, z);
    }

    private static void assertReadsDeclared(Expr e, List<Object> declared, Object where) {
        switch (e) {
            case Expr.Read r -> assertTrue(declared.contains(r.variable()),
                    where + " reads " + r.variable() + " before it is declared");
            case Expr.Binary b -> {
                assertReadsDeclared(b.lhs(), declared, where);
                assertReadsDeclared(b.rhs(), declared, where);
            }
            case Expr.Unary u -> assertReadsDeclared(u.operand(), declared, where);
            case Expr.MathCall m -> m.args().forEach(a -> assertReadsDeclared(a, declared, where));
            case Expr.VectorConstruct v -> v.components().forEach(c -> assertReadsDeclared(c, declared, where));
            case Expr.VectorExtract v -> assertReadsDeclared(v.vector(), declared, where);
            default -> {
            }
        }
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
