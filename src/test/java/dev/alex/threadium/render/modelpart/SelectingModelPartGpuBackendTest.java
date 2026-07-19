package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

class SelectingModelPartGpuBackendTest {
    @Test
    void readyFastPathUsesPublishedDelegateWithoutInitializingAgain() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
        AtomicInteger factoryCalls = new AtomicInteger();
        FakeBackend backend = new FakeBackend(true);
        SelectingModelPartGpuBackend selector = selector(metrics, true, () -> {
            factoryCalls.incrementAndGet();
            return backend;
        });

        assertTrue(selector.ensureReady());
        assertEquals(ModelPartBackendState.READY, selector.state());
        assertTrue(selector.ensureReady());

        assertEquals(1, factoryCalls.get());
        assertEquals(2, backend.ensureReadyCalls);
        assertEquals(1, metrics.initializationAttempts.sum());
        assertEquals(1, metrics.initializationSuccesses.sum());
    }

    @Test
    void slowPathDoesNotAttemptInitializationOffRenderThread() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
        AtomicInteger factoryCalls = new AtomicInteger();
        SelectingModelPartGpuBackend selector = selector(metrics, false, () -> {
            factoryCalls.incrementAndGet();
            return new FakeBackend(true);
        });

        assertFalse(selector.ensureReady());
        assertEquals(ModelPartBackendState.UNINITIALIZED, selector.state());
        assertEquals(0, factoryCalls.get());
        assertEquals(0, metrics.initializationAttempts.sum());
    }

    @Test
    void failedInitializationRemainsOneShotAndClosesDelegate() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
        AtomicInteger factoryCalls = new AtomicInteger();
        FakeBackend backend = new FakeBackend(false);
        SelectingModelPartGpuBackend selector = selector(metrics, true, () -> {
            factoryCalls.incrementAndGet();
            return backend;
        });

        assertFalse(selector.ensureReady());
        assertEquals(ModelPartBackendState.FAILED, selector.state());
        assertFalse(selector.ensureReady());

        assertEquals(1, factoryCalls.get());
        assertEquals(1, backend.ensureReadyCalls);
        assertEquals(1, backend.closeCalls);
        assertEquals(1, metrics.initializationAttempts.sum());
        assertEquals(1, metrics.initializationFailures.sum());
    }

    private static SelectingModelPartGpuBackend selector(
            ModelPartGpuMetrics metrics,
            boolean renderThread,
            java.util.function.Supplier<ModelPartGpuBackend> factory) {
        return new SelectingModelPartGpuBackend(
                "auto", 256, 1024, true, DebugVisualMode.OFF, true, metrics, () -> renderThread, factory);
    }

    private static final class FakeBackend implements ModelPartGpuBackend {
        private final boolean ready;
        private int ensureReadyCalls;
        private int closeCalls;

        private FakeBackend(boolean ready) {
            this.ready = ready;
        }

        @Override
        public ModelPartBackendState state() {
            return ready ? ModelPartBackendState.READY : ModelPartBackendState.FAILED;
        }

        @Override
        public boolean ensureReady() {
            ensureReadyCalls++;
            return ready;
        }

        @Override
        public MeshHandle upload(ImmutableModelPartMesh mesh) {
            return null;
        }

        @Override
        public boolean queue(
                MeshHandle mesh,
                Object renderType,
                Matrix4fc rootPose,
                ModelPartBoneData bones,
                int light,
                int overlay,
                int tint,
                ModelPartUvTransform uvTransform,
                ModelPartDecalTransform decalTransform) {
            return false;
        }

        @Override
        public void beginGroup(boolean strictlyOrdered) {}

        @Override
        public void endGroup() {}

        @Override
        public FlushStats flushGroup() {
            return FlushStats.EMPTY;
        }

        @Override
        public void destroy(MeshHandle mesh) {}

        @Override
        public void clear() {}

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
