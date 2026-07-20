# Minecraft 1.21.1 backport API map and port boundary

Status: initial design baseline
26.2 source baseline: `af7ddd3` (`mc26.2/fabric/dev`)
Target order: `26.2 -> 1.21.1 -> 1.20.1`

## 1. Scope

This document defines the boundary between the existing Minecraft 26.2 implementation and the Minecraft 1.21.1 port.

The first 1.21.1 milestone is deliberately non-accelerating:

- the game starts;
- Threadium observes eligible `ModelPart` rendering;
- Threadium never suppresses Vanilla rendering;
- no geometry is missing or duplicated;
- no crash occurs during world load, resource reload, disconnect, or shutdown.

The following are out of scope for that milestone:

- GPU mesh upload;
- draw replacement;
- batching;
- profitability decisions;
- translucent or ordered rendering;
- 1.20.1 work;
- copying the 26.2 Blaze3D backend.

## 2. Primary conclusion

The port is not a mechanical source backport.

Under official Mojang mappings, the Minecraft 1.21.1 `ModelPart` data model remains close enough to 26.2 that most topology, mesh, pose, cache, planning, and correctness concepts can be retained.

The hard boundary is the rendering execution model:

| Area | Minecraft 26.2 | Minecraft 1.21.1 | Port decision |
| --- | --- | --- | --- |
| Mapping state | Unobfuscated development environment | Obfuscated release requiring mappings | Add official Mojang mappings |
| Java target | Java 25 | Java 21 | Compile the 1.21.1 artifact for Java 21 |
| Entity preparation | `FeatureRenderDispatcher`, submit nodes, prepared groups | Immediate entity/layer rendering through `LivingEntityRenderer` and `MultiBufferSource` | Rewrite interception and grouping |
| Material state | `RenderType` backed by `RenderPipeline` and `PreparedRenderType` | Stateful `RenderType` and `RenderStateShard` | Replace descriptor binding and state capture |
| GPU resources | `GpuBuffer`, device API, `RenderPass` | Stateful OpenGL-era Blaze3D | Write a dedicated 1.21.1 OpenGL backend |
| Flush boundary | Prepared feature execution groups | `MultiBufferSource.BufferSource.endBatch(...)` | Introduce Threadium-owned queues and validated flush hooks |

## 3. Build boundary

The 26.2 build has no `mappings` dependency because the target is unobfuscated. Minecraft 1.21.1 requires a mapping namespace.

Initial 1.21.1 build policy:

```gradle
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
}
```

Use official Mojang mappings unless a concrete incompatibility is discovered. This keeps names such as `PoseStack`, `ModelPart`, `RenderType`, and `MultiBufferSource` aligned with the current codebase.

The 1.21.1 artifact must target Java 21 bytecode. Gradle may run on Azul Zulu 25, but `JavaCompile.options.release` must be `21`.

Exact Loom, Fabric Loader, Fabric API, and Mod Menu versions are selected when the 1.21.1 scaffold is created. They must be verified rather than guessed in this document.

## 4. API correspondence

The names below use official Mojang mappings.

