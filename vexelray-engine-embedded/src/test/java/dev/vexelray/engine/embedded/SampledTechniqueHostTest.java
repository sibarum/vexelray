package dev.vexelray.engine.embedded;

import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That an ordered {@link RenderTechnique} list runs against a {@link SampledColorTarget} — the claim this module
 * exists to make, which before it was false for every application whose scene is a region of a GUI window rather
 * than the whole of one.
 *
 * <h2>Why the techniques here draw nothing</h2>
 *
 * <p>{@link Stub} records no commands at all, which is a legal technique (it draws nothing) and is the right
 * scope for this module. What is new here is <em>which</em> techniques get recorded, in what order, and how
 * their lifecycle is driven — not how Vulkan composites what they record. That second question is already
 * measured upstream, exactly, on an offscreen target: {@code OffscreenEngineTest} asserts byte equality on the
 * pixels two solid-fill techniques leave behind, and {@code HybridFrameSmoke} runs a real march under real
 * chrome. Re-asserting it here would be testing the driver, and it would need a shader compiler on the test
 * path to do it.
 *
 * <p>So these assert the host's own contract and nothing else: order, realise-once, the shape the context
 * reports, what a retarget does, and what happens when a realise throws half way down the list.
 *
 * <p>They need a Vulkan graphics device and no window, which is why {@link SampledColorTarget} is the right
 * fixture for them — it is the one target on this stack that is a real render target without being a surface.
 */
class SampledTechniqueHostTest {

    private static final int W = 64;
    private static final int H = 48;

    /** Deliberately a different shape, so a retarget that silently kept the old extent is visible. */
    private static final int W2 = 128;
    private static final int H2 = 96;

