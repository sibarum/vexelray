package dev.vexelray.target;

/**
 * A GPU image a drawing layer can refer to without knowing what one is — the seam between "this quad shows an
 * image" and "this image is a {@code VkImageView} in a descriptor set".
 *
 * <p>It declares nothing, deliberately. The whole content of the type is <em>which</em> side of the seam a
 * value came from: something a binding layer minted and can bind, rather than any object at all. That is worth
 * a type because the alternative — and what {@code Canvas.Run.image} was — is {@code Object}, which accepts a
 * {@code String}, a {@code float[]}, or a stale handle from another device, and the first thing that notices is
 * a texture reading as garbage or a validation error several layers away.
 *
 * <p>It lives in {@code vexelray-core} for the same reason {@link Target} and {@link AttachmentFormat} do: it
 * is vocabulary both a feature module and the substrate need, and the two must not have to depend on each
 * other to share it. {@code vexelray-canvas} takes and returns these; {@code vexelray-vulkan}'s
 * {@code SampledImage} and {@code AtlasTexture} are them. Neither module names the other.
 *
 * <p>A binding layer still casts to its own concrete type to reach a descriptor set, and that cast is the
 * deferred backend abstraction being paid for at the point where it is visible (docs/refactor-decisions.md
 * D3) — but it is now a cast between two things that are both images, which fails loudly and locally when it
 * is wrong.
 */
public interface ImageHandle {
}
