package dev.vexelray.engine;

import dev.vexelray.runtime.EngineConfig;

/**
 * How a runtime implementation makes itself available to {@link VexelEngine#create}: one {@link java.util.ServiceLoader}
 * service, discovered on the module path, so an application compiles against this module and never names the
 * runtime that serves it.
 *
 * <p>This is the same convention {@code vexelray-os} and Tactroller already use for their per-platform backends,
 * and it is chosen here for the same reason: the alternative is a static factory in {@code engine-api} that
 * constructs a class from a module above it, which is either a compile-time cycle or a string passed to
 * reflection — and reflection is what a native-image build cannot see through. A service is declared in
 * {@code module-info} (or {@code META-INF/services}), which the native-image agent reads.
 *
 * <p>It also keeps the front door honest about a property the architecture claims. If techniques are open and the
 * runtime is replaceable, then <em>nothing</em> in the public API may name the Vulkan runtime — not even to
 * construct it. A second implementation (a software rasteriser for CI, a remote runtime) becomes a jar on the
 * path rather than a change here.
 */
public interface EngineProvider {

    /**
     * A human-readable name for this runtime, used when more than one provider is present and in the error
     * raised when none is — a message naming what <em>was</em> found is the difference between a five-minute
     * diagnosis and an afternoon.
     */
    String name();

    /**
     * Whether this provider can serve the current machine. A provider that needs a Vulkan loader it cannot find
     * answers {@code false} here rather than throwing from {@link #create}, so a host with several providers
     * selects rather than crashes.
     */
    boolean isAvailable();

    /** Create an engine for {@code config}. Called only when {@link #isAvailable()} answered {@code true}. */
    VexelEngine create(EngineConfig config);
}
