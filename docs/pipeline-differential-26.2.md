# Minecraft 26.2 pipeline differential audit

The safe offscreen target is `TextureTarget(label, width, height, true,
GpuFormat.RGBA8_UNORM)`. It owns a copy-source color texture and a `D32_FLOAT`
depth texture. `PreparedRenderType.drawFromBuffer` and Threadium's Blaze3D backend
both honor `RenderSystem.outputColorTextureOverride` and
`RenderSystem.outputDepthTextureOverride`, so a harness can redirect draws without
changing Vanilla or Threadium pipeline definitions.

Readback is asynchronous. `CommandEncoder.copyTextureToBuffer` accepts a completion
callback; the callback maps a `GpuBuffer` created with `USAGE_MAP_READ |
USAGE_COPY_DST`. This is the same lifecycle used by `Screenshot.takeScreenshot`.
Color and depth can be copied independently. No `glFinish`, polling loop, raw OpenGL,
or active-framebuffer readback is required.

The current interception point is the redirect of `Model.renderToBuffer` inside
`ModelFeatureRenderer.prepareModel`. Preparation and execution are separate:
`RenderTypeFeatureRenderer.prepareGroup` writes Vanilla vertices to a
`StagedVertexBuffer`, and `executeGroup` later issues the prepared draws. Therefore,
reference and candidate submits inserted into one ordinary feature group cannot be
isolated merely by changing the output override: suppression has already happened
during preparation, while the override is consumed during execution.

A correct differential runner must create two independent `ModelFeatureRenderer`
preparations, two independent `StagedVertexBuffer` instances, and two offscreen
targets. Reference preparation needs an explicit interception-bypass scope; candidate
preparation must require a positively accepted Threadium submission. Both must be
driven from one immutable fixture snapshot, with raw ModelPart pose bits verified
before each preparation. The resulting groups can then execute sequentially in the
same render frame under separate target overrides, followed by asynchronous readback.

Semantic fixtures cannot be derived from the synthetic PlayerModel grid. They require
real model/state/texture/atlas/decal inputs from the audited Vanilla call sites before
automatic image comparison is meaningful.
