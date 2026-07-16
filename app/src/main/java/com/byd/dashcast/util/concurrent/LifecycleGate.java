package com.byd.dashcast.util.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-way lifecycle gate for invalidating asynchronous work owned by a destroyed component. */
public final class LifecycleGate {

    private final AtomicBoolean active = new AtomicBoolean(true);

    public Token capture() {
        return new Token(active);
    }

    public void invalidate() {
        active.set(false);
    }

    public static final class Token {
        private final AtomicBoolean active;

        private Token(AtomicBoolean active) {
            this.active = active;
        }

        public boolean isValid() {
            return active.get();
        }
    }
}
