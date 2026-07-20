package dev.alex.threadium.render.modelpart.material;

/** Read-only resolution result. Provider and layer references are valid only for the current frame. */
public record MaterialContextResolution<P, L>(
        MaterialResolutionStatus status,
        P provider,
        L layer,
        long frameGeneration,
        long registrationSequence,
        String unresolvedConsumerClass) {
    public boolean directlyResolved() {
        return status == MaterialResolutionStatus.DIRECT_UNIQUE || status == MaterialResolutionStatus.DIRECT_REBOUND;
    }
}
