# Sodium 0.9.1 compatibility (Minecraft 26.2)

## Verified facts

Threadium resolves Sodium only when Gradle receives `-Pthreadium.sodium=true`:
`maven.modrinth:AANobbMI:2Yom1N68`, from Modrinth's authoritative Maven endpoint.
This is Sodium `mc26.2-0.9.1-fabric`, file
`sodium-fabric-0.9.1+mc26.2.jar`, Modrinth version `2Yom1N68`. It is a
development `runtimeOnly` dependency: the normal run is vanilla and Sodium is
not packaged in Threadium's JAR.

The resolved JAR's `sodium-common.mixins.json` registers
`net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin` and
`LevelRendererMixin`.

* `LevelExtractorMixin` injects HEAD into `extract`, injects at
  `SectionOcclusionGraph.consumeFrustumUpdate()Z`, and redirects
  `LevelExtractor.applyFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)V`
  inside `extract`; the redirect intentionally does nothing. This is Sodium's
  chunk-graph/terrain frustum path, not proof that entity frustum or distance
  checks are replaced.
* It injects cancellably at HEAD of
  `extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V`
  and delegates to `SodiumWorldRenderer.extractBlockEntities`. It also replaces
  visible block-entity iteration and several section-dirty methods.
* `LevelRendererMixin` overwrites `prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;`,
  overwrites `isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z`, and
  wraps the `prepareChunkRenders` invocation within `LevelRenderer.render(...)`.
  It does not overwrite `LevelRenderer.render` itself.
* No Sodium Mixin in this JAR targets `LevelExtractor.extractVisibleEntities` or
  `ParticleEngine.extract`. Sodium's entity culling Mixin targets
  `EntityRenderer`, so vanilla entity renderer distance/frustum policy remains
  the relevant entity check; the extractor's section-ready check now uses
  Sodium's chunk state.

`chunk graph visibility` and `chunk occlusion culling` are Sodium terrain
systems. They are separate from entity frustum checks, entity distance checks,
and renderer-backed entity render-state extraction. The JAR audit does not show
Sodium replacing Threadium's entity extraction section.

## Threadium result

| Threadium boundary | Sodium result |
| --- | --- |
| `LevelRenderer.render(...)` | Applies; still measures the entire, Sodium-modified world render. |
| `LevelExtractor.extractVisibleEntities(...)` | Applies; semantic boundary remains entity candidate filtering plus state extraction. |
| `ParticleEngine.extract(...)` | Applies; no target overlap found. |
| `LevelExtractor.extractVisibleBlockEntities(...)` | Disabled automatically under Sodium: Sodium cancels this vanilla method at HEAD, so a HEAD/TAIL timer would have an unmatched or zero invocation. |

No Threadium Mixin changes Sodium control flow, has a priority override, or
uses a redirect. Fabric initialization with Sodium completed without a Threadium
Mixin conflict warning; the environment failed later while creating a GPU backend,
before a world could exercise the timers.

## Worker scheduling and remaining work

Sodium performs terrain/graph work and has its own scheduling. Threadium does
not access Sodium internals or submit work in Phase 0. Do not infer any safe
worker boundary for entities from Sodium's terrain scheduling.

Merged [PR #2887](https://github.com/CaffeineMC/sodium/pull/2887),
“Asynchronous Graph Culling and Frame-Independent Task Scheduling”, describes
an asynchronous terrain occlusion graph search, independent render-list creation,
and task scheduling by age/distance/type/frustum status. This concerns chunk
graph work, not entity render-state extraction. Merged
[PR #3764](https://github.com/CaffeineMC/sodium/pull/3764) moves a terrain
occlusion-culling condition to camera render state for compatibility; it changes
three files and likewise does not establish an entity worker boundary.

Runtime validation must confirm each remaining timer fires once per section,
metrics are reported, and no conflict appears after world join/reload.
