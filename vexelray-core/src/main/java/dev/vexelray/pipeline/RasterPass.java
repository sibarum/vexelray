package dev.vexelray.pipeline;

import dev.vexelray.lighting.LightingModel;

import java.util.List;
import java.util.Optional;

/**
 * A pass that rasterises polygon meshes. Its material + {@link LightingModel} compose (at pipeline-build time)
 * into a vertex+fragment shader — the raster half of the hybrid engine. Meshes and their instance data are bound
 * from the {@link dev.vexelray.resource.ResourceManager}; this record only declares the pass's shape and targets.
 *
 * @param name     unique pass name
 * @param writes   colour targets written
 * @param reads    attachments sampled as input (usually empty for a geometry pass)
 * @param depth    the depth attachment tested/written, if depth testing is on
 * @param lighting the lighting model folded into the generated fragment shader
 */
public record RasterPass(String name, List<String> writes, List<String> reads,
                         Optional<String> depth, LightingModel lighting) implements Pass {

    public RasterPass {
        writes = List.copyOf(writes);
        reads = List.copyOf(reads);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must be non-blank");
        }
        if (writes.isEmpty()) {
            throw new IllegalArgumentException("a raster pass must write at least one colour target");
        }
    }

    @Override
    public PassKind kind() {
        return PassKind.RASTER;
    }
}
