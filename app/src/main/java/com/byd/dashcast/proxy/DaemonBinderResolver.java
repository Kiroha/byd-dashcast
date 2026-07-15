package com.byd.dashcast.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Method;

import com.byd.dashcast.util.AppLogger;

/**
 * Retrieves the MirrorDaemon Binder from ServiceManager via reflection.
 * Used in onStart() when the daemon was already running before MainActivity launched.
 *
 * Call {@link #fetch(Callback)} from the main thread; the callback fires on the main
 * thread if and only if the binder is found.
 */
public final class DaemonBinderResolver {

    private static final String TAG         = "DaemonBinderResolver";
    private static final String SERVICE_KEY = "byd_mirror_daemon";

    private DaemonBinderResolver() {}

    public interface Callback {
        void onFound(IBinder binder);
    }

    /**
     * Returns a {@link BroadcastReceiver} for {@code MirrorDaemon.ACTION_DAEMON_READY}.
     * Extracts the Binder from the intent extras and fires {@code callback.onFound()} if present.
     * Register/unregister this receiver in onCreate/onDestroy via {@code registerReceiver}.
     */
    public static BroadcastReceiver createActionReceiver(Callback callback) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                if (extras == null) return;
                IBinder binder = extras.getBinder("daemon_binder");
                if (binder == null) return;
                // The DAEMON_READY broadcast is implicitly exported (targetSdk 29) and only
                // sender-side permission-protected, so a co-installed app could spoof it with a
                // fake binder. Adopt the broadcast binder ONLY if it matches the one the real
                // daemon registered in the global ServiceManager — only uid-2000/system can
                // addService (SELinux blocks untrusted apps), so that entry is trustworthy, and
                // BinderProxy identity is stable for the same remote binder. If the daemon didn't
                // register one (getService null — addService failed on this ROM), fall back to the
                // broadcast binder (prior behaviour) so this can never break the daemon path; the
                // fetch()/ServiceManager path below is itself already trustworthy.
                IBinder registered = lookupRegisteredBinder();
                if (registered != null && registered != binder) {
                    AppLogger.w(TAG, "DAEMON_READY binder ≠ ServiceManager entry — ignoring (spoofed?)");
                    return;
                }
                AppLogger.i(TAG, "DaemonBinder received via broadcast ✓"
                        + (registered != null ? " (verified vs ServiceManager)"
                                              : " (no ServiceManager entry — trusted broadcast)"));
                callback.onFound(binder);
            }
        };
    }

    /**
     * Synchronous, non-throwing lookup of the registered mirror-daemon Binder (or null).
     * For background callers that already run off the main thread (e.g. the screenshot
     * recorder) and want the binder without the async {@link #fetch} callback.
     */
    public static IBinder getRegisteredBinderOrNull() {
        return lookupRegisteredBinder();
    }

    /** Reflective {@code ServiceManager.getService(SERVICE_KEY)}; null if absent or on any error. */
    private static IBinder lookupRegisteredBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            return (IBinder) getService.invoke(null, SERVICE_KEY);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Spawns a background thread to look up the daemon Binder via ServiceManager reflection. */
    public static void fetch(Callback callback) {
        new Thread(() -> {
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getDeclaredMethod("getService", String.class);
                getService.setAccessible(true);
                IBinder binder = (IBinder) getService.invoke(null, SERVICE_KEY);
                if (binder != null) {
                    AppLogger.i(TAG, "DaemonBinder retrieved from ServiceManager ✓");
                    new Handler(Looper.getMainLooper()).post(() -> callback.onFound(binder));
                } else {
                    AppLogger.d(TAG, "DaemonBinder not found (daemon not yet started?)");
                }
            } catch (Exception e) {
                AppLogger.w(TAG, "fetch failed: " + e.getMessage());
            }
        }, "sm-daemon-lookup").start();
    }
}
