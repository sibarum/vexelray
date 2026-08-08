package dev.vexelray.engine;

import dev.vexelray.pipeline.AttachmentFormat;

import java.util.Optional;

/**
 * The shared render target a {@link RenderPipeline}'s techniques composite into: one colour attachment and an
 * optional depth attachment, presented either to a window (swapchain) or an offscreen image. This is the public
 * authoring surface for "where and in what format the frame is drawn"; the runtime resolves it to concrete Vulkan
 * objects (swapchain or offscreen image, depth image, render pass) and hands each technique the render-pass handle
 * to build against.
 *
 * <p>Depth is modelled as always-present-if-declared so composition is never a retrofit: declaring
 * {@code .depth(DEPTH32F)} once lets every technique in the pipeline share it for cross-occlusion (a marched SDF
 * surface and a rasterised mesh occluding each other correctly). A target with no depth is legal for a single
 * colour-only technique.
 *
 * <p>Authoring is fluent and immutable-on-build:
 * <pre>{@code
 * Target.windowed("Fathom", 800, 600)
 *       .color(AttachmentFormat.SWAPCHAIN)
 *       .depth(AttachmentFormat.DEPTH32F);
 * }</pre>
 *
 * @param kind        windowed (swapchain) or offscreen (headless image)
 * @param title       window title (ignored for offscreen)
 * @param width       target width in pixels
 * @param height      target height in pixels
 * @param vsync       present with vsync (FIFO); ignored for offscreen
 * @param colorFormat the colour attachment format all techniques write
 * @param depthFormat the shared depth attachment format, or empty for a depth-less target
 */
public record Target(Kind kind, String title, int width, int height, boolean vsync,
                     AttachmentFormat colorFormat, Optional<AttachmentFormat> depthFormat) {

    /** Where the composited frame is presented. */
    public enum Kind {
        /** A window with a swapchain — the interactive path. */
        WINDOWED,
        /** A headless offscreen image (tests, native-image CI, offline render / screenshot). */
        OFFSCREEN
    }

    public Target {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("target size must be positive, got " + width + "x" + height);
        }
        if (colorFormat == null) {
            throw new IllegalArgumentException("a target must declare a colour format");
        }
        if (colorFormat.isDepth()) {
            throw new IllegalArgumentException("colour attachment needs a colour format, got " + colorFormat);
        }
        depthFormat = depthFormat == null ? Optional.empty() : depthFormat;
        depthFormat.ifPresent(f -> {
            if (!f.isDepth()) {
                throw new IllegalArgumentException("depth attachment needs a depth format, got " + f);
            }
        });
    }

    /** Begin a windowed target (swapchain, vsync on). Complete it with {@link Builder#color}/{@link Builder#depth}. */
    public static Builder windowed(String title, int width, int height) {
        return new Builder(Kind.WINDOWED, title, width, height, true);
    }

    /** Begin a headless offscreen target. Complete it with {@link Builder#color}/{@link Builder#depth}. */
    public static Builder offscreen(int width, int height) {
        return new Builder(Kind.OFFSCREEN, "", width, height, false);
    }

    public boolean hasDepth() {
        return depthFormat.isPresent();
    }

    /** Fluent {@link Target} configuration. Build once; the {@link Target} it yields is immutable. */
    public static final class Builder {
        private final Kind kind;
        private final String title;
        private final int width;
        private final int height;
        private boolean vsync;
        private AttachmentFormat colorFormat;
        private AttachmentFormat depthFormat;

        private Builder(Kind kind, String title, int width, int height, boolean vsync) {
            this.kind = kind;
            this.title = title;
            this.width = width;
            this.height = height;
            this.vsync = vsync;
        }

        /** The colour attachment format every technique writes (required). */
        public Builder color(AttachmentFormat format) {
            this.colorFormat = format;
            return this;
        }

        /** Declare the shared depth attachment. Omit for a depth-less target. */
        public Builder depth(AttachmentFormat format) {
            this.depthFormat = format;
            return this;
        }

        /** Present without vsync (windowed only). */
        public Builder noVsync() {
            this.vsync = false;
            return this;
        }

        public Target build() {
            return new Target(kind, title, width, height, vsync, colorFormat, Optional.ofNullable(depthFormat));
        }
    }
}
