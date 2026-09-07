package dev.vexelray.vulkan.present;

import dev.vexelray.vulkan.vk.VulkanDevice;

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

    /**
     * The device this image's descriptor set belongs to.
     *
     * <p>The one exception to the paragraph above, and it is not a capability — it is <b>identity</b>. A
     * descriptor set is meaningless to any device but the one that allocated it, so a drawing layer that binds
     * an image needs to know it is allowed to, and that is a different question from what the image is made of.
     *
     * <p>It is here because the alternative was for the drawing layer to know provenance it cannot know. A
     * headless capture builds its own device and walks a tree it did not build; when that tree holds a viewport
     * marched by a window's device, the two are incompatible and nothing in the descriptor set says so. The
     * frame then comes out correct about the chrome and silently wrong about the content — a screenshot that
     * looks like a screenshot. Asking the image is the only way to tell.
     */
    VulkanDevice device();
}
