package com.byd.dashcast.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.MainActivity;
import com.byd.dashcast.R;

/**
 * v1.2.73-beta — Phase A step 4, Couche 2 : foreground service that keeps
 * the BetaProxy daemon alive 24/7, regardless of whether any DashCast
 * activity is in the foreground.
 *
 * <p>Where {@link ProxyWatchdog} only polled while an activity was resumed
 * (so a background-only daemon death stayed undetected until the user came
 * back), this service runs as long as the process is alive — and a
 * {@code START_STICKY} return value asks Android to revive it after any
 * OOM kill. Combined with {@link com.byd.dashcast.proxy.daemon.ProxyDaemonMain}'s
 * own {@code oom_score_adj = -900} hardening (Couche 3) and the
 * {@code BootReceiver} in v1.2.72 (Couche 1), the daemon should now be
 * reachable in essentially every situation short of the user having
 * disabled ADB (which is unrecoverable by design).
 *
 * <p>Heartbeat: every {@link #HEARTBEAT_MS} milliseconds we check
 * {@link ProxyClient#isConnected()} (≈ 5 µs, just a volatile read +
 * {@code isBinderAlive()}). When the binder is dead we synchronously call
 * {@link ProxyClient#connect(Context)} which internally respects the
 * 10 s reconnect cooldown so cascading bootstraps remain impossible.
 *
 * <p>Notification: low-importance silent channel, no sound, no vibration,
 * not dismissible — it must stay visible because Android requires every
 * foreground service to display one. The intent points back at
 * {@link MainActivity} so a user tap just opens the app.
 *
 * <p>Always active — the daemon is the default projection path.
 * users who haven't opted in see no service and no notification.
 */
public final class ProxyKeeperService extends Service {

    private static final String TAG = "ProxyKeeperService";

    /** Heartbeat interval. 10 s is short enough to recover a dead daemon
     *  within one user interaction, long enough to be invisible in battery
     *  reports (a heartbeat that does nothing is just one volatile read). */
    static final long HEARTBEAT_MS = 10_000L;

    /** Stable notification ID. Picked far above any standard app range to
     *  avoid collisions with notifications coming from elsewhere. */
    private static final int  NOTIF_ID      = 0xDC70;
    private static final String CHANNEL_ID  = "dashcast_proxy_keeper";

    private HandlerThread mThread;
    private Handler       mHandler;
    private volatile boolean mRunning;
    private volatile long mLastSeenAliveMs;

