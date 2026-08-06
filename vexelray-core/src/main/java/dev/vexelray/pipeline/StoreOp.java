package dev.vexelray.pipeline;

/** Whether a pass's writes to an attachment survive after it ends — maps to {@code VkAttachmentStoreOp}. */
public enum StoreOp {
    /** Keep the writes — the attachment is presented or read by a later pass. */
    STORE,
    /** Discard the writes — a transient attachment (e.g. a depth buffer not read after the frame). */
    DONT_CARE
}
