package dev.vexelray.engine.vulkan;

import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.vulkan.vk.VulkanDevice;

/**
 * The {@link TechniqueContext} a Vulkan runtime hands to a technique at realise time — the Vulkan-bearing
 * subtype {@link RenderTechnique}'s javadoc tells techniques to cast to (docs/refactor-decisions.md D3).
 *
 * <p>The split is deliberate and worth stating from this side of it. {@link TechniqueContext} carries formats,
 * an extent, and a render-pass handle as plain values, so a technique that only needs to build a pipeline
 * compiles against {@code vexelray-engine-api} and never sees Vulkan. A technique that must <em>create</em>
 * objects — a buffer, a descriptor set, a sampled image — needs the device, and there is no honest way to hand
 * that over without naming Vulkan. So it is named here rather than smuggled into the public contract as an
 * opaque {@code long} that only one implementation could interpret.
 *
 * <p>A technique that casts has coupled itself to Vulkan, and that is the intended trade rather than a leak: it
 * is what "a backend abstraction is deferred — YAGNI until a second backend exists" costs, paid at the point
 * where it is visible.
 *
 * <h2>Why this is its own module (D18)</h2>
 *
 * <p>It used to be a record in {@code vexelray-engine}, which meant every technique module compile-depended on
 * the concrete runtime — its window, its swapchain, its frame loop — to reach one accessor. That pointed the
 * dependency arrow the wrong way (the runtime should depend on what techniques are written against, not the
 * reverse) and coupled every technique to one engine, so a second Vulkan runtime — an offscreen one for tests,
 * a runtime embedding into somebody else's swapchain — could not have served the techniques that already
 * exist. As an interface in a module of its own, it can.
 *
 * <p>An interface rather than a record for the same reason: what a technique needs is the device, not one
 * runtime's particular way of carrying it alongside four other fields.
 *
 * <pre>{@code
 * @Override
 * public void realize(TechniqueContext ctx) {
 *     VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
 *     this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(), ...);
 * }
 * }</pre>
 */
public interface VulkanTechniqueContext extends TechniqueContext {

    /**
     * The device every Vulkan object a technique creates must belong to, and the one it resolves command
     * handles against.
     *
     * <p>Owned by the runtime and outliving the technique: do not destroy it, and do not keep it past
     * {@link RenderTechnique#close()}.
     */
    VulkanDevice device();
}
