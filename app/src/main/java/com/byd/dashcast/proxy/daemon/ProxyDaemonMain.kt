package com.byd.dashcast.proxy.daemon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.FileObserver
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.Surface

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.ProxyBootstrapPolicy
import com.byd.dashcast.proxy.SystemContextHelper
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.ACTION_PROXY_CONNECTED
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.DESCRIPTOR
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.EXTRA_BINDER
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_AAOS_HAL_PROBE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_AUTOCONTAINER_REGISTER_CALLBACK
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO2
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_AUTOCONTAINER_SEND_INFO_RESULT
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CANCEL_FISSION_WATCHDOG
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_BATCH
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_INSTRUMENT_BYTES
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_INSTRUMENT_GET
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_INSTRUMENT_INT
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_LISTEN_CLEAR
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_LISTEN_DRAIN
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_LISTEN_MARK
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_LISTEN_START
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_NAVI_STATUS
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_SETTING_DOUBLE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_SETTING_GET
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CAN_SETTING_INT
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CLEAN_FISSION_STACKS
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_CREATE_VIRTUAL_DISPLAY
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_EXEC
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_FIND_TASK_FOR_PACKAGE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_FIND_TASK_LOCATION
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_FISSION_GET_AUTOCAR_DISPLAY
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_FORCE_STOP_PACKAGE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_GET_PIDS
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_LAUNCH_AND_FORCE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_MOVE_AND_RESIZE
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_PING
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_PROBE_PHASE4
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_PROJECTION_TRACE_DRAIN
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_PROJECTION_TRACE_START
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_READ_FILE_CHUNK
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_RELEASE_VIRTUAL_DISPLAY
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_REMOVE_TASK
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_SET_OVERSCAN
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract.TXN_WHOAMI
import com.byd.dashcast.system.CanBatchOperation

import org.lsposed.hiddenapibypass.HiddenApiBypass

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * ProxyDaemonMain — the uid=2000 daemon that **DOES** things; entry point for the Beta Engine
 * Component A daemon (v1.1.6+).
 *
 * ### The two-daemon boundary (read this before touching either daemon)
 * DashCast drives the instrument cluster through **two** uid-2000 helper processes. They are
 * not interchangeable and they do not share a binder:
 *  - **`ProxyDaemonMain` (this object) — "DOES things".** A stateless command executor:
 *    shell ([TXN_EXEC]) plus typed one-shot verbs (launchAndForce, moveAndResize, setOverscan,
 *    autoContainerSendInfo…). It owns no long-lived state, so if it dies you simply retry the
 *    command. Its binder is `ProxyClient.getProxyDaemonBinder()`, registered in ServiceManager
 *    as [SERVICE_NAME], and it enforces [ProxyDaemonContract.DESCRIPTOR].
 *  - **[SurfaceDaemon] — "HOLDS things".** A stateful surface/window owner: the in-app preview
 *    mirror *and*, in Layout mode, the per-slot `TYPE_SYSTEM_OVERLAY` windows **ON THE CLUSTER**,
 *    the trusted VirtualDisplays built from their Surfaces, the slot geometry, and touch
 *    injection. If it dies, the graphical state is lost and must be rebuilt. Its binder comes
 *    from `DaemonBinderResolver.surfaceDaemonBinder()` (ServiceManager name `byd_mirror_daemon`)
 *    and it enforces a different DESCRIPTOR.
 *
 * Practical triage rule: **a failed command → ProxyDaemon; a black or frozen surface →
 * SurfaceDaemon**. Never pair one daemon's DESCRIPTOR with the other's binder — the receiving
 * `enforceInterface` rejects it and the transaction silently does nothing.
 *
 * Started by [com.byd.dashcast.proxy.ProxyClient] via `app_process64` over a local-ADB pairing
 * session, so the JVM inherits the `shell` UID (2000) of the ADB connection.
 *
 * Since Android 10+ SELinux denies `untrusted_app`→`shell` `unix_stream_socket connectto`
 * (the 1.1.5 failure mode), this version does NOT expose a `LocalServerSocket`. Instead it
 * follows the pattern used by OpenBYD and our own [SurfaceDaemon]:
 *  1. Acquire a system [Context] via reflective `ActivityThread.systemMain().getSystemContext()`.
 *  2. Publish a [Binder] subclass implementing the `com.byd.dashcast.proxy.daemon.IProxyDaemon`
 *     contract.
 *  3. Broadcast [ACTION_PROXY_CONNECTED] targeted at the app package with the binder wrapped in
 *     a [BinderParcelable] extra.
 *  4. Enter [Looper.loop] to keep the binder pool alive.
 *
 * The wire protocol on top of [Binder.transact] is a tiny fixed set of transactions identified
 * by integer codes:
 *  - [TXN_PING]   — no args → `long` epoch_ms
 *  - [TXN_WHOAMI] — no args → `int uid, int pid, String ver, String instance`
 *  - [TXN_EXEC]   — `String cmd` → `int exit, String combinedOutput`
 */
@SuppressLint("StaticFieldLeak") // system context, daemon process-scoped, safe
object ProxyDaemonMain {

    // Wire-protocol constants (DESCRIPTOR, TXN_*, ACTION_PROXY_CONNECTED, EXTRA_BINDER)
    // live in ProxyDaemonContract and are imported above.

