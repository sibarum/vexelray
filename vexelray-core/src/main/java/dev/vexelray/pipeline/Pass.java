package dev.vexelray.pipeline;

import java.util.List;
import java.util.Optional;

/**
 * One node in a {@link RenderPipeline}: a unit of GPU work that reads some attachments and writes others. Passes
 * are pure configuration — no Vulkan objects, no shaders-yet — describing <em>what</em> should happen so the
 * {@link FrameGraph} can order them and the runtime can realise them. The runtime-generated shader for a pass is
 * composed from the pass's description at build time, not stored here.
 *
 * <p>The sealed set spans VexelRay's hybrid ambition: {@link RasterPass} rasterises polygons, {@link RaymarchPass}
 * ray-marches SDFs over a fullscreen triangle, {@link ComputePass} dispatches a kernel, and {@link PostPass}
 * runs a fullscreen image operation. A hybrid frame is just a raster pass and a ray-march pass sharing a colour
 * target and depth buffer, followed by a post pass — expressed entirely through these records.
 */
public sealed interface Pass permits RasterPass, RaymarchPass, ComputePass, PostPass {

    /** Unique pass name within a pipeline; used by the frame graph and for debug labels. */
    String name();

    /** Names of attachments this pass writes (colour targets, and the depth buffer for depth-writing passes). */
    List<String> writes();

    /** Names of attachments this pass samples as input (e.g. a post pass reading the HDR target). Empty if none. */
    List<String> reads();

    /** The depth attachment this pass tests/writes against, if any. */
    Optional<String> depth();

    /** Discriminator for logging and coarse dispatch; the sealed type is the real switch target. */
    PassKind kind();
}
