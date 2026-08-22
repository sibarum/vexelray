# A math face in the primary atlas

**Status:** requested, not built. **Consumer:** `vexelray-gui-typeset` (see `vexelray-gui/docs/typeset.md`, P5).
**Where the work lands:** `vexelray-text/pom.xml`, the `generate-atlases` execution.

## What is being asked for

One additional `<extraFont>` in the `primary` atlas, carrying a math face — STIX Two Math or equivalent.

This is **not** multi-texture work. `AtlasData.face(int)` already returns "that face's metrics and glyphs over the
same image", and the primary atlas already carries a second face (NotoSansMono) exactly this way. A math face is
the same mechanism used a second time: one more entry, one more charset, the same 2048 image, no engine change
and no renderer change.

```xml
<extraFonts>
    <extraFont>
        <font>${project.basedir}/fonts/NotoSansMono-Regular.ttf</font>
        <charset>latin-1</charset>
    </extraFont>
    <!-- Face 2: math. Italic variables plus the operators, delimiters and relations that carry
         notation. See "Charset budget" below before widening any range. -->
    <extraFont>
        <font>${project.basedir}/fonts/STIXTwoMath-Regular.ttf</font>
        <charset>...</charset>
    </extraFont>
</extraFonts>
```

## Why the primary face is not enough

The primary Noto Sans face already covers most of what math notation needs, and covers it well:

| Range | Contents | Status |
|---|---|---|
| `0x370–0x3FF` | Greek | already present |
| `0x2100–0x214F` | Letterlike, including U+210E ℎ | already present |
| `0x2190–0x21FF` | Arrows | already present |
| `0x2200–0x22FF` | Math operators, including U+221A √ | already present |
| `0x25A0–0x25FF` | Geometric shapes | already present |
| **`0x1D400–0x1D7FF`** | **Mathematical Alphanumeric Symbols** | **absent** |

The single real gap is the italic-math alphabet. A variable set in the upright UI face reads as a word, not a
variable — that distinction is semantic in notation, not decorative.

## Charset budget

The primary atlas is roughly 1300 glyphs of Noto Sans in a 2048 image at 32px, plus the Latin-1 mono face. The
full `0x1D400–0x1D7FF` block is about 1000 more glyphs and should **not** be taken whole.

Take italic and bold-italic; skip fraktur, script, double-struck, sans-serif and monospace variants until
something asks for them:

```
[0x1D434, 0x1D467]    italic A–Z, a–z
[0x1D468, 0x1D49B]    bold italic A–Z, a–z          (optional, second priority)
[0x1D6E2, 0x1D71B]    italic Greek                   (optional, third priority)
```

That is ~52 glyphs for the first range — negligible against the existing budget — and the atlas image size should
be verified after adding, not assumed.

Growable delimiters are handled by **scaling the ordinary glyph** to the content height, not by size variants, so
the size-variant and glyph-assembly ranges are not needed.

## Verification

- `AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json").faceCount() == 3`.
- `face(2).glyph(0x1D44E)` (italic *a*) resolves with a non-null `planeBounds`.
- The atlas image still fits 2048 at 32px with the existing `pxRange` of 4 — check the generated PNG, do not
  assume.

## Until then

The consumer degrades rather than failing: it probes `face.glyph(0x1D44E)` once, and where the block is absent it
passes variable letters through upright. Every other part of math typesetting — Greek, operators, relations,
radicals, growable delimiters — already renders correctly on today's atlas. So this face is a quality upgrade on
a working feature, not a prerequisite for one, and it should be scheduled on that basis.
