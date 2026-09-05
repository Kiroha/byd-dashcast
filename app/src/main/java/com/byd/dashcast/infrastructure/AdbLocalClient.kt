package com.byd.dashcast.infrastructure

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.widget.Toast

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.R
import com.byd.dashcast.cluster.EvictionOutcomePolicy
import com.byd.dashcast.cluster.EvictionTaskSetPolicy
import com.byd.dashcast.cluster.display.ClusterGeometryPolicy
import com.byd.dashcast.cluster.display.ClusterManager
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.task.LegacyTaskLocationParser
import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.util.AppLogger

import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.Dadb

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * AdbLocalClient — connects to the local ADB daemon (localhost:5555) from inside
 * the tablet, using the dadb library (same approach as Overdrive).
 *
 * Flow:
 *  1. Generates (or reloads) an RSA ADB key pair stored in internal files.
 *  2. Dadb.create() initiates the connection → adbd sends a challenge → dadb replies with
 *     the RSA signature → if the key is unknown, the system shows the popup
 *     "Allow USB debugging?" on the tablet screen.
 *  3. Three escalation passes:
 *     [1] setprop persist.sys.acc.whitelist — BYD DiLink native mechanism
 *     [2] abb_exec package grant — via direct Binder (Android 9+)
 *     [3] BYD service enumeration via service list (proxy preparation)
 *
 * The key pair is persisted → the popup appears only once (or after
 * manual revocation in the vehicle's developer settings).
 */
object AdbLocalClient {
    // Capped at 4 threads to avoid OutOfMemoryError or socket exhaustion
    // if the user hammers the UI triggering slow ADB commands.
    // Named daemon threads → easier debugging and won't keep the process alive.
    private val sExecutor: ExecutorService = Executors.newFixedThreadPool(4, object : ThreadFactory {
        private val seq = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "adb-local-" + seq.getAndIncrement())
            t.isDaemon = true
            return t
        }
    })

    /** At most main+split are blindly evicted; dedicated workers prevent shared-pool starvation. */
    private val sBlindEvictionExecutor: ExecutorService =
        Executors.newFixedThreadPool(2, object : ThreadFactory {
            private val seq = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "adb-blind-evict-" + seq.getAndIncrement())
                t.isDaemon = true
                return t
            }
        })

    private const val TAG = "AdbLocalClient"

    /** ADB TCP port — same for Android 7–10 in developer mode */
    private const val ADB_PORT = 5555

    /** Fast TCP-reachability probe budget before the ADB handshake (v1.6.102). */
    private const val CONNECT_PROBE_MS = 1500
    /** Default idle-read timeout for normal shell operations. Long-running commands remain valid
     *  while they keep producing packets; only a completely silent/wedged transport is aborted. */
    const val SHELL_IDLE_TIMEOUT_MS = 60_000
    /** Proven healthy transports retain the legacy caller's 8 s bootstrap budget. */
    const val BOOTSTRAP_IDLE_TIMEOUT_MS = 8_000
    /** First real command also performs the lazy ADB handshake and may wait for user approval. */
    const val FIRST_OPERATION_IDLE_TIMEOUT_MS = 15_000
    /** A newly-generated key always triggers the system approval UI; give the driver more time. */
    const val NEW_KEY_AUTH_IDLE_TIMEOUT_MS = 30_000
    /** Small read-only probes already have 8 s caller-side latches. */
    const val PROBE_IDLE_TIMEOUT_MS = 7_000
    /** Independent echo used to distinguish one stuck shell stream from a dead adbd. */
    private const val TRANSPORT_CONFIRM_IDLE_TIMEOUT_MS = 3_000
    private const val TRANSPORT_CONFIRM_MARKER = "__DASHCAST_ADB_HEALTHY__"
    /** The all-in-one report dump can legitimately pause between expensive dumpsys sections. */
    const val REPORT_IDLE_TIMEOUT_MS = 120_000

    // ──────────────────────────────────────────────────────────────────────────
    // AutoContainer service name — resolved by PROBING which casing is actually
    // registered in ServiceManager, not by a fixed DL3/DL5 rule.
    //   DiLink 3.0 / 4.0                    → "AutoContainer" (PascalCase)
    //   literal DiLink5.0 (API 32)          → "auto_container" (snake_case)  [D11/D12 PASS 22/05]
    //   DiLink50F_LC / 5.1 (1for2, API 33)  → "AutoContainer" (PascalCase)   [proven: bugreport
    //     20260702 D50F_LC — service list has "AutoContainer" (PascalCase) + AutoContainerNative,
    //     while `service call auto_container` returned "does not exist"]
    // So "DL5 ⇒ snake_case" is WRONG for the 50F_LC/5.1 variants. Matches the OEM's own rule
    // (AmapService: snake_case only when ro.product.name == "DiLink5.0", else PascalCase).
    //
    // Resolution order: cached result → in-proc ServiceManager probe (a positive getService is
    // trusted; a null is treated as "not visible to this uid", NOT "absent") → DL5/DL3 heuristic
    // as an uncached default. The activation path additionally self-corrects via
    // [noteAutoContainerMissing] if a `service call` returns "does not exist". Never throws.
    // ──────────────────────────────────────────────────────────────────────────
    private const val SVC_PASCAL = "AutoContainer"
    private const val SVC_SNAKE = "auto_container"
    /** Resolved-and-verified service name, cached process-wide (the registration never changes). */
    @Volatile private var sCachedSvcName: String? = null

    fun autoContainerSvcName(ctx: Context?): String {
        val cached = sCachedSvcName
        if (cached != null) return cached
        val dl5 = isDiLink5Safe(ctx)
        val pascalReg = serviceRegistered(SVC_PASCAL) // trust only a positive
        val snakeReg = serviceRegistered(SVC_SNAKE)
        val resolved: String = if (pascalReg && !snakeReg) SVC_PASCAL
        else if (snakeReg && !pascalReg) SVC_SNAKE
        else if (pascalReg) (if (dl5) SVC_SNAKE else SVC_PASCAL) // both visible → heuristic
        else {
            // Neither positively visible (probe blocked for this uid) — return the heuristic
            // default WITHOUT caching, so the activation fallback can still correct it.
            return if (dl5) SVC_SNAKE else SVC_PASCAL
        }
        sCachedSvcName = resolved
        AppLogger.i(TAG, "AutoContainer service resolved to '$resolved' (probe)")
        return resolved
    }

    /** `true` only if a binder is positively registered under `name` in ServiceManager.
     *  A `null` handle is reported as `false` (may be registered but not visible to an
     *  untrusted uid) — callers must not conclude "absent" from a single false; see the probe logic. */
    private fun serviceRegistered(name: String): Boolean {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val b = sm.getMethod("getService", String::class.java).invoke(null, name)
            b != null
        } catch (ignore: Throwable) {
            false
        }
    }

    /** Self-correction: when a `service call <tried>` returns "does not exist", pin the OTHER
     *  casing so every subsequent call (and the immediate retry) uses the name that exists. */
    fun noteAutoContainerMissing(tried: String?) {
        val other = if (SVC_SNAKE == tried) SVC_PASCAL else SVC_SNAKE
        sCachedSvcName = other
        AppLogger.i(TAG, "AutoContainer '$tried' does not exist → switching to '$other'")
    }

    fun isDiLink5Safe(ctx: Context?): Boolean {
        return try {
            ctx != null && Platform.get().isDiLink5(ctx)
        } catch (ignore: Throwable) { false }
    }

    /** True if running on DiLink 2 (alps / k65v1, single display 0). */
    fun isDiLink2Safe(ctx: Context?): Boolean {
        return try {
            ctx != null && Platform.get().isDiLink2(ctx)
        } catch (ignore: Throwable) { false }
    }

    /**
     * DL2 SAFETY GUARD — matches any `wm overscan|size|density` subcommand
     * (with any arguments, anywhere in the line, including pipelines and chains).
     *
     * On DiLink 2 (alps / k65v1 / MT6765 / API 28) there is only physical
     * display 0 (verified L3/L5 of the DL2 RECON REPORT 22/05/2026). The MTK
     * fork silently falls back to display 0 when `-d N` targets a
     * non-existent display id, which shrinks the user's main UI screen
     * (field report: user set margins 80/50 → main screen got smaller).
     * Any such command is therefore unconditionally blocked on DL2.
     */
    private val P_DISPLAY_RESIZE: Pattern =
        Pattern.compile("(?s)\\bwm\\s+(overscan|size|density)\\b")

    fun isDisplayResizeCmd(cmd: String?): Boolean {
        if (cmd == null) return false
        return P_DISPLAY_RESIZE.matcher(cmd).find()
    }

    /**
     * Returns true and logs a warning when `cmd` must be blocked because
     * it is a display-resize command running on DL2. Centralised so every
     * shell entry point (legacy `executeShell*`, [com.byd.dashcast.proxy.ShellGateway])
     * applies the same guard.
     */
    fun blockDiLink2Resize(ctx: Context?, cmd: String?): Boolean {
        if (isDiLink2Safe(ctx) && isDisplayResizeCmd(cmd)) {
            AppLogger.w(TAG, "DL2 BLOCK: refused resize cmd \"" + cmd +
                "\" — single display 0, would shrink main screen")
            return true
        }
        return false
    }

    // -------------------------------------------------------------------------

    /**
     * Executes a raw shell command via local ADB (asynchronous).
     */
    fun executeShell(context: Context, command: String?) {
        if (blockDiLink2Resize(context, command)) return
        val appCtx = context.applicationContext
        sExecutor.execute {
            try {
                connect(appCtx).use { dadb ->
                    val r = dadb.shell(command!!)
                    noteTransportSuccess()
                    AppLogger.d(TAG, "executeShell: " + command + " -> " + r.allOutput.trim())
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(appCtx, e)
                AppLogger.e(TAG, "executeShell ERROR for: $command", e)
            }
        }
    }

    /** Executes a shell command and returns the result via callback (background thread). */
    fun executeShellWithResult(context: Context, command: String?, callback: Callback?) {
        executeShellWithResult(context, command, callback, true)
    }

    /**
     * Blocking variant reserved for an already-background, externally bounded worker such as
     * ShellGateway. Keeping the ADB handshake and shell call on that worker prevents an async
     * hand-off from bypassing the gateway's queue bound.
     */
    @Throws(Exception::class)
    fun executeShellWithResultBlocking(context: Context?, command: String?): String {
        return executeShellWithResultBlocking(context, command, SHELL_IDLE_TIMEOUT_MS)
    }

    @Throws(Exception::class)
    fun executeShellWithResultBlocking(context: Context?, command: String?,
                                       socketTimeoutMs: Int): String {
        if (context == null || command == null) throw IllegalArgumentException("null ctx/cmd")
        if (blockDiLink2Resize(context, command)) {
            throw IOException("blocked on DiLink 2: no cluster display")
        }
        val appCtx = context.applicationContext
        try {
            connect(appCtx, socketTimeoutMs).use { dadb ->
                val output = dadb.shell(command).allOutput.trim()
                noteTransportSuccess()
                AppLogger.d(TAG, "executeShellWithResultBlocking: $command -> $output")
                return output
            }
        } catch (e: Exception) {
            noteTransportFailure(appCtx, e)
            throw e
        }
    }

    /**
     * Like [executeShellWithResult], but does NOT echo the full stdout into the
     * journal — it logs only the command length + byte count. Use for large payloads such
     * as the A13 bug-report body read-back (~1 MB), which would otherwise bloat the journal
     * that is itself embedded in the report.
     */
    fun executeShellWithResultUnlogged(context: Context, command: String?, callback: Callback?) {
        executeShellWithResult(context, command, callback, false, REPORT_IDLE_TIMEOUT_MS)
    }

    fun executeShellWithResultUnlogged(context: Context, command: String?, callback: Callback?,
                                       socketTimeoutMs: Int) {
        executeShellWithResult(context, command, callback, false, socketTimeoutMs)
    }

    fun executeShellWithResult(context: Context, command: String?, callback: Callback?,
                               socketTimeoutMs: Int) {
        executeShellWithResult(context, command, callback, true, socketTimeoutMs)
    }

    private fun executeShellWithResult(context: Context, command: String?, callback: Callback?,
                                       logOutput: Boolean) {
        executeShellWithResult(context, command, callback, logOutput, SHELL_IDLE_TIMEOUT_MS)
    }

    private fun executeShellWithResult(context: Context, command: String?, callback: Callback?,
                                       logOutput: Boolean, socketTimeoutMs: Int) {
        if (blockDiLink2Resize(context, command)) {
            callback?.onError(
                "blocked on DiLink 2: no cluster display (would shrink main screen)")
            return
        }
        val appCtx = context.applicationContext
        sExecutor.execute {
            try {
                connect(appCtx, socketTimeoutMs).use { dadb ->
                    val output = dadb.shell(command!!).allOutput.trim()
                    noteTransportSuccess()
                    if (logOutput) {
                        AppLogger.d(TAG, "executeShellWithResult: $command -> $output")
                    } else {
                        AppLogger.d(TAG, "executeShellWithResult (unlogged, " + command.length +
                            "-char cmd) -> " + output.length + " bytes")
                    }
                    callback?.onSuccess(output)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(appCtx, e)
                AppLogger.e(TAG, "executeShellWithResult ERROR: $command", e)
                callback?.onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    interface Callback {
        /** Called on a background thread when the connection + grants are complete. */
        fun onSuccess(out: String?)
        /** Called if the connection fails (port closed, timeout, refused…). */
        fun onError(err: String?)
        /**
         * What [forceStopApp] decided to do with the task, from the SAME round trip that
         * fed the kill. Default no-op, so the dozen callers that do not care are untouched.
         *
         * It exists because the caller must NOT re-derive this. Review of the first version
         * caught exactly that: `ClusterSessionTracker` decided from the last probe of its
         * landing-wait loop while this method decided from a fresher one taken immediately before
         * the kill, and on the give-up branch the tracker's copy could never say
         * `KEEP_AND_RESTORE_HOME` even when the task had in fact landed on display 0. One
         * probe, one decision, carried to whoever needs it.
         */
        fun onEvictionOutcome(outcome: EvictionOutcomePolicy.Outcome) {}
    }

    // LOT 4 — BitmapCallback interface removed: only used by captureClusterDisplay
    // (also removed). No external caller across the codebase.

    // Grep pattern uses the [m] trick to prevent grep from matching its own cmdline.
    // Character-class tricks match both runtime names without matching grep's own cmdline.
    private const val DAEMON_GREP = "grep -E '[m]irrordaemon|[b]yd[.]mirror[.]daemon'"

    /**
     * Keeps only lines owned by uid 2000, because a process NAME is not an identity.
     *
     * Any installed app can declare `android:process="byd.mirror.daemon"` and land in this
     * grep. It cannot be mistaken for the daemon — reuse also demands a live
     * `byd_mirror_daemon` binder AND a pid matching the marker file that only shell can write — but
     * a second matching line is enough to make [SurfaceDaemonReusePolicy.singleProcessPid]
     * return -1, which sends every startMirrorDaemon down the kill-and-respawn branch. That throws
     * away the state this daemon exists to HOLD (slot overlays, VirtualDisplays, the mirror token)
     * on every call, permanently, since our `kill -9` from uid 2000 cannot touch another
     * app's process. It would also let the post-launch "ACTIVE ✓" check pass on the impostor's
     * line while our own launch had failed.
     *
     * The USER column is field 1 of toybox `ps -A`; it can print either the name or the
     * number depending on the ROM, so both are accepted. Filtering here keeps the line shape
     * intact, so singleProcessPid still reads the pid from field 2.
     */
    private const val SHELL_OWNED_ONLY = "awk '\$1==\"shell\" || \$1==\"2000\"'"

    /** `ps` listing of OUR daemon processes only — never another app's lookalike. */
    private const val DAEMON_PS = "ps -A | $DAEMON_GREP | $SHELL_OWNED_ONLY"

    private const val KILL_DAEMON_CMD =
        "$DAEMON_PS | awk '{print \$2}'" +
        " | xargs -r kill -9 2>/dev/null; echo killed"

    @Volatile private var sLastDaemonStartMs = 0L
    private val sMirrorDaemonStartInFlight = AtomicBoolean(false)
    fun getLastDaemonStartMs(): Long = sLastDaemonStartMs

    /**
     * Spawns (or reuses) [SurfaceDaemon] — the uid-2000 daemon that HOLDS the mirror/cluster
     * surfaces; see the two-daemon boundary on that class.
     *
     * The method name and every log line below deliberately keep saying "MirrorDaemon": they are
     * the daemon's runtime identity in logcat and in `mirrordaemon_latest.log`, which the
     * bug-report tooling pastes into every report and triagers grep across historical reports.
     */
    fun startMirrorDaemon(context: Context) {
        if (!sMirrorDaemonStartInFlight.compareAndSet(false, true)) {
            AppLogger.d(TAG, "MirrorDaemon start already in flight — joining existing attempt")
            return
        }
        sLastDaemonStartMs = System.currentTimeMillis()
        sExecutor.execute {
            try {
                connect(context, PROBE_IDLE_TIMEOUT_MS).use { dadb ->
                    // IMPORTANT: the daemon renames itself to "com.byd.dashcast.mirrordaemon" via
                    // setArgV0(), not "byd.mirror.daemon" → grep on both patterns.
                    val psOut = dadb.shell("$DAEMON_PS 2>&1").allOutput.trim()
                    val daemonPid = SurfaceDaemonReusePolicy.singleProcessPid(psOut)
                    val versionMarker = if (daemonPid > 0)
                        dadb.shell("cat " + SurfaceDaemon.VERSION_FILE + " 2>/dev/null")
                            .allOutput.trim()
                    else ""
                    val knownBinder = DaemonBinderResolver.surfaceDaemonBinder()
                    val binderAlive = knownBinder != null && knownBinder.isBinderAlive
                    if (SurfaceDaemonReusePolicy.shouldReuse(binderAlive, daemonPid,
                            versionMarker, BuildConfig.VERSION_CODE)) {
                        AppLogger.i(TAG, "MirrorDaemon already active for build " +
                            BuildConfig.VERSION_CODE + " pid=" + daemonPid +
                            " — reusing Binder")
                        noteTransportSuccess()
                        return@use
                    }
                    if (psOut.isNotEmpty()) {
                        AppLogger.i(TAG, "MirrorDaemon identity stale, duplicate, or unavailable" +
                            " (pid=" + daemonPid + ", marker=" + versionMarker +
                            ", binder=" + binderAlive + ") — restarting")
                    }
                    if (psOut.isNotEmpty()) {
                        dadb.shell(KILL_DAEMON_CMD)
                        AppLogger.i(TAG, "Old MirrorDaemon(s) killed.")
                        Thread.sleep(500)
                    }
                    dadb.shell("rm -f " + SurfaceDaemon.VERSION_FILE)
                    val apkPath = context.packageCodePath
                    // Prune old per-launch daemon logs, keeping the 5 most recent:
                    // one file is created per daemon start and nothing ever deleted
                    // them — /data/local/tmp accumulated hundreds over weeks
                    // (user report, June 2026). The glob targets mirrordaemon_2*
                    // so the mirrordaemon_latest.log symlink is never matched.
                    dadb.shell("ls -t /data/local/tmp/mirrordaemon_2*.log 2>/dev/null" +
                        " | tail -n +6 | xargs -r rm -f")
                    // Java timestamp → unique filename per launch
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val logPath = "/data/local/tmp/mirrordaemon_$ts.log"
                    val latestLink = "/data/local/tmp/mirrordaemon_latest.log"
                    // setsid: detaches the process from the ADB session group
                    // → survives dadb connection close (otherwise SIGHUP possible)
                    // CLASSPATH inline (no export &&) as Commander APK does it
                    // -Xnoimage-dex2oat: avoids AOT crash at startup
                    val cmd = SurfaceDaemonStartCommand.build(apkPath, logPath, latestLink)
                    dadb.shell(cmd)
                    AppLogger.i(TAG, "MirrorDaemon launched → $logPath")

                    // Verification: is the process alive after 3s?
                    Thread.sleep(3000)
                    val psCheck = dadb.shell("$DAEMON_PS 2>&1").allOutput.trim()
                    if (psCheck.isNotEmpty()) {
                        AppLogger.i(TAG, "MirrorDaemon ACTIVE ✓  $psCheck")
                    } else {
                        AppLogger.e(TAG, "MirrorDaemon NOT FOUND after 3s — reading log:")
                        val logContent = safeOut(dadb.shell("cat $logPath 2>&1").allOutput)
                        AppLogger.e(TAG, "mirrordaemon.log = [$logContent]")
                    }
                    noteTransportSuccess()
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                val state = AdbTransportFailure.classify(e)
                val binderAlive = isMirrorDaemonBinderAlive()
                if (AdbTransportConfirmationPolicy.shouldReportMirrorFailure(state, binderAlive)) {
                    noteTransportFailure(context, e)
                    AppLogger.e(TAG, "MirrorDaemon startup error", e)
                } else {
                    AppLogger.w(TAG, "MirrorDaemon launch stream timed out, but Binder is live" +
                        " — suppressing global ADB outage: " + e.message)
                }
            } finally {
                sMirrorDaemonStartInFlight.set(false)
            }
        }
    }

    // ── Private helper — dadb connection (key already authorized, no popup) ───────────

    /** Lock for key generation: prevents TOCTOU if two ADB methods are called
     *  simultaneously on first launch (before .key/.pub files exist). */
    private val sKeyLock = Any()

    /** Parsed key pair cached for the process lifetime. The .key/.pub files are
     *  immutable after first generation (cleanupFiles explicitly preserves them),
     *  so re-reading + RSA-parsing them on every command was pure waste — the
     *  5 s pidof poll on the legacy path paid it twice per tick. */
    @Volatile private var sKeyPair: AdbKeyPair? = null
    @Volatile private var sFreshKeyAwaitingAuthorization = false

    /**
     * Set to `true` the first time [connect] receives ECONNREFUSED from `Dadb.create()`.
     * Cleared on the first successful connection. Used by the UI layer to display a one-time
     * warning when ADB TCP (port 5555) is not accessible on the device (e.g. disabled in ROM).
     */
    @Volatile private var sPortRefused = false

    /** Returns `true` if the last ADB connection attempt was refused (ECONNREFUSED). */
    fun isAdbPortRefused(): Boolean = sPortRefused

    // ── Transport health classification (v1.6.102) ─────────────────────────────
    // Sticky diagnosis of the self-ADB transport to localhost:5555 so callers can
    // (a) stop paying a blocking bootstrap on a permanently-dead transport (the
    // ProxyClient circuit-breaker) and (b) surface ONE actionable message to the
    // tester. Distinct from sPortRefused so the message can tell "ADB-TCP off /
    // port closed" apart from "port open but this app's key is not authorized".
    // Cleared on the first fully-successful connect().
    /** ECONNREFUSED — adbd not listening on TCP (ADB-over-TCP disabled in the ROM). */
    const val XPORT_REFUSED = "PORT_CLOSED"
    /** TCP connect timed out (SYN dropped / filtered) — no ADB listener on 5555. */
    const val XPORT_NO_LISTENER = "NO_LISTENER"
    /** TCP open but the ADB handshake failed — this app's RSA key is not authorized. */
    const val XPORT_AUTH = "KEY_UNAUTHORIZED"
    /** TCP and ADB handshake succeeded, but adbd stopped answering stream reads. */
    const val XPORT_UNRESPONSIVE = "ADB_UNRESPONSIVE"
    /**
     * TCP port 5555 answered but the ADB handshake did not complete — TRANSIENT.
     *
     * Distinct from [XPORT_NO_LISTENER] on purpose: the listener is proven alive by
     * the probe, so this state must never advise enabling `adb tcpip 5555`. Typically a
     * colliding transport (adbd tears down competing A_CNXN handshakes); [connect] has
     * already retried once by the time this is published.
     */
    const val XPORT_HANDSHAKE = "HANDSHAKE_FAILED"

    @Volatile private var sTransportState: String? = null // null = healthy / untested
    @Volatile private var sTransportMsgShown = false
    private val sTimeoutConfirmationInFlight = AtomicBoolean(false)
    private val sTransportSuccessGeneration = AtomicLong()
    /** Dadb.create() is lazy: only a successful operation proves handshake + shell health. */
    @Volatile private var sOperationSucceeded = false

    /** `true` once the self-ADB transport has been classified as unreachable. */
    fun isAdbTransportUnreachable(): Boolean = sTransportState != null

    /** One of the `XPORT_*` constants, or `null` when healthy / untested. */
    fun adbTransportState(): String? = sTransportState

    /** Human, actionable one-liner matching the current transport state (Diag / banners). */
    fun adbTransportDiagnosis(): String {
        val s = sTransportState ?: return "ADB transport OK / untested"
        if (XPORT_AUTH == s) {
            return "ADB over TCP is reachable but this app's debug key is not authorized. " +
                "Accept the “Allow USB debugging” prompt for DashCast (tick " +
                "“always allow from this computer”) so the uid-2000 proxy daemon can start."
        }
        if (XPORT_UNRESPONSIVE == s) {
            return "ADB over TCP accepts connections but does not answer ADB commands. " +
                "Restart Android debugging/adbd on the head unit, then reopen DashCast."
        }
        if (XPORT_HANDSHAKE == s) {
            // Port 5555 answered the TCP probe: never tell the tester to enable ADB-over-TCP.
            return "ADB port 5555 is reachable but the ADB handshake did not complete " +
                "(transient — usually two connections racing). DashCast retries automatically."
        }
        return "ADB over TCP (port 5555) is not reachable on this unit. Cluster projection needs " +
            "the uid-2000 proxy daemon, which connects over local ADB. Enable ADB debugging over " +
            "TCP (e.g. `adb tcpip 5555`) and keep it enabled."
    }

    /** Record a transport failure and, once per outage, log + toast one clear message. */
    private fun markTransport(ctx: Context?, state: String) {
        val transition = state != sTransportState
        sTransportState = state
        if (transition || !sTransportMsgShown) {
            sTransportMsgShown = true
            val msg = adbTransportDiagnosis()
            AppLogger.e(TAG, "SELF-ADB TRANSPORT UNREACHABLE [$state] — $msg")
            var toast = msg
            if (ctx != null) {
                try {
                    toast = if (XPORT_AUTH == state) {
                        ctx.getString(R.string.adb_transport_key_unauthorized)
                    } else if (XPORT_UNRESPONSIVE == state) {
                        ctx.getString(R.string.adb_transport_unresponsive)
                    } else if (XPORT_HANDSHAKE == state) {
                        ctx.getString(R.string.adb_transport_handshake)
                    } else {
                        ctx.getString(R.string.adb_transport_unreachable)
                    }
                } catch (ignore: Throwable) { /* technical English fallback above */ }
            }
            toastOnce(ctx, toast)
        }
    }

    /** Reset the classification after a fully-successful connect. */
    private fun clearTransport() {
        sTransportState = null
        sTransportMsgShown = false
    }

    private fun noteTransportSuccess() {
        sOperationSucceeded = true
        sFreshKeyAwaitingAuthorization = false
        sTransportSuccessGeneration.incrementAndGet()
        clearTransport()
    }

    private fun noteTransportFailure(context: Context?, error: Throwable) {
        val state = AdbTransportFailure.classify(error) ?: return
        // Never downgrade HANDSHAKE to NO_LISTENER.
        //
        // connect() classifies this SAME exception with classify(e, true) — it holds the proof that
        // a plain TCP connect to adbd succeeded milliseconds earlier — and publishes XPORT_HANDSHAKE.
        // The exception then propagates to the caller's catch, which lands here and re-classifies it
        // WITHOUT that proof; the one and only difference the flag makes is this exact pair, so the
        // verdict flips to NO_LISTENER and markTransport() overwrites the better answer. The tester
        // is then told to run `adb tcpip 5555` for a port that answered a fraction of a second ago —
        // precisely the misdiagnosis AdbTransportFailure's own doc says the flag was added to stop.
        // The fix was threaded into connect() and never into the sites downstream of it.
        //
        // Only this pair is guarded: every other classification here is made from the exception
        // alone and is not second-guessing a better-informed one.
        if (XPORT_NO_LISTENER == state && XPORT_HANDSHAKE == sTransportState) {
            AppLogger.d(TAG, "keeping XPORT_HANDSHAKE — connect() classified this with a passing" +
                " TCP probe; re-classifying without it would say NO_LISTENER")
            sOperationSucceeded = false
            return
        }
        if (XPORT_UNRESPONSIVE == state) {
            confirmUnresponsiveTransport(context, error)
            return
        }
        sOperationSucceeded = false
        markTransport(context, state)
    }

    private fun confirmUnresponsiveTransport(context: Context?, originalError: Throwable) {
        if (!sTimeoutConfirmationInFlight.compareAndSet(false, true)) {
            AppLogger.d(TAG, "ADB timeout confirmation already in flight — suppressing duplicate")
            return
        }
        val successGenerationAtStart = sTransportSuccessGeneration.get()
        var probeSucceeded = false
        var probeError: Throwable? = null
        try {
            val keyPair = sKeyPair ?: throw IOException("ADB key unavailable for health probe")
            Dadb.create(
                "localhost", ADB_PORT, keyPair,
                CONNECT_PROBE_MS, TRANSPORT_CONFIRM_IDLE_TIMEOUT_MS, true
            ).use { probe ->
                val output = probe.shell("echo $TRANSPORT_CONFIRM_MARKER").allOutput.trim()
                probeSucceeded = output.contains(TRANSPORT_CONFIRM_MARKER)
            }
        } catch (error: Throwable) {
            probeError = error
        } finally {
            sTimeoutConfirmationInFlight.set(false)
        }

        val confirmedState = AdbTransportConfirmationPolicy.resolve(
            XPORT_UNRESPONSIVE, probeSucceeded, probeError)
        if (confirmedState == null) {
            noteTransportSuccess()
            AppLogger.w(TAG, "ADB shell stream timed out but independent echo succeeded" +
                " — global outage suppressed: " + originalError.message)
            return
        }
        if (!AdbTransportConfirmationPolicy.shouldApplyFailure(
                successGenerationAtStart, sTransportSuccessGeneration.get())) {
            AppLogger.w(TAG, "ADB timeout confirmation became stale after a newer success" +
                " — global outage suppressed")
            return
        }
        sOperationSucceeded = false
        AppLogger.e(TAG, "ADB timeout confirmed by independent probe" +
            (if (probeError != null) ": $probeError" else " (unexpected echo response)"))
        markTransport(context, confirmedState)
    }

    private fun isMirrorDaemonBinderAlive(): Boolean {
        return try {
            val binder = DaemonBinderResolver.surfaceDaemonBinder()
            binder != null && binder.isBinderAlive
        } catch (ignore: Throwable) {
            false
        }
    }

    private fun toastOnce(ctx: Context?, msg: String) {
        try {
            val app = ctx?.applicationContext ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                } catch (ignore: Throwable) { /* Toast is best-effort */ }
            }
        } catch (ignore: Throwable) { /* a diagnostic notice must never crash a bg thread */ }
    }

    /**
     * Process-wide gate SERIALISING the local-ADB connect + handshake.
     *
     * ClusterService.onCreate fans out three independent local-ADB connects within ~45 ms
     * ([startMirrorDaemon], [dumpSignatureAndPermissions] and the
     * `ro.build.system.fission_single_os` getprop). adbd tears down colliding transports,
     * so the loser's handshake dies with `AdbConnectException("Connection handshake
     * failed")` on a port that is perfectly alive. Only the handshake itself is gated — the
     * shell command runs on the returned [Dadb] outside the critical section, so a long
     * dumpsys never blocks another caller's connect.
     *
     * Deadlock-free by construction: the one-argument [connect] delegates to the two-argument
     * one which is the ONLY acquirer, nothing inside the gated region re-enters connect(), and
     * the gate is released in a `finally` that runs before the surrounding catch/retry logic
     * (never held across a sleep).
     */
    private val sHandshakeGate = Semaphore(1, true)
    /** Never wedge a caller behind the gate: past this it simply proceeds unserialised. */
    private const val HANDSHAKE_GATE_WAIT_MS = 30_000L
    /** Backoff before the single silent retry of a handshake that lost a transport race. */
    private const val HANDSHAKE_RETRY_DELAY_MS = 400L

    @Throws(Exception::class)
    private fun connect(context: Context): Dadb {
        return connect(context, SHELL_IDLE_TIMEOUT_MS)
    }

    @Throws(Exception::class)
    private fun connect(context: Context, socketTimeoutMs: Int): Dadb {
        var keyPair = sKeyPair
        if (keyPair == null) {
            synchronized(sKeyLock) {
                if (sKeyPair == null) {
                    val privateKey = File(context.filesDir, "adb.key")
                    val publicKey = File(context.filesDir, "adb.pub")
                    if (!privateKey.exists() || !publicKey.exists()) {
                        AdbKeyPair.generate(privateKey, publicKey)
                        sFreshKeyAwaitingAuthorization = true
                    }
                    sKeyPair = AdbKeyPair.read(privateKey, publicKey)
                }
                keyPair = sKeyPair
            }
        }
        val key = keyPair!!

        // v1.6.102 — fast TCP reachability probe BEFORE the ADB handshake. Distinguishes
        // "port closed / ADB-TCP off" and "no listener (SYN dropped)" from "port open but key
        // not authorized", and fails those in ~1.5 s instead of blocking on the OS SYN timeout
        // inside Dadb.create(). Retrying is futile when the port itself is dead (no "Allow USB
        // debugging" popup can ever appear), so we throw immediately there; the 5×2 s retry
        // below only wraps the AUTH stage, where the popup is expected.
        try {
            val probe = Socket()
            try {
                probe.connect(InetSocketAddress("localhost", ADB_PORT), CONNECT_PROBE_MS)
            } finally {
                try { probe.close() } catch (ignore: IOException) { /* best-effort */ }
            }
        } catch (ste: SocketTimeoutException) {
            markTransport(context, XPORT_NO_LISTENER)
            throw IOException("ADB TCP 5555 unreachable (no listener / SYN dropped)", ste)
        } catch (ce: ConnectException) {
            sPortRefused = true
            markTransport(context, XPORT_REFUSED)
            throw IOException("ADB TCP 5555 refused (ADB-over-TCP off)", ce)
        } catch (ioe: IOException) {
            markTransport(context, XPORT_NO_LISTENER)
            throw ioe
        }

        // TCP is open → run the ADB handshake. Retry to give the user time to accept the
        // 'Allow USB debugging' popup if this app's RSA key is not yet authorized.
        var retries = 5
        var lastE: Exception? = null
        var handshakeRetried = false
        while (retries > 0) {
            var d: Dadb? = null
            try {
                val effectiveTimeout = AdbTimeoutPolicy.effectiveIdleTimeoutMs(
                    maxOf(1, socketTimeoutMs),
                    sOperationSucceeded,
                    if (sFreshKeyAwaitingAuthorization)
                        NEW_KEY_AUTH_IDLE_TIMEOUT_MS
                    else FIRST_OPERATION_IDLE_TIMEOUT_MS)
                // Serialise ONLY create()+handshake (see sHandshakeGate). The inner finally
                // releases the gate before the catch below runs, so no retry sleep and no shell
                // command is ever executed while holding it.
                var gateHeld = false
                try {
                    gateHeld = sHandshakeGate.tryAcquire(
                        HANDSHAKE_GATE_WAIT_MS, TimeUnit.MILLISECONDS)
                    if (!gateHeld) {
                        AppLogger.w(TAG, "ADB handshake gate busy > " + HANDSHAKE_GATE_WAIT_MS +
                            " ms — proceeding unserialised")
                    }
                    d = Dadb.create(
                        "localhost", ADB_PORT, key,
                        CONNECT_PROBE_MS, effectiveTimeout, true)
                    // Dadb.create() is lazy. Force A_CNXN/A_AUTH now so this retry loop actually
                    // surrounds the authorization handshake rather than returning an unopened
                    // client.
                    d.supportsFeature("shell_v2")
                } finally {
                    if (gateHeld) sHandshakeGate.release()
                }
                sPortRefused = false // full success: port reachable + key authorized
                if (XPORT_UNRESPONSIVE != sTransportState) clearTransport()
                return d
            } catch (e: Exception) {
                if (d != null) {
                    try { d.close() } catch (ignore: Throwable) { /* best-effort */ }
                }
                lastE = e
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                // The TCP probe above succeeded milliseconds ago → port 5555 IS alive. Classify
                // WITH that fact so an ADB-level failure can never be reported as "no listener"
                // (which used to toast "run adb tcpip 5555" for a port working 429 ms earlier).
                val state = AdbTransportFailure.classify(e, true)
                // Transient handshake failure: retry ONCE before ANY transport verdict is
                // published — no toast, no latched sTransportState, no circuit-breaker trip.
                if (XPORT_HANDSHAKE == state && !handshakeRetried) {
                    handshakeRetried = true
                    AppLogger.w(TAG, "ADB handshake failed right after a successful TCP probe" +
                        " (colliding transport?) — one silent retry: " + e)
                    try {
                        Thread.sleep(HANDSHAKE_RETRY_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                    continue // does not consume the auth-popup retry budget
                }
                if (XPORT_REFUSED == state) sPortRefused = true
                if (state != null && XPORT_UNRESPONSIVE != state) {
                    markTransport(context, state)
                }
                // Only a genuine auth rejection may self-heal via the approval popup.
                if (e !is AdbAuthException) throw e
                AppLogger.w(TAG, "ADB handshake exception (popup pending?), retrying in 2s... ($retries left)")
                try {
                    Thread.sleep(2000)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                retries--
            }
        }
        throw lastE!!
    }

    // ── Grant SYSTEM_ALERT_WINDOW via appops ─────────────────────────────────────
    /**
     * Grants SYSTEM_ALERT_WINDOW to the current package via the local ADB shell.
     *
     * On Android 10+ a non-system app does not get this AppOp even if
     * SYSTEM_ALERT_WINDOW is in the manifest and the APK is platform-signed.
     * The command "appops set <pkg> SYSTEM_ALERT_WINDOW allow" (shell uid=2000)
     * is sufficient for Settings.canDrawOverlays() to return true without a reboot.
     *
     * Callback is called on the dadb background thread — post to main thread
     * if you need to update the UI after success.
     */
    fun grantOverlayPermission(context: Context, callback: Callback) {
        val appCtx = context.applicationContext
        sExecutor.execute {
            try {
                connect(appCtx).use { dadb ->
                    val cmd = "appops set " + appCtx.packageName + " SYSTEM_ALERT_WINDOW allow"
                    val r = dadb.shell("$cmd 2>&1")
                    noteTransportSuccess()
                    AppLogger.i(TAG, "grantOverlayPermission → " + cmd +
                        " → '" + r.allOutput.trim() + "'")
                    callback.onSuccess(r.allOutput.trim())
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(appCtx, e)
                val msg = e.javaClass.simpleName + ": " + e.message
                AppLogger.e(TAG, "grantOverlayPermission ERREUR", e)
                callback.onError(msg)
            }
        } // adb-overlay-grant
    }

    /**
     * Restores the native BYD display on the cluster.
     *
     * com.byd.automap is NOT installed on BYD Seal EU — cannot use
     * Freedom sequence (am start automap).
     *
     * Seal EU fix:
     *   1. Find taskId of our app on display <displayId>
     *   2. am task remove <taskId>  → releases the surface (without killing the whole process)
     *   3. sendInfo(1000, 0)        → Qt regains control of the surface
     *
     * @param targetPackage nullable: package to force-stop before restore
     */
    fun restoreBydOnCluster(context: Context, targetPackage: String?, callback: Callback) {
        // v1.2.78 — invalidate ClusterManager's fast-path flag NOW (synchronously, before
        // the async dispatch). Qt will return to native mode as soon as sendInfo(18)
        // lands; the VirtualDisplay persists, so subsequent activate() calls must
        // take the warm path (30→6s→16) instead of the true fast path.
        ClusterManager.notifyProjectionStopped()
        sExecutor.execute {
            AppLogger.log(TAG, "Restoring BYD cluster" +
                (if (targetPackage != null) " (target=$targetPackage)" else ""))
            // Phase 4d: try the typed daemon path for the whole sequence
            // (force-stop + sendInfo×2). On any failure we fall through to
            // the legacy shell sequence below so semantics are preserved.
            // DL5: skip typed path — Phase4ProcessVerbs hardcodes "AutoContainer".
            if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context)) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    if (!ProxyClient.isConnected()) {
                        // Never call connect() from an executor thread — blocks 10–15 s.
                        // ProxyKeeperService reconnects in background; skip to legacy path.
                        throw Exception("proxy not connected — skip typed path")
                    }
                    val sb = StringBuilder()
                    if (targetPackage != null && targetPackage.isNotEmpty()) {
                        // Phase 4d.1 (build 180): userId=0 (current user) instead of -1.
                        // USER_ALL (-1) is silently no-op on some API 29 BYD framework
                        // builds — the call returned without throwing but the package
                        // process remained alive (Waze stayed visible on display 0
                        // after restoreBydOnCluster reported "typed ok").
                        ProxyClient.forceStopPackage(targetPackage, 0)
                        sb.append("force-stop ").append(targetPackage).append(" (typed,u=0)\n")
                        Thread.sleep(500)
                        verifyForceStop(targetPackage, sb)
                    }
                    ProxyClient.autoContainerSendInfo(1000, 18, "")
                    sb.append("sendInfo(18) : OK (typed)\n")
                    Thread.sleep(1000)
                    ProxyClient.autoContainerSendInfo(1000, 0, "")
                    sb.append("sendInfo(0)  : OK (typed)\n")
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.log(TAG, "beta restoreBydOnCluster typed ok (${dt}ms)")
                    callback.onSuccess("BYD restored ✓ (typed)\n$sb")
                    return@execute
                } catch (t: Throwable) {
                    if (t is InterruptedException) {
                        Thread.currentThread().interrupt()
                        callback.onError("interrupted")
                        return@execute
                    }
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.w(TAG, "beta restoreBydOnCluster typed failed after " + dt +
                        "ms, falling back to ADB shell: " + t.message)
                    // fall through to legacy path
                }
            }
            try {
                connect(context).use { dadb ->
                    val sb = StringBuilder()

                    // 0. Force-stop target package BEFORE sendInfo(18).
                    // Without this, the app task (launched via trampoline on display 1) remains
                    // registered in ActivityManager: when sendInfo(18) releases the Qt surface,
                    // Android relocates the orphan task to display 0 → the app appears
                    // on the tablet's main screen.
                    if (targetPackage != null && targetPackage.isNotEmpty()) {
                        dadb.shell("am force-stop $targetPackage 2>&1")
                        sb.append("force-stop ").append(targetPackage).append("\n")
                        Thread.sleep(500)
                    }

                    val rStop = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 18 s16 \"\" 2>&1")
                    sb.append("sendInfo(18) : ").append(rStop.allOutput.trim()).append("\n")
                    Thread.sleep(1000)

                    val rRestore = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 0 s16 \"\" 2>&1")
                    sb.append("sendInfo(0)  : ").append(rRestore.allOutput.trim()).append("\n")

                    noteTransportSuccess()
                    AppLogger.log(TAG, "restoreBydOnCluster -> OK")
                    callback.onSuccess("BYD restored ✓\n$sb")
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(context, e)
                val msg = e.javaClass.simpleName + ": " + e.message
                AppLogger.e(TAG, "restoreBydOnCluster ERROR", e)
                callback.onError(msg)
            }
        } // adb-restore-thread
    }

    /**
     * Origin cluster — restores the Qt cluster to the screen size configured by the user.
     *
     * Sequence:
     *   1. sendInfo(1000, 18)            — close projection (投屏关闭)          → wait 6s
     *   2. sendInfo(1000,  0)            — refresh Qt stream                   → wait 6s
     *   3. sendInfo(1000, screenSizeCmd) — switch Qt to the correct resolution
     *
     * @param requestedScreenSizeCmd size code: 29=8.8" (Atto 3), 30=12.3" (Seal EU — CONFIRMED), 31=10.25" (Seal U-DMI)
     * @param targetPackage nullable: package to force-stop before restore
     */
    fun restoreOriginCluster(context: Context, requestedScreenSizeCmd: Int,
                             targetPackage: String?, callback: Callback) {
        // This is the DEFAULT Stop flow and it passes the raw preference, whose default is 30
        // (12.3"). On an Atto 3 / Dolphin that never had its cluster size configured, every Stop
        // was therefore ordering a 1280x480 panel into the 12.3" shape — which drops it into its
        // degraded simple mode. Two of the three known simple-mode reports came through HERE, with
        // the ADAS window fix off entirely (INC-20260625-173900, INC-20260715-141429); only one
        // came through the ADAS path that was guarded first. Sanitising at the entry covers both
        // the typed and the shell sub-paths below with one check.
        val screenSizeCmd = if (ClusterGeometryPolicy.allowShapeCommand(
                requestedScreenSizeCmd,
                ClusterPrefs.getClusterType(context),
                ClusterPrefs.isSmallClusterPanelLatched(context)))
            requestedScreenSizeCmd
        else ClusterGeometryPolicy.CMD_8_8
        if (screenSizeCmd != requestedScreenSizeCmd) {
            AppLogger.w(TAG, "restoreOriginCluster: refusing shape preset " +
                requestedScreenSizeCmd + " on a 1280x480 cluster — sending " +
                screenSizeCmd + " (8.8\") instead, the larger shape breaks that panel")
        }
        // v1.2.78 — see restoreBydOnCluster() above for rationale.
        ClusterManager.notifyProjectionStopped()
        sExecutor.execute {
            AppLogger.log(TAG, "restoreOriginCluster screenSize=" + screenSizeCmd +
                (if (targetPackage != null) " target=$targetPackage" else ""))
            // Phase 4d: try the typed daemon path (force-stop + sendInfo×3).
            // Falls back to the legacy shell flow on any failure.
            // DL5: skip typed path — Phase4ProcessVerbs hardcodes "AutoContainer".
            if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context)) {
                val t0 = SystemClock.elapsedRealtime()
                var callbackFired = false
                try {
                    if (!ProxyClient.isConnected()) {
                        // Never call connect() from an executor thread — blocks 10–15 s.
                        // ProxyKeeperService reconnects in background; skip to legacy path.
                        throw Exception("proxy not connected — skip typed path")
                    }
                    val sb = StringBuilder()
                    if (targetPackage != null && targetPackage.isNotEmpty()) {
                        // Phase 4d.1 (build 180): see restoreBydOnCluster above.
                        ProxyClient.forceStopPackage(targetPackage, 0)
                        sb.append("force-stop ").append(targetPackage).append(" (typed,u=0)\n")
                        Thread.sleep(500)
                        verifyForceStop(targetPackage, sb)
                    }
                    ProxyClient.autoContainerSendInfo(1000, 18, "")
                    sb.append("sendInfo(18) : OK (typed)\n")
                    Thread.sleep(2000)
                    ProxyClient.autoContainerSendInfo(1000, 0, "")
                    sb.append("sendInfo(0)  : OK (typed)\n")
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.log(TAG, "beta restoreOriginCluster typed ok (${dt}ms)")
                    // Fire callback now — UI unblocked, ClusterManager state cleaned up.
                    // screenSizeCmd is cosmetic and completes in background.
                    callbackFired = true
                    callback.onSuccess("Origin cluster restored ✓ (typed)\n$sb")
                    Thread.sleep(3000)
                    ProxyClient.autoContainerSendInfo(1000, screenSizeCmd, "")
                    AppLogger.log(TAG, "restoreOriginCluster screenSize(cmd=$screenSizeCmd) sent in background")
                    return@execute
                } catch (t: Throwable) {
                    if (t is InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute // callback already fired; background screenSizeCmd aborted
                    }
                    if (callbackFired) {
                        // Background screenSizeCmd failed — log only, callback already done
                        AppLogger.w(TAG, "restoreOriginCluster background screenSize failed: " + t.message)
                        return@execute
                    }
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.w(TAG, "beta restoreOriginCluster typed failed after " + dt +
                        "ms, falling back to ADB shell: " + t.message)
                    // fall through to legacy path
                }
            }
            try {
                connect(context).use { dadb ->
                    val sb = StringBuilder()

                    // Force-stop target package before restore (same reason as
                    // restoreBydOnCluster: avoid task relocation to display 0).
                    if (targetPackage != null && targetPackage.isNotEmpty()) {
                        dadb.shell("am force-stop $targetPackage 2>&1")
                        sb.append("force-stop ").append(targetPackage).append("\n")
                        Thread.sleep(500)
                    }

                    val rStop = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 18 s16 \"\" 2>&1")
                    sb.append("sendInfo(18) : ").append(rStop.allOutput.trim()).append("\n")
                    Thread.sleep(2000)

                    val rRefresh = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 0 s16 \"\" 2>&1")
                    sb.append("sendInfo(0)  : ").append(rRefresh.allOutput.trim()).append("\n")

                    noteTransportSuccess()
                    AppLogger.log(TAG, "restoreOriginCluster -> OK (screenSize in background)")
                    // Fire callback now — UI unblocked, ClusterManager state cleaned up.
                    // screenSizeCmd is cosmetic and completes in background after 3s Qt settling.
                    callback.onSuccess("Origin cluster restored ✓\n$sb")
                    try {
                        Thread.sleep(3000)
                        dadb.shell("service call " + autoContainerSvcName(context) +
                            " 2 i32 1000 i32 " + screenSizeCmd + " s16 \"\" 2>&1")
                        AppLogger.log(TAG, "restoreOriginCluster screenSize(cmd=$screenSizeCmd) sent in background")
                    } catch (bg: Exception) {
                        if (bg is InterruptedException) Thread.currentThread().interrupt()
                        noteTransportFailure(context, bg)
                        AppLogger.w(TAG, "restoreOriginCluster background screenSize failed: " + bg.message)
                    }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(context, e)
                val msg = e.javaClass.simpleName + ": " + e.message
                AppLogger.e(TAG, "restoreOriginCluster ERROR", e)
                callback.onError(msg)
            }
        } // adb-origin-cluster-thread
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // sendInfo — bypasses SecurityException (uid=10100 not in whitelist JSON) by running as uid=2000.
    // dm-verity prevents patching /system/etc/container_comm_cfg.json on this hardware.
    // uid=2000 passes checkSignatures() in AutoContainerService.
    // Transport: the proxy daemon's typed binder.transact (ProxyClient.autoContainerSendInfo) is tried
    // FIRST (the default path); the ADB shell relay below is only a fallback when the daemon is
    // unavailable (legacy mode / DL5). Both run as uid=2000.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends sendInfo(type, infoInt, infoStr) to the AutoContainer service from uid=2000.
     *
     * Tries the proxy daemon's typed transact first (ProxyClient.autoContainerSendInfo, descriptor
     * android.os.IAutoContainer), then falls back to the ADB shell relay below when the daemon is
     * unavailable (legacy mode / DL5).
     * Shell-relay equivalent: service call AutoContainer 2 i32 <type> i32 <infoInt> s16 "<infoStr>"
     * uid=2000 (shell) passes checkSignatures → no SecurityException.
     *
     * Callback is called from a background thread — use runOnUiThread if necessary.
     */
    fun sendInfo(context: Context, type: Int, infoInt: Int, infoStr: String?,
                 callback: Callback?) {
        sendInfo(context, type, infoInt, infoStr, callback, null)
    }

    /** Observer used only by the ordered projection bus to recover a wedged typed transact. */
    interface TypedDispatchObserver {
        fun onStart()
        fun onFinish()
        fun shouldAbortFallback(): Boolean
    }

    fun sendInfo(context: Context, type: Int, infoInt: Int, infoStr: String?,
                 callback: Callback?, typedObserver: TypedDispatchObserver?) {
        // Last line of defence for the cluster shape presets. The senders are guarded individually
        // (the ADAS window fix in ClusterManager, the preference in restoreOriginCluster), but the
        // first version of this protection guarded one of them and missed the other, so the rule is
        // also enforced here — the single point every AutoContainer command physically leaves the
        // app through. Non-shape commands are untouched.
        if (type == ClusterManager.CLUSTER_TYPE &&
            !ClusterGeometryPolicy.allowShapeCommand(infoInt,
                ClusterPrefs.getClusterType(context),
                ClusterPrefs.isSmallClusterPanelLatched(context))) {
            AppLogger.w(TAG, "sendInfo: BLOCKED shape preset " + infoInt +
                " — this car has the 1280x480 cluster, the larger shape breaks it")
            callback?.onSuccess("")
            return
        }
        sExecutor.execute {
            // v1.6.102 — daemon-free & ADB-free FIRST attempt, tried ONLY when the
            // uid-2000 daemon is not connected (so healthy DL3/DL5.0 keep their exact
            // proven path). Transacts the AutoContainer binder directly from THIS process:
            // needs neither the daemon nor the self-ADB shell — the only path left on an
            // unprivileged unit whose ADB-TCP is dead (D50F_LC). The checkSignatures(uid<10000)
            // fast-path this whole call is betting on is confirmed real for the uid-2000
            // daemon (81 bug reports, zero refusals) — but the app's own uid is NOT under
            // 10000, so it does NOT get that fast-path and IS refused here in practice. That
            // is fine: this is explicitly a best-effort first attempt, and any failure — incl.
            // SecurityException — falls through untouched to the ADB/daemon paths below.
            if (!ProxyClient.isConnected()) {
                try {
                    val svc = autoContainerSvcName(context)
                    sendInfoInProcess(svc, type, infoInt, infoStr)
                    AppLogger.i(TAG, "sendInfo IN-PROC transact ACCEPTED from app uid on '" +
                        svc + "' (" + type + "," + infoInt + ") — daemon-free path")
                    callback?.onSuccess("")
                    return@execute
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "sendInfo IN-PROC transact REJECTED from app uid (" +
                        t.javaClass.simpleName + ": " + t.message +
                        ") — falling back to daemon/shell")
                    // fall through to the typed-daemon / ADB-shell path below
                }
            }
            // Phase 4c: try the typed daemon path first. P13 (build 176)
            // proved binder.transact(2, ...) on AutoContainer is accepted
            // from uid 2000 with descriptor android.os.IAutoContainer.
            // On any failure we fall through to the legacy ADB shell
            // wrapper below — semantics are preserved for callers that
            // only inspect callback.onSuccess(String) for emptiness.
            // DL5: skip typed path — Phase4ProcessVerbs hardcodes the DL3 service
            // name ("AutoContainer") which does not exist on DL5.
            if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context) &&
                (typedObserver == null || ProxyClient.supportsProtocol(25))) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    if (!ProxyClient.isConnected()) {
                        // Never call connect() from an executor thread — blocks 10–15 s.
                        // ProxyKeeperService reconnects in background; skip to legacy path.
                        throw Exception("proxy not connected — skip typed path")
                    }
                    if (typedObserver != null) {
                        ProxyClient.setNonBlockingReconnect(true)
                        typedObserver.onStart()
                    }
                    try {
                        ProxyClient.autoContainerSendInfo(type, infoInt, infoStr)
                    } finally {
                        if (typedObserver != null) {
                            typedObserver.onFinish()
                            ProxyClient.setNonBlockingReconnect(false)
                        }
                    }
                    if (typedObserver != null && typedObserver.shouldAbortFallback()) return@execute
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.log(TAG, "beta sendInfo typed ok (" + dt + "ms): " +
                        type + "," + infoInt + ",\"" + (infoStr ?: "") + "\"")
                    // Legacy wrapper returned `service call` stdout (Parcel
                    // hex dump). Typed path has no equivalent payload —
                    // empty string matches what every existing caller
                    // already expects (none of them parses the dump).
                    callback?.onSuccess("")
                    return@execute
                } catch (t: Throwable) {
                    if (typedObserver != null && typedObserver.shouldAbortFallback()) {
                        AppLogger.e(TAG, "beta sendInfo typed timed out — fallback suppressed")
                        return@execute
                    }
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.w(TAG, "beta sendInfo typed failed after " + dt +
                        "ms, falling back to ADB shell: " + t.message)
                    // fall through to legacy path
                }
            }
            if (isAdbTransportUnreachable()) {
                callback?.onError(adbTransportDiagnosis())
                return@execute
            }
            try {
                connect(context, BOOTSTRAP_IDLE_TIMEOUT_MS).use { dadb ->
                    // Escape shell metacharacters inside the double-quoted argument:
                    //   \  → must be first to avoid double-escaping
                    //   "  → terminates the quoted string
                    //   $  → triggers variable / arithmetic / command expansion
                    //   `  → triggers command substitution
                    val safeStr = (infoStr ?: "")
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("$", "\\$")
                        .replace("`", "\\`")
                    val svc = autoContainerSvcName(context)
                    val cmd = "service call " + svc + " 2 i32 " + type +
                        " i32 " + infoInt + " s16 \"" + safeStr + "\" 2>&1"
                    AppLogger.log(TAG, "sendInfo ADB: $cmd")
                    val r = dadb.shell(cmd)
                    val out = r.allOutput.trim()
                    AppLogger.log(TAG, "sendInfo ADB($type,$infoInt) → $out")
                    noteTransportSuccess()
                    callback?.onSuccess(out)
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(context, e)
                AppLogger.e(TAG, "sendInfo ADB ERREUR", e)
                callback?.onError(e.javaClass.simpleName + ": " + e.message)
            }
        } // adb-sendinfo-thread
    }

    /**
     * Sends `sendInfo(type, infoInt, infoStr)` to the AutoContainer service by
     * transacting its binder DIRECTLY from the current process — no daemon, no ADB.
     *
     * Resolves the live [IBinder] via `ServiceManager.getService(svc)` (reflection; hidden APIs
     * are already unlocked at startup) and reads the advertised interface descriptor at runtime
     * so OEM rebrands still work. Uses the resolved service name (see [autoContainerSvcName])
     * rather than a hardcoded one, so it is correct on both PascalCase (DL3 / DL5.1) and
     * snake_case (literal DiLink5.0) units.
     *
     * @throws Throwable if the service is absent, the binder is dead, or the server rejects
     *         the caller (e.g. a SecurityException surfaced via [Parcel.readException]).
     */
    @Throws(Throwable::class)
    private fun sendInfoInProcess(svc: String, type: Int, infoInt: Int, infoStr: String?) {
        val sm = Class.forName("android.os.ServiceManager")
        val b = sm.getMethod("getService", String::class.java).invoke(null, svc) as IBinder?
            ?: throw IllegalStateException("no '$svc' service in ServiceManager")

        // Read the advertised interface descriptor (the token the server expects).
        val descriptor: String?
        val d0 = Parcel.obtain()
        val r0 = Parcel.obtain()
        try {
            b.transact(IBinder.INTERFACE_TRANSACTION, d0, r0, 0)
            descriptor = r0.readString()
        } finally {
            r0.recycle()
            d0.recycle()
        }
        if (descriptor == null || descriptor.isEmpty()) {
            throw IllegalStateException("$svc advertised an empty descriptor")
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(type)
            data.writeInt(infoInt)
            data.writeString(infoStr ?: "")
            b.transact(2 /* TXN sendInfo */, data, reply, 0)
            reply.readException() // re-throws a SecurityException the server may return
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ── Diagnostic: actual signature + permissions ──────────────────────────────

    /**
     * Dumps the real signature and permission state for our app via ADB
     * (uid=2000, system view). Answers the question:
     * "Is the APK really signed with the same key as the ROM?"
     *
     * Output logged (AppLogger INFO, tag "SigDump"):
     *   - ro.build.tags / ro.build.version.security_patch
     *   - dumpsys package com.byd.dashcast | grep -E "Signature|signatures|version"
     *   - dumpsys package com.xdja.containerservice | grep -E "Signature|signatures"
     *   - pm dump com.byd.dashcast | grep -E "INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS"
     *   - dumpsys package com.byd.dashcast | grep -A 1 "install permissions:"
     *   - id (current shell uid)
     */
    fun dumpSignatureAndPermissions(context: Context) {
        sExecutor.execute {
            val dTag = "SigDump"
            try {
                connect(context).use { dadb ->
                    val pkg = context.packageName

                    AppLogger.i(dTag, "=== Build & shell uid ===")
                    AppLogger.i(dTag, "id: " + dadb.shell("id 2>&1").allOutput.trim())
                    AppLogger.i(dTag, "build.tags: " + dadb.shell(
                        "getprop ro.build.tags 2>&1").allOutput.trim())
                    AppLogger.i(dTag, "build.fingerprint: " + dadb.shell(
                        "getprop ro.build.fingerprint 2>&1").allOutput.trim())

                    AppLogger.i(dTag, "=== Notre APK ($pkg) signature & version ===")
                    val ourSig = dadb.shell(
                        "dumpsys package " + pkg +
                            " | grep -E 'versionCode|versionName|signatures' " +
                            "| head -10 2>&1").allOutput.trim()
                    for (line in ourSig.split("\n")) AppLogger.i(dTag, "  $line")

                    AppLogger.i(dTag, "=== ROM/AutoContainer signature (com.xdja.containerservice) ===")
                    val romSig = dadb.shell(
                        "dumpsys package com.xdja.containerservice " +
                            "| grep -E 'signatures|sharedUser' | head -5 2>&1").allOutput.trim()
                    for (line in romSig.split("\n")) AppLogger.i(dTag, "  $line")

                    AppLogger.i(dTag, "=== Permissions granted to our app ===")
                    val perms = dadb.shell(
                        "dumpsys package " + pkg +
                            " | grep -E " +
                            "'INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS|" +
                            "BYDAUTO_SPEED|BYDAUTO_GEARBOX|granted=true|granted=false' " +
                            "| head -30 2>&1").allOutput.trim()
                    for (line in perms.split("\n")) AppLogger.i(dTag, "  $line")

                    noteTransportSuccess()
                    AppLogger.i(dTag, "=== FIN dump ===")
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(context, e)
                AppLogger.e(dTag, "dumpSignatureAndPermissions ERREUR", e)
            }
        } // adb-sigdump-thread
    }

    // ── DIAG v1.74: REMOVED (v1.75.1) ──
    // dumpClusterRoutingState() performed a brute-force sendInfo(1000, N)
    // to identify the Freedom display routing. No longer needed: the real cause
    // (OWN_CONTENT_ONLY on the VirtualDisplay created by AutoDisplayService) is
    // identified and fixed by v1.75 (ClusterSurfaceProbe).
    // Removed to avoid any impact on the vehicle.

    // ── TEST 12 / sendClusterScreenSize / resetClusterDisplaySize ──
    // Removed in batch 6 audit: 0 callsite across the codebase after the Diag
    // redesign. The 3 methods (~200 LoC total) were manual sondes from the
    // pre-v0.9.88 era. Restore from git history if a future Diag tab needs them.

    /**
     * Force-stops an application via ADB.
     * Called when the user taps "✕" in the list.
     * Uses "am force-stop" which kills the whole process and releases allfaces.
     */
    fun forceStopApp(context: Context, packageName: String?, callback: Callback?) {
        forceStopAppInternal(context, packageName, callback, sExecutor, true,
            SHELL_IDLE_TIMEOUT_MS)
    }

    /** Service-less teardown: bypass potentially wedged Binder and shared ADB work. */
    fun forceStopAppForBlindEviction(context: Context, packageName: String?, callback: Callback?) {
        forceStopAppInternal(context, packageName, callback, sBlindEvictionExecutor, false,
            PROBE_IDLE_TIMEOUT_MS)
    }

    private fun forceStopAppInternal(context: Context, packageName: String?, callback: Callback?,
                                     executor: ExecutorService, allowTyped: Boolean,
                                     socketTimeoutMs: Int) {
        executor.execute {
            AppLogger.log(TAG, "forceStop $packageName ...")
            // Phase 7: typed daemon path — findTask + removeTask + forceStopPackage.
            // Replaces the 3-step ADB shell chain (dumpsys recents + am task remove
            // + TaskRemover app_process + am force-stop). Falls through to ADB on
            // any failure so semantics are fully preserved for callers.
            if (allowTyped && !DaemonConfig.isLegacyPathEnabled(context) &&
                ProxyClient.isConnected()) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    // ORDER MATTERS, and it is the reverse of what it used to be.
                    //
                    // This used to removeTask() FIRST and kill second, to avoid leaving an
                    // orphan task on display 0. That reasoning only holds when the kill
                    // actually succeeds. When it does not — a persistent / system-uid package
                    // the uid-2000 daemon cannot signal — destroying the task removes the one
                    // thing that would have brought the app back to the centre screen.
                    //
                    // INC-20260815-181820: the eviction correctly parked com.byd.androidauto's
                    // task on display 0, then removeTask() destroyed it, then the kill failed
                    // (pid unchanged before and after). Seven seconds later the tester tapped
                    // Android Auto in the head-unit launcher and system_server, with no task
                    // left to recycle, sent the fresh one to the CLUSTER — proven by the same
                    // launcher call site logging mDisplayId=0 before the session and
                    // mDisplayId=1 after it. Had the parked task survived, the tap would have
                    // found a task with the right affinity already on display 0.
                    //
                    // So: kill, verify, and only then remove the task. On the overwhelming
                    // majority of packages the kill succeeds and behaviour is unchanged.
                    // Location, not just the id: whether keeping the task HELPS depends on
                    // where it is. Review caught the first version keeping it unconditionally,
                    // which regresses the DL3 "free the cluster" path — MainActivity force-stops
                    // the app CURRENTLY ON THE CLUSTER before launching the next one, with no
                    // move to display 0 first, precisely because moveTaskToDisplay is stripped
                    // there. Keeping that task leaves the cluster occupied and the next launch
                    // lands split-screen: INC-20260621-130238's NPE. Same round trip, one more
                    // field.
                    val locations: List<TaskLocation> = findTaskLocationsForEviction(packageName)
                    ProxyClient.forceStopPackage(packageName, 0)
                    val verification = StringBuilder()
                    val killed = verifyForceStop(packageName, verification)
                    val decision = EvictionTaskSetPolicy.decide(killed, locations)
                    removeTypedTasks(decision.taskIdsToRemove)
                    val dt = SystemClock.elapsedRealtime() - t0
                    if (killed) {
                        AppLogger.log(TAG, "forceStopApp typed verified (" + dt + "ms): " +
                            packageName + " tasks=" + decision.taskIdsToRemove)
                        callback?.onEvictionOutcome(decision.outcome)
                        callback?.onSuccess("force-stop OK (typed, verified)")
                    } else {
                        val detail = if (verification.isEmpty())
                            "process still alive after force-stop"
                        else verification.toString().trim()
                        // A task on display 0 is the app's way home — keep it, that is the
                        // whole point of this inversion. A task still on the CLUSTER is the
                        // opposite: it occupies the display the caller is trying to free, so it
                        // must go, exactly as before this change. ABSENT/UNKNOWN keep too: a
                        // failed lookup is not evidence, and destroying on one is the original
                        // defect.
                        AppLogger.w(TAG, "forceStopApp verification failed for " +
                            packageName + ": " + detail + " — removed non-default tasks " +
                            decision.taskIdsToRemove + ", outcome=" +
                            decision.outcome)
                        callback?.onEvictionOutcome(decision.outcome)
                        callback?.onError(detail)
                    }
                    return@execute
                } catch (t: Throwable) {
                    if (t is InterruptedException) {
                        Thread.currentThread().interrupt()
                        callback?.onError("interrupted")
                        return@execute
                    }
                    val dt = SystemClock.elapsedRealtime() - t0
                    AppLogger.w(TAG, "forceStopApp typed failed after " + dt +
                        "ms, falling back to ADB: " + t.message)
                    // fall through to ADB path below
                }
            }
            var legacyLocations: List<TaskLocation> =
                Collections.singletonList(TaskLocation.unknown())
            try {
                connect(context, socketTimeoutMs).use { dadb ->
                    try {
                        val activities = dadb.shell(
                            "dumpsys activity activities 2>/dev/null").allOutput
                        legacyLocations = LegacyTaskLocationParser.parseAll(activities, packageName)
                    } catch (locationError: Throwable) {
                        AppLogger.w(TAG, "legacy task-location probe failed for " + packageName +
                            ": " + locationError.message)
                    }
                    // Kill first. Task deletion happens only after verification below, from the
                    // exact package/task pairs already returned by LegacyTaskLocationParser.
                    val r = dadb.shell("am force-stop $packageName 2>&1; echo STOPPED")
                    val out = r.allOutput.trim()
                    noteTransportSuccess()
                    AppLogger.log(TAG, "am force-stop $packageName -> $out")
                    if (callback != null) {
                        if (out.contains("STOPPED") || out.isEmpty()) {
                            val verification = StringBuilder()
                            val killed = verifyForceStopViaAdb(dadb, packageName, verification)
                            val decision = EvictionTaskSetPolicy.decide(killed, legacyLocations)
                            removeLegacyTasks(dadb, context, decision.taskIdsToRemove)
                            if (killed) {
                                callback.onEvictionOutcome(decision.outcome)
                                callback.onSuccess("force-stop OK (ADB, verified)")
                            } else {
                                callback.onEvictionOutcome(decision.outcome)
                                callback.onError(verification.toString().trim())
                            }
                        } else {
                            val decision = EvictionTaskSetPolicy.decide(false, legacyLocations)
                            removeLegacyTasks(dadb, context, decision.taskIdsToRemove)
                            callback.onEvictionOutcome(decision.outcome)
                            callback.onError(out)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                noteTransportFailure(context, e)
                val msg = e.javaClass.simpleName + ": " + e.message
                AppLogger.e(TAG, "forceStopApp ERREUR", e)
                if (callback != null) {
                    callback.onEvictionOutcome(
                        EvictionTaskSetPolicy.decide(false, legacyLocations).outcome)
                    callback.onError(msg)
                }
            }
        } // adb-forcestop-thread
    }

    // LOT 4 — captureClusterDisplay removed: dead code (0 caller across the
    // codebase, only referenced via reflection comment in AppLogger cleanup).
    // The cluster preview is now sourced from the mirror surface (no screencap).

    private fun safeOut(s: String?): String {
        if (s == null) return "(null)"
        val trimmed = s.trim()
        return if (trimmed.isEmpty()) "(empty)" else trimmed
    }

    private fun verifyForceStopViaAdb(dadb: Dadb, pkg: String?, sb: StringBuilder): Boolean {
        try {
            val pids = dadb.shell("pidof $pkg 2>/dev/null || true").allOutput.trim()
            if (pids.isEmpty()) {
                sb.append("verified killed\n")
                return true
            }
            if (!pids.matches(Regex("[0-9]+(?:\\s+[0-9]+)*"))) {
                sb.append("WARN: unexpected pidof output: ").append(pids).append("\n")
                return false
            }
            AppLogger.w(TAG, "ADB force-stop ineffective for " + pkg +
                " (pids=" + pids + ") - escalating kill -9")
            sb.append("WARN: still alive, pids=").append(pids).append("\n")
            dadb.shell("kill -9 " + pids.replace(Regex("\\s+"), " ") + " 2>/dev/null || true")
            Thread.sleep(200)
            val remaining = dadb.shell("pidof $pkg 2>/dev/null || true").allOutput.trim()
            if (remaining.isEmpty()) {
                sb.append("verified killed after escalation\n")
                return true
            }
            sb.append("WARN: still alive after kill -9, pids=").append(remaining).append("\n")
            return false
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            AppLogger.w(TAG, "verifyForceStopViaAdb(" + pkg + ") failed: " + error.message)
            sb.append("WARN: ADB verification failed: ").append(error.message).append("\n")
            return false
        }
    }

    @Throws(ProxyClient.ProxyException::class)
    private fun findTaskLocationsForEviction(packageName: String?): List<TaskLocation> {
        try {
            val activities = ProxyClient.runShell("dumpsys activity activities 2>/dev/null")
            val parsed = LegacyTaskLocationParser.parseAll(activities, packageName)
            for (location in parsed) {
                if (location.status == TaskLocation.Status.FOUND) {
                    return parsed
                }
            }
        } catch (error: Throwable) {
            AppLogger.w(TAG, "typed multi-task probe failed for " + packageName + ": " +
                error.message)
        }
        return Collections.singletonList(ProxyClient.findTaskLocationForPackage(packageName))
    }

    @Throws(Exception::class)
    private fun removeTypedTasks(taskIds: List<Int?>) {
        var first: Exception? = null
        for (taskId in taskIds) {
            if (taskId == null || taskId <= 0) continue
            try {
                ProxyClient.removeTask(taskId)
            } catch (error: Exception) {
                if (first == null) first = error
            }
        }
        if (first != null) throw first
    }

    private fun removeLegacyTasks(dadb: Dadb, context: Context, taskIds: List<Int?>?) {
        if (taskIds == null || taskIds.isEmpty()) return
        val apkPath = context.packageCodePath
        for (taskId in taskIds) {
            if (taskId == null || taskId <= 0) continue
            try {
                dadb.shell("am task remove " + taskId + " 2>/dev/null; " +
                    "export CLASSPATH=" + apkPath + "; " +
                    "/system/bin/app_process64 -Xnoimage-dex2oat /system/bin " +
                    "com.byd.dashcast.proxy.daemon.TaskRemover \"" + taskId +
                    "\" 2>/dev/null; true")
            } catch (error: Throwable) {
                AppLogger.w(TAG, "legacy removeTask " + taskId + " failed: " + error.message)
            }
        }
    }

    /**
     * Phase 4d.1 verification helper — after a typed forceStopPackage call,
     * queries the daemon for surviving PIDs of `pkg`. Logs a WARN line
     * if the kill was ineffective so we can spot silently-failing
     * IActivityManager.forceStopPackage invocations in device logs (root cause
     * of "Waze stays on display 0 after restoreBydOnCluster typed ok" in 179).
     */
    private fun verifyForceStop(pkg: String?, sb: StringBuilder): Boolean {
        try {
            val pids = ProxyClient.getPidsByPackage(pkg)
            if (pids != null && pids.trim().isNotEmpty()) {
                val alive = pids.trim()
                AppLogger.w(TAG, "beta force-stop ineffective for " + pkg +
                    " (pids=" + alive + ") — escalating kill -9")
                sb.append("  WARN: still alive, pids=").append(alive).append("\n")
                // v1.2.9 (Bug 1/2 défense en profondeur) : si IActivityManager
                // .forceStopPackage a échoué silencieusement (cas connu BYD AUTO
                // ROM avec certaines apps système-like), escalader avec kill -9
                // sur les PIDs survivants via le daemon (uid=2000, droit kill
                // sur process même uid).
                try {
                    val killCmd = "kill -9 " + alive.replace(Regex("\\s+"), " ")
                    ProxyClient.runShell(killCmd)
                    sb.append("  escalated: ").append(killCmd).append("\n")
                    // Petit délai pour laisser le kernel libérer les PIDs avant re-check.
                    try { Thread.sleep(200) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    val pids2 = ProxyClient.getPidsByPackage(pkg)
                    if (pids2 != null && pids2.trim().isNotEmpty()) {
                        AppLogger.w(TAG, "verifyForceStop: " + pkg +
                            " STILL alive after kill -9 (pids=" + pids2.trim() + ")")
                        sb.append("  WARN: still alive after kill -9, pids=")
                            .append(pids2.trim()).append("\n")
                        return false
                    } else {
                        AppLogger.i(TAG, "verifyForceStop: $pkg killed after escalation ✓")
                        sb.append("  verified killed after escalation\n")
                        return true
                    }
                } catch (escalateError: Throwable) {
                    AppLogger.w(TAG, "verifyForceStop: kill -9 escalation failed for " +
                        pkg + ": " + escalateError.message)
                    sb.append("  WARN: escalation failed: ")
                        .append(escalateError.message).append("\n")
                    return false
                }
            } else {
                sb.append("  verified killed\n")
                return true
            }
        } catch (t: Throwable) {
            // Verification failures are reported to the caller, which decides whether to continue.
            AppLogger.w(TAG, "verifyForceStop($pkg) threw: " + t.message)
            return false
        }
    }
}
