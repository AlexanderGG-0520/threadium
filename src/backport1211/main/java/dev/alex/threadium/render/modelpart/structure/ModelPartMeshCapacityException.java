package dev.alex.threadium.render.modelpart.structure;

/** Recoverable bounded-capacity rejection for CPU mesh capture or retention. */
public final class ModelPartMeshCapacityException extends RuntimeException {
    public ModelPartMeshCapacityException(String message) {
        super(message);
    }
}
