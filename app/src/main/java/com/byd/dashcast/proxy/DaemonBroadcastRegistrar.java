package com.byd.dashcast.proxy;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

/** Registers daemon Binder broadcasts with a sender permission held by the uid-2000 shell. */
public final class DaemonBroadcastRegistrar {

    public static final String SENDER_PERMISSION = Manifest.permission.DUMP;

    private DaemonBroadcastRegistrar() {}

    public static void register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        context.registerReceiver(receiver, filter, SENDER_PERMISSION, null);
    }
}