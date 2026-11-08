# vexelray-msdf-maven-plugin

Bakes fonts into distance-field atlases at build time. Its one goal, `generate-atlas`, runs
[msdf-atlas-gen](https://github.com/Chlumsky/msdf-atlas-gen) over TTF/OTF files and leaves, for each atlas, the image,
its glyph metrics as msdf-atlas-gen JSON, and the same pixels in a file the runtime can load without decoding a PNG.
`vexelray-text` reads those files; nothing at run time opens a font file.

What the runtime does with the output — `FontSet`, layout, fallback, the shader — is in
[docs/text.md](../docs/text.md).

## Why baked

A distance field is a glyph the shader cuts at its edge, one screen pixel soft at any size: one 32 px cell is the
letter at 10 px and at 200 px, and a shadow, a glow or letterpress is a function of the distance rather than another
texture. Generating that field is slow and needs the font's outlines, so it is done once, here, and the result is
committed.

Baking everything up front has a second reason. Every face the build makes is loaded when the application starts,
and nothing is generated, loaded on demand or evicted later. What text costs on the GPU is therefore a fact of the
build, which the plugin prints in the log (and can fail on), not something found in a profiler.

## The two forms

The plugin takes either block, or both in one execution.

- **`<families>`** — the current form. Name a font family and where its files are. Every face the files hold is
  baked into its own atlas, with every glyph that face maps, and `fonts.json` lists them all. Faces are found by
  reading the fonts, never from file names. `FontSet` reads this.
- **`<atlases>`** — the original form. One atlas from one font file and an explicit code-point list, optionally with
  more faces packed into the same image (`<extraFonts>`). `AtlasData.loadFromResource` reads it. `vexelray-text`
  still bakes its `primary` atlas this way, for the demos and tests that load it; new work should use
  `<families>`.

## `<families>`

`vexelray-text`'s own pom bakes its two families like this:

```xml
<plugin>
    <groupId>dev.vexelray</groupId>
    <artifactId>vexelray-msdf-maven-plugin</artifactId>
    <version>${project.version}</version>
    <executions>
        <execution>
            <id>generate-atlases</id>
            <goals><goal>generate-atlas</goal></goals>
            <configuration>
                <outputDir>${project.basedir}/src/main/resources/dev/vexelray/text/atlas</outputDir>
                <families>
                    <family>
                        <name>sans</name>
                        <sources><source>${project.basedir}/fonts</source></sources>
                        <match>Noto Sans</match>
                    </family>
                    <family>
                        <name>mono</name>
                        <sources><source>${project.basedir}/fonts</source></sources>
                        <match>Noto Sans Mono</match>
                    </family>
                </families>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Only `<name>` and `<sources>` are required. Everything else narrows or tunes.

| Element      | Default            | Meaning |
|--------------|--------------------|---------|
| `name`       | required           | The key the application asks for (`FontSet.face("sans")`) and the output subdirectory. Letters, digits, `.`, `_`, `-`. |
| `sources`    | required           | Font files, or directories whose `.ttf`/`.otf` files are read (not recursively). |
| `match`      | none               | The typographic family to take from the sources, matched case-insensitively against the font's `name` ID 16, else ID 1. Needed when the sources hold more than one family; without it the build fails and lists the families it found. |
| `styles`     | every face found   | Faces to bake, as style keys separated by spaces or commas: `regular bold italic bold-italic`. A key that is not in the sources fails the build and lists the ones that are. |
| `charset`    | every mapped glyph | Narrows each face to these code points, in the `<atlases>` charset syntax below. The default is every code point the face maps, less controls and U+FFFD. |
| `type`       | `msdf`             | `msdf` or `sdf`; see [MSDF or SDF](#msdf-or-sdf). |
| `fontSize`   | 32                 | Glyph em size in atlas pixels. |
| `pxRange`    | 4                  | Distance range in atlas pixels: how far from the edge the field stays true. A wider range costs texture and buys effects that reach further (a thick outline, a wide glow). |

Two settings apply to the whole execution:

| Parameter                    | Property                    | Meaning |
|------------------------------|-----------------------------|---------|
| `fontBudgetMegabytes`        | `msdf.fontBudgetMegabytes`  | Fail the build when all families' textures together exceed this. Unset, the total is printed and nothing fails. |
| `failOnMissingGlyphs`        | `msdf.failOnMissingGlyphs`  | Fail when a `charset` asks for code points the font lacks, rather than warn. Off by default, because asking for a whole block and taking what the face carries is usually deliberate. |

### Faces and their names

A face is named by where it sits in its family — stretch, weight, slope, the three axes CSS names a face by — read
from the font itself: `OS/2.usWeightClass`, `usWidthClass` and the italic and oblique bits for a static face, or
the named instance's `wght`, `wdth`, `ital` and `slnt` positions for a variable one. Defaults are left out of the
key, and a face that is all defaults is `regular`:

```
regular   bold   italic   bold-italic   light   semibold-italic   condensed   condensed-light   450
```

A weight without a conventional name keeps its number (`450`), so a variable font's 450 instance never collides with
`regular`. Axes that do not name a face (`opsz`, `GRAD`) are not in the key; two faces that differ only along one of
them fail the build with both sources named, and the fix is to keep one of them in `<sources>`.

A variable font is baked once per named instance, each its own atlas, through msdf-atlas-gen's `-varfont`. A variable
font that names no instances is baked at its defaults.

### Adding a font or a face

- **Another face of a family already configured:** drop its TTF/OTF into the source directory. A `Noto Sans Bold`
  file in `vexelray-text/fonts` becomes `sans/bold` on the next build on Windows, with no pom change. Commit the new
  `bold.png`, `bold.json`, `bold.rgba` and `bold.stamp`, and the updated `fonts.json`.
- **A new family:** add a `<family>` with a new `<name>`. If its files share a directory with another family's, add
  `<match>`.
- **Fewer faces than the files hold:** list the ones wanted in `<styles>`.
- **Removing a face:** take it out of `<styles>` or `<sources>`. The plugin warns about the files it left behind
  (they are no longer in `fonts.json`, so nothing loads them) but does not delete them; that is the author's call,
  since the directory is usually under `src/main/resources`.

Run the build on Windows (see [The binary](#the-binary)); elsewhere the committed files are used as they are.

### What it writes

For each family, under `<outputDir>/<name>/`, one set per face:

```
<style>.png     the atlas, the source of truth and what people look at
<style>.json    msdf-atlas-gen's JSON: atlas header, font metrics, one entry per glyph
<style>.rgba    the pixels again for the runtime, MSDF faces (4 channels)
<style>.r8      the same, SDF faces (1 channel)
<style>.stamp   a hash of what decided this face, so an unchanged face is skipped
```

and once, `<outputDir>/fonts.json`, the manifest the runtime reads:

```json
{
  "version": 1,
  "bytes": 15841664,
  "families": [
    {"name": "sans", "fontFamily": "Noto Sans", "faces": [
      {"style": "regular", "weight": 400, "stretch": 5, "slope": "normal", "source": "NotoSans-Regular.ttf",
       "type": "msdf", "metrics": "sans/regular.json", "pixels": "sans/regular.rgba",
       "width": 1364, "height": 1394, "glyphs": 3092, "bytes": 7605664}
    ]},
    ...
  ]
}
```

Paths in it are relative to the manifest. `bytes` is the face's texture size on the GPU, width × height × channels,
one mip level. Faces are listed in the order a style picker would show them: stretch, then weight, then slope.
`fonts.json` is rewritten only when its content changes, so a committed CRLF checkout is not dirtied by every build.

The build log carries the same facts as a table, one line per face and the total:

```
Font families (every face is loaded at startup):
  sans/regular                 msdf  1364 x 1394    3092 glyphs   7.3 MB
  mono/regular                 msdf  1420 x 1450    3488 glyphs   7.9 MB
  total                                                          15.1 MB
```

A face larger than 4096 px on either side, the `maxImageDimension2D` every Vulkan device must support, gets a
warning; narrow it with `<charset>` or lower `<fontSize>`.

Each face's atlas is sized to fit its glyphs (`-square4`), not to a fixed `atlasSize`.

### The stamp

A face is regenerated when its stamp changes. The stamp is a SHA-256 of the font file's bytes, the generator
arguments and the charset — contents, not paths or times. The outputs are committed, and a fresh clone has a
different directory and every file dated by the checkout; either in the stamp would regenerate every face on every
new machine. A changed size, range, instance or charset regenerates the face, where comparing file times would not.
The stamp is deleted before msdf-atlas-gen runs, so a failed run cannot leave a half-written face that the next build
skips.

## `<atlases>`

```xml
<atlases>
    <atlas>
        <name>primary</name>
        <font>${project.basedir}/fonts/NotoSans-Regular.ttf</font>
        <charset>
[0x20, 0x7E]
[0xA0, 0xFF]
        </charset>
        <extraFonts>
            <extraFont>
                <font>${project.basedir}/fonts/NotoSansMono-Regular.ttf</font>
                <charset>ascii</charset>
            </extraFont>
        </extraFonts>
        <atlasSize>2048</atlasSize>
        <fontSize>32</fontSize>
        <pxRange>4</pxRange>
    </atlas>
</atlases>
```

| Element      | Default | Meaning |
|--------------|---------|---------|
| `name`       | required | Output files are `<outputDir>/<name>.png`, `.json` and `.rgba`. |
| `font`       | required | The primary face's TTF/OTF. |
| `charset`    | `ascii` | `ascii` (U+0020–U+007E), `latin-1` (adds U+00A0–U+00FF), or msdf-atlas-gen charset-file content passed through as written: ranges like `[0x20, 0x7E]`, quoted strings. |
| `extraFonts` | none    | More faces in the same image, each with its own `font` and `charset` (default `ascii`). |
| `atlasSize`  | 1024    | The image's side in pixels; it is square. |
| `fontSize`, `pxRange` | 32, 4 | As for families. |

With `<extraFonts>`, msdf-atlas-gen is given each face after `-and`, and its JSON switches to the multi-font
`variants` shape: one variant per face, the primary first, in declaration order. `AtlasData.face(i)` follows that
order. These atlases are always MSDF.

An `<atlases>` entry is regenerated when the font (or any extra font) is newer than the outputs: file times, not a
stamp.

### Coverage

A charset that asks for code points the font does not have is not an error; the plugin says which, as ranges, on
every build, including builds that skip generation. A warning seen only on the build that regenerated the atlas
months ago is a warning nobody sees. `failOnMissingGlyphs` turns it into a failure, for an atlas something is
specified against. For a family with no `<charset>` there is nothing to check: it holds every glyph the face maps.

## The missing-glyph box

After msdf-atlas-gen runs, the plugin draws one more glyph into every atlas at U+FFFD: a square outline whose field
is computed analytically, not taken from the font. Every face gets the same recognisable box, sized to the face's
`0` advance. A printable code point no face has draws it. It is idempotent (an atlas that already has U+FFFD is left
alone), and an atlas with no free cell for the box grows by a strip on the side the y-origin does not count from, so
no glyph already in it moves. A family's U+FFFD is always this box, never the font's own replacement character.

## The runtime pixel file

The `.rgba` and `.r8` files are the PNG's pixels after the box is baked in, tightly packed, top row first, deflated:

```
4 bytes   magic "VXPX"                      4 bytes   magic "VXPC"
int32     width, big-endian                 int32     width, big-endian
int32     height, big-endian                int32     height, big-endian
rest      zlib stream of                    int32     channels (1 or 4), big-endian
          width · height · 4 bytes, RGBA    rest      zlib stream of width · height · channels bytes
```

Four-channel files keep the original `VXPX` layout; one-channel files use `VXPC`. They exist so the runtime never
decodes an image format. In Java that means ImageIO, and so AWT and its native libraries, which a GraalVM native image
on Windows can only ship as DLLs beside the executable; inflating this needs only `java.util.zip`. The file is
rewritten whenever the PNG is newer, including in prebuilt mode, which needs only the committed PNG.

## MSDF or SDF

`<type>` chooses what each texel holds:

- **`msdf`** (default): three distances whose median is the edge. Corners stay sharp at any magnification. Four bytes
  per texel (RGBA, alpha unused, the layout the GPU uploads).
- **`sdf`**: one true distance. A quarter of the texture and about a quarter of the file. Corners round off once text
  is drawn well above `fontSize`.

SDF is for an application whose text stays near its baked size and would rather have the memory, or one that needs a
true distance everywhere (MSDF's median is only the true distance near the edge). A canvas shader is composed for one
type, so an application's families should all be one type; see [docs/text.md](../docs/text.md#msdf-or-sdf).

## The binary

The plugin carries no msdf-atlas-gen itself. A `vexelray-msdf-tool-<os>` module does: a jar holding the executable and
`META-INF/vexelray/msdf-atlas-gen.properties`, which says where it is:

```properties
resource=/bin/windows-x64/msdf-atlas-gen.exe
file=msdf-atlas-gen.exe
```

The plugin's pom has an OS-activated profile that puts the module for the build host on the plugin's class path.
Only Windows has one today, [`vexelray-msdf-tool-windows`](../vexelray-msdf-tool-windows). The plugin finds the
properties file or it does not; no OS is named in its code. When it finds one, it extracts the executable to
`target/vexelray-msdf/bin` and runs it from there.

That is why the outputs are committed. A build on Linux or macOS has no generator and uses the atlases as they are in
the tree, so those platforms build and run; they just cannot change a font. Adding a generator for another OS is a
new `vexelray-msdf-tool-<os>` module with its binary and properties file, and a profile for it in this plugin's pom.

`msdf.mode` chooses:

| Mode       | Behaviour |
|------------|-----------|
| `auto`     | The default. `primary` where a tool module is on the class path, `prebuilt` where none is. |
| `primary`  | Run msdf-atlas-gen; fail if there is none for this OS. |
| `prebuilt` | Run nothing; check the committed atlases exist, and write the runtime pixel files from their PNGs. |

## Where to look

```
GenerateAtlasMojo  the goal: modes, both forms, the stamp, the budget, coverage reports
FamilyConfig       one <family>: what each element means
FamilyPlan         which faces a family bakes, decided from the fonts alone; every refusal names what it found
FaceStyle          weight, stretch, slope, and the style key built from them
SfntFont           reads name, OS/2, head, cmap and fvar, and nothing else; outlines are msdf-atlas-gen's business
FontManifest       fonts.json and the build-log table
AtlasConfig        one <atlas> and its <extraFonts>
CharsetCoverage    requested code points against the ones the JSON has
NotdefGlyph        the missing-glyph box
AtlasPixels        the .rgba / .r8 file
FieldType          msdf or sdf; dev.vexelray.text.FieldType is the runtime's side of the same list
```
