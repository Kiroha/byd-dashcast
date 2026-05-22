package com.byd.dashcast.beta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.beta.proxy.BinderParcelable;
import com.byd.dashcast.beta.proxy.ProxyDaemonMain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BetaProxyClient — Component A client (v1.1.6+).
 *
 * <p>Talks to the proxy daemon (see {@link ProxyDaemonMain}) over a
 * direct {@link IBinder} reference obtained via a one-shot broadcast that
 * the daemon emits at startup (from a system {@link Context} obtained via
 * {@code ActivityThread.systemMain()}). This pattern is borrowed from
 * OpenBYD and replaces the abstract-namespace {@link android.net.LocalSocket}
 * used up to 1.1.5, which was blocked by SELinux for {@code untrusted_app}
 * → {@code shell} connects on Android 10+.
 *
 * <p>If no daemon is running, {@link #connect(Context)} registers a dynamic
 * {@link BroadcastReceiver} for {@link ProxyDaemonMain#ACTION_PROXY_CONNECTED}
 * and then bootstraps a daemon by issuing an {@code app_process64} command
 * through {@link AdbLocalClient} (which triggers the standard ADB-pairing
 * flow). The spawned daemon inherits the {@code shell} UID (2000) and
 * outlives the app.
 *
 * <p>Thread-safety: all public methods synchronize on a static lock, and
 * concurrent callers will queue.
 */
public final class BetaProxyClient {

    private static final String TAG = "BetaProxyClient";

    /** App package whose APK hosts the daemon main class. Must match the installed package. */
    private static final String DAEMON_PKG = "com.byd.dashcast";

    /** Fully-qualified main class of the daemon. */
    private static final String DAEMON_MAIN = "com.byd.dashcast.beta.proxy.ProxyDaemonMain";

    /** Path of the daemon's stdout/stderr capture on the device (overwritten each bootstrap). */
    private static final String DAEMON_LOG = "/data/local/tmp/dashcast_proxy.log";

    /**
     * Bootstrap script run via local ADB. Mirrors the proven {@code MirrorDaemon}
     * recipe and preserves every hard-won fix from 1.1.3–1.1.5:
     * <ul>
     *   <li>{@code setsid} detaches from the ADB session group (survives SIGHUP);</li>
     *   <li>explicit {@code /system/bin/app_process64};</li>
     *   <li>{@code -Xnoimage-dex2oat} avoids an AOT crash at startup;</li>
     *   <li>{@code --nice-name=dashcast_proxy} sets argv[0] so the stale-kill
     *       heuristic below ({@code ps -A | grep '[d]ashcast_proxy'}) keeps working;</li>
     *   <li>stdout/stderr redirected to {@link #DAEMON_LOG} for cold-start diag.</li>
     * </ul>
     */
    private static final String BOOTSTRAP_CMD =
            "APK=$(pm path " + DAEMON_PKG + " 2>/dev/null | head -n1 | cut -d: -f2-); "
            + "if [ -z \"$APK\" ]; then echo ERR_NO_APK; exit 1; fi; "
            + "LOG=" + DAEMON_LOG + "; "
            // Kill any stale daemon from a previous session (it survives app shutdown
            // because of `setsid`). Without this, a new daemon would broadcast its
            // binder on top of the old one — the app receiver only keeps the last
            // one, but two live processes would still be wasteful.
            + "STALE=$(ps -A 2>/dev/null | grep '[d]ashcast_proxy' | awk '{print $2}'); "
            + "if [ -n \"$STALE\" ]; then kill -9 $STALE 2>/dev/null; sleep 0.3; fi; "
            // Self-diagnostic header so a failed bootstrap is debuggable from the log.
            + "{ echo \"[boot] $(date) apk=$APK\"; "
            +   "echo \"[boot] id=$(id)\"; "
            +   "echo \"[boot] getenforce=$(getenforce 2>/dev/null)\"; "
            +   "echo \"[boot] stale_killed=${STALE:-none}\"; "
            +   "ls -la \"$APK\" 2>&1; "
            +   "echo \"[boot] exec app_process64...\"; } > \"$LOG\" 2>&1; "
            // Outer double-quotes so $APK expands BEFORE setsid hands the string to sh.
            + "setsid sh -c \"CLASSPATH='$APK' exec /system/bin/app_process64"
            +     " -Xnoimage-dex2oat /system/bin"
            +     " --nice-name=dashcast_proxy"
            +     " " + DAEMON_MAIN
            +     " </dev/null >>'$LOG' 2>&1\" & "
            + "echo OK $APK";

    /** Fetched after a connect() failure to surface the daemon's first error line(s). */
    private static final String READ_LOG_CMD = "tail -n 20 " + DAEMON_LOG + " 2>/dev/null";

    private static final int  BOOTSTRAP_TIMEOUT_MS = 8000;
    /**
     * The daemon's {@code ActivityThread.systemMain()} call takes 5–8 s cold on
     * a DiLink 3.0 SoC (it brings up the framework runtime inside app_process),
     * then the broadcast still has to traverse AMS. v1.1.6's 8 s window was
     * racing the broadcast by ~1 ms in production. 15 s gives headroom without
     * making failure cases painfully slow.
     */
    private static final int  BROADCAST_WAIT_MS    = 15000;

    private static final Object LOCK = new Object();

    /**
     * The live binder reference, or {@code null} when the daemon is unreachable.
     *
     * <p>Declared {@code volatile} (build 195 / P1) so hot-path typed verbs
     * ({@link #setOverscan}, {@link #getPidsByPackage},
     * {@link #autoContainerSendInfo}, {@link #forceStopPackage}) can read it
     * without acquiring {@link #LOCK} — critical for the resize SeekBar
     * (~30 overscan/s) and pidof polling (~every 5 s during projection)
     * which used to serialize behind any in-flight {@code runShell}.
     *
     * <p>Writes are still done under {@link #LOCK} from {@code connect()} /
     * the receiver / handshake / explicit error-clear paths; only the cheap
     * reads dropped the lock.
     */
    private static volatile IBinder sBinder;
    /** Receiver registered once on first {@link #connect(Context)}; reused thereafter. */
    private static BroadcastReceiver sReceiver;
    /** Set just before bootstrap; counted-down by {@link #sReceiver} on arrival. */
    private static volatile CountDownLatch sBinderLatch;

    // Volatile (build 195 / P1) so the public getters below stay lockless.
    private static volatile int    sDaemonUid = -1;
    private static volatile int    sDaemonPid = -1;
    private static volatile String sDaemonVer;

    /**
     * Death recipient that clears {@link #sBinder} as soon as the kernel
     * notifies us that the daemon process died. With this in place
     * {@link #isConnected()} no longer needs {@link IBinder#pingBinder()}
     * (a full IPC roundtrip per call) — it can rely on the cheaper local
     * {@link IBinder#isBinderAlive()} check (build 195 / P2).
     */
    private static final DeathRecipient sDeath = new DeathRecipient() {
        @Override public void binderDied() {
            synchronized (LOCK) {
                AppLogger.w(TAG, "daemon binder died — clearing cached reference");
                IBinder dead = sBinder;
                if (dead != null) {
                    try { dead.unlinkToDeath(this, 0); } catch (Throwable ignore) {}
                }
                sBinder = null;
                sDaemonUid = -1;
                sDaemonPid = -1;
                sDaemonVer = null;
            }
        }
    };

    private BetaProxyClient() {}

    /**
     * @return {@code true} if a live binder to the daemon is currently held.
     *
     * <p>Build 195 / P2: uses {@link IBinder#isBinderAlive()} (local check,
     * 0 IPC) instead of {@link IBinder#pingBinder()} (real Binder roundtrip
     * ~5 ms). Correctness preserved by {@link #sDeath}, which clears
     * {@code sBinder} as soon as the daemon dies. Read is lock-free because
     * {@code sBinder} is {@code volatile}.
     */
    public static boolean isConnected() {
        IBinder b = sBinder;
        return b != null && b.isBinderAlive();
    }

    /**
     * Ensure the daemon is reachable. If a binder is already cached and live,
     * returns immediately. Otherwise: (1) registers a receiver if not done yet;
     * (2) bootstraps a daemon via {@link AdbLocalClient}; (3) waits up to
     * {@link #BROADCAST_WAIT_MS} for the daemon's connect-broadcast; (4)
     * runs the WHOAMI handshake.
     *
     * @return {@code true} on success.
     */
    public static boolean connect(Context ctx) {
        // Fast path: an already-live binder is reused without touching the daemon
        // process — critical to avoid the cascade of kill-and-respawn cycles that
        // froze the head unit in v1.1.6 (each respawn triggers a full
        // ActivityThread.systemMain() in app_process which is very heavy).
        synchronized (LOCK) {
            if (isConnected()) {
                if (sDaemonUid < 0) handshake();
                return true;
            }
            ensureReceiverRegistered(ctx);
            // arm the latch BEFORE bootstrapping so a fast broadcast isn't missed
            sBinderLatch = new CountDownLatch(1);
        }

        AppLogger.i(TAG, "bootstrapping daemon via AdbLocalClient");
        String bootMsg = bootstrap(ctx);
        AppLogger.d(TAG, "bootstrap result: " + bootMsg);

        // CRITICAL: await() must NOT be called while holding LOCK. The broadcast
        // arrives on the main thread, onReceive() tries to take LOCK to set
        // sBinder, and would block until our await() times out. v1.1.7 hit
        // exactly this deadlock: the broadcast was always 0 ms late because the
        // receiver was blocked on us. CountDownLatch.await() does not release
        // monitors the way Object.wait() does, so we have to drop LOCK manually.
        CountDownLatch latch;
        synchronized (LOCK) { latch = sBinderLatch; }
        try {
            latch.await(BROADCAST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }

        synchronized (LOCK) {
            // Late-arrival recovery: the receiver may have signalled just after
            // the latch timed out — re-check rather than failing hard.
            if (sBinder == null || !sBinder.pingBinder()) {
                AppLogger.w(TAG, "no live binder after " + BROADCAST_WAIT_MS
                        + "ms (latch=" + (latch.getCount() == 0 ? "signalled" : "timed-out") + ")");
                sBinder = null;
                return false;
            }
            handshake();
            boolean ok = isConnected();
            if (ok) {
                AppLogger.i(TAG, "daemon ready (uid=" + sDaemonUid
                        + " pid=" + sDaemonPid + " ver=" + sDaemonVer + ")");
            }
            return ok;
        }
    }

    /**
     * Read the tail of the daemon's stdout/stderr capture file via legacy ADB.
     * Useful to surface the real cold-start error (class not found, SELinux,
     * dex2oat failure, etc.) in the Diag test message when {@link #connect(Context)}
     * has returned {@code false}.
     */
    public static String readDaemonLogTail(Context ctx) {
        final AtomicReference<String> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        AdbLocalClient.executeShellWithResult(ctx, READ_LOG_CMD, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String s) { out.set(s); latch.countDown(); }
            @Override public void onError(String e)   { out.set("<log read failed: " + e + ">"); latch.countDown(); }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return "<log read timed out>";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "<interrupted>";
        }
        String s = out.get();
        return (s == null || s.isEmpty()) ? "<empty>" : s;
    }

    /**
     * No-op. Kept for API compatibility and for tests that want to assert
     * persistence semantics.
     *
     * <p>The cached binder is process-scoped (static), not Activity-scoped, and
     * the daemon is a separate {@code app_process64} process under uid 2000.
     * Clearing the binder reference here would lie about the daemon's actual
     * lifetime: it would force the next {@link #connect(Context)} into a full
     * bootstrap (which kills the live daemon via the {@code [d]ashcast_proxy}
     * heuristic and respawns it — changing the PID) even though nothing in the
     * daemon process changed.
     *
     * <p>If the daemon actually dies (e.g. {@code kill -9}, OOM), the kernel
     * notifies us via the {@code DeathRecipient} hooked in
     * {@link #onBinderReceived(IBinder)} and {@code sBinder} is cleared
     * automatically.
     */
    public static void disconnect() {
        AppLogger.d(TAG, "disconnect() called — no-op (binder is process-scoped, daemon outlives Activity)");
    }

    /** Round-trip latency in ms, or {@code -1} on error / not connected. */
    public static long ping() {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) return -1L;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            long t0 = SystemClock.elapsedRealtime();
            b.transact(ProxyDaemonMain.TXN_PING, data, reply, 0);
            long t1 = SystemClock.elapsedRealtime();
            reply.readException();
            reply.readLong(); // epoch ms — unused, kept to drain the parcel
            return t1 - t0;
        } catch (RemoteException e) {
            AppLogger.w(TAG, "ping failed: " + e.getMessage());
            // Don't null sBinder — sDeath will do it on real death. RemoteException
            // can also mean transient backpressure, in which case the next typed
            // verb may still succeed.
            return -1L;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** UID of the daemon process as reported by its last {@code WHOAMI}. */
    public static int getCallerUid() { return sDaemonUid; }

    /** PID of the daemon process as reported by its last {@code WHOAMI}. */
    public static int getDaemonPid() { return sDaemonPid; }

    /** Protocol version reported by the daemon, or {@code null} if never handshook. */
    public static String getProtocolVersion() { return sDaemonVer; }

    /**
     * Run a shell command on the daemon and return its combined stdout/stderr.
     * Blocks until the daemon completes the command.
     *
     * <p>Build 195 / P1: lock-free. Concurrent {@code runShell} / typed-verb
     * calls dispatch in parallel through the daemon's binder thread pool
     * instead of serializing behind a single static mutex.
     */
    public static String runShell(String cmd) throws BetaProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            data.writeString(cmd);
            b.transact(ProxyDaemonMain.TXN_EXEC, data, reply, 0);
            reply.readException();
            int exit = reply.readInt();
            String output = reply.readString();
            if (exit != 0 && output != null && output.startsWith("ERR ")) {
                throw new BetaProxyException(output.substring(4));
            }
            return output == null ? "" : output;
        } catch (RemoteException e) {
            // Volatile single-assignment publish; sDeath will also catch the
            // dropped binder on its own, but clearing eagerly here lets the
            // very next call see the disconnected state immediately.
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Run the full Phase 4 feasibility probe suite inside the daemon and return
     * the raw pipe-separated result string ({@code "P1=PASS:...|P2=FAIL_SECURITY:..."}).
     * Parse with {@link com.byd.dashcast.beta.proxy.Phase4Probes#parse(String)}.
     *
     * <p>Probes run sequentially in the daemon process under uid 2000; the whole
     * call typically returns in &lt; 1 s.
     */
    public static String runPhase4Probes() throws BetaProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            b.transact(ProxyDaemonMain.TXN_PROBE_PHASE4, data, reply, 0);
            reply.readException();
            String out = reply.readString();
            return out == null ? "" : out;
        } catch (RemoteException e) {
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Phase 4a typed verb — direct {@code IWindowManager.setOverscan} via the
     * daemon's cached binder, replacing the {@code sh -c "wm overscan …"} fork.
     *
     * <p>Equivalent to {@code wm overscan left,top,right,bottom -d displayId}.
     * Probe P1 (build 173) proved this call succeeds from uid 2000 on the
     * BYD Seal EU. Typical latency: a few ms (first call may include the
     * one-shot reflection cache warm-up in {@link com.byd.dashcast.beta.proxy.Phase4Verbs}).
     */
    public static void setOverscan(int displayId, int left, int top, int right, int bottom)
            throws BetaProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            data.writeInt(displayId);
            data.writeInt(left);
            data.writeInt(top);
            data.writeInt(right);
            data.writeInt(bottom);
            b.transact(ProxyDaemonMain.TXN_SET_OVERSCAN, data, reply, 0);
            // readException() throws if the daemon side called writeException —
            // that becomes our trigger for the legacy fallback in ShellGateway.
            reply.readException();
        } catch (RemoteException e) {
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Phase 4b typed verb — pure-Java {@code /proc/&lt;pid&gt;/cmdline} scan
     * inside the daemon, replacing the {@code sh -c "pidof <pkg>"} fork used
     * by {@code MainActivity.reconcileDisplayState} / {@code reconcileMainDisplayState}.
     *
     * <p>Returns a space-separated list of PIDs whose argv[0] equals
     * {@code packageName} or starts with {@code "packageName:"} (Android
     * sub-processes). Empty string when no match — i.e. {@code output.trim().isEmpty()}
     * is the same "process is dead" signal the legacy callers expected.
     *
     * <p>Probe P8 (build 173) measured the scan at &lt; 1 ms on the BYD Seal EU
     * with 241 live processes; build-174 device logs show the legacy fork at
     * 48–181 ms.
     */
    public static String getPidsByPackage(String packageName) throws BetaProxyException {
        if (packageName == null) packageName = "";
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            data.writeString(packageName);
            b.transact(ProxyDaemonMain.TXN_GET_PIDS, data, reply, 0);
            reply.readException();
            String pids = reply.readString();
            return pids == null ? "" : pids;
        } catch (RemoteException e) {
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Phase 4c typed verb — direct {@code AutoContainer.transact(2, …)} in the
     * daemon, replacing the {@code dadb.shell("service call AutoContainer 2 …")}
     * relay used by {@code AdbLocalClient.sendInfo}.
     *
     * <p>Probe P13 (build 176) measured the call at 0 ms on the BYD Seal EU,
     * vs the legacy shell relay which had to fork a {@code service} binary
     * and quote-escape the {@code s16} argument. Equivalent to
     * {@code service call AutoContainer 2 i32 <type> i32 <info> s16 "<str>"}.
     *
     * <p>{@code str} may be {@code null} — it is normalised to {@code ""}
     * on the wire (same as the legacy shell wrapper which passed
     * {@code s16 ""} when {@code infoStr} was empty).
     */
    public static void autoContainerSendInfo(int type, int info, String str)
            throws BetaProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str == null ? "" : str);
            b.transact(ProxyDaemonMain.TXN_AUTOCONTAINER_SEND_INFO, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Phase 4d typed verb — direct {@code IActivityManager.forceStopPackage} in
     * the daemon, replacing {@code dadb.shell("am force-stop <pkg>")} forks
     * used by {@code AdbLocalClient.restoreBydOnCluster} and
     * {@code restoreOriginCluster} (end-of-session teardown hot path).
     *
     * <p>Probe P3 (build 173) confirmed this call is accepted from uid 2000
     * on the BYD Seal EU. Pass {@code -1} for {@code userId} to target all
     * users (legacy {@code am force-stop} default behaviour).
     */
    public static void forceStopPackage(String packageName, int userId)
            throws BetaProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new BetaProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            data.writeString(packageName);
            data.writeInt(userId);
            b.transact(ProxyDaemonMain.TXN_FORCE_STOP_PACKAGE, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            sBinder = null;
            throw new BetaProxyException("transact: " + e.getMessage(), e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────

    /**
     * Register the dynamic {@link BroadcastReceiver} once per app process.
     * Uses the application context so the lifetime is tied to the process,
     * not to any short-lived Activity that happens to call us first.
     */
    private static void ensureReceiverRegistered(Context ctx) {
        if (sReceiver != null) return;
        final Context appCtx = ctx.getApplicationContext();
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                if (!ProxyDaemonMain.ACTION_PROXY_CONNECTED.equals(intent.getAction())) return;
                BinderParcelable bp = intent.getParcelableExtra(ProxyDaemonMain.EXTRA_BINDER);
                if (bp == null || bp.binder == null) {
                    AppLogger.w(TAG, "PROXY_CONNECTED received without binder extra");
                    return;
                }
                // Discard stale broadcasts whose binder is already dead — they happen
                // when a previous bootstrap killed an in-flight daemon and AMS only
                // dispatched its broadcast after the kill. Storing the dead ref would
                // mask the LIVE binder we either already have or are still waiting for
                // (root cause of A5/A6 ✗ in v1.1.6).
                if (!bp.binder.pingBinder()) {
                    AppLogger.d(TAG, "ignoring stale PROXY_CONNECTED (binder already dead)");
                    return;
                }
                synchronized (LOCK) {
                    // If we already hold a live binder, prefer it (avoid spurious
                    // handshake/state churn from late duplicate broadcasts).
                    // Local check via isBinderAlive() (P2) — sDeath would have
                    // cleared sBinder if the cached one had actually died.
                    if (sBinder != null && sBinder.isBinderAlive() && sBinder != bp.binder) {
                        AppLogger.d(TAG, "ignoring duplicate PROXY_CONNECTED (already have a live binder)");
                        CountDownLatch latch = sBinderLatch;
                        if (latch != null) latch.countDown();
                        return;
                    }
                    // Unhook the previous death recipient (if any) before swapping.
                    if (sBinder != null && sBinder != bp.binder) {
                        try { sBinder.unlinkToDeath(sDeath, 0); } catch (Throwable ignore) {}
                    }
                    sBinder = bp.binder;
                    // Hook the new binder so a future death immediately clears
                    // our cached reference (P2). Best-effort: if linkToDeath
                    // fails (binder already dead between pingBinder above and
                    // here — vanishingly unlikely), isBinderAlive() on the next
                    // call still gives the right answer.
                    try { sBinder.linkToDeath(sDeath, 0); }
                    catch (RemoteException re) {
                        AppLogger.w(TAG, "linkToDeath failed: " + re.getMessage());
                    }
                    // Invalidate WHOAMI cache — handshake() will refresh it.
                    sDaemonUid = -1;
                    sDaemonPid = -1;
                    sDaemonVer = null;
                    AppLogger.i(TAG, "live binder received from daemon");
                    CountDownLatch latch = sBinderLatch;
                    if (latch != null) latch.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ProxyDaemonMain.ACTION_PROXY_CONNECTED);
        // 2-arg form: targetSdk=29 so platform does not enforce RECEIVER_EXPORTED.
        // If targetSdk is ever raised to 33+, switch to the 3-arg overload with
        // Context.RECEIVER_EXPORTED (the broadcaster is in another process / uid).
        appCtx.registerReceiver(sReceiver, filter);
        AppLogger.d(TAG, "dynamic receiver registered for " + ProxyDaemonMain.ACTION_PROXY_CONNECTED);
    }

    /** Issue the WHOAMI transaction to populate uid/pid/version caches. */
    private static void handshake() {
        if (sBinder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonMain.DESCRIPTOR);
            sBinder.transact(ProxyDaemonMain.TXN_WHOAMI, data, reply, 0);
            reply.readException();
            sDaemonUid = reply.readInt();
            sDaemonPid = reply.readInt();
            sDaemonVer = reply.readString();
        } catch (RemoteException e) {
            AppLogger.w(TAG, "handshake failed: " + e.getMessage());
            sBinder = null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Issue the bootstrap script via legacy ADB and wait (briefly) for it to finish. */
    private static String bootstrap(Context ctx) {
        final AtomicReference<String> out = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        AdbLocalClient.executeShellWithResult(ctx, BOOTSTRAP_CMD, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) { out.set(report); latch.countDown(); }
            @Override public void onError(String error)    { out.set("ERR " + error); latch.countDown(); }
        });
        try {
            if (!latch.await(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "ERR bootstrap timed out";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "ERR interrupted";
        }
        return out.get();
    }

    /** Thrown when the proxy daemon path fails — caller should fall back to legacy. */
    public static class BetaProxyException extends Exception {
        public BetaProxyException(String msg) { super(msg); }
        public BetaProxyException(String msg, Throwable cause) { super(msg, cause); }
    }
}
