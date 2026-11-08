# Text: `vexelray-text`

How VexelRay draws text from baked distance-field atlases: which faces an application has, which face draws each
character, where the glyphs land, and how a shader turns a sampled distance into a crisp edge. The atlases themselves,
and how to add a font or a face, are in the plugin's README,
[vexelray-msdf-maven-plugin](../vexelray-msdf-maven-plugin/README.md).

`vexelray-text` binds to nothing. It produces geometry (glyph quads, a vertex array) and SPIR-V; the Vulkan side
supplies the image, sampler and vertex buffer that draw them. It never reads a font file, and never decodes an image.

## The pieces

```
FontSet        every face the build baked (fonts.json), style matching, the per-code-point fallback chain
  Face         one face: family, style key, weight, stretch, slope, field type, its AtlasData, its pixels file
AtlasData      one atlas's msdf-atlas-gen JSON: AtlasInfo, FontMetrics, a GlyphData per code point
  AtlasInfo    the atlas header: type, distanceRange, emSize, width, height, y-origin
  FontMetrics  em size, line height, ascender, descender, underline, all in em
  GlyphData    advance, planeBounds (em, baseline-relative, Y-up), atlasBounds (atlas pixels); null bounds = blank
AtlasPixels    a face's texels, read from the .rgba / .r8 file, ready to upload
FieldType      MSDF (4 channels) or SDF (1)
GlyphLayout    one line of text to GlyphQuads, through a face's fallback chain
TextLayout     on top: line breaking, alignment, boxes and anchors, measurement, fit queries
GlyphQuad      one placed glyph: screen rect, Vulkan UVs, which face's atlas, its screenPxRange
TextMesh       quads to an interleaved clip-space vertex array for MsdfShader
MsdfShader     a standalone text vertex + fragment pair, authored as SupirVast core IR
```

## Faces: `FontSet`

```java
FontSet fonts = FontSet.standard().withFallback("sans", "mono");
FontSet.Face body = fonts.face("sans");                                // regular, or the nearest to it
FontSet.Face strong = fonts.face("sans", 700, FontSet.Slope.NORMAL);   // bold, if the build baked one
TextLayout layout = new TextLayout(fonts.layout(strong));
```

`FontSet.standard()` reads `/dev/vexelray/text/atlas/fonts.json` from the class path, the manifest the plugin's
`<families>` writes. `fromClassPath(path)` reads another manifest, and `read(open)` reads one from anywhere: `open("")`
is the manifest and any other argument is a path relative to it.

Reading the manifest parses every face's metrics. Pixels are a separate file, read by `pixels(face)`, which an
application calls for every face at startup and uploads. Keeping the two apart means measurement never depends on
whether a texture is resident.

**The set is fixed when it is read.** No face is added, generated or evicted afterwards. What the application holds
does not depend on what it is shown, so its font memory is the number the build printed. A face's `id` is its index
in `faces()`, stable for the life of the set, and is what a draw names.

**Asking for a face always gets one.** A style the family was not built with resolves to the nearest face by CSS font
matching (Fonts Level 4 §5.2): stretch first, then slope, then weight. Asking for semibold in a family that has
regular and bold gets bold, as a browser would. Upright prefers oblique to italic, and each slant prefers the other
slant to upright. A family that does not exist throws: family names are build-time facts, so a wrong one is a
programming error.

### Weight and slope are real faces

