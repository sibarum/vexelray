# vexelray-shadelet-codegen

The annotation processor for shadelet styles. For each top-level `@Style` record `p.XStyle` it generates
`p.XStyleLayout`:

- `LAYOUT`: the record's `StyleLayout`, one field per component in order (`float` is 1 wide, `Color` 4),
  per instance where the component is `@PerInstance`;
- `shared(XStyle)`: the shared components' floats, in layout order, for the style table;
- `instance(XStyle, float[] out, int at)`: the per-instance components' floats, written at `out[at..]`;
- `use(String shadelet, XStyle)`: the `Look.Use` carrying `shared(s)`.

A component of any other type, a style that is not a record, a nested one, or one in the unnamed package is a
compile error against that type.

## Using it

JDK 23 and later do not discover processors on the classpath, so name it in the compiler plugin's
`annotationProcessorPaths`. The path is complete on its own: give it canvas too, which holds the annotations and the
types the generated code names.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>dev.vexelray</groupId>
                <artifactId>vexelray-shadelet-codegen</artifactId>
                <version>${project.version}</version>
            </path>
            <path>
                <groupId>dev.vexelray</groupId>
                <artifactId>vexelray-canvas</artifactId>
                <version>${project.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

The module itself still needs `vexelray-canvas` as an ordinary dependency, to compile the record and the generated
`Layout` beside it.
