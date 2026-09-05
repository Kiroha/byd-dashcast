package com.byd.dashcast.proxy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.view.Surface

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.proxy.daemon.BinderParcelable
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.daemon.ProxyDaemonMain
import com.byd.dashcast.system.CanBatchOperation
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.SingleFlight

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ProxyClient — Component A client (v1.1.6+).
 *
 * Talks to the proxy daemon (see `ProxyDaemonMain`) over a direct [IBinder] reference obtained via
 * a one-shot broadcast that the daemon emits at startup (from a system [Context] obtained via
 * `ActivityThread.systemMain()`). This pattern is borrowed from OpenBYD and replaces the
 * abstract-namespace `LocalSocket` used up to 1.1.5, which was blocked by SELinux for
 * `untrusted_app` → `shell` connects on Android 10+.
 *
 * If no daemon is running, [connect] registers a dynamic [BroadcastReceiver] for
 * `ProxyDaemonContract.ACTION_PROXY_CONNECTED` and then bootstraps a daemon by issuing an
 * `app_process64` command through [AdbLocalClient] (which triggers the standard ADB-pairing flow).
 * The spawned daemon inherits the `shell` UID (2000) and outlives the app.
 *
 * Thread-safety: connection state is guarded by [LOCK]; the hot-path reads are lock-free on
 * volatile fields.
 *
 * Kotlin port note: every `$` in BOOTSTRAP_CMD and in the hung-daemon recovery script is escaped.
 * There are 32 of them, they are shell variable expansions, and an unescaped one is Kotlin string
 * interpolation — it would compile a different script with no error anywhere. Both are diffed
 * byte-for-byte against a golden dump of the Java's own compiled constants.
 */
object ProxyClient {

    private const val TAG = "ProxyClient"

    /** App package whose APK hosts the daemon main class. Must match the installed package. */
    private const val DAEMON_PKG = "com.byd.dashcast"

    /** Fully-qualified main class of the daemon. */
    private const val DAEMON_MAIN = "com.byd.dashcast.proxy.daemon.ProxyDaemonMain"

    /** Path of the daemon's stdout/stderr capture on the device (overwritten each bootstrap). */
    private const val DAEMON_LOG = "/data/local/tmp/dashcast_proxy.log"

    /** PID file written by the daemon at startup (v1.2.63-beta, Phase A step 3). */
    private const val DAEMON_PID = "/data/local/tmp/dashcast_proxy.pid"

    /** Per-process nonce paired with [DAEMON_PID]; protocol v25 WHOAMI returns it. */
    private const val DAEMON_INSTANCE = "/data/local/tmp/dashcast_proxy_instance"

    /** Trigger file watched by the daemon to ask for a binder rebroadcast. */
    private const val DAEMON_TRIGGER = "/data/local/tmp/dashcast_proxy.trigger"

    /** Bootstrap-script lock file — flock'd to serialize concurrent bootstraps. */
    private const val DAEMON_LOCK = "/data/local/tmp/dashcast_proxy.lock"

    /**
     * Bootstrap script run via local ADB. Mirrors the proven `SurfaceDaemon` recipe and preserves
     * every hard-won fix from 1.1.3–1.1.5:
     *  - `setsid` detaches from the ADB session group (survives SIGHUP);
     *  - explicit `/system/bin/app_process64`;
     *  - `-Xnoimage-dex2oat` avoids an AOT crash at startup;
     *  - `--nice-name=dashcast_proxy` sets argv[0] so the stale-kill heuristic below
     *    (`ps -A | grep '[d]ashcast_proxy'`) keeps working;
     *  - stdout/stderr redirected to [DAEMON_LOG] for cold-start diag.
     *
     * v1.2.63-beta (Phase A step 3) additions, applied before the legacy recipe so both old and new
     * daemons keep working:
     *  - `flock -n` on [DAEMON_LOCK] — atomic w.r.t. any other bootstrap invocation, so two
     *    concurrent app calls can never race. Belt-and-suspenders on top of the 10 s Java-side
     *    cooldown.
     *  - PID-file fast path: if [DAEMON_PID] points to a live process named `dashcast_proxy`, just
     *    `touch` the trigger file (watched via `FileObserver` inside the daemon) and exit with
     *    `REBROADCAST <pid>`. The daemon re-emits its binder in milliseconds — no `app_process`
     *    restart needed, no 1 s penalty after an app process restart.
     */
    private val BOOTSTRAP_CMD: String =
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
            "TRIG=" + DAEMON_TRIGGER + "; " +
             "PS_OUT=\$(ps -A 2>/dev/null | grep '[d]ashcast_proxy'); " +
             "ALIVE_PID=\$(echo \"\$PS_OUT\" | awk '{print \$2}' | head -n1); " +
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
             "PID_FILE_VAL=\$(cat /data/local/tmp/dashcast_proxy.pid 2>/dev/null); " +
             "if [ -n \"\$ALIVE_PID\" ] && [ \"\$ALIVE_PID\" != \"\$PID_FILE_VAL\" ]; then " +
               "echo \"[diag] proxy name-match pid=\$ALIVE_PID != pidfile=\${PID_FILE_VAL:-none} — ignoring\" >&2; " +
               "ALIVE_PID=; " +
             "fi; " +
             "if [ -n \"\$ALIVE_PID\" ]; then " +
            // Version check: daemon loaded from old APK after OTA has a stale
            // versionCode in VERSION_FILE — fall through to kill+restart instead
            // of REBROADCAST, so proxy verbs are always from the current APK.
               "DAEMON_VER=\$(cat /data/local/tmp/dashcast_proxy_ver 2>/dev/null); " +
               "if [ \"\$DAEMON_VER\" = \"" + BuildConfig.VERSION_CODE + "\" ]; then " +
                 "echo trigger > \"\$TRIG\" 2>/dev/null; " +
                 "echo \"REBROADCAST \$ALIVE_PID\"; exit 0; " +
               "fi; " +
               "echo \"[diag] proxy stale ver=\${DAEMON_VER:-?} expected=" + BuildConfig.VERSION_CODE + "\" >&2; " +
            // The "no daemon at all" diagnostic belongs to the ELSE of the aliveness test. It
            // used to be emitted unconditionally right after this block, so a stale-but-ALIVE
            // daemon printed "[diag] proxy stale ver=588 expected=593" and then "[diag] no_alive
            // ps_empty" in the same breath — the second line falsely asserting `ps` had found
            // nothing. Every post-OTA triage had to work out which of the two was lying.
             "else " +
               "echo \"[diag] no_alive ps_empty\" >&2; " +
             "fi; " +
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
             "APK=\$(pm path " + DAEMON_PKG + " 2>/dev/null | head -n1 | cut -d: -f2-); " +
             "if [ -z \"\$APK\" ]; then echo ERR_NO_APK; exit 1; fi; " +
             "LOG=" + DAEMON_LOG + "; " +
            // Stale-kill kept as last-line defence. If the fast path saw a
            // live daemon we'd never reach here; if it didn't but `ps` here
            // still finds one, the daemon died between the two `ps` calls —
            // then this kill is harmless (already-gone PID).
             "STALE=\$(ps -A 2>/dev/null | grep '[d]ashcast_proxy' | awk '{print \$2}'); " +
             "if [ -n \"\$STALE\" ]; then kill -9 \$STALE 2>/dev/null; sleep 0.3; fi; " +
            // Self-diagnostic header so a failed bootstrap is debuggable from the log.
             "{ echo \"[boot] \$(date) apk=\$APK\"; " +
               "echo \"[boot] id=\$(id)\"; " +
               "echo \"[boot] getenforce=\$(getenforce 2>/dev/null)\"; " +
               "echo \"[boot] stale_killed=\${STALE:-none}\"; " +
               "ls -la \"\$APK\" 2>&1; " +
               "echo \"[boot] exec app_process64...\"; } > \"\$LOG\" 2>&1; " +
             "setsid sh -c \"CLASSPATH='\$APK' exec /system/bin/app_process64" +
                 " -Xnoimage-dex2oat /system/bin" +
                 " --nice-name=dashcast_proxy" +
                 " " + DAEMON_MAIN +
                 " </dev/null >>'\$LOG' 2>&1\" & " +
             "echo OK \$APK"
    /** Fetched after a connect() failure to surface the daemon's first error line(s). */
    private val READ_LOG_CMD = "tail -n 20 $DAEMON_LOG 2>/dev/null"

