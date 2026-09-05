package com.byd.dashcast.proxy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

import com.byd.dashcast.util.AppLogger

import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.2.62-beta — Phase A step 2: foreground liveness ping for the proxy daemon.
 *
 * Polls [ProxyClient.isConnected] every [PING_INTERVAL_MS] while the app is in the foreground
 * and [ProxyKeeperService] is not active. If the cached binder is dead (daemon crashed, killed,
 * OOM), triggers [ProxyClient.connect] synchronously on the watchdog thread so the next
 * user-initiated typed verb is instant instead of paying the ~1 s bootstrap cost on its first
 * call.
 *
 * Kotlin port note: the Java methods were `static synchronized`, which locks the CLASS object.
 * A Kotlin `@Synchronized` member of an object locks the INSTANCE instead — a different monitor.
 * Verified that nothing outside this file ever locks ProxyWatchdog.class, so either would be
 * correct, but the explicit `synchronized(ProxyWatchdog::class.java)` blocks below keep the
 * original monitor rather than relying on that argument holding forever.
 */
@SuppressLint("StaticFieldLeak") // sAppCtx is an application context, process-scoped, safe.
                                 // The suppression sits on the object because lint anchors
                                 // StaticFieldLeak to the enclosing static holder, not the field.
object ProxyWatchdog {

    private const val TAG = "ProxyWatchdog"

    /** Foreground ping interval. 30 s strikes the balance between « keep the daemon hot »
     *  and « no battery impact ». */
    private const val PING_INTERVAL_MS = 30_000L

    /** Singleton handler thread — one background thread for the entire process, owned by
     *  the watchdog. */
    @Volatile private var sThread: HandlerThread? = null
    @Volatile private var sHandler: Handler? = null

    /** Foreground tracking: incremented in onResume, decremented in onPause.
     *  Main-thread writes, watchdog-thread reads — AtomicInteger ensures visibility. */
    private val sForegroundCount = AtomicInteger(0)

    /** Application context cached at install(); used by connect() retries. */
    @Volatile private var sAppCtx: Context? = null

    /** Set true once [install] has wired the activity callbacks, so a second install
     *  attempt no-ops. Deliberately a plain field, as in the Java: it is written under the
     *  monitor but read unsynchronised by isInstalled(). */
    private var sInstalled = false

    /** True while [ProxyKeeperService] owns the stronger 10 s real Binder heartbeat.
     *  The foreground watchdog remains installed as a fallback but releases its HandlerThread. */
    @Volatile private var sKeeperActive = false

    /** Last time we successfully observed a live binder, for debug. */
    @Volatile private var sLastSeenAliveMs = 0L