    /** Protocol version reported by [TXN_WHOAMI]. Bump on any wire-incompatible change.
     *  v3 (build 235): adds TXN_CREATE_VIRTUAL_DISPLAY / TXN_RELEASE_VIRTUAL_DISPLAY.
     *  v7 (v1.2.63-beta): adds PID-file + trigger-file rebroadcast plumbing.
     *  v8 (v1.2.70-beta): daemon hardening (OOM protection, atomic PID lock, self-heal).
     *  v10 (v1.4.7-beta): adds TXN_CAN_NAVI_STATUS / TXN_CAN_INSTRUMENT_INT / TXN_CAN_INSTRUMENT_BYTES.
     *  v11 (v1.4.11-beta): adds TXN_CAN_SETTING_INT (BYDAutoSettingDevice, required for HUD activation).
     *  v12 (v1.6.69-beta): adds TXN_CAN_INSTRUMENT_GET / TXN_CAN_SETTING_GET (privileged HUD/nav reads).
     *  v13 (v1.6.73-beta): adds TXN_CAN_LISTEN_START / TXN_CAN_LISTEN_DRAIN (BYD setting push-feedback listener).
     *  v14 (v1.6.74-beta): adds TXN_AAOS_HAL_PROBE (automotive display proxy HAL reachability test).
     *  v15 (v1.6.89-beta): adds TXN_CAN_LISTEN_CLEAR (reset push-feedback log + last-known map).
     *  v16 (v1.6.97-beta): adds TXN_CAN_LISTEN_MARK (timestamped user ground-truth marker) + timestamps.
     *  v17 (v1.6.98-beta): adds TXN_CAN_SETTING_DOUBLE (HUD angle) + TXN_READ_FILE_CHUNK (pull raw logcat).
     *  v18: adds TXN_FIND_TASK_LOCATION (task identity + display with UNKNOWN semantics).
     *  v19: adds TXN_CAN_BATCH (ordered grouped HUD writes in one Binder round-trip).
     *  v24: TXN_CAN_BATCH appliedCount stops at the first non-zero native SDK result.
     *  v20: adds TXN_AUTOCONTAINER_SEND_INFO_RESULT (preserves native sendInfo result codes).
     *  v21: adds TXN_CANCEL_FISSION_WATCHDOG (teardown cannot race post-launch re-anchoring).
     *  v22: adds TXN_AUTOCONTAINER_SEND_INFO2 (raw sendInfo2 byte[] channel — NaviInfo HUD injection).
     *  v23: adds TXN_FISSION_GET_AUTOCAR_DISPLAY (read-only FissionHostSvc registry probe, DL3),
     *  TXN_AUTOCONTAINER_REGISTER_CALLBACK (arms serviceDied/receivedX logging, diagnostic-only,
     *  never called before this release) and TXN_PROJECTION_TRACE_START/DRAIN (60s registry sampler
     *  around a normal projection cycle). All three read-only or purely observational.
     *  v25: WHOAMI adds a per-process instance nonce for race-free hung-daemon recovery.
     *  Purely additive — old clients keep working unchanged. */
    private const val PROTOCOL_VERSION = "25"

    /** Process name shown in `ps` after the JVM's `setArgV0` runs. */
    private const val PROC_NAME = "dashcast_proxy"

    /** PID file written at startup, removed at shutdown via the JVM hook.
     *  Used by the bootstrap script's fast path to detect a surviving daemon
     *  after app restart (setsid'd daemon outlives the app process) and ask
     *  for a binder rebroadcast instead of paying the ~1 s app_process cost. */
    private const val PID_FILE = "/data/local/tmp/dashcast_proxy.pid"

    private const val STARTUP_LOCK_FILE = "/data/local/tmp/dashcast_proxy_startup.lock"

    private var sStartupLock: ProxyDaemonStartupLock? = null

    /** Random identity for this exact daemon process, paired with the PID for safe recovery. */
    private const val INSTANCE_FILE = "/data/local/tmp/dashcast_proxy_instance"

    private val INSTANCE_TOKEN: String = UUID.randomUUID().toString().replace("-", "")

    /** Trigger file watched via [FileObserver] by the running daemon.
     *  The bootstrap script (running as uid 2000 shell) touches this file to
     *  ask the daemon to re-emit [ACTION_PROXY_CONNECTED] so a freshly
     *  restarted app gets the binder without restarting the daemon. */
    private const val TRIGGER_FILE = "/data/local/tmp/dashcast_proxy.trigger"

    /** APK versionCode written at startup so the bootstrap script can detect a
     *  stale daemon that survived an OTA update (REBROADCAST would otherwise
     *  reconnect to the old, class-loaded binary). Deleted by shutdown hook. */
    private const val VERSION_FILE = "/data/local/tmp/dashcast_proxy_ver"

    /** App package that receives the [ProxyDaemonContract.ACTION_PROXY_CONNECTED] broadcast. */
    private const val TARGET_PKG = "com.byd.dashcast"

    /** Set in [main] once the system context is acquired, so [ProxyBinder] can hand it to
     *  [Phase4Probes] without re-acquiring. */
    @SuppressLint("StaticFieldLeak") // system context, daemon process-scoped, safe
    @Volatile private var sSystemContext: Context? = null

    /** Permission-bypass wrapper around [sSystemContext].
     *  Set immediately after [sSystemContext]; used by [CanWriteVerbs] so that
     *  `BYDAutoInstrumentDevice.set()` passes the SDK's internal permission check.
     *  Null only if [acquireSystemContext] failed (daemon already exits in that case). */
    @SuppressLint("StaticFieldLeak") // wrapped system context, daemon process-scoped, safe
    @Volatile private var sWrappedContext: Context? = null

    /** Strong reference to the trigger [FileObserver], kept alive for the
     *  lifetime of the daemon. `FileObserver` is delivered via a
     *  background thread internal to Android — no explicit Looper needed. */
    @Volatile private var sTriggerObserver: FileObserver? = null

    /** Cached binder + intent re-used by [emitBroadcast] so a USR-1-style rebroadcast does not
     *  need to rebuild any state. */
    @Volatile private var sBinder: ProxyBinder? = null

    /** ServiceManager name under which the proxy binder is (best-effort) registered so the app can
     *  authenticate the PROXY_CONNECTED broadcast binder against it — only uid-2000/system can
     *  addService (SELinux blocks untrusted apps). Public so ProxyClient can cross-check. */
    const val SERVICE_NAME = "byd_proxy_daemon"

    /** Our app's uid. Resolution is retried from the Binder gate after transient PM failures. */
    @Volatile private var sAppUid = -1

    /** True if `uid` may drive the privileged ProxyBinder verbs. */
    private fun isAllowedCaller(uid: Int): Boolean {
        var appUid = sAppUid
        if (appUid < 0) appUid = resolveAppUid()
        return DaemonCallerPolicy.isAllowed(uid, Process.myUid(), appUid)
    }

    @Suppress("DEPRECATION")
    private fun resolveAppUid(): Int {
        val context = sSystemContext ?: return -1
        return try {
            val resolved = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
            sAppUid = resolved
            resolved
        } catch (t: Throwable) {
            log("app uid resolution failed; privileged app calls remain denied: $t")
            -1
        }
    }

