package com.byd.dashcast.hud

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.BoundedSerialExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Reassert an existing approval on Android 10; never revoke or obtain notification access. */
internal object NavigationListenerApprovalRefresh {
    private val busy = AtomicBoolean()
    private val executor = BoundedSerialExecutor(1) { action ->
        Thread(action, "nav-listener-refresh").apply { isDaemon = true }
    }
    @Volatile var lastResult = "none"
        private set

    fun request(ctx: Context, component: ComponentName, stillDisconnected: () -> Boolean) {
        // This fallback targets the confirmed Android 10 field failure. Other platform versions
        // continue to use the public API until their binding behavior has been verified.
        if (Build.VERSION.SDK_INT != 29 || !busy.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    lastResult = refreshIfApproved(ctx, component, stillDisconnected) { command ->
                        if (!ProxyClient.isConnected()) return@refreshIfApproved "proxy_unavailable"
                        ProxyClient.setNonBlockingReconnect(true)
                        try { ProxyClient.runShell(command) }
                        finally { ProxyClient.setNonBlockingReconnect(false) }
                    }
                    AppLogger.i("NavListenerKeeper", "existing listener approval refresh: $lastResult")
                } catch (e: Exception) {
                    lastResult = "error_${e.javaClass.simpleName}"
                    AppLogger.w("NavListenerKeeper", "existing listener approval refresh: $lastResult")
                } finally { busy.set(false) }
            }
        } catch (_: RejectedExecutionException) {
            busy.set(false)
            lastResult = "queue_unavailable"
        }
    }

    internal fun refreshIfApproved(ctx: Context, component: ComponentName,
                                  stillDisconnected: () -> Boolean,
                                  runShell: (String) -> String): String {
        val expected = ComponentName(ctx, MapNotificationListenerService::class.java)
        if (component != expected || !stillDisconnected() ||
            !ClusterPrefs.getNavigationOutputs(ctx).enabled ||
            ctx.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(expected) != true) return "skipped"
        // Android's UID layout identifies the owning user, not whichever user is currently active.
        val output = runShell(command(component, Process.myUid() / 100_000)).trim()
        return when (output) {
            "requested", "no_grant", "failed", "proxy_unavailable" -> output
            else -> "unexpected_result"
        }
    }

    internal fun command(component: ComponentName, userId: Int): String {
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        require(userId >= 0 && full.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")))
        // Re-check the exact grant inside the SAME shell invocation, after any queue delay.
        // No disallow_listener, settings write, grant for another component, or sensitive output.
        // AOSP Android 10 allow_listener calls rebindServices even when approval is unchanged,
        // whereas requestBindListener returns early when its enabled state is already true.
        return "dc_nav_grants=\$(settings --user $userId get secure enabled_notification_listeners 2>/dev/null); " +
            "case \":\$dc_nav_grants:\" in *':$full:'*|*':$short:'*) " +
            "if cmd notification allow_listener '$full' $userId >/dev/null 2>&1; " +
            "then printf requested; else printf failed; fi ;; *) printf no_grant ;; esac"
    }
}
