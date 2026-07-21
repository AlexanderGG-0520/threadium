package dev.alex.threadium.render.modelpart.material;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Frame-local binding diagnostics plus a bounded lifecycle-local normalized class-name cache. */
public final class MaterialPathDiagnosticCache<C, P, L> {
    public static final Limits DEFAULT_LIMITS = new Limits(4_096, 32);

    private final Limits limits;
    private final IdentityHashMap<C, BindingDiagnostics<P, L>> bindings = new IdentityHashMap<>();
    private final LinkedHashMap<String, MaterialPathDiagnosticData> unresolvedClasses = new LinkedHashMap<>();

    public MaterialPathDiagnosticCache() {
        this(DEFAULT_LIMITS);
    }

    public MaterialPathDiagnosticCache(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public MaterialPathDiagnosticData bindingDiagnostics(
            C consumer,
            P provider,
            L layer,
            MaterialProviderSource source,
            boolean crossProviderRebound,
            int maximumLength) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(source, "source");
        BindingDiagnostics<P, L> cached = bindings.get(consumer);
        if (cached != null
                && cached.provider == provider
                && cached.layer == layer
                && cached.source == source
                && cached.crossProviderRebound == crossProviderRebound) {
            return cached.data;
        }
        MaterialPathDiagnosticData data = new MaterialPathDiagnosticData(
                source,
                MaterialDiagnosticText.className(provider, maximumLength),
                MaterialDiagnosticText.className(consumer, maximumLength),
                MaterialDiagnosticText.className(layer, maximumLength),
                MaterialDiagnosticText.describe(layer, maximumLength),
                crossProviderRebound);
        if (cached != null || bindings.size() < limits.maximumBindings()) {
            bindings.put(consumer, new BindingDiagnostics<>(provider, layer, source, crossProviderRebound, data));
        }
        return data;
    }

    public String consumerClass(C consumer, int maximumLength) {
        return unresolvedDiagnostics(consumer, maximumLength).consumerClass();
    }

    public MaterialPathDiagnosticData unresolvedDiagnostics(C consumer, int maximumLength) {
        Objects.requireNonNull(consumer, "consumer");
        String raw = consumer.getClass().getName();
        MaterialPathDiagnosticData cached = unresolvedClasses.get(raw);
        if (cached != null) return cached;
        String normalized = MaterialDiagnosticText.sanitize(raw, maximumLength);
        MaterialPathDiagnosticData data = MaterialPathDiagnosticData.unresolved(normalized);
        if (unresolvedClasses.size() < limits.maximumClassNames()) unresolvedClasses.put(raw, data);
        return data;
    }

    public void beginFrame() {
        bindings.clear();
    }

    public void clearLifecycle() {
        bindings.clear();
        unresolvedClasses.clear();
    }

    public int bindingCount() {
        return bindings.size();
    }

    public int classNameCount() {
        return unresolvedClasses.size();
    }

    public record Limits(int maximumBindings, int maximumClassNames) {
        public Limits {
            if (maximumBindings <= 0 || maximumClassNames <= 0) {
                throw new IllegalArgumentException("Material diagnostic cache limits are invalid");
            }
        }
    }

    private static final class BindingDiagnostics<P, L> {
        private final P provider;
        private final L layer;
        private final MaterialProviderSource source;
        private final boolean crossProviderRebound;
        private final MaterialPathDiagnosticData data;

        private BindingDiagnostics(
                P provider,
                L layer,
                MaterialProviderSource source,
                boolean crossProviderRebound,
                MaterialPathDiagnosticData data) {
            this.provider = provider;
            this.layer = layer;
            this.source = source;
            this.crossProviderRebound = crossProviderRebound;
            this.data = data;
        }
    }
}
