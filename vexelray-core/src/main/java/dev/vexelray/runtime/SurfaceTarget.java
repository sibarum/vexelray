package dev.vexelray.runtime;

/**
 * Where the engine presents its final image. The runtime owns its Vulkan device regardless; this only selects
 * whether there is a swapchain-backed window or a headless offscreen target (for tests, native-image CI, or
 * offline rendering). Keeping it a sealed choice lets the runtime manager pick its surface setup exhaustively.
 */
public sealed interface SurfaceTarget permits SurfaceTarget.Windowed, SurfaceTarget.Offscreen {

    int width();

    int height();

    /** A GLFW window with a swapchain — the interactive path. */
    record Windowed(String title, int width, int height, boolean vsync) implements SurfaceTarget {
        public Windowed {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("window size must be positive");
            }
        }
    }

    /** A headless offscreen colour target rendered to an image (no window, no swapchain). */
    record Offscreen(int width, int height) implements SurfaceTarget {
        public Offscreen {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("offscreen size must be positive");
            }
        }
    }
}
