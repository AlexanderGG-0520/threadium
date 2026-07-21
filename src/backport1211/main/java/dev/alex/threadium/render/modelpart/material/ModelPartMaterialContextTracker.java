package dev.alex.threadium.render.modelpart.material;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Render-thread-owned frame-local identity tracker. A direct association is observational only and does not imply
 * replacement eligibility.
 */
public final class ModelPartMaterialContextTracker<P, L, C> {
    public static final Limits DEFAULT_LIMITS = new Limits(4_096, 64, 2_048, 32, 256);

    private final Limits limits;
    private final IdentityHashMap<C, Binding<P, L>> bindings = new IdentityHashMap<>();
    private final IdentityHashMap<P, Boolean> providers = new IdentityHashMap<>();
    private final IdentityHashMap<L, Boolean> layers = new IdentityHashMap<>();
    private final LinkedHashSet<String> unresolvedConsumerClasses = new LinkedHashSet<>();
    private final LinkedHashMap<String, String> normalizedConsumerClasses = new LinkedHashMap<>();

    private long frameGeneration;
    private long registrationSequence;
    private boolean capacityExhausted;
    private long materialProviderRequests;
    private long materialConsumerRegistrations;
    private long materialRegistrationRefreshes;
    private long materialConsumerRebindings;
    private long materialCrossProviderRebindings;
    private long materialResolutionAttempts;
    private long materialDirectUniqueResolutions;
    private long materialDirectReboundResolutions;
    private long materialUnresolvedResolutions;
    private long materialCapacityRejections;
    private long materialTrackingFailures;

    public ModelPartMaterialContextTracker() {
        this(DEFAULT_LIMITS);
    }

    public ModelPartMaterialContextTracker(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public RegistrationResult register(P provider, L layer, C consumer) {
        return register(provider, layer, consumer, MaterialProviderSource.UNAVAILABLE);
    }

    public RegistrationResult register(P provider, L layer, C consumer, MaterialProviderSource providerSource) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(providerSource, "providerSource");
        materialProviderRequests++;

        Binding<P, L> existing = bindings.get(consumer);
        if (existing != null && existing.provider == provider && existing.layer == layer) {
            existing.sequence = ++registrationSequence;
            existing.providerSource = providerSource;
            materialRegistrationRefreshes++;
            return RegistrationResult.REFRESHED;
        }

        boolean newProvider = !providers.containsKey(provider);
        boolean newLayer = !layers.containsKey(layer);
        boolean newConsumer = existing == null;
        if ((newProvider && providers.size() >= limits.maximumProviders())
                || (newLayer && layers.size() >= limits.maximumLayers())
                || (newConsumer && bindings.size() >= limits.maximumConsumers())) {
            capacityExhausted = true;
            materialCapacityRejections++;
            if (existing != null) {
                boolean crossProvider = existing.provider != provider;
                if (crossProvider) materialCrossProviderRebindings++;
                materialConsumerRebindings++;
                existing.markRebound(crossProvider);
            }
            return RegistrationResult.CAPACITY_REJECTED;
        }

        if (newProvider) providers.put(provider, Boolean.TRUE);
        if (newLayer) layers.put(layer, Boolean.TRUE);
        long sequence = ++registrationSequence;
        if (existing == null) {
            bindings.put(consumer, new Binding<>(provider, layer, providerSource, sequence));
            materialConsumerRegistrations++;
            return RegistrationResult.REGISTERED;
        }

        if (existing.provider != provider) materialCrossProviderRebindings++;
        materialConsumerRebindings++;
        existing.rebind(provider, layer, providerSource, sequence);
        return RegistrationResult.REBOUND;
    }

    /** Pure lookup: it does not retain the query or change bindings, gauges, or counters. */
    public MaterialContextResolution<P, L> resolve(C consumer) {
        Objects.requireNonNull(consumer, "consumer");
        Binding<P, L> binding = bindings.get(consumer);
        if (binding == null) {
            MaterialResolutionStatus status = capacityExhausted
                    ? MaterialResolutionStatus.CAPACITY_REJECTED
                    : MaterialResolutionStatus.UNRESOLVED;
            return new MaterialContextResolution<>(
                    status,
                    MaterialProviderSource.UNAVAILABLE,
                    null,
                    null,
                    frameGeneration,
                    0,
                    false,
                    consumer.getClass().getName());
        }
        MaterialResolutionStatus status =
                binding.rebound ? MaterialResolutionStatus.DIRECT_REBOUND : MaterialResolutionStatus.DIRECT_UNIQUE;
        return new MaterialContextResolution<>(
                status,
                binding.providerSource,
                binding.provider,
                binding.layer,
                frameGeneration,
                binding.sequence,
                binding.crossProviderRebound,
                null);
    }

    /** Records aggregate diagnostics for a lookup without retaining an unresolved consumer identity. */
    public MaterialContextResolution<P, L> observeResolution(C consumer) {
        MaterialContextResolution<P, L> resolution = resolve(consumer);
        materialResolutionAttempts++;
        switch (resolution.status()) {
            case DIRECT_UNIQUE -> materialDirectUniqueResolutions++;
            case DIRECT_REBOUND -> materialDirectReboundResolutions++;
            case UNRESOLVED -> {
                materialUnresolvedResolutions++;
                retainUnresolvedClass(resolution.unresolvedConsumerClass());
            }
            case CAPACITY_REJECTED -> {
                materialUnresolvedResolutions++;
                retainUnresolvedClass(resolution.unresolvedConsumerClass());
            }
        }
        return resolution;
    }

