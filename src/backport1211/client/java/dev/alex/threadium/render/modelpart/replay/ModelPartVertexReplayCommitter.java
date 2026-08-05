package dev.alex.threadium.render.modelpart.replay;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;

/** Commits a completely prepared replay to a proven Vanilla BufferBuilder. */
public final class ModelPartVertexReplayCommitter {
    private ModelPartVertexReplayCommitter() {}

    public static boolean supports(VertexConsumer consumer) {
        return consumer != null && consumer.getClass() == BufferBuilder.class;
    }

    public static void commit(PreparedModelPartReplay replay, VertexConsumer consumer) {
        if (!supports(consumer)) {
            throw new IllegalArgumentException("ModelPart replay destination is not the exact Vanilla BufferBuilder");
        }
        for (int vertex = 0; vertex < replay.vertexCount(); vertex++) {
            consumer.vertex(
                    replay.field(vertex, PreparedModelPartReplay.POSITION_X),
                    replay.field(vertex, PreparedModelPartReplay.POSITION_Y),
                    replay.field(vertex, PreparedModelPartReplay.POSITION_Z),
                    replay.color(),
                    replay.field(vertex, PreparedModelPartReplay.TEXTURE_U),
                    replay.field(vertex, PreparedModelPartReplay.TEXTURE_V),
                    replay.overlay(),
                    replay.light(),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_X),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_Y),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_Z));
        }
    }
}
