package dev.vexelray.shader;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One compiled shader set per distinct description — the cache {@link ShaderKey} was written for and, until now,
 * had no user.
 *
 * <p>This is what makes runtime shader generation affordable rather than merely possible. Composing and lowering
 * is not free (it is IR construction plus a SPIR-V encode, and then the driver's own compile on first use), so
 * doing it once per <em>distinct</em> material or SDF scene rather than once per pipeline creation is the
 * difference between a technique that can be re-created freely and one that must be hoarded.
 *
 * <p>Keyed on the description's structure, not its identity: two separately-built but equal scenes share a
 * compile. That falls out of descriptions being records — see {@link ShaderKey} and {@code Surface}.
 *
 * <p>Thread-safe. A miss computes under {@code computeIfAbsent}, so concurrent requests for the <em>same</em>
 * key wait rather than compiling the same shader twice — which is the behaviour worth having when the
 * alternative is duplicated work, and the reason this is not a plain {@code get}/{@code put} pair.
 */
public final class ShaderCache {

    private final Map<ShaderKey, List<ComposedShader>> entries = new ConcurrentHashMap<>();

    /**
     * The compiled stages for {@code description}, composing them on first request.
     *
     * @throws IllegalStateException if the composer returns no stages, which would otherwise cache an empty
     *                               result and fail later at pipeline creation, far from the cause
     */
    public <D> List<ComposedShader> shadersFor(ShaderComposer<D> composer, D description) {
        return entries.computeIfAbsent(composer.keyFor(description), key -> {
            List<ComposedShader> composed = List.copyOf(composer.compose(description));
            if (composed.isEmpty()) {
                throw new IllegalStateException("composer " + composer.getClass().getSimpleName()
                        + " produced no stages for " + key);
            }
            return composed;
        });
    }

    /** Whether {@code description} has already been compiled — for tests and diagnostics, not control flow. */
    public <D> boolean contains(ShaderComposer<D> composer, D description) {
        return entries.containsKey(composer.keyFor(description));
    }

    /** Number of distinct shader sets held. */
    public int size() {
        return entries.size();
    }

    /** Drop everything. The SPIR-V is plain bytes, so there is nothing to release beyond the references. */
    public void clear() {
        entries.clear();
    }
}
