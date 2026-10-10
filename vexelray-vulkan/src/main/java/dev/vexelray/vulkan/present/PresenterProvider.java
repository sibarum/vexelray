package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.os.NativeWindow;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * A way of presenting other than a Vulkan swapchain, offered by a platform module through {@link ServiceLoader} —
 * so the code that owns windows can use it without depending on the module, which only exists on its platform.
 *
 * <p>The order a host follows: {@link #find} it, make the {@link VulkanDevice} with {@link #deviceExtensions()} and
 * (if {@link #timelineSemaphore()}) timeline semaphores, {@link #open} a {@link Backend} on that device, then per
 * window build a render pass of {@link Backend#colorFormat()} ending in {@link Backend#finalLayout()}, a pipeline on
 * it, and {@link Backend#create} the window's presenter. Whether anything failing on the way is fatal is the host's
 * call: {@code GuiApp} refuses to start on Windows without DXGI, because the swapchain there is too fragile.
 */
public interface PresenterProvider {

    /** The name a host finds this by, e.g. {@code "dxgi"}. */
    String name();

    /** Whether this can work on the running platform at all (the OS, not the GPU — {@link #open} finds that out). */
    boolean supported();

    /** Device extensions the {@link VulkanDevice} must be made with. */
    List<String> deviceExtensions();

    /** Whether the device must be made with timeline semaphores. */
    boolean timelineSemaphore();

    /** What this needs per device; fails if the device or driver cannot do it. */
    Backend open(VulkanInstance instance, VulkanDevice device);

    /** One device's worth of this presentation path, shared by every window on it. */
    interface Backend extends AutoCloseable {

        /** The {@code VkFormat} a window's render pass must draw in. */
        int colorFormat();

        /** The final layout of that render pass's colour attachment. */
        int finalLayout();

        /**
         * The presenter for {@code window}, drawing with {@code pipeline} through {@code renderPass} (built as
         * above). The window must have no Vulkan surface or swapchain of its own.
         */
        WindowPresenter create(NativeWindow window, long renderPass, GraphicsPipeline pipeline);

        /** Release the device's share; every presenter made from this is already closed. */
        @Override
        void close();
    }

    /** The provider called {@code name} on the class path, if there is one and it is supported here. */
    static Optional<PresenterProvider> find(String name) {
        for (PresenterProvider provider : ServiceLoader.load(PresenterProvider.class)) {
            if (provider.name().equals(name) && provider.supported()) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
