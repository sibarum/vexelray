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
}