    public void recordTrackingFailure() {
        materialTrackingFailures++;
    }

    public void beginFrame() {
        clearFrameReferences();
        frameGeneration++;
    }

    public void clearLifecycle() {
        clearFrameReferences();
        frameGeneration = 0;
        materialProviderRequests = 0;
        materialConsumerRegistrations = 0;
        materialRegistrationRefreshes = 0;
        materialConsumerRebindings = 0;
        materialCrossProviderRebindings = 0;
        materialResolutionAttempts = 0;
        materialDirectUniqueResolutions = 0;
        materialDirectReboundResolutions = 0;
        materialUnresolvedResolutions = 0;
        materialCapacityRejections = 0;
        materialTrackingFailures = 0;
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(
                materialProviderRequests,
                materialConsumerRegistrations,
                materialRegistrationRefreshes,
                materialConsumerRebindings,
                materialCrossProviderRebindings,
                materialResolutionAttempts,
                materialDirectUniqueResolutions,
                materialDirectReboundResolutions,
                materialUnresolvedResolutions,
                materialCapacityRejections,
                materialTrackingFailures,
                bindings.size(),
                providers.size(),
                layers.size(),
                unresolvedConsumerClasses.size(),
                registrationSequence,
                frameGeneration,
                Set.copyOf(unresolvedConsumerClasses));
    }

    private void retainUnresolvedClass(String className) {
        String normalized = normalizedConsumerClasses.get(className);
        if (normalized == null) {
            normalized = boundedClassName(className, limits.diagnosticTextLength());
            if (normalizedConsumerClasses.size() < limits.maximumUnresolvedClassNames()) {
                normalizedConsumerClasses.put(className, normalized);
            }
        }
        if (unresolvedConsumerClasses.size() < limits.maximumUnresolvedClassNames()
                || unresolvedConsumerClasses.contains(normalized)) {
            unresolvedConsumerClasses.add(normalized);
        }
    }

    private void clearFrameReferences() {
        bindings.clear();
        providers.clear();
        layers.clear();
        unresolvedConsumerClasses.clear();
        normalizedConsumerClasses.clear();
        registrationSequence = 0;
        capacityExhausted = false;
    }

    static String boundedClassName(Class<?> type, int maximumLength) {
        return boundedClassName(type.getName(), maximumLength);
    }

    private static String boundedClassName(String name, int maximumLength) {
        return MaterialDiagnosticText.sanitize(name, maximumLength);
    }

    static String sanitizeDiagnosticText(String text, int maximumLength) {
        return MaterialDiagnosticText.sanitize(text, maximumLength);
    }

    public enum RegistrationResult {
        REGISTERED,
        REFRESHED,
        REBOUND,
        CAPACITY_REJECTED
    }

    public record Limits(
            int maximumConsumers,
            int maximumProviders,
            int maximumLayers,
            int maximumUnresolvedClassNames,
            int diagnosticTextLength) {
        public Limits {
            if (maximumConsumers <= 0
                    || maximumProviders <= 0
                    || maximumLayers <= 0
                    || maximumUnresolvedClassNames < 0
                    || diagnosticTextLength <= 0) {
                throw new IllegalArgumentException("Material tracker limits are invalid");
            }
        }
    }

    public record Diagnostics(
            long materialProviderRequests,
            long materialConsumerRegistrations,
            long materialRegistrationRefreshes,
            long materialConsumerRebindings,
            long materialCrossProviderRebindings,
            long materialResolutionAttempts,
            long materialDirectUniqueResolutions,
            long materialDirectReboundResolutions,
            long materialUnresolvedResolutions,
            long materialCapacityRejections,
            long materialTrackingFailures,
            int trackedConsumersThisFrame,
            int distinctProvidersThisFrame,
            int distinctRenderLayersThisFrame,
            int unresolvedConsumerClassesThisFrame,
            long lastMaterialRegistrationSequence,
            long frameGeneration,
            Set<String> unresolvedConsumerClasses) {
        public Diagnostics {
            unresolvedConsumerClasses = Collections.unmodifiableSet(new LinkedHashSet<>(unresolvedConsumerClasses));
        }
    }

    private static final class Binding<P, L> {
        private P provider;
        private L layer;
        private MaterialProviderSource providerSource;
        private long sequence;
        private boolean rebound;
        private boolean crossProviderRebound;

        private Binding(P provider, L layer, MaterialProviderSource providerSource, long sequence) {
            this.provider = provider;
            this.layer = layer;
            this.providerSource = providerSource;
            this.sequence = sequence;
        }

        private void rebind(P nextProvider, L nextLayer, MaterialProviderSource nextProviderSource, long nextSequence) {
            if (provider != nextProvider) crossProviderRebound = true;
            rebound = true;
            provider = nextProvider;
            layer = nextLayer;
            providerSource = nextProviderSource;
            sequence = nextSequence;
        }

        private void markRebound(boolean crossProvider) {
            rebound = true;
            crossProviderRebound |= crossProvider;
        }
    }
}
