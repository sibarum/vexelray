# vexelray-demo-planet

**Status:** milestone 1 (the planet from orbit) at first light, 2026-10-31. **Goal:** a full-scale procedural
exoplanet with continuous level of detail from orbit down to single blades of grass and the leaves on a tree, flown
in a saucer whose Flying Object Identification Number is stencilled on a retroreflective sticker that is starting to
peel at one corner.

```
mvn -pl vexelray-demo-planet -am compile exec:exec                                 # a window: a slow orbit
mvn -pl vexelray-demo-planet -am compile exec:exec -Dplanet.args="capture DIR"     # the views, as PNGs
```

A window started from an agent's shell runs in the background and cannot hear the keyboard; fly it yourself.

## What exists

```
dev.vexelray.demo.planet
  Terrain          the height field: 11 octaves of 3D value noise over the unit sphere, in km, as core IR and
                   as a CPU twin computing the same function. Its bounds are computed from the octave list.
  PlanetShader     one fullscreen fragment stage: the march, the ground and sea, the air, the stars.
  PlanetTechnique  draws it; writes depth in the lens's convention, so later techniques composite against it.
  PlanetDemo       the window and the captured views.
```

- **A world of 3,000 km radius**, land to 9 km high and sea as deep, under 80 km of air. Kilometres throughout.
- **Bounds are proven, not sampled.** `Terrain.maxHeight` holds by construction (normalised octaves of noise in
  `[-1, 1]`, clamped). `Terrain.slopeBound` is the sum over octaves of amplitude times frequency times the noise's
  steepest slope. The field `|p| - R - max(h, 0)` therefore changes by at most `Terrain.lipschitz(R)` per km, and
  the march steps by the field over that (about 0.97 of it) and never crosses the surface. `TerrainTest` holds both
  bounds against the CPU twin at 40,000 random points.
- **The hash is GPU-stable**: Dave Hoskins's, multiplies, adds and fracts of small numbers only, the same on the GPU
  and the CPU. vastir-tools' `Noise` uses the `sin` hash that `Fathom.hash` explains is not.
- **The shell is the analytic bound sphere**, `R + maxHeight`: exact and free to intersect. The march starts where
  the ray enters it. Milestone 2 replaces it with a quadtree of mesh shells under the same contract.
- **Ground and sea.** The sea is the height clamped at zero. Land is sand at the shore; forest, grassland or desert
  by a moisture noise at the scale of climates, drying with height; rock on slopes; snow above a snow line that
  falls from 6.5 km at the equator to the sea at the poles. Sea darkens with depth, ices toward the poles, and
  glints in the sun.
- **Air**: Rayleigh and Mie single scattering (Earth's coefficients, per km) along the view ray inside the air and
  in front of what it hits, with the optical depth toward the sun at every sample and the planet's shadow. The
  ground is lit by the sun through the same air.
- **Space**: hashed stars and the sun's disc, through whatever air the ray crosses.

## Known wrong

- **A thin dark line** crosses the land in the low-orbit view (view 4). Straight on screen, so a plane through the
  camera: probably a precision seam in one of the sphere tests. Not yet traced.
- **Low views are hazy and featureless.** The finest octave is about 1.8 km across, and the normal is taken at about
  a pixel's footprint; there is nothing smaller to see. Ground detail is milestones 2 and 3.
- **The night-side ring is a pixel or two wide** at the captured distance. 80 km of air on a 3,000 km world; true to
  scale.
- **The near plane is 1 km** (`PlanetDemo.LENS`), because the stack's clip depth is hyperbolic. See below: this
  must change before anything comes close.

## Plan

| | Milestone | Proves |
|---|---|---|
| M1 | The planet from orbit: terrain shell, air, stars. **First light.** | precision at orbit, the bound contract |
| M2 | Descent from orbit to the ground with no popping | logarithmic depth, quadtree shells, the error metric |
| M2½ | Clouds | the scale ruler between orbit and treetops (see Perception) |
| M3 | Ground detail: rocks, then grass | shell-space fields, collapsing to albedo and normals with distance |
| M4 | Trees down to the leaves | nested shells |
| M5 | The saucer: hull, flight, chase camera, the FOIN sticker | input (`tactroller`), decals, a retroreflective material, the peeling corner as a curled flap with its adhesive side showing |

### M2: the descent

- **Logarithmic depth first.** The range from a blade of grass at 0.1 m to orbit at 40,000 km is 4×10⁸, and the
  hyperbolic `a - b/z` of `ClipDepth` puts its precision against the near plane. Decided: make the curve a choice on
  `ClipDepth`, hyperbolic (today's, the default) or logarithmic, `log(z / near) / log(far / near)`, carried by the
  `Lens`, so every technique in a scene agrees. In a 32-bit float buffer that resolves about 1.2×10⁻⁶ of the
  distance everywhere: 0.1 mm at 100 m, 12 m at 10,000 km. Marched surfaces already write their depth per pixel, so
  for them it is free. Rasterized triangles interpolate depth linearly, which a log curve is not, so the mesh stage
  writes it per fragment when the log curve is chosen (giving up early depth for those meshes). Reversed-Z with an
  infinite far plane was the alternative: nearly logarithmic, keeps early depth, but flips the stack's depth
  convention (clear 1.0, `LESS`) in every pipeline.
- **Camera-relative everything.** Positions in doubles on the CPU; every shell's map into its field composed relative
  to the eye, so the GPU sees small numbers (the clip-shell prototype's `FieldSpace` already does this per shell);
  noise taken from per-chunk integer cell offsets, never raw planet coordinates.
- **Quadtree shells.** A cube-sphere quadtree whose every node is a shell. The terrain is a sum of octaves with known
  amplitudes, so a node bakes the octaves coarser than itself into its mesh, offset outward by the bound on the rest,
  and the shader marches the rest. A node splits when its error passes about a pixel, and morphs, never pops.
  The design is [../docs/clip-shells.md](../docs/clip-shells.md).

## Perception

What simulations get wrong about falling toward a planet is mostly perceptual, and most of it can be put in the
image. What is known:

- **Time.** Gravity's effect on clocks here is a part in a billion. Felt time is another matter: in Stetson, Fiesta
  and Eagleman (2007), people dropped 31 m into a net remembered the fall as about a third longer than it was, but
  perceived nothing in slow motion during it. The stretch is in memory, laid down under fear and novelty.
- **Speed** is perceived in eye-heights per second, not true speed (Larish and Flach, 1990, global optical flow
  rate). From orbit the ground barely drifts at 7.8 km/s; low and slow feels fast. A descent reads as nothing, then
  everything: the ground rush. Lee's tau (1976), time to contact from the rate of optical expansion, describes the
  end of it.
- **Depth and size.** Stereo gives out after tens of metres and parallax nearly vanishes at altitude. With nothing of
  known size the land reads as a model or a map, and rescales suddenly when something familiar resolves: a tree, a
  road, a cloud's shadow. Balloonists and high-altitude pilots report the ground as a bowl, the horizon staying near
  eye level.
- **Detachment.** High-altitude pilots report the break-off phenomenon, a sense of being cut off from the Earth
  (Clark and Graybiel, 1957).

What follows for the build: **no visible LOD transitions** (popping or swimming detail destroys exactly the cues the
brain scales by); **clouds** as a parallax layer of known size casting shadows, between orbit and treetops; a **field
of view matched to the viewer's distance from the screen**; and **a constant near reference in frame**, the saucer's
hull and its sticker, the way a cockpit is one.
