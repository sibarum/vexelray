package dev.vexelray.technique.panel;

import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the panel shader is well-typed {@code core} IR, by lowering it.
 *
 * <p>The assertion is that {@link ComposedShader#lower} returns rather than throws, and it is not as weak as it
 * reads: {@code lower} runs {@code CoreCheck} first, so composing the shader <em>is</em> the check. This module
 * is where it matters most — the fragment stage is {@code CanvasShader}'s coverage core, the largest hand-authored
 * shader in the repository, spliced onto a different vertex stage and handed two expressions where it used to
 * have two constants. A shape mismatch there is a driver fault, not a compile error, and
 * {@code Ir.mul(vec2, float)} reaching a driver is how this project learned that.
 */
class PanelShaderTest {

    @Test
    void theVertexStageIsWellTypedAndLowers() {
        ComposedShader vertex = PanelShader.vertex(ClipDepth.DEFAULT);

        assertTrue(vertex.spirv().length > 0);
        assertEquals(0, vertex.spirv().length % 4, "SPIR-V is a stream of 32-bit words");
        assertEquals("main", vertex.entryPoint());
    }

    @Test
    void theFragmentStageIsWellTypedAndLowers() {
        ComposedShader fragment = PanelShader.fragment();

        assertTrue(fragment.spirv().length > 0);
        assertEquals(0, fragment.spirv().length % 4, "SPIR-V is a stream of 32-bit words");
        assertEquals("main", fragment.entryPoint());
    }

    /**
     * The near and far planes are baked in, so two scenes produce two different vertex stages.
     *
     * <p>Worth asserting because the alternative — a stage pre-compiled by the build, like the screen-space
     * canvas's — would silently carry one scene's depth convention into every other scene, and the symptom
     * would be a panel that occludes correctly in the demo it was written against and nowhere else.
     */
    @Test
    void theDepthConventionReachesTheVertexStage() {
        byte[] shallow = PanelShader.vertex(new ClipDepth(0.05, 100)).spirv();
        byte[] deep = PanelShader.vertex(new ClipDepth(0.05, 4000)).spirv();

        assertEquals(shallow.length, deep.length, "the same module should lower to the same shape");
        boolean differs = false;
        for (int i = 0; i < shallow.length && !differs; i++) {
            differs = shallow[i] != deep[i];
        }
        assertTrue(differs, "two far planes produced identical SPIR-V — the depth convention was not baked in");
    }

    /** The block both stages declare has to be one block, or the pipeline layout describes neither of them. */
    @Test
    void bothStagesAgreeAboutThePushConstantBlock() {
        assertEquals(PanelShader.PUSH_FLOATS, PanelShader.block().members().size());
        assertEquals(PanelShader.PUSH_FLOATS * Float.BYTES, PanelShader.PUSH_BYTES);
        assertEquals("halfHeightPx", PanelShader.block().members().get(PanelShader.PUSH_HALF_HEIGHT).name());
        assertEquals("focalLength", PanelShader.block().members().get(PanelShader.PUSH_FOCAL).name());
    }
}
