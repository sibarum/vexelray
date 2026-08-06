package dev.vexelray.shader;

import java.util.Objects;

/**
 * An identity for a runtime-generated shader set: the composer that produced it plus a structural fingerprint of
 * the description it was composed from. Equal keys must map to byte-identical SPIR-V, so the engine's shader
 * cache can serve one compiled set for every material or SDF scene that reduces to the same {@code core} module.
 *
 * <p>v1 fingerprints via the description's own {@code equals}/{@code hashCode}. Descriptions intended to collapse
 * by structure (not object identity) should be records or otherwise value-typed, or the composer should override
 * {@link ShaderComposer#keyFor} to supply a canonical fingerprint.
 */
public final class ShaderKey {

    private final Class<?> composerType;
    private final Object descriptionFingerprint;

    private ShaderKey(Class<?> composerType, Object descriptionFingerprint) {
        this.composerType = composerType;
        this.descriptionFingerprint = descriptionFingerprint;
    }

    public static ShaderKey of(Class<?> composerType, Object descriptionFingerprint) {
        return new ShaderKey(Objects.requireNonNull(composerType, "composerType"),
                Objects.requireNonNull(descriptionFingerprint, "descriptionFingerprint"));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShaderKey k
                && composerType.equals(k.composerType)
                && descriptionFingerprint.equals(k.descriptionFingerprint);
    }

    @Override
    public int hashCode() {
        return 31 * composerType.hashCode() + descriptionFingerprint.hashCode();
    }

    @Override
    public String toString() {
        return "ShaderKey[" + composerType.getSimpleName() + ", " + descriptionFingerprint + "]";
    }
}
