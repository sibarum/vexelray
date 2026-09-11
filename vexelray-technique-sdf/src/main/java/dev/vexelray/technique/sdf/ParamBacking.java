package dev.vexelray.technique.sdf;

/**
 * Which road a scene's parameters travel to the shader: push constants, or a storage buffer.
 *
 * <p><b>A fact about the machine, not about the design.</b> The same surface, the same slots, the same
 * {@code write(ParamId, double)} — only the transport differs, and it differs because
 * {@code maxPushConstantsSize} does. A design authored where the driver reports 256 bytes opens where it
 * reports 128; it simply takes the other road there. Nothing about what a design can express may depend on
 * this, and nothing that reaches a file may mention it.
 *
 * <h2>Why there is a choice at all</h2>
 *
 * <p>Push constants are not merely the smaller option, they are the faster one: a push constant lands in a
 * register, and a buffer load is memory — read inside a march loop that runs a hundred iterations per pixel.
 * So {@link Mode#AUTO} keeps a design on the register path while it fits, and moves it when it does not.
 * Always using the buffer would be simpler and would cost every small design the difference; always using
 * push constants is what P0a did, and it caps a design at around the sixth shape.
 *
 * <h2>What it costs to get wrong</h2>
 *
 * <p>The backing changes the emitted SPIR-V — {@code PushConstantRead} against {@code BufferLoad} — so it is
 * part of the shader key. A cache that ignored it would serve a push-constant shader to a pipeline built for
 * a buffer, which is not a slow picture but a wrong one.
 *
 * @param mode                  which road, or {@link Mode#AUTO} to decide by size
 * @param maxPushConstantBytes  what the device reports for {@code maxPushConstantsSize}, or
 *                              {@link #SPEC_FLOOR_BYTES} when nobody has asked it
 */
public record ParamBacking(Mode mode, int maxPushConstantBytes) {

    /**
     * Vulkan's guaranteed minimum for {@code maxPushConstantsSize}, and what to assume before a device has
     * been asked — every device has at least this, so assuming it is never wrong, only sometimes modest.
     */
    public static final int SPEC_FLOOR_BYTES = 128;

    /** Which road. */
    public enum Mode {
        /** Push constants while the block fits the device's limit, the buffer beyond it. */
        AUTO,
        /** Push constants, and a design too large for them is refused by name rather than moved. */
        PUSH_CONSTANTS,
        /** The buffer, whatever the size — what a test uses to exercise the road a small design would not. */
        BUFFER
    }

    /** What a host assumes before it has asked a device: decide by size, against the spec floor. */
    public static final ParamBacking DEFAULT = new ParamBacking(Mode.AUTO, SPEC_FLOOR_BYTES);

    public ParamBacking {
        if (mode == null) {
            throw new IllegalArgumentException("a backing needs a mode");
        }
        if (maxPushConstantBytes < SPEC_FLOOR_BYTES) {
            throw new IllegalArgumentException(
                    "maxPushConstantsSize is at least " + SPEC_FLOOR_BYTES + " bytes on every Vulkan device; "
                            + maxPushConstantBytes + " means the number came from the wrong place");
        }
    }

    /**
     * Decide by size against what this device actually reports.
     *
     * <p>The one thing a host should do with the number: report headroom, and pick the faster road while it
     * fits. Not: decide whether a design is representable, which is a question the device must never answer.
     */
    public static ParamBacking on(int deviceMaxPushConstantBytes) {
        return new ParamBacking(Mode.AUTO, deviceMaxPushConstantBytes);
    }

    /** Force the buffer, whatever the device says. */
    public ParamBacking alwaysBuffer() {
        return new ParamBacking(Mode.BUFFER, maxPushConstantBytes);
    }

    /** Force push constants, whatever the device says — a design that does not fit is then refused. */
    public ParamBacking alwaysPushConstants() {
        return new ParamBacking(Mode.PUSH_CONSTANTS, maxPushConstantBytes);
    }

    /** How many parameters fit in push constants here, after the camera and the lens. */
    public int pushConstantCapacity() {
        return (maxPushConstantBytes - SdfComposer.FIRST_PARAM_MEMBER * 4) / 4;
    }

    /**
     * Which road a scene with this many parameters actually takes — never {@link Mode#AUTO}, which is a
     * policy rather than a road.
     *
     * <p>What belongs in a shader key, and all that does: two devices reporting 128 and 256 bytes compile the
     * <em>same</em> module for a three-parameter scene, because both send it down the push road. Keying on
     * the whole backing would split that cache entry on a number the shader never sees.
     */
    public Mode roadFor(int parameters) {
        return usesBuffer(parameters) ? Mode.BUFFER : Mode.PUSH_CONSTANTS;
    }

    /** Whether a scene with this many parameters takes the buffer. */
    public boolean usesBuffer(int parameters) {
        return switch (mode) {
            case BUFFER -> true;
            case PUSH_CONSTANTS -> false;
            case AUTO -> parameters > pushConstantCapacity();
        };
    }
}
