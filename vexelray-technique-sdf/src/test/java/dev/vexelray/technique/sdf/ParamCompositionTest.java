package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamId;
import dev.vexelray.surface.Scalar;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0a at the composer: the block a parametric scene emits, and the claim the whole stage rests on — that a
 * value sweep is writes and not pipelines.
 */
class ParamCompositionTest {

    private static final ParamId RADIUS = ParamId.fresh();
    private static final ParamId LIFT = ParamId.fresh();

    private static SdfScene scene(double radius) {
        return SdfScene.of(Surface.union(
                Surface.Plane.ground(),
                new Surface.Sphere(Scalar.of(0), new Scalar.Param(LIFT, 0.5, 3, 1), Scalar.of(3),
                        new Scalar.Param(RADIUS, 0.25, 2, radius))));
    }

    @Test
    @DisplayName("the block is the camera, the lens, and one member per parameter")
    void blockCarriesCameraLensAndParameters() {
        List<Expr.PushConstantRead> reads = pushConstantReads(SdfComposer.sdfFunction(scene(1)).body());

        assertEquals(2, reads.size(), "one read per parameter, and no more");
        for (Expr.PushConstantRead read : reads) {
            assertEquals(SdfComposer.FIRST_PARAM_MEMBER + 2, read.block().members().size());
            assertTrue(read.member() >= SdfComposer.FIRST_PARAM_MEMBER,
                    "a field read landed in the camera's members");
        }
        assertEquals("focalLength",
                reads.get(0).block().members().get(SdfComposer.FIRST_PARAM_MEMBER - 1).name());
    }

    @Test
    @DisplayName("a value sweep composes byte-identical SPIR-V: one pipeline, 200 writes")
    void aSweepIsOnePipeline() {
        byte[] first = SdfComposer.fragmentSpirv(scene(0.25));

        for (int i = 0; i <= 200; i++) {
            double radius = 0.25 + i * (1.75 / 200);
            assertArrayEquals(first, SdfComposer.fragmentSpirv(scene(radius)),
                    "value " + radius + " changed the shader, so a sweep would recompile");
        }
    }

    @Test
    @DisplayName("the lens joins the block, so two focal lengths are one pipeline")
    void focalLengthNoLongerCompiles() {
        SdfScene wide = scene(1);
        SdfScene longLens = new SdfScene(wide.surface(), wide.shading(), wide.march(),
                wide.albedo(), wide.sky(), 3.0, wide.nearPlane());

        assertArrayEquals(SdfComposer.fragmentSpirv(wide), SdfComposer.fragmentSpirv(longLens));
    }

