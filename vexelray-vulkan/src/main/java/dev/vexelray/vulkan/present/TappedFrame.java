package dev.vexelray.vulkan.present;

import java.lang.foreign.MemorySegment;

/**
 * One frame a presenter put on the screen, handed to a {@link FrameTap}: its pixels in mapped GPU memory, as they were
 * presented, until {@link #release}.
 *
 * <p>Pixels are four bytes each, tightly packed, top row first, {@code width * height * 4} bytes, in the presented
 * image's own channel order: {@link #bgra()} says which. Alpha is whatever was drawn, and the screen ignores it.
 */
public interface TappedFrame {

    /** The pixels, valid until {@link #release}. Native memory: hand it to native code as it is, or copy it. */
    MemorySegment pixels();

    /** Width in pixels: the window's drawable width when it was presented. */
    int width();

    /** Height in pixels. */
    int height();

    /** True if the bytes are B, G, R, A; false if R, G, B, A. */
    boolean bgra();

    /** When it was presented, on {@link System#nanoTime}'s clock. */
    long presentedNanos();

    /** Give the buffer back to the presenter. From any thread, once; a second call does nothing. */
    void release();
}
