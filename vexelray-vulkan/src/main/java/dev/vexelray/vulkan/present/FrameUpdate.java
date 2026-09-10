package dev.vexelray.vulkan.present;

import java.lang.foreign.MemorySegment;

/**
 * Per-frame hook run before a frame is recorded: advance whatever the frame is a picture of, and fill the
 * presenter's push-constant storage if it has any.
 *
 * <p>Called on the thread driving the presenter, immediately before {@link Recorder#record} — so writing state
 * that {@code record} reads needs no synchronisation, and slow work here delays the frame it precedes rather
 * than overlapping it.
 *
 * <p>Named {@code FrameUpdate} rather than {@code Frame} because a frame is the noun this package already uses
 * for the thing being drawn; this is the verb that happens before one.
 */
@FunctionalInterface
public interface FrameUpdate {

    /**
     * @param dtSeconds     seconds since the previous frame this presenter drew
     * @param pushConstants the presenter's push-constant storage, or {@link MemorySegment#NULL} when the caller
     *                      asked for none — techniques push their own layouts inside {@link Recorder#record}
     *                      instead, which is why the engine asks for zero bytes here
     */
    void update(double dtSeconds, MemorySegment pushConstants);
}
