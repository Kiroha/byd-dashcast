package com.byd.dashcast.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

import com.byd.dashcast.util.AppLogger

/**
 * Retrieves the [com.byd.dashcast.proxy.daemon.SurfaceDaemon] Binder from ServiceManager via
 * reflection. Used in onStart() when the daemon was already running before MainActivity launched.
 *
 * **This is the one place the surface daemon's binder is looked up.** DashCast runs TWO uid-2000
 * daemons (see the boundary documented on [com.byd.dashcast.proxy.daemon.SurfaceDaemon] and
 * [com.byd.dashcast.proxy.daemon.ProxyDaemonMain]):
 *  - `ProxyClient.getProxyDaemonBinder()` → the PROXY daemon, which **DOES** things (shell +
 *    one-shot verbs) and enforces `ProxyDaemonContract.DESCRIPTOR`;
 *  - [surfaceDaemonBinder] (here) → the SURFACE daemon, which **HOLDS** things (the preview mirror,
 *    the cluster slot overlay windows and their trusted VirtualDisplays, touch injection) and
 *    enforces `SurfaceDaemon.DESCRIPTOR`.
 *
 * They are different processes with different binders. Never send one daemon's DESCRIPTOR to the
 * other's binder: the receiving `enforceInterface` rejects it and the transaction silently does
 * nothing.
 *
 * **The lookup lives here, but the binder REACHES callers under several names — grep them all.**
 * This file is the only place the service name and the reflection exist, yet a grep for
 * [surfaceDaemonBinder] alone finds a minority of the call sites, because the binder is also
 * passed around after being obtained once:
 *  - `FissionClient.getBinderFromServiceManager()` — an alias that delegates straight here; used by
 *    the whole Layout / Fission subsystem (`FissionOrchestrator`, `LayoutManagerActivity`);
 *  - `MainActivity.getSurfaceDaemonBinder()` — the cached `mDaemonBinder`, populated from the
 *    `ACTION_DAEMON_READY` broadcast extra, NOT from this file. It backs the **main preview**
 *    (`MirrorCoordinator` start/stop) — the app's most-used surface-daemon path;
 *  - `ClusterInputForwarder.setDaemonBinder(...)` — the field behind **every cluster touch and key**;
 *  - `FissionLayoutEditorActivity` — receives it as a `BinderParcelable` Intent extra.
 * So: to enumerate every surface-daemon call site, grep `surfaceDaemonBinder`,
 * `getBinderFromServiceManager`, `getSurfaceDaemonBinder`, `setDaemonBinder` and `EXTRA_DAEMON_BINDER`
 * — or, more reliably, grep the one thing they all end in: `SurfaceDaemon.DESCRIPTOR`.
 *
 * Call [fetch] from the main thread; the callback fires on the main
 * thread if and only if the binder is found.
 */
object DaemonBinderResolver {

    private const val TAG         = "DaemonBinderResolver"

    /** WIRE IDENTIFIER — the ServiceManager name the daemon registers under. Must stay byte-equal
     *  to the literal in `SurfaceDaemon.main()`; a daemon spawned by an older APK already used it. */
    private const val SERVICE_KEY = "byd_mirror_daemon"

    fun interface Callback {
        fun onFound(binder: IBinder)
    }

    /**
     * Returns a [BroadcastReceiver] for `SurfaceDaemon.ACTION_DAEMON_READY`.
     * Extracts the Binder from the intent extras and fires `callback.onFound()` if present.
     * Register/unregister this receiver in onCreate/onDestroy via `registerReceiver`.
     */
    @JvmStatic
    fun createActionReceiver(callback: Callback): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras ?: return
                val binder = extras.getBinder("daemon_binder") ?: return
                // The DAEMON_READY broadcast is implicitly exported (targetSdk 29) and only
                // sender-side permission-protected, so a co-installed app could spoof it with a
                // fake binder. Adopt the broadcast binder ONLY if it matches the one the real
                // daemon registered in the global ServiceManager — only uid-2000/system can
                // addService (SELinux blocks untrusted apps), so that entry is trustworthy, and
                // BinderProxy identity is stable for the same remote binder. If the daemon didn't
                // register one (getService null — addService failed on this ROM), fall back to the
                // broadcast binder (prior behaviour) so this can never break the daemon path; the
                // fetch()/ServiceManager path below is itself already trustworthy.
                val registered = lookupRegisteredBinder()
                if (registered != null && registered !== binder) {
                    AppLogger.w(TAG, "DAEMON_READY binder ≠ ServiceManager entry — ignoring (spoofed?)")
                    return
                }
                AppLogger.i(TAG, "DaemonBinder received via broadcast ✓"
                        + (if (registered != null) " (verified vs ServiceManager)"
                                                   else " (no ServiceManager entry — trusted broadcast)"))
                callback.onFound(binder)
            }
        }
    }

    /**
     * Synchronous, non-throwing lookup of the registered SURFACE daemon Binder (or null).
     *
     * The single implementation behind every sanctioned source of the binder that
     * `SurfaceDaemon.DESCRIPTOR` may be paired with; `FissionClient.getBinderFromServiceManager()`
     * is an alias that calls straight into it — see the object doc. Safe from any thread
     * (reflection + a ServiceManager lookup, no IPC to the daemon), so background callers such as
     * the screenshot recorder can use it directly without the async [fetch] callback.
     */
    @JvmStatic
    fun surfaceDaemonBinder(): IBinder? {
        return lookupRegisteredBinder()
    }

    /** Reflective `ServiceManager.getService(SERVICE_KEY)`; null if absent or on any error. */
    private fun lookupRegisteredBinder(): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, SERVICE_KEY) as IBinder?
        } catch (t: Throwable) {
            null
        }
    }

    /** Spawns a background thread to look up the daemon Binder via ServiceManager reflection. */
    @JvmStatic
    fun fetch(callback: Callback) {
        Thread({
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getDeclaredMethod("getService", String::class.java)
                getService.isAccessible = true
                val binder = getService.invoke(null, SERVICE_KEY) as IBinder?
                if (binder != null) {
                    AppLogger.i(TAG, "DaemonBinder retrieved from ServiceManager ✓")
                    Handler(Looper.getMainLooper()).post { callback.onFound(binder) }
                } else {
                    AppLogger.d(TAG, "DaemonBinder not found (daemon not yet started?)")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "fetch failed: " + e.message)
            }
        }, "sm-daemon-lookup").start()
    }
}
