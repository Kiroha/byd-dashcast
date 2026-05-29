package com.byd.dashcast.beta;

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

import com.byd.dashcast.AppLogger;
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
 * OOM kill. Combined with {@link com.byd.dashcast.beta.proxy.ProxyDaemonMain}'s
 * own {@code oom_score_adj = -900} hardening (Couche 3) and the
 * {@code BootReceiver} in v1.2.72 (Couche 1), the daemon should now be
 * reachable in essentially every situation short of the user having
 * disabled ADB (which is unrecoverable by design).
 *
 * <p>Heartbeat: every {@link #HEARTBEAT_MS} milliseconds we check
 * {@link BetaProxyClient#isConnected()} (≈ 5 µs, just a volatile read +
 * {@code isBinderAlive()}). When the binder is dead we synchronously call
 * {@link BetaProxyClient#connect(Context)} which internally respects the
 * 10 s reconnect cooldown so cascading bootstraps remain impossible.
 *
 * <p>Notification: low-importance silent channel, no sound, no vibration,
 * not dismissible — it must stay visible because Android requires every
 * foreground service to display one. The intent points back at
 * {@link MainActivity} so a user tap just opens the app.
 *
 * <p>Self-disable: gated on {@link BetaConfig#isProxyDaemonEnabled(Context)};
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
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
        // Honour the feature flag at every tick: a user toggle should make
        // the heartbeat stop performing work without stopping the service
        // (Android does not love services that self-stop in a tight loop).
        if (!BetaConfig.isProxyDaemonEnabled(ctx)) return;

        // v1.3.3 — Defense in depth against silent binder deaths seen on
        // DiLink 3 / Android 10: do a real pingBinder() round-trip (cheap,
        // ~1 ms) rather than only the local isBinderAlive() check.
        // pingBinder returns false the instant the daemon process is gone,
        // even when the kernel's binderDied() notification was never
        // delivered to our DeathRecipient. This converts every "stuck
        // alive" case into a recoverable one within HEARTBEAT_MS.
        android.os.IBinder b = BetaProxyClient.getDaemonBinder();
        boolean alive = (b != null) && b.pingBinder();
        if (b != null && !alive) {
            BetaProxyClient.invalidateBinder("KeeperPing");
        }

        if (alive) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime();
            return;
        }
        long downForMs = mLastSeenAliveMs == 0 ? -1
                : SystemClock.elapsedRealtime() - mLastSeenAliveMs;
        AppLogger.i(TAG, "binder dead (downFor="
                + (downForMs < 0 ? "unknown" : downForMs + "ms")
                + ") — proactive reconnect from keeper");
        boolean ok = BetaProxyClient.connect(ctx);
        if (ok) {
            mLastSeenAliveMs = SystemClock.elapsedRealtime();
            AppLogger.i(TAG, "keeper reconnect ✅ pid="
                    + BetaProxyClient.getDaemonPid());
        } else {
            AppLogger.w(TAG, "keeper reconnect ❌ — retrying in "
                    + (HEARTBEAT_MS / 1000) + "s");
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
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
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
         .setContentTitle(getString(R.string.proxy_keeper_notif_title))
         .setContentText(getString(R.string.proxy_keeper_notif_text))
         .setOngoing(true)
         .setShowWhen(false)
         .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }
}