    /** Best-effort registration of the proxy binder in the global ServiceManager (uid-2000/system
     *  only). Enables the app-side authenticity cross-check; broadcast delivery is unaffected, so
     *  a failure here is harmless (the app falls back to the broadcast binder). */
    private fun registerInServiceManager(binder: IBinder?) {
        if (binder == null) return
        try {
            val sm = Class.forName("android.os.ServiceManager")
            try {
                val add = sm.getDeclaredMethod("addService",
                    String::class.java, IBinder::class.java,
                    Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                add.isAccessible = true
                add.invoke(null, SERVICE_NAME, binder, false, 0)
            } catch (nsme: NoSuchMethodException) {
                val add = sm.getDeclaredMethod("addService",
                    String::class.java, IBinder::class.java)
                add.isAccessible = true
                add.invoke(null, SERVICE_NAME, binder)
            }
            log("ServiceManager.addService($SERVICE_NAME) OK")
        } catch (t: Throwable) {
            log("ServiceManager.addService failed (broadcast only): $t")
        }
    }

    @Suppress("DEPRECATION")
    private fun prepareStandaloneMainLooper() {
        Looper.prepareMainLooper()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // v1.8.24 — unlock hidden APIs for THIS process before anything else touches
            // reflection. Deliberately the very first statement: Phase4TaskVerbs/Phase4Probes/
            // CanWriteVerbs etc. all reflect into android.app.*/android.hardware.bydauto.* classes,
            // and setHiddenApiExemptions is per-ART-VM state — running it after any of those verbs
            // have already resolved (and cached, or given up on) a Method would be too late for
            // that call site. SurfaceDaemon and ClusterMirrorManager.kt each already unlock
            // hidden APIs in THEIR OWN process, but that never covered this one, which is the
            // process that actually makes the calls logged as "NoSuchMethodException" throughout
            // Phase4TaskVerbs — a class demonstrably present in this ROM's own services.jar/
            // framework.jar (setDisplayToSingleTaskInstance, setCustomTaskWindowingMode) is exactly
            // the symptom of hidden-API filtering: a method invisible to getMethod() despite
            // existing in the compiled bytecode.
            hiddenApiSelfTest()
            renameProcess()
            // v1.2.70 hardening (Couche 3): tell the Linux OOM killer to
            // treat us as critically important. uid=2000 (shell) can write
            // to its own /proc/self/oom_score_adj. -900 is just above the
            // system_server reserved -1000 / -900 band; any value below 0
            // protects us from typical low-memory kills triggered by foreground
            // app churn. Best-effort: ignored if /proc is not writable.
            hardenAgainstOom()
            // v1.2.70 hardening: atomic PID-lock via O_CREAT|O_EXCL semantics
            // (Files.createFile). If another daemon already owns the lock we
            // refuse to start so the bootstrap's stale-kill stays the single
            // source of truth for "which PID is canonical".
            if (!acquirePidLock()) {
                log("FATAL: another daemon already holds the PID lock — exiting")
                System.exit(3)
                return
            }
            writeVersionFile()
            if (!writeInstanceFile()) {
                log("FATAL: cannot persist daemon instance marker — exiting")
                System.exit(4)
                return
            }
            releaseStartupLock()
            installPidShutdownHook()
            prepareStandaloneMainLooper()

            val binder = ProxyBinder()
            sBinder = binder
            log("binder ready uid=" + Process.myUid() +
                " pid=" + Process.myPid() +
                " ver=" + PROTOCOL_VERSION)

            val systemContext = acquireSystemContext()
            if (systemContext == null) {
                log("FATAL: no system context — cannot broadcast binder")
                System.exit(2)
                return
            }
            sSystemContext = systemContext
            // Adopt our own package identity (createPackageContext + getPackageName
            // spoof) so BYD SDK surfaces that gate on the calling package — not just
            // the uid — accept our CAN/setting writes. Mirrors OpenBYD 2.2's
            // SystemContext.get(). Falls back to a plain permission-bypass wrap if
            // createPackageContext is unavailable.
            sWrappedContext = SystemContextHelper.adoptIdentity(systemContext)

            // Resolve before publication. A transient failure is retried by isAllowedCaller();
            // until then only system and the daemon itself may use privileged verbs.
            val resolvedAppUid = resolveAppUid()
            if (resolvedAppUid >= 0) log("app uid resolved: $resolvedAppUid")
            // Best-effort ServiceManager registration for the app-side authenticity cross-check.
            registerInServiceManager(binder)

            emitBroadcast()
            installTriggerObserver()
            // v1.2.70 hardening: periodic self-check thread (10s).
            // Re-creates the trigger file if it was deleted (some vendors wipe
            // /data/local/tmp on certain events), re-arms the FileObserver if
            // it died, and self-suicides if our own binder thread is wedged.
            // Suicide is safe: ProxyKeeperService (v1.2.71) or the next app
            // call will re-spawn us within 10s.
            installSelfHealHeartbeat()

            Looper.loop()
        } catch (t: Throwable) {
            log("FATAL: $t")
            t.printStackTrace()
            System.exit(1)
        }
    }

    /** Build and send the [ACTION_PROXY_CONNECTED] broadcast. Idempotent —
     *  may be invoked any number of times after the initial startup, every call
     *  re-emits the binder so a freshly registered receiver in a restarted app
     *  picks it up without a daemon respawn. */
    private fun emitBroadcast() {
        val ctx = sSystemContext
        val binder = sBinder
        if (ctx == null || binder == null) {
            log("emitBroadcast skipped: context or binder missing")
            return
        }
        val intent = Intent(ACTION_PROXY_CONNECTED)
            .setPackage(TARGET_PKG)
            .putExtra(EXTRA_BINDER, BinderParcelable(binder))
            // FLAG_INCLUDE_STOPPED_PACKAGES so the app receives the broadcast
            // even right after a force-stop — important for the bootstrap flow
            // where the receiver was just dynamically registered.
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        ctx.sendBroadcast(intent)
        log("broadcast sent: $ACTION_PROXY_CONNECTED → $TARGET_PKG")
    }

