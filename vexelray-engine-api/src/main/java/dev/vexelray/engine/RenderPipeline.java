package dev.vexelray.engine;

import java.util.List;
import java.util.Objects;

/**
 * A configured pipeline: a shared {@link Target} plus the ordered {@link RenderTechnique}s that composite into it.
 * This is the public "build-your-own-pipeline" artifact and the front door a client writes — composing <em>how
 * techniques combine</em> (this) with <em>what each renders</em> (each technique's own content API). The runtime
 * realises it into GPU objects and drives it each frame; the pipeline itself holds no Vulkan.
 *
 * <p>Techniques record in list order into one colour+depth target, so ordering is the composition: an opaque SDF
 * raymarch, then sprites that share its depth, then a post pass. The core never enumerates render modes — it only
 * knows this ordered list of techniques (see architecture.md §2, docs/refactor-decisions.md D6).
 *
 * <pre>{@code
 * RenderPipeline pipeline = RenderPipeline.builder()
 *     .target(Target.windowed("Fathom", 800, 600)
 *         .color(AttachmentFormat.SWAPCHAIN)
 *         .depth(AttachmentFormat.DEPTH32F))
 *     .technique(new SdfRaymarchTechnique(scene))
 *     // .technique(new SpriteTechnique(...))   // composite a hybrid, sharing that depth
 *     .build();
 * }</pre>
 *
 * @param target     the shared colour+depth target all techniques composite into
 * @param techniques the techniques, in the order they record into the frame (at least one)
 */
public record RenderPipeline(Target target, List<RenderTechnique> techniques) {

    public RenderPipeline {
        Objects.requireNonNull(target, "target");
        techniques = List.copyOf(techniques);
        if (techniques.isEmpty()) {
            throw new IllegalArgumentException("a pipeline must have at least one technique");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent {@link RenderPipeline} configuration. Not thread-safe; build once, use the immutable pipeline. */
    public static final class Builder {
        private Target target;
        private final java.util.List<RenderTechnique> techniques = new java.util.ArrayList<>();

        /** Set the shared target. Accepts a built {@link Target} or a {@link Target.Builder} (built here). */
        public Builder target(Target target) {
            this.target = target;
            return this;
        }

        /** Convenience: set the target directly from its builder. */
        public Builder target(Target.Builder target) {
            this.target = target.build();
            return this;
        }

        /** Append a technique. Techniques record in the order added. */
        public Builder technique(RenderTechnique technique) {
            techniques.add(Objects.requireNonNull(technique, "technique"));
            return this;
        }

        public RenderPipeline build() {
            if (target == null) {
                throw new IllegalStateException("a pipeline must declare a target");
            }
            return new RenderPipeline(target, techniques);
        }
    }
}
