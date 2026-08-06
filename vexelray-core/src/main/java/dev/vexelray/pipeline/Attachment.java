package dev.vexelray.pipeline;

/**
 * A named image a pass reads or writes — a colour target, an HDR intermediate, or a depth buffer. Attachments
 * are declared by name so passes can share them: the hybrid pipeline's raster pass and ray-march pass can write
 * the same colour target and test against the same depth buffer, which is how polygons and SDF surfaces occupy
 * one coherent frame. The {@link FrameGraph} resolves who produces and who consumes each name.
 *
 * @param name    unique attachment name within a pipeline
 * @param format  the image format
 * @param loadOp  what happens to existing contents when a pass begins writing it
 * @param storeOp whether the pass's writes are kept after it ends
 */
public record Attachment(String name, AttachmentFormat format, LoadOp loadOp, StoreOp storeOp) {

    public Attachment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attachment name must be non-blank");
        }
        if (format == null || loadOp == null || storeOp == null) {
            throw new IllegalArgumentException("format, loadOp, and storeOp must be non-null");
        }
    }

    /** A colour target that is cleared then kept (the common case for the first writer of a target). */
    public static Attachment color(String name, AttachmentFormat format) {
        return new Attachment(name, format, LoadOp.CLEAR, StoreOp.STORE);
    }

    /** A depth buffer that is cleared then kept for the duration of the frame's depth-tested passes. */
    public static Attachment depth(String name, AttachmentFormat format) {
        if (!format.isDepth()) {
            throw new IllegalArgumentException("depth attachment needs a depth format, got " + format);
        }
        return new Attachment(name, format, LoadOp.CLEAR, StoreOp.STORE);
    }

    public boolean isDepth() {
        return format.isDepth();
    }
}
