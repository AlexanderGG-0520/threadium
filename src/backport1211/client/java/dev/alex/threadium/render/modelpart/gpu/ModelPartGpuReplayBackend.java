package dev.alex.threadium.render.modelpart.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.render.modelpart.replay.PreparedModelPartReplay;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;

/**
 * Experimental render-thread-owned GPU backend for the first Minecraft 1.21.1 opaque replacement path.
 *
 * <p>Prepared replays are grouped by the exact Vanilla provider and RenderLayer identity. The provider's private draw
 * boundary flushes the group into a Threadium-owned BufferBuilder, uploads it to a reusable dynamic VBO, and submits it
 * under the exact RenderLayer state.
 */
public final class ModelPartGpuReplayBackend implements AutoCloseable {
    private static final boolean CONFIGURED = Boolean.getBoolean("threadium.backport1211.gpu");
    private static final int BUFFER_CAPACITY = 4 * 1024 * 1024;
    private static final int MAXIMUM_QUEUED_REPLAYS = 4_096;
    private static final int MAXIMUM_QUEUED_VERTICES = 1_048_576;

    private final Tessellator tessellator = new Tessellator(BUFFER_CAPACITY);
    private final IdentityHashMap<Object, IdentityHashMap<RenderLayer, Batch>> queued = new IdentityHashMap<>();
    private VertexBuffer vertexBuffer;
    private int queuedReplays;
    private int queuedVertices;
    private long flushes;
    private long drawCalls;
    private long uploadedVertices;
    private boolean closed;

    public static boolean configured() {
        return CONFIGURED;
    }

    public boolean queue(Object provider, RenderLayer layer, PreparedModelPartReplay replay) {
        requireOpenRenderThread();
        if (!CONFIGURED
                || !(provider instanceof VertexConsumerProvider.Immediate)
                || layer == null
                || replay == null
                || replay.vertexCount() == 0) {
            return false;
        }
        int nextReplays = Math.addExact(queuedReplays, 1);
        int nextVertices = Math.addExact(queuedVertices, replay.vertexCount());
        if (nextReplays > MAXIMUM_QUEUED_REPLAYS || nextVertices > MAXIMUM_QUEUED_VERTICES) return false;
        IdentityHashMap<RenderLayer, Batch> providerBatches =
                queued.computeIfAbsent(provider, ignored -> new IdentityHashMap<>());
        providerBatches.computeIfAbsent(layer, ignored -> new Batch()).replays.add(replay);
        queuedReplays = nextReplays;
        queuedVertices = nextVertices;
        return true;
    }

    public boolean flush(Object provider, RenderLayer layer) {
        requireOpenRenderThread();
        IdentityHashMap<RenderLayer, Batch> providerBatches = queued.get(provider);
        if (providerBatches == null) return false;
        Batch batch = providerBatches.remove(layer);
        if (batch == null) return false;
        if (providerBatches.isEmpty()) queued.remove(provider);
        for (PreparedModelPartReplay replay : batch.replays) {
            queuedReplays--;
            queuedVertices -= replay.vertexCount();
        }
        drawBatch(layer, batch.replays);
        return true;
    }

    public void verifyFrameDrained() {
        requireOpenRenderThread();
        if (queuedReplays != 0 || queuedVertices != 0 || !queued.isEmpty()) {
            clearQueued();
            throw new IllegalStateException("Threadium GPU replay queue crossed a frame boundary without flushing");
        }
    }

    public void reset() {
        requireOpenRenderThread();
        clearQueued();
        tessellator.clear();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(queuedReplays, queuedVertices, flushes, drawCalls, uploadedVertices);
    }

    @Override
    public void close() {
        if (closed) return;
        requireRenderThread();
        reset();
        closed = true;
    }

    private void drawBatch(RenderLayer layer, List<PreparedModelPartReplay> replays) {
        BufferBuilder builder = tessellator.begin(layer.getDrawMode(), layer.getVertexFormat());
        ThreadiumReplayVertexConsumer consumer = new ThreadiumReplayVertexConsumer(builder);
        int vertices = 0;
        for (PreparedModelPartReplay replay : replays) {
            consumer.replay(replay);
            vertices = Math.addExact(vertices, replay.vertexCount());
        }
        BuiltBuffer built = builder.endNullable();
        if (built == null) return;
        boolean layerStarted = false;
        try (built) {
            VertexBuffer buffer = vertexBuffer();
            layer.startDrawing();
            layerStarted = true;
            buffer.bind();
            try {
                buffer.upload(built);
                buffer.draw();
            } finally {
                VertexBuffer.unbind();
            }
            flushes++;
            drawCalls++;
            uploadedVertices = Math.addExact(uploadedVertices, vertices);
        } finally {
            if (layerStarted) layer.endDrawing();
        }
    }

    private VertexBuffer vertexBuffer() {
        if (vertexBuffer == null || vertexBuffer.isClosed()) {
            vertexBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        return vertexBuffer;
    }

    private void clearQueued() {
        queued.clear();
        queuedReplays = 0;
        queuedVertices = 0;
    }

    private void requireOpenRenderThread() {
        if (closed) throw new IllegalStateException("Threadium GPU replay backend is closed");
        requireRenderThread();
    }

    private static void requireRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Threadium GPU replay backend is not on the Render Thread");
        }
    }

    private static final class Batch {
        private final ArrayList<PreparedModelPartReplay> replays = new ArrayList<>();
    }

    public record Diagnostics(
            int queuedReplays, int queuedVertices, long flushes, long drawCalls, long uploadedVertices) {}
}
