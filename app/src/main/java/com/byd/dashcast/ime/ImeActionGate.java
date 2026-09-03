package com.byd.dashcast.ime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Single-flight lifecycle gate for an asynchronous IME action and its completion. */
final class ImeActionGate {
    interface Completion {
        void onComplete(boolean accepted);
    }

    static final class Operation {
        private final Completion completion;
        private final AtomicBoolean completed = new AtomicBoolean();

        Operation(Completion completion) {
            this.completion = completion;
        }

        void complete(boolean accepted) {
            if (!completed.compareAndSet(false, true)) return;
            completion.onComplete(accepted);
        }
    }

    private Operation current;

    synchronized Operation begin(Completion completion) {
        if (current != null) return null;
        current = new Operation(completion);
        return current;
    }

    synchronized boolean isCurrent(Operation operation) {
        return operation != null && current == operation;
    }

    void finish(Operation operation, boolean accepted) {
        boolean wasCurrent;
        synchronized (this) {
            wasCurrent = current == operation;
            if (wasCurrent) current = null;
        }
        operation.complete(accepted && wasCurrent);
    }

    void cancelCurrent() {
        Operation operation;
        synchronized (this) {
            operation = current;
            current = null;
        }
        if (operation != null) operation.complete(false);
    }
}