# Trace sites: `@Trace`

Status: **proposed 2026-10-24; steps 1 to 3 done 2026-10-24** (see "As built", below). Written for `vexelray-gui/docs/plans/export.md` (its M2), beside atchung's
[correlation.md](../../atchung/docs/correlation.md), which carries a cause through every message. A cause says what
set a chain of work off; a trace site says which code the chain passed through.

## What it is

A method annotated `@Trace` writes a probe row naming itself whenever it runs, inside whatever cause it runs in:

```java
@Trace(value = "seek", args = {"position"})
void seek(long position) { ... }
```

The row carries the site's id, the cause (from the probe, as every row does) and the values `args` names. A reader
resolves the site id to the method's fully qualified line, such as `dev.example.Player.seek(Player.java:42)`, and
the label, so the line of every traced method is known without walking a stack.

## Made at build time, by a weaver

Annotation processors can only add files, so they cannot make an existing method do something new. A post-compile
step can: `vexelray-trace-maven-plugin` runs after `javac`, reads each compiled class with the JDK's ClassFile API
(`java.lang.classfile`, final in JDK 24), and rewrites every annotated method to begin with a guarded call into
`vexelray-trace`. It is custom and has no dependencies. Nothing reflects and nothing runs at load time, so a native
image compiles the woven classes like any others.

## The site table: a generated class

The weaver also writes each module's table of sites, as a class, not a resource: a generated `TraceSites`
implementation per module holding each site's id, class, method, file, line, label and argument names, listed in
`META-INF/services` for `ServiceLoader`. A class needs no reachability metadata in a native image (which includes
services found on the class path by itself), is checked by the compiler, and is loaded with the code it describes.

## Site ids: as `serialVersionUID`

By default a site's id is a stable hash of its class, method name and descriptor, computed by the weaver, the way
`Serializable` computes a class's `serialVersionUID` when none is declared. Renaming the method or changing its
signature changes the id, which is harmless: the table ships in the same build as the rows that use it. Where an id
should outlive a rename (a site an external tool watches by id), `@Trace(id = …)` pins it, as declaring
`serialVersionUID` does.

Collisions are caught twice: the weaver refuses two sites with one id in a module, and loading the tables refuses two
modules that bring the same id, naming both sites.

## What `args` may name

Checked by the weaver, so a mistake is a build error and not a surprise in a recording:

- **Primitives, enums, IDs and strings**, and nothing else. Recording an object keeps it alive and records state
  that may change after the row is written.
- **Recorded raw, formatted when read.** Nothing is turned into text while the application runs.
- **Strings by their length**, as the mutation lane reports text, unless the annotation says `content = true`.

## Entry, and optionally exit

A site writes one row as it is entered. `timed = true` adds a row as it returns, for its duration and how it nests,
at twice the rows.

## What it costs off

The guard is a `static final` decided at startup, as `Probe.ON` is. On the JVM the JIT folds it, and an untraced run
pays nothing. In a native image, where tracing stays a launch flag, it is a load and a predictable branch per traced
call. Making it free there would mean deciding it at build time, and so a separate debug build; a launch flag is
worth the few cycles.

## Modules

| Module | What |
| --- | --- |
| `vexelray-trace` | `@Trace`, the guarded entry the weaver calls (which writes through atchung's `Probe`), the `TraceSites` service and the merged table readers use |
| `vexelray-trace-maven-plugin` | The weaver: rewrites annotated methods, checks `args`, assigns ids, generates the module's `TraceSites` |

## As built

What the first two steps settled that the sections above left open:

- **Rows go on atchung's `trace` lane** (`Lane.TRACE`), so a recording asks for them with `-Dprobe=trace,...`.
- **A row's kind is `trace.<label>`**, a constant the weaver bakes in, so `csvview --sources --lane trace` reads by
  site name and nothing is built per call. Its detail is the site id in hex and the arguments' raw values.
- **Raw means a `long`**: integers widened, a `float` or `double` as its bits, an enum as its ordinal, a string as
  its length, `-1` for null. The only text made while running is the decimal digits; `Site.Arg.format` reads a value
  back by the type its table records.
- **A recording names its own sites.** When tracing starts, `Tracing` writes a `trace.site` row for every site the
  class path holds (id, label, `Class.method(File:line)`, arguments), in no cause, as `cause.source` does for
  sources. A CSV reads without the table beside it.
- **`content = true`** writes each string argument in full on a `trace.text` row after the entry; **`timed = true`**
  adds a `trace.exit` row at every return and, through a catch-all that rethrows, at a throw (`... threw`).
- **`args` are named by parameter name**, from `MethodParameters` or the local variable table (Maven compiles with
  `-g`), **at most four**. The weaver refuses an object, a name that is no parameter, a fifth argument,
  `content = true` with no string, and two sites with one id, every one in one build error.
- **Re-weaving is harmless**: an incremental build leaves woven classes in place, and a method that already reads
  the guard is not woven again, though its site is still tabled.
- **The table class** is `<package of the first site>.TraceSites_<artifactId>`, whose `table()` returns the sites as
  tab-separated lines (`Site.parse`). `Sites` finds the tables through the thread's context class loader.
- **The plugin targets JDK 25**, for `java.lang.classfile`, so its pom gives `maven-plugin-plugin` ASM 9.9.1: the
  3.15.1 default (9.7.1) cannot read a JDK 25 class. `vexelray-msdf-maven-plugin` stayed on 21 for the same reason.

- **A native image needs nothing added**: a woven test project built with GraalVM CE 25.0.1 (`native-image
  --no-fallback`, no metadata of its own) finds its table through `ServiceLoader`, writes the same rows as on the
  JVM when launched with `-Dprobe=trace`, writes nothing without it, and reads an enum argument back by name.

Not yet: any sites in an application (step 4).

## Steps

1. **`vexelray-trace` and the weaver, on the JVM.** Verified by a test project whose traced method's row carries its
   site, its arguments and its caller's cause, with a pinned id and a hashed one, and whose untraced run writes
   nothing.
2. **The checks.** Verified by builds that must fail: an object in `args`, two sites with one id.
3. **In a native image.** The same test project, built native, reads its sites back through `ServiceLoader`.
4. **The first sites:** `PictureViewer.showTransport` and the other paths the Pix investigation names.
