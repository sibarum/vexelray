# The Vexel World Model — design direction

> **Living design doc, high-level.** A north star for how VexelRay could represent *worlds* (not just single
> hand-composed scenes): a sparse octree of small, bounded SDF primitives that soft-blend through a material
> matrix, where every node is a "vexel" — a prefiltered, self-contained, shadeable surface definition at its own
> scale. This is a research direction, prototyped in [`vexelray-experimental`](../vexelray-experimental) before
> any commitment; it slots in as a `RenderTechnique` atop the runtime (see [`architecture.md`](architecture.md)
> and [`refactor-decisions.md`](refactor-decisions.md)).

---

## 1. Thesis

A world is a **sparse voxel octree (SVO) of small, AABB-bounded SDF primitives.** Primitives carry floating-point
values that determine how they blend across a **material matrix**, so a continuous floor is many primitives with a
homogeneous blend (they melt into one seamless surface), while rocks and trees vary in how smoothly they interface
with the ground — some barely perceptibly, some fully. Each octree node is a **vexel**: a world-space surface
definition holding *whatever the graphics pipeline needs to shade a pixel* at that node's scale.

Two properties make this attractive over both the analytic-field approach we prototyped and a polygon world:

- **It is seam-free by construction.** Every primitive is a real bounded SDF, and a smooth-union of valid distance
  fields stays a valid (Lipschitz) distance field — so sphere-tracing is clean, with none of the overshoot seams
  that plague the heightfield-as-SDF trick (see refactor-decisions D10–D12 and the experimental montages).
- **LOD is continuous and (at selection time) free.** Because each vexel is a prefiltered surface at its scale,
  cone-marching stops at the node whose size matches the ray's footprint and shades from it — no discrete model
  swaps, no popping, and therefore no hard draw-distance boundary to hide behind fog. Distance dissolves into
  softness instead.

---

## 2. Core concepts

- **Primitive.** A small, AABB-bounded SDF (sphere, box, capsule, blob, …) with a material id and blend
  parameters. The atoms of the world.
- **Material matrix (M×M).** `blend[i][j]` = how the material `i` interfaces with material `j`. This keys blending
  off *materials*, not object pairs — O(M²) table instead of O(N²) per-pair data. That abstraction is what makes
  it authorable and scalable (grass↔grass melts fully → seamless floor; rock↔grass melts slightly; trunk↔anything
  ≈ 0 → crisp).
- **Blend operator — weighted soft-min (partition of unity).** *Not* naive pairwise `smin` (commutative but **not
  associative** → grouping-ambiguous where 3+ primitives meet). Use exponential soft-min / log-sum-exp,
  `−log(Σ wᵢ·exp(−k·dᵢ))/k`, which is a symmetric function of all local `dᵢ` at once → associative, order-free,
  genuinely N-ary. Encode the material matrix as per-material **weights and sharpness** inside it, rather than a
  literal per-pair radius (a per-pair `k` re-breaks the N-ary cleanliness). `exp`/`log` lower on both the SPIR-V
  and CPU (Truffle) backends, so this keeps render==sim.
- **Bounds, dilated by blend radius.** A primitive influences points *outside* its tight SDF bound (that's what
  blending is), so the AABB used for SVO placement / culling must be the primitive's bound **expanded by its max
  blend radius**, or blend edges pop under culling.
- **SVO acceleration.** The octree spatially bins primitives: per-sample you evaluate only the local set (the ones
  whose dilated bounds contain the point), frustum culling falls out of not descending out-of-frustum nodes, and
  the same structure serves CPU queries (collision / line-of-sight are local → traverse the same tree).
- **Vexel.** An octree node's payload: a prefiltered, self-contained surface definition at that node's scale —
  the LOD representation. Its exact fields are defined by what the shading pipeline needs (see §4).

---

## 3. Why this representation

| Property | Polygon world | Analytic SDF field (what we prototyped) | Vexel SVO world |
|---|---|---|---|
| Seams / overshoot | n/a | heightfield overshoot seams (D10–D12) | **none** (proper bounded SDFs + soft-min) |
| LOD | discrete authored meshes / imposters; popping | none for a naive field | **continuous, prefiltered, no popping** |
| Draw distance | hard boundary → fog cliff | trace-reach cliff | **dissolves to softness** (+ nearly-free DoF) |
| Frustum culling | per-object bounds | rays already in-frustum, but full min() every step | **SVO node culling for free** |
| Cost scaling | scene complexity | scene complexity per step | **screen pixels**, local sets per sample |
| render == sim | n/a | holds (shared IR) | **holds** (shared buffer; exp/log both backends) |

**On "free LOD," precisely:** *selection and continuity* are free (footprint vs node size, stop descending);
*building and storing* the prefiltered payload is the real cost, and it is the **hierarchy** that grants free LOD,
not SDF per se. Fairness note: Nanite gives near-continuous polygon LOD automatically too — SDF's edge is that
prefiltering a *field/signal* is natural, whereas prefiltering *mesh topology* is hard. We are leaning on the part
that is easy for this representation.

**On "dissolve instead of fog":** distance produces *correct filtering* (loss of high frequencies → smoothness),
which reads as softening — subtly distinct from a blur. Two independent levers realise the aesthetic: (a) the
footprint-driven detail-fade from LOD, and (b) **true depth-of-field, nearly free** because a raymarcher already
has exact per-pixel depth (the hit `t`) — no prepass or gather hacks. Detail-loss + mild DoF/atmospheric ≈ how
human acuity and air degrade distance, i.e. more natural than a fog wall.