Bold is a bold font and italic is an italic one, each baked into its own atlas. There is no synthetic emboldening
(shifting the distance field's edge outward) and no synthetic italic (shearing the quads). Both were planned once, as
a stopgap; real faces came first and made them unnecessary. A synthetic bold thickens every stroke by the same amount,
closing counters and losing the design's contrast, and a sheared roman is not an italic: the letterforms differ.

What this means in practice: a weight or slope is only as good as the faces the build has. `vexelray-text` ships
**Regular only** for both families today (`fonts/NotoSans-Regular.ttf`, `fonts/NotoSansMono-Regular.ttf`), so asking
for bold or italic resolves to regular until those TTFs are added. Dropping `NotoSans-Bold.ttf` into `fonts/` and
building on Windows adds `sans/bold` with no code or pom change; see the plugin README's *Adding a font or a face*.

### Fallback is per code point

A face's `chain(face)` is every face of its own family, nearest style first, then every face of each family named in
`withFallback(...)`, in the order named, each ordered the same way. The first face in the chain that has a glyph draws
it. So an italic that lacks a letter borrows the upright one from its own design before any other family's, and a
code point Noto Sans lacks (box drawing, most arrows) comes from Noto Sans Mono when `mono` is a fallback. A family is
never its own fallback.

Only a code point no face in the chain has falls through. If it is printable, it draws the **asking** face's
missing-glyph box (the U+FFFD the plugin bakes into every atlas); if it is whitespace, a control or a format
character, it draws nothing and advances by nothing.

Measuring and drawing resolve through the same chain, so a borrowed glyph occupies exactly the width it is measured
at. Vertical metrics are the asking face's alone: ascent, descent and line height do not change because one glyph was
borrowed, and a borrowed glyph sits on the asking face's baseline.

## Layout

### `GlyphLayout`: one line

`fonts.layout(face)` gives a `GlyphLayout` over that face's chain; `new GlyphLayout(atlasData)` gives one over a single
atlas with no fallback. `layout(text, penX, baselineY, pixelSize)` walks the code points, advances the pen by each
glyph's advance × `pixelSize` (the em size in screen pixels), and emits a `GlyphQuad` for each glyph that has ink.
`measure`, `advance`, `ascent`, `descent`, `lineHeight` and `resolve(codepoint)` answer the same questions without
emitting anything. An overload adds extra advance after each whitespace code point, which is how justification
stretches a line.

There is no kerning (msdf-atlas-gen emits none), no shaping and no bidi: this is advance-based layout, right for
Latin, Greek, Cyrillic and symbol text, and wrong for scripts that need shaping.

### `TextLayout`: blocks

`TextLayout` wraps a `GlyphLayout` (or an `AtlasData`) and is stateless beyond it; every input is per call.

- **Breaking.** `breakLines` and `breakLineSpans` with a `WrapMode`: `NONE` (only at `\n`), `WORD`, `WORD_CHAR`
  (break an over-wide word across characters) or `CHAR`. Break opportunities are whitespace first, then letter↔punctuation
  and letter↔digit transitions for long runs. Spans are offsets into the original text, for an editor that maps a caret
  across wrapping; a static overload takes an `Advances` function instead of an atlas.
- **Placement.** `place(text, box, style)` into a `TextBox` with `HAlign` (`LEFT`, `CENTER`, `RIGHT`, `JUSTIFY`) and
  `VAlign`; `placeAt(text, x, y, anchor, style)` against one of nine anchors. Both return `PlacedText`: the quads and
  the block's `TextBounds`.
- **Fit.** `measure`, `fits`, `largestSizeThatFits(text, box, style, minPx, maxPx)` and `fitAndPlace`.

`TextStyle` carries pixel size, wrap mode, alignment and line spacing, with `with...` copies. Everything is in screen
pixels, top-left origin, Y down.

### `GlyphQuad`

A placed glyph: its screen rectangle, its UVs already in the Vulkan convention (V = 0 at the top of the image; the
layout flips msdf-atlas-gen's bottom-origin bounds), the `face` id whose atlas those UVs index, and that atlas's
`screenPxRange` at this size. The face is on the quad because under fallback it need not be the face the run asked
for. A renderer binds that face's atlas for the glyph; `Canvas.runs()` splits its batch wherever the face changes, and
`CanvasTechnique.atlases(byFaceId)` takes one texture per face in `faces()` order.

## Drawing

The field stores, per texel, a distance from the glyph's edge, mapped so 0.5 is the edge and the atlas's
`distanceRange` (the plugin's `pxRange`) spans 0 to 1. To draw it crisply at any size the shader needs that range in
screen pixels:

```
screenPxRange = distanceRange × pixelSize / emSize
coverage      = clamp(screenPxRange × (distance − 0.5) + 0.5, 0, 1)
```

The usual way to get `screenPxRange` is from UV derivatives (`fwidth`) in the fragment shader. SupirVast's core IR has
no derivatives, so it is computed on the CPU instead, per draw or per vertex, by `GlyphLayout.screenPxRange(pixelSize)`
and on every `GlyphQuad`. Because a run shares one size, this costs nothing in accuracy for unrotated text. A smaller
`screenPxRange` than the true one is a softer edge, which is how letterpress (`Canvas.textSunken`) gets its shade and
glint from the same glyphs.

### `MsdfShader` and `TextMesh`

`MsdfShader` is a self-contained pipeline for text alone. The vertex stage takes a clip-space position (location 0)
and UV (location 1) and forwards the UV. The fragment stage samples the atlas at set 0, binding 0, takes the median of
R, G and B, and applies the coverage formula above. Colour and `screenPxRange` are a 16-byte fragment push constant,
four floats: `r@0 g@4 b@8 screenPxRange@12`. `TextMesh.toVertices(quads, viewWidth, viewHeight)` makes its vertex
buffer: six vertices per glyph, `[x_ndc, y_ndc, u, v]`, two `R32G32_SFLOAT` attributes at offsets 0 and 8.

Most text does not go through `MsdfShader`. `vexelray-canvas` draws glyphs in its single uber-shader batch, beside
boxes and images, with its own text source (`CoreShadelets.text`) and `screenPxRange` carried per vertex. `MsdfShader`
is what a renderer uses when it wants text and nothing else.

### MSDF or SDF

A family is baked as `msdf` (the default) or `sdf`, and the manifest records which; `Face.type()` says, and
`pixels(face)` returns that many channels.

- **MSDF** holds three distances whose median is the edge. Corners stay sharp at any magnification, which a single
  distance cannot do. Four bytes per texel.
- **SDF** holds one true distance. A quarter of the texture, and no median in the shader. Corners round off when text
  is drawn well above the baked size (32 px by default), so it suits text that stays near that size, or an
  application that would rather have the memory. Its distance is true everywhere in range, where MSDF's median is
  true only near the edge, which matters to effects that read far from the glyph.

A canvas shader holds one text source, so it is composed for one type: `CanvasShader.core(FieldType)` gives
`Fragment` for MSDF or `SdfFragment` for SDF. An application's families should therefore all be one type, and
`fonts.types()` returns a single type for a set one shader can draw. A set whose `types()` holds both cannot be drawn
by one canvas shader.

## The `primary` atlas

Before families, `vexelray-text` baked one atlas, `primary`: Noto Sans over a fixed list of blocks, with Noto Sans Mono
packed into the same image as a second face, read with `AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json")`
and `AtlasPixels.primary()`. `AtlasData.face(i)` returns one face's metrics and glyphs over the shared image (indices
past the end clamp to the primary face). Each extra face's glyph map is backed by the primary's, so a code point the
mono face lacks draws in Noto Sans rather than as the box.

It is still built and committed because the canvas and panel demos and several tests load it. New code should use
`FontSet`: `primary` holds about 2,100 glyphs across its two faces in a 2048 × 2048 texture (16 MB), where the two
families hold every glyph both fonts map, 6,580, in 15.1 MB.

## What it does not do

- No kerning, shaping, ligatures or bidi.
- No faces at run time: no font file is read, nothing is rasterised on demand, and nothing is evicted.
- No synthetic bold or italic. A missing weight resolves to the nearest real face.
- No colour glyphs (emoji): a distance field has one shape and the colour comes from the draw.
