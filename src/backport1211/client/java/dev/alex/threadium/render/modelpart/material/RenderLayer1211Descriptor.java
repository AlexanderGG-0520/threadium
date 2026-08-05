package dev.alex.threadium.render.modelpart.material;

import com.google.common.collect.ImmutableList;
import dev.alex.threadium.mixin.accessor.RenderLayerMultiPhaseAccessor;
import dev.alex.threadium.mixin.accessor.RenderLayerMultiPhaseParametersAccessor;
import dev.alex.threadium.mixin.accessor.RenderPhaseTextureAccessor;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/**
 * Exact Minecraft 1.20.1 descriptor for the first replacement-safe material subset.
 *
 * <p>The accepted shape is Vanilla {@code entity_cutout_no_cull} with a single non-blurred, non-mipmapped texture.
 * Every non-texture phase must be the same canonical phase object as Vanilla's template. Unknown, modded, wrapped,
 * translucent, outline, or crumbling states fail closed.
 */
public record RenderLayer1211Descriptor(
        RenderLayer layer, Identifier texture, ReplacementAdmissionPolicy admissionPolicy) {
    private static final Identifier TEMPLATE_TEXTURE = new Identifier("threadium", "replacement_probe");
    private static final RenderLayer ENTITY_CUTOUT_NO_CULL_TEMPLATE =
            RenderLayer.getEntityCutoutNoCull(TEMPLATE_TEXTURE);

    public RenderLayer1211Descriptor {
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    public boolean replacementSafe() {
        return layer != null && texture != null && admissionPolicy.allowed();
    }

    public static RenderLayer1211Descriptor inspect(MaterialContextResolution<Object, RenderLayer> resolution) {
        Objects.requireNonNull(resolution, "resolution");
        RenderLayer layer = resolution.layer();
        LayerInspection inspection = inspectLayer(layer);
        ReplacementAdmissionPolicy policy = new ReplacementAdmissionPolicy(
                resolution.status(),
                resolution.providerSource(),
                resolution.crossProviderRebound(),
                inspection.exactSupportedLayer(),
                inspection.texture() != null,
                layer != null && layer.isOutline(),
                layer != null && layer.isTranslucent(),
                layer != null && layer.hasCrumbling());
        return new RenderLayer1211Descriptor(layer, inspection.texture(), policy);
    }

    private static LayerInspection inspectLayer(RenderLayer layer) {
        if (layer == null
                || layer.getClass() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.getClass()
                || layer.getVertexFormat() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.getVertexFormat()
                || layer.getDrawMode() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.getDrawMode()
                || layer.hasCrumbling() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.hasCrumbling()
                || layer.isTranslucent() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.isTranslucent()
                || layer.isOutline() != ENTITY_CUTOUT_NO_CULL_TEMPLATE.isOutline()
                || layer.getAffectedOutline().isPresent()
                        != ENTITY_CUTOUT_NO_CULL_TEMPLATE.getAffectedOutline().isPresent()) {
            return LayerInspection.unsupported();
        }
        if (!(layer instanceof RenderLayerMultiPhaseAccessor actualLayer)
                || !(ENTITY_CUTOUT_NO_CULL_TEMPLATE instanceof RenderLayerMultiPhaseAccessor templateLayer)) {
            return LayerInspection.unsupported();
        }
        Object actualParameters = actualLayer.threadium$getPhases();
        Object templateParameters = templateLayer.threadium$getPhases();
        if (!(actualParameters instanceof RenderLayerMultiPhaseParametersAccessor actual)
                || !(templateParameters instanceof RenderLayerMultiPhaseParametersAccessor template)) {
            return LayerInspection.unsupported();
        }
        ImmutableList<?> actualPhases = actual.threadium$getPhaseList();
        ImmutableList<?> templatePhases = template.threadium$getPhaseList();
        if (actualPhases.size() != templatePhases.size() || actualPhases.isEmpty()) {
            return LayerInspection.unsupported();
        }
        Object actualTexturePhase = actual.threadium$getTexturePhase();
        Object templateTexturePhase = template.threadium$getTexturePhase();
        if (actualPhases.get(0) != actualTexturePhase
                || templatePhases.get(0) != templateTexturePhase
                || actualTexturePhase.getClass() != templateTexturePhase.getClass()) {
            return LayerInspection.unsupported();
        }
        for (int index = 1; index < actualPhases.size(); index++) {
            if (actualPhases.get(index) != templatePhases.get(index)) return LayerInspection.unsupported();
        }
        if (!(actualTexturePhase instanceof RenderPhaseTextureAccessor texture)
                || texture.threadium$isBlurred()
                || texture.threadium$isMipmapped()) {
            return LayerInspection.unsupported();
        }
        Optional<Identifier> id = texture.threadium$getTextureId();
        return id.map(identifier -> new LayerInspection(true, identifier)).orElseGet(LayerInspection::unsupported);
    }

    private record LayerInspection(boolean exactSupportedLayer, Identifier texture) {
        static LayerInspection unsupported() {
            return new LayerInspection(false, null);
        }
    }
}
