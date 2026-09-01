package com.byd.dashcast.proxy;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.util.concurrent.SingleFlight;
import com.byd.dashcast.proxy.daemon.BinderParcelable;
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ProxyClient — Component A client (v1.1.6+).
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
public final class ProxyClient {

    private static final String TAG = "ProxyClient";

    /** App package whose APK hosts the daemon main class. Must match the installed package. */
    private static final String DAEMON_PKG = "com.byd.dashcast";

    /** Fully-qualified main class of the daemon. */
    private static final String DAEMON_MAIN = "com.byd.dashcast.proxy.daemon.ProxyDaemonMain";

    /** Path of the daemon's stdout/stderr capture on the device (overwritten each bootstrap). */
    private static final String DAEMON_LOG = "/data/local/tmp/dashcast_proxy.log";

    /** PID file written by the daemon at startup (v1.2.63-beta, Phase A step 3). */
    private static final String DAEMON_PID = "/data/local/tmp/dashcast_proxy.pid";
    /** Per-process nonce paired with {@link #DAEMON_PID}; protocol v25 WHOAMI returns it. */
    private static final String DAEMON_INSTANCE = "/data/local/tmp/dashcast_proxy_instance";
    /** Trigger file watched by the daemon to ask for a binder rebroadcast. */
    private static final String DAEMON_TRIGGER = "/data/local/tmp/dashcast_proxy.trigger";
    /** Bootstrap-script lock file — flock'd to serialize concurrent bootstraps. */
    private static final String DAEMON_LOCK = "/data/local/tmp/dashcast_proxy.lock";

    /**
     * Bootstrap script run via local ADB. Mirrors the proven {@code SurfaceDaemon}
     * recipe and preserves every hard-won fix from 1.1.3–1.1.5:
     * <ul>
     *   <li>{@code setsid} detaches from the ADB session group (survives SIGHUP);</li>
     *   <li>explicit {@code /system/bin/app_process64};</li>
     *   <li>{@code -Xnoimage-dex2oat} avoids an AOT crash at startup;</li>
     *   <li>{@code --nice-name=dashcast_proxy} sets argv[0] so the stale-kill
     *       heuristic below ({@code ps -A | grep '[d]ashcast_proxy'}) keeps working;</li>
     *   <li>stdout/stderr redirected to {@link #DAEMON_LOG} for cold-start diag.</li>
     * </ul>
     *
     * <p>v1.2.63-beta (Phase A step 3) additions, applied before the legacy
     * recipe so both old and new daemons keep working:
     * <ul>
     *   <li>{@code flock -n} on {@link #DAEMON_LOCK} — atomic w.r.t. any other
     *       bootstrap invocation, so two concurrent app calls can never race.
     *       Belt-and-suspenders on top of the 10 s Java-side cooldown.</li>
     *   <li>PID-file fast path: if {@link #DAEMON_PID} points to a live process
     *       named {@code dashcast_proxy}, just {@code touch} the trigger file
     *       (watched via {@code FileObserver} inside the daemon) and exit with
     *       {@code REBROADCAST <pid>}. The daemon re-emits its binder in
     *       milliseconds — no {@code app_process} restart needed, no 1 s
     *       penalty after an app process restart.</li>
     * </ul>
     */
    private static final String BOOTSTRAP_CMD =
            // ── Live-daemon fast path (BEFORE the flock) ───────────────────
            // v1.2.67 hotfix: PID-file based detection turned out fragile in
            // the field — the file may be empty/missing on first run,
            // /proc/PID/comm is the JVM thread name ("main"), and toybox
            // `grep -a` behaviour on cmdline can't be relied on across
            // DiLink versions. We now use the EXACT same heuristic as the
            // stale-kill below (proven since 1.1.5):
            //   `ps -A | grep '[d]ashcast_proxy'`
            // If ANY live daemon is found (any version, including the
            // v1.2.63/64/65/66 ones stuck with the FD-9 lock leak), we just
            // touch the trigger file — the FileObserver introduced in
            // v1.2.63 lives in all those daemons and will re-emit the
            // binder broadcast, letting us recover WITHOUT killing the
            // stuck daemon and WITHOUT requiring a tablet reboot.
            // Concurrent touches on the trigger file coalesce inside the
            // FileObserver, so no locking is needed for the fast path.
            "TRIG=" + DAEMON_TRIGGER + "; "
            + "PS_OUT=$(ps -A 2>/dev/null | grep '[d]ashcast_proxy'); "
            + "ALIVE_PID=$(echo \"$PS_OUT\" | awk '{print $2}' | head -n1); "
            // AUD — the process NAME is not an identity. `ps -A | grep dashcast_proxy` matches
            // any process whose name merely contains that string, and any app can obtain one by
            // declaring an android:process ending in dashcast_proxy in its own manifest. With the version
            // file already holding the current versionCode — which it does as soon as a real
            // daemon has run once — such a process made this take the REBROADCAST path forever:
            // the genuine uid-2000 daemon was never started again, and every privileged feature
            // stayed dead with no error anywhere.
            //
            // The pid file is the identity, because only uid 2000 can write /data/local/tmp.
            // A name match that does not agree with it is not our daemon.
            + "PID_FILE_VAL=$(cat /data/local/tmp/dashcast_proxy.pid 2>/dev/null); "
            + "if [ -n \"$ALIVE_PID\" ] && [ \"$ALIVE_PID\" != \"$PID_FILE_VAL\" ]; then "
            +   "echo \"[diag] proxy name-match pid=$ALIVE_PID != pidfile=${PID_FILE_VAL:-none} — ignoring\" >&2; "
            +   "ALIVE_PID=; "
            + "fi; "
            + "if [ -n \"$ALIVE_PID\" ]; then "
            // Version check: daemon loaded from old APK after OTA has a stale
            // versionCode in VERSION_FILE — fall through to kill+restart instead
            // of REBROADCAST, so proxy verbs are always from the current APK.
            +   "DAEMON_VER=$(cat /data/local/tmp/dashcast_proxy_ver 2>/dev/null); "
            +   "if [ \"$DAEMON_VER\" = \"" + com.byd.dashcast.BuildConfig.VERSION_CODE + "\" ]; then "
            +     "echo trigger > \"$TRIG\" 2>/dev/null; "
            +     "echo \"REBROADCAST $ALIVE_PID\"; exit 0; "
            +   "fi; "
            +   "echo \"[diag] proxy stale ver=${DAEMON_VER:-?} expected=" + com.byd.dashcast.BuildConfig.VERSION_CODE + "\" >&2; "
            // The "no daemon at all" diagnostic belongs to the ELSE of the aliveness test. It
            // used to be emitted unconditionally right after this block, so a stale-but-ALIVE
            // daemon printed "[diag] proxy stale ver=588 expected=593" and then "[diag] no_alive
            // ps_empty" in the same breath — the second line falsely asserting `ps` had found
            // nothing. Every post-OTA triage had to work out which of the two was lying.
            + "else "
            +   "echo \"[diag] no_alive ps_empty\" >&2; "
            + "fi; "
            // ── flock guard ── REMOVED in v1.2.69 ──────────────────────────
            // v1.2.68 tried to gate the flock on its availability, but
            // field logs still show ALREADY_BOOTSTRAPPING with an empty
            // ps output — meaning either flock is present-but-broken on
            // DiLink 3, or `exec 9>file` itself fails silently for some
            // reason. After 6 hotfixes, just drop the flock entirely.
            // Race protection is already provided by:
            //   - the 10s RECONNECT_COOLDOWN_MS in attemptReconnect
            //   - the stale-kill below (which kills any duplicate)
            //   - the ps fast-path above (which short-circuits when a
            //     daemon is already alive)
            // Worst case: two near-simultaneous bootstraps spawn two
            // daemons; the second one's stale-kill terminates the first
            // and only one survives. Acceptable.
            // ── full bootstrap ─────────────────────────────────────────────
            + "APK=$(pm path " + DAEMON_PKG + " 2>/dev/null | head -n1 | cut -d: -f2-); "
            + "if [ -z \"$APK\" ]; then echo ERR_NO_APK; exit 1; fi; "
            + "LOG=" + DAEMON_LOG + "; "
            // Stale-kill kept as last-line defence. If the fast path saw a
            // live daemon we'd never reach here; if it didn't but `ps` here
            // still finds one, the daemon died between the two `ps` calls —
            // then this kill is harmless (already-gone PID).
            + "STALE=$(ps -A 2>/dev/null | grep '[d]ashcast_proxy' | awk '{print $2}'); "
            + "if [ -n \"$STALE\" ]; then kill -9 $STALE 2>/dev/null; sleep 0.3; fi; "
            // Self-diagnostic header so a failed bootstrap is debuggable from the log.
            + "{ echo \"[boot] $(date) apk=$APK\"; "
            +   "echo \"[boot] id=$(id)\"; "
            +   "echo \"[boot] getenforce=$(getenforce 2>/dev/null)\"; "
            +   "echo \"[boot] stale_killed=${STALE:-none}\"; "
            +   "ls -la \"$APK\" 2>&1; "
            +   "echo \"[boot] exec app_process64...\"; } > \"$LOG\" 2>&1; "
            + "setsid sh -c \"CLASSPATH='$APK' exec /system/bin/app_process64"
            +     " -Xnoimage-dex2oat /system/bin"
            +     " --nice-name=dashcast_proxy"
            +     " " + DAEMON_MAIN
            +     " </dev/null >>'$LOG' 2>&1\" & "
            + "echo OK $APK";

