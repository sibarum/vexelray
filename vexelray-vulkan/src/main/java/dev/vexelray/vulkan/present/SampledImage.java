package dev.vexelray.vulkan.present;

/**
 * Something a fragment stage can read: a combined image sampler already written into a descriptor set.
 *
 * <p>This is the whole of what a drawing layer needs to know about a texture in order to show it, and deliberately
 * no more — not its format, not its memory, not whether it was uploaded from a PNG or marched into this frame by
 * another pipeline. That is what lets an {@link AtlasTexture} and a {@link SampledColorTarget} be handed to
 * {@code Canvas.image} interchangeably, and it is why a viewport costs the canvas nothing conceptual: a rendered
 * scene and a decoded image arrive at the batch as the same kind of thing.
 *
 * <p>Implementations own the underlying objects and stay valid until closed; a set handed to a frame that is still
 * in flight must outlive it.
 */
public interface SampledImage {

    /**
     * The {@code VkDescriptorSet} holding this image at binding 0, ready to bind at
     * {@code CanvasShader.IMAGE_SET}. Never 0 for a live image.
     */
    long descriptorSet();

    /** The {@code VkDescriptorSetLayout} that set was allocated against — what a pipeline layout is built from. */
    long descriptorSetLayout();
}
