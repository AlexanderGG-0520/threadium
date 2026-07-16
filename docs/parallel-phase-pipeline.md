# Parallel phase pipeline (Minecraft 26.2)

## Verified boundary

Threadium targets the unobfuscated Minecraft 26.2 client shipped to Loom 1.17.14. The inspected class is `net.minecraft.client.renderer.feature.FeatureRenderDispatcher`.

- `prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;`
- `prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;`
- `lambda$prepareFrameWithContext$0(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;)V`

The first critical redirect is the sole `SubmitNodeStorage.drainPhases(Ljava/util/function/Consumer;)V` invocation at bytecode offset 24 in `prepareFrameWithContext`. It is after the profiler enters `sort` and before `FeatureRenderer.beginPrepare`. The second is the sole `FeatureRenderPhase.sortInto(FeatureRenderPhase$Output)` invocation at bytecode offset 10 in the synthetic consumer. Both redirects specify the full descriptor, ordinal zero, `require = 1`, and `expect = 1`.

The untouched continuation remains `beginPrepare`, `PreparedGroup.prepare`, `finishPrepare`, and `StagedVertexBuffer.upload`. All group construction and every buffer/GPU operation therefore remain on the render thread.

## Implemented processing

Only the recognized `TranslucentFeatureRenderPhase` is detached. Capture copies the current `List<TranslucentSubmit>` identity references and `FloatList` camera distances into independent arrays, then clears the vanilla lists at the normal sorting boundary. A worker receives an encapsulated clone of those arrays. It may read only the primitive distances, sort primitive indices with FastUtil `IntArrays.unstableSort`, and move opaque references into a result array. The comparator is exactly `Floats.compare(distances[right], distances[left])`, matching 26.2, including NaN, infinities, and signed zero.

The worker never invokes a `SubmitNode` method and has no reference to Minecraft, a phase, `PreparedFrame`, `PhaseSubmitGrouper`, a renderer, world, resource manager, `RenderSystem`, or `StagedVertexBuffer`. On the render thread, Threadium replays the ordered references through vanilla's original `FeatureRenderPhase.Output`. Vanilla therefore continues to own feature grouping, `strictlyOrdered`, adjacent extension, `groupsByPhase`, `groupsByFeature`, `allSubmits`, and prepared ranges.

Unknown and modded phase implementations use their original `sortInto` implementation synchronously. `SimpleFeatureRenderPhase` also remains entirely vanilla and synchronous in this revision. Its array feature order, `HashMap.values()` batch order, and `SharedConstants.DEBUG_SHUFFLE_MODELS` behavior are consequently unchanged. A safe Simple snapshot was not added because its measured work has not justified the extra render-thread copying and its debug shuffle semantics must not be approximated.

## Bounded scheduling and state

The dedicated `ThreadPoolExecutor` uses daemon platform threads named `Threadium Phase Worker #N`, a fixed worker count, an `ArrayBlockingQueue`, and `AbortPolicy`. Defaults are:

- workers: `max(1, min(4, (availableProcessors - 2) / 2))`; an override is capped at four and the scheduler additionally caps against available processors
- queue capacity: 32
- one absolute frame deadline: 500 microseconds
- translucent asynchronous threshold: 128 submits
- circuit breaker: three consecutive worker failures

The conservative worker formula reserves processors for rendering and game logic. The small queue bounds retained frame data and makes overload visible. Rejection never waits and never drops geometry: the render thread processes the same immutable snapshot immediately. A single absolute deadline is shared by all slots, so waiting cannot multiply by phase count.

Each task carries frame generation, phase slot, pipeline epoch, submission time, absolute deadline, snapshot, result, and failure. Its CAS states are `PENDING`, `RUNNING`, `ASYNC_COMPLETED`, `SYNC_FALLBACK`, `MERGED`, `FAILED`, and `CANCELLED`. Only an epoch-valid async completion can transition to `MERGED`. Fallback atomically takes ownership; a later worker completion clears its result and is counted stale. This makes double merge structurally impossible. Shutdown rejects new work, interrupts workers, and explicitly cancels queued tasks.

The first worker exception is logged with a stack trace. Later failures are aggregated in metrics. Three consecutive failures open a session circuit breaker; rendering then uses the original synchronous path. Async success resets the consecutive count.

## Metrics and snapshot consistency

Periodic pipeline metrics include snapshot, submission, queue wait, worker execution, render-thread wait, merge, and synchronous fallback timings. Each timing reports count, total, average, minimum, and maximum. Counters include rejected tasks, async completions, fallbacks, stale completions, worker failures, submits, result size, queue depth, and active workers.

Timing accumulators use independent atomics. Reset is a relaxed interval snapshot: a record racing reset can contribute fields to adjacent reporting intervals, but monotonic saturating totals prevent arithmetic overflow and no locking or allocation occurs per timing record. Rates can be derived from the interval counters; the next GPU measurements should compare worker time, render wait, fallback/rejection/stale ratios, and total frame impact at several translucent submit counts.

## Remaining risks and required measurements

The current interception defers consumer replay until `drainPhases` returns. Recognized translucent phases are already empty and are removed normally; a synchronous unknown phase can leave its now-empty collection in `SubmitNodeStorage` until the following drain. This does not replay submits, but should be confirmed over long modded sessions and is a reason not to broaden supported phase types yet.

The headless development environment cannot load a world, so the critical dispatcher redirects require another GPU-host pass after this change. Record vanilla and Sodium results for threshold crossings, queue occupancy, shared-deadline fallback, stale completions, and FPS/1%-low deltas. Do not proceed to entity visibility or renderer/model work until this pipeline demonstrates positive render-thread savings after snapshot, wait, and merge costs.