| 26.2 API or behavior | 1.21.1 counterpart | Required action |
| --- | --- | --- |
| `net.minecraft.client.model.Model` | Same class and `renderToBuffer(...)` concept | Reuse concept; do not depend on a generic root accessor |
| `Model.root()` | No generic `Model.root()` contract | Intercept `ModelPart.render(...)` instead of assuming every model exposes one root |
| `net.minecraft.client.model.geom.ModelPart` | Same mapped class | Retain topology and pose concepts |
| `ModelPart.Cube` | Same mapped nested class | Retain cuboid traversal through an accessor |
| `Cube.polygons` | Present in 1.21.1, but nested polygon details are not a stable public boundary | Prefer Vanilla geometry capture instead of private polygon access |
| `Polygon.vertices()` | 1.21.1 stores a `vertices` field | Do not copy the 26.2 record-style access path |
| `Polygon.normal()` | 1.21.1 stores a `normal` field | Avoid direct access where possible |
| `Vertex.worldX/Y/Z()` | 1.21.1 stores a `Vector3f pos` | Geometry adapter required |
| `Vertex.u()/v()` | 1.21.1 stores `u` and `v` fields | Geometry adapter required |
| `ModelPart.x/y/z` | Same mapped fields | Reuse pose composition |
| `ModelPart.xRot/yRot/zRot` | Same mapped fields | Reuse pose composition |
| `ModelPart.xScale/yScale/zScale` | Same mapped fields | Reuse pose composition |
| `ModelPart.visible` | Same mapped field | Reuse visibility propagation |
| `ModelPart.skipDraw` | Same mapped field | Reuse visibility-mask semantics |
| `ModelPart.getInitialPose()` | Same mapped concept | Reuse structural metadata after verification |
| `PoseStack.last().pose()` | Same mapped API | Reuse root-matrix capture |
| `RenderType.sortOnUpload()` | Present in 1.21.1 | Retain fail-closed sorted-policy checks |
| `RenderType.canConsolidateConsecutiveGeometry()` | Present in 1.21.1 | Retain planning policy after validation |
| `RenderType.pipeline()` | No equivalent | Replace pipeline identity with a 1.21.1 `RenderType` descriptor |
| `RenderType.prepare()` | No equivalent | Delete prepared-state reuse logic |
| `PreparedRenderType` | No equivalent | Do not port |
| `RenderPipeline` / `RenderPipelines` | No modern pipeline API | Do not port |
| `GpuBuffer` / `RenderPass` / device API | No equivalent API | Dedicated OpenGL backend |
| `ModelFeatureRenderer.Submit` | No equivalent submit record | Recover material context separately |
| `FeatureRenderDispatcher` preparation groups | No equivalent staged group | Threadium-owned group and frame scopes |
| Explicit sprite and decal data in submit records | Wrapped `VertexConsumer` implementations such as sprite, decal, and multi-consumer wrappers | Recognize proven wrappers; otherwise use Vanilla |
| Group submit count known before preparation | Not generally known before immediate rendering | Replace the profitability mechanism with a historical gate |
| Feature-group execution flush | `MultiBufferSource.BufferSource.endBatch(...)` | Candidate backend flush boundary for supported opaque layers |

## 5. Port classification

### 5.1 Reuse with little or no Minecraft coupling

The following concepts should be copied only after their tests pass in the 1.21.1 project:

- backend state machine;
- generation invalidation;
- bounded resource limits;
- immutable mesh representation;
- bone and instance layout constants;
- visibility masks;
- instance slice planning;
- submission-order planning;
- adjacent batch range construction;
- metrics arithmetic;
- bounded fallback diagnostics;
- the rule that Vanilla suppression occurs at one audited decision point only.

Representative 26.2 files:

```text
BackendStateMachine.java
ModelPartBackendState.java
ModelPartLayouts.java
BoneBaseOffsets.java
VisibilityMask.java
InstanceBatchSlicePlanner.java
InstanceSubmissionOrderPlanner.java
AdjacentBatchRanges.java
ModelPartSuppressionPolicy.java
PipelineValidity.java
PipelineValidityState.java
```

A file being listed here does not authorize blind copying. Imports, Java target, and tests must still be checked.

### 5.2 Reuse behind a 1.21.1 adapter

These algorithms remain valuable, but their Minecraft-facing access must change:

```text
GenericModelPartTopology.java
GenericModelPartMeshBaker.java
GenericModelPartPoseExtractor.java
ModelPartTopologyCache.java
ModelPartMeshCache.java
FrameBonePaletteCache.java
AdaptiveModelPartBatchGate.java
ModelPartRenderService.java
```

Expected changes:

- root acquisition becomes top-level `ModelPart.render(...)` interception;
- cube and child access uses a 1.21.1 accessor;
- mesh extraction uses a 1.21.1 geometry capture adapter;
- material lookup uses a render-context tracker;
- group scopes no longer come from 26.2 feature preparation;
- profitability becomes historical because the final group size is not known in advance.

### 5.3 Rewrite for 1.21.1

The following 26.2 implementation is tied to APIs that do not exist in 1.21.1:

```text
Blaze3dModelPartBackend.java
ModelPartPipelineDescriptor.java
GroupLocalPreparedRenderTypeCache.java
ModelFeatureRendererMixin.java
RenderTypeFeatureRendererMixin.java
FeatureRenderDispatcherMixin.java
```

Also exclude the 26.2 staged-renderer integration from the first ModelPart milestone:

```text
PreparedGroupMetricsMixin.java
StagedVertexBufferMetricsMixin.java
ThreadiumPhasePipeline integration
FeatureRenderPhase integration
SubmitNodeStorage integration
```

The algorithms inside these files may inform the port, but their API calls and Mixin targets are not portable.

