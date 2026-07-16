# Experimental retained Text Display renderer

## Implemented boundary

Minecraft 26.2 separates Text Display submission from glyph construction. `DisplayRenderer.TextDisplayRenderer.submitInner(TextDisplayEntityRenderState, PoseStack, SubmitNodeCollector, int, float)` submits one `TextFeatureRenderer.Submit` per cached line. Later, `TextFeatureRenderer.buildGroup(FeatureFrameContext, List)` calls `Font.prepareText` or `Font.prepare8xTextOutline`. Those calls perform glyph selection, style expansion, shadow/effect construction, atlas/render-type selection, and local glyph placement, returning an immutable `Font.PreparedText`. Its `visit` method emits the prepared renderables using the current pose, packed light, and display mode.

Threadium retains these exact vanilla `Font.PreparedText` objects for sequences marked by `TextDisplayRenderer`. It does not independently shape text or recreate glyph rules. On a hit, Minecraft skips `Font.prepareText` and immediately visits the retained vanilla renderables. Vertex emission into the frame's `StagedVertexBuffer` remains vanilla.

This is deliberately a CPU retained-mode vertical slice, not persistent GPU geometry. In 26.2 the vanilla text vertex format bakes color/opacity and packed light into vertices, and `TextRenderable.render` applies the entity pose while writing vertices. A persistent local-space GPU buffer would require a different shader/uniform contract and would not preserve the active vanilla or modded shader pipeline. Background geometry is a separate `submitCustomGeometry` callback and remains vanilla. Consequently `retainedTextGpuBytes` is zero in this revision.

## Verified methods

- `TextDisplayRenderer.extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V`: obtains `textRenderState` and entity-cached line layout.
- `TextDisplayRenderer.splitLines(Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;`: `Font.split`, widths, immutable cached lines.
- `TextDisplayRenderer.submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V`: flags at offsets 6-60, opacity interpolation 62-75, background interpolation 77-129, pose rotation/scale 141-163, background submission 229-263, line/alignment loop 286-451, `submitText` at offset 438.
- `TextFeatureRenderer.buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V`: normal `Font.prepareText` at offset 119, outline preparation at 157, outlined base text preparation at 186, `PreparedText.visit` at 128/203/220.
- `TextFeatureRenderer$GlyphRenderer.acceptRenderable(Lnet/minecraft/client/gui/font/TextRenderable;)V`: obtains the render type at offset 9 and calls `TextRenderable.render(Matrix4fc, VertexConsumer, int, boolean)` at offset 29.

Normal Text Displays use `Font.DisplayMode.POLYGON_OFFSET`; see-through displays use `SEE_THROUGH`. Outline preparation uses `NORMAL`, followed by base glyphs in `POLYGON_OFFSET`. Backgrounds use `RenderTypes.textBackground()` or `textBackgroundSeeThrough()`. Packed light is carried by `TextFeatureRenderer.Submit` and applied only when retained renderables are visited, so it remains dynamic.

## Key and dynamic state

The collision-safe key stores the complete ordered visual stream as `(visual index, Unicode code point, immutable Style)` tuples, plus x/y line placement, ARGB text/outline color, shadow, bidirectional flag, prepared background color, preparation variant, and resource generation. Equality compares complete values; hash collisions cannot select another entry.

Pose, billboard/fixed orientation, display transformation interpolation, packed light, display mode/see-through selection, entity position, and camera-relative transformation remain outside the retained object and are applied by vanilla during `PreparedText.visit`. Text opacity is currently part of the color key because 26.2 bakes it into prepared renderables; interpolation remains visually exact but creates misses while the value changes. Background color and geometry remain wholly vanilla.

Obfuscated text is unsupported because glyph choice is intentionally randomized during preparation. It always falls back.

## Cache lifecycle

The cache is access-ordered and bounded by entry count. Defaults are 512 entries, 64 MiB configured GPU budget, and 60 seconds idle lifetime. The GPU budget is reserved for a future GPU-backed entry and currently remains at zero bytes. Entries are invalidated on world join/disconnect, resource/font reload, client shutdown, resource generation change, and runtime disable. Negative entries suppress repeated failing builds until generation invalidation.

Configuration:

```properties
display.text.retained.enabled=true
display.text.retained.maxCacheEntries=512
display.text.retained.maxGpuBytes=67108864
display.text.retained.entryIdleSeconds=60
```

The enabled property is polled once per second. Editing it provides an in-session A/B toggle. Disabling immediately clears the cache and restores direct vanilla preparation.

## Compatibility

Sodium 0.9.1 does not replace `TextDisplayRenderer.submitInner`, `TextFeatureRenderer.buildGroup`, or the font preparation calls. Threadium therefore uses the same retained vanilla objects with Sodium, without referencing Sodium classes.

Iris disables the feature by default because persistent or altered text shader compatibility has not been established. ImmediatelyFast also disables this experimental path when detected because its general text batching boundary has not been verified for Minecraft 26.2. Neither mod is modified or globally disabled. Unknown sequences not marked by Text Display submission, including other entity and GUI text, use vanilla preparation.

## Operational counters

The interval log reports hits, misses, entries, GPU bytes, retained draws, marked instances, vanilla fallbacks, evictions, and build failures. No per-entity logging is performed.

## GPU-host validation

Use a world with many identical Text Displays. Confirm an initial miss followed by increasing hits; compare visual output while toggling the property; test multiline alignment, shadow, see-through, opacity interpolation, background modes, billboard/fixed transforms, packed-light overrides, outlines, resource reload, disconnect, and reconnect. Repeat with Sodium 0.9.1. Iris and ImmediatelyFast should produce the single initialization line explaining fallback and no hits.

## Known limitations and extension path

This revision retains vanilla glyph/effect preparation but still writes glyph and background vertices each frame. It does not implement instancing or persistent GPU buffers. The next direct step, if CPU savings are measurable, is a vanilla-only text render pipeline whose vertex format moves pose, light, and opacity into per-instance data while retaining atlas-specific local glyph buffers. That requires shader and ordering validation before Iris support. Block Display and Item Display require separate cache formats and are intentionally out of scope.
