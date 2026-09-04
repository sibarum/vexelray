package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.tools.NativeTools;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.ShaderCache;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static dev.vexelray.ir.Ir.POINT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdfComposerTest {

    private static final int SPIRV_MAGIC = 0x07230203;

    private final SdfComposer composer = new SdfComposer();

    /** A scene with a bit of everything: blended primitives, a carve, and a normalised implicit. */
    private static SdfScene scene() {
        return SdfScene.of(Surface.smoothUnion(6.0,
                Surface.Plane.ground(),
                new Surface.Sphere(0, 1, 3, 1),
                new Surface.Difference(
                        new Surface.Box(2, 1, 3, 0.6, 0.6, 0.6),
                        new Surface.Sphere(2, 1.6, 3, 0.5)),
                new Surface.Translate(-2, 1, 3,
                        new Surface.Implicit(dev.vexelray.ir.Ir.sub(
                                dev.vexelray.ir.Ir.dot(POINT, POINT),
                                dev.vexelray.ir.Ir.f(1.0))))));
    }

    @Test
    @DisplayName("composing a scene produces a fullscreen vertex and a ray-march fragment")
    void composesBothStages() {
        List<ComposedShader> shaders = composer.compose(scene());
        assertEquals(List.of(ShaderStage.VERTEX, ShaderStage.FRAGMENT), composer.stages());
        assertEquals(2, shaders.size());
        assertEquals(ShaderStage.VERTEX, shaders.get(0).stage());
        assertEquals(ShaderStage.FRAGMENT, shaders.get(1).stage());
        for (ComposedShader shader : shaders) {
            assertEquals(SPIRV_MAGIC, firstWord(shader.spirv()), "not a SPIR-V module");
            assertEquals("main", shader.entryPoint());
        }
    }

    @Test
    @DisplayName("the vertex stage writes the varying the fragment reads")
    void stagesAgreeOnTheirInterface() {
        // The bug this pins rendered every frame a single flat colour, and nothing reported it.
        //
        // Fullscreen offers two vertex stages: triangleVertexSpirv, which writes only gl_Position, and
        // triangleVertexWithUvSpirv, which also emits vUv. The march fragment reads vUv -- it is where a
        // pixel's screen position comes from, and so the only thing making one ray differ from another.
        // Paired with the first, the fragment's input is never written by anything: every pixel marches the
        // identical ray and the frame comes out one colour, uniformly.
        //
        // spirv-val cannot catch this and never will. It validates a module, and BOTH modules are valid --
        // a shader input that no earlier stage writes is legal SPIR-V, its value merely undefined. The defect
        // lives in the gap between two individually correct things, which is why the check has to be made
        // here, across the pair, rather than left to the validator that already runs below.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-tools not bundled for this platform");
        List<ComposedShader> shaders = composer.compose(scene());
        String vertex = tools.disassemble(shaders.get(0).spirv());
        String fragment = tools.disassemble(shaders.get(1).spirv());
        assertTrue(fragment.contains("%_ptr_Input_v2float Input"),
                "the fragment is expected to read a vec2 varying; if that changed, so must this test");
        assertTrue(vertex.contains("%_ptr_Output_v2float Output"),
                "the vertex stage writes no vec2 varying, so the fragment's is undefined:\n" + vertex);
    }

    @Test
    @DisplayName("the generated SPIR-V passes spirv-val")
    void generatedSpirvIsValid() {
        // The real check. Everything else here inspects bytes we produced ourselves; this asks Khronos's own
        // validator whether the module is legal — which is what the driver will effectively do at pipeline
        // creation, except there the failure arrives as a device error a long way from the cause.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-val not bundled for this platform");
        for (ComposedShader shader : composer.compose(scene())) {
            NativeTools.ValidationResult result = tools.validate(shader.spirv());
            assertTrue(result.valid(), shader.stage() + " rejected by spirv-val:\n" + result.output());
        }
    }

    @Test
    @DisplayName("composing the same scene twice gives byte-identical SPIR-V")
    void compositionIsDeterministic() {
        // ShaderKey's contract is that equal keys map to byte-identical SPIR-V. If composition were not
        // deterministic the cache would be unsound — it would hand out a shader that does not match the key it
        // was asked for, and the mismatch would show up as a scene rendering as some other scene.
        assertArrayEquals(SdfComposer.fragmentSpirv(scene()), SdfComposer.fragmentSpirv(scene()));
    }

    @Test
    @DisplayName("the field is emitted once, and sampled exactly eight times per pixel")
    void fieldIsEmittedOnceAndSampledEightTimes() {
        // Two separate guards, both of which have already caught something.
        //
        // One definition (D12): inlining the field at every use multiplied one field's shader to 22 MB.
        //
        // Eight CALLS: one per march step, one for the final hit test, six for the normal's central differences.
        // Any more means something downstream of the normal is being recomputed rather than reused. This started
        // at twenty, because the Lambert model broadcasts its diffuse term into three colour channels and the
        // normal came along with it each time — the reason Shading.shade is handed a Bindings.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-tools not bundled for this platform");
        String disassembly = tools.disassemble(SdfComposer.fragmentSpirv(scene()));
        assertEquals(1, countOccurrences(disassembly, "= OpFunction %float "),
                "expected exactly one float-returning function: the field");
        assertEquals(8, countOccurrences(disassembly, "OpFunctionCall"),
                "expected 8 field samples per pixel (1 march + 1 hit + 6 normal taps)");
    }

    /** A scene whose geometry carries colour: a stroke running red to blue, over the plain ground plane. */
    private static SdfScene colouredScene() {
        return SdfScene.of(Surface.union(
                Surface.Plane.ground(),
                new Surface.Stroke(List.of(
                        new Surface.Stroke.Vertex(-2, 1, 3, 0.2, 1)
                                .painted(new Surface.Rgb(0.9, 0.1, 0.1)),
                        new Surface.Stroke.Vertex(0, 2, 3, 0.3, 1)
                                .painted(new Surface.Rgb(0.9, 0.9, 0.1)),
                        new Surface.Stroke.Vertex(2, 1, 3, 0.2, 1)
                                .painted(new Surface.Rgb(0.1, 0.1, 0.9))))));
    }

    @Test
    @DisplayName("a surface with no colour of its own emits no colour function at all")
    void uncolouredScenesCostNothing() {
        // The invariant the whole module is built around, in its colour spelling: a feature nobody used must
        // leave no trace in the output. An extra function here — even an unused one — would break the parity
        // this composer is meant to hold.
        assertNull(SdfComposer.albedoFunction(scene()));
    }

    @Test
    @DisplayName("a coloured surface adds one function, called once, and the module still validates")
    void colourAddsOneFunctionCalledOnce() {
        // The cost story worth pinning. Colour is a second function roughly the size of the field, because
        // picking a colour out of a union means re-testing the same distances — but it is called ONCE, at the
        // hit point, where the field is called eight times. If this ever reads more than nine calls, something
        // has started evaluating the colour inside the march.
        NativeTools tools = new NativeTools();
        Assumptions.assumeTrue(tools.isAvailable(), "spirv-tools not bundled for this platform");

        assertNotNull(SdfComposer.albedoFunction(colouredScene()));
        String disassembly = tools.disassemble(SdfComposer.fragmentSpirv(colouredScene()));
        assertEquals(1, countOccurrences(disassembly, "= OpFunction %float "),
                "expected exactly one float-returning function: the field");
        assertEquals(1, countOccurrences(disassembly, "= OpFunction %v3float "),
                "expected exactly one vec3-returning function: the colour");
        assertEquals(9, countOccurrences(disassembly, "OpFunctionCall"),
                "expected 8 field samples plus a single colour read");

        for (ComposedShader shader : composer.compose(colouredScene())) {
            NativeTools.ValidationResult result = tools.validate(shader.spirv());
            assertTrue(result.valid(), shader.stage() + " rejected by spirv-val:\n" + result.output());
        }
    }

    @Test
    @DisplayName("an equal scene is the same cache entry; a differently lit one is not")
    void cacheCollapsesEqualScenesOnly() {
        ShaderCache cache = new ShaderCache();
        SdfScene one = scene();
        SdfScene other = scene();                       // built separately, structurally equal
        assertEquals(one, other);

        List<ComposedShader> first = cache.shadersFor(composer, one);
        List<ComposedShader> second = cache.shadersFor(composer, other);
        assertEquals(1, cache.size(), "equal scenes should share one compiled shader set");
        assertTrue(first == second, "the second request should be served from the cache, not recompiled");

        // A different sun must not collide: LightingModel's contract is that equal ids emit equal IR, so the
        // Lambert id carries its parameters. Were it just "lambert", this scene would render with the other's
        // light — a cache hit that is silently wrong.
        SdfScene relit = one.withShading(Shadings.lambert(0, 1, 0, 1.0, 0.2));
        cache.shadersFor(composer, relit);
        assertEquals(2, cache.size());
        assertNotEquals(composer.keyFor(one), composer.keyFor(relit));
        assertFalse(java.util.Arrays.equals(
                SdfComposer.fragmentSpirv(one), SdfComposer.fragmentSpirv(relit)));
    }

    @Test
    @DisplayName("per-frame camera state is push constants, so moving the camera does not recompile")
    void cameraDoesNotAffectTheShader() {
        // The whole point of the push-constant block: turning your head or resizing the window is a 24-byte
        // upload, not a shader compile. Nothing about the camera is part of the cache key.
        ShaderCache cache = new ShaderCache();
        cache.shadersFor(composer, scene());
        cache.shadersFor(composer, scene());
        assertEquals(1, cache.size());

        byte[] block = SdfComposer.cameraBytes(1.5, 2.0, -3.25, 0.75, -0.25, 16.0 / 9.0);
        assertEquals(SdfComposer.CAMERA_BYTES, block.length);
        ByteBuffer read = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5f, read.getFloat());
        assertEquals(2.0f, read.getFloat());
        assertEquals(-3.25f, read.getFloat());
        assertEquals(0.75f, read.getFloat());
        assertEquals(-0.25f, read.getFloat());
        assertEquals((float) (16.0 / 9.0), read.getFloat());
    }

    @Test
    @DisplayName("the shader's field is the same function a host lowers to the CPU")
    void theFieldIsSharedWithSimulation() {
        // render == sim: the fragment calls this exact function, and it is CPU-lowerable core IR. A surface
        // typed in at runtime is collidable without a second implementation of it existing anywhere.
        SdfScene scene = scene();
        assertEquals(SdfComposer.SDF_FUNCTION, SdfComposer.sdfFunction(scene).name());
        assertEquals(SdfComposer.field(scene).asFunction(SdfComposer.SDF_FUNCTION),
                SdfComposer.sdfFunction(scene));
        assertTrue(SdfComposer.field(scene).isMarchable());
    }

    @Test
    @DisplayName("a scene with an unlit model composes too, and differs from a lit one")
    void unlitComposes() {
        SdfScene unlit = scene().withShading(Shadings.unlit());
        NativeTools tools = new NativeTools();
        if (tools.isAvailable()) {
            assertTrue(tools.validate(SdfComposer.fragmentSpirv(unlit)).valid());
        }
        assertFalse(java.util.Arrays.equals(
                SdfComposer.fragmentSpirv(unlit), SdfComposer.fragmentSpirv(scene())));
    }

    @Test
    @DisplayName("nonsense settings are refused where they are written, not where they render")
    void settingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> MarchSettings.DEFAULT.withSteps(0));
        assertThrows(IllegalArgumentException.class,
                () -> new MarchSettings(64, 0, 100, 0.01, 0, 0.01, 0));
        assertThrows(IllegalArgumentException.class, () -> Shadings.lambert(0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Shadings.lambert(0, 1, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SdfScene(Surface.Plane.ground(), Shadings.unlit(), MarchSettings.DEFAULT,
                        new SdfScene.Rgb(1, 1, 1), new SdfScene.Rgb(0, 0, 0), 0));
    }

    private static int firstWord(byte[] spirv) {
        return ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
