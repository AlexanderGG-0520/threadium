package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MaterialPathDiagnosticCacheTest {
    @Test
    void stableBindingFormatsDiagnosticDescriptionOnlyOnce() {
        MaterialPathDiagnosticCache<Object, Object, Object> cache = new MaterialPathDiagnosticCache<>();
        Object consumer = new Object();
        Object provider = new Object();
        CountingDescription layer = new CountingDescription();
        MaterialPathDiagnosticData first = null;
        for (int index = 0; index < 10_000; index++) {
            MaterialPathDiagnosticData current =
                    cache.bindingDiagnostics(consumer, provider, layer, MaterialProviderSource.IMMEDIATE, false, 256);
            if (first == null) first = current;
            else assertSame(first, current);
        }
        assertEquals(1, layer.formatCount.get());
        assertEquals(1, cache.bindingCount());
    }

    @Test
    void identityChangeReformatsAndReplacesOneBindingEntry() {
        MaterialPathDiagnosticCache<Object, Object, Object> cache = new MaterialPathDiagnosticCache<>();
        Object consumer = new Object();
        Object provider = new Object();
        CountingDescription firstLayer = new CountingDescription();
        CountingDescription secondLayer = new CountingDescription();
        MaterialPathDiagnosticData first =
                cache.bindingDiagnostics(consumer, provider, firstLayer, MaterialProviderSource.IMMEDIATE, false, 256);
        MaterialPathDiagnosticData second =
                cache.bindingDiagnostics(consumer, provider, secondLayer, MaterialProviderSource.IMMEDIATE, false, 256);

        assertNotSame(first, second);
        assertEquals(1, firstLayer.formatCount.get());
        assertEquals(1, secondLayer.formatCount.get());
        assertEquals(1, cache.bindingCount());
    }

    @Test
    void normalizedUnresolvedClassDataIsMemoizedAndLifecycleBounded() {
        MaterialPathDiagnosticCache<Object, Object, Object> cache = new MaterialPathDiagnosticCache<>();
        Object consumer = new Object();
        MaterialPathDiagnosticData first = cache.unresolvedDiagnostics(consumer, 256);
        MaterialPathDiagnosticData second = cache.unresolvedDiagnostics(new Object(), 256);
        assertSame(first, second);
        assertEquals(1, cache.classNameCount());

        cache.beginFrame();
        assertEquals(0, cache.bindingCount());
        assertEquals(1, cache.classNameCount());
        cache.clearLifecycle();
        assertEquals(0, cache.classNameCount());
    }

    @Test
    void bindingCacheIsBounded() {
        MaterialPathDiagnosticCache<Object, Object, Object> cache =
                new MaterialPathDiagnosticCache<>(new MaterialPathDiagnosticCache.Limits(1, 1));
        Object provider = new Object();
        CountingDescription layer = new CountingDescription();
        Object first = new Object();
        Object second = new Object();
        cache.bindingDiagnostics(first, provider, layer, MaterialProviderSource.IMMEDIATE, false, 256);
        cache.bindingDiagnostics(second, provider, layer, MaterialProviderSource.IMMEDIATE, false, 256);
        assertEquals(1, cache.bindingCount());
    }

    private static final class CountingDescription {
        private final AtomicInteger formatCount = new AtomicInteger();

        @Override
        public String toString() {
            return "Layer@" + formatCount.incrementAndGet();
        }
    }
}
