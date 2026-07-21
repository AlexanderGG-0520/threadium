package dev.alex.threadium.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class RenderThreadLifecycleDispatcherTest {
    @Test
    void offThreadWorldInvalidationDoesNotAccessStateImmediately() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = false;

        assertEquals(
                RenderThreadLifecycleDispatcher.RequestResult.QUEUED,
                fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD));
        assertTrue(fixture.events.isEmpty());
        assertEquals(1, fixture.scheduler.tasks.size());
    }

    @Test
    void queuedActionSummarizesBeforeClearingOnOwnerThread() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = false;
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);

        fixture.scheduler.runNextOnOwner();

        assertEquals(List.of("summary:WORLD", "clear:WORLD"), fixture.events);
        assertTrue(fixture.callbackRanOnOwner.get());
    }

    @Test
    void duplicatePendingInvalidationsCoalesceWithoutLosingGenerationCounts() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = false;
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);

        assertEquals(1, fixture.dispatcher.pendingOperationKinds());
        assertEquals(1, fixture.scheduler.tasks.size());
        fixture.scheduler.runNextOnOwner();

        assertEquals(3, fixture.worldGeneration.get());
        assertEquals(List.of("summary:WORLD", "clear:WORLD"), fixture.events);
    }

    @Test
    void staleQueuedOperationCannotTouchReplacementState() {
        AtomicBoolean firstActive = new AtomicBoolean(true);
        AtomicBoolean replacementActive = new AtomicBoolean(false);
        FakeScheduler scheduler = new FakeScheduler();
        List<String> firstEvents = new ArrayList<>();
        List<String> replacementEvents = new ArrayList<>();
        RenderThreadLifecycleDispatcher first = new RenderThreadLifecycleDispatcher(
                scheduler, firstActive::get, (operation, occurrences) -> firstEvents.add(operation.name()));
        RenderThreadLifecycleDispatcher replacement = new RenderThreadLifecycleDispatcher(
                scheduler, replacementActive::get, (operation, occurrences) -> replacementEvents.add(operation.name()));
        scheduler.owner = false;
        first.request(RenderThreadLifecycleDispatcher.Operation.WORLD);
        firstActive.set(false);
        replacementActive.set(true);

        scheduler.runNextOnOwner();

        assertTrue(firstEvents.isEmpty());
        assertTrue(replacementEvents.isEmpty());
        assertEquals(0, replacement.pendingOperationKinds());
    }

    @Test
    void resourceAndWorldCountsRetainTheirGenerationSemantics() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = false;
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.RESOURCES);
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.RESOURCES);
        fixture.scheduler.runNextOnOwner();

        assertEquals(1, fixture.worldGeneration.get());
        assertEquals(2, fixture.resourceGeneration.get());
        assertEquals(List.of("summary:RESOURCES", "clear:RESOURCES", "summary:WORLD", "clear:WORLD"), fixture.events);
    }

    @Test
    void queuedLifecycleClearReleasesRetainedIdentityReferences() {
        Fixture fixture = new Fixture();
        Object provider = new Object();
        Object layer = new Object();
        Object consumer = new Object();
        fixture.identities.put(provider, consumer);
        fixture.identities.put(layer, consumer);
        fixture.scheduler.owner = false;
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD);

        assertEquals(2, fixture.identities.size());
        fixture.scheduler.runNextOnOwner();
        assertTrue(fixture.identities.isEmpty());
    }

    @Test
    void ownerThreadInvalidationRunsImmediately() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = true;

        assertEquals(
                RenderThreadLifecycleDispatcher.RequestResult.EXECUTED,
                fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD));
        assertEquals(1, fixture.worldGeneration.get());
        assertTrue(fixture.scheduler.tasks.isEmpty());
    }

    @Test
    void lifecycleClearPreservesM1AndM2ResetBehavior() {
        Fixture fixture = new Fixture();
        fixture.meshEntries = 4;
        fixture.poseEntries = 7;
        fixture.scheduler.owner = true;

        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.RESOURCES);

        assertEquals(0, fixture.meshEntries);
        assertEquals(0, fixture.poseEntries);
    }

    @Test
    void shutdownTerminatesDispatcherAndIgnoresLaterRequests() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = true;
        fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.SHUTDOWN);

        assertEquals(
                RenderThreadLifecycleDispatcher.RequestResult.IGNORED_AFTER_SHUTDOWN,
                fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD));
        assertFalse(fixture.active.get());
    }

    @Test
    void rejectedExecutorLeavesBoundedWorkForOwnerRecovery() {
        Fixture fixture = new Fixture();
        fixture.scheduler.owner = false;
        fixture.scheduler.accept = false;
        assertEquals(
                RenderThreadLifecycleDispatcher.RequestResult.QUEUE_REJECTED,
                fixture.dispatcher.request(RenderThreadLifecycleDispatcher.Operation.WORLD));
        assertEquals(1, fixture.dispatcher.pendingOperationKinds());

        fixture.scheduler.owner = true;
        fixture.dispatcher.drainPendingOnOwnerThread();
        assertEquals(1, fixture.worldGeneration.get());
    }

    private static final class Fixture {
        private final FakeScheduler scheduler = new FakeScheduler();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean callbackRanOnOwner = new AtomicBoolean();
        private final AtomicLong worldGeneration = new AtomicLong();
        private final AtomicLong resourceGeneration = new AtomicLong();
        private final List<String> events = new ArrayList<>();
        private final IdentityHashMap<Object, Object> identities = new IdentityHashMap<>();
        private int meshEntries = 1;
        private int poseEntries = 1;
        private final RenderThreadLifecycleDispatcher dispatcher =
                new RenderThreadLifecycleDispatcher(scheduler, active::get, (operation, occurrences) -> {
                    callbackRanOnOwner.set(scheduler.owner);
                    events.add("summary:" + operation);
                    if (operation == RenderThreadLifecycleDispatcher.Operation.WORLD) {
                        worldGeneration.addAndGet(occurrences);
                    } else if (operation == RenderThreadLifecycleDispatcher.Operation.RESOURCES) {
                        resourceGeneration.addAndGet(occurrences);
                    } else {
                        active.set(false);
                    }
                    identities.clear();
                    meshEntries = 0;
                    poseEntries = 0;
                    events.add("clear:" + operation);
                });
    }

    private static final class FakeScheduler implements RenderThreadLifecycleDispatcher.OwnerThreadScheduler {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean owner = true;
        private boolean accept = true;

        @Override
        public boolean isOwnerThread() {
            return owner;
        }

        @Override
        public boolean execute(Runnable action) {
            if (!accept) return false;
            tasks.addLast(action);
            return true;
        }

        private void runNextOnOwner() {
            boolean previous = owner;
            owner = true;
            try {
                tasks.removeFirst().run();
            } finally {
                owner = previous;
            }
        }
    }
}
