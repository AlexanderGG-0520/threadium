package dev.alex.threadium.render.modelpart;

import java.util.concurrent.atomic.AtomicReference;

public final class BackendStateMachine {
    private final AtomicReference<ModelPartBackendState> state;

    public BackendStateMachine(ModelPartBackendState initial) {
        state = new AtomicReference<>(initial);
    }

    public ModelPartBackendState state() {
        return state.get();
    }

    public boolean transition(ModelPartBackendState from, ModelPartBackendState to) {
        if (!allowed(from, to)) throw new IllegalArgumentException(from + " -> " + to);
        return state.compareAndSet(from, to);
    }

    private static boolean allowed(ModelPartBackendState a, ModelPartBackendState b) {
        return (a == ModelPartBackendState.DISABLED && b == ModelPartBackendState.UNINITIALIZED)
                || (a == ModelPartBackendState.UNINITIALIZED
                        && (b == ModelPartBackendState.INITIALIZING || b == ModelPartBackendState.DISABLED))
                || (a == ModelPartBackendState.INITIALIZING
                        && (b == ModelPartBackendState.READY || b == ModelPartBackendState.FAILED))
                || ((a == ModelPartBackendState.READY || a == ModelPartBackendState.ACTIVE)
                        && (b == ModelPartBackendState.ACTIVE
                                || b == ModelPartBackendState.FAILED
                                || b == ModelPartBackendState.DISABLED))
                || (a == ModelPartBackendState.FAILED
                        && (b == ModelPartBackendState.UNINITIALIZED || b == ModelPartBackendState.DISABLED));
    }
}
