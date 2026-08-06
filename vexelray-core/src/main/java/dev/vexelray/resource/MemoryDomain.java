package dev.vexelray.resource;

/**
 * Where a resource's memory lives, and thus how it is written. The {@link ResourceManager} maps each domain to a
 * concrete Vulkan {@code VkMemoryPropertyFlags} selection (and, later, a suballocation strategy).
 */
public enum MemoryDomain {
    /** Fastest for the GPU, not host-mappable — written via a staging upload. Meshes, textures, static tables. */
    DEVICE_LOCAL,
    /** Host-mappable and coherent — written directly each frame. Per-frame uniforms, dynamic instance data. */
    HOST_VISIBLE,
    /** Host-mappable, GPU-preferred where the platform exposes it (ReBAR); manager falls back to HOST_VISIBLE. */
    HOST_VISIBLE_DEVICE_LOCAL
}
