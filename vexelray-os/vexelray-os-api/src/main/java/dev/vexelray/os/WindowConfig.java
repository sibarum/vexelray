package dev.vexelray.os;

/**
 * Requested properties of a native window. A {@link NativePlatform} realises these on the OS; the actual
 * framebuffer size after DPI scaling is read back from {@link NativeWindow#width()}/{@link NativeWindow#height()}.
 *
 * @param title      the window title
 * @param width      requested client width in logical pixels
 * @param height     requested client height in logical pixels
 * @param resizable  whether the user may resize the window
 */
public record WindowConfig(String title, int width, int height, boolean resizable) {

    public WindowConfig {
        if (title == null) {
            throw new IllegalArgumentException("title must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive, got " + width + "x" + height);
        }
    }

    /** A resizable window with the given title and size. */
    public static WindowConfig of(String title, int width, int height) {
        return new WindowConfig(title, width, height, true);
    }
}
