package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BackendStateMachineTest {
    @Test
    void initializationActivationFailureAndRecoveryAreExplicit() {
        BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);

        assertTrue(states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING));
        assertTrue(states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY));
        assertTrue(states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE));
        assertTrue(states.transition(ModelPartBackendState.ACTIVE, ModelPartBackendState.FAILED));
        assertTrue(states.transition(ModelPartBackendState.FAILED, ModelPartBackendState.UNINITIALIZED));
        assertEquals(ModelPartBackendState.UNINITIALIZED, states.state());
    }

    @Test
    void staleExpectedStateCannotOverwriteANewerState() {
        BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
        states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);

        assertFalse(states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.DISABLED));
        assertEquals(ModelPartBackendState.INITIALIZING, states.state());
    }

    @Test
    void invalidTransitionsAreRejectedBeforeMutation() {
        BackendStateMachine states = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);

        assertThrows(
                IllegalArgumentException.class,
                () -> states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.ACTIVE));
        assertEquals(ModelPartBackendState.UNINITIALIZED, states.state());
    }
}
