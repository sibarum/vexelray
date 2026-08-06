package dev.vexelray.pipeline;

/** Coarse category of a {@link Pass}. The sealed {@code Pass} type is the precise discriminator; this is for logs. */
public enum PassKind {
    /** Rasterise polygon meshes with a generated material shader. */
    RASTER,
    /** Ray-march an SDF scene over a fullscreen triangle. */
    RAYMARCH,
    /** Dispatch a compute kernel (via SupirVast's compute path). */
    COMPUTE,
    /** Fullscreen image operation over sampled inputs (tone-map, blur, composite). */
    POST
}