### 5.4 New 1.21.1-specific components

Expected new components:

```text
ModelPartRenderMixin
ModelPartRenderDepth
ModelPart1211Access
ModelPartGeometryCapture
RenderContextTracker
RenderType1211Descriptor
OpenGl1211ModelPartBackend
OpenGlStateSnapshot1211
WrappedVertexConsumerClassifier
```

Names are provisional. Responsibilities are not.

## 6. Interception design

### 6.1 Milestone 0

Inject at the head of the five-argument method:

```text
ModelPart.render(PoseStack, VertexConsumer, int light, int overlay, int color)
```

Milestone 0 rules:

- the injection is observational only;
- it never cancels the method;
- it increments bounded diagnostics or frame counters;
- it does not log every part;
- it does not inspect or mutate OpenGL state;
- it does not allocate per rendered part;
- any failure disables only Threadium observation and leaves Vanilla untouched.

The four-argument overload must be checked in bytecode. If it delegates to the five-argument overload, hook only the latter to avoid duplicate observation.

### 6.2 Later replacement

When cancellation is introduced, only a top-level `ModelPart.render(...)` invocation may be replaced.

Use a render-thread-owned depth guard:

- depth `0` before entry means top-level;
- recursive child renders are never independently replaced;
- depth is reset at frame start as a recovery guard;
- cancellation is allowed only after a complete backend queue commit;
- any unknown state returns to Vanilla before cancellation.

Models that render multiple independent top-level parts remain valid: each top-level invocation is treated as a separate candidate.

## 7. Geometry extraction design

Do not begin the port by targeting private `ModelPart.Polygon` or `ModelPart.Vertex` internals.

Preferred 1.21.1 path:

1. Access `ModelPart.cubes` and `ModelPart.children`.
2. Traverse the hierarchy in stable child order.
3. Invoke Vanilla's `ModelPart.Cube.compile(...)` with an identity `PoseStack.Pose`.
4. Supply a capture-only `VertexConsumer`.
5. Record position, normal, UV, and bone index.
6. Construct indices from the emitted quad stream.
7. Derive the exact structural key from captured immutable geometry and hierarchy metadata.

Reasons:

- Vanilla expands mirroring, omitted faces, UV layout, and normals;
- private nested geometry classes are avoided;
- emitted vertex order becomes the source of truth;
- the adapter is easier to differential-test against Vanilla;
- the structural fingerprint remains collision-safe by retaining exact data equality.

The capture path is CPU-only and runs only while constructing a previously unseen mesh.

## 8. Pose extraction design

The current composition rule remains the baseline:

```text
parent
-> translate(x / 16, y / 16, z / 16)
-> rotate Z, Y, X
-> scale(xScale, yScale, zScale)
```

The 1.21.1 port must verify this against `ModelPart.translateAndRotate(...)` before enabling replacement.

Visibility remains:

```text
treeVisible = parentVisible && part.visible
drawVisible = treeVisible && !part.skipDraw
```

Pose extraction remains render-thread-owned and non-thread-safe. No mutable `ModelPart` object may leave the render thread.

## 9. Material and consumer context

`ModelPart.render(...)` receives a `VertexConsumer`, not the originating `RenderType`.

Milestone 0 does not attempt material recovery.

Later milestones introduce a frame-local identity map populated when a `MultiBufferSource` returns a consumer for a `RenderType`.

Required policy:

- a directly recognized consumer may resolve to its `RenderType`;
- supported wrappers are unwrapped only when their semantics are implemented;
- sprite-coordinate wrappers require a captured UV transform;
- sheeted-decal wrappers require a captured decal transform;
- multi-consumers, outline consumers, unknown wrappers, and modded consumers use Vanilla until proven safe;
- consumer identity state is cleared every frame and on resource generation changes.

The classifier must fail closed. Class-name heuristics alone are not sufficient for suppression.

## 10. 1.21.1 backend boundary

The 26.2 Blaze3D device backend is not a template for 1.21.1 API calls.

The dedicated backend must:

- issue all OpenGL calls on the Render Thread;
- require an explicitly validated capability set;
- own its VAO, vertex buffers, index buffers, instance buffers, and shader;
- bound every allocation and queue;
- preserve or reconstruct all relevant OpenGL state;
- disable itself for the current generation after any backend failure;
- destroy resources on reload, disconnect, and shutdown;
- never call 26.2 `GpuBuffer`, `RenderPass`, `RenderPipeline`, or `PreparedRenderType` APIs.