    /**
     * v1.8.24 — self-proving hidden-API unlock. Logs whether a method already known to be
     * blocked on this exact car (`IActivityTaskManager$Stub$Proxy.setDisplayToSingleTaskInstance`)
     * is visible to `getMethod()` BEFORE and AFTER calling
     * [HiddenApiBypass.setHiddenApiExemptions], so the very next bug report either confirms
     * or refutes the whole hypothesis with a plain log line — no guesswork needed on the next pull.
     *
     * Uses the same scoped exemption list as the existing `unlockHiddenApis()` precedent in
     * `SurfaceDaemon`/`ClusterMirrorManager` (`Landroid/`, `Lcom/android/`, `Ljava/lang/`) — every
     * reflected target across the daemon (android.app.*, android.view.*, android.hardware.bydauto.*)
     * falls under `Landroid/`; nothing observed so far needs a broader exemption. Uses the library's
     * `HiddenApiBypass` class specifically (backed by `sun.misc.Unsafe`, documented stable on
     * API 10+ without touching internal ART structures) — not `LSPass`, which the library's own
     * README says cannot reach core platform API, exactly what every target here is.
     *
     * Read-only: only calls `getMethod()`, never `invoke()`. Cannot itself break anything even if
     * the whole hypothesis is wrong.
     */
    private fun hiddenApiSelfTest() {
        log("hiddenapi BEFORE: " + probeHiddenMethodVisibility())
        try {
            HiddenApiBypass.setHiddenApiExemptions("Landroid/", "Lcom/android/", "Ljava/lang/")
            log("hiddenapi: setHiddenApiExemptions OK")
        } catch (t: Throwable) {
            log("hiddenapi: setHiddenApiExemptions FAILED: $t")
        }
        log("hiddenapi AFTER: " + probeHiddenMethodVisibility())
    }

