# Minecraft 26.2 staged vertex pipeline investigation

## Scope and versions

This document describes the exact unobfuscated 26.2 classes resolved by this repository, not an older 1.21 or 26.x layout. The build uses Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.154.2+26.2, non-remapping Fabric Loom 1.17.14, Gradle 9.6.1, and Java 25. No mappings artifact is declared. The optional runtime renderer is Sodium 0.9.1 (`sodium-fabric-0.9.1+mc26.2.jar`, Modrinth version `2Yom1N68`).

ImmediatelyFast and Iris are not present in the Gradle dependency graph or development run classpath.

## Verified call graph

All calls below execute on the render thread.

```text
FeatureRenderDispatcher.prepareFrame(SubmitNodeStorage): PreparedFrame
  -> prepareFrameWithContext(FeatureFrameContext, SubmitNodeStorage): PreparedFrame
     -> PreparedFrame.begin(...)
     -> SubmitNodeStorage.drainPhases(Consumer)                 [offset 31]
     -> FeatureRenderer.beginPrepare(context)                  [offset 82]
     -> PreparedGroup.prepare(context, renderers, submits)     [offset 207]
        -> FeatureRenderer.prepareGroup(context, slice, strict)[PreparedGroup offset 22]
        -> renderer-specific appendDraw/getVertexBuilder and CPU vertex production
     -> FeatureRenderer.finishPrepare(context)                 [offset 271]
     -> StagedVertexBuffer.upload()                             [offset 293]
        -> finishLastVertexBuilder()                            [offset 31]
           -> BufferBuilder.build()
           -> Draw.append(MeshData)
        -> compute per-draw vertex/index offsets
        -> RenderSystem.getSequentialBuffer(...).getBuffer(...) for unsorted draws
        -> RenderSystem.getDevice()
        -> GPU buffer-pool acquire
        -> uploadDrawsToBuffers(device, draws, buffers, sizes)
           -> GpuDevice.createCommandEncoder()                  [offset 1]
           -> acquire and map staging GpuBuffer                 [offsets 13-39]
           -> ByteBuffer.put(sourceSlice)                       [offset 141]
           -> decodeSortingPoints(draw)                         [offset 209]
              -> MeshData.decodeQuadCentroids(...)              [offset 76]
              -> Sodium may wrap this centroid call
           -> SortState.writeSortedIndexBuffer(buffer, sorting) [offset 247]
           -> Draw.freeVertexData()                             [offset 252]
           -> MappedView.close()                                [offset 265]
           -> CommandEncoder.copyToBuffer(vertex staging, GPU)  [offset 317]
           -> optional copyToBuffer(index staging, GPU)         [offset 347]
```

Exact descriptors:

- `FeatureRenderDispatcher.prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;`
- `FeatureRenderDispatcher.prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;`
- `PreparedGroup.prepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/feature/FeatureRendererMap;Ljava/util/List;)V`
- `StagedVertexBuffer.upload()V`
- `StagedVertexBuffer.uploadDrawsToBuffers(Lcom/mojang/blaze3d/systems/GpuDevice;Ljava/util/List;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;II)V`
- `StagedVertexBuffer.decodeSortingPoints(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lcom/mojang/blaze3d/vertex/CompactVectorArray;`
- `MeshData.decodeQuadCentroids(Ljava/nio/ByteBuffer;ILcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/CompactVectorArray;I)V`
- `MeshData$SortState.writeSortedIndexBuffer(Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/vertex/VertexSorting;)V`
- `CommandEncoder.copyToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V`

## CPU versus GPU work

Renderer-specific `prepareGroup` calls produce vertices through a shared native `ByteBufferBuilder`. `finishLastVertexBuilder` turns the active builder range into a `MeshData` and appends its `ByteBufferBuilder.Result` slice to a draw. Offset calculation is CPU work, but the same loop also requests `RenderSystem` sequential GPU buffers and is not instrumented as a fictitious pure finalization stage.

