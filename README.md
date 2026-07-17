# Threadium

Threadium is an experimental Fabric client optimization mod for Minecraft Java
Edition 26.2. It accelerates eligible `ModelPart` entity rendering by replacing
repeated CPU-side vertex emission with cached meshes, per-instance pose data, and
GPU instancing.

Threadium does **not** move arbitrary Minecraft rendering or OpenGL calls onto
worker threads. GPU work remains on the Render Thread. Unsupported or unsafe
render paths are rejected before suppression and continue through vanilla
rendering.

## Status

The current release line is **beta**. Threadium has completed differential
correctness coverage for 20 canonical entity pipelines and runtime validation
with Sodium `0.9.1+mc26.2`, but broad modpack compatibility is not yet proven.

## Requirements

- Minecraft Java Edition `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API `0.154.2+26.2` or newer
- Java 25
- A graphics environment supported by Minecraft's Blaze3D OpenGL renderer

## What Threadium optimizes

- Standard `ModelPart` entity meshes
- Repeated instances sharing collision-verified structural topology
- Static pose palettes through exact frame-local deduplication
- Highly animated populations through adaptive direct pose packing
- Adjacent compatible draws through stable GPU batch consolidation
- Mesh, instance, bone, light, overlay, tint, visibility, and transform data
- Minecraft Blaze3D `RenderPipeline` submission with generation-aware validation

The implementation preserves invocation order and does not globally reorder
entities to create larger batches.

## Safety and fallback

Threadium accepts a draw only after topology validation, mesh availability, pose
extraction, capacity checks, and successful bounded queue insertion. Unsupported
materials, custom consumers, outlines, translucent or sorted paths, stale
resources, backend failures, and overlapping render replacements use vanilla
rendering.

The renderer can be disabled immediately in:

```properties
# config/threadium.properties
entity.gpu.enabled=false
```

The default for this beta is `true`.

## Compatibility

Confirmed:

- Vanilla Minecraft 26.2 entity rendering
- Sodium `0.9.1+mc26.2`
- Blaze3D pipeline compilation, resource reload, and reconnect lifecycle
- Automatic fallback for rejected pipelines

Threadium is designed to avoid replacing unsupported or overlapping paths, but
compatibility with every entity-adding or rendering mod is not guaranteed.
Iris, custom shaders, custom vertex modification, and renderers that replace the
same `ModelPart` path may fall back or require additional validation.

## Indicative benchmark results

Dedicated cow scenes were measured on an Intel Core i5-12400 and GeForce RTX
4060 Ti. Each row below is a single 10-second exploratory trial, so the values
are evidence of direction and scaling rather than universal performance claims.

| Scene | Entities | Vanilla FPS | Threadium FPS | Difference |
| --- | ---: | ---: | ---: | ---: |
| Static | 16 | 1744.18 | 2005.29 | +14.97% |
| Static | 256 | 771.41 | 1311.32 | +69.99% |
| Static | 1024 | 147.80 | 348.64 | +135.89% |
| Animated | 16 | 1684.07 | 1986.39 | +17.95% |
| Animated | 256 | 709.71 | 1074.81 | +51.44% |
| Animated | 1024 | 118.16 | 211.55 | +79.05% |

Across the completed 16–1024 entity sweep, Threadium exceeded vanilla average
FPS at every measured point in both static and animated scenes. The exact
break-even point below 16 entities was intentionally left unresolved because
the practical value was low relative to measurement noise and test cost.

## Configuration

Threadium creates `config/threadium.properties`. Important options include:

```properties
enabled=true
entity.gpu.enabled=true
entity.gpu.backend=auto
entity.gpu.allowVanillaFallback=true
entity.gpu.batchConsolidation=true
metrics.enabled=true
```

Use `entity.gpu.backend=auto` for the release backend or `disabled` to prevent
GPU backend initialization. The legacy values `opengl45` and `opengl33` remain
accepted for configuration compatibility, but this beta routes enabled
production rendering through the same Blaze3D backend.

## Building

```fish
set -lx JAVA_HOME /usr/lib/jvm/zulu-25
./gradlew --no-daemon clean spotlessCheck test build
```

Release jars are written to `build/libs/`.

## License

Threadium is available under the MIT License.
