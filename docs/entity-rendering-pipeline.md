# Minecraft 26.2 entity rendering pipeline

## Verification basis

Verified against the resolved unobfuscated 26.2 client JAR with `javap -p -s -c` and Loom's Vineflower source-generation task. Names below are official 26.2 names, not historical Yarn names.

## Verified call path

`GameRenderer.renderLevel(Lnet/minecraft/client/DeltaTracker;)V` invokes `LevelRenderer.render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V`.

Before render submission, `GameRenderer.extract(Lnet/minecraft/client/DeltaTracker;Z)V` drives `LevelExtractor.extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V`. `LevelExtractor.extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V` iterates `ClientLevel.entitiesForRendering()`, calls `isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z`, then appends extracted states to `LevelRenderState.entityRenderStates`.

Visibility delegates to `EntityRenderDispatcher.shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z`; it selects a renderer and invokes `EntityRenderer.shouldRender`. The extractor additionally checks `LevelRenderer.isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z`. The selected renderer owns distance/frustum policy; the extractor owns section visibility.

`EntityRenderDispatcher.extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;` selects a renderer with `getRenderer(Entity)` and invokes `EntityRenderer.createRenderState(Entity,float)`. Packed light uses `EntityRenderDispatcher.getPackedLightCoords(Entity,float)I`, which invokes `EntityRenderer.getPackedLightCoords(Entity,float)I`.

Private `LevelRenderer.submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V` iterates those states and calls `EntityRenderDispatcher.submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V`, which invokes `EntityRenderer.submit(EntityRenderState,PoseStack,SubmitNodeCollector,CameraRenderState)`.

Block entities are extracted by private `LevelExtractor.extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V` using `BlockEntityRenderDispatcher.tryExtractRenderState(...)`, stored in `LevelRenderState.blockEntityRenderStates`, and submitted by private `LevelRenderer.submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V`. Particles use `ParticleEngine.extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V`.

## Ownership, Mixins, and safety

`LevelRenderState` owns per-frame mutable lists. Dispatchers own shared renderer maps; renderers and models are shared and rebuilt on resource reload. `EntityRenderState` is created on the render path and consumed later that frame; it is not proven immutable or thread-safe.

Threadium uses timing-only HEAD/TAIL Mixins on `LevelExtractor.extractVisibleEntities`, `LevelExtractor.extractVisibleBlockEntities`, `ParticleEngine.extract`, and `LevelRenderer.render`, each with its exact descriptor and `require = 1`. These whole-method boundaries survive interior edits; if another mod changes a target incompatibly, the Mixin fails rather than silently moving.

Phase 0 counters use exact method boundaries only: `entityVisibilityChecks` is the number of `LevelExtractor.isEntityVisible(...)` calls; `entityVisibilityRejected` is the subset returning false; `entityRenderStatesExtracted` is the number of successful returns from private `LevelExtractor.extractEntity(...)`; and `entityStatesSubmitted` counts the sole `EntityRenderDispatcher.submit(...)` invocation in private `LevelRenderer.submitEntities(...)`. The first two are visibility-predicate calls, not a claim about all later camera/local-player rejections. No loop-local candidate counter is installed.

Each timing bucket reports count, total nanoseconds, average nanoseconds when count is non-zero, and maximum nanoseconds. Reset uses independent atomic `getAndSet` operations. All current render timings and interval reporting run on the client/render thread, so normal operation is coherent. If another mod invokes a measured method concurrently with reporting, a racing sample can have count/total/max split between adjacent intervals; individual atomic values are retained, but the snapshot is intentionally relaxed rather than adding a hot-path lock.

Unsafe asynchronous boundaries: `ClientLevel`, entities, frustum, renderers/models, `LevelRenderState`, `PoseStack`, `SubmitNodeCollector`, and resource objects. Threadium retains none and changes no rendering control flow.

## Hypothesis and Phase 1 gate

The extraction-to-submit split is verified, but extraction calls shared renderer code. Phase 1 is justified only if profiling shows material extraction cost and a copied immutable boundary is proven for a narrow entity class.
