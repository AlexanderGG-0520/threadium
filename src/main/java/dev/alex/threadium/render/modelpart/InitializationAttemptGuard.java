package dev.alex.threadium.render.modelpart;

import java.util.concurrent.atomic.AtomicBoolean;

/** Allows one attempt until an explicit generation/runtime reset creates or resets the guard. */
public final class InitializationAttemptGuard {
    private final AtomicBoolean attempted = new AtomicBoolean();

    public boolean beginAttempt() {
        return attempted.compareAndSet(false, true);
    }

    public boolean attempted() {
        return attempted.get();
    }

    public void reset() {
        attempted.set(false);
    }
}
