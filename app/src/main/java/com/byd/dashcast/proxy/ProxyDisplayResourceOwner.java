package com.byd.dashcast.proxy;

import android.os.Binder;
import android.os.IBinder;

/** Process-owned token for transient VirtualDisplays created by ProxyDaemon. */
final class ProxyDisplayResourceOwner {

    private static final IBinder TOKEN = new Binder();

    private ProxyDisplayResourceOwner() {}

    static IBinder token() {
        return TOKEN;
    }
}
