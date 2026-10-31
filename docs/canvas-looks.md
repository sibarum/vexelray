# Looks on every canvas primitive, and a vertex stage built from shadelets

**Status:** proposed 2026-10-30, not started. **Consumer:** `vexelray-gui` (`docs/plans/rich-text.md`: effect spans,
glyph morphs), and any application drawing images, video or fields with effects. **Where the work lands:**
`vexelray-canvas` (`Canvas`, `CanvasVertex`, `CanvasShader`, `shadelet`), `vexelray-technique-canvas` (the set 2
layout), `vexelray-shadelets` (the new sources), `vexelray-msdf-maven-plugin` (a family in one atlas).

## What exists

- **Two primitives take a look.** `Canvas.box(..., Look, float...)` and `Canvas.field(..., Look, float...)` set the
  element from the look (`elementFor`) and grow the quad by `LookTable.reach`. Glyphs (`appendGlyphs` →
  `glyphVert`) and images (`Canvas.image`) are written with the core looks and nothing else.
- **Shadelets are fragment-only.** `CanvasShader.Vertex` is one fixed module, `canvas.vert`. Only the fragment stage
  is composed, named `canvas.frag.<manifest hash>` so stale SPIR-V is never loaded against other layouts.
- **The tables are invisible to the vertex stage.** Set 2 is declared with `Vk.SHADER_STAGE_FRAGMENT_BIT` in
  `CanvasTechnique` (and `Composition`'s javadoc says "read by the fragment stage only").
- **Reach is a CPU decision.** The quad is grown when it is emitted, from the look's reach for its shared and
  per-instance values. Nothing in a shader moves geometry.
- **Faces can share an image.** `AtlasData.face(int)` returns a face's metrics and glyphs over the same image; the
  primary atlas already carries NotoSansMono beside NotoSans that way ([math-face.md](math-face.md)). The
  `<families>` bake (one atlas per face) is the newer path and does not use it.

The principle this plan follows: **a look is the canvas's, not one primitive's.** Whatever can be drawn can be
drawn by a look. A look reads no clock: what moves is a per-instance value the caller writes, so a frame is a
function of what was drawn, captures are reproducible, and a still canvas sleeps.

---

## 1. A look on a glyph

`Canvas.text(TextLayout, String, ..., Color, Look look, float... instance)`, in each of the existing shapes.
`appendGlyphs` sets the element per glyph the way `box` does.

- **The source must be the composition's text source**, `CoreShadelets.text(type)` (or a source built on it, §6).
  A glyph has no box for a box source to read. A look that names another source is refused loudly, once.
- **Every glyph is its own element**, so its record carries a small **glyph context** ahead of the look's own
  instance floats: the glyph's index in the call, the count, and its x along the call's advance in px. The
  `Fragment` gains these as `glyphIndex()`, `glyphCount()` and `glyphX()`. Without them every letter of an effect is
  the same letter: glitter that sparkles in step, a wave with no travel.
- **Glyphs without a look cost what they cost today.** They carry the core text record and no context. Only text
  drawn with a look writes records, so a page of plain text does not eat the record capacity (records past the
  capacity are already dropped and said).

Done when: a capture of an outlined word matches a reference, and the same word with glyph context driving a hue
shows a different hue per glyph.

## 2. Inflating a glyph quad

A glyph's look has a reach like a box's. The quad grows by it, and the glyph stays where and how big it was: the
UVs extend by the same proportion, so a texel still lands on the same place.

**Past the cell, the atlas is a neighbour.** An inflated fragment's UV leaves the glyph's cell and would sample the
next glyph. So the fragment clamps its sample to the cell and carries the distance on outward from the clamp point,
as the distance to the cell's box. That is exact inside the bake's range and box-shaped beyond it, which at a
glow's radius is what letters look like anyway.

**The cell rides in slots glyphs already leave empty.** Glyphs use only `shape.x` (screen px range) of loc4 and
none of loc3 (`local`). One packing:

| Slot | Glyph value |
|---|---|
| `local.xy` (loc3) | px from the cell's centre — glyph-local coordinates, which effects want anyway |
| `shape.yz` (loc4) | the cell's half-size, in UV |
| `shape.w` (loc4) | UV per px |

The cell's centre in UV is then `uv − local · shape.w`, and its bounds follow. No new attribute, no stride change.

**The reach is clamped to what the field can say.** A glyph's distance is true out to its atlas's range, and a
painter that asks further gets the box approximation, not an error. An effect that needs a true distance further
out (a thick outline that keeps a glyph's counters) needs a family baked with a wider range; that is a build
setting in `<families>`, said in the shadelet's javadoc.

Done when: a glow wider than the cell draws with no trace of a neighbouring glyph, and a capture with inflation and
without matches inside the glyph's own cell.

## 3. Deformers: a vertex stage built from shadelets

`Shadelet` gains a fourth kind:

```java
non-sealed interface Deformer extends Shadelet {
    /** An affine map of the element in canvas px: a mat2 and a translation, about {@code pivot}. */
    Expr deform(VertexView vertex, Expr pivot, StyleView style);
}
```

- **Affine by construction.** A deformer returns a matrix, not a position. A quad is two triangles; corners moved
  into a non-parallelogram interpolate their UVs differently in each, and the diagonal shows as a kink. Returning a
  2×3 map makes that unsayable. Translate, rotate, scale, shear and every combination are in reach: bounce, spin,
  jitter, a pulse. **Bending** a glyph is a fragment's job, as an inverse warp of where it samples, inside a quad
  inflated far enough (§2). That is the two stages combining, and it is how a wave through a letter is drawn.
- **The pivot is the element's centre**, which the vertex knows as `pos − local` (box-centred `local` for boxes,
  images and fields; cell-centred for glyphs, §2). Per-glyph rotation turns each letter about itself.
- **A look holds at most one deformer.** Several compose in Java before they reach the shader, or are one
  deformer. A look with none costs nothing in the vertex stage beyond reading its record.
- **Its reach is how far it can move a corner**, from its shared and per-instance values, and the canvas counts it
  wherever it bounds an element. Clipping needs nothing: the clip is coverage at the fragment's own screen
  position (`CanvasVertex` loc5–6), so a moved fragment is clipped where it lands.
- **The vertex stage is composed.** `CanvasShader.Vertex` becomes `canvas.vert.<manifest hash>`, from the same
  `Composition` the fragment stage is, and the supirvast plugin precompiles both. A composition with no deformer
  hashes to the stage that exists today.
- **Set 2 becomes visible to both stages**: `SHADER_STAGE_VERTEX_BIT | SHADER_STAGE_FRAGMENT_BIT` in
  `CanvasTechnique`, because a vertex finds its look through its element record (loc8). The look header gains a
  deformer id and offsets, after the painters, so every existing look reads as it did.

It is all appearance. Nothing that hit-tests reads a deformed position, which is the only safe answer for an
animated one.

Done when: a word whose glyphs each rotate by a glyph-index-driven angle matches a reference, a composition without
deformers produces byte-identical vertex SPIR-V to today's, and a deformed glyph clipped by a rounded box is cut
where it lands.

## 4. A look on an image, a stream and a field from art

`Canvas.image(..., Look look, float... instance)` in each existing shape. The source is the box (the image's
rounded rectangle); the core `image` painter is what a look names to draw the picture, and its filter and
linear-light stamp are unchanged.

- **Painters that read the texel** work on any picture: duotone, pixelate, scanlines, a colour grade. They need
  the image's texel read made available to a painter as `PaintContext.texel(uv)`, which today is private to
  `IMAGE_FILL`.
- **Painters of distance** work on the box: a glow around the picture's frame, a ring, a ripple.
- **A glow around the subject** (the opaque part of a PNG) needs a distance from its alpha. For a static image
  that is a field: `vexelray-fields-art` bakes one from "one channel of a raster, such as its transparency", and it
  is drawn with `Canvas.field` and a look, which works today. For live content that needs a GPU distance pass (jump
  flooding) and is **not** in this plan.
- **A stream** (frame-contract.md's `StreamTexture` in vexelray-gui) is an image handle. A look on it applies to
  every frame; its per-instance values are written per frame from the playhead, which is how an effect is held to
  a soundtrack.
- **An SVG** is already covered: one colour through `ArtField` into a field, several through `ArtPair` (a mark's
  field beside its colour). An SVG made of gradients is a raster and takes an image look.

Done when: a duotone painter on an image and on a stream match references, and the image's filter (`LANCZOS`, say)
is still applied under it.

## 5. Text across glyphs: the span-text source

A source for one quad that covers a run of glyphs, for effects that are the run's shape rather than any glyph's:
a bloom past the word, smoke off a line. The distance at a point is the union of the run's glyph distances.

- **The glyphs are a table, not instance floats.** A record holds a fixed count of per-instance floats per look, and
  a run's length varies. So the record points into a **glyph segment** of the tables, written by the canvas: per
  glyph, its cell (UV centre and half-size) and its offset in the quad. The source loops over it.
- **Exact near, box far**, as §2: within a glyph's range the MSDF distance, beyond it the distance to its cell.
- **Bounded.** A quad covers at most 32 glyphs; a longer run is several quads, each carrying the run's offset so a
  pattern does not restart. The loop is the cost, and it is fixed per pixel.
- **The atlas is one per face.** A run that crosses faces is split by face, as text runs already are, so one quad
  reads one atlas.

This is the engine half of rich-text.md's span quads. The decoration half (underlines and their kin) is a box
source on a quad the GUI places, and needs nothing here.

Done when: a bloom 4× the atlas range around a word shows no seam between glyphs, and a 100-glyph run costs the
same per pixel as a 32-glyph one.

## 6. Morphing between faces

`Effects.FIELD_MORPH` mixes two cells of one texture by `t`, offset by `(du, dv)`, through the core *field* source.
A glyph needs a `GLYPH_MORPH` source with three differences:

- **The text source on both cells**, taking each median before mixing. Mixing the medians, not the channels, is
  what keeps the corners.
- **An offset and a scale** from the first cell to the second: glyphs differ in size and bearing, so four
  per-instance floats, not two. The quad is inflated (§2) to the union of both glyphs' boxes.
- **Both faces in one image.** A morph reads both cells from what is bound. The plugin's `<families>` gains an
  option to bake a family's faces as faces of one atlas, the mechanism `AtlasData.face(int)` and the primary
  atlas's mono face already use. No second texture binding.

Regular to bold, upright to italic, and along a variable font's baked instances morph cleanly, because the shapes
correspond. Different letters crossfade through blobs, and the javadoc says so.

Done when: a glyph at `t = 0` and `t = 1` matches the static faces, and at `t = 0.5` draws nothing outside the
union of the two.

---

## 7. Order

| Step | Size | Needs |
|---|---|---|
| 4 (images) | small | — |
| 1 (glyph looks) | medium | — |
| 2 (inflation) | small | 1 |
| 3 (deformers) | medium | 1 for glyphs; none for boxes and images |
| 5 (span-text source) | medium | 2 |
| 6 (glyph morph) | medium | 2, and the plugin option |

Images first: the smallest change that puts a look on a primitive that has none, and it proves the overload shape
before glyphs copy it.

## 8. Open questions

- **What a larger ubershader costs an element that uses none of it.** Shadelets dispatch by an if-chain on ids, so
  a plain box should pay nothing per added effect. Measure the core set against the core set plus every effect in
  `vexelray-shadelets` on a text-heavy frame before the effects library grows.
- **Record capacity under heavy effect text.** One record per glyph with a look. A page of animated text could be
  thousands; the capacity and what is said when it is exceeded may need to change.
- **Whether a deformer wants per-vertex rather than per-element values** (a corner pinned while the others move).
  Affine per element covers every case named so far; this waits for one that it does not.
