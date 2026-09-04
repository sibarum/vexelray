package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.surface.Cones;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConeFieldTest {

    private static final int SPIRV_MAGIC = 0x07230203;

    /** A scene carrying the shading, march and lens; its surface is not what gets compiled here. */
    private static SdfScene scene(Surface surface) {
        return SdfScene.of(surface);
    }

    private static Surface.Stroke strokeOf(int vertices) {
        List<Surface.Stroke.Vertex> through = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            through.add(Surface.Stroke.Vertex.sharp(i * 0.1 - 0.5, Math.sin(i) * 0.4, Math.cos(i) * 0.3, 0.05));
        }
        return new Surface.Stroke(through, 4);
    }

    private static byte[] fragment(Surface surface) {
        return SdfComposer.fragmentSpirv(scene(surface), ConeField.sdfFunction(SdfComposer.SDF_FUNCTION), null);
    }

    @Test
    @DisplayName("the cone-field fragment is well-formed SPIR-V")
    void isSpirv() {
        byte[] spirv = fragment(strokeOf(8));
        assertTrue(spirv.length > 0, "no SPIR-V produced");
        assertEquals(SPIRV_MAGIC, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
                "not a SPIR-V module");
    }

    @Test
    @DisplayName("spirv-val accepts a fragment that reads its geometry from a storage buffer")
    void validates() {
        // The one check that is not marking our own homework. A storage buffer in a *fragment* stage is the part
        // worth asking about: the lowering declares it the same way for every stage, and nothing until now had
        // put one anywhere but a compute kernel.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");
        NativeTools.ValidationResult result = tools.validate(fragment(strokeOf(8)));
        assertTrue(result.valid(), "cone-field fragment rejected by spirv-val:\n" + result.output());
    }

    @Test
    @DisplayName("the shader is the same bytes whatever geometry it draws — the whole point")
    void isIndependentOfItsGeometry() {
        // This is the property the class exists for. An unrolled scene compiles its geometry into the module, so
        // a different curve is a different module and a new pipeline — five seconds of it, on the frame loop.
        // Here a curve of eight vertices and a curve of two hundred are the *same* shader, and the difference
        // between them is a memory copy.
        assertArrayEquals(fragment(strokeOf(8)), fragment(strokeOf(200)),
                "the cone-field shader changed with its geometry; it is meant to be independent of it");
    }

    @Test
    @DisplayName("a stroke's cones survive the round trip into buffer floats")
    void packsWhatItFlattens() {
        List<Cones.Cone> cones = Cones.of(strokeOf(5));
        assertTrue(cones.size() > 1, "a five-vertex stroke should be more than one cone");

        float[] packed = ConeField.pack(Cones.flatten(cones), cones.size());
        assertEquals(ConeField.floatsFor(cones.size()), packed.length, "packed length disagrees with floatsFor");
        assertEquals(cones.size(), (int) packed[0], "the header should carry the count");

        // The first cone's first end, where the shader will look for it.
        Cones.Cone first = cones.get(0);
        assertEquals((float) first.ax(), packed[ConeField.HEADER_FLOATS], "first cone misplaced");
        assertEquals((float) first.ar(), packed[ConeField.HEADER_FLOATS + 3], "first radius misplaced");
    }

    @Test
    @DisplayName("a stroke whose ends coincide is carried as a cone, not dropped")
    void degeneratePiecesBecomeCones() {
        // Cones collapses a swallowed piece to a coincident-ended cone rather than a sphere, so the shader has one
        // primitive and no branch. What matters here is only that such a piece still *arrives*: an isolated
        // placeable point is sometimes the entire picture (see Preview's BEAD), and dropping it would lose it.
        Surface.Stroke bead = new Surface.Stroke(
                List.of(Surface.Stroke.Vertex.sharp(0, 0, 0, 0.075),
                        Surface.Stroke.Vertex.sharp(0, 0, 0, 0.075)), 4);
        List<Cones.Cone> cones = Cones.of(bead);
        assertTrue(cones.size() >= 1, "a bead produced no cones at all");
        for (Cones.Cone c : cones) {
            assertTrue(c.ar() > 0 && c.br() > 0, "a cone arrived with no thickness");
        }
    }

    // --- culling: the bounds the march skips groups on ---

    @Test
    @DisplayName("every cone lies inside its group's bounding sphere — the invariant the culling rests on")
    void everyConeIsInsideItsGroupBound() {
        // THE test for this feature. The march skips a whole group when that group's sphere is further off than
        // the best distance so far, and it is allowed to do that only because the sphere *contains* the cones.
        // A sphere that is short by any amount makes the march skip a group it should have evaluated, and the
        // symptom is geometry missing from the picture in a way that shifts as the camera turns — which reads as
        // a broken renderer, not as a bad bounding sphere. So this asserts containment directly.
        //
        // Containment is checked on the end spheres rather than on distances to the cone body, because a cone is
        // the convex hull of its two ends: a sphere holding both ends holds everything between them. That makes
        // this a statement about four numbers rather than about a distance formula the test would have to
        // reimplement — and a test that reimplements the thing it is checking checks nothing.
        List<Cones.Cone> cones = Cones.of(strokeOf(60));
        assertTrue(cones.size() % ConeField.GROUP != 0,
                "pick a stroke whose cone count does not divide by GROUP, so the short last group is covered too");

        float[] packed = ConeField.pack(Cones.flatten(cones), cones.size());
        int bounds = (int) packed[2];

        for (int g = 0; g < ConeField.groupsFor(cones.size()); g++) {
            int at = bounds + g * ConeField.BOUND_FLOATS;
            double cx = packed[at];
            double cy = packed[at + 1];
            double cz = packed[at + 2];
            double radius = packed[at + 3];
            assertTrue(radius > 0, "group " + g + " has no radius at all");

            int from = g * ConeField.GROUP;
            int to = Math.min(cones.size(), from + ConeField.GROUP);
            assertTrue(to > from, "group " + g + " covers no cones");
            for (int c = from; c < to; c++) {
                Cones.Cone cone = cones.get(c);
                assertInside(cx, cy, cz, radius, cone.ax(), cone.ay(), cone.az(), cone.ar(), g, c, "a");
                assertInside(cx, cy, cz, radius, cone.bx(), cone.by(), cone.bz(), cone.br(), g, c, "b");
            }
        }
    }

    /** The end sphere at {@code e} of radius {@code r} reaches no further from the centre than {@code radius}. */
    private static void assertInside(double cx, double cy, double cz, double radius,
            double ex, double ey, double ez, double r, int group, int cone, String end) {
        double reach = Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy) + (ez - cz) * (ez - cz)) + r;
        assertTrue(reach <= radius,
                "cone " + cone + " end " + end + " reaches " + reach + " from group " + group
                        + "'s centre, outside its bound of " + radius);
    }

    @Test
    @DisplayName("the header locates the group bounds the shader goes looking for")
    void theHeaderLocatesTheGroupBounds() {
        // The shader reads all three of these and indexes on them; a header that disagrees with the array is a
        // march reading cone floats as sphere centres, which is a picture of nothing in particular.
        List<Cones.Cone> cones = Cones.of(strokeOf(20));
        float[] packed = ConeField.pack(Cones.flatten(cones), cones.size());

        int groups = ConeField.groupsFor(cones.size());
        assertEquals(cones.size(), (int) packed[0], "the header should carry the cone count");
        assertEquals(groups, (int) packed[1], "the header should carry the group count");
        assertEquals(ConeField.HEADER_FLOATS + cones.size() * Cones.FLOATS, (int) packed[2],
                "the bounds should begin immediately after the last cone");
        assertEquals((int) packed[2] + groups * ConeField.BOUND_FLOATS, packed.length,
                "the array should end with the last bound; floatsFor and pack disagree");
    }

    @Test
    @DisplayName("floatsFor grows with the count, so one worst-case allocation holds every smaller plot")
    void floatsForIsMonotonic() {
        // Preview allocates once, for MAX_SAMPLES, and then packs whatever the expression actually produced into
        // that buffer. If floatsFor ever dipped, a smaller plot would overrun a buffer sized for a larger one.
        int previous = ConeField.floatsFor(0);
        for (int cones = 1; cones <= 400; cones++) {
            int now = ConeField.floatsFor(cones);
            assertTrue(now > previous, "floatsFor(" + cones + ") did not exceed floatsFor(" + (cones - 1) + ")");
            previous = now;
        }
    }

    @Test
    @DisplayName("an empty chain packs to a header with nothing behind it")
    void anEmptyChainHasNoGroups() {
        // What a refused plot uploads. The march must find no cones *and* no groups: a group count left over
        // from the previous expression would send it reading bounds out of a buffer that no longer has any.
        float[] packed = ConeField.pack(new float[0], 0);
        assertEquals(ConeField.HEADER_FLOATS, packed.length, "an empty chain should pack to the header alone");
        assertEquals(0, (int) packed[0], "cone count");
        assertEquals(0, (int) packed[1], "group count");
    }
}