    /** Fetched after a connect() failure to surface the daemon's first error line(s). */
    private static final String READ_LOG_CMD = "tail -n 20 " + DAEMON_LOG + " 2>/dev/null";

    /** Covers dadb's first-command authorization window (15 s) plus callback delivery. */
    private static final int  BOOTSTRAP_TIMEOUT_MS = 16_000;
    /**
     * The daemon's {@code ActivityThread.systemMain()} call takes 5–8 s cold on
     * a DiLink 3.0 SoC (it brings up the framework runtime inside app_process),
     * then the broadcast still has to traverse AMS. v1.1.6's 8 s window was
     * racing the broadcast by ~1 ms in production. 15 s gives headroom without
     * making failure cases painfully slow.
     */
    private static final int  BROADCAST_WAIT_MS    = 15000;
    /** A timed-out/refused ADB command may still have spawned the daemon just before transport
     *  failure; keep a small grace window for its already-armed Binder broadcast. */
    private static final int  TRANSPORT_FAILURE_BINDER_GRACE_MS = 2_000;
    private static final long CONNECT_JOIN_TIMEOUT_MS =
            BOOTSTRAP_TIMEOUT_MS + BROADCAST_WAIT_MS + 1_000L;

    private static final Object LOCK = new Object();

    /** Re-probe interval for a transport classified as permanently unreachable (v1.6.102). */
    private static final long XPORT_RECHECK_MS = 60_000L;
    /** Authorization may become healthy immediately after the driver accepts the popup. */
    private static final long XPORT_AUTH_RECHECK_MS = 2_000L;
    /** Last time the dead-transport circuit-breaker allowed a real bootstrap attempt. */
    private static volatile long sLastDeadXportAttemptMs = 0L;

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
    static volatile IBinder sBinder;
    /** Receiver registered once on first {@link #connect(Context)}; reused thereafter. */
    private static BroadcastReceiver sReceiver;
    /** Set just before bootstrap; counted-down by {@link #sReceiver} on arrival. */
    private static volatile CountDownLatch sBinderLatch;
    /** Exactly one caller owns a cold daemon bootstrap; concurrent callers join its result. */
    private static final SingleFlight<Boolean> sConnectSingleFlight = new SingleFlight<>();

    // Volatile (build 195 / P1) so the public getters below stay lockless.
    private static volatile int    sDaemonUid = -1;
    private static volatile int    sDaemonPid = -1;
    private static volatile String sDaemonVer;
    private static volatile String sDaemonInstance;

    // ─── Auto-recovery (v1.2.58-beta, Phase A step 1) ─────────────────────
    /**
     * Application context captured on first successful {@link #connect(Context)}
     * call, used by {@link #attemptReconnect()} to bootstrap the daemon when a
     * typed verb finds a dead binder. Application-scoped (not Activity), safe
     * to hold statically.
     */
    @SuppressLint("StaticFieldLeak") // application context, process-scoped, safe
    private static volatile Context sAppCtx;
    /**
     * Anti-storm gate for {@link #attemptReconnect()}. A reconnect attempt is
     * skipped if the previous one ran less than {@link #RECONNECT_COOLDOWN_MS}
     * ago. Protects against bootstrap-storms when the cluster resize SeekBar
     * (~30 setOverscan/s) or input forwarder (~60 transact/s) hits a dead
     * binder — each bootstrap is a full {@code app_process64} +
     * {@code ActivityThread.systemMain()} (5–8 s on DiLink SoCs) and serial
     * bootstraps would kill one another via the {@code [d]ashcast_proxy}
     * stale-kill heuristic. Updated under {@link #LOCK} only.
     */
    private static long sLastReconnectAttemptMs;
    /** Cooldown window for {@link #attemptReconnect()}. See field doc above. */
    private static final long RECONNECT_COOLDOWN_MS = 10_000L;
    /**
     * v1.2.78 — Couche 4: adaptive backoff steps in ms. The cooldown gate
     * picks {@code BACKOFF_MS[min(sBackoffStep, last)]} instead of the flat
     * {@link #RECONNECT_COOLDOWN_MS}. {@code sBackoffStep} is reset to 0 on
     * every successful {@link #connect(Context)} and bumped on every failed
     * one. ERR_NO_APK forces an immediate retry by zeroing the timestamp
     * (the APK race is transient — the next post-OTA scan completes in <1s).
     */
    private static final long[] BACKOFF_MS = { 1_000L, 2_000L, 4_000L, 8_000L, 10_000L };
    private static int sBackoffStep = 0;

    /**
     * Death recipient that clears {@link #sBinder} as soon as the kernel
     * notifies us that the daemon process died. With this in place
     * {@link #isConnected()} no longer needs {@link IBinder#pingBinder()}
     * (a full IPC roundtrip per call) — it can rely on the cheaper local
     * {@link IBinder#isBinderAlive()} check (build 195 / P2).
     */
    private static DeathRecipient sDeath;
    private static IBinder sDeathBinder;

    /** LOCK must be held. Registers a recipient that can clear only {@code watchedBinder}. */
    private static void linkDeathLocked(final IBinder watchedBinder) throws RemoteException {
        DeathRecipient recipient = new DeathRecipient() {
            @Override public void binderDied() {
                synchronized (LOCK) {
                    if (sBinder != watchedBinder) return;
                    AppLogger.w(TAG, "daemon binder died — clearing matching cached reference");
                    try { watchedBinder.unlinkToDeath(this, 0); } catch (Throwable ignore) {}
                    if (sDeath == this) {
                        sDeath = null;
                        sDeathBinder = null;
                    }
                    sBinder = null;
                    sDaemonUid = -1;
                    sDaemonPid = -1;
                    sDaemonVer = null;
                    sDaemonInstance = null;
                    ProxyMetrics.inc(sAppCtx, ProxyMetrics.K_BINDER_ZOMBIES);
                }
            }
        };
        watchedBinder.linkToDeath(recipient, 0);
        sDeath = recipient;
        sDeathBinder = watchedBinder;
    }

    /** LOCK must be held. Unlinks only the recipient registered for {@code binder}. */
    private static void unlinkDeathLocked(IBinder binder) {
        if (binder == null || binder != sDeathBinder || sDeath == null) return;
        try { binder.unlinkToDeath(sDeath, 0); } catch (Throwable ignore) {}
        sDeath = null;
        sDeathBinder = null;
    }

    /** Clears one complete connection generation only while it is still current. */
    static boolean clearConnectionIfCurrent(IBinder expectedBinder) {
        synchronized (LOCK) {
            if (sBinder != expectedBinder) return false;
            unlinkDeathLocked(expectedBinder);
            sBinder = null;
            sDaemonUid = -1;
            sDaemonPid = -1;
            sDaemonVer = null;
            sDaemonInstance = null;
            return true;
        }
    }

