# Generic ModelPart GPU renderer (experimental)

## Verified 26.2 boundary

Threadium targets unobfuscated Minecraft 26.2. `ModelFeatureRenderer.prepareModel(ModelFeatureRenderer.Submit): void` copies `Submit.pose` (bytecode 11), chooses/wraps the consumer (19–62), invokes `Model.setupAnim(Object): void` (73), then invokes `Model.renderToBuffer(PoseStack, VertexConsumer, int, int, int): void` (94). The latter enters `ModelPart.render`, `ModelPart.compile`, and `ModelPart.Cube.compile`; the cube method calls `VertexConsumer.addVertex(float,float,float,int,float,float,int,int,float,float,float)` at bytecode 179. Threadium redirects only the call at 94 (`ordinal=0`, `require=1`, `expect=1`). Rejected calls execute the original method unchanged.

`ModelFeatureRenderer.buildGroup(FeatureFrameContext,List): void` HEAD/RETURN injections delimit each prepared GPU queue segment. That segment alone is flushed at the HEAD of the corresponding `RenderTypeFeatureRenderer.executeGroup(FeatureFrameContext,int,List,boolean): void` when the renderer instance is `ModelFeatureRenderer`. Later prepared groups remain queued until their own execution call, keeping suppression and drawing in the same logical feature stage. GPU-host validation must confirm render-state parity before this experiment is considered generally usable.

Minecraft does not leave the render type's destination framebuffer implicitly bound for arbitrary raw GL. `PreparedRenderType.drawFromBuffer(...)` resolves `outputTarget().getRenderTarget()`, creates a pass with that target's color/depth views, applies the pipeline and only then draws. The original Threadium backend drew into the incidental framebuffer at `executeGroup` HEAD. Both backends now attach the prepared render type's exact `GlTextureView` color/depth views to a Threadium-owned draw FBO, verify completeness, set the target-sized viewport, draw, and restore the preceding FBO and viewport once per flush.

## Eligibility and safety

Eligibility is structural, never based on entity type or part names. The model must expose a standard `ModelPart` root, expanded quad polygons, a non-blended/non-outline `RenderType`, a direct `BufferBuilder`, fit configured limits, and pass compatibility gates. Sheeted decal, sprite-wrapped, custom consumer, translucent, outline, Iris, ImmediatelyFast-overlap, failed GL, stale generation, bake failure, and capacity exhaustion use vanilla. Sodium remains optional and its CPU path is untouched on fallback.

Suppression occurs only after: topology inspection, collision-safe exact structural-key lookup, successful immutable mesh bake/upload, primitive pose extraction, and successful bounded queue insertion. With no current GL 4.5 context the backend remains `UNINITIALIZED`, so suppression is impossible.

## Representation

The immutable mesh copies vanilla's expanded `Cube.polygons`: position (3 floats), face normal (3), UV (2), owning bone index (1), 36-byte stride, and six indices per quad. Every part—including transform-only parts—receives a stable preorder bone index and parent index. Structural equality contains hierarchy cardinalities, bind translation, polygon normals, and every expanded vertex/UV value; the fingerprint is only an accelerator and record equality remains collision-safe.

Vertices stay in their owning part's local space. Current accumulated matrices use vanilla's translation/16, ZYX rotation, scale, and parent-first composition. Each 112-byte bone record is a mat4 plus a std430-padded inverse-transpose mat3. Visibility is propagated through parents; `skipDraw` hides only the owning part geometry. Hidden bone matrices are zeroed before upload.

Exact pose deduplication is frame-local and collision-verified. For each topology, the cache probes at most 16 consecutive misses before bypassing fingerprint capture and cache insertion for the rest of that frame. Any hit during the probe keeps exact deduplication active, and every new frame probes again, so static populations retain shared palettes while highly animated populations direct-pack their one-use palettes without carrying the bypass decision across frames.

## OpenGL backend and shaders

`SelectingModelPartGpuBackend` initializes lazily on the Render Thread from the first intercepted model invocation after LWJGL capabilities exist. The old implementation returned from `ensureReady` while still `UNINITIALIZED` whenever `OpenGL45` was false; Minecraft's 3.3 context therefore silently rejected every invocation. Selection now performs one guarded attempt, logs the complete active-context feature projection, and transitions through `INITIALIZING` to either `READY` or `FAILED`.

`auto` selects OpenGL45 only when the active context exposes 4.5, instancing, TBO/UBO/VAO, buffer storage, SSBO, and direct-state-access requirements. Otherwise it selects OpenGL33 when instanced arrays/draws, texture buffers, uniform buffers, and VAOs are present. `opengl45`, `opengl33`, and `disabled` may be forced; unsupported forced modes fail once and retain vanilla rendering.

