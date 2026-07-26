package com.byd.dashcast.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

import com.byd.dashcast.util.AppLogger

/**
 * Retrieves the MirrorDaemon Binder from ServiceManager via reflection.
 * Used in onStart() when the daemon was already running before MainActivity launched.
 *
 * Call [fetch] from the main thread; the callback fires on the main
 * thread if and only if the binder is found.
 */
object DaemonBinderResolver {

    private const val TAG         = "DaemonBinderResolver"
    private const val SERVICE_KEY = "byd_mirror_daemon"

    fun interface Callback {
        fun onFound(binder: IBinder)
    }

    /**
     * Returns a [BroadcastReceiver] for `MirrorDaemon.ACTION_DAEMON_READY`.
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
     * Synchronous, non-throwing lookup of the registered mirror-daemon Binder (or null).
     * For background callers that already run off the main thread (e.g. the screenshot
     * recorder) and want the binder without the async [fetch] callback.
     */
    @JvmStatic
    fun getRegisteredBinderOrNull(): IBinder? {
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
