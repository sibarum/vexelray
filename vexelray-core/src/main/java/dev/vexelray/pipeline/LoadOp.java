package dev.vexelray.pipeline;

/** What a pass does with an attachment's existing contents when it begins writing — maps to {@code VkAttachmentLoadOp}. */
public enum LoadOp {
    /** Clear to the attachment's clear value first. The first writer of a target each frame. */
    CLEAR,
    /** Preserve existing contents and write over them — a later pass compositing onto an earlier one's output. */
    LOAD,
    /** Contents are undefined; the pass overwrites every texel it cares about. Cheapest when nothing is reused. */
    DONT_CARE
}
