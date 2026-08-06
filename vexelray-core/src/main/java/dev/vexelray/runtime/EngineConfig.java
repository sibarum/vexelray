package dev.vexelray.runtime;

/**
 * Static configuration for a {@link VexelEngine} instance: the application identity the Vulkan instance reports,
 * the {@link SurfaceTarget} to present to, and toggles that must be known before the device exists (validation
 * layers, preferred frames-in-flight). Everything here is fixed for the engine's lifetime; per-frame state is not.
 *
 * @param applicationName reported to Vulkan via {@code VkApplicationInfo}
 * @param surface         where the engine presents
 * @param validation      enable Vulkan validation layers (development) — off for native-image/release
 * @param framesInFlight  how many frames the CPU may record ahead of the GPU (typically 2)
 */
public record EngineConfig(String applicationName, SurfaceTarget surface, boolean validation, int framesInFlight) {

    public EngineConfig {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must be non-blank");
        }
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        if (framesInFlight < 1 || framesInFlight > 3) {
            throw new IllegalArgumentException("framesInFlight must be 1..3, got " + framesInFlight);
        }
    }

    /** A windowed development configuration: validation on, double-buffered, vsync. */
    public static EngineConfig windowed(String applicationName, String title, int width, int height) {
        return new EngineConfig(applicationName, new SurfaceTarget.Windowed(title, width, height, true), true, 2);
    }

    /** A headless configuration for tests and offline rendering: no window, validation on, single frame. */
    public static EngineConfig offscreen(String applicationName, int width, int height) {
        return new EngineConfig(applicationName, new SurfaceTarget.Offscreen(width, height), true, 1);
    }
}
