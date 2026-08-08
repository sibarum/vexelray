package dev.vexelray.os;

import java.lang.foreign.MemorySegment;

/**
 * A live OS window. The engine drives its event pump and asks it to produce a Vulkan surface. All platform
 * specifics (the HWND, Display*, or CAMetalLayer behind it) are hidden; callers see only handles as opaque longs.
 */
public interface NativeWindow extends AutoCloseable {

    /** Current framebuffer width in pixels (after DPI scaling). */
    int width();

    /** Current framebuffer height in pixels (after DPI scaling). */
    int height();

    /** Pump the OS event queue once. Returns {@code false} once the window has been asked to close. */
    boolean pumpEvents();

    /** Whether {@code key} is currently held down (updated by {@link #pumpEvents()}). */
    boolean isKeyDown(Key key);

    /**
     * Create a {@code VkSurfaceKHR} for this window. The OS module owns the platform surface struct and its
     * {@code vkCreate*SurfaceKHR} entry point; the Vulkan module stays platform-agnostic and only supplies the
     * instance and its function loader. This keeps every platform detail on this side of the seam.
     *
     * @param vkInstance            the {@code VkInstance} handle (opaque)
     * @param vkGetInstanceProcAddr a pointer to {@code vkGetInstanceProcAddr}, used to load the surface entry point
     * @return the created {@code VkSurfaceKHR} handle (opaque)
     */
    long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr);

    /** The raw OS window handle (HWND / X11 Window / NSWindow) — for logging and validation only. */
    long osHandle();

    @Override
    void close();
}
