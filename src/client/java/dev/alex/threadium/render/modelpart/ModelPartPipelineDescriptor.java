package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;

/** Canonical Vanilla 26.2 pipelines proven to receive ModelFeatureRenderer geometry. */
enum ModelPartPipelineDescriptor {
    ARMOR_CUTOUT(RenderPipelines.ARMOR_CUTOUT_NO_CULL, ShaderMode.LIT_NO_OVERLAY, Ordering.ORDERED_ADJACENT),
    ARMOR_DECAL(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, ShaderMode.LIT_NO_OVERLAY, Ordering.ORDERED_ADJACENT),
    ARMOR_TRANSLUCENT(RenderPipelines.ARMOR_TRANSLUCENT, ShaderMode.LIT_NO_OVERLAY, Ordering.ORDERED_ADJACENT),
    ENTITY_SOLID(RenderPipelines.ENTITY_SOLID, ShaderMode.LIT_OVERLAY, Ordering.ADJACENT),
    ENTITY_CUTOUT_CULL(RenderPipelines.ENTITY_CUTOUT_CULL, ShaderMode.LIT_OVERLAY, Ordering.ADJACENT),
    ENTITY_CUTOUT(RenderPipelines.ENTITY_CUTOUT, ShaderMode.LIT_OVERLAY, Ordering.ADJACENT),
    ENTITY_CUTOUT_Z_OFFSET(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ShaderMode.LIT_OVERLAY, Ordering.ADJACENT),
    ENTITY_CUTOUT_DISSOLVE(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, ShaderMode.DISSOLVE, Ordering.ORDERED_ADJACENT),
    ENTITY_TRANSLUCENT(RenderPipelines.ENTITY_TRANSLUCENT, ShaderMode.LIT_OVERLAY, Ordering.ORDERED_ADJACENT),
    ENTITY_TRANSLUCENT_EMISSIVE(
            RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ShaderMode.EMISSIVE_OVERLAY, Ordering.ORDERED_ADJACENT),
    ENTITY_TRANSLUCENT_CULL(RenderPipelines.ENTITY_TRANSLUCENT_CULL, ShaderMode.LIT_OVERLAY, Ordering.ORDERED_ADJACENT),
    BANNER_PATTERN(RenderPipelines.BANNER_PATTERN, ShaderMode.LIT_NO_OVERLAY, Ordering.ORDERED_ADJACENT),
    BREEZE_WIND(RenderPipelines.BREEZE_WIND, ShaderMode.LIT_TEXTURE_MATRIX, Ordering.ORDERED_ADJACENT),
    ENERGY_SWIRL(RenderPipelines.ENERGY_SWIRL, ShaderMode.EMISSIVE_TEXTURE_MATRIX, Ordering.ORDERED_ADJACENT),
    EYES(RenderPipelines.EYES, ShaderMode.EMISSIVE, Ordering.ORDERED_ADJACENT),
    WATER_MASK(RenderPipelines.WATER_MASK, ShaderMode.WATER_MASK, Ordering.ADJACENT),
    GLINT(RenderPipelines.GLINT, ShaderMode.GLINT, Ordering.ORDERED_ADJACENT),
    CRUMBLING(RenderPipelines.CRUMBLING, ShaderMode.CRUMBLING, Ordering.ORDERED_ADJACENT),
    OUTLINE_CULL(RenderPipelines.OUTLINE_CULL, ShaderMode.OUTLINE, Ordering.ORDERED_ADJACENT),
    OUTLINE_NO_CULL(RenderPipelines.OUTLINE_NO_CULL, ShaderMode.OUTLINE, Ordering.ORDERED_ADJACENT);

    static final List<RenderPipeline> SOURCES =
            List.of(values()).stream().map(ModelPartPipelineDescriptor::source).toList();
    private static final Map<RenderPipeline, ModelPartPipelineDescriptor> BY_SOURCE = buildSourceLookup();

    private final RenderPipeline source;
    private final ShaderMode shaderMode;
    private final Ordering ordering;

    ModelPartPipelineDescriptor(RenderPipeline source, ShaderMode shaderMode, Ordering ordering) {
        this.source = source;
        this.shaderMode = shaderMode;
        this.ordering = ordering;
    }

    RenderPipeline source() {
        return source;
    }

    ShaderMode shaderMode() {
        return shaderMode;
    }

    Ordering ordering() {
        return ordering;
    }

