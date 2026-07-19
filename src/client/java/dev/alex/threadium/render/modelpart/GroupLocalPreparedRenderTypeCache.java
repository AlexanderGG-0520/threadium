package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

/** One consecutive identity only; the owner clears it at every group, frame, generation, and lifecycle boundary. */
final class GroupLocalPreparedRenderTypeCache {
    private RenderType type;
    private ModelPartPipelineDescriptor descriptor;
    private RenderPipeline source;
    private PreparedRenderType prepared;
    private long generation;
    private long groupId;

    void observe(RenderType nextType) {
        if (type != null && type != nextType) clear();
    }

    PreparedRenderType reuse(
            RenderType requestedType,
            ModelPartPipelineDescriptor requestedDescriptor,
            RenderPipeline requestedSource,
            long requestedGeneration,
            long requestedGroupId) {
        if (type != requestedType) return null;
        if (descriptor != requestedDescriptor
                || source != requestedSource
                || generation != requestedGeneration
                || groupId != requestedGroupId
                || prepared == null
                || prepared.pipeline() != requestedSource) {
            clear();
            return null;
        }
        return prepared;
    }

    boolean install(
            RenderType nextType,
            ModelPartPipelineDescriptor nextDescriptor,
            RenderPipeline nextSource,
            PreparedRenderType nextPrepared,
            long nextGeneration,
            long nextGroupId) {
        if (nextPrepared == null || nextPrepared.pipeline() != nextSource) {
            clear();
            return false;
        }
        type = nextType;
        descriptor = nextDescriptor;
        source = nextSource;
        prepared = nextPrepared;
        generation = nextGeneration;
        groupId = nextGroupId;
        return true;
    }

    void clear() {
        type = null;
        descriptor = null;
        source = null;
        prepared = null;
        generation = 0L;
        groupId = 0L;
    }

    boolean populated() {
        return type != null;
    }
}
