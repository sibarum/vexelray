package dev.vexelray.resource;

import java.util.Set;

/**
 * An opaque handle to a GPU buffer owned by the {@link ResourceManager}. It carries description, not raw Vulkan
 * handles: the underlying {@code VkBuffer}/{@code VkDeviceMemory} live inside the manager, so nothing outside can
 * accidentally free or alias them. Passes and binders refer to buffers only through this handle.
 *
 * @param id       a manager-unique, stable identifier
 * @param debugName human-readable label for validation/RenderDoc output
 * @param sizeBytes the allocated size in bytes
 * @param usage    the roles this buffer was allocated for
 * @param domain   where its memory lives
 */
public record GpuBuffer(long id, String debugName, long sizeBytes, Set<BufferUsage> usage, MemoryDomain domain) {

    public GpuBuffer {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got " + sizeBytes);
        }
        if (usage == null || usage.isEmpty()) {
            throw new IllegalArgumentException("a buffer must declare at least one usage");
        }
        usage = Set.copyOf(usage);
    }
}
