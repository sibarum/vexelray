package dev.vexelray.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A configured rendering pipeline: the set of named {@link Attachment}s and the {@link Pass}es that read and
 * write them. This is the "build-your-own-pipeline" artifact — an immutable, validated description a
 * {@link dev.vexelray.runtime.RuntimeManager} realises into Vulkan objects and drives each frame.
 *
 * <p>Construction goes through {@link #builder()}: declare attachments, add passes, {@code build()}. The build
 * validates referential integrity (every attachment a pass names exists; every pass name is unique) and hands the
 * pass list to the {@link FrameGraph} for dependency ordering. It intentionally holds no shaders and no GPU
 * objects — a pipeline is portable data that can be inspected, cached, or serialised before any device exists.
 */
public record RenderPipeline(Map<String, Attachment> attachments, List<Pass> passes) {

    public RenderPipeline {
        attachments = Map.copyOf(attachments);
        passes = List.copyOf(passes);
    }

    /** The passes in a valid execution order (topological over attachment read/write dependencies). */
    public List<Pass> executionOrder() {
        return FrameGraph.of(this).order();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent configuration DSL. Not thread-safe; build once, use the immutable {@link RenderPipeline}. */
    public static final class Builder {
        private final Map<String, Attachment> attachments = new LinkedHashMap<>();
        private final List<Pass> passes = new ArrayList<>();

        /** Declare an attachment. Names must be unique. */
        public Builder attachment(Attachment attachment) {
            if (attachments.putIfAbsent(attachment.name(), attachment) != null) {
                throw new IllegalArgumentException("duplicate attachment name: " + attachment.name());
            }
            return this;
        }

        /** Add a pass. Passes are validated against declared attachments at {@link #build()}. */
        public Builder pass(Pass pass) {
            passes.add(pass);
            return this;
        }

        public RenderPipeline build() {
            Map<String, Integer> passNames = new LinkedHashMap<>();
            for (int i = 0; i < passes.size(); i++) {
                Pass pass = passes.get(i);
                if (passNames.putIfAbsent(pass.name(), i) != null) {
                    throw new IllegalArgumentException("duplicate pass name: " + pass.name());
                }
                requireDeclared(pass, pass.writes());
                requireDeclared(pass, pass.reads());
                pass.depth().ifPresent(d -> requireDeclared(pass, List.of(d)));
            }
            return new RenderPipeline(attachments, passes);
        }

        private void requireDeclared(Pass pass, List<String> names) {
            for (String name : names) {
                // Compute passes reference storage-resource names, not framebuffer attachments, so only
                // graphics passes are checked against the attachment table here.
                if (pass.kind() != PassKind.COMPUTE && !attachments.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "pass '" + pass.name() + "' references undeclared attachment '" + name + "'");
                }
            }
        }
    }
}
