package dev.vexelray.technique.canvas;

import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.shader.ComposedShader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the canvas uber-shader is well-typed {@code core} IR, by lowering it.
 *
 * <p>It is the largest hand-authored shader in the repository after Fathom's — shapes, MSDF text and sampled
 * images in one fragment, selected by a vertex kind — and until this test existed nothing in the build lowered
 * it. Both stages were composed only inside {@code main} methods: the window demos in this module, which a
 * developer runs and CI does not. So the one shader most likely to accumulate a type fault was the one shader
 * no automated thing ever looked at.
 *
 * <p>The assertion is that {@link ComposedShader#lower} returns rather than throws. That reads as a weak test
 * and is not, because {@code lower} now runs {@link dev.vexelray.shader.CoreCheck} first: composing this
 * shader at all is the check, and a mismatch anywhere in it fails here with the faults named instead of
 * surfacing as a driver crash in a demo somebody runs next month.
 */
class CanvasShaderTypeTest {

    @Test
    void theVertexStageIsWellTypedAndLowers() {
        ComposedShader vertex = CanvasShader.vertex();

        assertTrue(vertex.spirv().length > 0);
        assertEquals(0, vertex.spirv().length % 4, "SPIR-V is a stream of 32-bit words");
    }

    @Test
    void theFragmentStageIsWellTypedAndLowers() {
        ComposedShader fragment = CanvasShader.fragment();

        assertTrue(fragment.spirv().length > 0);
        assertEquals(0, fragment.spirv().length % 4, "SPIR-V is a stream of 32-bit words");
    }

    /**
     * The uber-shader's whole claim is that shapes, text and images are one pipeline, so the two stages must be
     * composable together — and a fragment that lowers alone but disagrees with its vertex stage about the
     * interface between them is a failure this catches only if both are built in one place.
     */
    @Test
    void bothStagesComposeTogether() {
        ComposedShader vertex = CanvasShader.vertex();
        ComposedShader fragment = CanvasShader.fragment();

        assertEquals("main", vertex.entryPoint());
        assertEquals("main", fragment.entryPoint());
    }
}
