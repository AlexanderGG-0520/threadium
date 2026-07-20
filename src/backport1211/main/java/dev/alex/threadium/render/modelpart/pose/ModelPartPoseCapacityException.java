package dev.alex.threadium.render.modelpart.pose;

/** Recoverable bounded-capacity rejection for CPU pose capture or retention. */
public final class ModelPartPoseCapacityException extends RuntimeException {
    public ModelPartPoseCapacityException(String message) {
        super(message);
    }
}
