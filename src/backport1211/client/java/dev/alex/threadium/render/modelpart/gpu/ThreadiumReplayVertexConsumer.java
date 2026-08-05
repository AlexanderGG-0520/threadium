package dev.alex.threadium.render.modelpart.gpu;

import dev.alex.threadium.render.modelpart.replay.PreparedModelPartReplay;
import java.util.Objects;
import net.minecraft.client.render.VertexConsumer;

/** Threadium-owned wrapper that isolates replay emission from Vanilla's original consumer. */
public final class ThreadiumReplayVertexConsumer implements VertexConsumer {
    private static final float CHANNEL_SCALE = 1.0F / 255.0F;
    private final VertexConsumer delegate;

    public ThreadiumReplayVertexConsumer(VertexConsumer delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void replay(PreparedModelPartReplay replay) {
        Objects.requireNonNull(replay, "replay");
        int color = replay.color();
        float alpha = (color >>> 24 & 0xFF) * CHANNEL_SCALE;
        float red = (color >>> 16 & 0xFF) * CHANNEL_SCALE;
        float green = (color >>> 8 & 0xFF) * CHANNEL_SCALE;
        float blue = (color & 0xFF) * CHANNEL_SCALE;
        for (int vertex = 0; vertex < replay.vertexCount(); vertex++) {
            delegate.vertex(
                    replay.field(vertex, PreparedModelPartReplay.POSITION_X),
                    replay.field(vertex, PreparedModelPartReplay.POSITION_Y),
                    replay.field(vertex, PreparedModelPartReplay.POSITION_Z),
                    red,
                    green,
                    blue,
                    alpha,
                    replay.field(vertex, PreparedModelPartReplay.TEXTURE_U),
                    replay.field(vertex, PreparedModelPartReplay.TEXTURE_V),
                    replay.overlay(),
                    replay.light(),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_X),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_Y),
                    replay.field(vertex, PreparedModelPartReplay.NORMAL_Z));
        }
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        delegate.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        delegate.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        delegate.light(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }
}