    /**
     * Wire the watchdog. Idempotent: a second call returns immediately.
     * Call from [com.byd.dashcast.DashCastApp.onCreate].
     */
    @JvmStatic
    fun install(app: Application) {
        synchronized(ProxyWatchdog::class.java) {
            if (sInstalled) return
            sInstalled = true
            sAppCtx = app.applicationContext

            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) {
                    if (sForegroundCount.incrementAndGet() == 1) startPolling()
                }
                override fun onActivityPaused(a: Activity) {
                    if (sForegroundCount.get() > 0 && sForegroundCount.decrementAndGet() == 0) stopPolling()
                }
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })

            AppLogger.i(TAG, "installed (interval=" + PING_INTERVAL_MS + "ms)")
        }
    }

    /**
     * True while at least one DashCast Activity is resumed.
     *
     * Read by `HotspotKeeper`: a resumed window is a background-activity-start exemption in its
     * own right, so the in-app TetherFi launch route stays usable while the Hotspot page is open
     * even when the "display over other apps" permission was never granted. Cheap and lock-free —
     * the counter is already maintained by [install].
     */
    @JvmStatic
    fun isAppForeground(): Boolean = sForegroundCount.get() > 0

    /** Start the periodic ping on the background handler thread. */
    private fun startPolling() {
        synchronized(ProxyWatchdog::class.java) {
            if (!shouldPoll(sKeeperActive, sForegroundCount.get())) return
            if (sThread == null) {
                val thread = HandlerThread("proxy-watchdog")
                thread.start()
                sThread = thread
                sHandler = Handler(thread.looper)
            }
            // Cancel anything pending then schedule fresh.
            val handler = sHandler ?: return
            handler.removeCallbacks(sTick)
            handler.postDelayed(sTick, PING_INTERVAL_MS)
            AppLogger.d(TAG, "polling started")
        }
    }

    /** Stop the periodic ping. The handler thread is kept around — cheap to reuse on the
     *  next foreground transition. */
    private fun stopPolling() {
        synchronized(ProxyWatchdog::class.java) {
            sHandler?.removeCallbacks(sTick)
            AppLogger.d(TAG, "polling stopped")
        }
    }

    /** Called after the keeper has fully started its own heartbeat.
     *  Public + @JvmStatic rather than internal: ProxyKeeperService.java calls it, and an
     *  `internal fun` would be name-mangled and unreachable from Java. */
    @JvmStatic
    fun noteKeeperStarted() {
        synchronized(ProxyWatchdog::class.java) {
            sKeeperActive = true
            val handler = sHandler
            val thread = sThread
            sHandler = null
            sThread = null
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
            AppLogger.d(TAG, "keeper active — foreground watchdog thread released")
        }
    }

    /** Restores foreground-only monitoring if the always-on keeper stops. Same visibility
     *  reasoning as [noteKeeperStarted]. */
    @JvmStatic
    fun noteKeeperStopped() {
        synchronized(ProxyWatchdog::class.java) {
            sKeeperActive = false
            if (shouldPoll(false, sForegroundCount.get())) startPolling()
        }
    }

    /** Package-private in the Java original; `internal` is the closest Kotlin equivalent and is
     *  enough, because the only outside caller is the Kotlin unit test in the same module. */
    internal fun shouldPoll(keeperActive: Boolean, foregroundCount: Int): Boolean =
            !keeperActive && foregroundCount > 0

    private val sTick = object : Runnable {
        override fun run() {
            try {
                tickInternal()
            } catch (t: Throwable) {
                // Watchdog must never crash the app.
                AppLogger.w(TAG, "tick threw: " + t.javaClass.simpleName + ": " + t.message)
            } finally {
                // Only re-arm while foreground and not superseded by the keeper.
                val handler = sHandler
                if (shouldPoll(sKeeperActive, sForegroundCount.get()) && handler != null) {
                    handler.postDelayed(this, PING_INTERVAL_MS)
                }
            }
        }
    }

    private fun tickInternal() {
        val ctx = sAppCtx ?: return
        if (ProxyClient.isConnected()) {
            sLastSeenAliveMs = SystemClock.elapsedRealtime()
            return
        }
        // Dead binder detected proactively. Try to bring it back so the next user action is
        // instant. Cooldown-gated inside connect().
        val downForMs = if (sLastSeenAliveMs == 0L) -1L
                        else SystemClock.elapsedRealtime() - sLastSeenAliveMs
        AppLogger.i(TAG, "binder dead (downFor="
                + (if (downForMs < 0) "unknown" else downForMs.toString() + "ms")
                + ") — proactive reconnect")
        val ok = ProxyClient.connect(ctx)
        if (ok) {
            sLastSeenAliveMs = SystemClock.elapsedRealtime()
            AppLogger.i(TAG, "proactive reconnect ✅ pid=" + ProxyClient.getDaemonPid())
        } else {
            AppLogger.w(TAG, "proactive reconnect ❌ — will retry in "
                    + (PING_INTERVAL_MS / 1000) + "s")
        }
    }

    /** Test helper (Diag): age of the last "binder alive" observation. */
    @JvmStatic
    fun getMsSinceLastSeenAlive(): Long =
            if (sLastSeenAliveMs == 0L) -1L else SystemClock.elapsedRealtime() - sLastSeenAliveMs

    /** Test helper (Diag): true once [install] ran. */
    @JvmStatic
    fun isInstalled(): Boolean = sInstalled

    /** Test helper (Diag): current foreground activity count. */
    @JvmStatic
    fun getForegroundCount(): Int = sForegroundCount.get()

    /** Test helper (Diag): true while ProxyKeeperService owns daemon monitoring. */
    @JvmStatic
    fun isKeeperActive(): Boolean = sKeeperActive
}
