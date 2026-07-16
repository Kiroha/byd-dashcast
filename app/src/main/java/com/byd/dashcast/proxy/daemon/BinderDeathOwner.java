package com.byd.dashcast.proxy.daemon;

import android.os.IBinder;

import com.byd.dashcast.util.concurrent.DeathLease;

/** Adapts an app-owned Binder token to the platform-independent DeathLease contract. */
final class BinderDeathOwner implements DeathLease.Owner {

    private final IBinder binder;
    private IBinder.DeathRecipient recipient;
    private Runnable callback;

    BinderDeathOwner(IBinder binder) {
        if (binder == null) throw new IllegalArgumentException("binder required");
        this.binder = binder;
    }

    @Override
    public synchronized void link(Runnable deathCallback) throws android.os.RemoteException {
        if (recipient != null) throw new IllegalStateException("owner already linked");
        callback = deathCallback;
        recipient = () -> {
            Runnable current;
            synchronized (BinderDeathOwner.this) {
                current = callback;
                callback = null;
                recipient = null;
            }
            if (current != null) current.run();
        };
        binder.linkToDeath(recipient, 0);
    }

    @Override
    public synchronized void unlink(Runnable deathCallback) {
        if (callback != deathCallback || recipient == null) return;
        IBinder.DeathRecipient current = recipient;
        callback = null;
        recipient = null;
        try { binder.unlinkToDeath(current, 0); } catch (Throwable ignored) {}
    }
}
