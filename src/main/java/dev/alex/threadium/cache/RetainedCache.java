package dev.alex.threadium.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded access-order cache with negative entries and deterministic cleanup. */
public final class RetainedCache<K, V extends RetainedCache.Resource> {
    public interface Resource extends AutoCloseable { long bytes(); @Override void close(); }
    public enum Outcome { HIT, MISS, NEGATIVE }
    public record Lookup<V>(Outcome outcome, V value) { }
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private int maxEntries;
    private long maxBytes;
    private long bytes;

    public RetainedCache(int maxEntries, long maxBytes) { reconfigure(maxEntries, maxBytes); }

    public synchronized Lookup<V> getOrBuild(K key, long nowNanos, long generation, Supplier<V> builder) {
        Entry<V> current = entries.get(key);
        if (current != null && current.generation == generation) {
            current.lastUsedNanos = nowNanos;
            return current.negative ? new Lookup<>(Outcome.NEGATIVE, null) : new Lookup<>(Outcome.HIT, current.value);
        }
        if (current != null) remove(key, current);
        V built;
        try { built = builder.get(); }
        catch (RuntimeException failure) {
            entries.put(key, new Entry<>(null, generation, nowNanos, true));
            trim();
            return new Lookup<>(Outcome.NEGATIVE, null);
        }
        if (built == null || built.bytes() < 0L || built.bytes() > maxBytes) {
            if (built != null) built.close();
            entries.put(key, new Entry<>(null, generation, nowNanos, true));
            trim();
            return new Lookup<>(Outcome.NEGATIVE, null);
        }
        entries.put(key, new Entry<>(built, generation, nowNanos, false));
        bytes = saturatingAdd(bytes, built.bytes());
        trim();
        Entry<V> retained = entries.get(key);
        return retained == null || retained.negative ? new Lookup<>(Outcome.NEGATIVE, null) : new Lookup<>(Outcome.MISS, retained.value);
    }

    public synchronized int evictIdle(long cutoffNanos) {
        int removed = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue();
            if (entry.lastUsedNanos > cutoffNanos) continue;
            release(entry); iterator.remove(); removed++;
        }
        return removed;
    }

    public synchronized int clear() {
        int count = entries.size();
        for (Entry<V> entry : entries.values()) release(entry);
        entries.clear(); bytes = 0L;
        return count;
    }

    public synchronized void reconfigure(int entries, long bytes) {
        if (entries < 1 || bytes < 0L) throw new IllegalArgumentException("invalid cache limits");
        this.maxEntries = entries; this.maxBytes = bytes; trim();
    }
    public synchronized int size() { return entries.size(); }
    public synchronized long bytes() { return bytes; }

    private void trim() {
        var iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue(); release(entry); iterator.remove();
        }
    }
    private void remove(K key, Entry<V> entry) { entries.remove(key); release(entry); }
    private void release(Entry<V> entry) { if (!entry.negative) { bytes -= entry.value.bytes(); entry.value.close(); } }
    private static long saturatingAdd(long value, long addition) { return value > Long.MAX_VALUE - addition ? Long.MAX_VALUE : value + addition; }
    private static final class Entry<V> {
        final V value; final long generation; final boolean negative; long lastUsedNanos;
        Entry(V value, long generation, long lastUsedNanos, boolean negative) { this.value = value; this.generation = generation; this.lastUsedNanos = lastUsedNanos; this.negative = negative; }
    }
}
