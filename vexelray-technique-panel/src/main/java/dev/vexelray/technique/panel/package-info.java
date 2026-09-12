/**
 * A {@link dev.vexelray.canvas.Canvas} on a plane in the world: 2D drawing projected through the scene's camera,
 * sharing the frame's depth buffer, with nothing in it fixed to a pixel size.
 *
 * <p>Three types. {@link dev.vexelray.technique.panel.Panel} is where the sheet of canvas coordinates hangs and
 * how big one of them is. {@link dev.vexelray.technique.panel.PanelShader} is the canvas uber-shader with the
 * camera in front of it — the same coverage core, a vertex stage that places the geometry on that plane, and a
 * fragment stage that differentiates the projection to find its own anti-aliasing width.
 * {@link dev.vexelray.technique.panel.PanelTechnique} is the {@link dev.vexelray.engine.RenderTechnique} that
 * records it into a frame the runtime began.
 *
 * <p>What this is <em>not</em> is a textured quad. There is no intermediate image, no resolution chosen in
 * advance, and no sampler in the path: the shapes are the geometry, so approaching a panel reveals more detail
 * in its corners rather than more texels in its blur.
 */
package dev.vexelray.technique.panel;