**LOD vs sim is a feature, not a divergence:** rendering is view-dependent (reads coarse vexels far away);
collision/line-of-sight are not (always read the fine leaves near the player). They consult *different depths of
the same tree* — consistent (same data), never contradictory.

---

## 4. The vexel payload (the prefiltered surface definition)

The crux: LOD converts "how do I simplify?" into "**how do I prefilter a surface faithfully?**" — and appearance
does **not** average linearly. A minimal faithful payload per vexel:

- **Shape proxy** at that scale — a plane+offset "contour" (à la ESVO) or a few compact SDF coefficients / a tiny
  brick. Enough to intersect and to give a coarse distance.
- **Aggregate normal + normal-distribution width (NDF / roughness).** Storing only a *mean* normal loses the
  *variance* of the sub-surface normals — and that variance is the highlight. Drop it and distant surfaces go
  glassy and **specular-alias** (crawling shimmer). Store mean + spread and *widen effective roughness with
  prefilter level* — Toksvig / LEAN / LEADR territory. This is the load-bearing field.
- **Material-blend coefficients + coverage/alpha.** The material-matrix weights aggregate here; coverage handles a
  partially-full coarse vexel for antialiased silhouettes.

**Aggregation rule (child → parent) must be defined per field** and must support **inter-level interpolation**
(blend node depth *d* and *d+1* by footprint fraction — the "trilinear across mips" trick) or transitions pop.
The payload is deliberately *defined by the pipeline's shading needs*, keeping the tree structure orthogonal to
what a technique stores — the same open-technique spirit as the engine's `RenderTechnique` SPI.

---

## 5. Hard problems / open questions

1. **Prefiltering correctness (the deep water).** Faithful child→parent aggregation, especially the normal
   distribution (anti-specular-aliasing) and coverage. This is where the engineering actually lives; Dreams and
   the VXGI/GigaVoxels line poured effort here.
2. **Edit propagation.** A dynamic/editable world must re-propagate the prefiltered pyramid up the tree on edit
   (incremental up-sweep). Cost and correctness of that update.
3. **Blend operator parameterisation.** Confirm weighted soft-min with per-material weights/sharpness expresses
   the desired range (seamless ↔ crisp) without the per-pair-k associativity trap.
4. **Authoring.** How worlds are authored/generated as primitive sets; guaranteeing floor primitives overlap
   within blend radius for true seamlessness.
5. **Traversal divergence** on GPU (per-ray octree walks diverge) — mitigations: cone/beam upper levels, ray
   binning, stackless traversal. Deferred until there's something to profile.

---

## 6. Fit with the engine

- Prototyped **now** in `vexelray-experimental` as `ShapeField` candidates — no runtime dependency, scored against
  the analytic fields on perf / fidelity / applicability.
- Lands eventually as a first-class **`RenderTechnique`** (a "vexel world" technique) compositing into the shared
  target, once the technique-refactor runtime (refactor-decisions Phase 2) exists. The two efforts are
  independent until then: research in the harness, plumbing in the runtime.
- Keeps the engine invariants: **render == sim** (CPU and GPU traverse the same buffer; blend math uses only ops
  both backends implement) and **open techniques** (the vexel payload is defined by shading needs, not fixed).

---

## 7. Plan forward

De-risk cheapest → hardest, all in the harness first so each step is measured before the next is built.

- **V0 — Blend operator + soft-unioned primitives.** A `ShapeField` of a few AABB-bounded primitives (ground slab
  + rock + "tree") combined via **weighted soft-min** driven by a small material matrix; emit material/blend
  output, not just shape. *Goal:* confirm seam-free sphere-tracing and believable material interfaces; validate
  the operator. Cheap; existing harness.
- **V1 — Spatial structure + culling.** Bin primitives into a grid/SVO with **blend-dilated AABBs**; evaluate only
  the local set per sample; add per-tile (froxel) culling. *Goal:* measure `full-min-over-N` vs culled as N grows,
  and show CPU queries reuse the same structure. *(Do GPU timestamp timing in the harness first — see cross-cutting
  — so these scaling numbers are real GPU time, not cold shader-compile.)*
- **V2 — Vexel payload + prefiltering (the research core).** Define the payload (shape proxy + normal+NDF +
  material coeffs + coverage), build the child→parent pyramid, cone-march with LOD cutoff + inter-level
  interpolation. *Goal:* correct minification (no specular aliasing), no popping.
- **V3 — The aesthetic payoff.** Footprint-driven detail-fade (dissolve, no fog cliff) + depth-of-field from the
  hit depth. *Goal:* the "blurry horizon instead of a gray wall" look, validated.

**Cross-cutting / plumbing** (parallel or interleaved):
- **GPU timestamp timing** in the harness (persistent pipeline + timestamp queries) so perf numbers from V1 on are
  true GPU frame time, not cold compile. Do before V1.
- **Land the technique-refactor runtime** (refactor-decisions Phase 2) so the vexel world can become a real
  `RenderTechnique`; apply the inline→function refactor (D12) to Fathom while there.
- **Commit** the current two-repo work (SupirVast `Noise`; vexelray engine-api + render-pass split + experimental
  harness) before starting V0, so the research builds on a clean base.

Recommended start: **V0** — it's cheap, it directly tests the thesis (seam-free + material blend), and it either
validates the whole direction or exposes the blend-operator issues early.
