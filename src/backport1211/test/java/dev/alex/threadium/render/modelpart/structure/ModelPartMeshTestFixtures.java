package dev.alex.threadium.render.modelpart.structure;

import java.util.ArrayList;
import java.util.List;

final class ModelPartMeshTestFixtures {
    static final ModelPartMeshCapture.Limits DEFAULT_LIMITS =
            new ModelPartMeshCapture.Limits(16, 64, 256, 384, 64 * 1024);

    private ModelPartMeshTestFixtures() {}

    static ModelPartStructureSnapshot structure(int... cuboidCounts) {
        ArrayList<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        for (int index = 0; index < cuboidCounts.length; index++) {
            nodes.add(new ModelPartStructureSnapshot.Node(
                    index, index == 0 ? -1 : 0, index == 0 ? "root" : "child-" + index, cuboidCounts[index]));
        }
        return ModelPartStructureSnapshot.of(nodes);
    }

    static ImmutableModelPartMesh oneQuadMesh(float offset) {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        capture.beginNode(0);
        emitQuad(capture, offset, 0x10203040, 0x50607080, 0x90a0b0c0, 0.0F, 0.0F, 1.0F);
        capture.endCuboid();
        return capture.complete(structure(1));
    }

    static void emitQuad(
            ModelPartMeshCapture capture,
            float offset,
            int color,
            int overlay,
            int light,
            float normalX,
            float normalY,
            float normalZ) {
        emitVertex(capture, offset, 0, 0, 0, 0, color, overlay, light, normalX, normalY, normalZ);
        emitVertex(capture, offset + 1, 0, 0, 1, 0, color, overlay, light, normalX, normalY, normalZ);
        emitVertex(capture, offset + 1, 1, 0, 1, 1, color, overlay, light, normalX, normalY, normalZ);
        emitVertex(capture, offset, 1, 0, 0, 1, color, overlay, light, normalX, normalY, normalZ);
    }

    static void emitVertex(
            ModelPartMeshCapture capture,
            float x,
            float y,
            float z,
            float u,
            float v,
            int color,
            int overlay,
            int light,
            float normalX,
            float normalY,
            float normalZ) {
        capture.position(x, y, z);
        capture.color(color, color >>> 8, color >>> 16, color >>> 24);
        capture.texture(u, v);
        capture.overlay(overlay, overlay >>> 16);
        capture.light(light, light >>> 16);
        capture.normal(normalX, normalY, normalZ);
    }

    static List<Integer> boneIndices(ImmutableModelPartMesh mesh) {
        ArrayList<Integer> bones = new ArrayList<>();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            bones.add(mesh.vertexFieldBits(vertex, ImmutableModelPartMesh.BONE_INDEX));
        }
        return bones;
    }
}