    String canonicalName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    SubmissionPolicy submissionPolicy() {
        return switch (this) {
            case ARMOR_TRANSLUCENT,
                    ENTITY_TRANSLUCENT,
                    ENTITY_TRANSLUCENT_EMISSIVE,
                    ENTITY_TRANSLUCENT_CULL,
                    BANNER_PATTERN,
                    BREEZE_WIND,
                    ENERGY_SWIRL,
                    EYES,
                    CRUMBLING -> SubmissionPolicy.SORTED_QUAD_STREAM;
            case ENTITY_SOLID, ENTITY_CUTOUT_CULL, ENTITY_CUTOUT, ENTITY_CUTOUT_Z_OFFSET, WATER_MASK ->
                SubmissionPolicy.OPAQUE_BATCHED;
            default -> SubmissionPolicy.ORDERED_ADJACENT_BATCHED;
        };
    }

    boolean perFaceLighting() {
        return switch (this) {
            case ARMOR_CUTOUT,
                    ARMOR_DECAL,
                    ARMOR_TRANSLUCENT,
                    ENTITY_CUTOUT,
                    ENTITY_CUTOUT_Z_OFFSET,
                    ENTITY_CUTOUT_DISSOLVE,
                    ENTITY_TRANSLUCENT,
                    ENTITY_TRANSLUCENT_EMISSIVE -> true;
            default -> false;
        };
    }

    boolean cardinalLighting() {
        return this == BANNER_PATTERN;
    }

    boolean alphaCutout() {
        return switch (this) {
            case ENTITY_SOLID, BANNER_PATTERN, EYES, OUTLINE_CULL, OUTLINE_NO_CULL, WATER_MASK -> false;
            default -> true;
        };
    }

    boolean bindsOverlay() {
        return shaderMode.overlay || this == ARMOR_CUTOUT || this == ARMOR_DECAL || this == ARMOR_TRANSLUCENT;
    }

    boolean bindsLightmap() {
        return shaderMode.lightmap || this == ENERGY_SWIRL;
    }

    /**
     * Minecraft 26.2 prepare() snapshots textures/samplers, dynamic transforms, and scissor state. These three ordinary
     * entity types have fixed texture-transform/layering policy and may reuse that snapshot only for the same
     * RenderType identity during one ModelFeatureRenderer preparation group. All other descriptors fail closed.
     */
    boolean allowsGroupLocalPreparedReuse() {
        return this == ENTITY_SOLID || this == ENTITY_CUTOUT_CULL || this == ENTITY_CUTOUT;
    }

    static ModelPartPipelineDescriptor from(RenderPipeline pipeline) {
        return BY_SOURCE.get(pipeline);
    }

    private static Map<RenderPipeline, ModelPartPipelineDescriptor> buildSourceLookup() {
        IdentityHashMap<RenderPipeline, ModelPartPipelineDescriptor> lookup = new IdentityHashMap<>();
        for (ModelPartPipelineDescriptor descriptor : values()) {
            ModelPartPipelineDescriptor existing = lookup.put(descriptor.source, descriptor);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate ModelPart source pipeline identity: " + existing + " and " + descriptor);
            }
        }
        return Collections.unmodifiableMap(lookup);
    }

    enum Ordering {
        ADJACENT,
        ORDERED_ADJACENT
    }

    enum SubmissionPolicy {
        OPAQUE_BATCHED,
        ORDERED_ADJACENT_BATCHED,
        SORTED_QUAD_STREAM,
        SINGLETON_ONLY
    }

    enum ShaderMode {
        LIT_OVERLAY(true, true, false, false),
        LIT_NO_OVERLAY(false, true, false, false),
        DISSOLVE(true, true, true, false),
        EMISSIVE_OVERLAY(true, false, false, false),
        LIT_TEXTURE_MATRIX(false, true, false, true),
        EMISSIVE_TEXTURE_MATRIX(false, false, false, true),
        EMISSIVE(false, false, false, false),
        OUTLINE(false, false, false, false),
        GLINT(false, false, false, true),
        CRUMBLING(false, false, false, false),
        WATER_MASK(false, false, false, false);
        final boolean overlay, lightmap, dissolveMask, textureMatrix;

        ShaderMode(boolean overlay, boolean lightmap, boolean dissolveMask, boolean textureMatrix) {
            this.overlay = overlay;
            this.lightmap = lightmap;
            this.dissolveMask = dissolveMask;
            this.textureMatrix = textureMatrix;
        }
    }
}
