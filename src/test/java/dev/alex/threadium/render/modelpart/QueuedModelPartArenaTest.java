package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class QueuedModelPartArenaTest {
    @Test
    void copiesRootPoseWithoutRetainingMatrixObject() {
        QueuedModelPartArena arena = new QueuedModelPartArena(2);
        Matrix4f source = new Matrix4f().translate(3, 4, 5);
        ModelPartBoneData bones = bones(1);
        ModelPartGpuBackend.MeshHandle mesh = mesh(1);

        add(arena, mesh, source, bones, 7);
        source.identity().translate(30, 40, 50);

        assertEquals(3, arena.rootMatrixComponent(0, 12));
        assertEquals(4, arena.rootMatrixComponent(0, 13));
        assertEquals(5, arena.rootMatrixComponent(0, 14));
        assertSame(mesh, arena.mesh(0));
        assertSame(bones, arena.bones(0));
        assertEquals(7, arena.tint(0));
    }

    @Test
    void boundedArenaReusesCircularSlots() {
        QueuedModelPartArena arena = new QueuedModelPartArena(2);
        ModelPartGpuBackend.MeshHandle first = mesh(1);
        ModelPartGpuBackend.MeshHandle second = mesh(2);
        ModelPartGpuBackend.MeshHandle third = mesh(3);

        add(arena, first, new Matrix4f().translate(1, 0, 0), bones(1), 1);
        add(arena, second, new Matrix4f().translate(2, 0, 0), bones(2), 2);
        assertTrue(arena.isFull());
        assertThrows(
                IllegalStateException.class, () -> add(arena, third, new Matrix4f().translate(3, 0, 0), bones(3), 3));

        arena.removePrefix(1);
        assertFalse(arena.isFull());
        add(arena, third, new Matrix4f().translate(3, 0, 0), bones(3), 3);

        assertSame(second, arena.mesh(0));
        assertSame(third, arena.mesh(1));
        assertEquals(2, arena.rootMatrixComponent(0, 12));
        assertEquals(3, arena.rootMatrixComponent(1, 12));
    }

    @Test
    void clearDropsObjectReferencesAndRestartsFromZero() {
        QueuedModelPartArena arena = new QueuedModelPartArena(1);
        ModelPartGpuBackend.MeshHandle first = mesh(1);
        add(arena, first, new Matrix4f(), bones(1), 1);

        arena.clear();

        assertTrue(arena.isEmpty());
        ModelPartGpuBackend.MeshHandle second = mesh(2);
        add(arena, second, new Matrix4f().translate(2, 0, 0), bones(2), 2);
        assertSame(second, arena.mesh(0));
        assertEquals(2, arena.rootMatrixComponent(0, 12));
    }

    private static void add(
            QueuedModelPartArena arena,
            ModelPartGpuBackend.MeshHandle mesh,
            Matrix4f pose,
            ModelPartBoneData bones,
            int tint) {
        arena.add(null, mesh, null, null, pose, bones, 10, 20, tint, ModelPartUvTransform.IDENTITY, null, -1);
    }

    private static ModelPartGpuBackend.MeshHandle mesh(int token) {
        return new ModelPartGpuBackend.MeshHandle(token, 0, 0, 6, 1, 0);
    }

    private static ModelPartBoneData bones(int value) {
        return new ModelPartBoneData(new float[] {value}, new long[] {1});
    }
}