    @Test
    @DisplayName("the pushed bytes are camera, lens, then values in slot order")
    void pushedBytesMatchTheBlock() {
        SdfScene scene = scene(1);
        ParamBlock values = SdfComposer.paramBlock(scene);
        values.write(RADIUS, 1.75);
        values.write(LIFT, 2.5);

        byte[] pushed = SdfComposer.pushConstantBytes(scene, 1, 2, 3, 0.5, -0.25, 1.5, values);

        assertEquals(SdfComposer.pushBytes(scene), pushed.length);
        assertEquals((SdfComposer.FIRST_PARAM_MEMBER + 2) * 4, pushed.length);
        ByteBuffer read = ByteBuffer.wrap(pushed).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.0f, read.getFloat(0));
        assertEquals(1.5f, read.getFloat(20), "aspect");
        assertEquals((float) scene.focalLength(), read.getFloat(24), "the lens");
        // The walk visits a sphere's numbers in declaration order, so the lift — the sphere's cy — is met
        // before its radius and takes slot 0. That is the contract rather than an accident: one tree shape must
        // always give one slot assignment, or a value written before a recompile would land somewhere else.
        assertEquals(2.5f, read.getFloat(28), "slot 0 is the lift, met first in the walk");
        assertEquals(1.75f, read.getFloat(32), "slot 1 is the radius");
    }

    @Test
    @DisplayName("values built for another surface are refused rather than written to the wrong slots")
    void staleValuesAreRefused() {
        SdfScene scene = scene(1);
        ParamBlock other = SdfComposer.paramBlock(
                SdfScene.of(new Surface.Sphere(Scalar.of(0), Scalar.of(0), Scalar.of(0),
                        Scalar.Param.over(0.5, 2))));

        assertThrows(IllegalArgumentException.class,
                () -> SdfComposer.pushConstantBytes(scene, 0, 0, 0, 0, 0, 1, other));
    }

    /** A union of {@code n} spheres, each with a driven radius — one parameter apiece. */
    private static SdfScene spheres(int n) {
        List<Surface> of = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            of.add(new Surface.Sphere(Scalar.of(i * 3), Scalar.of(0), Scalar.of(0),
                    Scalar.Param.over(0.25, 2)));
        }
        return SdfScene.of(new Surface.Union(of));
    }

    @Test
    @DisplayName("past what push constants hold, the parameters take the other road rather than failing")
    void pastTheCapTheBufferTakesOver() {
        // What P0a threw on. The design is unchanged and so is its API — the same slots, the same
        // write(ParamId, double) — and only the transport differs, which is the whole of P0b.
        int cap = ParamBacking.DEFAULT.pushConstantCapacity();
        SdfScene fits = spheres(cap);
        SdfScene doesNot = spheres(cap + 1);

        assertFalse(ParamBacking.DEFAULT.usesBuffer(cap), "at the cap, push constants still hold it");
        assertTrue(ParamBacking.DEFAULT.usesBuffer(cap + 1), "one past it, the buffer takes over");

        assertEquals((SdfComposer.FIRST_PARAM_MEMBER + cap) * 4, SdfComposer.pushBytes(fits));
        assertEquals(SdfComposer.FIRST_PARAM_MEMBER * 4, SdfComposer.pushBytes(doesNot),
                "on the buffer road the block is the camera and the lens alone");

        // And it composes, which is the sentence P0a could not say.
        assertTrue(SdfComposer.fragmentSpirv(doesNot).length > 0);
    }

    @Test
    @DisplayName("a design opens on a smaller device than it was drawn on — by the other road")
    void aDesignOutlivesTheDeviceItWasDrawnOn() {
        // The rule the whole backing exists to keep. Forty parameters fit in push constants where the driver
        // reports 256 bytes and do not where it reports 128; the design is the same design on both, and the
        // difference is which road the values travel.
        SdfScene scene = spheres(40);
        ParamBacking roomy = ParamBacking.on(256);
        ParamBacking tight = ParamBacking.DEFAULT;

        assertFalse(roomy.usesBuffer(40), "256 bytes holds forty parameters");
        assertTrue(tight.usesBuffer(40), "128 bytes does not");

        assertTrue(SdfComposer.fragmentSpirv(scene, roomy).length > 0);
        assertTrue(SdfComposer.fragmentSpirv(scene, tight).length > 0);
        assertFalse(java.util.Arrays.equals(SdfComposer.fragmentSpirv(scene, roomy),
                        SdfComposer.fragmentSpirv(scene, tight)),
                "two roads are two shaders, which is why the backing is in the cache key");
    }

    @Test
    @DisplayName("forcing push constants past the cap fails by name, and names the way out")
    void forcingThePushRoadFailsByName() {
        SdfScene tooMany = spheres(ParamBacking.DEFAULT.pushConstantCapacity() + 1);
        ParamBacking forced = ParamBacking.DEFAULT.alwaysPushConstants();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> SdfComposer.fragmentSpirv(tooMany, forced));
        assertTrue(thrown.getMessage().contains("ParamBacking.AUTO"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(String.valueOf(forced.pushConstantCapacity())),
                thrown.getMessage());
    }

    @Test
    @DisplayName("the buffer road's SPIR-V passes spirv-val")
    void theBufferRoadValidates() {
        // The road P0a could not take at all, asked of Khronos's own validator: a storage buffer at
        // descriptor set 0 read from inside the field, with the camera still in push constants beside it.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");

        SdfScene scene = spheres(60);
        assertTrue(ParamBacking.DEFAULT.usesBuffer(60), "sixty parameters should be past the cap");
        NativeTools.ValidationResult result = tools.validate(SdfComposer.fragmentSpirv(scene));
        assertTrue(result.valid(), "the buffer-backed fragment was rejected:\n" + result.output());
    }

    @Test
    @DisplayName("two devices that take the same road are one cache entry; two roads are two")
    void theBackingIsInTheKey() {
        SdfScene small = spheres(3);
        assertEquals(new SdfComposer(ParamBacking.on(128)).keyFor(small),
                new SdfComposer(ParamBacking.on(256)).keyFor(small),
                "a three-parameter scene takes the push road on both, so it is one shader");

        SdfScene large = spheres(40);
        assertNotEquals(new SdfComposer(ParamBacking.on(128)).keyFor(large),
                new SdfComposer(ParamBacking.on(256)).keyFor(large),
                "forty parameters take different roads, and a cache that missed that would be wrong");
    }

    @Test
    @DisplayName("a parametric scene's SPIR-V passes spirv-val")
    void parametricSpirvValidates() {
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");

        Surface driven = new Surface.Twist(Scalar.Param.over(0, 0.8), Scalar.of(2),
                Surface.Rotate.aboutY(Scalar.Param.over(0, Math.PI),
                        new Surface.Box(Scalar.of(0), Scalar.of(1), Scalar.of(3),
                                Scalar.Param.over(0.25, 1.5), Scalar.of(1), Scalar.of(1))));
        SdfScene scene = SdfScene.of(Surface.smoothUnion(Scalar.Param.over(2, 12),
                Surface.Plane.ground(), driven));

        NativeTools.ValidationResult result = tools.validate(SdfComposer.fragmentSpirv(scene));
        assertTrue(result.valid(), "parametric fragment rejected by spirv-val:\n" + result.output());
    }

    private static List<Expr.PushConstantRead> pushConstantReads(Region region) {
        List<Expr.PushConstantRead> found = new ArrayList<>();
        for (Statement statement : region.statements()) {
            if (statement instanceof Statement.Return ret && ret.value() != null) {
                collect(ret.value(), found);
            }
        }
        return found;
    }

    private static void collect(Expr e, List<Expr.PushConstantRead> found) {
        switch (e) {
            case Expr.PushConstantRead read -> found.add(read);
            case Expr.Binary b -> {
                collect(b.lhs(), found);
                collect(b.rhs(), found);
            }
            case Expr.Unary u -> collect(u.operand(), found);
            case Expr.MathCall m -> m.args().forEach(a -> collect(a, found));
            case Expr.VectorConstruct v -> v.components().forEach(c -> collect(c, found));
            case Expr.VectorExtract v -> collect(v.vector(), found);
            case Expr.Convert c -> collect(c.operand(), found);
            default -> {
                // A leaf.
            }
        }
    }
}
