# VexelRay

A Vulkan graphics engine whose **shaders are generated programmatically at runtime**. A hybrid polygon
rasterizer and SDF ray-marcher with a build-your-own-pipeline configuration API, flexible buffer management,
and pluggable lighting models.

- **Java 25 · Maven · GraalVM native-image · Vulkan (LWJGL) · JOML**
- Depends on the sibling **[SupirVast](../supirvast)** project for the entire shader story: engine-level
  descriptions compose into SupirVast `core` IR, which lowers to validated SPIR-V. VexelRay never emits SPIR-V
  itself — it composes *meaning*, SupirVast owns the *encoding*.

## Architecture

VexelRay **owns its Vulkan runtime** (one instance/device/swapchain shared across passes), unlike SupirVast's
monolithic single-shader hosts. That shared device is what lets a raster pass and a ray-march pass write one
coherent frame.

Multi-module Maven reactor:

```
vexelray                 parent (pom)
├─ vexelray-core         binding-agnostic engine vocabulary — no SupirVast, no Vulkan
│    dev.vexelray.runtime   VexelEngine + RuntimeManager (owns instance/device/swapchain, frame loop)
│    dev.vexelray.pipeline  build-your-own-pipeline API: Attachment, Pass (Raster/Raymarch/Compute/Post),
│                           RenderPipeline + builder, FrameGraph (dependency ordering)
│    dev.vexelray.resource  flexible buffer management: ResourceManager, GpuBuffer, BufferUsage, MemoryDomain
│    dev.vexelray.lighting  pluggable lighting models that participate in shader composition
├─ vexelray-shader       runtime shader generation (the SupirVast seam) — depends on vexelray-core + SupirVast
│    dev.vexelray.shader    ShaderComposer (engine concept -> core IR), ComposedShader (lowers via
│                           CoreToSpirv to a SPIR-V byte[]), ShaderKey (cache)
├─ vexelray-os           OS integration (nested aggregator) — direct Panama bindings, no LWJGL/GLFW
│    ├─ vexelray-os-api     platform-agnostic API: NativePlatform, NativeWindow, WindowConfig, Ffi helper
│    ├─ vexelray-os-windows WindowsPlatform  (user32/kernel32 + VK_KHR_win32_surface)
│    ├─ vexelray-os-linux   LinuxPlatform    (libX11 + VK_KHR_xlib_surface)      — skeleton
│    └─ vexelray-os-macos   MacosPlatform    (AppKit/QuartzCore + VK_EXT_metal_surface) — skeleton
└─ vexelray-vulkan       (planned) the Panama Vulkan runtime + resource implementation; hosts the
                         OS-activated selection profiles that pick the platform module
```

The `ComposedShader` `byte[]` SPIR-V boundary keeps SupirVast isolated to `vexelray-shader`; `vexelray-core`
stays free of both the shader compiler and any Vulkan binding.

### Native integration

VexelRay talks to the OS directly through its own Panama (FFM) bindings — no LWJGL, GLFW, or SDL. Windowing and
the Vulkan surface are hand-rolled per platform, multi-platform from day one, native-image-safe, and selected at
build time by the host OS. The binding pattern is normative and documented in
**[docs/native-bindings.md](docs/native-bindings.md)** — read it before writing any native code.

## Status

**Early — API design.** The pipeline-configuration API + runtime-manager seam are defined and compile against
the real SupirVast types; a hybrid raster+SDF+post pipeline is expressible and validated
(`HybridPipelineTest`). No Vulkan plumbing yet — the `RuntimeManager`/`ResourceManager` implementations are the
next increment.

## Build

```bash
mvn install    # requires SupirVast installed to the local .m2 (mvn install in ../supirvast first)
```
