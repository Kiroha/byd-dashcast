package com.byd.dashcast.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.2.73-beta — Phase A step 4, Couche 2: foreground service that keeps the BetaProxy daemon
 * alive 24/7, regardless of whether any DashCast activity is in the foreground.
 *
 * Where [ProxyWatchdog] only polled while an activity was resumed (so a background-only daemon
 * death stayed undetected until the user came back), this service runs as long as the process is
 * alive — and a `START_STICKY` return value asks Android to revive it after any OOM kill.
 * Combined with [com.byd.dashcast.proxy.daemon.ProxyDaemonMain]'s own `oom_score_adj = -900`
 * hardening (Couche 3) and the `BootReceiver` in v1.2.72 (Couche 1), the daemon should now be
 * reachable in essentially every situation short of the user having disabled ADB (which is
 * unrecoverable by design).
 *
 * Heartbeat: every [HEARTBEAT_MS] milliseconds we check [ProxyClient.isConnected] (≈ 5 µs, just a
 * volatile read + `isBinderAlive()`). When the binder is dead we synchronously call
 * [ProxyClient.connect] which internally respects the 10 s reconnect cooldown so cascading
 * bootstraps remain impossible.
 *
 * Notification: low-importance silent channel, no sound, no vibration, not dismissible — it must
 * stay visible because Android requires every foreground service to display one. The intent
 * points back at [MainActivity] so a user tap just opens the app.
 *
 * Always active — the daemon is the default projection path.
 */
class ProxyKeeperService : Service() {

    private var mThread: HandlerThread? = null
    private var mHandler: Handler? = null
    @Volatile private var mRunning = false
    @Volatile private var mLastSeenAliveMs = 0L

    /** Set once we've registered the daemon HUD push-feedback listener for the current daemon
     *  connection; reset on reconnect / respawn so it re-arms against the fresh daemon. */
    @Volatile private var mHudListenerArmed = false

    /**
     * Failed arm attempts against the CURRENT daemon connection. See [ARM_MAX_ATTEMPTS].
     *
     * Atomic because it is incremented on the arm executor and reset on the heartbeat thread; a
     * plain int would let a reconnect's reset be lost against an in-flight increment, and the
     * budget would then run out early on a daemon that had just come back.
     */
    private val mArmAttempts = AtomicInteger(0)

