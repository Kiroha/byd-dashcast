package com.byd.dashcast.proxy;

import android.os.Binder;
import android.os.IBinder;

/** Process-lifetime token whose Binder death lets MirrorDaemon release transient mirror state. */
public final class MirrorResourceOwner {

    private static final IBinder TOKEN = new Binder();

    private MirrorResourceOwner() {}

    public static IBinder token() {
        return TOKEN;
    }
}
