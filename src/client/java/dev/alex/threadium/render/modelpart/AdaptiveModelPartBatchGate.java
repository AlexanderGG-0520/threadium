package dev.alex.threadium.render.modelpart;

import java.util.Arrays;

/**
 * Frame-delayed exact-identity profitability predictor.
 *
 * <p>The current frame is observed only. A candidate becomes eligible in the next frame when the same render group,
 * renderer-owned model root, and RenderType reached the configured minimum. This keeps the replacement decision ahead
 * of topology lookup, pose extraction, buffer packing, and GPU upload without allocating a key per invocation.
 */
final class AdaptiveModelPartBatchGate {
    private Table previous = new Table();
    private Table current = new Table();
    private int nextGroupOrdinal;

    void beginFrame() {
        Table swap = previous;
        previous = current;
        current = swap;
        current.clear();
        nextGroupOrdinal = 0;
    }

    int nextGroupOrdinal() {
        return nextGroupOrdinal++;
    }

    boolean observeAndShouldReplace(int groupOrdinal, Object modelRoot, Object type, int minimumInstances) {
        current.increment(groupOrdinal, modelRoot, type);
        return minimumInstances <= 1 || previous.count(groupOrdinal, modelRoot, type) >= minimumInstances;
    }

    void clear() {
        previous.clear();
        current.clear();
        nextGroupOrdinal = 0;
    }

    private static final class Table {
        private static final int INITIAL_CAPACITY = 32;

        private int[] groups = new int[INITIAL_CAPACITY];
        private Object[] roots = new Object[INITIAL_CAPACITY];
        private Object[] types = new Object[INITIAL_CAPACITY];
        private int[] counts = new int[INITIAL_CAPACITY];
        private int size;

        int count(int group, Object root, Object type) {
            int slot = find(group, root, type);
            return roots[slot] == null ? 0 : counts[slot];
        }

        void increment(int group, Object root, Object type) {
            if ((size + 1) * 2 > roots.length) grow();
            int slot = find(group, root, type);
            if (roots[slot] == null) {
                groups[slot] = group;
                roots[slot] = root;
                types[slot] = type;
                counts[slot] = 1;
                size++;
            } else {
                counts[slot]++;
            }
        }

        void clear() {
            Arrays.fill(roots, null);
            Arrays.fill(types, null);
            size = 0;
        }

        private int find(int group, Object root, Object type) {
            int mask = roots.length - 1;
            int slot = mix(group, root, type) & mask;
            while (roots[slot] != null && (groups[slot] != group || roots[slot] != root || types[slot] != type)) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private void grow() {
            int[] oldGroups = groups;
            Object[] oldRoots = roots;
            Object[] oldTypes = types;
            int[] oldCounts = counts;

            int capacity = Math.multiplyExact(roots.length, 2);
            groups = new int[capacity];
            roots = new Object[capacity];
            types = new Object[capacity];
            counts = new int[capacity];
            int oldSize = size;
            size = 0;

            for (int i = 0; i < oldRoots.length; i++) {
                Object root = oldRoots[i];
                if (root == null) continue;
                int slot = find(oldGroups[i], root, oldTypes[i]);
                groups[slot] = oldGroups[i];
                roots[slot] = root;
                types[slot] = oldTypes[i];
                counts[slot] = oldCounts[i];
                size++;
            }

            if (size != oldSize) {
                throw new IllegalStateException("Adaptive batch table lost entries while growing");
            }
        }

        private static int mix(int group, Object root, Object type) {
            int hash = group;
            hash = 31 * hash + System.identityHashCode(root);
            hash = 31 * hash + System.identityHashCode(type);
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            hash ^= hash >>> 15;
            hash *= 0x846ca68b;
            return hash ^ (hash >>> 16);
        }
    }
}
