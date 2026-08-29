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
     * Block until an OS event arrives or {@code timeoutNanos} elapses; {@link Long#MAX_VALUE} to wait
     * indefinitely.
     *
     * <p>What a render-on-demand loop needs and {@link #pumpEvents()} cannot provide: peek drains
     * whatever is there, this waits for there to be something. A loop with only the former has no
     * choice but to redraw continuously, so a still window costs a core.
     *
     * <p>Spurious returns are permitted. The caller pumps afterwards regardless, finds nothing, and
     * comes back — so an implementation may always be conservative and never has to be exact.
     *
     * <p><b>Defaults to returning immediately</b>, which leaves the caller spinning exactly as it does
     * without this method. A platform that has not implemented it therefore keeps working and merely
     * keeps burning what it burns today; the alternative default — blocking — would freeze it.
     */
    default void waitEvents(long timeoutNanos) {
    }

    /**
     * End a {@link #waitEvents(long)} early. Safe from any thread, and the only method here that is.
     *
     * <p>Called when work arrives for a loop that has gone to sleep: a worker thread's mutation, a
     * background load finishing, a click handler starting an animation. Without it a loop that blocked
     * indefinitely has nothing but OS input to wake it, and a window whose own program has something to
     * show it stays frozen until the user happens to move the pointer over it.
     *
     * <p>Defaults to a no-op, which is only safe in company: a platform whose {@link #waitEvents} also
     * defaults never sleeps, so there is never a sleep to end. Implement both or neither.
     */
    default void postWake() {
    }

    /**
     * Whether this window is the one the user is currently working in.
     *
     * <p>What lets a host spend its idle budget where it will be noticed. A focused window is worth
     * keeping a floor under — a few frames a second, so that anything nobody thought to wake for is
     * <em>late</em> rather than lost — while an unfocused one is worth parking outright, because nobody
     * is waiting on it.
     *
     * <p>Defaults to {@code true}, and the direction is deliberate: a platform that cannot tell is
     * treated as always focused, so it keeps its floor and stays responsive. Guessing the other way
     * would park a window the user is looking at, which is the failure this exists to prevent.
     */
    default boolean isFocused() {
        return true;
    }

    // ---- Outer bounds, for persisting and restoring window placement. The rect these describe is the same one
    // WindowConfig's width/height/x/y request, so save-then-recreate round-trips exactly (a client-rect size fed
    // back as an outer size would shrink the window by its frame on every launch). Defaults are for platforms
    // without placement support yet: they report nothing useful and move nothing, rather than throwing.

    /** Screen x of the window's outer top-left, or 0 where the platform doesn't report placement yet. */
    default int screenX() {
        return 0;
    }

    /** Screen y of the window's outer top-left, or 0 where the platform doesn't report placement yet. */
    default int screenY() {
        return 0;
    }

    /** Outer width of the window (frame included), or {@link #width()} where placement isn't supported yet. */
    default int outerWidth() {
        return width();
    }

    /** Outer height of the window (frame included), or {@link #height()} where placement isn't supported yet. */
    default int outerHeight() {
        return height();
    }

    /** Move the window's outer top-left to screen {@code (x, y)}. No-op where placement isn't supported yet. */
    default void setPosition(int x, int y) {
        // no-op by default
    }

    /**
     * Move <em>and</em> size the window's outer rect in one step — the whole of "put it back where it was".
     *
     * <p>{@link WindowConfig} covers this at creation, which is the right moment when the bounds are known then.
     * This is for when they are not: a window an application reopens was configured once, while the bounds worth
     * restoring are the ones the user last left it at, possibly later in the same session. One call rather than a
     * move and then a resize is what keeps the correction from being visible as a jump.
     *
     * <p>Defaults to moving only, so a platform that has not implemented sizing still lands the window in the
     * right place rather than ignoring the request entirely.
     */
    default void setBounds(int x, int y, int width, int height) {
        setPosition(x, y);
    }

    /**
     * Make the window visible. Idempotent. A window is created hidden so that the (potentially slow) Vulkan
     * bring-up runs while nothing is on screen; the present loop calls this once the first frame is ready, so the
     * window appears already painted instead of flashing blank/unresponsive during initialization.
     */
    void show();

    /**
     * Hide the window without destroying it: it leaves the screen and the taskbar, and everything it owns — its
     * surface, its swapchain, the tree it shows — stays alive and mutable. {@link #show()} brings the same window
     * back, which is what makes a window a <em>place the application returns to</em> rather than something it
     * builds again. Idempotent, and a no-op where the platform cannot hide a window yet.
     */
    default void hide() {
        // no-op by default
    }

    /**
     * Whether the window is currently on screen — false between {@link #hide()} and the next {@link #show()},
     * and before the first {@code show()}. Platforms without visibility control report the optimistic default.
     */
    default boolean isVisible() {
        return true;
    }

    /**
     * Raise this window and give it the keyboard — what a second request to open an already-open window does.
     * A minimized window is restored first, because "focus it" cannot mean "leave it in the taskbar". No-op
     * where the platform cannot activate a window yet.
     *
     * <p>Window managers are entitled to refuse a foreground steal from a process the user is not currently
     * working in; a platform does what it can (raise, flash) rather than pretending it succeeded.
     */
    default void focus() {
        // no-op by default
    }

    /**
     * Enable or disable input to this window. A disabled window is still drawn but takes no pointer or keyboard
     * input and cannot be activated — the OS-level half of modality: the dialog's owner is disabled while the
     * dialog is up, so the block is enforced by the window manager rather than by an application remembering to
     * ignore events. No-op where the platform cannot disable a window yet.
     */
    default void setEnabled(boolean enabled) {
        // no-op by default
    }

    /**
     * Withdraw a close request that {@link #pumpEvents()} has reported, so the window carries on living — the
     * "you have unsaved changes" veto. Returns whether the window survived: {@code false} means the close was
     * not a request at all but a destruction already carried out (the owner went away, the session ended), and
     * the host must let it go. Platforms that cannot distinguish the two report {@code false}, which fails
     * safe — the window closes, exactly as it did before this method existed.
     *
     * <p>Only meaningful between a close being reported and the host tearing the window down. Cancelling a close
     * the application means to honour simply leaves the window open until it is requested again.
     */
    default boolean cancelClose() {
        return false;
    }

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

    // ---- Client-drawn chrome. All defaults, so a platform that has not implemented Decorations.CLIENT yet
    // simply keeps its system frame: the window still works, it is only decorated by the OS.

    /**
     * Declare where this window's own drawing is caption and where it is content, for a
     * {@link Decorations#CLIENT} window. Push the regions once per frame from the laid-out UI; the platform
     * answers the window manager's hit-test from them, so move, resize, snap, double-click-to-maximize and the
     * system menu keep working against application-drawn chrome. Ignored by a system-decorated window.
     */
    default void setHitRegions(HitRegions regions) {
        // no-op by default
    }

    /**
     * Install the callback the platform invokes when it must paint outside the host's own loop — while the
     * window manager runs a modal move or resize, or during a live resize. Without it a window drawn by a
     * pull-style frame loop freezes for the duration of a drag, because that loop is suspended inside the
     * platform's nested one; with it, the platform pulls a frame instead.
     *
     * <p>The callback renders <b>one</b> frame and must not pump events: it is already being called from inside
     * the event pump. Pass {@code null} to remove it.
     */
    default void setFrameSink(Runnable renderOneFrame) {
        // no-op by default
    }

    /** Minimize (iconify) the window — the action an application-drawn minimize button performs. */
    default void minimize() {
        // no-op by default
    }

    /** Maximize the window to the current monitor's work area. */
    default void maximize() {
        // no-op by default
    }

    /** Restore the window from maximized (or minimized) back to its previous bounds. */
    default void restore() {
        // no-op by default
    }

    /** Whether the window is currently maximized — what an application-drawn maximize button toggles on. */
    default boolean isMaximized() {
        return false;
    }

    /**
     * Whether the window is minimized. A minimized window has no client area — zero by zero — so there is
     * nothing to present to, and a renderer that carries on drawing to it is at best doing pointless work and at
     * worst hanging on a swapchain whose surface no longer has an extent. Hosts skip their draw while this is
     * true; the default {@code false} keeps platforms that cannot report it rendering as they always did.
     */
    default boolean isMinimized() {
        return false;
    }

    /**
     * Ask the window to close, exactly as its system close button would: the request travels the ordinary route,
     * so {@link #pumpEvents()} reports the close on the next pump and the host tears the window down on its own
     * terms. This is what an application-drawn close button calls — never {@link #close()}, which destroys OS
     * resources the host may still be presenting to.
     */
    default void requestClose() {
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