The OpenGL33 backend uses bind-to-edit VAO/VBO/IBO calls, a streamed texture-buffer object for bones, and a streamed 96-byte instance VBO with divisors. Each bone occupies seven RGBA32F texels: four pose-matrix columns followed by three inverse-transpose normal-matrix columns. The instance contains a root matrix, absolute bone base, packed light, overlay, and normalized tint/alpha. Bounded native staging buffers are allocated once with the backend and reused. The complete group is orphaned and uploaded once; each adjacent batch rebinds instance attribute byte offsets and calls `glDrawElementsInstanced` with its real instance count. No base-instance extension is required.

The OpenGL45 backend uploads the same logical 96-byte instance records to an SSBO. Its shader indexes `instances[uInstanceBase + gl_InstanceID]`; every record contains its own bone base into the group-contiguous bone SSBO. One instance and one bone upload occur per non-empty group.

## Consolidation

The previous implementation uploaded and drew inside the queued-entry loop, forcing `drawnInstances == drawCalls`. Group finalization now copies detached pose data into contiguous bone and instance buffers, then forms stable adjacent ranges. The ordering policy is deliberately `A A A B B A A -> A×3, B×2, A×2`; no invocation is reordered.

`ModelPartBatchKey` requires the same mesh handle and identity-equal canonical RenderType, pipeline, output target/framebuffer selection, vertex format, primitive topology, resource epoch, and render-group ID. RenderType identity owns the fixed textures and supported blend/depth/cull state. Light, overlay, root transform, tint/alpha, visibility, and bone base remain per-instance. Resource invalidation increments the backend epoch and clears all groups, preventing cross-generation batching.

`entity.gpu.batchConsolidation=true` enables consolidation. Setting it false produces singleton ranges through the identical buffer/shader path for A/B diagnostics. A runtime change closes the backend, clears pending groups, and reinitializes safely.

Metrics report consolidated/singleton/multi-instance batches, maximum instances per draw, instances covered by multi-draws, group upload calls and bytes, plus `instancesPerDraw`, `drawReductionRatio`, and `multiInstanceCoverage` with zero-safe calculations. These batching ratios use only batchable unsorted instance draws; sorted quad draws remain visible through separate `sortedInstances` and `sortedDrawCalls` counters and are still included in total `drawCalls`.

Both backends capture and restore program, VAO, array buffer, texture units 0–3, blend/depth/cull enables, depth function/mask, front face, viewport, draw framebuffer, active texture, and projection UBO binding once per flush. Raw GL remains isolated in backend classes. State is `DISABLED`, `UNINITIALIZED`, `INITIALIZING`, `READY`, `ACTIVE`, or `FAILED`; only READY/ACTIVE accept work. Draw-time failure clears queued work, releases backend resources, and makes future invocations fall back, although already-suppressed geometry cannot be recovered in that frame.

Separate GLSL 450 and GLSL 330 variants support the base texture, cutout discard, tint/alpha, packed lightmap, overlay, root pose, bone pose, and inverse-transpose normals. The 330 vertex shader fetches bone texels through `samplerBuffer` and receives the root transform through instanced attributes. Translucency, outlines, emissive/custom shader behavior, Iris, and custom vertex modification are deliberately unsupported.

Mesh ownership belongs to the bounded generation-scoped cache. Resource reload, disconnect, runtime disable, and shutdown release VAO/VBO/IBO/program/SSBO resources on the Render Thread. `entity.gpu.enabled` is polled once per second, allowing an in-session A/B toggle without rebuilding.

## Configuration

The safe default is `entity.gpu.enabled=false` until GPU-host visual parity is confirmed. The properties also include `entity.gpu.debugVisualMode` and `entity.gpu.debugSuppressVanilla`. Modes are `off`, `screen_triangle`, `mesh_clip_space`, `mesh_magenta`, `mesh_no_depth`, `mesh_no_cull`, `mesh_identity_bone`, `mesh_identity_root`, `mesh_projection_only`, and `normal`. Screen-triangle and clip-space modes always retain vanilla output. Other diagnostic modes retain vanilla unless `debugSuppressVanilla=true`; normal/off use the ordinary queue-before-suppression rule. A mode change closes resources, clears pending work, advances the backend epoch and logs once.

`screen_triangle` uses a separate GLSL 330 program and VAO, no model/instance/bone data, no textures, depth, blend, or culling. It targets the same resolved render-type attachment and records a one-shot framebuffer/viewport/draw-buffer/program/error diagnostic. Mesh modes use a shader integer selector: clip-space mesh validates the real VAO/VBO/IBO; magenta validates transforms without material sampling; identity-bone/root and projection-only isolate matrix stages; no-depth/no-cull isolate raster state. Debug GL errors and incomplete FBOs are aggregated, not logged per entity.

