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

    /**
     * Make the window visible. Idempotent. A window is created hidden so that the (potentially slow) Vulkan
     * bring-up runs while nothing is on screen; the present loop calls this once the first frame is ready, so the
     * window appears already painted instead of flashing blank/unresponsive during initialization.
     */
    void show();

    /** Whether {@code key} is currently held down (updated by {@link #pumpEvents()}). */
    boolean isKeyDown(Key key);

    /** The mouse-cursor shapes a window can display over its client area. */
    enum Cursor { ARROW, TEXT }

    /**
     * Request the cursor shape shown over the client area (e.g. an I-beam over editable text). Takes effect the
     * next time the OS queries the cursor (typically the next pointer move). The default is a no-op for platforms
     * without cursor control yet.
     */
    default void setCursor(Cursor cursor) {
        // no-op by default
    }

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