    /** Covers dadb's first-command authorization window (15 s) plus callback delivery. */
    private const val BOOTSTRAP_TIMEOUT_MS = 16_000

    /**
     * The daemon's `ActivityThread.systemMain()` call takes 5–8 s cold on a DiLink 3.0 SoC (it
     * brings up the framework runtime inside app_process), then the broadcast still has to traverse
     * AMS. v1.1.6's 8 s window was racing the broadcast by ~1 ms in production. 15 s gives headroom
     * without making failure cases painfully slow.
     */
    private const val BROADCAST_WAIT_MS = 15000

    /** A timed-out/refused ADB command may still have spawned the daemon just before transport
     *  failure; keep a small grace window for its already-armed Binder broadcast. */
    private const val TRANSPORT_FAILURE_BINDER_GRACE_MS = 2_000

    private const val CONNECT_JOIN_TIMEOUT_MS =
            (BOOTSTRAP_TIMEOUT_MS + BROADCAST_WAIT_MS + 1_000).toLong()

    private val LOCK = Any()

    /** Re-probe interval for a transport classified as permanently unreachable (v1.6.102). */
    private const val XPORT_RECHECK_MS = 60_000L

    /** Authorization may become healthy immediately after the driver accepts the popup. */
    private const val XPORT_AUTH_RECHECK_MS = 2_000L

    /** Last time the dead-transport circuit-breaker allowed a real bootstrap attempt. */
    @Volatile private var sLastDeadXportAttemptMs = 0L

    /**
     * The live binder reference, or `null` when the daemon is unreachable.
     *
     * Declared `volatile` (build 195 / P1) so hot-path typed verbs ([setOverscan],
     * [getPidsByPackage], [autoContainerSendInfo], [forceStopPackage]) can read it without
     * acquiring [LOCK] — critical for the resize SeekBar (~30 overscan/s) and pidof polling
     * (~every 5 s during projection) which used to serialize behind any in-flight [runShell].
     *
     * Writes are still done under [LOCK] from [connect] / the receiver / handshake / explicit
     * error-clear paths; only the cheap reads dropped the lock.
     */
    @Volatile @JvmField internal var sBinder: IBinder? = null

    /** Receiver registered once on first [connect]; reused thereafter. */
    private var sReceiver: BroadcastReceiver? = null

    /** Set just before bootstrap; counted-down by [sReceiver] on arrival. */
    @Volatile private var sBinderLatch: CountDownLatch? = null

    /** Exactly one caller owns a cold daemon bootstrap; concurrent callers join its result. */
    private val sConnectSingleFlight = SingleFlight<Boolean>()

    // Volatile (build 195 / P1) so the public getters below stay lockless.
    @Volatile private var sDaemonUid = -1
    @Volatile private var sDaemonPid = -1
    @Volatile private var sDaemonVer: String? = null
    @Volatile private var sDaemonInstance: String? = null

    // ─── Auto-recovery (v1.2.58-beta, Phase A step 1) ─────────────────────
    /**
     * Application context captured on first successful [connect] call, used by [attemptReconnect]
     * to bootstrap the daemon when a typed verb finds a dead binder. Application-scoped (not
     * Activity), safe to hold statically.
     */
    @SuppressLint("StaticFieldLeak") // application context, process-scoped, safe
    @Volatile private var sAppCtx: Context? = null

    /**
     * Anti-storm gate for [attemptReconnect]. A reconnect attempt is skipped if the previous one
     * ran less than [RECONNECT_COOLDOWN_MS] ago. Protects against bootstrap-storms when the cluster
     * resize SeekBar (~30 setOverscan/s) or input forwarder (~60 transact/s) hits a dead binder —
     * each bootstrap is a full `app_process64` + `ActivityThread.systemMain()` (5–8 s on DiLink
     * SoCs) and serial bootstraps would kill one another via the `[d]ashcast_proxy` stale-kill
     * heuristic. Updated under [LOCK] only.
     */
    private var sLastReconnectAttemptMs = 0L

    /** Cooldown window for [attemptReconnect]. See field doc above. */
    private const val RECONNECT_COOLDOWN_MS = 10_000L

