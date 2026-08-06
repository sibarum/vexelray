package dev.vexelray.resource;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Owns the lifetime of GPU memory resources — the "flexible buffer management" pillar. Allocation, host uploads,
 * and destruction all funnel through here so the engine has one place that knows what exists on the device and
 * can tear it all down deterministically (VexelRay owns its Vulkan runtime; there is no driver GC).
 *
 * <p>Callers hold only {@link GpuBuffer} handles, never raw Vulkan objects. This is the seam a pass binds its
 * inputs through and the seam the frame loop updates per-frame uniforms through.
 *
 * <p>API-design-only: the reference implementation backs these with Vulkan allocations later; for now the
 * contract fixes the shape (handle-based, usage/domain-typed, explicit lifetime).
 */
public interface ResourceManager extends AutoCloseable {

    /**
     * Allocate a buffer of {@code sizeBytes} for the given roles and memory domain. The returned handle is valid
     * until {@link #destroy(GpuBuffer)} or {@link #close()}.
     */
    GpuBuffer allocateBuffer(String debugName, long sizeBytes, Set<BufferUsage> usage, MemoryDomain domain);

    /**
     * Copy {@code data} into {@code buffer} at {@code offsetBytes}. For a host-visible buffer this is a mapped
     * write; for a device-local buffer the manager stages and enqueues a transfer. {@code data}'s remaining bytes
     * must fit within the buffer from the offset.
     */
    void upload(GpuBuffer buffer, long offsetBytes, ByteBuffer data);

    /** Release {@code buffer}. Using its handle afterwards is an error. Idempotent for an already-destroyed handle. */
    void destroy(GpuBuffer buffer);

    /** Free every resource still outstanding. Called during engine shutdown, after the device is idle. */
    @Override
    void close();
}
