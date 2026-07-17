# Minecraft 26.2 ModelPart pipeline inventory

Source of truth: mapped 26.2 `RenderTypes`, `RenderPipelines`, `SubmitNodeCollection`,
`ModelFeatureRenderer`, entity layers, entity renderers, and block-entity renderers.
Only pipelines reaching `SubmitNodeCollector.submitModel`/`submitModelPart` are included.

| Canonical descriptor | Vanilla source/call sites | Textures and dynamic state | Raster state summary | Ordering |
|---|---|---|---|---|
| `entity_solid` | default solid models; banners, bells, books, shields, tridents | base + overlay + lightmap | opaque, depth write, cull | adjacent |
| `entity_cutout_cull` | bats, arrows, fishing hooks, chests | base + overlay + lightmap | alpha 0.1, depth write, cull | adjacent |
| `entity_cutout` | default entity models and colored feature layers | base + overlay + lightmap; outline flag belongs to submit routing | alpha 0.1, depth write, no cull | adjacent |
| `entity_cutout_z_offset` | shulkers and skull block models | base + overlay + lightmap; view-offset layering | alpha 0.1, depth write, no cull | adjacent |
| `entity_cutout_dissolve` | dying Ender Dragon | base + dissolve mask + overlay + lightmap | alpha/dissolve, no cull | ordered adjacent |
| `entity_translucent` | allay, player, slime outer, horse markings, skulls | base + overlay + lightmap | translucent, depth write, no cull | ordered adjacent |
| `entity_translucent_cull` | invisible-but-visible living entity body | base + overlay + lightmap; item-entity target | translucent, depth write, cull | ordered adjacent |
| `entity_translucent_emissive` | Warden/Creaking emissive layers; breeze-eyes alias | base + overlay, no lightmap multiplication | translucent, depth compare GE, no write/cull | ordered adjacent |
| `armor_cutout` | equipment layers and non-decal armor trims | base + bound overlay + lightmap; view layering | alpha 0.1, no cull | ordered adjacent |
| `armor_decal` | decal armor trims | atlas sprite + bound overlay + lightmap; view layering | alpha 0.1, depth equal/no write | ordered adjacent |
| `armor_translucent` | damaged wolf armor | base + bound overlay + lightmap; view layering | translucent, no cull | ordered adjacent |
| `banner_pattern` | banner/shield pattern ModelParts | atlas sprite + lightmap | translucent, depth compare GE/no write | ordered adjacent |
| `breeze_wind` | Breeze and wind-charge ModelParts | per-submit UV offset + base + lightmap | translucent, no cull | ordered adjacent |
| `energy_swirl` | charged creeper/wither-style layers | per-submit UV offset + base; additive/emissive | additive, depth write, no cull | ordered adjacent |
| `eyes` | spider/enderman/phantom/dragon eyes | base only, emissive | translucent, depth compare GE/no write | ordered adjacent |
| `glint` | armor foil and thrown-trident foil | glint texture + frame texture matrix | glint blend, depth equal/no write, no cull | ordered adjacent |
| `outline_cull` | outline submit derived from culling source type | base texture, outline target/color | opaque, cull | ordered adjacent |
| `outline_no_cull` | outline submit derived from no-cull source type; invisible glowing slime | base texture, outline target/color | opaque, no cull | ordered adjacent |
| `crumbling` | ModelPart breaking overlays from `CrumblingOverlay` | destroy texture + per-submit sheeted decal inverse pose | multiplicative blend, polygon offset, depth compare GE/no write | ordered adjacent |
| `water_mask` | boat water-patch ModelPart | no texture | color writes disabled, depth write | adjacent |

`breezeEyes` and the four glint factory variants are aliases of canonical source
pipelines. Texture identity, sprite coordinates, prepared dynamic transforms, output
target, and submit group remain part of compatibility. Dynamic UV factories create
distinct `RenderType` identities, preventing incompatible batching.

Outside ModelPart scope: `entity_solid_z_offset_forward` (painting custom geometry
and block-model feature rendering), beacon/end-crystal beams, dragon rays, end portal/gateway
cubes, lightning, lines/leashes, shadows, terrain/block models, particles, text,
item baked quads, GUI, and fullscreen pipelines. They use custom geometry or other
feature renderers rather than `ModelFeatureRenderer`.

Suggested deterministic coverage fixtures: armored player or armor stand with trim
and foil; slime; enderman/spider; charged creeper; Breeze; shulker; boat; banner;
painting; dying dragon (or a developer submit fixture); glowing invisible slime;
and a breaking block entity/model fixture. Coverage counters in benchmark status
provide accepted/fallback/draw/instance/max-batch values per canonical descriptor.

The current direct PlayerModel fixture grid is classified as
`SYNTHETIC_REACHABILITY_ONLY`. It proves descriptor encounter, acceptance, fallback,
submission, and sorted collected/submitted accounting. Applying unrelated materials
to one PlayerModel is not a visual-equivalence test; manual notes from that grid do
not satisfy or override differential correctness results.

## Minecraft 26.2 sorted-quad contract

`RenderTypeFeatureRenderer.Group.getOrAddDraw` passes the active projection type's
`VertexSorting` to one `StagedVertexBuffer.Draw` when `RenderType.sortOnUpload()` is
true. Reorderable groups consolidate structurally equal `PreparedRenderType` draws;
strict groups only consolidate consecutive geometry. At upload,
`StagedVertexBuffer.decodeSortingPoints` gathers every quad in that draw, including
geometry appended by multiple model submits. `MeshData.decodeQuadCentroids` uses the
midpoint of vertices 0 and 2. Perspective sorting compares squared distance to the
origin in descending order. `IntArrays.mergeSort` makes equal keys stable. The
generated index order for each sorted quad is `0,1,2,2,3,0`.

The nine audited ModelPart factories with `sortOnUpload=true` are
`armor_translucent`, `entity_translucent`, `entity_translucent_cull`,
`entity_translucent_emissive`, `banner_pattern`, `breeze_wind`, `energy_swirl`,
`eyes`, and `crumbling`. Glint and outline pipelines are ordered but not
sort-on-upload in Minecraft 26.2.