    private ProxyClient() {}

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
     * Returns the cached binder of the <b>PROXY</b> daemon
     * ({@link com.byd.dashcast.proxy.daemon.ProxyDaemonMain}, ServiceManager name
     * {@code byd_proxy_daemon}), or {@code null} when it is not currently connected.
     *
     * <p><b>This is NOT the surface daemon.</b> DashCast runs two uid-2000 daemons:
     * <ul>
     *   <li>the PROXY daemon (this one) <b>DOES</b> things — shell commands and one-shot verbs —
     *       and enforces {@link com.byd.dashcast.proxy.daemon.ProxyDaemonContract#DESCRIPTOR};</li>
     *   <li>the SURFACE daemon ({@link com.byd.dashcast.proxy.daemon.SurfaceDaemon}) <b>HOLDS</b>
     *       things — the preview mirror, the cluster slot overlay windows and their trusted
     *       VirtualDisplays, touch injection — and enforces
     *       {@link com.byd.dashcast.proxy.daemon.SurfaceDaemon#DESCRIPTOR}.</li>
     * </ul>
     *
     * <p>Do <b>not</b> pass this binder to mirror / slot / injection APIs such as
     * {@code ClusterMirrorManager.startMirrorViaDaemon()}, {@code FissionClient.*} or
     * {@code ClusterInputForwarder}: writing the surface daemon's interface token onto this binder
     * makes the receiving {@code enforceInterface} reject the transaction, which then silently does
     * nothing. For those, use {@link DaemonBinderResolver#surfaceDaemonBinder()}.
     *
     * <p>Triage rule: a failed <i>command</i> → proxy daemon; a black or frozen <i>surface</i> →
     * surface daemon.
     *
     * <p>Callers of this accessor are the ones that legitimately need the proxy binder itself
     * (liveness ping, DESCRIPTOR-crossing guard). Every other proxy operation should go through the
     * typed verbs on this class instead of transacting on the raw binder.
     */
    public static IBinder getProxyDaemonBinder() {
        IBinder b = sBinder;
        return (b != null && b.isBinderAlive()) ? b : null;
    }

    /**
     * v1.3.3 — Eager invalidation entry point for call-sites that detect a
     * dead binder by catching {@link android.os.DeadObjectException} from a
     * {@code transact()} call on the daemon binder. The kernel sometimes
     * fails to deliver the binderDied() notification on DiLink 3 / Android
     * 10 (observed silent deaths on user devices in v1.3.x), leaving
     * {@link #isConnected()} stuck on {@code true} while every call throws.
     * Sites must call this method as soon as they catch such an exception
     * so that:
     *   (1) the next {@link #isConnected()} returns false immediately;
     *   (2) {@link ProxyKeeperService} picks up the dead state at its next
     *       heartbeat and triggers a reconnect;
     *   (3) the silent-death event is counted in metrics for diagnosis.
     *
     * Safe to call from any thread, idempotent.
     *
     * @param reason short tag included in the log line (e.g. "MirrorStart").
     */
    public static void invalidateBinder(String reason) {
        synchronized (LOCK) {
            IBinder dead = sBinder;
            if (dead == null) return; // already invalidated, nothing to do
            unlinkDeathLocked(dead);
            sBinder = null;
            sDaemonUid = -1;
            sDaemonPid = -1;
            sDaemonVer = null;
            sDaemonInstance = null;
            ProxyMetrics.inc(sAppCtx, ProxyMetrics.K_BINDER_DEATHS_SILENT);
            AppLogger.w(TAG, "invalidateBinder(" + reason
                    + ") — silent death detected by caller (kernel notif missing)");
        }
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
        if (ctx == null) return false;
        // Cache the application context the very first time we are called from
        // any thread/site, so attemptReconnect() can bootstrap silently from
        // inside a typed verb (which has no Context parameter). Application
        // context is process-scoped → safe to hold statically.
        if (sAppCtx == null) {
            sAppCtx = ctx.getApplicationContext();
        }
        IBinder cachedBinder = sBinder;
        if (cachedBinder != null && cachedBinder.isBinderAlive()) {
            return sDaemonUid >= 0 || handshakeAndVerify(cachedBinder);
        }
        // Fast path: an already-live binder is reused without touching the daemon
        // process — critical to avoid the cascade of kill-and-respawn cycles that
        // froze the head unit in v1.1.6 (each respawn triggers a full
        // ActivityThread.systemMain() in app_process which is very heavy).
        SingleFlight.Ticket<Boolean> ticket;
        CountDownLatch binderSignal = null;
        IBinder lateBinder = null;
        synchronized (LOCK) {
            IBinder currentBinder = sBinder;
            if (currentBinder != null && currentBinder.isBinderAlive()) {
                if (sDaemonUid >= 0) return true;
                // The receiver published this Binder after the lock-free snapshot above. WHOAMI
                // must run outside LOCK, just like the ordinary fast path.
                lateBinder = currentBinder;
                ticket = null;
            } else {
                ticket = sConnectSingleFlight.join();
            }
            if (lateBinder != null) {
                // Drop LOCK before the synchronous Binder transaction below.
            } else if (!ticket.isLeader()) {
                // The leader already owns receiver setup, bootstrap, metrics, and Binder wait.
                // Drop LOCK before waiting so the broadcast receiver can publish the Binder.
            } else {
                // v1.6.102 — circuit-breaker for a permanently-dead self-ADB transport
                // (e.g. D50F_LC: ADB-over-TCP off / app unprivileged). The keeper (10 s) and
                // watchdog (30 s) both call connect(); without this each would pay a full
                // blocking bootstrap every cycle, forever. When AdbLocalClient has classified
                // the transport as unreachable, bail fast without bootstrapping — but still
                // allow ONE real attempt every XPORT_RECHECK_MS so it self-heals if ADB-TCP
                // is enabled later without restarting the app.
                if (AdbLocalClient.isAdbTransportUnreachable()) {
                    long now = SystemClock.elapsedRealtime();
                    long recheckMs = ProxyTransportRetryPolicy.recheckMs(
                            AdbLocalClient.adbTransportState(),
                            XPORT_RECHECK_MS,
                            XPORT_AUTH_RECHECK_MS);
                    if (now - sLastDeadXportAttemptMs < recheckMs) {
                        ticket.complete(false);
                        return false;
                    }
                    sLastDeadXportAttemptMs = now;
                }
                // Arm the latch BEFORE registering the receiver so that a broadcast
                // arriving immediately after registration (daemon already alive) finds
                // a non-null latch and can count it down rather than being silently
                // dropped. Both operations are inside LOCK so onReceive() cannot
                // interleave, but creating the latch first is the safer ordering.
                binderSignal = new CountDownLatch(1);
                sBinderLatch = binderSignal;
                try {
                    ensureReceiverRegistered(ctx);
                } catch (Throwable registrationError) {
                    sBinderLatch = null;
                    ticket.complete(false);
                    AppLogger.e(TAG, "proxy receiver registration failed", registrationError);
                    return false;
                }
            }
        }

        if (lateBinder != null) return handshakeAndVerify(lateBinder);

        if (!ticket.isLeader()) {
            try {
                return Boolean.TRUE.equals(ticket.await(CONNECT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.TimeoutException timeout) {
                AppLogger.w(TAG, "timed out joining in-flight daemon connect");
                return false;
            }
        }

        boolean result = false;
        try {
            AppLogger.i(TAG, "bootstrapping daemon via AdbLocalClient");
            String bootMsg = bootstrap(ctx);
            AppLogger.d(TAG, "bootstrap result: " + bootMsg);

            // v1.2.78 — Couche 4: metric instrumentation + ERR_NO_APK fast-path.
            // The bootstrap script returns one of:
            //   "REBROADCAST <pid>"  → live daemon, trigger file touched
            //   <nothing>            → cold spawn launched (app_process detached)
            //   "ERR_NO_APK"         → PM has not indexed our APK yet (post-OTA race)
            //   "ERR ..."            → ADB transport error
            // The actual success/fail will be decided by the latch below, but the
            // bootstrap-side outcome tells us WHY we are about to wait.
            String upper = bootMsg == null ? "" : bootMsg.trim();
            if (AdbLocalClient.isAdbTransportUnreachable()) {
                // The first failure entered connect() while transport state was still null, so
                // the entry circuit-breaker could not timestamp it. Arm the recheck window now;
                // otherwise the very next 10 s keeper heartbeat performs a second full attempt.
                sLastDeadXportAttemptMs = SystemClock.elapsedRealtime();
            }
            if (upper.startsWith("REBROADCAST")) {
                ProxyMetrics.inc(ctx, ProxyMetrics.K_REBROADCASTS);
            } else if (upper.equals("ERR_NO_APK") || upper.contains("ERR_NO_APK")) {
                ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_NO_APK);
                // Force the next attemptReconnect to bypass cooldown — the PM
                // race window is sub-second and a 1s+ wait wastes UX.
                synchronized (LOCK) {
                    sLastReconnectAttemptMs = 0L;
                    sBackoffStep = 0;
                }
            }

            // CRITICAL: await() must NOT be called while holding LOCK. The broadcast
            // arrives on the main thread, onReceive() tries to take LOCK to set
            // sBinder, and would block until our await() times out. v1.1.7 hit
            // exactly this deadlock: the broadcast was always 0 ms late because the
            // receiver was blocked on us. CountDownLatch.await() does not release
            // monitors the way Object.wait() does, so we have to drop LOCK manually.
            //
            // v1.3.9 — REBROADCAST fast-path: when the daemon is already alive
            // (REBROADCAST), ProxyDaemonMain's trigger-file poll delivers the
            // broadcast within one poll period. That period and this budget are the
            // same decision and now live together in ProxyBootstrapPolicy, derived
            // from each other -- do NOT restate either as a literal here again; the
            // prose version of this coupling drifted and shipped a regression.
            // Use this shorter timeout so the fallback am-start triggers quickly
            // rather than blocking 15s.
            // Cold-spawn still uses the full 15s (the JVM boot itself takes 5-8s
            // on DiLink SoCs). A classified transport failure gets only a 2s grace
            // in case the detached daemon started just before the socket failed.
            long waitMs = ProxyBootstrapPolicy.binderWaitMs(
                    upper,
                    AdbLocalClient.isAdbTransportUnreachable(),
                    ProxyBootstrapPolicy.REBROADCAST_BUDGET_MS,
                    TRANSPORT_FAILURE_BINDER_GRACE_MS,
                    BROADCAST_WAIT_MS);
            try {
                binderSignal.await(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }

            IBinder receivedBinder = sBinder;
            if (receivedBinder != null && receivedBinder.isBinderAlive() && sDaemonUid < 0) {
                handshake(receivedBinder);
            }

            synchronized (LOCK) {
                // Late-arrival recovery: the receiver may have signalled just after
                // the latch timed out — re-check rather than failing hard.
                // 1.2.31 — isBinderAlive() (local check, 0 IPC) instead of
                // pingBinder() (Binder roundtrip): the live binder cache is hooked
                // via linkToDeath in the receiver above, so isBinderAlive is
                // strictly equivalent here and avoids one IPC while holding LOCK.
                if (sBinder == null || !sBinder.isBinderAlive()) {
                    AppLogger.w(TAG, "no live binder after " + waitMs
                            + "ms (latch=" + (binderSignal.getCount() == 0
                            ? "signalled" : "timed-out") + ")");
                    sBinder = null;
                    // v1.2.78 — Couche 4: distinguish timeout vs other bootstrap fail.
                    String transportState = AdbLocalClient.adbTransportState();
                    if (AdbLocalClient.XPORT_UNRESPONSIVE.equals(transportState)
                            || upper.contains("timed out")
                            || upper.startsWith("REBROADCAST") || upper.isEmpty()) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_TIMEOUT);
                    } else if (!upper.equals("ERR_NO_APK") && !upper.contains("ERR_NO_APK")) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_OTHER);
                    }
                    return false;
                }
                result = isConnected() && sDaemonUid >= 0;
                if (result) {
                    AppLogger.i(TAG, "daemon ready (uid=" + sDaemonUid
                            + " pid=" + sDaemonPid + " ver=" + sDaemonVer + ")");
                    // v1.2.78 — Couche 4: count cold spawn (REBROADCAST already
                    // counted above and we shouldn't double-count it as a cold one).
                    if (!upper.startsWith("REBROADCAST")) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_COLD_SPAWNS);
                    }
                    // v1.2.78 — reset backoff on success so the next failure starts
                    // at step 0 (1s) instead of inheriting the previous run's state.
                    sBackoffStep = 0;
                }
                return result;
            }
        } finally {
            synchronized (LOCK) {
                if (sBinderLatch == binderSignal) sBinderLatch = null;
            }
            ticket.complete(result);
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
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            long t0 = SystemClock.elapsedRealtime();
            b.transact(ProxyDaemonContract.TXN_PING, data, reply, 0);
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

    /** True when the connected daemon reports a numeric protocol at least {@code minimum}. */
    public static boolean supportsProtocol(int minimum) {
        String version = sDaemonVer;
        if (version == null) return false;
        try { return Integer.parseInt(version) >= minimum; }
        catch (NumberFormatException ignored) { return false; }
    }

    /**
     * Run a shell command on the daemon and return its combined stdout/stderr.
     * Blocks until the daemon completes the command.
     *
     * <p>Build 195 / P1: lock-free. Concurrent {@code runShell} / typed-verb
     * calls dispatch in parallel through the daemon's binder thread pool
     * instead of serializing behind a single static mutex.
     */
    public static String runShell(String cmd) throws ProxyException {
        return callWithRetry("runShell", () -> ProxyProcessVerbs.runShell(cmd));
    }

    /**
     * Run the full Phase 4 feasibility probe suite inside the daemon and return
     * the raw pipe-separated result string ({@code "P1=PASS:...|P2=FAIL_SECURITY:..."}).
     * Parse with {@link com.byd.dashcast.proxy.daemon.Phase4Probes#parse(String)}.
     *
     * <p>Probes run sequentially in the daemon process under uid 2000; the whole
     * call typically returns in &lt; 1 s.
     */
    public static String runPhase4Probes() throws ProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            b.transact(ProxyDaemonContract.TXN_PROBE_PHASE4, data, reply, 0);
            reply.readException();
            String out = reply.readString();
            return out == null ? "" : out;
        } catch (RemoteException e) {
            invalidateBinder("Phase4Probes");
            throw new ProxyException("transact: " + e.getMessage(), e);
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
     * one-shot reflection cache warm-up in {@link com.byd.dashcast.proxy.daemon.Phase4DisplayVerbs}).
     */
    public static void setOverscan(int displayId, int left, int top, int right, int bottom)
            throws ProxyException {
        callWithRetry("setOverscan",
                () -> { ProxyDisplayVerbs.setOverscan(displayId, left, top, right, bottom); return null; });
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
    public static String getPidsByPackage(String packageName) throws ProxyException {
        final String pkg = packageName == null ? "" : packageName;
        return callWithRetry("getPidsByPackage", () -> ProxyProcessVerbs.getPidsByPackage(pkg));
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
            throws ProxyException {
        final String safeStr = str == null ? "" : str;
        callWithRetry("autoContainerSendInfo",
                () -> { ProxyProcessVerbs.autoContainerSendInfo(type, info, safeStr); return null; });
    }

    /** AutoContainer sendInfo preserving the OEM/native integer result code. */
    public static int autoContainerSendInfoResult(int type, int info, String str)
            throws ProxyException {
        if (!supportsProtocol(20)) {
            throw new ProxyException("AutoContainer result codes unsupported by daemon");
        }
        final String safeStr = str == null ? "" : str;
        return callWithRetry("autoContainerSendInfoResult",
                () -> ProxyProcessVerbs.autoContainerSendInfoResult(type, info, safeStr));
    }

    /**
     * Sends AutoContainer info after transport recovery, preserving a native result when the
     * connected daemon supports it. Returns {@code null} only for a legacy daemon whose wire
     * contract predates result codes.
     */
    public static Integer autoContainerSendInfoResultCompatible(int type, int info, String str)
            throws ProxyException {
        final String safeStr = str == null ? "" : str;
        return callWithRetry("autoContainerSendInfoResultCompatible", () -> {
            if (supportsProtocol(20)) {
                return ProxyProcessVerbs.autoContainerSendInfoResult(type, info, safeStr);
            }
            ProxyProcessVerbs.autoContainerSendInfo(type, info, safeStr);
            return null;
        });
    }

    /**
     * Typed verb for {@code AutoContainer.sendInfo2(type, data)} (AIDL transaction 3) — same binder
     * the OEM's own navigation app uses to push a serialized {@code byd.fbs.naviInfo.NaviInfo}
     * FlatBuffer (type=4) to the instrument-cluster HUD. Reaches the daemon's cached AutoContainer
     * binder via the same {@code checkSignatures} fast-path proven by {@link #autoContainerSendInfo},
     * so any {@code type} value is accepted, not only the ones the OEM's
     * {@code container_comm_cfg.json} allow-lists for app-uid callers.
     *
     * @param type BYD AutoContainer message type (4 = navigation guidance, per the OEM's own usage)
     * @param data raw payload bytes (a FlatBuffer-serialized struct for type 4)
     */
    public static void autoContainerSendInfo2(int type, byte[] data) throws ProxyException {
        callWithRetry("autoContainerSendInfo2",
                () -> { ProxyProcessVerbs.autoContainerSendInfo2(type, data); return null; });
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
            throws ProxyException {
        callWithRetry("forceStopPackage",
                () -> { ProxyProcessVerbs.forceStopPackage(packageName, userId); return null; });
    }

    /**
     * Phase 5a typed verb — ask the daemon (uid 2000) to create a
     * {@link android.hardware.display.VirtualDisplay} backed by the provided
     * {@code Surface} and return its display id. App processes cannot create
     * a {@code PUBLIC/TRUSTED} VD because {@code CAPTURE_VIDEO_OUTPUT} is
     * {@code signature|privileged}; this typed verb is the OpenBYD 2.0
     * cluster mini-window technique adapted to our existing proxy.
     *
     * <p>The {@code Surface} parameter is typically the output of a
     * {@link android.view.SurfaceView} the caller posted inside the cluster
     * overlay. Once the VD is alive, launch an activity onto it with
     * {@code ActivityOptions.setLaunchDisplayId(returnedId)}.
     *
     * <p>Callers MUST eventually invoke {@link #releaseVirtualDisplay} to
     * free the VD (the daemon retains a strong reference until then).
     *
     * <p>If the daemon predates build 235 (PROTOCOL_VERSION ≤ "2"), this
     * call throws a {@link ProxyException} with cause
     * {@link RemoteException} reporting "Unknown transaction" — caller
     * should surface a "daemon obsolète, redémarre-le" hint.
     *
     * @since v1.2.39 build 235 — Phase 5a (Cluster mini-mode POC, Diag-only).
     */
    public static int createVirtualDisplay(String name, int width, int height,
                                           int densityDpi,
                                           android.view.Surface surface, int flags)
            throws ProxyException {
        IBinder b = sBinder;
        if (b == null || !b.isBinderAlive()) throw new ProxyException("not connected");
        if (surface == null || !surface.isValid()) throw new ProxyException("surface null or invalid");
        try {
            return ProxyDisplayVerbs.createVirtualDisplay(
                    b, name, width, height, densityDpi, flags, surface);
        } catch (RemoteException e) {
            clearConnectionIfCurrent(b);
            throw new ProxyException("transact: " + e.getMessage(), e);
        }
    }

    /**
     * Release a VirtualDisplay previously returned by
     * {@link #createVirtualDisplay}. Best-effort: silently no-ops on
     * unknown ids in the daemon. Always pair every successful
     * createVirtualDisplay with a matching releaseVirtualDisplay (typically
     * inside {@code SurfaceHolder.Callback#surfaceDestroyed}).
     *
     * @since v1.2.39 build 235 — Phase 5a.
     */
    public static void releaseVirtualDisplay(int displayId) throws ProxyException {
        callWithRetry("releaseVirtualDisplay",
                () -> { ProxyDisplayVerbs.releaseVirtualDisplay(displayId); return null; });
    }

    /**
     * OpenBYD 2.0 launchAndForce sequence — run inside the daemon (shell uid):
     * <ol>
     *   <li>{@code am start} the package (no {@code --display}) so it lands
     *       on display 0 first;</li>
     *   <li>poll {@code IActivityTaskManager.getTasks()} via reflection until
     *       the new taskId appears (or timeout);</li>
     *   <li>two-pass force-redirect loop: {@code moveTaskToDisplay},
     *       {@code resizeTask}, {@code setFocusedRootTask}.</li>
     * </ol>
     * The second pass catches apps (Waze) that internally re-launch their
     * main activity with {@code FLAG_ACTIVITY_LAUNCH_ADJACENT}, which would
     * otherwise bounce back to display 0.
     *
     * <p>This bypasses {@code ActivityStackSupervisor.canPlaceEntityOnDisplay}
     * because that gate only fires on new {@code startActivity} calls — task
     * relocation of an existing task is unrestricted (modulo shell-level
     * MANAGE_ACTIVITY_STACKS / INTERNAL_SYSTEM_WINDOW, which uid 2000 holds).
     *
     * @param pkg         target package, e.g. {@code "com.waze"}
     * @param activityCls optional FQCN to force-launch, or null for the
     *                    default launcher activity
     * @param displayId   destination display id
     * @param width       desired width inside the display (px); 0 = full
     * @param height      desired height (px); 0 = full
     * @return verbose multi-line log of what the daemon did
     * @since v1.2.45 build 241 — Phase 5b.
     */
    public static String launchAndForce(String pkg, String activityCls,
                                        int displayId, int width, int height)
            throws ProxyException {
        if (pkg == null || pkg.isEmpty()) throw new ProxyException("pkg required");
        return callWithRetry("launchAndForce",
                () -> ProxyFissionVerbs.launchAndForce(pkg, activityCls, displayId, width, height));
    }

    /** Best-effort teardown guard; never reconnects or blocks teardown on an old daemon. */
    public static boolean cancelFissionWatchdog(String packageName) {
        if (packageName != null && packageName.isEmpty()) return false;
        if (!isConnected() || !supportsProtocol(21)) {
            return false;
        }
        try {
            return ProxyFissionVerbs.cancelFissionWatchdog(packageName);
        } catch (Throwable error) {
            AppLogger.w(TAG, "cancelFissionWatchdog failed for " + packageName
                    + ": " + error.getMessage());
            return false;
        }
    }

    public static boolean cancelAllFissionWatchdogs() {
        return cancelFissionWatchdog(null);
    }

    /**
     * Phase 6 — Move an existing task to {@code displayId} and resize it to
     * the given rect (in destination-display pixels). No am-start, no
     * polling. The task must already exist (use {@link #launchAndForce} once
     * to create it, then drive this verb interactively to reposition the
     * floating window inside the fission display).
     *
     * @return verbose multi-line log of what the daemon did
     * @since v1.2.58 — Phase 6 (Diag move/resize UI).
     */
    public static String moveAndResize(String pkg, int displayId,
                                       int left, int top, int right, int bottom)
            throws ProxyException {
        if (pkg == null || pkg.isEmpty()) throw new ProxyException("pkg required");
        return callWithRetry("moveAndResize",
                () -> ProxyFissionVerbs.moveAndResize(pkg, displayId, left, top, right, bottom));
    }

    /**
     * Phase 6b — Destroy every non-fullscreen, non-home stack on
     * {@code displayId}. Recovery verb for the fission display when an
     * earlier session left a zombie split-screen-primary / freeform stack
     * (regression v1.2.61 → 1.2.62 : "Can only have one child on stack
     * mode=split-screen-primary" on every subsequent launch via IAM).
     *
     * <p>Safe to call repeatedly and at any time — on a clean display the
     * call simply finds nothing to remove. Returns a verbose multi-line log
     * of what was inspected and what was removed.
     *
     * @since v1.2.63
     */
    public static String cleanFissionStacks(int displayId) throws ProxyException {
        return callWithRetry("cleanFissionStacks",
                () -> ProxyFissionVerbs.cleanFissionStacks(displayId));
    }

    /**
     * Phase 7 typed verb — find the task ID hosting {@code packageName} via
     * {@code IActivityTaskManager.getTasks()} reflection inside the daemon.
     * Returns -1 if no matching task is found or the call fails.
     *
     * <p>Pair with {@link #removeTask} before {@link #forceStopPackage} to
     * prevent orphan tasks from re-appearing on display 0 after teardown.
     */
    public static int findTaskIdForPackage(String packageName) throws ProxyException {
        final String pkg = packageName == null ? "" : packageName;
        return callWithRetry("findTaskIdForPackage",
                () -> ProxyProcessVerbs.findTaskIdForPackage(pkg));
    }

    /** Locate a package task and the display that currently owns it. */
    public static com.byd.dashcast.infrastructure.task.TaskLocation findTaskLocationForPackage(
            String packageName) throws ProxyException {
        final String pkg = packageName == null ? "" : packageName;
        return callWithRetry("findTaskLocationForPackage",
                () -> ProxyProcessVerbs.findTaskLocationForPackage(pkg));
    }

    /**
     * Phase 7 typed verb — remove a task from the ActivityTaskManager recents
     * stack via {@code IActivityTaskManager.removeTask(int)} reflection inside
     * the daemon. Call this before {@link #forceStopPackage} to avoid orphan
     * tasks on display 0 after session teardown.
     *
     * <p>Throws {@link ProxyException} if the daemon rejects the call (e.g.
     * the ATM method was renamed by this OEM build) — callers should fall back
     * to {@code am task remove} via ADB in that case.
     */
    public static void removeTask(int taskId) throws ProxyException {
        callWithRetry("removeTask",
                () -> { ProxyProcessVerbs.removeTask(taskId); return null; });
    }

    // ─── CAN bus write verbs (Phase CAN-1, v1.4.7-beta) ───────────────────

    /**
     * Set the navigation status on the instrument cluster HUD.
     *
     * @param status {@code CanWriteVerbs.NAVI_STATUS_ACTIVE} (2) to start navigation display,
     *               {@code CanWriteVerbs.NAVI_STATUS_STOPPED} (4) to stop it.
     * @return SDK result code (0 = success).
     */
    public static int canNaviStatus(int status) throws ProxyException {
        return callWithRetry("canNaviStatus", () -> ProxyCanVerbs.canNaviStatus(status));
    }

    /**
     * Write an integer value to a CAN instrument feature ID.
     *
     * <p>Use the constants in {@link com.byd.dashcast.proxy.daemon.CanWriteVerbs}
     * or call the higher-level helpers in {@link com.byd.dashcast.system.CanBusController}.
     *
     * @param featureId raw BYD CAN feature constant
     * @param value     integer to write
     * @return SDK result code (0 = success).
     */
    public static int canInstrumentInt(int featureId, int value) throws ProxyException {
        return callWithRetry("canInstrumentInt",
                () -> ProxyCanVerbs.canInstrumentInt(featureId, value));
    }

    /**
     * Write a byte buffer to a CAN instrument feature ID (e.g. street name as UTF-8).
     *
     * @param featureId raw BYD CAN feature constant
     * @param bytes     payload; null is treated as an empty array
     * @return SDK result code (0 = success).
     */
    public static int canInstrumentBytes(int featureId, byte[] bytes) throws ProxyException {
        final byte[] payload = (bytes == null) ? new byte[0] : bytes;
        return callWithRetry("canInstrumentBytes",
                () -> ProxyCanVerbs.canInstrumentBytes(featureId, payload));
    }

    /**
     * Write an integer value to a CAN <em>setting</em> feature ID via
     * {@code BYDAutoSettingDevice} inside the daemon.
     *
     * <p>Required for activating the navigation screen on the instrument cluster:
     * call {@code canSettingInt(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3)}
     * immediately after {@code canNaviStatus(NAVI_STATUS_ACTIVE)}.
     *
     * @param featureId raw BYD CAN setting feature constant
     * @param value     integer to write
     * @return SDK result code (0 = success).
     */
    public static int canSettingInt(int featureId, int value) throws ProxyException {
        return callWithRetry("canSettingInt",
                () -> ProxyCanVerbs.canSettingInt(featureId, value));
    }

    /** Executes an ordered CAN write group with truthful applied-count semantics (protocol v24+). */
    public static int canBatch(java.util.List<com.byd.dashcast.system.CanBatchOperation> operations)
            throws ProxyException {
        if (!supportsProtocol(24)) throw new ProxyException("truthful CAN batch unsupported by daemon");
        try {
            // Do not use callWithRetry here: a RemoteException can arrive after the daemon applied
            // a prefix of the group. Replaying the whole batch would violate exactly-once grouping.
            return ProxyCanVerbs.canBatch(operations);
        } catch (RemoteException transportError) {
            invalidateBinder("canBatch");
            throw new ProxyException("canBatch transact: " + transportError.getMessage(),
                    transportError);
        }
    }

    /**
     * Read an integer from a CAN <em>instrument</em> feature via
     * {@code BYDAutoInstrumentDevice.get(int[])} inside the daemon (privileged context).
     *
     * <p>In-app reads are rejected by the SDK ({@code InvocationTargetException}); the
     * daemon's permission-bypass context is the only path that the SDK accepts.
     *
     * @param featureId raw BYD CAN instrument feature constant (e.g. a {@code *_FEEDBACK} id)
     * @return the feature's current integer value
     */
    public static int canInstrumentGet(int featureId) throws ProxyException {
        return callWithRetry("canInstrumentGet",
                () -> ProxyCanVerbs.canInstrumentGet(featureId));
    }

    /**
     * Read an integer from a CAN <em>setting</em> feature via
     * {@code BYDAutoSettingDevice.get(int[])} inside the daemon (privileged context).
     * Used to read e.g. {@code SET_HUD_MODE_FEEDBACK} while the OEM nav drives the HUD.
     *
     * @param featureId raw BYD CAN setting feature constant
     * @return the feature's current integer value
     */
    public static int canSettingGet(int featureId) throws ProxyException {
        return callWithRetry("canSettingGet",
                () -> ProxyCanVerbs.canSettingGet(featureId));
    }

    /**
     * Write a DOUBLE value to a CAN <em>setting</em> feature via {@code BYDAutoSettingDevice}
     * inside the daemon. Required for the HUD angle ({@code CanWriteVerbs.SET_HUD_ANGLE}), which
     * the OEM CarSettings app writes as a double.
     *
     * @param featureId raw BYD CAN setting feature constant
     * @param value     double to write
     * @return SDK result code (0 = success).
     */
    public static int canSettingDouble(int featureId, double value) throws ProxyException {
        return callWithRetry("canSettingDouble",
                () -> ProxyCanVerbs.canSettingDouble(featureId, value));
    }

    /**
     * Read up to {@code maxLen} bytes of {@code path} at {@code offset} from inside the daemon
     * (uid 2000 = shell), which can read {@code /data/local/tmp} files SELinux hides from the app
     * uid. Returns an empty array at EOF. Loop, advancing {@code offset} by each chunk's length,
     * to pull an arbitrarily large file without overflowing a single Binder parcel.
     *
     * @param path   absolute path on the device
     * @param offset byte offset to read from
     * @param maxLen max bytes to read (daemon clamps to a Binder-safe ceiling)
     * @return the bytes read (length 0 at EOF)
     */
    public static byte[] readFileChunk(String path, long offset, int maxLen) throws ProxyException {
        return callWithRetry("readFileChunk",
                () -> ProxyFileVerbs.readFileChunk(path, offset, maxLen));
    }

    /**
     * Register a BYD setting feedback listener inside the daemon to capture PUSH feedback
     * (the HUD/nav feature values are push-only — not gettable). Idempotent.
     *
     * @return a short status string ("registered…" / "already-registered")
     */
    public static String canListenStart() throws ProxyException {
        return callWithRetry("canListenStart", ProxyCanVerbs::canListenStart);
    }

    /** Drain (return + clear) the push events captured by the daemon listener. */
    public static String canListenDrain() throws ProxyException {
        return callWithRetry("canListenDrain", ProxyCanVerbs::canListenDrain);
    }

    /**
     * AAOS-only: probe the automotive display proxy HAL (IAutomotiveDisplayProxyService) from the
     * daemon (uid 2000) — tests whether app windows can be drawn to the cluster panel.
     *
     * @return the probe report (HAL reachability + getHGraphicBufferProducer result)
     */
    public static String aaosHalProbe() throws ProxyException {
        return callWithRetry("aaosHalProbe", ProxyCanVerbs::aaosHalProbe);
    }

    /**
     * Read-only probe of the native {@code FissionHostSvc} display registry (DL3 only,
     * transaction 101 = {@code getAutoCarDisplay}) — RE'd from a real firmware pull, never called
     * before v1.8.26-beta. Returns raw hex plus a best-effort decode; "SERVICE NOT FOUND" is a
     * normal, expected answer on every platform but DL3.
     */
    public static String fissionGetAutoCarDisplay() throws ProxyException {
        return callWithRetry("fissionGetAutoCarDisplay", ProxyNativeServiceVerbs::fissionGetAutoCarDisplay);
    }

    /**
     * Arms the daemon's {@code AutoContainer.registerCallback} listener (AIDL transaction 4,
     * documented since the DL3 RE pass, never called before this release). The registration lives
     * for the rest of THIS daemon process — re-arm after any daemon respawn. Payoff is
     * asynchronous: any push the native service makes afterward lands in the daemon's own
     * transcript, {@code --- PROXYDAEMON LOG ---} in the next bug report.
     *
     * @return the native result code from {@code registerCallback}
     */
    public static int autoContainerRegisterCallback() throws ProxyException {
        return callWithRetry("autoContainerRegisterCallback", ProxyNativeServiceVerbs::autoContainerRegisterCallback);
    }

    /**
     * Arms a ~90s-capped background sampler of the {@code FissionHostSvc} registry, one sample
     * every ~2s, logged only on change. Call {@link #projectionTraceDrain()} after the tester has
     * run their normal projection start/stop cycle.
     */
    public static void projectionTraceStart() throws ProxyException {
        callWithRetry("projectionTraceStart", () -> { ProxyNativeServiceVerbs.projectionTraceStart(); return null; });
    }

    /** Stops the sampler armed by {@link #projectionTraceStart()} and returns every change recorded. */
    public static String projectionTraceDrain() throws ProxyException {
        return callWithRetry("projectionTraceDrain", ProxyNativeServiceVerbs::projectionTraceDrain);
    }

    /** Clear the push-feedback log + persistent last-known map (for a fresh, uncontaminated read). */
    public static void canListenClear() throws ProxyException {
        callWithRetry("canListenClear", () -> { ProxyCanVerbs.canListenClear(); return null; });
    }

    /** Append a timestamped user ground-truth marker (e.g. the HUD maneuver just seen) to the log. */
    public static void canListenMark(String label) throws ProxyException {
        callWithRetry("canListenMark", () -> { ProxyCanVerbs.canListenMark(label); return null; });
    }

    /**
     * Force-kill the running daemon (if any) so the next {@link #connect}
     * bootstraps a fresh one. Useful after installing an APK that ships new
     * typed verbs: the old daemon process keeps the previous APK's
     * classpath loaded and would reject newer TXN codes with "Unknown
     * transaction".
     *
     * <p>Diag-only helper. The rest of the app should keep using
     * {@link #connect(Context)} which reuses the live daemon.
     *
     * <p>Safe to call when no daemon is alive — the kill is best-effort and
     * the reconnect attempts the standard ADB-pairing bootstrap.
     *
     * @since v1.2.39 build 235 — Phase 5a.
     */
    public static boolean killAndRestartDaemon(Context ctx) {
        try {
            IBinder b = sBinder;
            if (b != null && b.isBinderAlive()) {
                try {
                    // Use the existing EXEC transport to kill ourselves —
                    // simplest and avoids new shell perms on the caller side.
                    runShell("ps -A 2>/dev/null | grep '[d]ashcast_proxy' "
                            + "| awk '{print $2}' | xargs -r kill -9");
                } catch (Throwable ignore) { /* daemon may already be dead */ }
            }
            // M10: acquire LOCK + unlinkToDeath before clearing state so we don't
            // race with the broadcast receiver or sDeath.binderDied().
            synchronized (LOCK) {
                IBinder dead = sBinder;
                if (dead != null) {
                    unlinkDeathLocked(dead);
                }
                sBinder = null;
                sDaemonUid = -1;
                sDaemonPid = -1;
                sDaemonVer = null;
                sDaemonInstance = null;
            }
            // Give AMS / the kernel a moment to reap the old process before
            // the receiver waits for the next broadcast.
            try { Thread.sleep(400L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return connect(ctx);
        } catch (Throwable t) {
            AppLogger.w(TAG, "killAndRestartDaemon failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Terminates the authenticated proxy PID through direct local ADB after a typed Binder call
     * exceeded its deadline. This method never uses the possibly wedged Binder itself.
     *
     * @return true only when the old daemon is killed or already absent, making it impossible for
     *         the timed-out physical command to execute after a newer queued command.
     */
    public static final class DaemonIdentity {
        private final IBinder binder;
        private final int pid;
        private final String instance;

        private DaemonIdentity(IBinder binder, int pid, String instance) {
            this.binder = binder;
            this.pid = pid;
            this.instance = instance;
        }
    }

    /** Snapshot taken on the actual typed-dispatch thread immediately before Binder entry. */
    public static DaemonIdentity captureDaemonIdentity() {
        synchronized (LOCK) {
            if (sBinder == null || sDaemonPid <= 0 || sDaemonInstance == null
                    || !sDaemonInstance.matches("[0-9a-fA-F]{32}")) return null;
            return new DaemonIdentity(sBinder, sDaemonPid, sDaemonInstance);
        }
    }

    public static boolean terminateHungDaemonViaAdb(Context ctx, DaemonIdentity expected) {
        if (expected == null) return false;
        final String command = "PID=" + expected.pid
                + "; EXPECT=" + expected.instance
                + "; case \"$PID\" in ''|*[!0-9]*) echo NO_PID; exit 3;; esac"
                + "; CURRENT=$(cat " + DAEMON_INSTANCE + " 2>/dev/null)"
                + "; if [ \"$CURRENT\" != \"$EXPECT\" ]; then echo INSTANCE_CHANGED; exit 5; fi"
                + "; LINE=$(ps -A 2>/dev/null | awk -v p=\"$PID\" '$2 == p {print; exit}')"
                + "; if echo \"$LINE\" | grep -q '[d]ashcast_proxy'; then"
                + " kill -9 \"$PID\" 2>/dev/null && echo KILLED"
                + "; elif [ -z \"$LINE\" ]; then echo ABSENT"
                + "; else echo REFUSED; exit 4; fi";
        try {
            String result = AdbLocalClient.executeShellWithResultBlocking(ctx, command, 15_000);
            if (!result.contains("KILLED") && !result.contains("ABSENT")) {
                if (!expected.binder.isBinderAlive()) {
                    AppLogger.i(TAG, "hung daemon already superseded: " + result);
                    return true;
                }
                AppLogger.e(TAG, "hung daemon recovery refused: " + result);
                return false;
            }
            synchronized (LOCK) {
                if (sBinder == expected.binder && sDaemonPid == expected.pid
                        && expected.instance.equals(sDaemonInstance)) {
                    unlinkDeathLocked(expected.binder);
                    sBinder = null;
                    sDaemonUid = -1;
                    sDaemonPid = -1;
                    sDaemonVer = null;
                    sDaemonInstance = null;
                }
            }
            AppLogger.e(TAG, "hung proxy daemon terminated via direct ADB: " + result);
            return true;
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            AppLogger.e(TAG, "hung daemon recovery failed: " + error.getMessage());
            return false;
        }
    }

    // ─── Auto-recovery helpers (v1.2.58-beta, Phase A step 1) ─────────────

    /**
     * Functional handle for a single binder transaction body. Implementations
     * must read {@code sBinder} fresh on every {@link #run()} call so that a
     * post-reconnect retry sees the new binder published by {@code connect()}.
     * Both checked exception types are declared because verb bodies need to
     * throw {@link ProxyException} for logical errors (null Surface, bad
     * argument) and {@link RemoteException} for transport failures — only the
     * latter triggers the retry.
     */
    @FunctionalInterface
    private interface BinderOp<T> {
        T run() throws RemoteException, ProxyException;
    }

    /**
     * Best-effort daemon revive, rate-limited by {@link #RECONNECT_COOLDOWN_MS}.
     *
     * <p>Called from {@link #callWithRetry(String, BinderOp)} when a verb
     * either finds a dead binder at entry or trips a {@link RemoteException}
     * mid-transact. Skips the attempt if the previous one ran less than
     * {@link #RECONNECT_COOLDOWN_MS} ago to avoid bootstrap-storms during
     * input forwarding (~60 Hz) or resize SeekBar (~30 Hz).
     *
     * @return {@code true} if a reconnect was attempted AND a live binder is
     *         now held; {@code false} if the attempt was skipped (cooldown)
     *         or failed.
     */
    private static boolean attemptReconnect() {
        Context ctx = sAppCtx;
        if (ctx == null) {
            // No call site has ever connected — caller bug, surface as no-op.
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            // v1.2.78 — Couche 4: adaptive cooldown (1s→2s→4s→8s→10s).
            int step = Math.min(sBackoffStep, BACKOFF_MS.length - 1);
            long cooldown = BACKOFF_MS[step];
            if (now - sLastReconnectAttemptMs < cooldown) {
                AppLogger.d(TAG, "attemptReconnect skipped (cooldown, "
                        + (now - sLastReconnectAttemptMs) + "ms < " + cooldown
                        + "ms, step=" + step + ")");
                return false;
            }
            sLastReconnectAttemptMs = now;
        }
        AppLogger.i(TAG, "attemptReconnect: bootstrapping daemon (cooldown gate passed, step="
                + sBackoffStep + ")");
        boolean ok = connect(ctx);
        // v1.2.78 — Couche 4: reset/bump backoff based on outcome. connect()
        // already does the reset on success, but we set it here too so
        // attemptReconnect remains internally consistent if connect() returns
        // success via a fast path that didn't go through the bump site.
        if (ok) {
            sBackoffStep = 0;
        } else {
            sBackoffStep = Math.min(sBackoffStep + 1, BACKOFF_MS.length - 1);
        }
        return ok;
    }

    // ─── Main-thread ANR guard for the reconnect bootstrap ───────────────────
    // connect() blocks the caller for up to BOOTSTRAP_TIMEOUT_MS + BROADCAST_WAIT_MS
    // (~23s) bootstrapping a cold uid-2000 daemon. That MUST never run on the UI
    // looper (frozen instrument cluster = ANR). This single-thread executor runs the
    // blocking bootstrap off-thread when a verb is (defensively) called on main.
    private static final java.util.concurrent.ExecutorService sReconnectExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "proxy-reconnect");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean sAsyncReconnectPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Threads that own their own transport fallback (e.g. the ShellGateway serial
    // executor, which routes to AdbLocalClient on any failure) can opt out of the
    // blocking bootstrap: a binder that dies mid-transact would otherwise stall that
    // single worker ~23s before the fallback runs. When set, callWithRetry's reconnect
    // is kicked async (daemon still revives) and the verb fails fast instead.
    private static final ThreadLocal<Boolean> sNonBlockingReconnect =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Opt the CURRENT thread out of the blocking daemon bootstrap inside
     * {@link #callWithRetry}. Intended for dedicated worker threads that have their
     * own legacy fallback and must not be stalled by a cold-daemon reconnect.
     */
    public static void setNonBlockingReconnect(boolean enabled) {
        sNonBlockingReconnect.set(enabled);
    }

    /**
     * Reconnect policy that never blocks the main thread. On a background thread this
     * runs the (cooldown-gated) blocking bootstrap synchronously — unchanged behaviour.
     * On the main thread it kicks the bootstrap onto the dedicated "proxy-reconnect"
     * thread (coalesced — at most one in flight) and returns {@code false} immediately,
     * so a cold daemon can never ANR the UI: the caller's {@code op.run()} throws
     * "not connected" (handled by existing AdbLocalClient fallbacks) and the daemon
     * still revives in the background for the next call.
     *
     * @return {@code true} only when a synchronous reconnect ran AND a live binder is
     *         now held; {@code false} on the main thread (kicked async) or on failure.
     */
    private static boolean reconnectUnlessMainThread() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                || Boolean.TRUE.equals(sNonBlockingReconnect.get())) {
            if (sAsyncReconnectPending.compareAndSet(false, true)) {
                try {
                    sReconnectExecutor.execute(() -> {
                        try { attemptReconnect(); }
                        finally { sAsyncReconnectPending.set(false); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ree) {
                    sAsyncReconnectPending.set(false);
                }
            }
            return false;
        }
        return attemptReconnect();
    }

    /**
     * Wrap a typed-verb body in single-shot auto-recovery: pre-check live
     * binder (best-effort silent reconnect if dead), run the body, retry once
     * on {@link RemoteException} after a rate-limited reconnect. Logical
     * {@link ProxyException} from the body (e.g. "not connected", "null
     * Surface") propagate unchanged — they are not transport errors and a
     * retry would not change the outcome.
     *
     * <p>The {@code sBinder = null} after a failed transact is preserved
     * (eager publish so the very next caller sees the dead state without
     * waiting for {@link #sDeath} to fire).
     *
     * @param tag short identifier used in failure log lines (e.g.
     *            {@code "setOverscan"})
     * @param op  the verb body — must re-read {@code sBinder} on each call
     */
    private static <T> T callWithRetry(String tag, BinderOp<T> op) throws ProxyException {
        // Pre-flight: if no live binder, opportunistically reconnect once
        // (cooldown-gated) before the first attempt. If reconnect fails, the
        // body will throw "not connected" on its own — sites that still want
        // the legacy fallback (e.g. AdbLocalClient.sendInfo) catch and route.
        IBinder pre = sBinder;
        if (pre == null || !pre.isBinderAlive()) {
            reconnectUnlessMainThread();
        }
        try {
            return op.run();
        } catch (RemoteException e) {
            // C3: guard sBinder null-out inside LOCK so a freshly-arrived binder
            // from the broadcast receiver (written inside LOCK) is not clobbered.
            synchronized (LOCK) {
                IBinder dead = sBinder;
                if (dead != null && !dead.isBinderAlive()) {
                    unlinkDeathLocked(dead);
                    sBinder = null;
                }
            }
            AppLogger.w(TAG, tag + " RemoteException: " + e.getMessage()
                    + " — attempting reconnect");
            if (!reconnectUnlessMainThread()) {
                throw new ProxyException(tag + ": " + e.getMessage(), e);
            }
            try {
                return op.run();
            } catch (RemoteException e2) {
                synchronized (LOCK) {
                    IBinder dead = sBinder;
                    if (dead != null && !dead.isBinderAlive()) {
                        unlinkDeathLocked(dead);
                        sBinder = null;
                    }
                }
                throw new ProxyException(
                        tag + " (after reconnect): " + e2.getMessage(), e2);
            }
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────

    /**
     * Register the dynamic {@link BroadcastReceiver} once per app process.
     * Uses the application context so the lifetime is tied to the process,
     * not to any short-lived Activity that happens to call us first.
     * Note: The lack of an unregisterReceiver call is process-scoped intentional (P2-1)
     * as the dynamic receiver is registered once on the application context and persists
     * for the entire lifetime of the process. Gated by sReceiver null check.
     */
    private static void ensureReceiverRegistered(Context ctx) {
        if (sReceiver != null) return;
        final Context appCtx = ctx.getApplicationContext();
        sReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                if (!ProxyDaemonContract.ACTION_PROXY_CONNECTED.equals(intent.getAction())) return;
                BinderParcelable bp = intent.getParcelableExtra(ProxyDaemonContract.EXTRA_BINDER);
                if (bp == null || bp.binder == null) {
                    AppLogger.w(TAG, "PROXY_CONNECTED received without binder extra");
                    return;
                }
                // Discard stale broadcasts whose binder is already dead — they happen
                // when a previous bootstrap killed an in-flight daemon and AMS only
                // dispatched its broadcast after the kill. Storing the dead ref would
                // mask the LIVE binder we either already have or are still waiting for
                // (root cause of A5/A6 ✗ in v1.1.6).
                // P3-1: use isBinderAlive() (local cache check) instead of
                // pingBinder() (Binder round-trip) — coherent with sBinder check
                // below at L825. A dead binder will still be rejected because
                // sDeath would have invalidated the cache.
                if (!bp.binder.isBinderAlive()) {
                    AppLogger.d(TAG, "ignoring stale PROXY_CONNECTED (binder already dead)");
                    return;
                }
                // Authenticate the daemon: adopt the broadcast binder only if it matches the one the
                // real daemon registered in the global ServiceManager (only uid-2000/system can
                // addService — SELinux blocks apps). A spoofed broadcast carries a fake binder that
                // won't match. If there is NO entry (older daemon / addService failed on this ROM),
                // fall back to the broadcast binder (prior behaviour) so this can never break the
                // daemon path — including across the update where a pre-S2 daemon is still running.
                IBinder registered = lookupRegisteredProxyBinder();
                if (registered != null && registered != bp.binder) {
                    AppLogger.w(TAG, "PROXY_CONNECTED binder ≠ ServiceManager entry — ignoring (spoofed?)");
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
                        unlinkDeathLocked(sBinder);
                    }
                    // Invalidate identity BEFORE publishing the replacement Binder. Lock-free
                    // readers must never observe a new Binder paired with the previous daemon's UID.
                    sDaemonUid = -1;
                    sDaemonPid = -1;
                    sDaemonVer = null;
                    sDaemonInstance = null;
                    sBinder = bp.binder;
                    // Hook the new binder so a future death immediately clears
                    // our cached reference (P2). Best-effort: if linkToDeath
                    // fails (binder already dead between isBinderAlive above
                    // and here — vanishingly unlikely), isBinderAlive() on
                    // the next call still gives the right answer.
                    try { linkDeathLocked(sBinder); }
                    catch (RemoteException re) {
                        AppLogger.w(TAG, "linkToDeath failed: " + re.getMessage());
                    }
                    AppLogger.i(TAG, "live binder received from daemon");
                    CountDownLatch latch = sBinderLatch;
                    if (latch != null) latch.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ProxyDaemonContract.ACTION_PROXY_CONNECTED);
        // The daemon runs as uid 2000 (com.android.shell), which holds DUMP. Requiring that
        // sender permission preserves the broadcast fallback on ROMs where addService fails,
        // without allowing an ordinary co-installed app to supply a fake Binder.
        DaemonBroadcastRegistrar.register(appCtx, sReceiver, filter);
        AppLogger.d(TAG, "dynamic receiver registered for " + ProxyDaemonContract.ACTION_PROXY_CONNECTED);
    }

    /** Reflective {@code ServiceManager.getService(ProxyDaemonMain.SERVICE_NAME)} — the trusted
     *  anchor for authenticating a PROXY_CONNECTED broadcast binder. null if absent or on error. */
    private static IBinder lookupRegisteredProxyBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            return (IBinder) getService.invoke(null,
                    com.byd.dashcast.proxy.daemon.ProxyDaemonMain.SERVICE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Runs WHOAMI and confirms its published identity still belongs to the expected Binder. */
    private static boolean handshakeAndVerify(IBinder expectedBinder) {
        if (!handshake(expectedBinder)) return false;
        synchronized (LOCK) {
            return sBinder == expectedBinder
                    && expectedBinder.isBinderAlive()
                    && sDaemonUid >= 0;
        }
    }

    /** Issue WHOAMI without holding {@link #LOCK}; publish only if the Binder is still current. */
    private static boolean handshake(IBinder expectedBinder) {
        if (expectedBinder == null || !expectedBinder.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR);
            if (!expectedBinder.transact(ProxyDaemonContract.TXN_WHOAMI, data, reply, 0)) return false;
            reply.readException();
            int daemonUid = reply.readInt();
            int daemonPid = reply.readInt();
            String daemonVer = reply.readString();
            String daemonInstance = reply.dataAvail() > 0 ? reply.readString() : null;
            if (daemonUid < 0 || daemonPid <= 0 || daemonVer == null || daemonVer.isEmpty()) {
                throw new IllegalStateException("invalid WHOAMI response");
            }
            try {
                if (Integer.parseInt(daemonVer) >= 25
                        && (daemonInstance == null
                        || !daemonInstance.matches("[0-9a-fA-F]{32}"))) {
                    throw new IllegalStateException("invalid WHOAMI instance");
                }
            } catch (NumberFormatException badVersion) {
                throw new IllegalStateException("invalid WHOAMI protocol");
            }
            synchronized (LOCK) {
                if (sBinder != expectedBinder || !expectedBinder.isBinderAlive()) return false;
                sDaemonUid = daemonUid;
                sDaemonPid = daemonPid;
                sDaemonVer = daemonVer;
                sDaemonInstance = daemonInstance;
                return true;
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "handshake failed (" + e.getClass().getSimpleName() + ")");
            synchronized (LOCK) {
                if (sBinder == expectedBinder) {
                    sBinder = null;
                    sDaemonUid = -1;
                    sDaemonPid = -1;
                    sDaemonVer = null;
                    sDaemonInstance = null;
                }
            }
            return false;
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
        }, AdbLocalClient.BOOTSTRAP_IDLE_TIMEOUT_MS);
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
    public static class ProxyException extends Exception {
        public ProxyException(String msg) { super(msg); }
        public ProxyException(String msg, Throwable cause) { super(msg, cause); }
    }
}
