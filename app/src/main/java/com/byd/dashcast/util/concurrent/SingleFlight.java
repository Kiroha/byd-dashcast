package com.byd.dashcast.util.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces concurrent callers onto one leader operation and one immutable result. */
public final class SingleFlight<T> {

    private Attempt<T> current;

    public synchronized Ticket<T> join() {
        if (current == null) {
            current = new Attempt<>();
            return new Ticket<>(this, current, true);
        }
        return new Ticket<>(this, current, false);
    }

    private synchronized void clear(Attempt<T> attempt) {
        if (current == attempt) current = null;
    }

    private static final class Attempt<T> {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicBoolean completionClaimed = new AtomicBoolean(false);
        private volatile T result;

        void complete(T value) {
            if (!completionClaimed.compareAndSet(false, true)) {
                throw new IllegalStateException("single-flight attempt already completed");
            }
            result = value;
            completed.countDown();
        }

        T await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
            if (!completed.await(timeout, unit)) {
                throw new TimeoutException("single-flight result timed out");
            }
            return result;
        }
    }

    public static final class Ticket<T> {
        private final SingleFlight<T> owner;
        private final Attempt<T> attempt;
        private final boolean leader;

        private Ticket(SingleFlight<T> owner, Attempt<T> attempt, boolean leader) {
            this.owner = owner;
            this.attempt = attempt;
            this.leader = leader;
        }

        public boolean isLeader() {
            return leader;
        }

        public void complete(T result) {
            if (!leader) throw new IllegalStateException("only the leader can complete an attempt");
            attempt.complete(result);
            owner.clear(attempt);
        }

        public T await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
            return attempt.await(timeout, unit);
        }
    }
}
