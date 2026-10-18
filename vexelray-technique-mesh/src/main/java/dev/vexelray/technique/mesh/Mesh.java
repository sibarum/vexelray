package dev.vexelray.technique.mesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Polygons, as the triangles a rasteriser draws: immutable, in the mesh's own coordinates, with a normal and a
 * colour at every vertex.
 *
 * <pre>{@code
 * Mesh roof = Mesh.builder()
 *         .colour(0.7, 0.2, 0.15)
 *         .polygon(-1, 0, -1,   1, 0, -1,   0, 1, 0)        // any simple planar polygon, convex or not
 *         .build();
 * Mesh crate = Mesh.box(1, 1, 1);
 * }</pre>
 *
 * <h2>Faces are two-sided, so winding does not matter</h2>
 *
 * <p>The fragment stage turns every normal to face the eye before it lights the point, and the pipeline culls
 * nothing. So a polygon may be listed clockwise or anticlockwise, a sheet is lit from whichever side it is seen
 * from, and a closed solid is lit on its outside because the outside is the side that can be seen. A mesh author
 * never has to know which way this engine's axes turn — which matters here more than usual, because x right, y
 * up and z forward is a <em>left</em>-handed frame and the usual right-handed habit would get it backwards.
 *
 * <h2>Why triangles are stored unindexed</h2>
 *
 * <p>Every polygon is flat-shaded: its own normal on each of its vertices, so a box's corner is three vertices
 * with three normals rather than one shared one. An index buffer saves storage only where vertices are shared,
 * and a flat-shaded polygon mesh shares almost none. Three vertices a triangle keeps the draw a single
 * {@code vkCmdDraw} over a range, which is what the rest of the engine already records.
 *
 * <h2>Vertex layout</h2>
 *
 * <p>{@link #FLOATS_PER_VERTEX} floats: position, normal and colour, each a {@code vec4}. Four rather than three
 * because {@code R32G32B32_SFLOAT} is not guaranteed as a vertex format on every device (see
 * {@code GraphicsPipeline.VertexAttribute.floats}); the fourth components are 1, 0 and 1, which are what they
 * would mean if anything read them.
 */
public final class Mesh {

    /** Floats per vertex: position, normal, colour, four each. */
    public static final int FLOATS_PER_VERTEX = 12;

    /** Byte stride of one vertex. */
    public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    /** Float offset of the position within a vertex. */
    public static final int POSITION = 0;

    /** Float offset of the normal within a vertex. */
    public static final int NORMAL = 4;

    /** Float offset of the colour within a vertex. */
    public static final int COLOUR = 8;

    private final float[] vertices;

    private Mesh(float[] vertices) {
        this.vertices = vertices;
    }

    /** A builder whose faces are white until {@link Builder#colour} says otherwise. */
    public static Builder builder() {
        return new Builder();
    }

    /** One polygon: {@code xyz} is its corners, three numbers each, in order around its edge. */
    public static Mesh polygon(double... xyz) {
        return builder().polygon(xyz).build();
    }

    /**
     * A box centred on the origin, {@code width} along x, {@code height} along y and {@code depth} along z: six
     * quads, twelve triangles.
     */
    public static Mesh box(double width, double height, double depth) {
        double x = width * 0.5;
        double y = height * 0.5;
        double z = depth * 0.5;
        return builder()
                .polygon(-x, -y, -z, x, -y, -z, x, y, -z, -x, y, -z)   // back   (-z)
                .polygon(-x, -y, z, x, -y, z, x, y, z, -x, y, z)       // front  (+z)
                .polygon(-x, -y, -z, -x, y, -z, -x, y, z, -x, -y, z)   // left   (-x)
                .polygon(x, -y, -z, x, y, -z, x, y, z, x, -y, z)       // right  (+x)
                .polygon(-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z)   // bottom (-y)
                .polygon(-x, y, -z, x, y, -z, x, y, z, -x, y, z)       // top    (+y)
                .build();
    }

    /** How many vertices: three per triangle. */
    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    /** How many triangles. */
    public int triangleCount() {
        return vertexCount() / 3;
    }

    /** A copy of the interleaved vertex data, {@link #FLOATS_PER_VERTEX} floats a vertex. */
    public float[] vertices() {
        return vertices.clone();
    }

    /**
     * Where this mesh's vertices are in the world when it stands at {@code placement}: three numbers per vertex,
     * three vertices per triangle, and nothing else — no normals, no colour.
     *
     * <p>The shape a mask wants. {@code SdfRaymarchTechnique.mask} takes exactly this, so a marched object drawn
     * only inside a mesh's silhouette is {@code march.mask(mesh.worldPositions(placement))}, and following the mesh
     * as it moves is the same call each frame.
     */
    public float[] worldPositions(Placement placement) {
        float[] out = new float[vertexCount() * 3];
        worldPositions(placement, out);
        return out;
    }

    /** The same, into {@code out} — {@code vertexCount() * 3} floats — for a caller that calls it every frame. */
    public void worldPositions(Placement placement, float[] out) {
        if (out.length < vertexCount() * 3) {
            throw new IllegalArgumentException("worldPositions writes " + vertexCount() * 3 + " floats and was "
                    + "given " + out.length);
        }
        float[] m = new float[Placement.MATRIX_FLOATS];
        placement.modelMatrix(m, 0);
        for (int i = 0; i < vertexCount(); i++) {
            int at = i * FLOATS_PER_VERTEX + POSITION;
            float x = vertices[at];
            float y = vertices[at + 1];
            float z = vertices[at + 2];
            out[i * 3] = m[0] * x + m[3] * y + m[6] * z + m[9];
            out[i * 3 + 1] = m[1] * x + m[4] * y + m[7] * z + m[10];
            out[i * 3 + 2] = m[2] * x + m[5] * y + m[8] * z + m[11];
        }
    }

    /** Copies the vertex data into {@code out} at {@code at}, without the copy {@link #vertices()} makes. */
    void copyInto(float[] out, int at) {
        System.arraycopy(vertices, 0, out, at, vertices.length);
    }

    int floatCount() {
        return vertices.length;
    }

    /** Accumulates polygons. Not thread-safe; {@link #build} copies, so a builder can go on after it. */
    public static final class Builder {

        private float[] data = new float[FLOATS_PER_VERTEX * 36];
        private int size;
        private float r = 1;
        private float g = 1;
        private float b = 1;

        private Builder() {
        }

        /** The linear-RGB colour of the faces added after this, in {@code [0,1]}. */
        public Builder colour(double r, double g, double b) {
            this.r = (float) r;
            this.g = (float) g;
            this.b = (float) b;
            return this;
        }

        /** One triangle, flat-shaded. */
        public Builder triangle(double ax, double ay, double az,
                                double bx, double by, double bz,
                                double cx, double cy, double cz) {
            return polygon(ax, ay, az, bx, by, bz, cx, cy, cz);
        }

        /**
         * One flat polygon: {@code xyz} is its corners, three numbers each, in order around its edge.
         *
         * <p>Any <b>simple, planar</b> polygon — convex or concave — is triangulated by ear clipping, so an L, a
         * star or a letter outline comes out as its own shape rather than as the fan that a convex-only builder
         * would draw across its notches. Corners that are not quite coplanar are tolerated, the way a polygon
         * typed in by hand never is quite: the triangulation and the normal both come from the plane that fits
         * them best (Newell's), and each triangle keeps the corners as given.
         *
         * @throws IllegalArgumentException for fewer than three corners, a corner count that is not a whole
         *         number of points, a polygon with no area, or one whose edges cross — reported rather than drawn,
         *         because a self-intersecting outline has no single right triangulation and any choice made here
         *         would look like a modelling mistake somewhere else
         */
        public Builder polygon(double... xyz) {
            if (xyz.length % 3 != 0) {
                throw new IllegalArgumentException("a polygon is three numbers per corner, got " + xyz.length);
            }
            int n = xyz.length / 3;
            if (n < 3) {
                throw new IllegalArgumentException("a polygon needs at least three corners, got " + n);
            }
            for (double v : xyz) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("polygon corners must be finite: " + Arrays.toString(xyz));
                }
            }
            double[] normal = newellNormal(xyz, n);
            double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            if (!(length > 0)) {
                throw new IllegalArgumentException("this polygon has no area: " + Arrays.toString(xyz));
            }
            float nx = (float) (normal[0] / length);
            float ny = (float) (normal[1] / length);
            float nz = (float) (normal[2] / length);

            for (int[] t : triangulate(xyz, n, normal)) {
                for (int corner : t) {
                    put(xyz[corner * 3], xyz[corner * 3 + 1], xyz[corner * 3 + 2], nx, ny, nz);
                }
            }
            return this;
        }

        /** The mesh so far. The builder may go on being used; the mesh does not see what it adds. */
        public Mesh build() {
            if (size == 0) {
                throw new IllegalStateException("a mesh needs at least one polygon");
            }
            return new Mesh(Arrays.copyOf(data, size));
        }

        private void put(double x, double y, double z, float nx, float ny, float nz) {
            if (size + FLOATS_PER_VERTEX > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            float[] d = data;
            int i = size;
            d[i] = (float) x;
            d[i + 1] = (float) y;
            d[i + 2] = (float) z;
            d[i + 3] = 1;
            d[i + 4] = nx;
            d[i + 5] = ny;
            d[i + 6] = nz;
            d[i + 7] = 0;
            d[i + 8] = r;
            d[i + 9] = g;
            d[i + 10] = b;
            d[i + 11] = 1;
            size = i + FLOATS_PER_VERTEX;
        }
    }

    /**
     * Newell's normal: twice the polygon's vector area. Exact for a planar polygon of any shape, and the
     * least-squares plane for one that is only nearly planar — unlike the cross product of two edges, which is
     * wrong whenever the corner it is taken at happens to be a reflex one.
     */
    static double[] newellNormal(double[] xyz, int n) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = xyz[i * 3];
            double ay = xyz[i * 3 + 1];
            double az = xyz[i * 3 + 2];
            double bx = xyz[j * 3];
            double by = xyz[j * 3 + 1];
            double bz = xyz[j * 3 + 2];
            x += (ay - by) * (az + bz);
            y += (az - bz) * (ax + bx);
            z += (ax - bx) * (ay + by);
        }
        return new double[]{x, y, z};
    }

    /**
     * Ear clipping in the plane the normal is most nearly perpendicular to: {@code n - 2} triangles, each a triple
     * of corner indices.
     *
     * <p>Dropping the normal's largest axis is a projection that preserves which side of every edge a point is on
     * — it is a shear of the polygon's own plane, never edge-on to it — so convexity and containment tested in 2D
     * are the 3D answers. Orientation is read from the projected area rather than assumed, which is what lets a
     * caller list corners either way round.
     */
    static List<int[]> triangulate(double[] xyz, int n, double[] normal) {
        double ax = Math.abs(normal[0]);
        double ay = Math.abs(normal[1]);
        double az = Math.abs(normal[2]);
        int u;
        int v;
        if (ax >= ay && ax >= az) {
            u = 1;
            v = 2;
        } else if (ay >= az) {
            u = 2;
            v = 0;
        } else {
            u = 0;
            v = 1;
        }
        double[] px = new double[n];
        double[] py = new double[n];
        double area = 0;
        for (int i = 0; i < n; i++) {
            px[i] = xyz[i * 3 + u];
            py[i] = xyz[i * 3 + v];
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += px[i] * py[j] - px[j] * py[i];
        }
        double sign = Math.signum(area);
        if (crosses(px, py, n)) {
            throw new IllegalArgumentException("this polygon's edges cross; split it into simple polygons: "
                    + Arrays.toString(xyz));
        }

        List<int[]> triangles = new ArrayList<>(n - 2);
        if (n == 3) {
            triangles.add(new int[]{0, 1, 2});
            return triangles;
        }
        int[] ring = new int[n];
        for (int i = 0; i < n; i++) {
            ring[i] = i;
        }
        int remaining = n;
        int guard = 0;
        int i = 0;
        while (remaining > 3) {
            int prev = ring[(i + remaining - 1) % remaining];
            int curr = ring[i % remaining];
            int next = ring[(i + 1) % remaining];
            if (isEar(px, py, sign, ring, remaining, prev, curr, next)) {
                triangles.add(new int[]{prev, curr, next});
                int at = i % remaining;
                System.arraycopy(ring, at + 1, ring, at, remaining - at - 1);
                remaining--;
                guard = 0;
                // Stay on the same index: the corner after the clipped one has moved into it.
                i = at % remaining;
            } else {
                i = (i + 1) % remaining;
                if (++guard > remaining) {
                    throw new IllegalArgumentException("this polygon cannot be triangulated — its edges cross, or "
                            + "it doubles back on itself: " + Arrays.toString(xyz));
                }
            }
        }
        triangles.add(new int[]{ring[0], ring[1], ring[2]});
        return triangles;
    }

    /**
     * Whether two edges that do not share a corner properly cross. Checked up front, because ear clipping on a
     * self-crossing outline does not reliably fail — it often returns triangles, just not ones that mean anything.
     * Quadratic in the corner count, which for a polygon somebody typed or imported as one face is nothing.
     */
    private static boolean crosses(double[] px, double[] py, int n) {
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                int j2 = (j + 1) % n;
                if (j == i2 || j2 == i) {
                    continue;
                }
                double d1 = cross(px, py, i, i2, j);
                double d2 = cross(px, py, i, i2, j2);
                double d3 = cross(px, py, j, j2, i);
                double d4 = cross(px, py, j, j2, i2);
                if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEar(double[] px, double[] py, double sign, int[] ring, int remaining,
                                 int a, int b, int c) {
        // Convex at b, in the polygon's own turning direction. Strictly: a corner on a straight edge is not an
        // ear, or clipping it would make a triangle with no area.
        if (cross(px, py, a, b, c) * sign <= 0) {
            return false;
        }
        for (int k = 0; k < remaining; k++) {
            int p = ring[k];
            if (p == a || p == b || p == c) {
                continue;
            }
            if (inside(px, py, sign, a, b, c, p)) {
                return false;
            }
        }
        return true;
    }

    private static double cross(double[] px, double[] py, int a, int b, int c) {
        return (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
    }

    /** Inside or on the boundary of triangle {@code abc}: a corner touching an ear's edge blocks it too. */
    private static boolean inside(double[] px, double[] py, double sign, int a, int b, int c, int p) {
        return cross(px, py, a, b, p) * sign >= 0
                && cross(px, py, b, c, p) * sign >= 0
                && cross(px, py, c, a, p) * sign >= 0;
    }
}
