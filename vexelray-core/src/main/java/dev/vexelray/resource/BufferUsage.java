package dev.vexelray.resource;

/**
 * How a GPU buffer will be used — drives the Vulkan {@code VkBufferUsageFlags} the {@link ResourceManager} sets
 * at allocation. A buffer may combine roles (e.g. a mesh's interleaved vertices, or a storage buffer also fed as
 * a uniform); the manager unions the flags.
 */
public enum BufferUsage {
    /** Per-vertex attribute data read by a vertex stage (the raster path's interleaved layout). */
    VERTEX,
    /** Index data for indexed draws. */
    INDEX,
    /** Small, frequently-updated shader constants (uniform buffer). */
    UNIFORM,
    /** General read/write structured data (storage buffer) — light lists, SDF primitive tables, instance data. */
    STORAGE,
    /** Staging source for an upload to a device-local resource. */
    TRANSFER_SRC,
    /** Destination of an upload (a device-local buffer filled from a staging buffer). */
    TRANSFER_DST,
    /** Indirect draw/dispatch argument buffer. */
    INDIRECT
}
