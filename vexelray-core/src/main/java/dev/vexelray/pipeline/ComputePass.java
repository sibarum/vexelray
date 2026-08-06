package dev.vexelray.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * A pass that dispatches a compute kernel — reachable through SupirVast's compute path ({@code GpuContext} /
 * {@code Accelerator}) rather than a graphics pipeline. Useful for pre-pass work that feeds the raster/ray-march
 * passes: culling, building an SDF primitive table, particle simulation, prefix sums.
 *
 * <p>Compute passes read and write storage buffers, not framebuffer attachments, so {@link #writes()} names the
 * storage resources produced and {@link #depth()} is always empty. The frame graph still orders it by its
 * read/write names so a later pass sees its results.
 *
 * @param name          unique pass name
 * @param reads         storage resource names consumed
 * @param writes        storage resource names produced
 * @param groupCountX,groupCountY,groupCountZ the dispatch dimensions (workgroup counts)
 */
public record ComputePass(String name, List<String> reads, List<String> writes,
                          int groupCountX, int groupCountY, int groupCountZ) implements Pass {

    public ComputePass {
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("pass name must be non-blank");
        }
        if (groupCountX <= 0 || groupCountY <= 0 || groupCountZ <= 0) {
            throw new IllegalArgumentException("dispatch group counts must be positive");
        }
    }

    @Override
    public Optional<String> depth() {
        return Optional.empty();
    }

    @Override
    public PassKind kind() {
        return PassKind.COMPUTE;
    }
}
