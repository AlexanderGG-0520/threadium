# Changelog

## 0.1.0-beta.1

Initial public beta for Minecraft Java Edition 26.2.

### Added

- Generic GPU renderer for eligible standard `ModelPart` entity models.
- Blaze3D `RenderPipeline` backend with generation-aware pipeline validation.
- Collision-verified structural mesh caching.
- Stable adjacent batch consolidation without invocation reordering.
- Per-instance transforms, pose palettes, visibility, light, overlay, and tint.
- Exact static-pose deduplication and adaptive animated-pose bypass.
- Bounded capacity controls, generation-aware invalidation, and vanilla fallback.
- Benchmark, differential-rendering, and pipeline-coverage infrastructure.

### Validated

- Differential correctness across 20 canonical entity render pipelines.
- Cardinal-direction crumbling/decal UV projection.
- Runtime compatibility with Sodium `0.9.1+mc26.2`.
- Static and animated exploratory sweeps from 16 through 1024 entities.
- Clean `spotlessCheck`, unit tests, and release build with Java 25.

### Known limitations

- Minecraft 26.2 and Fabric only.
- Broad third-party modpack compatibility is not yet proven.
- Unsupported, sorted, translucent, outline, custom-consumer, and custom-shader
  paths remain on vanilla rendering.
- Iris and renderers replacing the same entity path require additional
  compatibility validation.
