package dev.vexelray.technique.mesh;

import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shadings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the mesh shader is well-typed {@code core} IR, by lowering it — {@code lower} runs {@code CoreCheck} first,
 * so composing the shader is the check. The fragment stage is lowered under more than one lighting model, because
 * a {@code Shading} is free to read any field of the point and a model that reads one the others ignore is where a
 * shape mismatch would hide.
 */
class MeshShaderTest {

    @Test
    void theVertexStageIsWellTypedAndLowers() {
        ComposedShader vertex = MeshShader.vertex(ClipDepth.DEFAULT);
        assertTrue(vertex.spirv().length > 0);
        assertEquals(0, vertex.spirv().length % 4, "SPIR-V is a stream of 32-bit words");
    }

    @Test
    void theFragmentStageLowersUnderEveryStockShading() {
        assertTrue(MeshShader.fragment(Shadings.unlit()).spirv().length > 0);
        assertTrue(MeshShader.fragment(Shadings.defaultKeyLight()).spirv().length > 0);
        assertTrue(MeshShader.fragment(Shadings.lambert(0.3, 1, -0.4, 0.9, 0.1)).spirv().length > 0);
    }

    @Test
    void theDepthConventionReachesTheVertexStage() {
        byte[] shallow = MeshShader.vertex(new ClipDepth(0.05, 100)).spirv();
        byte[] deep = MeshShader.vertex(new ClipDepth(0.05, 4000)).spirv();
        assertFalse(java.util.Arrays.equals(shallow, deep),
                "two far planes produced identical SPIR-V — the depth convention was not baked in");
    }

    @Test
    void bothStagesAgreeAboutThePushConstantBlock() {
        assertEquals(MeshShader.PUSH_FLOATS, MeshShader.block().members().size());
        assertEquals("eyeX", MeshShader.block().members().get(MeshShader.PUSH_EYE).name());
        assertEquals("focalLength", MeshShader.block().members().get(MeshShader.PUSH_FOCAL).name());
        assertEquals("tintR", MeshShader.block().members().get(MeshShader.PUSH_TINT).name());
        assertEquals(Placement.MATRIX_FLOATS, MeshShader.PUSH_EYE - MeshShader.PUSH_MODEL);
    }
}
