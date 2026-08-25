/**
 * The SDF ray-march technique: a scene of surfaces, composed at runtime into the shader that renders it.
 *
 * <p>{@link dev.vexelray.technique.sdf.SdfScene} is the description — a {@code Surface}, a {@code Shading}
 * model, {@link dev.vexelray.technique.sdf.MarchSettings}, a lens. {@link dev.vexelray.technique.sdf.SdfComposer}
 * compiles it to a fullscreen vertex+fragment pair. Because the description is a record of records, equal scenes
 * are equal values, and {@code ShaderCache} serves one compiled shader set for all of them without a line of
 * bespoke fingerprinting.
 *
 * <p>Nothing that changes per frame is part of the description: camera position, orientation, and viewport
 * aspect are push constants, so turning your head or resizing the window does not recompile a shader.
 *
 * <p>The generated field is exposed as a standalone {@code float sdf(vec3)}
 * ({@link dev.vexelray.technique.sdf.SdfComposer#sdfFunction}), which is both the shader's own single copy of
 * the field and the function a host lowers to the CPU for collision — the same definition, drawn and simulated.
 *
 * <p>The module is named for what it becomes: when the Phase 2 runtime lands it grows a {@code RenderTechnique}
 * implementation alongside the composer. See {@code docs/architecture.md} §3 and
 * {@code docs/surface-compiler.md} stage S1.
 */
package dev.vexelray.technique.sdf;
