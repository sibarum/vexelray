/**
 * Polygon meshes as a render technique: triangles through the rasteriser, composited with a marched scene in one
 * colour and depth target.
 *
 * <p>Four types. {@link dev.vexelray.technique.mesh.Mesh} is the polygons — any simple planar polygon, convex or
 * concave, triangulated once. {@link dev.vexelray.technique.mesh.Placement} is where one stands, in the camera's own
 * angle convention. {@link dev.vexelray.technique.mesh.MeshShader} is the shader as {@code core} IR: the march's
 * pinhole run forwards, the march's depth curve from {@code ClipDepth.clipZ}, and the scene's {@code Shading} at
 * every fragment. {@link dev.vexelray.technique.mesh.MeshTechnique} is the
 * {@link dev.vexelray.engine.RenderTechnique} that records it.
 *
 * <p>What this deliberately does not do is give a mesh to the simulation. A marched field is evaluated on the CPU
 * from the same IR it is drawn with, which is the render==sim property; a triangle soup is not a field and has no
 * such twin. Meshes are for the things that do not need to be collided with — props, markers, imported art — and a
 * scene that needs one to be solid needs a field beside it.
 */
package dev.vexelray.technique.mesh;
