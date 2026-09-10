package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.vulkan.vk.VkLoader;

/**
 * How {@link VexelEngine#create} finds this runtime: a {@link EngineProvider} service, so an application depends
 * on {@code vexelray-engine-api} and gets a Vulkan engine because this module is on the path — not because
 * anything in the public API named it.
 */
public final class VulkanEngineProvider implements EngineProvider {

    @Override
    public String name() {
        // The same string VulkanEngine reports in EngineEvents.RunStarted, so a log line and a "found but
        // declined" message name the runtime identically.
        return VulkanEngine.ENGINE_NAME;
    }

    /**
     * Whether this machine has both halves the runtime needs: a Vulkan loader, and an OS backend that can make
     * a window and a surface.
     *
     * <p>Checked here rather than left to fail in the constructor because a missing loader and a missing
     * platform backend are the two most common ways a fresh checkout does not run, and both produce link errors
     * from deep inside Panama that name a symbol rather than a cause. Answering {@code false} lets
     * {@code VexelEngine.create} report "found but declined" with this provider's name in it.
     */
    @Override
    public boolean isAvailable() {
        try {
            VkLoader.getInstanceProcAddrPointer();
            NativePlatform.current();
            return true;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    @Override
    public VexelEngine create(EngineConfig config) {
        return new VulkanEngine(config);
    }
}