    private static boolean hasGraphicsDevice() {
        try (VulkanInstance instance = new VulkanInstance("vexelray-engine-embedded probe",
                NativePlatform.current().requiredVulkanInstanceExtensions())) {
            return instance.selectGraphicsDevice().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Open a device and a target, run {@code body}, and close both however it ends. */
    private static void onTarget(TargetBody body) {
        assumeTrue(hasGraphicsDevice(), "no Vulkan graphics device — the embedded host needs one");
        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("vexelray-engine-embedded test",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));
            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 SampledColorTarget target = new SampledColorTarget(device, W, H)) {
                body.run(device, target);
            }
        }
    }

    @FunctionalInterface
    private interface TargetBody {
        void run(VulkanDevice device, SampledColorTarget target);
    }

    /**
     * The composition is the order, so the order had better be the list's. A host that reversed them, or drained
     * a set, would draw the chrome under the scene and report nothing wrong.
     */
    @Test
    void recordsEveryTechniqueInListOrderOncePerFrame() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub first = new Stub("first", log, false);
            Stub second = new Stub("second", log, false);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(first, second))) {
                host.render(0f, 0f, 0f, 1f);
                assertEquals(List.of("first.realize", "second.realize", "first.record", "second.record"), log);
            }
        });
    }

    /**
     * Realise is one-time setup and record is per frame, and the difference is the whole lifecycle. A host that
     * realised per frame would rebuild a pipeline every frame and still produce a correct picture — slowly, and
     * with nothing failing.
     */
    @Test
    void realisesOnceHoweverManyFramesAreRendered() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub only = new Stub("only", log, false);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(only))) {
                host.render(0f, 0f, 0f, 1f);
                host.render(0f, 0f, 0f, 1f);
                host.render(0f, 0f, 0f, 1f);
            }
            assertEquals(1, only.realizeCount, "realise is one-time setup");
            assertEquals(3, only.recordCount, "record is per frame");
            assertEquals(1, only.closeCount, "close is paired with realise, not with render");
        });
    }

    /**
     * The six accessors, which is the finding this module was written for: a sampled target already answers
     * every question a technique asks at realise time, and the adapter is a field copy.
     *
     * <p>{@code hasDepth} false is asserted rather than assumed — a technique branches on it to decide whether
     * to declare depth-stencil state, and a pipeline that declares it inside a pass without depth is invalid
     * usage the loader does not always report.
     */
    @Test
    void theContextReportsTheTargetsOwnShape() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub stub = new Stub("stub", log, false);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(stub))) {
                host.render(0f, 0f, 0f, 1f);
            }
            assertEquals(W, stub.width);
            assertEquals(H, stub.height);
            assertEquals(target.renderPass(), stub.renderPass, "a technique builds its pipeline against this");
            assertNotEquals(0L, stub.renderPass, "a zero render pass is a pipeline that cannot be built");
            assertSame(device, stub.device, "every object a technique creates must belong to this device");
            assertFalse(stub.hasDepth, "a SampledColorTarget is colour-only; techniques composite by order");
        });
    }

    /**
     * A viewport re-mints its target when the box it is drawn in outgrows it, and the pipelines built against
     * the old render pass do not carry over. {@code RenderTechnique} promises a technique is never realised
     * twice over live objects, so the close has to come first — and this is the method that keeps the promise.
     */
    @Test
    void retargetClosesBeforeRealisingAgainAndCarriesTheNewExtent() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub stub = new Stub("stub", log, false);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(stub))) {
                host.render(0f, 0f, 0f, 1f);
                log.clear();
                try (SampledColorTarget grown = new SampledColorTarget(device, W2, H2)) {
                    host.retarget(grown);
                    host.render(0f, 0f, 0f, 1f);
                    assertEquals(List.of("stub.close", "stub.realize", "stub.record"), log,
                            "closed before realised again, and not realised until the next frame");
                    assertEquals(W2, stub.width, "the second realise saw the new target");
                    assertEquals(H2, stub.height);
                    // Closed while `grown` is still open: a technique's objects must not outlive the target
                    // they were built against.
                    host.close();
                }
            }
        });
    }

    /** Re-pointing at the target already held is the caller's growth threshold, not a rebuild. */
    @Test
    void retargetToTheSameTargetIsNotARebuild() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub stub = new Stub("stub", log, false);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(stub))) {
                host.render(0f, 0f, 0f, 1f);
                log.clear();
                host.retarget(target);
                host.render(0f, 0f, 0f, 1f);
                assertEquals(List.of("stub.record"), log, "no close, no second realise");
            }
        });
    }

    /**
     * The rollback path {@code RenderTechnique} names: close is called exactly once per <em>successful</em>
     * realise, including when a later technique's realise throws. Without it the first technique's pipeline is
     * leaked by a host that never believed it owned one — which surfaces as the probe's ledger reporting a live
     * {@code GraphicsPipeline} at exit, a long way from the throw that caused it.
     */
    @Test
    void aRealiseThatThrowsClosesTheOnesThatAlreadySucceeded() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub good = new Stub("good", log, false);
            Stub bad = new Stub("bad", log, true);
            try (SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(good, bad))) {
                assertThrows(IllegalStateException.class, () -> host.render(0f, 0f, 0f, 1f));
                assertEquals(List.of("good.realize", "bad.realize", "good.close"), log);
                assertEquals(1, good.closeCount, "closed exactly once");
                assertEquals(0, bad.closeCount, "a realise that threw is not owed a close");
            }
            assertEquals(1, good.closeCount, "and not closed again when the host itself closes");
        });
    }

    /** Reverse order, because a technique realised later may have been built against something an earlier made. */
    @Test
    void closesInReverseOrderOfRealise() {
        onTarget((device, target) -> {
            List<String> log = new ArrayList<>();
            Stub a = new Stub("a", log, false);
            Stub b = new Stub("b", log, false);
            SampledTechniqueHost host = new SampledTechniqueHost(target, List.of(a, b));
            host.render(0f, 0f, 0f, 1f);
            log.clear();
            host.close();
            assertEquals(List.of("b.close", "a.close"), log);
        });
    }

    /** An empty list is a host that would draw nothing and report nothing — refused where it is written. */
    @Test
    void refusesAnEmptyTechniqueList() {
        onTarget((device, target) -> assertThrows(IllegalArgumentException.class,
                () -> new SampledTechniqueHost(target, List.of())));
    }

    /**
     * A technique that records nothing, and writes down every call it receives.
     *
     * <p>Legal: recording nothing draws nothing. That is what makes it the right instrument for a test about
     * the driving of techniques rather than about their output.
     */
    private static final class Stub implements RenderTechnique {

        private final String name;
        private final List<String> log;
        private final boolean failOnRealize;

        int realizeCount;
        int recordCount;
        int closeCount;

        int width;
        int height;
        boolean hasDepth;
        long renderPass;
        VulkanDevice device;

        Stub(String name, List<String> log, boolean failOnRealize) {
            this.name = name;
            this.log = log;
            this.failOnRealize = failOnRealize;
        }

        @Override
        public void realize(TechniqueContext ctx) {
            log.add(name + ".realize");
            width = ctx.width();
            height = ctx.height();
            hasDepth = ctx.hasDepth();
            renderPass = ctx.renderPass();
            device = ((VulkanTechniqueContext) ctx).device();
            if (failOnRealize) {
                throw new IllegalStateException(name + " refused to realise");
            }
            realizeCount++;
        }

        @Override
        public void record(FrameContext frame) {
            log.add(name + ".record");
            recordCount++;
        }

        @Override
        public void close() {
            log.add(name + ".close");
            closeCount++;
        }
    }
}
