package dev.alex.threadium.render.modelpart;

/** Plans the physical instance-buffer order independently from source queue indices. */
final class InstanceSubmissionOrderPlanner {
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private final Plan result = new Plan();
    private Object[] tableKeys = new Object[0];
    private int[] tableGroups = new int[0];
    private int[] touchedSlots = new int[0];
    private int touchedSlotCount;
    private int[] entryGroups = new int[0];
    private int[] groupStarts = new int[0];
    private int[] writes = new int[0];

    static final class Plan {
        private int[] packedSourceIndices = new int[0];
        private int[] representativeSourceIndices = new int[0];
        private int[] firstPackedInstances = new int[0];
        private int[] instanceCounts = new int[0];
        private int instanceCount;
        private int batchCount;

        int[] packedSourceIndices() {
            return packedSourceIndices;
        }

        int[] representativeSourceIndices() {
            return representativeSourceIndices;
        }

        int[] firstPackedInstances() {
            return firstPackedInstances;
        }

        int[] instanceCounts() {
            return instanceCounts;
        }

        int instanceCount() {
            return instanceCount;
        }

        int batchCount() {
            return batchCount;
        }
    }

    Plan plan(
            int[] sourceIndices,
            int sourceCount,
            Object[] exactKeysBySourceIndex,
            int keyCount,
            int packedBase,
            boolean reorderable,
            boolean consolidate) {
        clearTouchedSlots();
        result.instanceCount = 0;
        result.batchCount = 0;
        if (sourceCount < 0 || sourceCount > sourceIndices.length) {
            throw new IllegalArgumentException("Invalid ModelPart source count: " + sourceCount);
        }
        if (keyCount < 0 || keyCount > exactKeysBySourceIndex.length) {
            throw new IllegalArgumentException("Invalid ModelPart key count: " + keyCount);
        }
        if (packedBase < 0) throw new IllegalArgumentException("packedBase must be non-negative");
        ensureEntryCapacity(sourceCount);
        result.instanceCount = sourceCount;
        try {
            if (sourceCount == 0) return result;
            if (!reorderable || !consolidate) {
                preserveOrder(sourceIndices, sourceCount, exactKeysBySourceIndex, keyCount, packedBase, consolidate);
            } else {
                compactByExactKey(sourceIndices, sourceCount, exactKeysBySourceIndex, keyCount, packedBase);
            }
            return result;
        } catch (Throwable failure) {
            clear();
            throw failure;
        }
    }

    private void preserveOrder(
            int[] sourceIndices,
            int sourceCount,
            Object[] exactKeysBySourceIndex,
            int keyCount,
            int packedBase,
            boolean consolidate) {
        int batchCount = 0;
        Object previousKey = null;
        for (int i = 0; i < sourceCount; i++) {
            int sourceIndex = sourceIndices[i];
            Object key = keyAt(exactKeysBySourceIndex, keyCount, sourceIndex);
            result.packedSourceIndices[i] = sourceIndex;
            if (consolidate && batchCount > 0 && previousKey.equals(key)) {
                result.instanceCounts[batchCount - 1]++;
            } else {
                result.representativeSourceIndices[batchCount] = sourceIndex;
                result.firstPackedInstances[batchCount] = Math.addExact(packedBase, i);
                result.instanceCounts[batchCount] = 1;
                batchCount++;
            }
            previousKey = key;
        }
        result.batchCount = batchCount;
    }

    private void compactByExactKey(
            int[] sourceIndices, int sourceCount, Object[] exactKeysBySourceIndex, int keyCount, int packedBase) {
        prepareTable(sourceCount);
        int groupCount = 0;
        int mask = tableKeys.length - 1;
        for (int i = 0; i < sourceCount; i++) {
            int sourceIndex = sourceIndices[i];
            Object key = keyAt(exactKeysBySourceIndex, keyCount, sourceIndex);
            int slot = spread(key.hashCode()) & mask;
            int group;
            while (true) {
                Object existing = tableKeys[slot];
                if (existing == null) {
                    group = groupCount++;
                    tableKeys[slot] = key;
                    tableGroups[slot] = group;
                    touchedSlots[touchedSlotCount++] = slot;
                    result.representativeSourceIndices[group] = sourceIndex;
                    result.instanceCounts[group] = 0;
                    break;
                }
                if (existing.equals(key)) {
                    group = tableGroups[slot];
                    break;
                }
                slot = (slot + 1) & mask;
            }
            entryGroups[i] = group;
            result.instanceCounts[group]++;
        }

        int next = 0;
        for (int group = 0; group < groupCount; group++) {
            groupStarts[group] = next;
            writes[group] = next;
            result.firstPackedInstances[group] = Math.addExact(packedBase, next);
            next = Math.addExact(next, result.instanceCounts[group]);
        }
        for (int i = 0; i < sourceCount; i++) {
            int group = entryGroups[i];
            result.packedSourceIndices[writes[group]++] = sourceIndices[i];
        }
        result.batchCount = groupCount;
    }

    private void ensureEntryCapacity(int required) {
        if (result.packedSourceIndices.length >= required) return;
        int capacity = growCapacity(result.packedSourceIndices.length, required);
        result.packedSourceIndices = new int[capacity];
        result.representativeSourceIndices = new int[capacity];
        result.firstPackedInstances = new int[capacity];
        result.instanceCounts = new int[capacity];
        entryGroups = new int[capacity];
        groupStarts = new int[capacity];
        writes = new int[capacity];
    }

    private void prepareTable(int sourceCount) {
        int required = tableCapacity(sourceCount);
        if (tableKeys.length < required) {
            tableKeys = new Object[required];
            tableGroups = new int[required];
            touchedSlots = new int[required];
            touchedSlotCount = 0;
        }
    }

    private void clearTouchedSlots() {
        for (int i = 0; i < touchedSlotCount; i++) tableKeys[touchedSlots[i]] = null;
        touchedSlotCount = 0;
    }

    private static Object keyAt(Object[] keys, int keyCount, int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= keyCount) {
            throw new IllegalArgumentException("ModelPart source index out of range: " + sourceIndex);
        }
        Object key = keys[sourceIndex];
        if (key == null)
            throw new IllegalArgumentException("Missing ModelPart exact key at source index " + sourceIndex);
        return key;
    }

    private static int tableCapacity(int count) {
        if (count > (1 << 29)) throw new IllegalArgumentException("Too many ModelPart instances: " + count);
        int required = Math.max(2, count << 1);
        int capacity = 1;
        while (capacity < required) capacity <<= 1;
        return capacity;
    }

    private static int growCapacity(int current, int required) {
        if (required < 0 || required > MAX_ARRAY_SIZE) {
            throw new IllegalArgumentException("Too many ModelPart instances: " + required);
        }
        int grown = current + (current >> 1) + 1;
        return Math.max(required, Math.min(MAX_ARRAY_SIZE, grown));
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    int packedCapacity() {
        return result.packedSourceIndices.length;
    }

    int[] packedBackingArray() {
        return result.packedSourceIndices;
    }

    Object[] tableBackingArray() {
        return tableKeys;
    }

    void clear() {
        clearTouchedSlots();
        result.instanceCount = 0;
        result.batchCount = 0;
    }
}
