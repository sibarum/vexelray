package dev.vexelray.os;

/**
 * Requested properties of a native window. A {@link NativePlatform} realises these on the OS; the actual
 * framebuffer size after DPI scaling is read back from {@link NativeWindow#width()}/{@link NativeWindow#height()}.
 *
 * <p><b>Ownership.</b> {@code owner} is the raw OS handle of another window (0 for none). An owned window is the
 * portable "satellite" concept — Win32 owned windows, X11 {@code WM_TRANSIENT_FOR}, Wayland
 * {@code xdg_toplevel.set_parent}, macOS child windows — and buys the whole palette feel at once: no taskbar
 * button of its own, always above its owner, raised together with it when any window of the group is activated,
 * minimized and destroyed with it. It is <em>not</em> modality: the owner stays fully interactive.
 *
 * <p><b>Position.</b> {@code x}/{@code y} place the window's outer top-left in screen coordinates;
 * {@link #UNPOSITIONED} lets the OS choose. Width/height size the <em>outer</em> rect, which is what
 * {@link NativeWindow#screenX()}/{@code outerWidth()} report back — so persisted bounds round-trip exactly. That
 * stays true under {@link Decorations#CLIENT}: the frame is still there and still sized, it is simply drawn by
 * the application, so bounds saved from a system-framed window restore unchanged into a client-framed one.
 *
 * @param title       the window title
 * @param width       requested width (outer rect)
 * @param height      requested height (outer rect)
 * @param resizable   whether the user may resize the window
 * @param owner       raw OS handle of the owning window, or 0 for an ordinary top-level window
 * @param x           screen x of the outer top-left, or {@link #UNPOSITIONED} for OS placement
 * @param y           screen y of the outer top-left, or {@link #UNPOSITIONED} for OS placement
 * @param decorations who draws the frame (see {@link Decorations})
 */
public record WindowConfig(String title, int width, int height, boolean resizable, long owner, int x, int y,
                           Decorations decorations) {

    /** "Let the OS place it" — the default for {@link #x}/{@link #y}. */
    public static final int UNPOSITIONED = Integer.MIN_VALUE;

    public WindowConfig {
        if (title == null) {
            throw new IllegalArgumentException("title must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive, got " + width + "x" + height);
        }
        if (decorations == null) {
            decorations = Decorations.SYSTEM;
        }
    }

    /** An ordinary, unowned, OS-placed, OS-decorated window. */
    public WindowConfig(String title, int width, int height, boolean resizable) {
        this(title, width, height, resizable, 0L, UNPOSITIONED, UNPOSITIONED, Decorations.SYSTEM);
    }

    /** The five-argument form kept for callers that set ownership and placement only. */
    public WindowConfig(String title, int width, int height, boolean resizable, long owner, int x, int y) {
        this(title, width, height, resizable, owner, x, y, Decorations.SYSTEM);
    }

    /** A resizable window with the given title and size. */
    public static WindowConfig of(String title, int width, int height) {
        return new WindowConfig(title, width, height, true);
    }

    /** This window as a satellite of {@code ownerHandle} (see the class doc for what ownership means). */
    public WindowConfig ownedBy(long ownerHandle) {
        return new WindowConfig(title, width, height, resizable, ownerHandle, x, y, decorations);
    }

    /** This window placed at screen {@code (x, y)} instead of OS placement. */
    public WindowConfig at(int x, int y) {
        return new WindowConfig(title, width, height, resizable, owner, x, y, decorations);
    }

    /** This window with the given frame ownership — {@link Decorations#CLIENT} to draw the chrome yourself. */
    public WindowConfig decorations(Decorations decorations) {
        return new WindowConfig(title, width, height, resizable, owner, x, y, decorations);
    }
}
