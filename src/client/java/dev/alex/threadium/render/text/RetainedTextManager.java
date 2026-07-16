package dev.alex.threadium.render.text;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.cache.RetainedCache;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.lifecycle.ThreadiumLifecycle;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Render-thread-only retained Font.PreparedText cache for explicitly marked Text Display lines. */
public final class RetainedTextManager {
    private static final Map<FormattedCharSequence, Boolean> MARKED_LINES = new IdentityHashMap<>();
    private static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), draws = new AtomicLong();
    private static final AtomicLong instances = new AtomicLong(), fallbacks = new AtomicLong(), evictions = new AtomicLong(), failures = new AtomicLong();
    private static RetainedCache<TextGeometryKey, PreparedResource> cache;
    private static boolean configuredEnabled, active;
    private static int maxEntries;
    private static long maxGpuBytes, idleNanos, lastPollNanos;
    private static Path configPath;
    private static String inactiveReason = "not initialized";

    private RetainedTextManager() { }

    public static void initialize(ThreadiumConfig config) {
        configuredEnabled = config.retainedTextEnabled();
        maxEntries = config.retainedTextMaxCacheEntries();
        maxGpuBytes = config.retainedTextMaxGpuBytes();
        idleNanos = config.retainedTextEntryIdleSeconds() * 1_000_000_000L;
        configPath = FabricLoader.getInstance().getConfigDir().resolve("threadium.properties");
        cache = new RetainedCache<>(maxEntries, maxGpuBytes);
        evaluateCompatibility();
        ThreadiumClient.LOGGER.info("Threadium retained Text Display cache: active={}, reason={}", active, inactiveReason);
    }

    private static void evaluateCompatibility() {
        if (!configuredEnabled) { active = false; inactiveReason = "disabled by display.text.retained.enabled"; return; }
        if (FabricLoader.getInstance().isModLoaded("iris")) { active = false; inactiveReason = "Iris shader compatibility is unverified"; return; }
        if (FabricLoader.getInstance().isModLoaded("immediatelyfast")) { active = false; inactiveReason = "ImmediatelyFast text-path compatibility is unverified"; return; }
        active = true; inactiveReason = FabricLoader.getInstance().isModLoaded("sodium") ? "enabled with Sodium-compatible Font boundary" : "enabled with vanilla Font boundary";
    }

    public static void beginFrame() {
        MARKED_LINES.clear();
        if (active) evictions.addAndGet(cache.evictIdle(System.nanoTime() - idleNanos));
    }

    public static void mark(Display.TextDisplay.CachedInfo info) {
        if (!active) { fallbacks.incrementAndGet(); return; }
        for (Display.TextDisplay.CachedLine line : info.lines()) MARKED_LINES.put(line.contents(), Boolean.TRUE);
    }

    public static Font.PreparedText retain(FormattedCharSequence sequence, float x, float y, int color, boolean shadow,
                                           boolean bidirectional, int background, int variant, Supplier<Font.PreparedText> vanilla) {
        if (!active || !MARKED_LINES.containsKey(sequence)) return vanilla.get();
        instances.incrementAndGet();
        TextGeometryKey key;
        try { key = TextGeometryKey.capture(sequence, x, y, color, shadow, bidirectional, background, variant, ThreadiumLifecycle.resourceGeneration()); }
        catch (RuntimeException failure) { failures.incrementAndGet(); fallbacks.incrementAndGet(); return vanilla.get(); }
        for (TextGeometryKey.Glyph glyph : key.glyphs()) {
            if (glyph.style().isObfuscated()) { fallbacks.incrementAndGet(); return vanilla.get(); }
        }
        RetainedCache.Lookup<PreparedResource> lookup = cache.getOrBuild(key, System.nanoTime(), key.resourceGeneration(), () -> new PreparedResource(vanilla.get()));
        return switch (lookup.outcome()) {
            case HIT -> { hits.incrementAndGet(); draws.incrementAndGet(); yield lookup.value().text; }
            case MISS -> { misses.incrementAndGet(); draws.incrementAndGet(); yield lookup.value().text; }
            case NEGATIVE -> { failures.incrementAndGet(); fallbacks.incrementAndGet(); yield vanilla.get(); }
        };
    }

    public static void invalidate(String reason) {
        MARKED_LINES.clear();
        if (cache != null) evictions.addAndGet(cache.clear());
        ThreadiumClient.LOGGER.debug("Threadium retained text cache invalidated: {}", reason);
    }

    public static void pollRuntimeConfig() {
        long now = System.nanoTime();
        if (now - lastPollNanos < 1_000_000_000L) return;
        lastPollNanos = now;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) { properties.load(input); }
        catch (IOException | IllegalArgumentException ignored) { return; }
        boolean requested = strictBoolean(properties.getProperty("display.text.retained.enabled"), configuredEnabled);
        if (requested == configuredEnabled) return;
        configuredEnabled = requested;
        if (!requested) invalidate("runtime disable");
        evaluateCompatibility();
        ThreadiumClient.LOGGER.info("Threadium retained Text Display cache runtime state: active={}, reason={}", active, inactiveReason);
    }

    private static boolean strictBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if (value.trim().equalsIgnoreCase("true")) return true;
        if (value.trim().equalsIgnoreCase("false")) return false;
        return fallback;
    }

    public static String metricsSnapshot() {
        int entries = cache == null ? 0 : cache.size();
        long bytes = cache == null ? 0L : cache.bytes();
        return "retainedText={hits=" + hits.getAndSet(0) + ",misses=" + misses.getAndSet(0) + ",entries=" + entries
                + ",gpuBytes=" + bytes + ",draws=" + draws.getAndSet(0) + ",instances=" + instances.getAndSet(0)
                + ",vanillaFallbacks=" + fallbacks.getAndSet(0) + ",evictions=" + evictions.getAndSet(0)
                + ",buildFailures=" + failures.getAndSet(0) + '}';
    }

    private static final class PreparedResource implements RetainedCache.Resource {
        final Font.PreparedText text;
        PreparedResource(Font.PreparedText text) { this.text = text; }
        @Override public long bytes() { return 0L; }
        @Override public void close() { }
    }
}