    /** Serial executor for arming the HUD listener — reused across heartbeats instead of spawning
     *  a new "hud-listener-arm" Thread per arm (which churned one thread per heartbeat under
     *  persistent canListenStart failure). Shut down in onDestroy. */
    private val mArmExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "hud-listener-arm")
        t.isDaemon = true
        t
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
        val thread = HandlerThread("proxy-keeper")
        thread.start()
        mThread = thread
        mHandler = Handler(thread.looper)
        mRunning = true
        mHandler?.post(mTick)
        ProxyWatchdog.noteKeeperStarted()
        AppLogger.i(TAG, "started (heartbeat=" + HEARTBEAT_MS + "ms)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if Android kills us we get re-created with a null intent. The onCreate
        // path above is enough to bootstrap state.
        return START_STICKY
    }

    override fun onDestroy() {
        mRunning = false
        mHandler?.removeCallbacksAndMessages(null)
        mThread?.quitSafely()
        mArmExecutor.shutdownNow()
        ProxyWatchdog.noteKeeperStopped()
        AppLogger.i(TAG, "stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null /* not bound */

    private val mTick = object : Runnable {
        override fun run() {
            if (!mRunning) return
            try {
                tickInternal()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "tick threw: " + t.javaClass.simpleName + ": " + t.message)
            } finally {
                val handler = mHandler
                if (mRunning && handler != null) {
                    handler.postDelayed(this, HEARTBEAT_MS)
                }
            }
        }
    }

    private fun tickInternal() {
        val ctx = applicationContext
        // v1.6.x — app-wide persistent hotspot keep-alive rides this always-on FG heartbeat so
        // the hotspot "always on" survives HotspotActivity being closed (INC-20260705-195419: the
        // in-Activity watchdog stopped on onPause). No-op unless the user enabled it; internally
        // throttled to ~20 s.
        try {
            com.byd.dashcast.ui.hotspot.HotspotKeeper.maybeKeepAlive(ctx)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "hotspot keep-alive threw: " + t.message)
        }
        // v1.3.3 — Defense in depth against silent binder deaths seen on DiLink 3 / Android 10:
        // do a real pingBinder() round-trip (cheap, ~1 ms) rather than only the local
        // isBinderAlive() check. pingBinder returns false the instant the daemon process is gone,
        // even when the kernel's binderDied() notification was never delivered to our
        // DeathRecipient. This converts every "stuck alive" case into a recoverable one within
        // HEARTBEAT_MS.
        val b: IBinder? = ProxyClient.getProxyDaemonBinder()
        val alive = b != null && b.pingBinder()
        if (b != null && !alive) {
            ProxyClient.invalidateBinderIfCurrent(b, "KeeperPing")
        }

        if (alive) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime()
            armHudListener()   // keep the HUD push-feedback listener registered app-wide
            // Rolling screenshot recorder for the bug reporter — no-op unless projection is
            // active AND the feature is enabled; internally throttled to ~15 s and self-cleaning.
            // Rides this always-on heartbeat so it needs no timer of its own (like HotspotKeeper).
            try {
                com.byd.dashcast.report.ClusterShotRecorder.maybeCapture(ctx)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "shot recorder threw: " + t.message)
            }
            return
        }
        val downForMs = if (mLastSeenAliveMs == 0L) -1L
                        else SystemClock.elapsedRealtime() - mLastSeenAliveMs
        AppLogger.i(TAG, "binder dead (downFor="
                + (if (downForMs < 0) "unknown" else downForMs.toString() + "ms")
                + ") — proactive reconnect from keeper")
        val ok = ProxyClient.connect(ctx)
        if (ok) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime()
            mHudListenerArmed = false   // fresh daemon → re-arm the HUD listener on the next tick
            mArmAttempts.set(0)         // and give it the full retry budget again
            AppLogger.i(TAG, "keeper reconnect ✅ pid=" + ProxyClient.getDaemonPid())
        } else {
            AppLogger.w(TAG, "keeper reconnect ❌ — retrying in " + (HEARTBEAT_MS / 1000) + "s")
        }
    }

    /**
     * Keep the BYDAuto HUD push-feedback listener registered app-wide (off-thread, guarded) so the
     * bug report / diag can read the HUD's ACTUAL switch + display-mode. That state is push-only
     * (get() returns 0) and the OEM nav pushes it once at nav-start — capturing it reliably needs
     * the listener already registered, which this guarantees. Idempotent daemon-side; armed once
     * per connection, re-armed after a reconnect / respawn.
     */
    private fun armHudListener() {
        if (mHudListenerArmed) return
        mHudListenerArmed = true
        try {
            mArmExecutor.execute {
                // Fail fast on a cold daemon instead of blocking this worker ~23s (mirrors F6).
                ProxyClient.setNonBlockingReconnect(true)
                var r: String? = null
                try {
                    r = ProxyClient.canListenStart()
                } catch (t: Throwable) {
                    r = null
                    AppLogger.w(TAG, "HUD listener arm failed: " + t.message)
                }
                // Latch on the daemon's VERDICT, not on having made the attempt. The flag was set
                // before the call and only cleared when the call THREW, so a daemon that answered
                // "register-in-flight" or "ERR register timeout" — the normal shape of a daemon
                // still coming up — left the listener unarmed for the life of the process, with
                // no exception anywhere. HUD push-feedback is push-only and captured once at
                // nav-start, so every bug report from that car then carried no HUD state at all.
                if (isListenerArmed(r)) {
                    mArmAttempts.set(0)
                    return@execute
                }
                val attempt = mArmAttempts.incrementAndGet()
                if (attempt < ARM_MAX_ATTEMPTS) {
                    mHudListenerArmed = false   // retry on the next alive tick
                    AppLogger.w(TAG, "HUD listener not armed (" + r + ") — retry in "
                            + (HEARTBEAT_MS / 1000) + "s, attempt " + attempt
                            + "/" + ARM_MAX_ATTEMPTS)
                } else {
                    AppLogger.w(TAG, "HUD listener not armed (" + r + ") after "
                            + ARM_MAX_ATTEMPTS + " attempts — giving up until the next reconnect")
                }
            }
        } catch (ree: RejectedExecutionException) {
            mHudListenerArmed = false   // executor shut down (service stopping) — allow a retry
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proxy_keeper_channel_name),
                NotificationManager.IMPORTANCE_MIN)
        ch.description = getString(R.string.proxy_keeper_channel_desc)
        ch.setShowBadge(false)
        ch.enableVibration(false)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = Intent(this, MainActivity::class.java)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, tap, piFlags)

        val b = Notification.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.proxy_keeper_notif_title))
                .setContentText(getString(R.string.proxy_keeper_notif_text))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
        return b.build()
    }

    companion object {
        private const val TAG = "ProxyKeeperService"

        /** Heartbeat interval. 10 s is short enough to recover a dead daemon within one user
         *  interaction, long enough to be invisible in battery reports (a heartbeat that does
         *  nothing is just one volatile read). */
        internal const val HEARTBEAT_MS = 10_000L

        /** Stable notification ID. Picked far above any standard app range to avoid collisions
         *  with notifications coming from elsewhere. */
        private const val NOTIF_ID = 0xDC70
        private const val CHANNEL_ID = "dashcast_proxy_keeper"

        /**
         * How many times to retry arming before giving up until the next reconnect.
         *
         * A car with no `BYDAutoSettingDevice` fails every attempt, and retrying it forever would
         * cost a binder round-trip and a journal line every [HEARTBEAT_MS] for as long as the app
         * runs — the one way this retry could be worse than the bug it fixes. 30 attempts is five
         * minutes at the current heartbeat, which covers a daemon that is merely slow to come up.
         */
        private const val ARM_MAX_ATTEMPTS = 30

        /** Idempotent starter. Safe to call from anywhere on the main thread. */
        @JvmStatic
        fun ensureRunning(ctx: Context) {
            try {
                val i = Intent(ctx, ProxyKeeperService::class.java)
                ctx.startForegroundService(i)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "ensureRunning failed: " + t)
            }
        }

        /**
         * Whether `canListenStart` actually registered the listener.
         *
         * The verb answers with a status string, and only two of its answers mean the listener is
         * live: `"registered (…)"` and `"already-registered"`. Everything else —
         * `"register-in-flight"`, `"ERR register timeout"`, any `"ERR …"` — is a daemon that is
         * not yet listening, and the retry is safe because `CanFeedbackListener.startSetting`
         * short-circuits on both `sRegistered` and `sRegisterInFlight`, so it can never register
         * a duplicate device listener.
         *
         * `reply` stays NULLABLE: HudListenerArmVerdictTest calls isListenerArmed(null), and the
         * arm path above sets r = null when canListenStart throws.
         */
        internal fun isListenerArmed(reply: String?): Boolean =
                reply != null
                        && (reply.startsWith("registered") || reply.startsWith("already-registered"))
    }
}
