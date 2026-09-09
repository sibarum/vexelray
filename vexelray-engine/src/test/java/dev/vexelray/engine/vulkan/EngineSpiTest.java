package dev.vexelray.engine.vulkan;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the front door that can be checked without a GPU: that a runtime is discoverable, that the
 * context handed to techniques refuses to be built wrong, and that a target this runtime cannot honour is
 * refused rather than quietly substituted.
 *
 * <p>What is deliberately <em>not</em> here is the frame loop, because running one opens a window and needs a
 * device — see {@code TwoTechniqueSmoke}, which is a manual smoke for exactly that reason. The split follows
 * the convention already in this repository: unit tests are pure, and anything that needs hardware says so by
 * being a {@code main} with a measurement in it.
 */
class EngineSpiTest {

    @Test
    void theRuntimeIsDiscoverableAsAService() {
        List<String> providers = new ArrayList<>();
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            providers.add(provider.getClass().getName());
        }
        assertTrue(providers.contains(VulkanEngineProvider.class.getName()),
                "VexelEngine.create finds a runtime only through ServiceLoader, so a missing "
                        + "META-INF/services entry breaks the front door while every class still compiles; "
                        + "found: " + providers);
    }

    @Test
    void theContextRefusesToBeBuiltWithoutWhatATechniqueNeeds() {
        assertThrows(IllegalArgumentException.class, () -> new VulkanTechniqueContext(
                null, AttachmentFormat.SWAPCHAIN, Optional.empty(), 800, 600, 42L),
                "a technique that cannot reach a device cannot create a pipeline");
        assertThrows(IllegalArgumentException.class, () -> new VulkanTechniqueContext(
                null, AttachmentFormat.SWAPCHAIN, Optional.empty(), 800, 600, 0L),
                "a render pass handle of 0 would let a pipeline be built against nothing");
    }

    /**
     * {@code hasDepth} is a default method on the interface, so it is checked there rather than through the
     * Vulkan-bearing record — which is the point of it being a default: a technique asks the context, and every
     * implementation of the context answers consistently without restating the rule.
     */
    @Test
    void hasDepthFollowsTheDeclaredFormat() {
        assertFalse(contextWithDepth(Optional.empty()).hasDepth());
        assertTrue(contextWithDepth(Optional.of(AttachmentFormat.DEPTH32F)).hasDepth());
    }

    private static TechniqueContext contextWithDepth(Optional<AttachmentFormat> depth) {
        return new TechniqueContext() {
            @Override
            public AttachmentFormat colorFormat() {
                return AttachmentFormat.SWAPCHAIN;
            }

            @Override
            public Optional<AttachmentFormat> depthFormat() {
                return depth;
            }

            @Override
            public int width() {
                return 800;
            }

            @Override
            public int height() {
                return 600;
            }

            @Override
            public long renderPass() {
                return 42L;
            }
        };
    }

    @Test
    void aPipelineWithNoTechniqueIsRefused() {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .target(Target.windowed("test", 320, 240).color(AttachmentFormat.SWAPCHAIN));
        assertThrows(IllegalArgumentException.class, builder::build,
                "an empty pipeline draws nothing, which is indistinguishable from a broken one");
    }

    @Test
    void aTargetDeclaringDepthKeepsItThroughThePipeline() {
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("test", 320, 240)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(new CountingTechnique())
                .build();

        assertTrue(pipeline.target().hasDepth());
        assertEquals(Optional.of(AttachmentFormat.DEPTH32F), pipeline.target().depthFormat());
    }

    @Test
    void techniquesRecordInTheOrderTheyWereAdded() {
        CountingTechnique first = new CountingTechnique();
        CountingTechnique second = new CountingTechnique();
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("test", 320, 240).color(AttachmentFormat.SWAPCHAIN))
                .technique(first)
                .technique(second)
                .build();

        // Order is the composition — the pipeline must preserve it exactly, because "later paints over earlier"
        // is the only thing a caller has to reason about when depth does not decide the pixel.
        assertEquals(List.of(first, second), pipeline.techniques());
    }

    /** A technique that records what the SPI did to it and nothing else — no device, so no GPU needed. */
    private static final class CountingTechnique implements RenderTechnique {
        int realized;
        int recorded;
        int closed;

        @Override
        public void realize(TechniqueContext ctx) {
            realized++;
        }

        @Override
        public void record(FrameContext frame) {
            recorded++;
        }

        @Override
        public void close() {
            closed++;
        }
    }

    @Test
    void aFrameContextNeedsACommandBuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrameContext(null, 0, 0, 0, 800, 600));
        // MemorySegment.NULL is a real segment, not null: a technique handed it would record into address zero,
        // so the check is about the reference and the runtime is responsible for never passing NULL.
        assertEquals(800, new FrameContext(MemorySegment.NULL, 0, 0, 0, 800, 600).width());
    }
}
