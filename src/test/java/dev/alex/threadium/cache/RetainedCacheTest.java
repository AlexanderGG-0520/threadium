package dev.alex.threadium.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetainedCacheTest {
    @Test void hitMissAndCollisionSafeKeys() {
        RetainedCache<Key, Value> cache = new RetainedCache<>(4, 100);
        Key first = new Key("first", 7);
        Key collision = new Key("second", 7);
        assertEquals(RetainedCache.Outcome.MISS, cache.getOrBuild(first, 1, 1, () -> new Value(10)).outcome());
        assertEquals(RetainedCache.Outcome.HIT, cache.getOrBuild(first, 2, 1, () -> fail("must not rebuild")).outcome());
        assertEquals(RetainedCache.Outcome.MISS, cache.getOrBuild(collision, 3, 1, () -> new Value(10)).outcome());
        assertEquals(2, cache.size());
    }

    @Test void evictsLeastRecentlyUsedAndAccountsBytes() {
        RetainedCache<String, Value> cache = new RetainedCache<>(2, 15);
        Value first = new Value(8), second = new Value(8);
        cache.getOrBuild("a", 1, 1, () -> first);
        cache.getOrBuild("b", 2, 1, () -> second);
        assertTrue(first.closed);
        assertEquals(8, cache.bytes());
        assertEquals(1, cache.size());
    }

    @Test void negativeEntryBacksOffUntilGenerationChanges() {
        RetainedCache<String, Value> cache = new RetainedCache<>(4, 100);
        AtomicInteger attempts = new AtomicInteger();
        assertEquals(RetainedCache.Outcome.NEGATIVE, cache.getOrBuild("x", 1, 1, () -> { attempts.incrementAndGet(); throw new IllegalStateException(); }).outcome());
        assertEquals(RetainedCache.Outcome.NEGATIVE, cache.getOrBuild("x", 2, 1, () -> { attempts.incrementAndGet(); return new Value(1); }).outcome());
        assertEquals(1, attempts.get());
        assertEquals(RetainedCache.Outcome.MISS, cache.getOrBuild("x", 3, 2, () -> { attempts.incrementAndGet(); return new Value(1); }).outcome());
        assertEquals(2, attempts.get());
    }

    @Test void idleSessionAndRuntimeDisableCleanupReleaseResources() {
        RetainedCache<String, Value> cache = new RetainedCache<>(4, 100);
        Value idle = new Value(4);
        cache.getOrBuild("idle", 10, 1, () -> idle);
        assertEquals(1, cache.evictIdle(10));
        assertTrue(idle.closed);
        Value session = new Value(5);
        cache.getOrBuild("session", 20, 1, () -> session);
        assertEquals(1, cache.clear());
        assertTrue(session.closed);
        assertEquals(0, cache.bytes());
    }

    @Test void overLimitEntryIsClosedAndNegativeCached() {
        RetainedCache<String, Value> cache = new RetainedCache<>(4, 5);
        Value huge = new Value(Long.MAX_VALUE);
        assertEquals(RetainedCache.Outcome.NEGATIVE, cache.getOrBuild("huge", 1, 1, () -> huge).outcome());
        assertTrue(huge.closed);
        assertEquals(0, cache.bytes());
    }

    private record Key(String value, int forcedHash) {
        @Override public int hashCode() { return forcedHash; }
    }
    private static final class Value implements RetainedCache.Resource {
        final long bytes; boolean closed;
        Value(long bytes) { this.bytes = bytes; }
        @Override public long bytes() { return bytes; }
        @Override public void close() { closed = true; }
    }
}
