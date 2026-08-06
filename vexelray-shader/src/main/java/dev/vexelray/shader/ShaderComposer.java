package dev.vexelray.shader;

import dev.supirvast.vastir.core.ShaderStage;

import java.util.List;

/**
 * The heart of VexelRay: a component that <em>generates a shader stage at runtime</em> by assembling SupirVast
 * {@code core} IR from an engine-level description, then lowering it (via {@link ComposedShader#lower}).
 *
 * <p>A composer is deterministic in its inputs: the same description must compose the same {@code core} module,
 * so results can be cached by {@link ShaderKey}. This is what makes runtime shader generation cheap — a material
 * or SDF scene is compiled once per distinct configuration, not once per frame.
 *
 * <p>Concrete composers live alongside the concept they compile:
 * <ul>
 *   <li>a <b>material composer</b> turns a surface/material graph into a vertex+fragment pair for the raster path;</li>
 *   <li>an <b>SDF composer</b> turns a signed-distance scene into a fullscreen fragment for the ray-march path
 *       (building on SupirVast's {@code Fullscreen} vertex primitive);</li>
 *   <li>a <b>post composer</b> turns a chain of image operations into a fullscreen fragment.</li>
 * </ul>
 * Each folds in a {@link dev.vexelray.lighting.LightingModel} where lighting is involved, so the lighting model
 * is a plug-in to composition rather than a fixed branch in the shader.
 *
 * @param <D> the engine-level description this composer compiles (a material graph, an SDF scene, …)
 */
public interface ShaderComposer<D> {

    /** The stages this composer emits for a given description (e.g. VERTEX+FRAGMENT for a material). */
    List<ShaderStage> stages();

    /**
     * Compose and lower every stage for {@code description}. Implementations build one {@code core.CoreModule}
     * per stage and return the lowered SPIR-V. Pure with respect to {@code description}.
     */
    List<ComposedShader> compose(D description);

    /**
     * A stable cache key for {@code description}: two descriptions with the same key must compose to identical
     * SPIR-V. The engine's shader cache keys generated pipelines on this so identical materials/scenes share one
     * compiled shader set. Default derives from the description's own {@code equals}/{@code hashCode}; override
     * when structural (not reference) identity is what should collapse.
     */
    default ShaderKey keyFor(D description) {
        return ShaderKey.of(getClass(), description);
    }
}