`decodeSortingPoints` is CPU-only traversal of completed vertex slices plus `CompactVectorArray` allocation. Vanilla calculates quad centroids. `writeSortedIndexBuffer` performs CPU ordering and index encoding into the mapped staging buffer; ordering and index generation are combined in one method and are reported together. `ByteBuffer.put` copies CPU vertex slices into mapped staging memory.

`upload`, taken as a whole, is not CPU-only. It calls `RenderSystem`, obtains the `GpuDevice`, acquires/maps GPU buffers, creates a command encoder, and records GPU buffer copies. `gpuUploadCall` measures the Java/driver-facing `copyToBuffer` call duration, not GPU completion time.

## Ownership and mutability

`StagedVertexBuffer` owns one native `ByteBufferBuilder`, its `draws` list, the current `BufferBuilder`, and GPU pools. Each `Draw` owns a mutable list of `ByteBufferBuilder.Result` slices and mutable counts/offsets. Renderers receive `VertexConsumer` instances backed by the shared builder.

A draw is not immutable merely because one renderer group has returned: later groups can request the same draw again and append another slice. Consequently group completion is not a safe hand-off point. `finishLastVertexBuilder` at upload offset 31 is the verified last vertex write. From that point until `Draw.freeVertexData`, the result slices are read during vertex copying and centroid decoding. `freeVertexData` closes/releases the slices, so worker reads would race lifetime management unless ownership were redesigned.

The destination used by sorted-index generation is a mapped `GpuBufferSlice`; it is a GPU resource and cannot be handed to Threadium workers. A safe worker implementation would need a detached CPU index buffer and a later render-thread copy into mapped memory. That introduces at least `indexCount * indexType.bytes` of additional CPU storage/copying. Moving centroid decoding would also require exclusive lifetime ownership of every source `ByteBufferBuilder.Result`, or copying all vertex bytes (`vertexBufferSize`)—potentially the entire staged vertex payload. No ownership transfer API exists in the inspected implementation.

The staging, vertex, and index GPU buffers are pooled across frames. `endDraw` clears draws/current buffers; `endFrame` uses `RenderSystem.getDevice()` and advances all GPU pools. `ByteBufferBuilder.Result` references escape into `Draw.vertexBufferSlices` only until upload frees them. Prepared groups share the same `StagedVertexBuffer`; draws are not isolated per group.

## Instrumentation

Critical Mixins use full descriptors, explicit ordinals where applicable, and `require`/`expect` counts. Instrumentation does not alter values or control flow.

- Feature preparation: first `FeatureRendererMap.values()` through immediately before `StagedVertexBuffer.upload`.
- Prepared-group preparation: `PreparedGroup.prepare` head/return.
- Upload total: `StagedVertexBuffer.upload` head/all returns.
- CPU-data-ready point: immediately after the sole `finishLastVertexBuilder` call in `upload`.
- Sorting-point decode: `decodeSortingPoints` head/return.
- CPU vertex copy: redirect-and-call the sole `ByteBuffer.put(ByteBuffer)` at offset 141.
- Quad ordering/index generation: redirect-and-call the sole `writeSortedIndexBuffer` at offset 247.
- GPU copy calls: redirect-and-call both possible `CommandEncoder.copyToBuffer` sites.

There is no separate vanilla method for “sorting point calculation” below `decodeSortingPoints`, nor separate ordering and index-generation calls. Splitting those would require more invasive instrumentation inside Mojang methods, so the report uses the real combined boundaries.

Timing samples contain count, total, interval average, minimum, and maximum. No sample arrays are retained. Volume counters include prepared frames/groups/draws, sorted and unsorted draws, topology-valid quads, sorted quads, generated indices, staged bytes, uploaded bytes, and per-frame maxima. Quad counts are emitted only for `PrimitiveTopology.QUADS` with a vertex count divisible by four.

Sorted-quad buckets are `0`, `1`, `2-7`, `8-31`, `32-127`, `128-511`, `512-2047`, `2048-8191`, `8192+`. Group buckets are `0`, `1`, `2`, `3-4`, `5-8`, `9-16`, `17+`.