    /**
     * v1.2.78 — Couche 4: adaptive backoff steps in ms. The cooldown gate picks
     * `BACKOFF_MS[min(sBackoffStep, last)]` instead of the flat [RECONNECT_COOLDOWN_MS].
     * `sBackoffStep` is reset to 0 on every successful [connect] and bumped on every failed one.
     * ERR_NO_APK forces an immediate retry by zeroing the timestamp (the APK race is transient —
     * the next post-OTA scan completes in <1s).
     */
    private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L)
    private var sBackoffStep = 0

    /**
     * Death recipient that clears [sBinder] as soon as the kernel notifies us that the daemon
     * process died. With this in place [isConnected] no longer needs [IBinder.pingBinder] (a full
     * IPC roundtrip per call) — it can rely on the cheaper local [IBinder.isBinderAlive] check
     * (build 195 / P2).
     */
    private var sDeath: DeathRecipient? = null
    private var sDeathBinder: IBinder? = null

    /** LOCK must be held. Registers a recipient that can clear only `watchedBinder`. */
    @Throws(RemoteException::class)
    private fun linkDeathLocked(watchedBinder: IBinder) {
        val recipient = object : DeathRecipient {
            override fun binderDied() {
                synchronized(LOCK) {
                    if (sBinder !== watchedBinder) return
                    AppLogger.w(TAG, "daemon binder died — clearing matching cached reference")
                    try { watchedBinder.unlinkToDeath(this, 0) } catch (ignore: Throwable) {}
                    if (sDeath === this) {
                        sDeath = null
                        sDeathBinder = null
                    }
                    sBinder = null
                    sDaemonUid = -1
                    sDaemonPid = -1
                    sDaemonVer = null
                    sDaemonInstance = null
                    ProxyMetrics.inc(sAppCtx, ProxyMetrics.K_BINDER_ZOMBIES)
                }
            }
        }
        watchedBinder.linkToDeath(recipient, 0)
        sDeath = recipient
        sDeathBinder = watchedBinder
    }

    /** LOCK must be held. Unlinks only the recipient registered for `binder`. */
    private fun unlinkDeathLocked(binder: IBinder?) {
        val death = sDeath
        if (binder == null || binder !== sDeathBinder || death == null) return
        try { binder.unlinkToDeath(death, 0) } catch (ignore: Throwable) {}
        sDeath = null
        sDeathBinder = null
    }

    /** Clears one complete connection generation only while it is still current. */
    @JvmStatic
    internal fun clearConnectionIfCurrent(expectedBinder: IBinder?): Boolean {
        synchronized(LOCK) {
            if (sBinder !== expectedBinder) return false
            unlinkDeathLocked(expectedBinder ?: sDeathBinder)
            sBinder = null
            sDaemonUid = -1
            sDaemonPid = -1
            sDaemonVer = null
            sDaemonInstance = null
            return true
        }
    }

    /**
     * @return `true` if a live binder to the daemon is currently held.
     *
     * Build 195 / P2: uses [IBinder.isBinderAlive] (local check, 0 IPC) instead of
     * [IBinder.pingBinder] (real Binder roundtrip ~5 ms). Correctness preserved by [sDeath], which
     * clears `sBinder` as soon as the daemon dies. Read is lock-free because `sBinder` is volatile.
     */
    @JvmStatic
    fun isConnected(): Boolean {
        val b = sBinder
        return b != null && b.isBinderAlive
    }

    /**
     * Returns the cached binder of the **PROXY** daemon ([ProxyDaemonMain], ServiceManager name
     * `byd_proxy_daemon`), or `null` when it is not currently connected.
     *
     * **This is NOT the surface daemon.** DashCast runs two uid-2000 daemons:
     *  - the PROXY daemon (this one) **DOES** things — shell commands and one-shot verbs — and
     *    enforces [ProxyDaemonContract.DESCRIPTOR];
     *  - the SURFACE daemon (`SurfaceDaemon`) **HOLDS** things — the preview mirror, the cluster
     *    slot overlay windows and their trusted VirtualDisplays, touch injection — and enforces
     *    its own DESCRIPTOR.
     *
     * Do **not** pass this binder to mirror / slot / injection APIs such as
     * `ClusterMirrorManager.startMirrorViaDaemon()`, `FissionClient.*` or `ClusterInputForwarder`:
     * writing the surface daemon's interface token onto this binder makes the receiving
     * `enforceInterface` reject the transaction, which then silently does nothing. For those, use
     * [DaemonBinderResolver.surfaceDaemonBinder].
     *
     * Triage rule: a failed *command* → proxy daemon; a black or frozen *surface* → surface daemon.
     */
    @JvmStatic
    fun getProxyDaemonBinder(): IBinder? {
        val b = sBinder
        return if (b != null && b.isBinderAlive) b else null
    }

    /**
     * v1.3.3 — Eager invalidation entry point for call-sites that detect a dead binder by catching
     * `DeadObjectException` from a `transact()` call on the daemon binder. The kernel sometimes
     * fails to deliver the binderDied() notification on DiLink 3 / Android 10 (observed silent
     * deaths on user devices in v1.3.x), leaving [isConnected] stuck on `true` while every call
     * throws. Sites must call this method as soon as they catch such an exception so that:
     *   (1) the next [isConnected] returns false immediately;
     *   (2) `ProxyKeeperService` picks up the dead state at its next heartbeat and reconnects;
     *   (3) the silent-death event is counted in metrics for diagnosis.
     *
     * Safe to call from any thread, idempotent.
     *
     * @param reason short tag included in the log line (e.g. "MirrorStart").
     */
    @JvmStatic
    fun invalidateBinder(reason: String) {
        invalidateBinderIfCurrent(sBinder, reason)
    }

    @JvmStatic
    fun invalidateBinderIfCurrent(expectedBinder: IBinder?, reason: String): Boolean {
        if (expectedBinder == null || !clearConnectionIfCurrent(expectedBinder)) return false
        ProxyMetrics.inc(sAppCtx, ProxyMetrics.K_BINDER_DEATHS_SILENT)
        AppLogger.w(TAG, "invalidateBinder($reason)" +
                " — silent death detected by caller (kernel notif missing)")
        return true
    }

    /**
     * Ensure the daemon is reachable. If a binder is already cached and live, returns immediately.
     * Otherwise: (1) registers a receiver if not done yet; (2) bootstraps a daemon via
     * [AdbLocalClient]; (3) waits up to [BROADCAST_WAIT_MS] for the daemon's connect-broadcast;
     * (4) runs the WHOAMI handshake.
     *
     * @return `true` on success.
     */
    @JvmStatic
    fun connect(ctx: Context?): Boolean {
        if (ctx == null) return false
        // Cache the application context the very first time we are called from any thread/site, so
        // attemptReconnect() can bootstrap silently from inside a typed verb (which has no Context
        // parameter). Application context is process-scoped → safe to hold statically.
        if (sAppCtx == null) {
            sAppCtx = ctx.applicationContext
        }
        val cachedBinder = sBinder
        if (cachedBinder != null && cachedBinder.isBinderAlive) {
            return sDaemonUid >= 0 || handshakeAndVerify(cachedBinder)
        }
        // Fast path: an already-live binder is reused without touching the daemon process —
        // critical to avoid the cascade of kill-and-respawn cycles that froze the head unit in
        // v1.1.6 (each respawn triggers a full ActivityThread.systemMain() in app_process).
        var ticket: SingleFlight.Ticket<Boolean>? = null
        var binderSignal: CountDownLatch? = null
        var lateBinder: IBinder? = null
        synchronized(LOCK) {
            val currentBinder = sBinder
            if (currentBinder != null && currentBinder.isBinderAlive) {
                if (sDaemonUid >= 0) return true
                // The receiver published this Binder after the lock-free snapshot above. WHOAMI
                // must run outside LOCK, just like the ordinary fast path.
                lateBinder = currentBinder
                ticket = null
            } else {
                ticket = sConnectSingleFlight.join()
            }
            if (lateBinder != null) {
                // Drop LOCK before the synchronous Binder transaction below.
            } else if (!ticket!!.isLeader) {
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
                    val now = SystemClock.elapsedRealtime()
                    val recheckMs = ProxyTransportRetryPolicy.recheckMs(
                            AdbLocalClient.adbTransportState(),
                            XPORT_RECHECK_MS,
                            XPORT_AUTH_RECHECK_MS)
                    if (now - sLastDeadXportAttemptMs < recheckMs) {
                        ticket!!.complete(false)
                        return false
                    }
                    sLastDeadXportAttemptMs = now
                }
                // Arm the latch BEFORE registering the receiver so that a broadcast arriving
                // immediately after registration (daemon already alive) finds a non-null latch and
                // can count it down rather than being silently dropped. Both operations are inside
                // LOCK so onReceive() cannot interleave, but creating the latch first is safer.
                val signal = CountDownLatch(1)
                binderSignal = signal
                sBinderLatch = signal
                try {
                    ensureReceiverRegistered(ctx)
                } catch (registrationError: Throwable) {
                    sBinderLatch = null
                    ticket!!.complete(false)
                    AppLogger.e(TAG, "proxy receiver registration failed", registrationError)
                    return false
                }
            }
        }

        val late = lateBinder
        if (late != null) return handshakeAndVerify(late)

        val leaderTicket = ticket!!
        if (!leaderTicket.isLeader) {
            return try {
                leaderTicket.await(CONNECT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (timeout: TimeoutException) {
                AppLogger.w(TAG, "timed out joining in-flight daemon connect")
                false
            }
        }

        val signal = binderSignal!!
        var result = false
        try {
            AppLogger.i(TAG, "bootstrapping daemon via AdbLocalClient")
            val bootMsg = bootstrap(ctx)
            AppLogger.d(TAG, "bootstrap result: $bootMsg")

            // v1.2.78 — Couche 4: metric instrumentation + ERR_NO_APK fast-path.
            // The bootstrap script returns one of:
            //   "REBROADCAST <pid>"  → live daemon, trigger file touched
            //   <nothing>            → cold spawn launched (app_process detached)
            //   "ERR_NO_APK"         → PM has not indexed our APK yet (post-OTA race)
            //   "ERR ..."            → ADB transport error
            // The actual success/fail will be decided by the latch below, but the
            // bootstrap-side outcome tells us WHY we are about to wait.
            val upper = bootMsg?.trim() ?: ""
            if (AdbLocalClient.isAdbTransportUnreachable()) {
                // The first failure entered connect() while transport state was still null, so
                // the entry circuit-breaker could not timestamp it. Arm the recheck window now;
                // otherwise the very next 10 s keeper heartbeat performs a second full attempt.
                sLastDeadXportAttemptMs = SystemClock.elapsedRealtime()
            }
            if (upper.startsWith("REBROADCAST")) {
                ProxyMetrics.inc(ctx, ProxyMetrics.K_REBROADCASTS)
            } else if (upper == "ERR_NO_APK" || upper.contains("ERR_NO_APK")) {
                ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_NO_APK)
                // Force the next attemptReconnect to bypass cooldown — the PM race window is
                // sub-second and a 1s+ wait wastes UX.
                synchronized(LOCK) {
                    sLastReconnectAttemptMs = 0L
                    sBackoffStep = 0
                }
            }

            // CRITICAL: await() must NOT be called while holding LOCK. The broadcast arrives on the
            // main thread, onReceive() tries to take LOCK to set sBinder, and would block until our
            // await() times out. v1.1.7 hit exactly this deadlock: the broadcast was always 0 ms
            // late because the receiver was blocked on us. CountDownLatch.await() does not release
            // monitors the way Object.wait() does, so we have to drop LOCK manually.
            //
            // v1.3.9 — REBROADCAST fast-path: when the daemon is already alive (REBROADCAST),
            // ProxyDaemonMain's trigger-file poll delivers the broadcast within one poll period.
            // That period and this budget are the same decision and now live together in
            // ProxyBootstrapPolicy, derived from each other -- do NOT restate either as a literal
            // here again; the prose version of this coupling drifted and shipped a regression.
            // Cold-spawn still uses the full 15s (the JVM boot itself takes 5-8s on DiLink SoCs).
            // A classified transport failure gets only a 2s grace in case the detached daemon
            // started just before the socket failed.
            val waitMs = ProxyBootstrapPolicy.binderWaitMs(
                    upper,
                    AdbLocalClient.isAdbTransportUnreachable(),
                    ProxyBootstrapPolicy.REBROADCAST_BUDGET_MS,
                    TRANSPORT_FAILURE_BINDER_GRACE_MS.toLong(),
                    BROADCAST_WAIT_MS.toLong())
            try {
                signal.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }

            val receivedBinder = sBinder
            if (receivedBinder != null && receivedBinder.isBinderAlive && sDaemonUid < 0) {
                handshake(receivedBinder)
            }

            synchronized(LOCK) {
                // Late-arrival recovery: the receiver may have signalled just after the latch timed
                // out — re-check rather than failing hard.
                // 1.2.31 — isBinderAlive() (local check, 0 IPC) instead of pingBinder() (Binder
                // roundtrip): the live binder cache is hooked via linkToDeath in the receiver
                // above, so isBinderAlive is strictly equivalent here and avoids one IPC while
                // holding LOCK.
                val failedBinder = sBinder
                if (failedBinder == null || !failedBinder.isBinderAlive) {
                    AppLogger.w(TAG, "no live binder after " + waitMs +
                            "ms (latch=" + (if (signal.count == 0L) "signalled" else "timed-out") + ")")
                    clearConnectionIfCurrent(failedBinder)
                    // v1.2.78 — Couche 4: distinguish timeout vs other bootstrap fail.
                    val transportState = AdbLocalClient.adbTransportState()
                    if (AdbLocalClient.XPORT_UNRESPONSIVE == transportState
                            || upper.contains("timed out")
                            || upper.startsWith("REBROADCAST") || upper.isEmpty()) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_TIMEOUT)
                    } else if (upper != "ERR_NO_APK" && !upper.contains("ERR_NO_APK")) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_FAILS_OTHER)
                    }
                    return false
                }
                result = isConnected() && sDaemonUid >= 0
                if (result) {
                    AppLogger.i(TAG, "daemon ready (uid=" + sDaemonUid +
                            " pid=" + sDaemonPid + " ver=" + sDaemonVer + ")")
                    // v1.2.78 — Couche 4: count cold spawn (REBROADCAST already counted above and
                    // we shouldn't double-count it as a cold one).
                    if (!upper.startsWith("REBROADCAST")) {
                        ProxyMetrics.inc(ctx, ProxyMetrics.K_COLD_SPAWNS)
                    }
                    // v1.2.78 — reset backoff on success so the next failure starts at step 0 (1s)
                    // instead of inheriting the previous run's state.
                    sBackoffStep = 0
                }
                return result
            }
        } finally {
            synchronized(LOCK) {
                if (sBinderLatch === signal) sBinderLatch = null
            }
            leaderTicket.complete(result)
        }
    }

    /**
     * Read the tail of the daemon's stdout/stderr capture file via legacy ADB. Useful to surface
     * the real cold-start error (class not found, SELinux, dex2oat failure, etc.) in the Diag test
     * message when [connect] has returned `false`.
     */
    @JvmStatic
    fun readDaemonLogTail(ctx: Context): String {
        val out = AtomicReference<String>()
        val latch = CountDownLatch(1)
        AdbLocalClient.executeShellWithResult(ctx, READ_LOG_CMD, object : AdbLocalClient.Callback {
            override fun onSuccess(s: String?) { out.set(s); latch.countDown() }
            override fun onError(e: String?) { out.set("<log read failed: $e>"); latch.countDown() }
        })
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return "<log read timed out>"
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return "<interrupted>"
        }
        val s = out.get()
        return if (s == null || s.isEmpty()) "<empty>" else s
    }

    /**
     * No-op. Kept for API compatibility and for tests that want to assert persistence semantics.
     *
     * The cached binder is process-scoped (static), not Activity-scoped, and the daemon is a
     * separate `app_process64` process under uid 2000. Clearing the binder reference here would lie
     * about the daemon's actual lifetime: it would force the next [connect] into a full bootstrap
     * (which kills the live daemon via the `[d]ashcast_proxy` heuristic and respawns it — changing
     * the PID) even though nothing in the daemon process changed.
     *
     * If the daemon actually dies (e.g. `kill -9`, OOM), the kernel notifies us via the
     * `DeathRecipient` hooked when the binder is received and `sBinder` is cleared automatically.
     */
    @JvmStatic
    fun disconnect() {
        AppLogger.d(TAG, "disconnect() called — no-op (binder is process-scoped, daemon outlives Activity)")
    }

    /** Round-trip latency in ms, or `-1` on error / not connected. */
    @JvmStatic
    fun ping(): Long {
        val b = sBinder
        if (b == null || !b.isBinderAlive) return -1L
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            val t0 = SystemClock.elapsedRealtime()
            b.transact(ProxyDaemonContract.TXN_PING, data, reply, 0)
            val t1 = SystemClock.elapsedRealtime()
            reply.readException()
            reply.readLong() // epoch ms — unused, kept to drain the parcel
            return t1 - t0
        } catch (e: RemoteException) {
            AppLogger.w(TAG, "ping failed: " + e.message)
            // Don't null sBinder — sDeath will do it on real death. RemoteException can also mean
            // transient backpressure, in which case the next typed verb may still succeed.
            return -1L
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** UID of the daemon process as reported by its last `WHOAMI`. */
    @JvmStatic fun getCallerUid(): Int = sDaemonUid

    /** PID of the daemon process as reported by its last `WHOAMI`. */
    @JvmStatic fun getDaemonPid(): Int = sDaemonPid

    /** Protocol version reported by the daemon, or `null` if never handshook. */
    @JvmStatic fun getProtocolVersion(): String? = sDaemonVer

    /** True when the connected daemon reports a numeric protocol at least `minimum`. */
    @JvmStatic
    fun supportsProtocol(minimum: Int): Boolean {
        val version = sDaemonVer ?: return false
        return try { version.toInt() >= minimum }
        catch (ignored: NumberFormatException) { false }
    }

    // ─── Typed verbs ──────────────────────────────────────────────────────

    /** Run a shell command on the daemon and return its combined stdout/stderr. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun runShell(cmd: String): String =
            callWithRetry("runShell") { ProxyProcessVerbs.runShell(cmd) }

    /** Run the full Phase 4 feasibility probe suite inside the daemon (pipe-separated result). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun runPhase4Probes(): String {
        val b = sBinder
        if (b == null || !b.isBinderAlive) throw ProxyException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            b.transact(ProxyDaemonContract.TXN_PROBE_PHASE4, data, reply, 0)
            reply.readException()
            return reply.readString() ?: ""
        } catch (e: RemoteException) {
            invalidateBinderIfCurrent(b, "Phase4Probes")
            throw ProxyException("transact: " + e.message, e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Phase 4a typed verb — direct `IWindowManager.setOverscan` via the daemon's cached binder. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun setOverscan(displayId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        callWithRetry("setOverscan") {
            ProxyDisplayVerbs.setOverscan(displayId, left, top, right, bottom); null
        }
    }

    /** Phase 4b typed verb — pure-Java `/proc/<pid>/cmdline` scan inside the daemon. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun getPidsByPackage(packageName: String?): String? {
        val pkg = packageName ?: ""
        return callWithRetry("getPidsByPackage") { ProxyProcessVerbs.getPidsByPackage(pkg) }
    }

    /** Phase 4c typed verb — direct `AutoContainer.transact(2, …)` in the daemon. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun autoContainerSendInfo(type: Int, info: Int, str: String?) {
        val safeStr = str ?: ""
        callWithRetry("autoContainerSendInfo") {
            ProxyProcessVerbs.autoContainerSendInfo(type, info, safeStr); null
        }
    }

    /** AutoContainer sendInfo preserving the OEM/native integer result code. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun autoContainerSendInfoResult(type: Int, info: Int, str: String?): Int {
        if (!supportsProtocol(20)) {
            throw ProxyException("AutoContainer result codes unsupported by daemon")
        }
        val safeStr = str ?: ""
        return callWithRetry("autoContainerSendInfoResult") {
            ProxyProcessVerbs.autoContainerSendInfoResult(type, info, safeStr)
        }
    }

    /**
     * Sends AutoContainer info after transport recovery, preserving a native result when the
     * connected daemon supports it. Returns `null` only for a legacy daemon whose wire contract
     * predates result codes.
     */
    @JvmStatic
    @Throws(ProxyException::class)
    fun autoContainerSendInfoResultCompatible(type: Int, info: Int, str: String?): Int? {
        val safeStr = str ?: ""
        return callWithRetry("autoContainerSendInfoResultCompatible") {
            if (supportsProtocol(20)) {
                ProxyProcessVerbs.autoContainerSendInfoResult(type, info, safeStr)
            } else {
                ProxyProcessVerbs.autoContainerSendInfo(type, info, safeStr)
                null
            }
        }
    }

    /** Typed verb for `AutoContainer.sendInfo2(type, data)` (AIDL transaction 3). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun autoContainerSendInfo2(type: Int, data: ByteArray?) {
        callWithRetry("autoContainerSendInfo2") {
            ProxyProcessVerbs.autoContainerSendInfo2(type, data); null
        }
    }

    /** Phase 4d typed verb — direct `IActivityManager.forceStopPackage` in the daemon. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun forceStopPackage(packageName: String?, userId: Int) {
        callWithRetry("forceStopPackage") {
            ProxyProcessVerbs.forceStopPackage(packageName, userId); null
        }
    }

    /**
     * Phase 5a typed verb — ask the daemon (uid 2000) to create a VirtualDisplay backed by the
     * provided `Surface` and return its display id. Callers MUST eventually invoke
     * [releaseVirtualDisplay].
     */
    @JvmStatic
    @Throws(ProxyException::class)
    fun createVirtualDisplay(name: String?, width: Int, height: Int, densityDpi: Int,
                             surface: Surface?, flags: Int): Int {
        val b = sBinder
        if (b == null || !b.isBinderAlive) throw ProxyException("not connected")
        if (surface == null || !surface.isValid) throw ProxyException("surface null or invalid")
        try {
            return ProxyDisplayVerbs.createVirtualDisplay(
                    b, name, width, height, densityDpi, flags, surface)
        } catch (e: RemoteException) {
            clearConnectionIfCurrent(b)
            throw ProxyException("transact: " + e.message, e)
        }
    }

    /** Release a VirtualDisplay previously returned by [createVirtualDisplay]. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun releaseVirtualDisplay(displayId: Int) {
        callWithRetry("releaseVirtualDisplay") {
            ProxyDisplayVerbs.releaseVirtualDisplay(displayId); null
        }
    }

    /** OpenBYD 2.0 launchAndForce sequence — run inside the daemon (shell uid). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun launchAndForce(pkg: String?, activityCls: String?,
                       displayId: Int, width: Int, height: Int): String? {
        if (pkg == null || pkg.isEmpty()) throw ProxyException("pkg required")
        return callWithRetry("launchAndForce") {
            ProxyFissionVerbs.launchAndForce(pkg, activityCls, displayId, width, height)
        }
    }

    /** Best-effort teardown guard; never reconnects or blocks teardown on an old daemon. */
    @JvmStatic
    fun cancelFissionWatchdog(packageName: String?): Boolean {
        if (packageName != null && packageName.isEmpty()) return false
        if (!isConnected() || !supportsProtocol(21)) {
            return false
        }
        return try {
            ProxyFissionVerbs.cancelFissionWatchdog(packageName)
        } catch (error: Throwable) {
            AppLogger.w(TAG, "cancelFissionWatchdog failed for $packageName: " + error.message)
            false
        }
    }

    @JvmStatic
    fun cancelAllFissionWatchdogs(): Boolean = cancelFissionWatchdog(null)

    /** Phase 6 — Move an existing task to `displayId` and resize it to the given rect. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun moveAndResize(pkg: String?, displayId: Int,
                      left: Int, top: Int, right: Int, bottom: Int): String? {
        if (pkg == null || pkg.isEmpty()) throw ProxyException("pkg required")
        return callWithRetry("moveAndResize") {
            ProxyFissionVerbs.moveAndResize(pkg, displayId, left, top, right, bottom)
        }
    }

    /** Phase 6b — Destroy every non-fullscreen, non-home stack on `displayId`. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun cleanFissionStacks(displayId: Int): String? =
            callWithRetry("cleanFissionStacks") { ProxyFissionVerbs.cleanFissionStacks(displayId) }

    /** Phase 7 typed verb — find the task ID hosting `packageName`. Returns -1 when absent. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun findTaskIdForPackage(packageName: String?): Int {
        val pkg = packageName ?: ""
        return callWithRetry("findTaskIdForPackage") { ProxyProcessVerbs.findTaskIdForPackage(pkg) }
    }

    /** Locate a package task and the display that currently owns it. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun findTaskLocationForPackage(packageName: String?): TaskLocation {
        val pkg = packageName ?: ""
        return callWithRetry("findTaskLocationForPackage") {
            ProxyProcessVerbs.findTaskLocationForPackage(pkg)
        }
    }

    /** Phase 7 typed verb — remove a task from the ActivityTaskManager recents stack. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun removeTask(taskId: Int) {
        callWithRetry("removeTask") { ProxyProcessVerbs.removeTask(taskId); null }
    }

    // ─── CAN bus write verbs (Phase CAN-1, v1.4.7-beta) ───────────────────

    /** Set the navigation status on the instrument cluster HUD. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canNaviStatus(status: Int): Int =
            callWithRetry("canNaviStatus") { ProxyCanVerbs.canNaviStatus(status) }

    /** Write an integer value to a CAN instrument feature ID. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canInstrumentInt(featureId: Int, value: Int): Int =
            callWithRetry("canInstrumentInt") { ProxyCanVerbs.canInstrumentInt(featureId, value) }

    /** Write a byte buffer to a CAN instrument feature ID (e.g. street name bytes). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canInstrumentBytes(featureId: Int, bytes: ByteArray?): Int {
        val payload = bytes ?: ByteArray(0)
        return callWithRetry("canInstrumentBytes") {
            ProxyCanVerbs.canInstrumentBytes(featureId, payload)
        }
    }

    /** Write an integer value to a CAN *setting* feature ID via `BYDAutoSettingDevice`. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canSettingInt(featureId: Int, value: Int): Int =
            callWithRetry("canSettingInt") { ProxyCanVerbs.canSettingInt(featureId, value) }

    /** Executes an ordered CAN write group with truthful applied-count semantics (protocol v24+). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canBatch(operations: List<CanBatchOperation>): Int {
        if (!supportsProtocol(24)) throw ProxyException("truthful CAN batch unsupported by daemon")
        val b = sBinder
        if (b == null || !b.isBinderAlive) throw ProxyException("not connected")
        try {
            // Do not use callWithRetry here: a RemoteException can arrive after the daemon applied
            // a prefix of the group. Replaying the whole batch would violate exactly-once grouping.
            return ProxyCanVerbs.canBatch(b, operations)
        } catch (transportError: RemoteException) {
            invalidateBinderIfCurrent(b, "canBatch")
            throw ProxyException("canBatch transact: " + transportError.message, transportError)
        }
    }

    /** Read an integer from a CAN *instrument* feature (privileged daemon context). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canInstrumentGet(featureId: Int): Int =
            callWithRetry("canInstrumentGet") { ProxyCanVerbs.canInstrumentGet(featureId) }

    /** Read an integer from a CAN *setting* feature (privileged daemon context). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canSettingGet(featureId: Int): Int =
            callWithRetry("canSettingGet") { ProxyCanVerbs.canSettingGet(featureId) }

    /** Write a DOUBLE value to a CAN *setting* feature (HUD angle). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canSettingDouble(featureId: Int, value: Double): Int =
            callWithRetry("canSettingDouble") { ProxyCanVerbs.canSettingDouble(featureId, value) }

    /** Read up to `maxLen` bytes of `path` at `offset` from inside the daemon (uid 2000). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun readFileChunk(path: String, offset: Long, maxLen: Int): ByteArray =
            callWithRetry("readFileChunk") { ProxyFileVerbs.readFileChunk(path, offset, maxLen) }

    /** Register a BYD setting feedback listener inside the daemon (push-only feedback). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canListenStart(): String? =
            callWithRetry("canListenStart") { ProxyCanVerbs.canListenStart() }

    /** Drain (return + clear) the push events captured by the daemon listener. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canListenDrain(): String? =
            callWithRetry("canListenDrain") { ProxyCanVerbs.canListenDrain() }

    /** AAOS-only: probe the automotive display proxy HAL from the daemon (uid 2000). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun aaosHalProbe(): String? =
            callWithRetry("aaosHalProbe") { ProxyCanVerbs.aaosHalProbe() }

    /** Read-only probe of the native `FissionHostSvc` display registry (DL3 only). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun fissionGetAutoCarDisplay(): String? =
            callWithRetry("fissionGetAutoCarDisplay") {
                ProxyNativeServiceVerbs.fissionGetAutoCarDisplay()
            }

    /** Arms the daemon's `AutoContainer.registerCallback` listener (AIDL transaction 4). */
    @JvmStatic
    @Throws(ProxyException::class)
    fun autoContainerRegisterCallback(): Int =
            callWithRetry("autoContainerRegisterCallback") {
                ProxyNativeServiceVerbs.autoContainerRegisterCallback()
            }

    /** Arms a ~90s-capped background sampler of the `FissionHostSvc` registry. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun projectionTraceStart() {
        callWithRetry("projectionTraceStart") {
            ProxyNativeServiceVerbs.projectionTraceStart(); null
        }
    }

    /** Stops the sampler armed by [projectionTraceStart] and returns every change recorded. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun projectionTraceDrain(): String? =
            callWithRetry("projectionTraceDrain") {
                ProxyNativeServiceVerbs.projectionTraceDrain()
            }

    /** Clear the push-feedback log + persistent last-known map. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canListenClear() {
        callWithRetry("canListenClear") { ProxyCanVerbs.canListenClear(); null }
    }

    /** Append a timestamped user ground-truth marker to the log. */
    @JvmStatic
    @Throws(ProxyException::class)
    fun canListenMark(label: String?) {
        callWithRetry("canListenMark") { ProxyCanVerbs.canListenMark(label); null }
    }

    /**
     * Force-kill the running daemon (if any) so the next [connect] bootstraps a fresh one. Useful
     * after installing an APK that ships new typed verbs: the old daemon process keeps the previous
     * APK's classpath loaded and would reject newer TXN codes with "Unknown transaction".
     */
    @JvmStatic
    fun killAndRestartDaemon(ctx: Context): Boolean {
        return try {
            val b = sBinder
            if (b != null && b.isBinderAlive) {
                try {
                    // Use the existing EXEC transport to kill ourselves — simplest and avoids new
                    // shell perms on the caller side.
                    runShell("ps -A 2>/dev/null | grep '[d]ashcast_proxy' " +
                            "| awk '{print \$2}' | xargs -r kill -9")
                } catch (ignore: Throwable) { /* daemon may already be dead */ }
            }
            // M10: acquire LOCK + unlinkToDeath before clearing state so we don't race with the
            // broadcast receiver or sDeath.binderDied().
            synchronized(LOCK) {
                val dead = sBinder
                if (dead != null) {
                    unlinkDeathLocked(dead)
                }
                sBinder = null
                sDaemonUid = -1
                sDaemonPid = -1
                sDaemonVer = null
                sDaemonInstance = null
            }
            // Give AMS / the kernel a moment to reap the old process before the receiver waits for
            // the next broadcast.
            try { Thread.sleep(400L) } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            connect(ctx)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "killAndRestartDaemon failed: " + t.message)
            false
        }
    }

    /** Snapshot of one daemon generation, taken on the dispatch thread before Binder entry. */
    class DaemonIdentity internal constructor(
        @JvmField internal val binder: IBinder,
        @JvmField internal val pid: Int,
        @JvmField internal val instance: String
    )

    /** Snapshot taken on the actual typed-dispatch thread immediately before Binder entry. */
    @JvmStatic
    fun captureDaemonIdentity(): DaemonIdentity? {
        synchronized(LOCK) {
            val binder = sBinder
            val instance = sDaemonInstance
            if (binder == null || sDaemonPid <= 0 || instance == null
                    || !instance.matches(Regex("[0-9a-fA-F]{32}"))) return null
            return DaemonIdentity(binder, sDaemonPid, instance)
        }
    }

    /**
     * Terminates the authenticated proxy PID through direct local ADB after a typed Binder call
     * exceeded its deadline. This method never uses the possibly wedged Binder itself.
     *
     * @return true only when the old daemon is killed or already absent, making it impossible for
     *         the timed-out physical command to execute after a newer queued command.
     */
    @JvmStatic
    fun terminateHungDaemonViaAdb(ctx: Context, expected: DaemonIdentity?): Boolean {
        if (expected == null) return false
        val command = "PID=" + expected.pid +
                "; EXPECT=" + expected.instance +
                "; case \"\$PID\" in ''|*[!0-9]*) echo NO_PID; exit 3;; esac" +
                "; CURRENT=\$(cat " + DAEMON_INSTANCE + " 2>/dev/null)" +
                "; if [ \"\$CURRENT\" != \"\$EXPECT\" ]; then echo INSTANCE_CHANGED; exit 5; fi" +
                "; LINE=\$(ps -A 2>/dev/null | awk -v p=\"\$PID\" '\$2 == p {print; exit}')" +
                "; if echo \"\$LINE\" | grep -q '[d]ashcast_proxy'; then" +
                " kill -9 \"\$PID\" 2>/dev/null && echo KILLED" +
                "; elif [ -z \"\$LINE\" ]; then echo ABSENT" +
                "; else echo REFUSED; exit 4; fi"
        return try {
            val result = AdbLocalClient.executeShellWithResultBlocking(ctx, command, 15_000)
            if (!result.contains("KILLED") && !result.contains("ABSENT")) {
                if (!expected.binder.isBinderAlive) {
                    AppLogger.i(TAG, "hung daemon already superseded: $result")
                    return true
                }
                AppLogger.e(TAG, "hung daemon recovery refused: $result")
                return false
            }
            synchronized(LOCK) {
                if (sBinder === expected.binder && sDaemonPid == expected.pid
                        && expected.instance == sDaemonInstance) {
                    unlinkDeathLocked(expected.binder)
                    sBinder = null
                    sDaemonUid = -1
                    sDaemonPid = -1
                    sDaemonVer = null
                    sDaemonInstance = null
                }
            }
            AppLogger.e(TAG, "hung proxy daemon terminated via direct ADB: $result")
            true
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            AppLogger.e(TAG, "hung daemon recovery failed: " + error.message)
            false
        }
    }

    // ─── Auto-recovery helpers (v1.2.58-beta, Phase A step 1) ─────────────

    /**
     * Functional handle for a single binder transaction body. Implementations must read `sBinder`
     * fresh on every [run] call so that a post-reconnect retry sees the new binder published by
     * [connect]. Both checked exception types are declared because verb bodies need to throw
     * [ProxyException] for logical errors (null Surface, bad argument) and [RemoteException] for
     * transport failures — only the latter triggers the retry.
     */
    private fun interface BinderOp<T> {
        @Throws(RemoteException::class, ProxyException::class)
        fun run(): T
    }

    private val sDispatchBinder = ThreadLocal<IBinder?>()

    /** Verb accessor: one callWithRetry attempt always uses one Binder. */
    @JvmStatic
    internal fun dispatchBinder(): IBinder? = sDispatchBinder.get() ?: sBinder

    @Throws(RemoteException::class, ProxyException::class)
    private fun <T> runPinned(binder: IBinder?, op: BinderOp<T>): T {
        if (binder == null || !binder.isBinderAlive) throw ProxyException("not connected")
        val previous = sDispatchBinder.get()
        sDispatchBinder.set(binder)
        try {
            return op.run()
        } finally {
            if (previous == null) sDispatchBinder.remove() else sDispatchBinder.set(previous)
        }
    }

    /**
     * Best-effort daemon revive, rate-limited by [RECONNECT_COOLDOWN_MS].
     *
     * Called from [callWithRetry] when a verb either finds a dead binder at entry or trips a
     * [RemoteException] mid-transact. Skips the attempt if the previous one ran less than
     * [RECONNECT_COOLDOWN_MS] ago to avoid bootstrap-storms during input forwarding (~60 Hz) or
     * resize SeekBar (~30 Hz).
     *
     * @return `true` if a reconnect was attempted AND a live binder is now held; `false` if the
     *         attempt was skipped (cooldown) or failed.
     */
    private fun attemptReconnect(): Boolean {
        val ctx = sAppCtx
        if (ctx == null) {
            // No call site has ever connected — caller bug, surface as no-op.
            return false
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(LOCK) {
            // v1.2.78 — Couche 4: adaptive cooldown (1s→2s→4s→8s→10s).
            val step = minOf(sBackoffStep, BACKOFF_MS.size - 1)
            val cooldown = BACKOFF_MS[step]
            if (now - sLastReconnectAttemptMs < cooldown) {
                AppLogger.d(TAG, "attemptReconnect skipped (cooldown, " +
                        (now - sLastReconnectAttemptMs) + "ms < " + cooldown +
                        "ms, step=" + step + ")")
                return false
            }
            sLastReconnectAttemptMs = now
        }
        AppLogger.i(TAG, "attemptReconnect: bootstrapping daemon (cooldown gate passed, step=" +
                sBackoffStep + ")")
        val ok = connect(ctx)
        // v1.2.78 — Couche 4: reset/bump backoff based on outcome. connect() already does the reset
        // on success, but we set it here too so attemptReconnect remains internally consistent if
        // connect() returns success via a fast path that didn't go through the bump site.
        if (ok) {
            sBackoffStep = 0
        } else {
            sBackoffStep = minOf(sBackoffStep + 1, BACKOFF_MS.size - 1)
        }
        return ok
    }

    // ─── Main-thread ANR guard for the reconnect bootstrap ───────────────────
    // connect() blocks the caller for up to BOOTSTRAP_TIMEOUT_MS + BROADCAST_WAIT_MS (~23s)
    // bootstrapping a cold uid-2000 daemon. That MUST never run on the UI looper (frozen instrument
    // cluster = ANR). This single-thread executor runs the blocking bootstrap off-thread when a
    // verb is (defensively) called on main.
    private val sReconnectExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                val t = Thread(r, "proxy-reconnect")
                t.isDaemon = true
                t
            }
    private val sAsyncReconnectPending = AtomicBoolean(false)

    // Threads that own their own transport fallback (e.g. the ShellGateway serial executor, which
    // routes to AdbLocalClient on any failure) can opt out of the blocking bootstrap: a binder that
    // dies mid-transact would otherwise stall that single worker ~23s before the fallback runs.
    // When set, callWithRetry's reconnect is kicked async (daemon still revives) and the verb fails
    // fast instead.
    private val sNonBlockingReconnect: ThreadLocal<Boolean> =
            ThreadLocal.withInitial { false }

    /**
     * Opt the CURRENT thread out of the blocking daemon bootstrap inside [callWithRetry]. Intended
     * for dedicated worker threads that have their own legacy fallback and must not be stalled by a
     * cold-daemon reconnect.
     */
    @JvmStatic
    fun setNonBlockingReconnect(enabled: Boolean) {
        sNonBlockingReconnect.set(enabled)
    }

    /**
     * Reconnect policy that never blocks the main thread. On a background thread this runs the
     * (cooldown-gated) blocking bootstrap synchronously — unchanged behaviour. On the main thread
     * it kicks the bootstrap onto the dedicated "proxy-reconnect" thread (coalesced — at most one
     * in flight) and returns `false` immediately, so a cold daemon can never ANR the UI: the
     * caller's `op.run()` throws "not connected" (handled by existing AdbLocalClient fallbacks) and
     * the daemon still revives in the background for the next call.
     *
     * @return `true` only when a synchronous reconnect ran AND a live binder is now held; `false`
     *         on the main thread (kicked async) or on failure.
     */
    private fun reconnectUnlessMainThread(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()
                || sNonBlockingReconnect.get() == true) {
            if (sAsyncReconnectPending.compareAndSet(false, true)) {
                try {
                    sReconnectExecutor.execute {
                        try { attemptReconnect() }
                        finally { sAsyncReconnectPending.set(false) }
                    }
                } catch (ree: RejectedExecutionException) {
                    sAsyncReconnectPending.set(false)
                }
            }
            return false
        }
        return attemptReconnect()
    }

    /**
     * Wrap a typed-verb body in single-shot auto-recovery: pre-check live binder (best-effort
     * silent reconnect if dead), run the body, retry once on [RemoteException] after a rate-limited
     * reconnect. Logical [ProxyException] from the body (e.g. "not connected", "null Surface")
     * propagate unchanged — they are not transport errors and a retry would not change the outcome.
     *
     * The `sBinder = null` after a failed transact is preserved (eager publish so the very next
     * caller sees the dead state without waiting for [sDeath] to fire).
     *
     * @param tag short identifier used in failure log lines (e.g. `"setOverscan"`)
     * @param op  the verb body — must re-read `sBinder` on each call
     */
    @Throws(ProxyException::class)
    private fun <T> callWithRetry(tag: String, op: BinderOp<T>): T {
        // Pre-flight: if no live binder, opportunistically reconnect once (cooldown-gated) before
        // the first attempt. If reconnect fails, the body will throw "not connected" on its own —
        // sites that still want the legacy fallback (e.g. AdbLocalClient.sendInfo) catch and route.
        val pre = sBinder
        if (pre == null || !pre.isBinderAlive) {
            reconnectUnlessMainThread()
        }
        val firstAttempt = sBinder
        try {
            return runPinned(firstAttempt, op)
        } catch (e: RemoteException) {
            invalidateBinderIfCurrent(firstAttempt, tag)
            AppLogger.w(TAG, "$tag RemoteException: " + e.message + " — attempting reconnect")
            if (!reconnectUnlessMainThread()) {
                throw ProxyException("$tag: " + e.message, e)
            }
            val retryAttempt = sBinder
            try {
                return runPinned(retryAttempt, op)
            } catch (e2: RemoteException) {
                invalidateBinderIfCurrent(retryAttempt, "$tag retry")
                throw ProxyException("$tag (after reconnect): " + e2.message, e2)
            }
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────

    /**
     * Register the dynamic [BroadcastReceiver] once per app process. Uses the application context
     * so the lifetime is tied to the process, not to any short-lived Activity that happens to call
     * us first. Note: the lack of an unregisterReceiver call is process-scoped intentional (P2-1)
     * as the dynamic receiver is registered once on the application context and persists for the
     * entire lifetime of the process. Gated by the sReceiver null check.
     */
    @Suppress("DEPRECATION")
    private fun ensureReceiverRegistered(ctx: Context) {
        if (sReceiver != null) return
        val appCtx = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (ProxyDaemonContract.ACTION_PROXY_CONNECTED != intent.action) return
                val bp: BinderParcelable? =
                        intent.getParcelableExtra(ProxyDaemonContract.EXTRA_BINDER)
                val incoming = bp?.binder
                if (incoming == null) {
                    AppLogger.w(TAG, "PROXY_CONNECTED received without binder extra")
                    return
                }
                // Discard stale broadcasts whose binder is already dead — they happen when a
                // previous bootstrap killed an in-flight daemon and AMS only dispatched its
                // broadcast after the kill. Storing the dead ref would mask the LIVE binder we
                // either already have or are still waiting for (root cause of A5/A6 ✗ in v1.1.6).
                // P3-1: use isBinderAlive() (local cache check) instead of pingBinder() (Binder
                // round-trip) — coherent with the sBinder check below. A dead binder will still be
                // rejected because sDeath would have invalidated the cache.
                if (!incoming.isBinderAlive) {
                    AppLogger.d(TAG, "ignoring stale PROXY_CONNECTED (binder already dead)")
                    return
                }
                // Authenticate the daemon: adopt the broadcast binder only if it matches the one
                // the real daemon registered in the global ServiceManager (only uid-2000/system can
                // addService — SELinux blocks apps). A spoofed broadcast carries a fake binder that
                // won't match. If there is NO entry (older daemon / addService failed on this ROM),
                // fall back to the broadcast binder (prior behaviour) so this can never break the
                // daemon path — including across the update where a pre-S2 daemon is still running.
                val registered = lookupRegisteredProxyBinder()
                if (registered != null && registered !== incoming) {
                    AppLogger.w(TAG,
                            "PROXY_CONNECTED binder ≠ ServiceManager entry — ignoring (spoofed?)")
                    return
                }
                synchronized(LOCK) {
                    // If we already hold a live binder, prefer it (avoid spurious handshake/state
                    // churn from late duplicate broadcasts). Local check via isBinderAlive() (P2) —
                    // sDeath would have cleared sBinder if the cached one had actually died.
                    val current = sBinder
                    if (current != null && current.isBinderAlive && current !== incoming) {
                        AppLogger.d(TAG,
                                "ignoring duplicate PROXY_CONNECTED (already have a live binder)")
                        sBinderLatch?.countDown()
                        return
                    }
                    // Unhook the previous death recipient (if any) before swapping.
                    if (current != null && current !== incoming) {
                        unlinkDeathLocked(current)
                    }
                    // Invalidate identity BEFORE publishing the replacement Binder. Lock-free
                    // readers must never observe a new Binder paired with the previous daemon's UID.
                    sDaemonUid = -1
                    sDaemonPid = -1
                    sDaemonVer = null
                    sDaemonInstance = null
                    sBinder = incoming
                    // Hook the new binder so a future death immediately clears our cached reference
                    // (P2). Best-effort: if linkToDeath fails (binder already dead between
                    // isBinderAlive above and here — vanishingly unlikely), isBinderAlive() on the
                    // next call still gives the right answer.
                    try { linkDeathLocked(incoming) }
                    catch (re: RemoteException) {
                        AppLogger.w(TAG, "linkToDeath failed: " + re.message)
                    }
                    AppLogger.i(TAG, "live binder received from daemon")
                    sBinderLatch?.countDown()
                }
            }
        }
        sReceiver = receiver
        val filter = IntentFilter(ProxyDaemonContract.ACTION_PROXY_CONNECTED)
        // The daemon runs as uid 2000 (com.android.shell), which holds DUMP. Requiring that sender
        // permission preserves the broadcast fallback on ROMs where addService fails, without
        // allowing an ordinary co-installed app to supply a fake Binder.
        DaemonBroadcastRegistrar.register(appCtx, receiver, filter)
        AppLogger.d(TAG, "dynamic receiver registered for " +
                ProxyDaemonContract.ACTION_PROXY_CONNECTED)
    }

    /** Reflective `ServiceManager.getService(ProxyDaemonMain.SERVICE_NAME)` — the trusted anchor
     *  for authenticating a PROXY_CONNECTED broadcast binder. null if absent or on error. */
    private fun lookupRegisteredProxyBinder(): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, ProxyDaemonMain.SERVICE_NAME) as IBinder?
        } catch (t: Throwable) {
            null
        }
    }

    /** Runs WHOAMI and confirms its published identity still belongs to the expected Binder. */
    private fun handshakeAndVerify(expectedBinder: IBinder): Boolean {
        if (!handshake(expectedBinder)) return false
        synchronized(LOCK) {
            return sBinder === expectedBinder &&
                    expectedBinder.isBinderAlive &&
                    sDaemonUid >= 0
        }
    }

    /** Issue WHOAMI without holding [LOCK]; publish only if the Binder is still current. */
    private fun handshake(expectedBinder: IBinder?): Boolean {
        if (expectedBinder == null || !expectedBinder.isBinderAlive) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ProxyDaemonContract.DESCRIPTOR)
            if (!expectedBinder.transact(ProxyDaemonContract.TXN_WHOAMI, data, reply, 0)) return false
            reply.readException()
            val daemonUid = reply.readInt()
            val daemonPid = reply.readInt()
            val daemonVer = reply.readString()
            val daemonInstance = if (reply.dataAvail() > 0) reply.readString() else null
            if (daemonUid < 0 || daemonPid <= 0 || daemonVer == null || daemonVer.isEmpty()) {
                throw IllegalStateException("invalid WHOAMI response")
            }
            try {
                if (daemonVer.toInt() >= 25
                        && (daemonInstance == null
                                || !daemonInstance.matches(Regex("[0-9a-fA-F]{32}")))) {
                    throw IllegalStateException("invalid WHOAMI instance")
                }
            } catch (badVersion: NumberFormatException) {
                throw IllegalStateException("invalid WHOAMI protocol")
            }
            synchronized(LOCK) {
                if (sBinder !== expectedBinder || !expectedBinder.isBinderAlive) return false
                sDaemonUid = daemonUid
                sDaemonPid = daemonPid
                sDaemonVer = daemonVer
                sDaemonInstance = daemonInstance
                return true
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "handshake failed (" + e.javaClass.simpleName + ")")
            clearConnectionIfCurrent(expectedBinder)
            return false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Issue the bootstrap script via legacy ADB and wait (briefly) for it to finish. */
    private fun bootstrap(ctx: Context): String? {
        val out = AtomicReference<String>()
        val latch = CountDownLatch(1)
        AdbLocalClient.executeShellWithResult(ctx, BOOTSTRAP_CMD, object : AdbLocalClient.Callback {
            override fun onSuccess(report: String?) { out.set(report); latch.countDown() }
            override fun onError(error: String?) { out.set("ERR $error"); latch.countDown() }
        }, AdbLocalClient.BOOTSTRAP_IDLE_TIMEOUT_MS)
        try {
            if (!latch.await(BOOTSTRAP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) {
                return "ERR bootstrap timed out"
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return "ERR interrupted"
        }
        return out.get()
    }

    /** Thrown when the proxy daemon path fails — caller should fall back to legacy. */
    open class ProxyException : Exception {
        constructor(msg: String?) : super(msg)
        constructor(msg: String?, cause: Throwable?) : super(msg, cause)
    }
}