    /** Idempotent starter. Safe to call from anywhere on the main thread. */
    public static void ensureRunning(Context ctx) {
        try {
            Intent i = new Intent(ctx, ProxyKeeperService.class);
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            AppLogger.w(TAG, "ensureRunning failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification());
        mThread = new HandlerThread("proxy-keeper");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mRunning = true;
        mHandler.post(mTick);
        ProxyWatchdog.noteKeeperStarted();
        AppLogger.i(TAG, "started (heartbeat=" + HEARTBEAT_MS + "ms)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if Android kills us we get re-created with a null
        // intent. The onCreate path above is enough to bootstrap state.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        if (mThread != null) mThread.quitSafely();
        mArmExecutor.shutdownNow();
        ProxyWatchdog.noteKeeperStopped();
        AppLogger.i(TAG, "stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; /* not bound */ }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!mRunning) return;
            try {
                tickInternal();
            } catch (Throwable t) {
                AppLogger.w(TAG, "tick threw: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            } finally {
                if (mRunning && mHandler != null) {
                    mHandler.postDelayed(this, HEARTBEAT_MS);
                }
            }
        }
    };

    private void tickInternal() {
        Context ctx = getApplicationContext();
        // v1.6.x — app-wide persistent hotspot keep-alive rides this always-on FG
        // heartbeat so the hotspot "always on" survives HotspotActivity being closed
        // (INC-20260705-195419: the in-Activity watchdog stopped on onPause). No-op
        // unless the user enabled it; internally throttled to ~20 s.
        try {
            com.byd.dashcast.ui.hotspot.HotspotKeeper.maybeKeepAlive(ctx);
        } catch (Throwable t) {
            AppLogger.w(TAG, "hotspot keep-alive threw: " + t.getMessage());
        }
        // v1.3.3 — Defense in depth against silent binder deaths seen on
        // DiLink 3 / Android 10: do a real pingBinder() round-trip (cheap,
        // ~1 ms) rather than only the local isBinderAlive() check.
        // pingBinder returns false the instant the daemon process is gone,
        // even when the kernel's binderDied() notification was never
        // delivered to our DeathRecipient. This converts every "stuck
        // alive" case into a recoverable one within HEARTBEAT_MS.
        android.os.IBinder b = ProxyClient.getProxyDaemonBinder();
        boolean alive = (b != null) && b.pingBinder();
        if (b != null && !alive) {
            ProxyClient.invalidateBinderIfCurrent(b, "KeeperPing");
        }

        if (alive) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime();
            armHudListener();   // keep the HUD push-feedback listener registered app-wide
            // Rolling screenshot recorder for the bug reporter — no-op unless projection is
            // active AND the feature is enabled; internally throttled to ~15 s and self-cleaning.
            // Rides this always-on heartbeat so it needs no timer of its own (like HotspotKeeper).
            try {
                com.byd.dashcast.report.ClusterShotRecorder.maybeCapture(ctx);
            } catch (Throwable t) {
                AppLogger.w(TAG, "shot recorder threw: " + t.getMessage());
            }
            return;
        }
        long downForMs = mLastSeenAliveMs == 0 ? -1
                : SystemClock.elapsedRealtime() - mLastSeenAliveMs;
        AppLogger.i(TAG, "binder dead (downFor="
                + (downForMs < 0 ? "unknown" : downForMs + "ms")
                + ") — proactive reconnect from keeper");
        boolean ok = ProxyClient.connect(ctx);
        if (ok) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime();
            mHudListenerArmed = false;   // fresh daemon → re-arm the HUD listener on the next tick
            mArmAttempts.set(0);         // and give it the full retry budget again
            AppLogger.i(TAG, "keeper reconnect ✅ pid="
                    + ProxyClient.getDaemonPid());
        } else {
            AppLogger.w(TAG, "keeper reconnect ❌ — retrying in "
                    + (HEARTBEAT_MS / 1000) + "s");
        }
    }

    /** Set once we've registered the daemon HUD push-feedback listener for the current daemon
     *  connection; reset on reconnect / respawn so it re-arms against the fresh daemon. */
    private volatile boolean mHudListenerArmed = false;

    /**
     * Failed arm attempts against the CURRENT daemon connection. See {@link #ARM_MAX_ATTEMPTS}.
     *
     * Atomic because it is incremented on the arm executor and reset on the heartbeat thread; a
     * plain int would let a reconnect's reset be lost against an in-flight increment, and the
     * budget would then run out early on a daemon that had just come back.
     */
    private final java.util.concurrent.atomic.AtomicInteger mArmAttempts =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * How many times to retry arming before giving up until the next reconnect.
     *
     * A car with no {@code BYDAutoSettingDevice} fails every attempt, and retrying it forever would
     * cost a binder round-trip and a journal line every {@link #HEARTBEAT_MS} for as long as the
     * app runs — the one way this retry could be worse than the bug it fixes. 30 attempts is five
     * minutes at the current heartbeat, which covers a daemon that is merely slow to come up.
     */
    private static final int ARM_MAX_ATTEMPTS = 30;

    /**
     * Whether {@code canListenStart} actually registered the listener.
     *
     * The verb answers with a status string, and only two of its answers mean the listener is
     * live: {@code "registered (…)"} and {@code "already-registered"}. Everything else —
     * {@code "register-in-flight"}, {@code "ERR register timeout"}, any {@code "ERR …"} — is a
     * daemon that is not yet listening, and the retry is safe because
     * {@code CanFeedbackListener.startSetting} short-circuits on both {@code sRegistered} and
     * {@code sRegisterInFlight}, so it can never register a duplicate device listener.
     */
    static boolean isListenerArmed(String reply) {
        return reply != null
                && (reply.startsWith("registered") || reply.startsWith("already-registered"));
    }

    /** Serial executor for arming the HUD listener — reused across heartbeats instead of
     *  spawning a new "hud-listener-arm" Thread per arm (which churned one thread per heartbeat
     *  under persistent canListenStart failure). Shut down in onDestroy. */
    private final java.util.concurrent.ExecutorService mArmExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hud-listener-arm");
                t.setDaemon(true);
                return t;
            });

    /**
     * Keep the BYDAuto HUD push-feedback listener registered app-wide (off-thread, guarded) so the
     * bug report / diag can read the HUD's ACTUAL switch + display-mode. That state is push-only
     * (get() returns 0) and the OEM nav pushes it once at nav-start — capturing it reliably needs
     * the listener already registered, which this guarantees. Idempotent daemon-side; armed once
     * per connection, re-armed after a reconnect / respawn.
     */
    private void armHudListener() {
        if (mHudListenerArmed) return;
        mHudListenerArmed = true;
        try {
            mArmExecutor.execute(() -> {
                // Fail fast on a cold daemon instead of blocking this worker ~23s (mirrors F6).
                ProxyClient.setNonBlockingReconnect(true);
                String r = null;
                try {
                    r = ProxyClient.canListenStart();
                } catch (Throwable t) {
                    r = null;
                    AppLogger.w(TAG, "HUD listener arm failed: " + t.getMessage());
                }
                // Latch on the daemon's VERDICT, not on having made the attempt. The flag was set
                // before the call and only cleared when the call THREW, so a daemon that answered
                // "register-in-flight" or "ERR register timeout" — the normal shape of a daemon
                // still coming up — left the listener unarmed for the life of the process, with
                // no exception anywhere. HUD push-feedback is push-only and captured once at
                // nav-start, so every bug report from that car then carried no HUD state at all.
                if (isListenerArmed(r)) {
                    mArmAttempts.set(0);
                    return;
                }
                final int attempt = mArmAttempts.incrementAndGet();
                if (attempt < ARM_MAX_ATTEMPTS) {
                    mHudListenerArmed = false;   // retry on the next alive tick
                    AppLogger.w(TAG, "HUD listener not armed (" + r + ") — retry in "
                            + (HEARTBEAT_MS / 1000) + "s, attempt " + attempt
                            + "/" + ARM_MAX_ATTEMPTS);
                } else {
                    AppLogger.w(TAG, "HUD listener not armed (" + r + ") after "
                            + ARM_MAX_ATTEMPTS + " attempts — giving up until the next reconnect");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            mHudListenerArmed = false;   // executor shut down (service stopping) — allow a retry
        }
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.proxy_keeper_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(getString(R.string.proxy_keeper_channel_desc));
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(R.mipmap.ic_launcher)
         .setContentTitle(getString(R.string.proxy_keeper_notif_title))
         .setContentText(getString(R.string.proxy_keeper_notif_text))
         .setOngoing(true)
         .setShowWhen(false)
         .setContentIntent(pi);
        return b.build();
    }
}