### Diagnostic isolation invariant

The original overlay implementation called the production `tryQueue` path and returned a boolean that ambiguously represented both queue acceptance and whether the redirected vanilla call should run. Consequently `screen_triangle` still initialized production resources, baked and queued the entity mesh, and depended on a production group flush. This made it an invalid overlay diagnostic even when the final boolean intended to preserve vanilla.

The interception contract is now the exhaustive `PASS_THROUGH`, `DEBUG_OVERLAY_ONLY`, or `GPU_REPLACED` result. The redirect contains the sole suppression gate and skips `Model.renderToBuffer` only when `ModelPartSuppressionPolicy` permits a `GPU_REPLACED` result. `screen_triangle` and `mesh_clip_space` return `DEBUG_OVERLAY_ONLY` before backend initialization, topology inspection, mesh baking, pose extraction, or queue insertion; `debugSuppressVanilla` cannot override that rule.

Overlay requests are issued once at `LevelRenderer.render(...)` HEAD and consumed at its TAIL through an independently owned diagnostic renderer. A frame latch coalesces the request, including frames containing no visible entity. The diagnostic renderer owns its shader/VAO and catches initialization or draw failures without changing the production backend state or cache. Mode changes destroy both pending production work and overlay resources. `mesh_clip_space` submits an independently owned clip-space diagnostic primitive; it retains no entity, model, mesh, pose, material, instance, or bone data and never enters the production queue.

## GPU-host validation (fish)

```fish
set -lx JAVA_HOME /usr/lib/jvm/zulu-25
./gradlew --no-daemon clean test build
sed -i 's/^entity.gpu.enabled=.*/entity.gpu.enabled=false/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.enabled=.*/entity.gpu.enabled=true/' run/config/threadium.properties
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=screen_triangle/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=mesh_clip_space/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=mesh_magenta/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=mesh_identity_bone/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=mesh_no_cull/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=mesh_no_depth/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
sed -i 's/^entity.gpu.debugVisualMode=.*/entity.gpu.debugVisualMode=normal/' run/config/threadium.properties
sed -i 's/^entity.gpu.debugSuppressVanilla=.*/entity.gpu.debugSuppressVanilla=true/' run/config/threadium.properties
./gradlew --no-daemon runClient --info
./gradlew --no-daemon -Pthreadium.sodium=true runClient --info
rg -i 'GPU ModelPart|debugVisual|framebuffer|viewport|clip|matrix|projection|bone|cull|depth|GL error|gpuEntity|threadium.*exception' run/logs/latest.log | tail -n 120
```

In each run inspect Cow, Zombie, Creeper, a deep vanilla hierarchy, and a Blockbench-style modded standard `ModelPart` model without adding entity-specific production code. Toggle the property while connected; verify immediate vanilla restoration, no invisible/duplicate base pass, animation, UVs, lighting, hurt overlay, tint, hidden parts, unsupported feature-layer fallback, F3+T rebuild, disconnect cleanup, and Sodium fallback. Benchmark identical 100/500/1000-entity scenes with MangoHud, recording average FPS, 1% low, GPU/Render-thread load, accepted/fallback counts, and visual parity.

## Known unverified behavior

### Fullscreen readback diagnostic

`screen_triangle` now draws a fullscreen, texture-free GLSL 330 triangle into an FBO attached to Minecraft's main color/depth texture at `LevelRenderer.render:TAIL`. That hook is early: Minecraft still renders the hand and screen effects, entity outlines, optional post-processing, clears depth, and renders the GUI afterward. `screen_triangle_main_target` performs the identical overlay-only draw at `GameRenderer.render:TAIL`, after GUI and immediately before `Minecraft.renderFrame` asks `windowSurface` to blit the main color texture for presentation. Both modes perform a single before/after center-pixel readback per activation while continuing the fullscreen draw once per requested world frame.

The diagnostic owns its shader, nonzero VAO, FBO, and four-byte direct readback buffer. It explicitly disables depth, culling, blending, scissor, rasterizer discard, and stencil; enables all color channels; selects fill mode, `GL_COLOR_ATTACHMENT0`, and the complete target viewport; and restores draw/read FBOs and buffers, program, VAO, viewport, depth, cull, blend, scissor, rasterizer-discard, stencil, color-mask, polygon, texture, and UBO state. Readback failure is isolated from the production ModelPart backend.

The fullscreen readback path was exercised on the OpenGL 3.3 NVIDIA host; real production multi-instance draws still require separate visual-parity validation. Remaining work includes visual parity under large mixed groups, fence-backed multi-frame rings for eliminating possible driver-side orphaning stalls, and optionally proven-safe full opaque partitioning. The implementation intentionally keeps adjacent-only ordering and does not use multi-draw indirect.
