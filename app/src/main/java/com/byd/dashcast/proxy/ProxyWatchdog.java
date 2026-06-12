package com.byd.dashcast.proxy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.byd.dashcast.util.AppLogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.2.62-beta — Phase A step 2 : foreground liveness ping for the proxy
 * daemon.
 *
 * <p>Polls {@link ProxyClient#isConnected()} every {@link #PING_INTERVAL_MS}
 * while the app is in the foreground. If the cached binder is dead (daemon
 * crashed, killed, OOM), triggers {@link ProxyClient#connect(Context)}
 * synchronously on the watchdog thread so the next user-initiated typed verb
 * is instant instead of paying the ~1 s bootstrap cost on its first call.
 *
 * <p>The check is intentionally lock-free and IPC-free: {@code isConnected()}
 * reads a {@code volatile IBinder} and calls {@code isBinderAlive()} (≈ 5 µs).
 * Cost while the daemon is healthy is therefore one volatile read every
 * 30 s — strictly invisible.
 *
 * <p>Foreground tracking uses
 * {@link Application#registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks)}
 * (zero deps, available since API 14) — no {@code androidx.lifecycle:process}
 * needed. The watchdog stops as soon as the last activity is paused, so
 * background and locked-screen sessions don't keep the daemon hot — that's
 * Phase A step 1's job (auto-recovery on the next call).
 *
 * <p>Reconnects respect the same 10 s cooldown gate as the on-demand
 * reconnect path because we route through {@link ProxyClient#connect}
 * which in turn re-uses the existing receiver/latch machinery; bootstrap
 * cascades remain impossible.
 *
 * <p><b>Safety</b>: never enables itself if the proxy is feature-flag
 * disabled (always active — the daemon is the default projection path.
 * users who never opted in at zero overhead.
 */
public final class ProxyWatchdog {

    private static final String TAG = "ProxyWatchdog";

    /** Foreground ping interval. 30 s strikes the balance between « keep
     *  the daemon hot » and « no battery impact ». */
    private static final long PING_INTERVAL_MS = 30_000L;

    /** Singleton handler thread — one background thread for the entire
     *  process, owned by the watchdog. */
    private static volatile HandlerThread sThread;
    private static volatile Handler       sHandler;

    /** Foreground tracking: incremented in onResume, decremented in onPause.
     *  Main-thread writes, watchdog-thread reads — AtomicInteger ensures visibility. */
    private static final AtomicInteger sForegroundCount = new AtomicInteger(0);

    /** Application context cached at install(); used by connect() retries. */
    @SuppressLint("StaticFieldLeak") // application context, process-scoped, safe
    private static volatile Context sAppCtx;

    /** Set true once {@link #install(Application)} has wired the activity
     *  callbacks, so a second install attempt no-ops. */
    private static boolean sInstalled = false;

    /** Last time we successfully observed a live binder, for debug. */
    private static volatile long sLastSeenAliveMs = 0L;

    private ProxyWatchdog() { /* no instances */ }

    /**
     * Wire the watchdog. Idempotent: a second call returns immediately.
     * Call from {@link com.byd.dashcast.DashCastApp#onCreate()}.
     */
    public static synchronized void install(Application app) {
        if (sInstalled) return;
        sInstalled = true;
        sAppCtx = app.getApplicationContext();

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                if (sForegroundCount.incrementAndGet() == 1) startPolling();
            }
            @Override public void onActivityPaused(Activity a) {
                if (sForegroundCount.get() > 0 && sForegroundCount.decrementAndGet() == 0) stopPolling();
            }
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });

        AppLogger.i(TAG, "installed (interval=" + PING_INTERVAL_MS + "ms)");
    }

    /** Start the periodic ping on the background handler thread. */
    private static synchronized void startPolling() {
        if (sThread == null) {
            sThread = new HandlerThread("proxy-watchdog");
            sThread.start();
            sHandler = new Handler(sThread.getLooper());
        }
        // Cancel anything pending then schedule fresh.
        sHandler.removeCallbacks(sTick);
        sHandler.postDelayed(sTick, PING_INTERVAL_MS);
        AppLogger.d(TAG, "polling started");
    }

    /** Stop the periodic ping. The handler thread is kept around — cheap to
     *  reuse on the next foreground transition. */
    private static synchronized void stopPolling() {
        if (sHandler != null) sHandler.removeCallbacks(sTick);
        AppLogger.d(TAG, "polling stopped");
    }

    private static final Runnable sTick = new Runnable() {
        @Override public void run() {
            try {
                tickInternal();
            } catch (Throwable t) {
                // Watchdog must never crash the app.
                AppLogger.w(TAG, "tick threw: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            } finally {
                // Only re-arm while still foreground.
                if (sForegroundCount.get() > 0 && sHandler != null) {
                    sHandler.postDelayed(this, PING_INTERVAL_MS);
                }
            }
        }
    };

    private static void tickInternal() {
        Context ctx = sAppCtx;
        if (ctx == null) return;
        if (ProxyClient.isConnected()) {
            sLastSeenAliveMs = SystemClock.elapsedRealtime();
            return;
        }
        // Dead binder detected proactively. Try to bring it back so the next
        // user action is instant. Cooldown-gated inside connect().
        long downForMs = sLastSeenAliveMs == 0 ? -1
                : SystemClock.elapsedRealtime() - sLastSeenAliveMs;
        AppLogger.i(TAG, "binder dead (downFor="
                + (downForMs < 0 ? "unknown" : downForMs + "ms")
                + ") — proactive reconnect");
        boolean ok = ProxyClient.connect(ctx);
        if (ok) {
            sLastSeenAliveMs = SystemClock.elapsedRealtime();
            AppLogger.i(TAG, "proactive reconnect ✅ pid="
                    + ProxyClient.getDaemonPid());
        } else {
            AppLogger.w(TAG, "proactive reconnect ❌ — will retry in "
                    + (PING_INTERVAL_MS / 1000) + "s");
        }
    }

    /** Test helper (Diag) : age of the last "binder alive" observation. */
    public static long getMsSinceLastSeenAlive() {
        return sLastSeenAliveMs == 0 ? -1
                : SystemClock.elapsedRealtime() - sLastSeenAliveMs;
    }

    /** Test helper (Diag) : true once {@link #install(Application)} ran. */
    public static boolean isInstalled() { return sInstalled; }

    /** Test helper (Diag) : current foreground activity count. */
    public static int getForegroundCount() { return sForegroundCount.get(); }
}
