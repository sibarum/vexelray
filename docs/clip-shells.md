# Clip shells: analytic fields shown through polygon meshes

**Status:** prototyped 2026-10-30 to -31 in `vexelray-technique-mesh`'s tests (`MengerShell*`, `FieldSpace`, `Sky`),
commits `83056fd` to `a406394`; nothing in a main source set yet. **Consumer:** `vexelray-demo-planet`, whose
terrain shells are this design at planet scale. **Where it lands when it graduates:** a technique beside
`vexelray-technique-mesh`, and asset items in `vexelray-codec-asset` (the `.vxa` file).

## The idea

A polygon mesh is drawn by the rasteriser, and each fragment marches an analytic signed distance field (a fractal, a
terrain, any shadelet) starting from the point where its ray meets the polygon. The mesh is a **clip shell**: it
says where a ray starts, and its surface is where the field is cut. The result is the CSG intersection of the mesh's
solid and the field's, `max(d_field, d_mesh)`, with the mesh half done by the rasteriser instead of stored.

At the entry point:

- **The field is already negative** (inside its solid): a **cut face**. Lit with the polygon's normal (or a smooth,
  creased shell normal), and coloured from the field's interior (depth inside, orbit traps, iteration counts): a
  real cross-section, not a cap.
- **Otherwise the ray marches**, and a hit is lit with the field's gradient.

The goal is as much fidelity as possible for as little cost as possible: the mesh skips the empty space in front, and
a shell fitted to the field leaves only a thin band to march.

## What the prototype established

- **Any mesh is a clip shell**: a cube, the same cube on its corner, a random lumpy concave blob. Nothing about
  concavity needed special handling in the default mode.
- **The march does not stop at the back face** (the default, by decision). The march has a fixed budget, so stopping
  early saves nothing a GPU can use; the rest of the budget finds farther hits. Consequence: clipping happens only at
  the entry; a ray leaving the shell can show unclipped field beyond it. That reads as a portal or a funhouse, and
  was kept for that.
- **Back-face exit, as an option**: a ray (the eye's and every reflected one) becomes transparent where it leaves its
  shell, with no re-entry. The prototype finds the exit by testing every triangle of the mesh, baked in the mesh's
  own coordinates and reached through the model matrix, so a shell can move and turn. Whether a ray is heading in or
  out is the **parity of the crossings ahead of it**, not any face's winding (`Mesh.box` does not wind consistently).
  The cost is per triangle: a prototype's way. A real one takes the exit from a back-face depth pass, which is exact
  for each pixel's first interval; re-entry (depth peeling, k-buffers, ray queries) was investigated and set aside.
- **Field space is its own frame** (`FieldSpace`): placed in the object (it moves, turns and scales with the shell as
  one solid) or in the world (the shell moves through it), and movable on its own either way. Object and field
  compose on the CPU into one world-to-field map, a quaternion, an offset and one scale: 8 floats, which with the
  mesh's 24 fills Vulkan's guaranteed 128 bytes of push constants. A ray stays a ray under the map, so a fragment
  maps its entry and direction once and the march costs nothing more. Rigid plus uniform scale only, so a field
  distance is a world distance times one number and steps stay safe. `FieldSpaceTest` round-trips random
  placements through the shader's formula.
- **Fills**: the sponge, its inverse (`-d`: still a distance), and the two mixed cell by cell (`CHECKER`, `HASH`).
  A mixed fill's field jumps at a wall between unlike cells, so it is made safe three ways: each cell's field is
  intersected with its own box (which ends a solid at the wall with the wall's normal), no step goes more than a
  hair past its cell's wall before the field is read again, and a gradient's taps are all taken in the hit's cell.
  Cells sit half a period off the field's own lattice, so a shell built on the lattice never has faces lying exactly
  on cell walls, where the cell a point belongs to would flip by rounding.
- **Darkening**: by steps taken, or by distance travelled in field units (scales with the field), or not at all.
- **Polish**: the field as metal, reflected rays marched again in field space with their own budget, falling back to
  a ground plane or the sky; cut faces as copper reflecting only the ground and the sky (their reflected ray leaves
  the shell at once). Schlick's Fresnel.
- **A procedural sky** (`Sky`): one IR function shared by the background pass and every reflection, so a reflected
  sky is the sky. The background pass draws after everything, two floats short of the far plane, testing depth
  without writing it, so it covers only pixels nothing real has claimed.

## Design not yet built

### Bounded shells and the level-of-detail chain

For a field that is a sum of known terms (Fourier octaves, fractal iterations), split the detail: the coarse terms
are baked into the shell's mesh and the shader evaluates the rest. The rest is bounded (the sum of the omitted
amplitudes), so offsetting the shell outward by that bound **proves** containment, and an inner shell offset inward
proves solidity: a ray only ever marches the band between them, and its worst-case step count comes from the band's
thickness, not the object's size.

Level of detail is then one dial, how much is in the mesh and how much in the shader:

| Distance | Mesh carries | Shader carries |
|---|---|---|
| far | everything: a UV-mapped mesh and a baked texture | nothing |
| middle | the coarse terms, a loose shell | a few more |
| near | a tighter shell | the deep terms |

Inside the shader the level of detail is continuous for free: stop stepping when the step is under the pixel's
footprint (cone marching), stop iterating when the next term is smaller than a pixel. The far texture is baked by
the same shadelet (march from each shell texel inward), so the crossfade between levels does not pop.

To decide: the per-shadelet step factor (many fractal estimators overestimate; a Fourier sum's Lipschitz bound is
provable: the sum of amplitude times frequency); baking with the shadelet itself, in a compute pass, so the bake and
the shader cannot disagree; and that discarded fragments at grazing silhouettes are the expensive ones, which a tight
shell edge and a deferred setup address.

### Shell geometry

- **Curved shells**: per-vertex normals make PN-triangle patches, far tighter than flat triangles. A patch bulges
  past its flat triangle, but a Bézier patch lies inside its control net, so rasterising the net (or the triangle
  pushed out by the bulge) keeps containment proven.
- **Shell space**: the interpolated normal as an axis makes the band a coordinate system, `(u, v, t)`, and a field
  defined in it wraps any base shape: fractal material applied the way a texture is (shell mapping, Porumbescu et
  al. 2005).
- **Creases**: one magnitude per vertex cannot say which of its edges are creased. Either a crease direction and
  magnitude per vertex (a line through the vertex; corners split), or per-edge sharpness as OpenSubdiv does.
  `MeshData` already carries channels on edges.

### In the `.vxa` file

A level is a record: outer and inner shells (`MeshData`), the bound it was baked to, the term split, the shadelet's
step factor, and its `Look`. The far level is `MeshData` with UVs and a `Texture`. All of it grows the schema at the
end, as `.vxa` requires; what is new is a level-chain item and a shell-with-bound item.

## The prototype

```
vexelray-technique-mesh/src/test/java/dev/vexelray/technique/mesh/
  MengerShellShader     the fragment stage: fills, fades, polish, exits, field space
  MengerShellTechnique  MeshTechnique's drawing with that fragment stage
  FieldSpace            a field's placement, in the object or the world, composed to 8 floats
  Sky, SkyTechnique     the procedural sky and its background pass
  MengerShellDemo       the scenes: `--capture DIR [look]`, `--motion DIR`, `--compare DIR`, or a window
```

A look is `steps`, `distance` or `reflect`, then any of `-exit` and one of `-inverse`, `-checker`, `-hash`:
`reflect-exit-hash` is all of it.