    /** @return "VISIBLE (...)" / "HIDDEN (NoSuchMethodException)" / "INCONCLUSIVE ..." — never throws. */
    private fun probeHiddenMethodVisibility(): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)
            val m = iAtm!!.javaClass.getMethod("setDisplayToSingleTaskInstance",
                Int::class.javaPrimitiveType)
            "VISIBLE ($m)"
        } catch (nsme: NoSuchMethodException) {
            "HIDDEN (NoSuchMethodException)"
        } catch (t: Throwable) {
            "INCONCLUSIVE " + t.javaClass.simpleName + ": " + t.message
        }
    }

    /** v1.2.70 hardening: lower our OOM score so Linux's low-memory killer
     *  reaches for foreground apps before us. -900 sits just above the framework
     *  reserved range (-1000..-900 used by system_server etc.).
     *
     *  Best-effort, and it is *expected* to fail on DiLink 3: that ROM's SELinux policy
     *  denies the shell domain write access to proc_oom_adj, so the open itself returns EACCES
     *  (79 of 80 DiLink 3 reports in the corpus; every DiLink 4 / 5.0 / 5.1 / AAOS report
     *  succeeds). The earlier claim here that "uid=2000 can always write to its own
     *  /proc/self/oom_score_adj" was wrong. The daemon runs fine without it — it only loses a
     *  priority hint — so this is logged as a state, not as a failure to chase. */
    private fun hardenAgainstOom() {
        try {
            FileOutputStream("/proc/self/oom_score_adj").use { fos ->
                fos.write("-900".toByteArray())
                log("oom_score_adj=-900 set")
            }
        } catch (t: Throwable) {
            log("oom_score_adj not settable, continuing without the priority hint" +
                " (expected on DiLink 3 — SELinux denies shell): " + t)
        }
    }

    /** v1.2.70 hardening: atomic PID-lock acquisition.
     *
     *  Strategy:
     *   1. Read any existing PID file.
     *   2. If the recorded PID is still alive AND points to a `dashcast_proxy` process
     *      (verified via `/proc/PID/cmdline`), refuse to start — a canonical daemon
     *      already exists.
     *   3. Otherwise (stale file or no file), atomically rewrite it with our own PID
     *      and continue.
     *
     *  This replaces the broken shell-side `flock` (see v1.2.69 cascade) with an in-JVM
     *  check that has no toybox/util-linux dependency. */
    private fun acquirePidLock(): Boolean {
        var startupLock: ProxyDaemonStartupLock? = null
        try {
            startupLock = ProxyDaemonStartupLock.tryAcquire(File(STARTUP_LOCK_FILE))
            if (startupLock == null) return false
            val pidFile = File(PID_FILE)
            if (pidFile.exists()) {
                // File already existed — check if a live daemon owns it.
                val existing = readSmallFile(pidFile).trim()
                if (existing.isNotEmpty()) {
                    var otherPid = -1
                    try {
                        otherPid = existing.toInt()
                    } catch (ignore: NumberFormatException) {
                    }
                    if (otherPid > 0 && otherPid != Process.myPid() && isLiveDaemon(otherPid)) {
                        startupLock.close()
                        return false
                    }
                }
            }
            // PID and nonce are one startup transaction. Remove only after the cross-process
            // lock proves no peer can concurrently claim either marker.
            try { File(INSTANCE_FILE).delete() } catch (ignore: Throwable) {}
            startupLock.publishPid(pidFile, Process.myPid().toString())
            sStartupLock = startupLock
            return true
        } catch (t: Throwable) {
            if (startupLock != null) {
                try { startupLock.close() } catch (ignore: Throwable) {}
            }
            log("acquirePidLock error: $t")
            return false
        }
    }

    private fun releaseStartupLock() {
        val lock = sStartupLock
        sStartupLock = null
        if (lock == null) return
        try {
            lock.close()
        } catch (error: Throwable) {
            log("releaseStartupLock failed: $error")
        }
    }

    @Throws(Exception::class)
    private fun readSmallFile(f: File): String {
        FileInputStream(f).use { input ->
            ByteArrayOutputStream().use { baos ->
                val buf = ByteArray(256)
                var n: Int
                while (input.read(buf).also { n = it } > 0) baos.write(buf, 0, n)
                return baos.toString()
            }
        }
    }

    /** True iff `/proc/PID/cmdline` exists and contains "dashcast_proxy". */
    private fun isLiveDaemon(pid: Int): Boolean {
        return try {
            val cmdline = File("/proc/$pid/cmdline")
            if (!cmdline.exists()) return false
            val s = readSmallFile(cmdline)
            s.contains(PROC_NAME)
        } catch (t: Throwable) {
            false
        }
    }

    private fun writeVersionFile() {
        try {
            FileOutputStream(File(VERSION_FILE)).use { fos ->
                fos.write(BuildConfig.VERSION_CODE.toString().toByteArray())
            }
        } catch (ignore: Throwable) {
        }
    }

    private fun writeInstanceFile(): Boolean {
        return try {
            ProxyInstanceMarker.ensureOwned(File(INSTANCE_FILE), INSTANCE_TOKEN)
        } catch (error: Throwable) {
            log("writeInstanceFile failed: $error")
            false
        }
    }

    /** Remove the PID file on JVM shutdown. Pure best-effort — a SIGKILL'd
     *  daemon will leave a stale file behind, which the bootstrap script
     *  detects via `/proc/$PID/comm` sanity check. */
    private fun installPidShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(object : Thread("pid-cleanup") {
                override fun run() {
                    var cleanupLock: ProxyDaemonStartupLock? = null
                    try {
                        cleanupLock = ProxyDaemonStartupLock.tryAcquire(File(STARTUP_LOCK_FILE))
                    } catch (ignore: Throwable) {}
                    // A successor is publishing PID+nonce. Never make a cleanup decision from
                    // markers that can change between our ownership check and delete.
                    if (cleanupLock == null) return
                    try {
                        // Only clean up if THIS process still owns the lock. A duplicate daemon
                        // that lost the race and suicided (healPidLock -> System.exit) must NOT
                        // delete the SURVIVOR's PID file — otherwise a fresh bootstrap sees no
                        // lock and spawns a spurious third daemon during the ~10s until the
                        // survivor rewrites it.
                        var owner = -1
                        try { owner = readSmallFile(File(PID_FILE)).trim().toInt() }
                        catch (ignore: Throwable) {}
                        if (owner != Process.myPid()) return
                        try { File(PID_FILE).delete() } catch (ignore: Throwable) {}
                        try { File(TRIGGER_FILE).delete() } catch (ignore: Throwable) {}
                        try { File(VERSION_FILE).delete() } catch (ignore: Throwable) {}
                        try {
                            if (INSTANCE_TOKEN == readSmallFile(File(INSTANCE_FILE)).trim()) {
                                File(INSTANCE_FILE).delete()
                            }
                        } catch (ignore: Throwable) {}
                    } finally {
                        try { cleanupLock.close() } catch (ignore: Throwable) {}
                    }
                }
            })
        } catch (ignore: Throwable) {
            // shutdown hooks may be disallowed in some app_process contexts —
            // not fatal, stale file is recoverable.
        }
    }

    /** Watch [TRIGGER_FILE] for CREATE/MODIFY events. The bootstrap script touches that file to
     *  ask us to re-broadcast the binder — used when the app process restarts but our daemon
     *  survived (setsid).
     *
     *  v1.3.10: watches the PARENT DIRECTORY instead of the file itself. On Android 12 (DL5,
     *  uid=2000, app_process64), inotify watches on a specific file inode are silently dropped
     *  when the shell creates the file with O_TRUNC (which may allocate a new inode). A
     *  directory-level watch captures CREATE/CLOSE_WRITE on any child including our trigger file,
     *  regardless of inode recycling. */
    // NewApi: FileObserver(File, Int) is API 29 and minSdk is 28. This is NOT new here — the
    // Java version made the same call and lint simply never reported it. The whole body is inside
    // `try { } catch (Throwable)`, so on an API-28 unit (DiLink 3) the constructor raises
    // NoSuchMethodError, it is caught, and "installTriggerObserver failed" is logged; the daemon
    // then runs on the 1 Hz mtime poll in installSelfHealHeartbeat, which the code already calls
    // "the PRIMARY recovery path". Suppressed rather than guarded because adding a real
    // Build.VERSION branch would CHANGE behaviour (it would arm inotify on DL3 for the first
    // time), and that belongs in its own commit, not in a port.
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun installTriggerObserver() {
        try {
            // Ensure the trigger file and its parent directory exist.
            val triggerFile = File(TRIGGER_FILE)
            val parentDir = triggerFile.parentFile
            try { triggerFile.createNewFile() } catch (ignore: Throwable) {}
            // v1.3.10: watch the DIRECTORY (more robust on Android 12).
            // onEvent path argument will be just the filename, not full path.
            val triggerName = triggerFile.name
            val observer = object : FileObserver(parentDir,
                MODIFY or CREATE or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (triggerName == path) {
                        log("rebroadcast trigger received via dir-watch (event=$event)")
                        emitBroadcast()
                    }
                }
            }
            sTriggerObserver = observer
            observer.startWatching()
            log("trigger observer armed on dir $parentDir (filter=$triggerName)")
        } catch (t: Throwable) {
            log("installTriggerObserver failed: $t")
        }
    }

    /** v1.2.70 hardening: periodic self-check thread.
     *
     *  Responsibilities:
     *   - Re-create the trigger file if it was deleted (some vendors wipe `/data/local/tmp`
     *     on certain events).
     *   - Re-arm the [FileObserver] if [sTriggerObserver] is null or was stopped.
     *   - Detect that our PID file was clobbered (another daemon spawned in parallel and won
     *     the race) → suicide so the survivor stays canonical.
     *   - v1.3.9: poll trigger file mtime every 1s as backup for FileObserver unreliability
     *     observed on DL5 (inotify events not delivered in uid=2000 / app_process64 context).
     *
     *  Daemon thread, started as DAEMON so it never blocks JVM shutdown. */
    private fun installSelfHealHeartbeat() {
        val t = object : Thread("dashcast-self-heal") {
            override fun run() {
                // v1.3.9 — record the initial mtime so we only react to
                // FUTURE writes, not the file that was there when we started.
                var lastTriggerMtime = File(TRIGGER_FILE).lastModified()
                var tick = 0
                // AUD-PERF-P4 — cadence ramp.
                //
                // This poll is the PRIMARY recovery path when the FileObserver silently stops
                // delivering on DL5 (see the class doc above), so it is not something to simply
                // slow down. But that inotify failure manifests during bootstrap, and this loop
                // used to run at 1 Hz for the entire life of a process that outlives the app and
                // survives its kills: on a head unit that stays up for days that is ~86 400
                // wakeups and ~86 400 stat() calls PER DAY, forever, for an event that in normal
                // operation arrives over inotify anyway.
                //
                // So: keep full 1 Hz sensitivity through the window the failure appears in, then
                // fall back to the steady-state period below.
                //
                // That steady-state period is NOT a free choice and is deliberately not written
                // here as a literal. REBROADCAST only ever runs against an already-alive same-build
                // daemon -- one always far past RAMP_TICKS -- so it is the steady-state interval,
                // not the ramp interval, that must fit inside the app's rebroadcast wait. Both
                // numbers now live in ProxyBootstrapPolicy and are derived from each other, so the
                // invariant cannot be broken by editing one side. See the doc there for the
                // regression that made this necessary.
                val rampTicks = 60 // ~60 s of 1 Hz polling after daemon start
                val slowPollMs = ProxyBootstrapPolicy.TRIGGER_SLOW_POLL_MS
                while (true) {
                    try {
                        sleep(if (tick < rampTicks) 1_000L else slowPollMs)
                    } catch (ignore: InterruptedException) {
                        return
                    }
                    // v1.3.9 — every tick: poll trigger file mtime.
                    // This is the primary recovery path when FileObserver
                    // silently stops delivering events on DL5.
                    try {
                        val f = File(TRIGGER_FILE)
                        val mtime = f.lastModified()
                        if (mtime != lastTriggerMtime) {
                            lastTriggerMtime = mtime
                            log("trigger poll: mtime changed → rebroadcast")
                            emitBroadcast()
                        }
                    } catch (th: Throwable) { log("trigger poll: $th") }
                    // Full self-heal (file + pid lock) every 10 ticks: ~10 s during the 1 s ramp
                    // and ~30 s at the 3 s steady rate, which is the cadence we want on each side,
                    // so a single divisor covers both and no branch is needed here.
                    if (++tick % 10 == 0) {
                        try { healTriggerFile() } catch (th: Throwable) { log("heal trigger: $th") }
                        try { healPidLock() } catch (th: Throwable) { log("heal pid: $th") }
                        healInstanceFile()
                    }
                }
            }
        }
        t.isDaemon = true
        t.start()
        log("self-heal heartbeat armed (1s poll + 10s heal for 60s, then " +
            (ProxyBootstrapPolicy.TRIGGER_SLOW_POLL_MS / 1000) +
            "s poll + 30s heal)")
    }

    private fun healTriggerFile() {
        val f = File(TRIGGER_FILE)
        if (!f.exists()) {
            try { f.createNewFile(); log("trigger file re-created") } catch (ignore: Throwable) {}
            // v1.3.10: directory watch survives trigger file deletion (watches
            // the parent dir inode, not the file inode). Only re-arm if the
            // observer itself is gone.
        }
        if (sTriggerObserver == null) {
            installTriggerObserver()
        }
    }

    private fun healPidLock() {
        val pidFile = File(PID_FILE)
        if (!pidFile.exists()) {
            // Someone wiped our PID file; rewrite it.
            try {
                FileOutputStream(pidFile).use { fos ->
                    fos.write(Process.myPid().toString().toByteArray())
                }
            } catch (ignore: Throwable) {}
            return
        }
        try {
            val contents = readSmallFile(pidFile).trim()
            var recorded = -1
            try { recorded = contents.toInt() } catch (ignore: NumberFormatException) {}
            if (recorded > 0 && recorded != Process.myPid() && isLiveDaemon(recorded)) {
                // Another daemon stole the lock and is alive — yield to it.
                log("self-heal: lock stolen by pid=$recorded → suicide")
                System.exit(0)
            }
        } catch (ignore: Throwable) {}
    }

    private fun healInstanceFile() {
        try {
            if (ProxyInstanceMarker.ensureOwned(File(INSTANCE_FILE), INSTANCE_TOKEN)) return
            log("self-heal: instance marker belongs to another generation → suicide")
        } catch (error: Throwable) {
            log("self-heal: cannot restore instance marker → suicide: $error")
        }
        System.exit(4)
    }

    /** Reflective hop to obtain a usable system [Context] from inside `app_process`. */
    private fun acquireSystemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread)
            ctx as Context
        } catch (t: Throwable) {
            log("acquireSystemContext failed: $t")
            null
        }
    }

    private fun renameProcess() {
        try {
            val m = Process::class.java.getDeclaredMethod("setArgV0", String::class.java)
            m.invoke(null, PROC_NAME)
        } catch (ignore: Throwable) {
            // not fatal — process keeps its app_process / --nice-name argv[0]
        }
    }

    /**
     * Binder published to the app. Uses raw [Binder.onTransact] (no AIDL codegen needed — keeps
     * the build simple and the wire format obvious).
     */
    internal class ProxyBinder : Binder() {

        init {
            attachInterface(null, DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Caller-identity gate: the binder is now discoverable via ServiceManager (for the
            // app-side authenticity cross-check) and TXN_EXEC runs arbitrary shell as uid 2000, so
            // only the app (+ system / the daemon's own uid) may drive any verb. The per-case
            // enforceInterface below is NOT authentication. If PackageManager is temporarily
            // unavailable, isAllowedCaller retries resolution and fails closed for app callers.
            val callingUid = getCallingUid()
            if (!isAllowedCaller(callingUid)) {
                log("ProxyBinder: rejected transact code=$code from uid=$callingUid")
                reply?.writeException(
                    SecurityException("caller uid $callingUid not permitted"))
                return true
            }
            when (code) {
                TXN_PING -> {
                    data.enforceInterface(DESCRIPTOR)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeLong(System.currentTimeMillis())
                    }
                    return true
                }
                TXN_WHOAMI -> {
                    data.enforceInterface(DESCRIPTOR)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(Process.myUid())
                        reply.writeInt(Process.myPid())
                        reply.writeString(PROTOCOL_VERSION)
                        reply.writeString(INSTANCE_TOKEN)
                    }
                    return true
                }
                TXN_EXEC -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cmd = data.readString()
                    val er = ProxyShell.exec(cmd)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(er.exit)
                        reply.writeString(er.output)
                    }
                    return true
                }
                TXN_PROBE_PHASE4 -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result: String = try {
                        Phase4Probes.runAll(sSystemContext)
                    } catch (t: Throwable) {
                        // Probe harness must never crash the daemon — fall back to a
                        // synthetic single-token error so the client side still parses.
                        "P0=FAIL_OTHER:harness " + t.javaClass.simpleName +
                            " " + (t.message ?: "")
                    }
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeString(result)
                    }
                    return true
                }
                TXN_SET_OVERSCAN -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val l = data.readInt()
                    val t = data.readInt()
                    val r = data.readInt()
                    val b = data.readInt()
                    try {
                        Phase4DisplayVerbs.setOverscan(displayId, l, t, r, b)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        // Surface the real cause to the client so it can fall back to
                        // the shell path with full diagnostic context.
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_GET_PIDS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val pids: String
                    try {
                        pids = Phase4ProcessVerbs.getPidsByPackage(pkg)
                    } catch (t: Throwable) {
                        // Pure-Java /proc scan should never throw, but guard anyway:
                        // surface as a normal exception so the client falls back to shell.
                        reply?.writeException(legacyWrapThrowable(t))
                        return true
                    }
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeString(pids)
                    }
                    return true
                }
                TXN_AUTOCONTAINER_SEND_INFO -> {
                    data.enforceInterface(DESCRIPTOR)
                    val type = data.readInt()
                    val info = data.readInt()
                    val str = data.readString()
                    try {
                        Phase4ProcessVerbs.autoContainerSendInfo(type, info, str)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_AUTOCONTAINER_SEND_INFO_RESULT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val type = data.readInt()
                    val info = data.readInt()
                    val str = data.readString()
                    try {
                        val result = Phase4ProcessVerbs.autoContainerSendInfoResult(type, info, str)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeInt(result)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_AUTOCONTAINER_SEND_INFO2 -> {
                    data.enforceInterface(DESCRIPTOR)
                    val type = data.readInt()
                    val payload = data.createByteArray()
                    try {
                        Phase4ProcessVerbs.autoContainerSendInfo2(type, payload)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_FORCE_STOP_PACKAGE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val userId = data.readInt()
                    try {
                        Phase4ProcessVerbs.forceStopPackage(pkg, userId)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_CREATE_VIRTUAL_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val name = data.readString()
                    val w = data.readInt()
                    val h = data.readInt()
                    val dpi = data.readInt()
                    val vflag = data.readInt()
                    val surface: Surface? = if (data.readInt() != 0)
                        Surface.CREATOR.createFromParcel(data) else null
                    val owner: IBinder? = if (data.dataAvail() > 0)
                        data.readStrongBinder() else null
                    try {
                        val displayId = Phase4DisplayVerbs.createVirtualDisplay(
                            sSystemContext, name, w, h, dpi, surface, vflag, owner)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeInt(displayId)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    } finally {
                        // CREATOR produced a daemon-local wrapper. DisplayManagerService/VD now
                        // owns the producer reference; release this temporary Java/native handle.
                        surface?.release()
                    }
                    return true
                }
                TXN_RELEASE_VIRTUAL_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    try {
                        Phase4DisplayVerbs.releaseVirtualDisplay(displayId)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_LAUNCH_AND_FORCE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val cls = if (data.readInt() != 0) data.readString() else null
                    val did = data.readInt()
                    val w = data.readInt()
                    val h = data.readInt()
                    try {
                        // `!!` reproduces the Java behaviour exactly: launchAndForce declares a
                        // non-null package, so a null off the wire threw NPE inside it, and the
                        // catch below turns that into a Parcel-encodable NullPointerException.
                        val log = Phase4TaskVerbs.launchAndForce(pkg!!, cls, did, w, h)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeString(log)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_MOVE_AND_RESIZE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val did = data.readInt()
                    val l = data.readInt()
                    val t = data.readInt()
                    val r = data.readInt()
                    val b = data.readInt()
                    try {
                        // `!!` — see the note on TXN_LAUNCH_AND_FORCE above.
                        val log = Phase4TaskVerbs.moveAndResize(pkg!!, did, l, t, r, b)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeString(log)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_CLEAN_FISSION_STACKS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val did = data.readInt()
                    try {
                        val log = Phase4TaskVerbs.cleanFissionStacks(did)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeString(log)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_FIND_TASK_FOR_PACKAGE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val taskId = Phase4TaskVerbs.findTaskIdForPackage(pkg)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(taskId)
                    }
                    return true
                }
                TXN_FIND_TASK_LOCATION -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val location = Phase4TaskVerbs.findTaskLocationForPackage(pkg)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(location.status.wireCode)
                        reply.writeInt(location.taskId)
                        reply.writeInt(location.displayId)
                    }
                    return true
                }
                TXN_CANCEL_FISSION_WATCHDOG -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pkg = data.readString()
                    val cancelled = Phase4TaskVerbs.cancelFissionWatchdog(pkg)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(if (cancelled) 1 else 0)
                    }
                    return true
                }
                TXN_REMOVE_TASK -> {
                    data.enforceInterface(DESCRIPTOR)
                    val taskId = data.readInt()
                    try {
                        Phase4TaskVerbs.removeTask(taskId)
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(legacyWrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_NAVI_STATUS -> {
                    data.enforceInterface(DESCRIPTOR)
                    val status = data.readInt()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val rc = CanWriteVerbs.setInt(
                            ctx, CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, status)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_INSTRUMENT_INT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    val value = data.readInt()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val rc = CanWriteVerbs.setInt(ctx, featureId, value)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_INSTRUMENT_BYTES -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    val bytes = data.createByteArray()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val rc = CanWriteVerbs.setBytes(ctx, featureId, bytes ?: ByteArray(0))
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_SETTING_INT -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    val value = data.readInt()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val rc = CanWriteVerbs.settingSetInt(ctx, featureId, value)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_BATCH -> {
                    data.enforceInterface(DESCRIPTOR)
                    val count = data.readInt()
                    try {
                        if (count <= 0 || count > CanBatchOperation.MAX_BATCH_SIZE) {
                            throw IllegalArgumentException("invalid CAN batch size $count")
                        }
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val writer = object : CanBatchOperation.Writer {
                            override fun setNaviStatus(status: Int): Int =
                                CanWriteVerbs.setInt(
                                    ctx, CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, status)

                            override fun setInstrumentInt(featureId: Int, value: Int): Int =
                                CanWriteVerbs.setInt(ctx, featureId, value)

                            override fun setInstrumentBytes(featureId: Int, bytes: ByteArray): Int =
                                CanWriteVerbs.setBytes(ctx, featureId, bytes)

                            override fun setSettingInt(featureId: Int, value: Int): Int =
                                CanWriteVerbs.settingSetInt(ctx, featureId, value)
                        }
                        val operations = ArrayList<CanBatchOperation>(count)
                        for (i in 0 until count) {
                            val type = data.readInt()
                            val featureId = data.readInt()
                            val intValue = data.readInt()
                            val bytes = data.createByteArray()
                            operations.add(
                                CanBatchOperation.fromWire(type, featureId, intValue, bytes))
                        }
                        val applied = CanBatchOperation.executeAcceptedPrefix(operations, writer)
                        if (reply != null) {
                            reply.writeNoException()
                            reply.writeInt(applied)
                        }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_INSTRUMENT_GET -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val v = CanWriteVerbs.getInt(ctx, featureId)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(v) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_SETTING_GET -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val v = CanWriteVerbs.settingGetInt(ctx, featureId)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(v) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_LISTEN_START -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val r = CanFeedbackListener.startSetting(ctx)
                        if (reply != null) { reply.writeNoException(); reply.writeString(r) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_LISTEN_DRAIN -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val r = CanFeedbackListener.drain()
                        if (reply != null) { reply.writeNoException(); reply.writeString(r) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_AAOS_HAL_PROBE -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val r = AaosDisplayHalProbe.probe()
                        if (reply != null) { reply.writeNoException(); reply.writeString(r) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_LISTEN_CLEAR -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        CanFeedbackListener.clear()
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_LISTEN_MARK -> {
                    data.enforceInterface(DESCRIPTOR)
                    val label = data.readString()
                    try {
                        CanFeedbackListener.mark(label ?: "")
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_CAN_SETTING_DOUBLE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val featureId = data.readInt()
                    val value = data.readDouble()
                    try {
                        val ctx = sWrappedContext
                            ?: throw IllegalStateException("wrapped context unavailable")
                        val rc = CanWriteVerbs.settingSetDouble(ctx, featureId, value)
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_READ_FILE_CHUNK -> {
                    data.enforceInterface(DESCRIPTOR)
                    val path = data.readString()
                    val off = data.readLong()
                    val maxLen = data.readInt()
                    try {
                        val chunk = readFileChunk(path, off, maxLen)
                        if (reply != null) { reply.writeNoException(); reply.writeByteArray(chunk) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_FISSION_GET_AUTOCAR_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val report = FissionHostSvcVerbs.getAutoCarDisplay()
                        if (reply != null) { reply.writeNoException(); reply.writeString(report) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_AUTOCONTAINER_REGISTER_CALLBACK -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val rc = Phase4ProcessVerbs.autoContainerRegisterCallback()
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_PROJECTION_TRACE_START -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        FissionHostSvcVerbs.startTrace()
                        reply?.writeNoException()
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                TXN_PROJECTION_TRACE_DRAIN -> {
                    data.enforceInterface(DESCRIPTOR)
                    try {
                        val report = FissionHostSvcVerbs.drainTrace()
                        if (reply != null) { reply.writeNoException(); reply.writeString(report) }
                    } catch (ex: Throwable) {
                        reply?.writeException(wrapThrowable(ex))
                    }
                    return true
                }
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    /**
     * Read up to [maxLen] bytes of [path] starting at [offset]. Runs in the daemon
     * (uid 2000 = shell), which can read `/data/local/tmp` files that SELinux hides from the
     * app uid. Returns an empty array at/after EOF so the caller's pull loop terminates.
     * [maxLen] is clamped to a Binder-safe ceiling.
     */
    @Throws(IOException::class)
    private fun readFileChunk(path: String?, offset: Long, maxLen: Int): ByteArray {
        if (path == null) throw FileNotFoundException("null path")
        val ceil = 512 * 1024 // keep well under the ~1 MB Binder transaction limit
        if (maxLen <= 0) return ByteArray(0)
        val capped = if (maxLen > ceil) ceil else maxLen
        val f = File(path)
        val size = f.length()
        if (offset < 0 || offset >= size) return ByteArray(0)
        val toRead = minOf(capped.toLong(), size - offset).toInt()
        val buf = ByteArray(toRead)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    /**
     * The catch block eleven of the verbs above each carried a private copy of, extracted
     * VERBATIM — same expression, same order, same types.
     *
     * It is deliberately NOT [wrapThrowable]: this one lets ANY `Exception` through unchanged and
     * falls back to `RuntimeException`, whereas [wrapThrowable] normalises to the set
     * `Parcel.writeException` can actually encode. The two policies were already mixed across the
     * switch before this file was Kotlin; keeping them distinct preserves behaviour exactly and
     * makes the divergence visible instead of hiding it in eleven copy-pasted blocks.
     */
    private fun legacyWrapThrowable(ex: Throwable): Exception {
        var cause: Throwable = ex
        val nested = ex.cause
        if (ex is InvocationTargetException && nested != null) {
            cause = nested
        }
        // writeException needs a real Exception subclass; wrap if necessary.
        return if (cause is Exception) cause
        else RuntimeException(cause.javaClass.simpleName + ": " + cause.message)
    }

    /** Unwrap InvocationTargetException and ensure we always hand a real Exception to
     *  [android.os.Parcel.writeException] (which only accepts Exception, not Throwable). */
    internal fun wrapThrowable(t: Throwable): Exception {
        var cause: Throwable = t
        val nested = t.cause
        if (t is InvocationTargetException && nested != null) {
            cause = nested
        }
        if (isParcelEncodable(cause)) return cause as Exception
        // Everything else becomes IllegalStateException, which IS encodable, carrying the original
        // type and message in its own message so nothing is lost for triage.
        //
        // Handing Parcel.writeException an Exception is not enough — it only encodes eight
        // well-known types. For anything else it writes code 0, which the client reads as "no
        // exception", and THEN throws on the daemon side. So the app was told the verb succeeded
        // and went on to read a zeroed return value: a failed launch, a refused CAN write or a
        // missing task came back as success with 0, and the app acted on it. Silent wrong answers
        // are the worst failure mode this binder can have, and this was producing them for every
        // exception type outside that set of eight.
        return IllegalStateException(cause.javaClass.name + ": " + cause.message, cause)
    }

    /** The exact set [android.os.Parcel.writeException] can encode. Anything else is code 0. */
    private fun isParcelEncodable(t: Throwable): Boolean {
        return t is SecurityException ||
            t is android.os.BadParcelableException ||
            t is IllegalArgumentException ||
            t is NullPointerException ||
            t is IllegalStateException ||
            t is android.os.NetworkOnMainThreadException ||
            t is UnsupportedOperationException ||
            // By name: android.os.ServiceSpecificException is a hidden API and is not in the
            // compileSdk 33 stubs, but it IS one of the eight the platform can encode, and a
            // binder verb can genuinely receive one from a system service.
            "android.os.ServiceSpecificException" == t.javaClass.name
    }

    /** Visible to the other daemon verb classes (e.g. the AutoContainer callback listener in
     *  [Phase4ProcessVerbs]) so they can write into the same daemon transcript — the one section
     *  of a bug report (`--- PROXYDAEMON LOG ---`) that survives a logcat flood, unlike
     *  `android.util.Log`. */
    internal fun log(s: String) {
        println("[dashcast_proxy] $s")
    }
}