Initial supported material subset:

```text
entitySolid
entityCutout
entityCutoutNoCull
```

Initial exclusions:

```text
translucent
sorted-on-upload
glint
eyes/emissive
outline
crumbling/decal
energy swirl
breeze wind
armor layers
unknown or modded RenderType
```

Support expands one descriptor at a time, each with differential validation.

## 11. Batching and flush boundary

Minecraft 1.21.1 does not expose the 26.2 prepared group size before each model is rendered. Therefore `minimumGroupSubmits=16` cannot be applied with the same mechanism.

Use a historical profitability gate:

1. frame N renders a previously unknown key through Vanilla and records its count;
2. frame N+1 may enable replacement only if the prior observation met the threshold and remained stable;
3. a generation change clears history;
4. a wrong prediction may reduce performance but must never affect correctness;
5. unsupported ordered or translucent material always remains Vanilla.

Candidate key inputs:

```text
renderer/model class
top-level ModelPart identity or structural key
RenderType descriptor
feature/layer identity when available
```

For the initial opaque subset, queue by `RenderType` and flush at the corresponding `MultiBufferSource.BufferSource.endBatch(RenderType)` boundary.

Do not enable batching until all relevant `endBatch`, `endLastBatch`, and fixed-buffer paths have been verified. Ordered and translucent layers remain excluded because a layer-level flush does not preserve their finer submission order.

## 12. Correctness invariants

The 1.21.1 port inherits these non-negotiable rules:

1. Vanilla is the default path.
2. Unknown material, consumer, model topology, capability, or lifecycle state means Vanilla.
3. No Vanilla cancellation occurs before successful queue commitment.
4. The suppression decision exists in one audited location.
5. GPU work stays on the Render Thread.
6. Cached geometry is immutable.
7. Mutable pose state is captured on the Render Thread.
8. All queues, buffers, meshes, bones, and diagnostics are bounded.
9. Resource generations invalidate GPU resources, descriptors, consumer mappings, topology state, and profitability history.
10. Backend failure disables replacement and cannot produce partial suppression.
11. No ordered or translucent path is enabled without an explicit ordering proof.
12. Compatibility with Iris, Sodium, ImmediatelyFast, Nvidium, Lithium, and Async is validated rather than assumed.

## 13. Milestones

### M0 — Compatibility shell

Deliverables:

- standalone 1.21.1 Fabric build;
- official Mojang mappings;
- Java 21 bytecode target;
- client entrypoint;
- observational `ModelPart.render(...)` Mixin;
- bounded detection metrics;
- reload, disconnect, and shutdown hooks;
- zero Vanilla suppressions.

Acceptance:

```text
game starts
world loads
cow renders static and animated
armor and feature layers remain visible
resource reload succeeds
disconnect and reconnect succeed
detected ModelPart invocations > 0
Vanilla suppressions = 0
backend allocations = 0
```

### M1 — Topology and mesh capture

Deliverables:

- 1.21.1 `ModelPart` access adapter;
- hierarchy inspection;
- Vanilla cube compile capture;
- immutable mesh;
- collision-safe key;
- unit tests and capture limits.

No rendering is replaced.

### M2 — Pose capture

Deliverables:

- pose matrices;
- normal matrices;
- visibility masks;
- exact-pose cache;
- comparison against Vanilla transforms.

No rendering is replaced.

### M3 — Minimal OpenGL replacement

Deliverables:

- one proven opaque `RenderType`;
- one-instance draws;
- strict state restoration;
- generation invalidation;
- backend failure recovery;
- differential image validation.

No batching yet.

### M4 — Opaque batching

Deliverables:

- frame-local queues;
- validated `endBatch` flush integration;
- stable draw planning;
- historical profitability gate;
- static and animated benchmarks.

### M5 — Compatibility and descriptor expansion

Deliverables:

- Iris active-shader fallback;
- ImmediatelyFast coexistence validation;
- Sodium/Nvidium/Async/Lithium validation;
- descriptor-by-descriptor expansion;
- fallback diagnostics for every exclusion.

## 14. First code change after this document

The next code patch must implement M0 only.

It must not contain:

- mesh extraction;
- GPU buffers;
- shaders;
- OpenGL calls;
- Vanilla cancellation;
- batching;
- profitability logic.

The M0 patch is complete only when it can prove that Threadium sees 1.21.1 `ModelPart` rendering while producing exactly the Vanilla render path.