`postProcessingReadyTime` is captured after the upload-time final builder flush, when no later renderer can append vertices. `postProcessingRequiredTime` is the entry to `decodeSortingPoints` for a sorted draw. `availableOverlapNanos` is the positive difference. It represents the maximum structural window before that draw needs centroid data. It does not prove ownership safety, because the render thread concurrently copies the same result slices and later frees them. Non-positive cases are counted separately.

Metrics are controlled by `metrics.enabled` in `threadium.properties`. When disabled, timer entry returns zero without calling `System.nanoTime`; volume inspection and ThreadLocal lookup are skipped. Reports are emitted at `metrics.output.interval.seconds`. The independent `phase.pipeline.enabled` option can disable the translucent phase experiment during staged-buffer benchmarks; staged and phase metrics are separate report sections.

## Sodium and renderer compatibility

Sodium 0.9.1 applies `net.caffeinemc.mods.sodium.mixin.features.render.immediate.buffer_builder.sorting.StagedVertexBufferMixin`. It uses MixinExtras `@WrapOperation` around `MeshData.decodeQuadCentroids` inside `decodeSortingPoints`. With the closest-point entity-sort option and `VertexSorting.DISTANCE_TO_ORIGIN`, Sodium traverses quad positions and writes closest points instead of vanilla centroids. Threadium surrounds the enclosing method and therefore measures either implementation without competing at Sodium's invocation site.

Sodium's FRAPI `FeatureRenderDispatcherMixin` only registers extended block-model and item feature renderers in the dispatcher constructor. Those renderers remain covered by the generic group and staged-buffer boundaries. Threadium does not reference Sodium classes or require Sodium.

Fabric Renderer API and custom feature renderers may change the amount and topology of emitted geometry, but unknown renderers continue unchanged. Counts are taken from final `Draw` state. ImmediatelyFast or Iris could alter buffer production or shader execution; neither is installed here. A mod that overwrites `upload`, `uploadDrawsToBuffers`, or `PreparedGroup.prepare` could invalidate critical hooks and will fail loudly rather than silently producing misleading metrics. Batching before this boundary remains measurable; bypassing `StagedVertexBuffer` does not.

## GPU-host benchmark procedure

Use fish-compatible commands from the repository root:

```fish
set -lx JAVA_HOME /usr/lib/jvm/zulu-25
set -lx GRADLE_USER_HOME /tmp/threadium-gradle
./gradlew --no-daemon clean test build
./gradlew --no-daemon runClient --info
```

Set `metrics.enabled=true`, `metrics.output.interval.seconds=30`, and preferably `phase.pipeline.enabled=false` in `config/threadium.properties`. In each renderer configuration, warm up the same world, then collect matching 30- or 60-second MangoHud and Threadium intervals in: a normal high-FPS scene, an entity-heavy scene, and a deliberately translucent-heavy scene. Repeat with:

```fish
./gradlew --no-daemon -Pthreadium.sodium=true runClient --info
```

Correlate `decodeSortingPoints`, `quadOrderingIndexGeneration`, and `cpuBufferCopy` totals with frame count. Check sorted-quad/group distributions and overlap before averages. Preserve logs from both runs and inspect them with:

```fish
rg -i 'stagedVertex|mixin.*(error|fail)|threadium.*exception' run/logs/latest.log
```

## Decision criteria

Proceed only if normal-scene CPU post-processing reaches roughly 30 microseconds per frame, at least 3% of CPU-bound frame time, or p95-equivalent observations above 100 microseconds; multiple sorted draws must provide repeatable overlap, and a design must avoid copying the full vertex payload. Reject if work is normally below 5 microseconds, appears only in pathological scenes, requires full vertex copying, or Sodium makes it negligible.

Current classification: **INSUFFICIENT_RUNTIME_DATA**. Static inspection proves a measurable CPU candidate exists, but also proves that sorted-index output is written directly into mapped GPU memory and source slices remain under render-thread lifetime management. GPU-host interval data is required before choosing an implementation boundary.
