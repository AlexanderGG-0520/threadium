package dev.alex.threadium.render.modelpart;

import java.util.IdentityHashMap;
import net.minecraft.client.model.geom.ModelPart;

/** Render-thread-owned cache keyed by the renderer's shared model root identity. */
final class ModelPartTopologyCache {
    private final IdentityHashMap<ModelPart, GenericModelPartTopology> entries = new IdentityHashMap<>();

    GenericModelPartTopology get(ModelPart root) {
        return entries.get(root);
    }

    void put(ModelPart root, GenericModelPartTopology topology) {
        entries.put(root, topology);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
