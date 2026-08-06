package dev.vexelray.shader;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.lower.CoreToSpirv;

/**
 * One stage of a runtime-generated shader: validated SPIR-V plus the entry-point name and stage it fills. This
 * is VexelRay's boundary object with SupirVast — the {@link CoreModule} authored by a {@link ShaderComposer}
 * has been lowered to bytes here, and everything downstream (pipeline creation, descriptor binding) speaks only
 * in these terms.
 *
 * <p>Producing one is deliberately a single call: {@link #lower(ShaderStage, CoreModule, String)} runs
 * SupirVast's {@link CoreToSpirv}. VexelRay never emits SPIR-V itself; it emits {@code core} IR and asks
 * SupirVast to lower it. That is the whole point of the split — the engine composes <em>meaning</em>, SupirVast
 * owns the <em>encoding</em>.
 *
 * @param stage      which pipeline stage this fills (vertex / fragment / compute)
 * @param spirv      validated SPIR-V words as little-endian bytes (length is a multiple of 4)
 * @param entryPoint the entry-point function name inside {@code spirv} (conventionally {@code "main"})
 */
public record ComposedShader(ShaderStage stage, byte[] spirv, String entryPoint) {

    public ComposedShader {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (spirv == null || spirv.length == 0 || spirv.length % 4 != 0) {
            throw new IllegalArgumentException("spirv must be non-empty and a multiple of 4 bytes");
        }
        if (entryPoint == null || entryPoint.isBlank()) {
            throw new IllegalArgumentException("entryPoint must be non-blank");
        }
    }

    /**
     * Lower a composed {@code core} module to a stage of SPIR-V via SupirVast. The single seam through which all
     * runtime-generated shaders in VexelRay reach the GPU.
     */
    public static ComposedShader lower(ShaderStage stage, CoreModule module, String entryPoint) {
        byte[] spirv = new CoreToSpirv().lower(module).toByteArray();
        return new ComposedShader(stage, spirv, entryPoint);
    }
}
