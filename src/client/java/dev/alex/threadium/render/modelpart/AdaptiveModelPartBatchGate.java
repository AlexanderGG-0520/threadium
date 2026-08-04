package dev.alex.threadium.render.modelpart;

import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Frame-delayed exact-identity profitability predictor.
 *
 * <p>The current frame is observed only. A candidate becomes eligible in the next frame when the same stable feature
 * renderer, renderer-owned model root, and RenderType reached the configured minimum. Actual flush efficiency feeds a
 * short cooldown: groups that produced mostly singleton draws temporarily return to vanilla instead of repeatedly
 * paying pose packing, upload, and render-pass overhead without reducing draw calls.
 */
final class AdaptiveModelPartBatchGate {
    static final int UNPROFITABLE_COOLDOWN_FRAMES = 30;

    private Table previous = new Table();
    private Table current = new Table();
    private final IdentityHashMap<Object, GroupFeedback> feedback = new IdentityHashMap<>();

    void beginFrame() {
        Table swap = previous;
        previous = current;
        current = swap;
        current.clear();
        for (GroupFeedback state : feedback.values()) state.beginFrame();
    }

    boolean observeAndShouldReplace(Object groupOwner, Object modelRoot, Object type, int minimumInstances) {
        if (groupOwner == null || modelRoot == null || type == null) return false;
        current.increment(groupOwner, modelRoot, type);
        if (minimumInstances <= 1) return true;
        GroupFeedback state = feedback.get(groupOwner);
        return (state == null || state.cooldownFrames == 0)
                && previous.count(groupOwner, modelRoot, type) >= minimumInstances;
    }

    void recordFlush(Object groupOwner, ModelPartGpuBackend.FlushStats stats) {
        if (groupOwner == null || stats == null || stats.instances() <= 0) return;
        int instances = stats.batchableInstances();
        int draws = stats.batchableDrawCalls();
        if (instances <= 0) return;

        boolean averageBatchAtLeastTwo = draws > 0 && (long) draws * 2L <= instances;
        boolean majorityCoveredByMultiDraws =
                (long) stats.totalInstancesInMultiDraws() * 2L >= instances;
        boolean profitable = averageBatchAtLeastTwo && majorityCoveredByMultiDraws;

        GroupFeedback state = feedback.computeIfAbsent(groupOwner, ignored -> new GroupFeedback());
        if (profitable) {
            state.cooldownFrames = 0;
        } else {
            state.cooldownFrames = UNPROFITABLE_COOLDOWN_FRAMES;
        }
    }

    void clear() {
        previous.clear();
        current.clear();
        feedback.clear();
    }

    int cooldownFrames(Object groupOwner) {
        GroupFeedback state = feedback.get(groupOwner);
        return state == null ? 0 : state.cooldownFrames;
    }

    private static final class GroupFeedback {
        private int cooldownFrames;

        private void beginFrame() {
            if (cooldownFrames > 0) cooldownFrames--;
        }
    }

    private static final class Table {
        private static final int INITIAL_CAPACITY = 32;

        private Object[] groups = new Object[INITIAL_CAPACITY];
        private Object[] roots = new Object[INITIAL_CAPACITY];
        private Object[] types = new Object[INITIAL_CAPACITY];
        private int[] counts = new int[INITIAL_CAPACITY];
        private int size;

        int count(Object group, Object root, Object type) {
            int slot = find(group, root, type);
            return groups[slot] == null ? 0 : counts[slot];
        }

        void increment(Object group, Object root, Object type) {
            if ((size + 1) * 2 > groups.length) grow();
            int slot = find(group, root, type);
            if (groups[slot] == null) {
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
            Arrays.fill(groups, null);
            Arrays.fill(roots, null);
            Arrays.fill(types, null);
            size = 0;
        }

        private int find(Object group, Object root, Object type) {
            int mask = groups.length - 1;
            int slot = mix(group, root, type) & mask;
            while (groups[slot] != null
                    && (groups[slot] != group || roots[slot] != root || types[slot] != type)) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private void grow() {
            Object[] oldGroups = groups;
            Object[] oldRoots = roots;
            Object[] oldTypes = types;
            int[] oldCounts = counts;

            int capacity = Math.multiplyExact(groups.length, 2);
            groups = new Object[capacity];
            roots = new Object[capacity];
            types = new Object[capacity];
            counts = new int[capacity];
            int oldSize = size;
            size = 0;

            for (int i = 0; i < oldGroups.length; i++) {
                Object group = oldGroups[i];
                if (group == null) continue;
                int slot = find(group, oldRoots[i], oldTypes[i]);
                groups[slot] = group;
                roots[slot] = oldRoots[i];
                types[slot] = oldTypes[i];
                counts[slot] = oldCounts[i];
                size++;
            }

            if (size != oldSize) {
                throw new IllegalStateException("Adaptive batch table lost entries while growing");
            }
        }

        private static int mix(Object group, Object root, Object type) {
            int hash = System.identityHashCode(group);
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
