package dev.alex.threadium.render.modelpart;

import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fc;

/** Fixed-capacity, frame-local structure-of-arrays queue for ModelPart instances. */
final class QueuedModelPartArena {
    static final int ROOT_MATRIX_COMPONENTS = 16;

    private final int capacity;
    private final ModelPartBatchKey[] keys;
    private final ModelPartGpuBackend.MeshHandle[] meshes;
    private final RenderType[] types;
    private final PreparedRenderType[] prepared;
    private final ModelPartBoneData[] bones;
    private final ModelPartUvTransform[] uvTransforms;
    private final ModelPartDecalTransform[] decalTransforms;
    private final int[] lights;
    private final int[] overlays;
    private final int[] tints;
    private final int[] decalBases;
    private final float[] rootMatrices;
    private int head;
    private int size;

    QueuedModelPartArena(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        keys = new ModelPartBatchKey[capacity];
        meshes = new ModelPartGpuBackend.MeshHandle[capacity];
        types = new RenderType[capacity];
        prepared = new PreparedRenderType[capacity];
        bones = new ModelPartBoneData[capacity];
        uvTransforms = new ModelPartUvTransform[capacity];
        decalTransforms = new ModelPartDecalTransform[capacity];
        lights = new int[capacity];
        overlays = new int[capacity];
        tints = new int[capacity];
        decalBases = new int[capacity];
        rootMatrices = new float[Math.multiplyExact(capacity, ROOT_MATRIX_COMPONENTS)];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean isFull() {
        return size == capacity;
    }

    void add(
            ModelPartBatchKey key,
            ModelPartGpuBackend.MeshHandle mesh,
            RenderType type,
            PreparedRenderType preparedRenderType,
            Matrix4fc rootPose,
            ModelPartBoneData boneData,
            int light,
            int overlay,
            int tint,
            ModelPartUvTransform uvTransform,
            ModelPartDecalTransform decalTransform,
            int decalBase) {
        if (isFull()) throw new IllegalStateException("ModelPart queue arena is full");
        int slot = physical(size);
        keys[slot] = key;
        meshes[slot] = mesh;
        types[slot] = type;
        prepared[slot] = preparedRenderType;
        bones[slot] = boneData;
        lights[slot] = light;
        overlays[slot] = overlay;
        tints[slot] = tint;
        uvTransforms[slot] = uvTransform;
        decalTransforms[slot] = decalTransform;
        decalBases[slot] = decalBase;
        copyRootPose(rootPose, slot * ROOT_MATRIX_COMPONENTS);
        size++;
    }

    ModelPartBatchKey key(int index) {
        return keys[physicalChecked(index)];
    }

    ModelPartGpuBackend.MeshHandle mesh(int index) {
        return meshes[physicalChecked(index)];
    }

    RenderType type(int index) {
        return types[physicalChecked(index)];
    }

    PreparedRenderType prepared(int index) {
        return prepared[physicalChecked(index)];
    }

    ModelPartBoneData bones(int index) {
        return bones[physicalChecked(index)];
    }

    int light(int index) {
        return lights[physicalChecked(index)];
    }

    int overlay(int index) {
        return overlays[physicalChecked(index)];
    }

    int tint(int index) {
        return tints[physicalChecked(index)];
    }

    ModelPartUvTransform uvTransform(int index) {
        return uvTransforms[physicalChecked(index)];
    }

    ModelPartDecalTransform decalTransform(int index) {
        return decalTransforms[physicalChecked(index)];
    }

    int decalBase(int index) {
        return decalBases[physicalChecked(index)];
    }

    int rootMatrixOffset(int index) {
        return physicalChecked(index) * ROOT_MATRIX_COMPONENTS;
    }

    float[] rootMatrices() {
        return rootMatrices;
    }

    float rootMatrixComponent(int index, int component) {
        if (component < 0 || component >= ROOT_MATRIX_COMPONENTS) throw new IndexOutOfBoundsException(component);
        return rootMatrices[rootMatrixOffset(index) + component];
    }

    void removePrefix(int count) {
        if (count < 0 || count > size) throw new IndexOutOfBoundsException(count);
        for (int i = 0; i < count; i++) clearSlot(physical(i));
        head = (head + count) % capacity;
        size -= count;
        if (size == 0) head = 0;
    }

    void clear() {
        if (size == 0) return;
        for (int i = 0; i < size; i++) clearSlot(physical(i));
        head = 0;
        size = 0;
    }

    private int physicalChecked(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return physical(index);
    }

    private int physical(int logicalIndex) {
        int slot = head + logicalIndex;
        return slot >= capacity ? slot - capacity : slot;
    }

    private void clearSlot(int slot) {
        keys[slot] = null;
        meshes[slot] = null;
        types[slot] = null;
        prepared[slot] = null;
        bones[slot] = null;
        uvTransforms[slot] = null;
        decalTransforms[slot] = null;
    }

    private void copyRootPose(Matrix4fc matrix, int offset) {
        rootMatrices[offset] = matrix.m00();
        rootMatrices[offset + 1] = matrix.m01();
        rootMatrices[offset + 2] = matrix.m02();
        rootMatrices[offset + 3] = matrix.m03();
        rootMatrices[offset + 4] = matrix.m10();
        rootMatrices[offset + 5] = matrix.m11();
        rootMatrices[offset + 6] = matrix.m12();
        rootMatrices[offset + 7] = matrix.m13();
        rootMatrices[offset + 8] = matrix.m20();
        rootMatrices[offset + 9] = matrix.m21();
        rootMatrices[offset + 10] = matrix.m22();
        rootMatrices[offset + 11] = matrix.m23();
        rootMatrices[offset + 12] = matrix.m30();
        rootMatrices[offset + 13] = matrix.m31();
        rootMatrices[offset + 14] = matrix.m32();
        rootMatrices[offset + 15] = matrix.m33();
    }
}
