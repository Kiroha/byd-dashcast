package com.byd.dashcast.hud

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.util.AppLogger

/**
 * Runs from ProxyKeeperService's background heartbeat, including after boot and ACC-on.
 * Only Android's listener lifecycle confirms connectivity; an idle route is not a disconnect.
 * This never grants notification access, opens an Activity, or toggles the user's destinations.
 */
object NavigationListenerKeeper {
    private const val TAG = "NavListenerKeeper"
    private val recovery = NavigationListenerRecovery()

    internal fun onCreated(token: Any) = recovery.onCreated(token)

    internal fun onConnected(token: Any) {
        if (recovery.onConnected(token)) AppLogger.i(TAG, "notification listener connected")
    }

    internal fun onDisconnected(token: Any) {
        if (recovery.onDisconnected(token)) {
            AppLogger.w(TAG, "notification listener disconnected; recovery pending")
        }
    }

    internal fun onDestroyed(token: Any) = recovery.onDestroyed(token)

    fun maybeKeepAlive(ctx: Context) {
        recover(ctx) { NotificationListenerService.requestRebind(it) }
    }

    internal fun recover(ctx: Context, requestRebind: (ComponentName) -> Unit): Boolean {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        return try {
            val enabled = ClusterPrefs.getNavigationOutputs(app).enabled
            val component = ComponentName(app, MapNotificationListenerService::class.java)
            val manager = app.getSystemService(NotificationManager::class.java)
            // Check this exact component; another listener in the same package is not a grant.
            val granted = enabled && manager?.isNotificationListenerAccessGranted(component) == true
            recovery.maybeRecover(now, enabled, granted) {
                try {
                    requestRebind(component)
                    AppLogger.i(TAG, "notification listener rebind requested; " + summary())
                } catch (e: Exception) {
                    // A rejected request consumes the same backoff as an unconfirmed request.
                    AppLogger.w(TAG, "notification listener rebind failed: " + e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "notification listener probe failed: " + e.javaClass.simpleName)
            false
        }
    }

    /** Small, location-free snapshot for reports; a request is not a confirmed connection. */
    fun summary(): String {
        val state = recovery.snapshot(SystemClock.elapsedRealtime())
        return "connected=${state.connected} eligible=${state.eligible} " +
            "retryLevel=${state.attempts} requestInFlight=${state.inFlight} " +
            "lastRequestAgeMs=${state.lastAttemptAgeMs ?: "none"}"
    }
}
