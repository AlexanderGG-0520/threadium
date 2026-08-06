package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import java.util.Arrays;

/** Growable, bounded structure-of-arrays queue for Minecraft 1.21.1 GPU ModelPart instances. */
final class QueuedModelPartArena1211<M> {
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private final int maximumCapacity;
    private Object[] groupOwners;
    private Object[] meshes;
    private RenderLayer1211Descriptor[] descriptors;
    private ImmutableModelPartBonePose[] poses;
    private ImmutableRootRenderTransform[] roots;
    private int[] lights;
    private int[] overlays;
    private int[] colors;
    private ModelPartDecalTransform1211[] decals;
    private long[] worldGenerations;
    private long[] resourceGenerations;
    private int size;

    QueuedModelPartArena1211(int initialCapacity, int maximumCapacity) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("initialCapacity must be positive");
        if (maximumCapacity < initialCapacity) {
            throw new IllegalArgumentException("maximumCapacity must be at least initialCapacity");
        }
        this.maximumCapacity = maximumCapacity;
        allocate(initialCapacity);
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void add(
            Object groupOwner,
            M mesh,
            RenderLayer1211Descriptor descriptor,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int light,
            int overlay,
            int color,
            ModelPartDecalTransform1211 decal,
            long worldGeneration,
            long resourceGeneration) {
        ensureCapacity(Math.addExact(size, 1));
        int index = size++;
        groupOwners[index] = groupOwner;
        meshes[index] = mesh;
        descriptors[index] = descriptor;
        poses[index] = pose;
        roots[index] = root;
        lights[index] = light;
        overlays[index] = overlay;
        colors[index] = color;
        decals[index] = decal;
        worldGenerations[index] = worldGeneration;
        resourceGenerations[index] = resourceGeneration;
    }

    Object groupOwner(int index) {
        return groupOwners[checked(index)];
    }

    @SuppressWarnings("unchecked")
    M mesh(int index) {
        return (M) meshes[checked(index)];
    }

    RenderLayer1211Descriptor descriptor(int index) {
        return descriptors[checked(index)];
    }

    ImmutableModelPartBonePose pose(int index) {
        return poses[checked(index)];
    }

    ImmutableRootRenderTransform root(int index) {
        return roots[checked(index)];
    }

    int light(int index) {
        return lights[checked(index)];
    }

    int overlay(int index) {
        return overlays[checked(index)];
    }

    int color(int index) {
        return colors[checked(index)];
    }

    ModelPartDecalTransform1211 decal(int index) {
        return decals[checked(index)];
    }

    long worldGeneration(int index) {
        return worldGenerations[checked(index)];
    }

    long resourceGeneration(int index) {
        return resourceGenerations[checked(index)];
    }

    void truncate(int newSize) {
        if (newSize < 0 || newSize > size) throw new IndexOutOfBoundsException(newSize);
        clearReferences(newSize, size);
        size = newSize;
    }

    void clear() {
        clearReferences(0, size);
        size = 0;
    }

    int capacity() {
        return groupOwners.length;
    }

    private int checked(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return index;
    }

    private void ensureCapacity(int required) {
        if (required <= groupOwners.length) return;
        if (required > maximumCapacity || required > MAX_ARRAY_SIZE) {
            throw new IllegalStateException("Minecraft 1.21.1 ModelPart queue arena capacity exceeded");
        }
        int grown = groupOwners.length + (groupOwners.length >> 1) + 1;
        int capacity = Math.min(maximumCapacity, Math.max(required, grown));
        groupOwners = Arrays.copyOf(groupOwners, capacity);
        meshes = Arrays.copyOf(meshes, capacity);
        descriptors = Arrays.copyOf(descriptors, capacity);
        poses = Arrays.copyOf(poses, capacity);
        roots = Arrays.copyOf(roots, capacity);
        lights = Arrays.copyOf(lights, capacity);
        overlays = Arrays.copyOf(overlays, capacity);
        colors = Arrays.copyOf(colors, capacity);
        decals = Arrays.copyOf(decals, capacity);
        worldGenerations = Arrays.copyOf(worldGenerations, capacity);
        resourceGenerations = Arrays.copyOf(resourceGenerations, capacity);
    }

    private void allocate(int capacity) {
        groupOwners = new Object[capacity];
        meshes = new Object[capacity];
        descriptors = new RenderLayer1211Descriptor[capacity];
        poses = new ImmutableModelPartBonePose[capacity];
        roots = new ImmutableRootRenderTransform[capacity];
        lights = new int[capacity];
        overlays = new int[capacity];
        colors = new int[capacity];
        decals = new ModelPartDecalTransform1211[capacity];
        worldGenerations = new long[capacity];
        resourceGenerations = new long[capacity];
    }

    private void clearReferences(int from, int to) {
        Arrays.fill(groupOwners, from, to, null);
        Arrays.fill(meshes, from, to, null);
        Arrays.fill(descriptors, from, to, null);
        Arrays.fill(poses, from, to, null);
        Arrays.fill(roots, from, to, null);
        Arrays.fill(decals, from, to, null);
    }
}
