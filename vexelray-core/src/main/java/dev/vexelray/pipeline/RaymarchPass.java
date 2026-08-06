package dev.vexelray.pipeline;

import dev.vexelray.lighting.LightingModel;

import java.util.List;
import java.util.Optional;

/**
 * A pass that ray-marches a signed-distance scene over a fullscreen triangle (SupirVast's {@code Fullscreen}
 * vertex primitive) — the SDF half of the hybrid engine. The SDF scene + {@link LightingModel} compose into a
 * fullscreen fragment shader at build time.
 *
 * <p>Sharing a depth attachment with a {@link RasterPass} is what makes the hybrid coherent: the ray-marcher can
 * read rasterised depth to occlude marched surfaces behind polygons, and write depth so later polygons occlude
 * behind SDF surfaces. Whether it reads, writes, or ignores depth is expressed through {@link #depth()} and
 * {@link #reads()}.
 *
 * @param name     unique pass name
 * @param writes   colour targets written
 * @param reads    attachments sampled as input (e.g. the raster pass's depth, for correct occlusion)
 * @param depth    the depth attachment tested/written, if the march participates in depth
 * @param lighting the lighting model folded into the generated fragment shader
 */
public record RaymarchPass(String name, List<String> writes, List<String> reads,
                           Optional<String> depth, LightingModel lighting) implements Pass {

    public RaymarchPass {
        writes = List.copyOf(writes);
        reads = List.copyOf(reads);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must be non-blank");
        }
        if (writes.isEmpty()) {
            throw new IllegalArgumentException("a ray-march pass must write at least one colour target");
        }
    }

    @Override
    public PassKind kind() {
        return PassKind.RAYMARCH;
    }
}
