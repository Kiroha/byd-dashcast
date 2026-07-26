package com.byd.dashcast.util.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Links one transient resource owner to a cleanup callback, exactly once. */
public final class DeathLease implements AutoCloseable {

    public interface Owner {
        void link(Runnable deathCallback) throws Exception;
        void unlink(Runnable deathCallback);
    }

    private final Owner owner;
    private final Runnable cleanup;
    private final Runnable deathCallback = this::ownerDied;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private DeathLease(Owner owner, Runnable cleanup) {
        this.owner = owner;
        this.cleanup = cleanup;
    }

    public static DeathLease attach(Owner owner, Runnable cleanup) throws Exception {
        if (owner == null) throw new IllegalArgumentException("owner required");
        if (cleanup == null) throw new IllegalArgumentException("cleanup required");
        DeathLease lease = new DeathLease(owner, cleanup);
        owner.link(lease.deathCallback);
        return lease;
    }

    public boolean isActive() {
        return active.get();
    }

    private void ownerDied() {
        if (!active.compareAndSet(true, false)) return;
        try {
            cleanup.run();
        } finally {
            owner.unlink(deathCallback);
        }
    }

    /** Ends explicit ownership without invoking death cleanup; the normal owner performs teardown. */
    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        owner.unlink(deathCallback);
    }
}
