package dev.vexelray.engine;

/**
 * One thing a {@link RenderTechnique} reads that somebody else owns: a buffer or a picture the host binds for it each
 * frame (vexelray-gui's {@code docs/plans/device-feed.md}, C). A technique declares these ({@link
 * RenderTechnique#bindings}) instead of reaching for them, so what it reads is said in one place, the host makes the
 * descriptor set's layout from the declaration, and the technique never holds a resource whose life is not its own:
 * a simulation's step, a decoded picture, a slot of a feed chosen by the frame.
 *
 * <p>Binding {@code i} of the technique's {@link RenderTechnique#bindingSet set} is the {@code i}th declared. The
 * name is how the host is told what to bind there.
 *
 * @param name what the host calls it when it is told what to bind ({@code "step"}, {@code "picture"})
 * @param kind a storage buffer the shaders read, or a picture they sample
 */
public record TechniqueBinding(String name, Kind kind) {

    /** What is bound. */
    public enum Kind {
        /** A storage buffer, read by any stage that declares it. */
        STORAGE_BUFFER,
        /** A picture with its sampler, sampled by the fragment stage. */
        SAMPLED_IMAGE
    }

    public TechniqueBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a binding has a name");
        }
        if (kind == null) {
            throw new IllegalArgumentException("a binding has a kind: " + name);
        }
    }

    /** A storage buffer named {@code name}. */
    public static TechniqueBinding buffer(String name) {
        return new TechniqueBinding(name, Kind.STORAGE_BUFFER);
    }

    /** A picture named {@code name}. */
    public static TechniqueBinding picture(String name) {
        return new TechniqueBinding(name, Kind.SAMPLED_IMAGE);
    }
}
