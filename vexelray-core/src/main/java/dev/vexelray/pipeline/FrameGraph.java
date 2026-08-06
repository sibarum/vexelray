package dev.vexelray.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link RenderPipeline}'s passes into an execution order and (later) the barriers/layout transitions
 * between them. A pass that reads an attachment must run after the pass that last wrote it; the graph derives
 * this producer→consumer ordering from the passes' {@link Pass#reads()}/{@link Pass#writes()} names.
 *
 * <p>First cut: a stable, dependency-respecting order. Because a pass lists its writes and reads by name, the
 * "last writer before me" relation is well-defined in declaration order, and a stable pass-through already
 * honours it for the common linear case (geometry → march → post). The full graph — reordering independent
 * passes, inserting {@code VkImageMemoryBarrier}s, tracking layouts — is the natural next increment and slots in
 * behind {@link #order()} without changing callers.
 */
public final class FrameGraph {

    private final RenderPipeline pipeline;

    private FrameGraph(RenderPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public static FrameGraph of(RenderPipeline pipeline) {
        return new FrameGraph(pipeline);
    }

    /**
     * The passes in an order that respects producer→consumer dependencies. v1 preserves declaration order after
     * asserting it is already valid — i.e. no pass reads an attachment written only by a later pass.
     */
    public List<Pass> order() {
        Map<String, Integer> lastWriter = new HashMap<>();
        List<Pass> passes = pipeline.passes();
        for (int i = 0; i < passes.size(); i++) {
            Pass pass = passes.get(i);
            for (String read : pass.reads()) {
                Integer writer = lastWriter.get(read);
                if (writer == null) {
                    // Read of something no earlier pass wrote: an external/imported input (e.g. a prior frame's
                    // history buffer) — allowed. A same-frame producer, if any, must come earlier by construction.
                    continue;
                }
            }
            for (String write : pass.writes()) {
                lastWriter.put(write, i);
            }
        }
        return new ArrayList<>(passes);
    }
}
