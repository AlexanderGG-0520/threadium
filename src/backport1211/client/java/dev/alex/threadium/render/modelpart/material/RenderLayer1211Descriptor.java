package dev.alex.threadium.render.modelpart.material;

import dev.alex.threadium.mixin.accessor.RenderLayerMultiPhaseAccessor;
import dev.alex.threadium.mixin.accessor.RenderLayerMultiPhaseParametersAccessor;
import dev.alex.threadium.mixin.accessor.RenderPhaseTextureAccessor;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/** Exact Vanilla 1.21.1 ModelPart RenderLayer classification. */
public record RenderLayer1211Descriptor(
        RenderLayer layer,
        Identifier texture,
        Kind kind,
        ShaderMode shaderMode,
        SubmissionPolicy submissionPolicy,
        boolean alphaCutout,
        ReplacementAdmissionPolicy admissionPolicy) {
    public RenderLayer1211Descriptor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(shaderMode, "shaderMode");
        Objects.requireNonNull(submissionPolicy, "submissionPolicy");
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    public static RenderLayer1211Descriptor inspect(MaterialContextResolution<Object, RenderLayer> resolution) {
        return inspect(resolution, false);
    }

    /** Classifies an outline delegate only after its wrapper color and underlying Immediate drawer are proven. */
    public static RenderLayer1211Descriptor inspectOutline(MaterialContextResolution<Object, RenderLayer> resolution) {
        return inspect(resolution, true);
    }

    private static RenderLayer1211Descriptor inspect(
            MaterialContextResolution<Object, RenderLayer> resolution, boolean outlineInputsResolved) {
        Objects.requireNonNull(resolution, "resolution");
        RenderLayer layer = resolution.layer();
        Inspection inspection = inspectLayer(layer);
        Kind kind = inspection.kind;
        boolean supported = kind != Kind.UNSUPPORTED && kind.backendSupported;
        ReplacementAdmissionPolicy policy = new ReplacementAdmissionPolicy(
                resolution.status(),
                resolution.providerSource(),
                resolution.crossProviderRebound(),
                kind != Kind.UNSUPPORTED,
                inspection.inputsResolved,
                supported,
                !isOutline(kind) || outlineInputsResolved);
        return new RenderLayer1211Descriptor(
                layer, inspection.texture, kind, kind.shaderMode, kind.submissionPolicy, kind.alphaCutout, policy);
    }

    private static boolean isOutline(Kind kind) {
        return kind == Kind.OUTLINE_CULL || kind == Kind.OUTLINE_NO_CULL;
    }

    public boolean replacementSafe() {
        return admissionPolicy.allowed();
    }

    private static Inspection inspectLayer(RenderLayer layer) {
        if (!(layer instanceof RenderLayer.MultiPhase)) return Inspection.unsupported();
        Identifier texture = texture(layer);
        if (texture != null) {
            if (layer == RenderLayer.getArmorCutoutNoCull(texture))
                return new Inspection(texture, Kind.ARMOR_CUTOUT, true);
            if (phaseEquivalent(layer, RenderLayer.createArmorDecalCutoutNoCull(texture), false)) {
                return new Inspection(texture, Kind.ARMOR_DECAL, true);
            }
            if (layer == RenderLayer.getEntitySolid(texture)) return new Inspection(texture, Kind.ENTITY_SOLID, true);
            if (layer == RenderLayer.getEntityCutout(texture))
                return new Inspection(texture, Kind.ENTITY_CUTOUT_CULL, true);
            if (layer == RenderLayer.getEntityCutoutNoCull(texture, true)
                    || layer == RenderLayer.getEntityCutoutNoCull(texture, false)) {
                return new Inspection(texture, Kind.ENTITY_CUTOUT, true);
            }
            if (layer == RenderLayer.getEntityCutoutNoCullZOffset(texture, true)
                    || layer == RenderLayer.getEntityCutoutNoCullZOffset(texture, false)) {
                return new Inspection(texture, Kind.ENTITY_CUTOUT_Z_OFFSET, true);
            }
            if (layer == RenderLayer.getEntityTranslucent(texture, true)
                    || layer == RenderLayer.getEntityTranslucent(texture, false)) {
                return new Inspection(texture, Kind.ENTITY_TRANSLUCENT, true);
            }
            if (layer == RenderLayer.getEntityTranslucentEmissive(texture, true)
                    || layer == RenderLayer.getEntityTranslucentEmissive(texture, false)) {
                return new Inspection(texture, Kind.ENTITY_TRANSLUCENT_EMISSIVE, true);
            }
            if (layer == RenderLayer.getEntityTranslucentCull(texture)) {
                return new Inspection(texture, Kind.ENTITY_TRANSLUCENT_CULL, true);
            }
            if (layer == RenderLayer.getItemEntityTranslucentCull(texture)) {
                return new Inspection(texture, Kind.ITEM_ENTITY_TRANSLUCENT_CULL, true);
            }
            if (layer == RenderLayer.getEntityNoOutline(texture)) {
                return new Inspection(texture, Kind.BANNER_PATTERN, true);
            }
            if (layer == RenderLayer.getEntityDecal(texture)) return new Inspection(texture, Kind.ENTITY_DECAL, true);
            if (layer == RenderLayer.getEyes(texture)) return new Inspection(texture, Kind.EYES, true);
            if (phaseEquivalent(layer, RenderLayer.getBreezeWind(texture, 0.125F, 0.25F), true)) {
                return new Inspection(texture, Kind.BREEZE_WIND, true);
            }
            if (phaseEquivalent(layer, RenderLayer.getEnergySwirl(texture, 0.125F, 0.25F), true)) {
                return new Inspection(texture, Kind.ENERGY_SWIRL, true);
            }
            if (layer == RenderLayer.getBlockBreaking(texture)) return new Inspection(texture, Kind.CRUMBLING, true);
            if (layer == RenderLayer.getOutline(texture)) return new Inspection(texture, Kind.OUTLINE_NO_CULL, true);
            RenderLayer cullingOutline =
                    RenderLayer.getEntityCutout(texture).getAffectedOutline().orElse(null);
            if (layer == cullingOutline) return new Inspection(texture, Kind.OUTLINE_CULL, true);
        }
        if (layer == RenderLayer.getWaterMask()) return new Inspection(null, Kind.WATER_MASK, true);
        if (layer == RenderLayer.getEntityGlint()
                || layer == RenderLayer.getDirectEntityGlint()
                || layer == RenderLayer.getGlint()
                || layer == RenderLayer.getGlintTranslucent()
                || layer == RenderLayer.getArmorEntityGlint()) {
            return new Inspection(null, Kind.GLINT, true);
        }
        return Inspection.unsupported();
    }

    private static Identifier texture(RenderLayer layer) {
        Object phases = ((RenderLayerMultiPhaseAccessor) (Object) layer).threadium$getPhases();
        Object texturePhase = ((RenderLayerMultiPhaseParametersAccessor) phases).threadium$getTexturePhase();
        if (!(texturePhase instanceof RenderPhaseTextureAccessor accessor)) return null;
        return accessor.threadium$getTextureId().orElse(null);
    }

    private static boolean phaseEquivalent(RenderLayer actual, RenderLayer template, boolean dynamicTexturing) {
        if (actual.getVertexFormat() != template.getVertexFormat()
                || actual.getDrawMode() != template.getDrawMode()
                || actual.hasCrumbling() != template.hasCrumbling()
                || actual.isTranslucent() != template.isTranslucent()
                || actual.isOutline() != template.isOutline()
                || actual.getAffectedOutline().isPresent()
                        != template.getAffectedOutline().isPresent()) {
            return false;
        }
        List<?> actualPhases = phases(actual);
        List<?> templatePhases = phases(template);
        if (actualPhases.size() != templatePhases.size()) return false;
        for (int index = 0; index < actualPhases.size(); index++) {
            Object left = actualPhases.get(index);
            Object right = templatePhases.get(index);
            if (index == 0
                    && left instanceof RenderPhaseTextureAccessor leftTexture
                    && right instanceof RenderPhaseTextureAccessor rightTexture) {
                if (left.getClass() != right.getClass()
                        || leftTexture.threadium$isBlurred() != rightTexture.threadium$isBlurred()
                        || leftTexture.threadium$isMipmapped() != rightTexture.threadium$isMipmapped()) {
                    return false;
                }
                continue;
            }
            if (dynamicTexturing && index == 9) {
                if (left.getClass() != right.getClass()) return false;
            } else if (left != right) {
                return false;
            }
        }
        return true;
    }

    private static List<?> phases(RenderLayer layer) {
        Object parameters = ((RenderLayerMultiPhaseAccessor) (Object) layer).threadium$getPhases();
        return ((RenderLayerMultiPhaseParametersAccessor) parameters).threadium$getPhaseList();
    }

    public enum Kind {
        ARMOR_CUTOUT(ShaderMode.LIT_NO_OVERLAY, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, true, true),
        ARMOR_DECAL(ShaderMode.LIT_NO_OVERLAY, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, true, true),
        ENTITY_SOLID(ShaderMode.LIT_OVERLAY, SubmissionPolicy.OPAQUE_BATCHED, false, true),
        ENTITY_CUTOUT_CULL(ShaderMode.LIT_OVERLAY, SubmissionPolicy.OPAQUE_BATCHED, true, true),
        ENTITY_CUTOUT(ShaderMode.LIT_OVERLAY, SubmissionPolicy.OPAQUE_BATCHED, true, true),
        ENTITY_CUTOUT_Z_OFFSET(ShaderMode.LIT_OVERLAY, SubmissionPolicy.OPAQUE_BATCHED, true, true),
        ENTITY_DECAL(ShaderMode.LIT_DECAL, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, true, true),
        ENTITY_TRANSLUCENT(ShaderMode.LIT_OVERLAY, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        ENTITY_TRANSLUCENT_EMISSIVE(ShaderMode.EMISSIVE_OVERLAY, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        ENTITY_TRANSLUCENT_CULL(ShaderMode.LIT_NO_OVERLAY, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        ITEM_ENTITY_TRANSLUCENT_CULL(ShaderMode.LIT_OVERLAY, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        BANNER_PATTERN(ShaderMode.LIT_NO_OVERLAY, SubmissionPolicy.SORTED_QUAD_STREAM, false, true),
        BREEZE_WIND(ShaderMode.LIT_TEXTURE_MATRIX, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        ENERGY_SWIRL(ShaderMode.EMISSIVE_TEXTURE_MATRIX, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        EYES(ShaderMode.EMISSIVE, SubmissionPolicy.SORTED_QUAD_STREAM, false, true),
        WATER_MASK(ShaderMode.WATER_MASK, SubmissionPolicy.OPAQUE_BATCHED, false, true),
        GLINT(ShaderMode.GLINT, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, true, true),
        CRUMBLING(ShaderMode.CRUMBLING, SubmissionPolicy.SORTED_QUAD_STREAM, true, true),
        OUTLINE_CULL(ShaderMode.OUTLINE, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, false, true),
        OUTLINE_NO_CULL(ShaderMode.OUTLINE, SubmissionPolicy.ORDERED_ADJACENT_BATCHED, false, true),
        UNSUPPORTED(ShaderMode.LIT_OVERLAY, SubmissionPolicy.SINGLETON_ONLY, true, false);

        private final ShaderMode shaderMode;
        private final SubmissionPolicy submissionPolicy;
        private final boolean alphaCutout;
        private final boolean backendSupported;

        Kind(ShaderMode shaderMode, SubmissionPolicy submissionPolicy, boolean alphaCutout, boolean backendSupported) {
            this.shaderMode = shaderMode;
            this.submissionPolicy = submissionPolicy;
            this.alphaCutout = alphaCutout;
            this.backendSupported = backendSupported;
        }

        public ShaderMode shaderMode() {
            return shaderMode;
        }

        public SubmissionPolicy submissionPolicy() {
            return submissionPolicy;
        }

        public boolean alphaCutout() {
            return alphaCutout;
        }

        public boolean backendSupported() {
            return backendSupported;
        }
    }

    public enum SubmissionPolicy {
        OPAQUE_BATCHED,
        ORDERED_ADJACENT_BATCHED,
        SORTED_QUAD_STREAM,
        SINGLETON_ONLY
    }

    public enum ShaderMode {
        LIT_OVERLAY(0),
        LIT_NO_OVERLAY(1),
        EMISSIVE_OVERLAY(2),
        LIT_TEXTURE_MATRIX(3),
        EMISSIVE_TEXTURE_MATRIX(4),
        EMISSIVE(5),
        OUTLINE(6),
        GLINT(7),
        CRUMBLING(8),
        WATER_MASK(9),
        LIT_DECAL(10);

        private final int uniformValue;

        ShaderMode(int uniformValue) {
            this.uniformValue = uniformValue;
        }

        public int uniformValue() {
            return uniformValue;
        }
    }

    private record Inspection(Identifier texture, Kind kind, boolean inputsResolved) {
        private static Inspection unsupported() {
            return new Inspection(null, Kind.UNSUPPORTED, false);
        }
    }
}
