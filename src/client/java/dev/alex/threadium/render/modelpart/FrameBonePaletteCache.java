package dev.alex.threadium.render.modelpart;

import java.util.Arrays;
import java.util.IdentityHashMap;

/** Render-thread-owned exact pose cache. Palette objects are valid for one frame only. */
final class FrameBonePaletteCache {
    static final int MISS_PROBE_LIMIT = 16;

    private final IdentityHashMap<GenericModelPartTopology, ReuseProbe> reuseProbes = new IdentityHashMap<>();
    private int[] table = new int[16];
    private long[] hashes = new long[8];
    private GenericModelPartTopology[] topologies = new GenericModelPartTopology[8];
    private int[] offsets = new int[8];
    private int[] lengths = new int[8];
    private ModelPartBoneData[] palettes = new ModelPartBoneData[8];
    private int[] storedBits = new int[128];
    private int[] scratch = new int[128];
    private int entries;
    private int storedCount;
    private GenericModelPartTopology recentProbeTopology;
    private ReuseProbe recentProbe;
    private long capturedHash;
    private GenericModelPartTopology capturedTopology;
    private int capturedLength;
    private boolean lastLookupPerformed;
    private boolean capturedForStore;

    ModelPartBoneData find(GenericModelPartTopology topology) {
        ReuseProbe probe = probe(topology);
        if (probe.bypass) return bypassLookup();
        capture(topology);
        return finishLookup(probe, capturedHash);
    }

    ModelPartBoneData findWithHashForTest(GenericModelPartTopology topology, long hash) {
        ReuseProbe probe = probe(topology);
        if (probe.bypass) return bypassLookup();
        capture(topology);
        capturedHash = hash;
        return finishLookup(probe, hash);
    }

    private ModelPartBoneData bypassLookup() {
        lastLookupPerformed = false;
        capturedForStore = false;
        return null;
    }

    private ModelPartBoneData finishLookup(ReuseProbe probe, long hash) {
        lastLookupPerformed = true;
        ModelPartBoneData found = findCaptured(hash);
        if (found != null) {
            probe.reuseConfirmed = true;
            capturedForStore = false;
            return found;
        }
        probe.misses++;
        // A confirmed hit keeps exact dedup active. Otherwise bound unique-pose probing per frame.
        capturedForStore = probe.reuseConfirmed || probe.misses < MISS_PROBE_LIMIT;
        if (!capturedForStore) probe.bypass = true;
        return null;
    }

    boolean lastLookupPerformed() {
        return lastLookupPerformed;
    }

    boolean store(ModelPartBoneData palette) {
        if (!capturedForStore) return false;
        if ((entries + 1) * 2 > table.length) rehash(table.length * 2);
        ensureEntries(entries + 1);
        ensureStored(storedCount + capturedLength);
        int entry = entries++;
        hashes[entry] = capturedHash;
        topologies[entry] = capturedTopology;
        offsets[entry] = storedCount;
        lengths[entry] = capturedLength;
        palettes[entry] = palette;
        System.arraycopy(scratch, 0, storedBits, storedCount, capturedLength);
        storedCount += capturedLength;
        insert(entry);
        capturedForStore = false;
        return true;
    }

    void beginFrame() {
        Arrays.fill(table, 0);
        Arrays.fill(topologies, 0, entries, null);
        Arrays.fill(palettes, 0, entries, null);
        entries = 0;
        storedCount = 0;
        lastLookupPerformed = false;
        capturedForStore = false;
        for (ReuseProbe probe : reuseProbes.values()) probe.beginFrame();
    }

    void clear() {
        beginFrame();
        reuseProbes.clear();
        recentProbeTopology = null;
        recentProbe = null;
        capturedTopology = null;
        capturedLength = 0;
        capturedHash = 0L;
    }

    int size() {
        return entries;
    }

    private ReuseProbe probe(GenericModelPartTopology topology) {
        if (topology == recentProbeTopology) return recentProbe;
        ReuseProbe probe = reuseProbes.get(topology);
        if (probe == null) {
            probe = new ReuseProbe();
            reuseProbes.put(topology, probe);
        }
        recentProbeTopology = topology;
        recentProbe = probe;
        return probe;
    }

    private void capture(GenericModelPartTopology topology) {
        int length = Math.multiplyExact(topology.nodes().size(), 11);
        if (scratch.length < length) scratch = Arrays.copyOf(scratch, grow(scratch.length, length));
        long hash = 0xcbf29ce484222325L ^ System.identityHashCode(topology);
        int at = 0;
        for (GenericModelPartTopology.Node node : topology.nodes()) {
            var part = node.part();
            scratch[at++] = Float.floatToRawIntBits(part.x);
            scratch[at++] = Float.floatToRawIntBits(part.y);
            scratch[at++] = Float.floatToRawIntBits(part.z);
            scratch[at++] = Float.floatToRawIntBits(part.xRot);
            scratch[at++] = Float.floatToRawIntBits(part.yRot);
            scratch[at++] = Float.floatToRawIntBits(part.zRot);
            scratch[at++] = Float.floatToRawIntBits(part.xScale);
            scratch[at++] = Float.floatToRawIntBits(part.yScale);
            scratch[at++] = Float.floatToRawIntBits(part.zScale);
            scratch[at++] = part.visible ? 1 : 0;
            scratch[at++] = part.skipDraw ? 1 : 0;
        }
        for (int i = 0; i < length; i++) hash = (hash ^ Integer.toUnsignedLong(scratch[i])) * 0x100000001b3L;
        capturedHash = hash;
        capturedTopology = topology;
        capturedLength = length;
    }

    private ModelPartBoneData findCaptured(long hash) {
        int mask = table.length - 1;
        int slot = mix(hash) & mask;
        while (table[slot] != 0) {
            int entry = table[slot] - 1;
            if (hashes[entry] == hash
                    && topologies[entry] == capturedTopology
                    && lengths[entry] == capturedLength
                    && exact(entry)) return palettes[entry];
            slot = (slot + 1) & mask;
        }
        return null;
    }

    private boolean exact(int entry) {
        int offset = offsets[entry];
        for (int i = 0; i < capturedLength; i++) if (storedBits[offset + i] != scratch[i]) return false;
        return true;
    }

    private void insert(int entry) {
        int mask = table.length - 1;
        int slot = mix(hashes[entry]) & mask;
        while (table[slot] != 0) slot = (slot + 1) & mask;
        table[slot] = entry + 1;
    }

    private void rehash(int capacity) {
        table = new int[capacity];
        for (int i = 0; i < entries; i++) insert(i);
    }

    private void ensureEntries(int needed) {
        if (hashes.length >= needed) return;
        int capacity = grow(hashes.length, needed);
        hashes = Arrays.copyOf(hashes, capacity);
        topologies = Arrays.copyOf(topologies, capacity);
        offsets = Arrays.copyOf(offsets, capacity);
        lengths = Arrays.copyOf(lengths, capacity);
        palettes = Arrays.copyOf(palettes, capacity);
    }

    private void ensureStored(int needed) {
        if (storedBits.length < needed) storedBits = Arrays.copyOf(storedBits, grow(storedBits.length, needed));
    }

    private static int grow(int current, int needed) {
        int result = current;
        while (result < needed) result = Math.multiplyExact(result, 2);
        return result;
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }

    private static final class ReuseProbe {
        private int misses;
        private boolean reuseConfirmed;
        private boolean bypass;

        private void beginFrame() {
            misses = 0;
            reuseConfirmed = false;
            bypass = false;
        }
    }
}
