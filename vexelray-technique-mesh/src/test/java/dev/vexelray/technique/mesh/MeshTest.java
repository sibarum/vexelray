package dev.vexelray.technique.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Polygons become the right triangles.
 *
 * <p>The check that matters is the concave one. A fan from the first corner is the easy triangulation and is right
 * for every convex polygon, so a builder that only ever met squares would pass everything else here. On an L it
 * draws a triangle across the notch; the triangles still exist, they are just in the wrong place, and the
 * symptom on screen is a polygon with a corner filled in. Summing the triangles' <em>unsigned</em> areas catches
 * it without a picture: a triangle outside the outline adds area the outline does not have.
 */
class MeshTest {

    private static final double EPSILON = 1e-5;

    @Test
    void aSquareIsTwoTriangles() {
        Mesh square = Mesh.polygon(0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        assertEquals(2, square.triangleCount());
        assertEquals(1.0, area(square), EPSILON);
    }

    @Test
    void aConcavePolygonIsTriangulatedInsideItsOutline() {
        // An L: a 2x2 square with its top-right quarter missing. Area 3. Listed starting at the reflex corner,
        // which is the start a fan gets most wrong.
        Mesh l = Mesh.polygon(
                1, 1, 0,
                1, 2, 0,
                0, 2, 0,
                0, 0, 0,
                2, 0, 0,
                2, 1, 0);
        assertEquals(4, l.triangleCount());
        assertEquals(3.0, area(l), EPSILON, "a triangle lies outside the L — the notch was filled in");
    }

    /** The same L listed the other way round: winding is the caller's choice, not a contract. */
    @Test
    void windingDoesNotMatter() {
        Mesh l = Mesh.polygon(
                2, 1, 0,
                2, 0, 0,
                0, 0, 0,
                0, 2, 0,
                1, 2, 0,
                1, 1, 0);
        assertEquals(4, l.triangleCount());
        assertEquals(3.0, area(l), EPSILON);
    }

    /** A star: five reflex corners out of ten, in a plane that is none of the axis planes. */
    @Test
    void aStarInATiltedPlane() {
        int points = 5;
        double[] xyz = new double[points * 2 * 3];
        double outer = 1.0;
        double inner = 0.4;
        double expected = 0;
        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI * i / points;
            double radius = i % 2 == 0 ? outer : inner;
            double u = radius * Math.cos(angle);
            double v = radius * Math.sin(angle);
            // The plane spanned by (1,0,1)/sqrt2 and (0,1,0): tilted 45 degrees about y.
            xyz[i * 3] = u / Math.sqrt(2);
            xyz[i * 3 + 1] = v;
            xyz[i * 3 + 2] = u / Math.sqrt(2);
        }
        // Ten triangles from the centre, each with sides outer and inner at an angle of pi/5.
        expected = points * 2 * 0.5 * outer * inner * Math.sin(Math.PI / points);

        Mesh star = Mesh.polygon(xyz);
        assertEquals(points * 2 - 2, star.triangleCount());
        assertEquals(expected, area(star), EPSILON);
    }

    @Test
    void theNormalIsPerpendicularAndUnit() {
        Mesh tilted = Mesh.polygon(0, 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0);
        float[] v = tilted.vertices();
        double nx = v[Mesh.NORMAL];
        double ny = v[Mesh.NORMAL + 1];
        double nz = v[Mesh.NORMAL + 2];
        assertEquals(1.0, Math.sqrt(nx * nx + ny * ny + nz * nz), EPSILON);
        // Perpendicular to both edges out of the first corner.
        assertEquals(0.0, nx * 1 + nz * 1, EPSILON);
        assertEquals(0.0, ny, EPSILON);
    }

    @Test
    void aCornerOnAStraightEdgeIsHarmless() {
        Mesh square = Mesh.polygon(0, 0, 0, 0.5, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        assertEquals(3, square.triangleCount());
        assertEquals(1.0, area(square), EPSILON);
    }

    @Test
    void aBoxIsTwelveTrianglesWithTheRightSurfaceArea() {
        Mesh box = Mesh.box(1, 2, 3);
        assertEquals(12, box.triangleCount());
        assertEquals(2 * (1 * 2 + 2 * 3 + 1 * 3), area(box), EPSILON);
    }

    @Test
    void theBuilderCarriesColourPerFace() {
        Mesh two = Mesh.builder()
                .colour(1, 0, 0).triangle(0, 0, 0, 1, 0, 0, 0, 1, 0)
                .colour(0, 0, 1).triangle(0, 0, 1, 1, 0, 1, 0, 1, 1)
                .build();
        float[] v = two.vertices();
        assertEquals(1f, v[Mesh.COLOUR]);
        assertEquals(1f, v[3 * Mesh.FLOATS_PER_VERTEX + Mesh.COLOUR + 2]);
        assertEquals(0f, v[3 * Mesh.FLOATS_PER_VERTEX + Mesh.COLOUR]);
    }

    @Test
    void aBowTieIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Mesh.polygon(0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 0, 0.5, 2, 0, -0.5, 1, 0));
    }

    @Test
    void degenerateInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Mesh.polygon(0, 0, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Mesh.polygon(0, 0, 0, 1, 0, 0, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Mesh.polygon(0, 0, 0, 1, 0));
        assertThrows(IllegalStateException.class, () -> Mesh.builder().build());
    }

    /** The sum of every triangle's unsigned area. */
    private static double area(Mesh mesh) {
        float[] v = mesh.vertices();
        double total = 0;
        int stride = Mesh.FLOATS_PER_VERTEX;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            int a = t * 3 * stride;
            int b = a + stride;
            int c = b + stride;
            double ux = v[b] - v[a];
            double uy = v[b + 1] - v[a + 1];
            double uz = v[b + 2] - v[a + 2];
            double wx = v[c] - v[a];
            double wy = v[c + 1] - v[a + 1];
            double wz = v[c + 2] - v[a + 2];
            double cx = uy * wz - uz * wy;
            double cy = uz * wx - ux * wz;
            double cz = ux * wy - uy * wx;
            total += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return total;
    }
}
