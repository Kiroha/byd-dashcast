package com.byd.dashcast.util.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Serial latest-value dispatcher with at most one drain task and ordered terminal cleanup. */
public final class LatestValueDispatcher<T> {

    public interface ValueHandler<T> {
        void accept(T value) throws Exception;
    }

    private final ExecutorService executor;
    private final ValueHandler<T> handler;
    private final AtomicReference<T> latest = new AtomicReference<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LatestValueDispatcher(ExecutorService executor, ValueHandler<T> handler) {
        if (executor == null) throw new IllegalArgumentException("executor required");
        if (handler == null) throw new IllegalArgumentException("handler required");
        this.executor = executor;
        this.handler = handler;
    }

    /** Publishes a value, replacing any not-yet-consumed value. Returns false after close/reject. */
    public boolean submit(T value) {
        if (value == null || closed.get()) return false;
        latest.set(value);
        if (closed.get()) {
            latest.set(null);
            return false;
        }
        return scheduleDrain();
    }

    /** Cancels the pending value and serializes an action after any handler already in progress. */
    public boolean cancelPendingAndExecute(Runnable action) {
        if (action == null || closed.get()) return false;
        latest.set(null);
        try {
            executor.execute(action);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    /** Drops pending values, runs terminal cleanup after any active handler, then shuts down. */
    public void close(Runnable terminalCleanup) {
        if (terminalCleanup == null) throw new IllegalArgumentException("terminalCleanup required");
        if (!closed.compareAndSet(false, true)) return;
        latest.set(null);
        try {
            executor.execute(terminalCleanup);
        } catch (RejectedExecutionException rejected) {
            terminalCleanup.run();
        } finally {
            executor.shutdown();
        }
    }

    private boolean scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return true;
        try {
            executor.execute(this::drain);
            return true;
        } catch (RejectedExecutionException rejected) {
            drainScheduled.set(false);
            latest.set(null);
            return false;
        }
    }

    private void drain() {
        try {
            while (!closed.get()) {
                T value = latest.getAndSet(null);
                if (value == null) return;
                try {
                    handler.accept(value);
                } catch (Exception ignored) {
                    // A failed value must not stall later values or terminal cleanup.
                }
            }
        } finally {
            drainScheduled.set(false);
            if (!closed.get() && latest.get() != null) scheduleDrain();
        }
    }
}
