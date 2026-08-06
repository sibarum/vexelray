package dev.vexelray.pipeline;

import dev.vexelray.lighting.LightingModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the build-your-own-pipeline API by configuring VexelRay's headline case: a hybrid frame where a
 * raster pass and an SDF ray-march pass share a colour target and depth buffer, then a post pass tone-maps the
 * HDR result to the swapchain. This is a configuration/validation test — no GPU — proving the API expresses the
 * intended shape and rejects malformed pipelines.
 */
class HybridPipelineTest {

    private RenderPipeline hybrid() {
        return RenderPipeline.builder()
                .attachment(Attachment.color("hdr", AttachmentFormat.RGBA16F))
                .attachment(Attachment.depth("depth", AttachmentFormat.DEPTH32F))
                .attachment(Attachment.color("swapchain", AttachmentFormat.SWAPCHAIN))
                // Rasterise meshes into the HDR target, writing depth.
                .pass(new RasterPass("geometry", List.of("hdr"), List.of(),
                        Optional.of("depth"), LightingModels.cookTorrance()))
                // Ray-march SDFs into the same HDR target, reading rasterised depth for correct occlusion.
                .pass(new RaymarchPass("sdf", List.of("hdr"), List.of("depth"),
                        Optional.of("depth"), LightingModels.lambert()))
                // Tone-map the HDR target down to the swapchain.
                .pass(new PostPass("tonemap", List.of("hdr"), List.of("swapchain"), "tonemap-aces"))
                .build();
    }

    @Test
    void configuresHybridFrameInDependencyOrder() {
        RenderPipeline pipeline = hybrid();
        List<Pass> order = pipeline.executionOrder();

        assertEquals(List.of("geometry", "sdf", "tonemap"), order.stream().map(Pass::name).toList());
        assertEquals(3, pipeline.attachments().size());
    }

    @Test
    void rejectsPassReferencingUndeclaredAttachment() {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .attachment(Attachment.color("swapchain", AttachmentFormat.SWAPCHAIN))
                .pass(new PostPass("bad", List.of("missing"), List.of("swapchain"), "copy"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals(true, ex.getMessage().contains("missing"));
    }

    @Test
    void rejectsDuplicatePassNames() {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .attachment(Attachment.color("swapchain", AttachmentFormat.SWAPCHAIN))
                .pass(new PostPass("p", List.of("swapchain"), List.of("swapchain"), "a"))
                .pass(new PostPass("p", List.of("swapchain"), List.of("swapchain"), "b"));

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
