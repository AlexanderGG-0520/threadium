package dev.alex.threadium.render.modelpart.structure;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Capture-only consumer for Cuboid.renderCuboid's verified packed-vertex protocol. The inherited packed method
 * decomposes each call into these six ordered element methods; no real render consumer is wrapped or delegated to.
 */
final class VanillaCuboidCaptureConsumer implements VertexConsumer {
    private final ModelPartMeshCapture capture;

    VanillaCuboidCaptureConsumer(ModelPartMeshCapture capture) {
        this.capture = capture;
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        capture.position(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        capture.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        capture.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        capture.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        capture.light(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        capture.normal(x, y, z);
        return this;
    }

    @Override
    public void quad(
            MatrixStack.Entry entry,
            BakedQuad quad,
            float red,
            float green,
            float blue,
            float alpha,
            int light,
            int overlay) {
        throw new UnsupportedOperationException("Bulk quad emission is not supported by ModelPart cuboid capture");
    }

    @Override
    public void quad(
            MatrixStack.Entry entry,
            BakedQuad quad,
            float[] brightness,
            float red,
            float green,
            float blue,
            float alpha,
            int[] lights,
            int overlay,
            boolean useQuadColorData) {
        throw new UnsupportedOperationException("Bulk quad emission is not supported by ModelPart cuboid capture");
    }
}
