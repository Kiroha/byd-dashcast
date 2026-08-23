package com.byd.dashcast.proxy.daemon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.FileObserver;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static com.byd.dashcast.proxy.daemon.ProxyDaemonContract.*;

/**
 * ProxyDaemonMain — the uid=2000 daemon that <b>DOES</b> things; entry point for the Beta Engine
 * Component A daemon (v1.1.6+).
 *
 * <h3>The two-daemon boundary (read this before touching either daemon)</h3>
 * DashCast drives the instrument cluster through <b>two</b> uid-2000 helper processes. They are
 * not interchangeable and they do not share a binder:
 * <ul>
 *   <li><b>{@code ProxyDaemonMain} (this class) — "DOES things".</b> A stateless command executor:
 *       shell ({@link #TXN_EXEC}) plus typed one-shot verbs (launchAndForce, moveAndResize,
 *       setOverscan, autoContainerSendInfo…). It owns no long-lived state, so if it dies you simply
 *       retry the command. Its binder is {@code ProxyClient.getProxyDaemonBinder()}, registered in
 *       ServiceManager as {@link #SERVICE_NAME}, and it enforces
 *       {@link ProxyDaemonContract#DESCRIPTOR}.</li>
 *   <li><b>{@link SurfaceDaemon} — "HOLDS things".</b> A stateful surface/window owner: the in-app
 *       preview mirror <em>and</em>, in Layout mode, the per-slot {@code TYPE_SYSTEM_OVERLAY}
 *       windows <b>ON THE CLUSTER</b>, the trusted VirtualDisplays built from their Surfaces, the
 *       slot geometry, and touch injection. If it dies, the graphical state is lost and must be
 *       rebuilt. Its binder comes from {@code DaemonBinderResolver.surfaceDaemonBinder()}
 *       (ServiceManager name {@code byd_mirror_daemon}) and it enforces a different DESCRIPTOR.</li>
 * </ul>
 * Practical triage rule: <b>a failed command → ProxyDaemon; a black or frozen surface →
 * SurfaceDaemon</b>. Never pair one daemon's DESCRIPTOR with the other's binder — the receiving
 * {@code enforceInterface} rejects it and the transaction silently does nothing.
 *
 * <p>Started by {@link com.byd.dashcast.proxy.ProxyClient} via {@code app_process64}
 * over a local-ADB pairing session, so the JVM inherits the {@code shell} UID
 * (2000) of the ADB connection.
 *
 * <p>Since Android 10+ SELinux denies {@code untrusted_app}→{@code shell}
 * {@code unix_stream_socket connectto} (the 1.1.5 failure mode), this version
 * does NOT expose a {@link android.net.LocalServerSocket}. Instead it follows
 * the pattern used by OpenBYD and our own {@link SurfaceDaemon}:
 * <ol>
 *   <li>Acquire a system {@link Context} via reflective
 *       {@code ActivityThread.systemMain().getSystemContext()}.</li>
 *   <li>Publish a {@link Binder} subclass implementing the
 *       {@code com.byd.dashcast.proxy.daemon.IProxyDaemon} contract.</li>
 *   <li>Broadcast {@link #ACTION_PROXY_CONNECTED} targeted at the app package
 *       with the binder wrapped in a {@link BinderParcelable} extra.</li>
 *   <li>Enter {@link Looper#loop()} to keep the binder pool alive.</li>
 * </ol>
 *
 * <p>The wire protocol on top of {@link Binder#transact} is a tiny fixed set of
 * transactions identified by integer codes:
 * <ul>
 *   <li>{@link #TXN_PING}   — no args → {@code long} epoch_ms</li>
 *   <li>{@link #TXN_WHOAMI} — no args → {@code int uid, int pid, String ver}</li>
 *   <li>{@link #TXN_EXEC}   — {@code String cmd} → {@code int exit, String combinedOutput}</li>
 * </ul>
 */
public final class ProxyDaemonMain {

    // Wire-protocol constants (DESCRIPTOR, TXN_*, ACTION_PROXY_CONNECTED, EXTRA_BINDER)
    // live in ProxyDaemonContract and are imported via static import above.

    /** Protocol version reported by {@link #TXN_WHOAMI}. Bump on any wire-incompatible change.
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
    *  v20: adds TXN_AUTOCONTAINER_SEND_INFO_RESULT (preserves native sendInfo result codes).
    *  v21: adds TXN_CANCEL_FISSION_WATCHDOG (teardown cannot race post-launch re-anchoring).
    *  v22: adds TXN_AUTOCONTAINER_SEND_INFO2 (raw sendInfo2 byte[] channel — NaviInfo HUD injection).
    *  v23: adds TXN_FISSION_GET_AUTOCAR_DISPLAY (read-only FissionHostSvc registry probe, DL3),
    *  TXN_AUTOCONTAINER_REGISTER_CALLBACK (arms serviceDied/receivedX logging, diagnostic-only,
    *  never called before this release) and TXN_PROJECTION_TRACE_START/DRAIN (60s registry sampler
    *  around a normal projection cycle). All three read-only or purely observational.
     *  Purely additive — old clients keep working unchanged. */
    private static final String PROTOCOL_VERSION = "23";

    /** Process name shown in {@code ps} after the JVM's {@code setArgV0} runs. */
    private static final String PROC_NAME = "dashcast_proxy";

    /** PID file written at startup, removed at shutdown via the JVM hook.
     *  Used by the bootstrap script's fast path to detect a surviving daemon
     *  after app restart (setsid'd daemon outlives the app process) and ask
     *  for a binder rebroadcast instead of paying the ~1 s app_process cost. */
    private static final String PID_FILE = "/data/local/tmp/dashcast_proxy.pid";

    /** Trigger file watched via {@link FileObserver} by the running daemon.
     *  The bootstrap script (running as uid 2000 shell) touches this file to
     *  ask the daemon to re-emit {@link #ACTION_PROXY_CONNECTED} so a freshly
     *  restarted app gets the binder without restarting the daemon. */
    private static final String TRIGGER_FILE = "/data/local/tmp/dashcast_proxy.trigger";

    /** APK versionCode written at startup so the bootstrap script can detect a
     *  stale daemon that survived an OTA update (REBROADCAST would otherwise
     *  reconnect to the old, class-loaded binary). Deleted by shutdown hook. */
    private static final String VERSION_FILE = "/data/local/tmp/dashcast_proxy_ver";

    /** App package that receives the {@link ProxyDaemonContract#ACTION_PROXY_CONNECTED} broadcast. */
    private static final String TARGET_PKG = "com.byd.dashcast";

    /** Set in {@link #main(String[])} once the system context is acquired, so
     *  {@link ProxyBinder} can hand it to {@link Phase4Probes} without re-acquiring. */
    @SuppressLint("StaticFieldLeak") // system context, daemon process-scoped, safe
    private static volatile Context sSystemContext;

    /** Permission-bypass wrapper around {@link #sSystemContext}.
     *  Set immediately after {@link #sSystemContext}; used by {@link CanWriteVerbs} so that
     *  {@code BYDAutoInstrumentDevice.set()} passes the SDK's internal permission check.
     *  Null only if {@link #acquireSystemContext()} failed (daemon already exits in that case). */
    @SuppressLint("StaticFieldLeak") // wrapped system context, daemon process-scoped, safe
    private static volatile Context sWrappedContext;

    /** Strong reference to the trigger {@link FileObserver}, kept alive for the
     *  lifetime of the daemon. {@code FileObserver} is delivered via a
     *  background thread internal to Android — no explicit Looper needed. */
    private static volatile FileObserver sTriggerObserver;

    /** Cached binder + intent re-used by {@link #emitBroadcast()} so a USR-1-
     *  style rebroadcast does not need to rebuild any state. */
    private static volatile ProxyBinder sBinder;

    /** ServiceManager name under which the proxy binder is (best-effort) registered so the app can
     *  authenticate the PROXY_CONNECTED broadcast binder against it — only uid-2000/system can
     *  addService (SELinux blocks untrusted apps). Public so ProxyClient can cross-check. */
    public static final String SERVICE_NAME = "byd_proxy_daemon";

    /** Our app's uid, resolved once in main(); onTransact rejects callers that are not the app,
     *  system(1000), or the daemon's own uid. -1 = unresolved → fall open (never break the app). */
    private static volatile int sAppUid = -1;

    /** True if {@code uid} may drive the privileged ProxyBinder verbs. Falls OPEN when the app uid
     *  could not be resolved, so it can never block the legitimate app→daemon path. */
    private static boolean isAllowedCaller(int uid) {
        if (sAppUid == -1) return true;              // unresolved → fall open, never break
        if (uid == 1000) return true;                // system
        if (uid == Process.myUid()) return true;     // the daemon's own uid (shell 2000)
        return (uid % 100000) == (sAppUid % 100000); // our app (user-agnostic appId match)
    }

    /** Best-effort registration of the proxy binder in the global ServiceManager (uid-2000/system
     *  only). Enables the app-side authenticity cross-check; broadcast delivery is unaffected, so
     *  a failure here is harmless (the app falls back to the broadcast binder). */
    private static void registerInServiceManager(android.os.IBinder binder) {
        if (binder == null) return;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            try {
                java.lang.reflect.Method add = sm.getDeclaredMethod("addService",
                        String.class, android.os.IBinder.class, boolean.class, int.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, binder, false, 0);
            } catch (NoSuchMethodException nsme) {
                java.lang.reflect.Method add = sm.getDeclaredMethod("addService",
                        String.class, android.os.IBinder.class);
                add.setAccessible(true);
                add.invoke(null, SERVICE_NAME, binder);
            }
            log("ServiceManager.addService(" + SERVICE_NAME + ") OK");
        } catch (Throwable t) {
            log("ServiceManager.addService failed (broadcast only): " + t);
        }
    }

    private ProxyDaemonMain() {}

    public static void main(String[] args) {
        try {
            // v1.8.24 — unlock hidden APIs for THIS process before anything else touches
            // reflection. Deliberately the very first statement: Phase4TaskVerbs/Phase4Probes/
            // CanWriteVerbs etc. all reflect into android.app.*/android.hardware.bydauto.* classes,
            // and setHiddenApiExemptions is per-ART-VM state — running it after any of those verbs
            // have already resolved (and cached, or given up on) a Method would be too late for
            // that call site. SurfaceDaemon.java and ClusterMirrorManager.kt each already unlock
            // hidden APIs in THEIR OWN process, but that never covered this one, which is the
            // process that actually makes the calls logged as "NoSuchMethodException" throughout
            // Phase4TaskVerbs — a class demonstrably present in this ROM's own services.jar/
            // framework.jar (setDisplayToSingleTaskInstance, setCustomTaskWindowingMode) is exactly
            // the symptom of hidden-API filtering: a method invisible to getMethod() despite
            // existing in the compiled bytecode.
            hiddenApiSelfTest();
            renameProcess();
            // v1.2.70 hardening (Couche 3): tell the Linux OOM killer to
            // treat us as critically important. uid=2000 (shell) can write
            // to its own /proc/self/oom_score_adj. -900 is just above the
            // system_server reserved -1000 / -900 band; any value below 0
            // protects us from typical low-memory kills triggered by foreground
            // app churn. Best-effort: ignored if /proc is not writable.
            hardenAgainstOom();
            // v1.2.70 hardening: atomic PID-lock via O_CREAT|O_EXCL semantics
            // (Files.createFile). If another daemon already owns the lock we
            // refuse to start so the bootstrap's stale-kill stays the single
            // source of truth for "which PID is canonical".
            if (!acquirePidLock()) {
                log("FATAL: another daemon already holds the PID lock — exiting");
                System.exit(3);
                return;
            }
            writeVersionFile();
            installPidShutdownHook();
            Looper.prepareMainLooper();

            sBinder = new ProxyBinder();
            log("binder ready uid=" + Process.myUid()
                    + " pid=" + Process.myPid()
                    + " ver=" + PROTOCOL_VERSION);

            Context systemContext = acquireSystemContext();
            if (systemContext == null) {
                log("FATAL: no system context — cannot broadcast binder");
                System.exit(2);
                return;
            }
            sSystemContext = systemContext;
            // Adopt our own package identity (createPackageContext + getPackageName
            // spoof) so BYD SDK surfaces that gate on the calling package — not just
            // the uid — accept our CAN/setting writes. Mirrors OpenBYD 2.2's
            // SystemContext.get(). Falls back to a plain permission-bypass wrap if
            // createPackageContext is unavailable.
            sWrappedContext = com.byd.dashcast.proxy.SystemContextHelper.adoptIdentity(systemContext);

            // Resolve our app's uid once so the ProxyBinder caller-gate can reject untrusted callers
            // (the binder is registered in ServiceManager just below). Fall open on any failure.
            try {
                sAppUid = systemContext.getPackageManager()
                        .getPackageUid(com.byd.dashcast.BuildConfig.APPLICATION_ID, 0);
                log("app uid resolved: " + sAppUid);
            } catch (Throwable t) {
                log("app uid resolution failed (fall open): " + t);
            }
            // Best-effort ServiceManager registration for the app-side authenticity cross-check.
            registerInServiceManager(sBinder);

            emitBroadcast();
            installTriggerObserver();
            // v1.2.70 hardening: periodic self-check thread (10s).
            // Re-creates the trigger file if it was deleted (some vendors wipe
            // /data/local/tmp on certain events), re-arms the FileObserver if
            // it died, and self-suicides if our own binder thread is wedged.
            // Suicide is safe: ProxyKeeperService (v1.2.71) or the next app
            // call will re-spawn us within 10s.
            installSelfHealHeartbeat();

            Looper.loop();
        } catch (Throwable t) {
            log("FATAL: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /** Build and send the {@link #ACTION_PROXY_CONNECTED} broadcast. Idempotent —
     *  may be invoked any number of times after the initial startup, every call
     *  re-emits the binder so a freshly registered receiver in a restarted app
     *  picks it up without a daemon respawn. */
    private static void emitBroadcast() {
        Context ctx = sSystemContext;
        ProxyBinder binder = sBinder;
        if (ctx == null || binder == null) {
            log("emitBroadcast skipped: context or binder missing");
            return;
        }
        Intent intent = new Intent(ACTION_PROXY_CONNECTED)
                .setPackage(TARGET_PKG)
                .putExtra(EXTRA_BINDER, new BinderParcelable(binder))
                // FLAG_INCLUDE_STOPPED_PACKAGES so the app receives the broadcast
                // even right after a force-stop — important for the bootstrap flow
                // where the receiver was just dynamically registered.
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        ctx.sendBroadcast(intent);
        log("broadcast sent: " + ACTION_PROXY_CONNECTED + " → " + TARGET_PKG);
    }

    /**
     * v1.8.24 — self-proving hidden-API unlock. Logs whether a method already known to be
     * blocked on this exact car ({@code IActivityTaskManager$Stub$Proxy.setDisplayToSingleTaskInstance})
     * is visible to {@code getMethod()} BEFORE and AFTER calling
     * {@link HiddenApiBypass#setHiddenApiExemptions}, so the very next bug report either confirms
     * or refutes the whole hypothesis with a plain log line — no guesswork needed on the next pull.
     *
     * <p>Uses the same scoped exemption list as the existing {@code unlockHiddenApis()} precedent
     * in {@code SurfaceDaemon}/{@code ClusterMirrorManager} ({@code Landroid/}, {@code Lcom/android/},
     * {@code Ljava/lang/}) — every reflected target across the daemon (android.app.*, android.view.*,
     * android.hardware.bydauto.*) falls under {@code Landroid/}; nothing observed so far needs a
     * broader exemption. Uses the library's {@code HiddenApiBypass} class specifically (backed by
     * {@code sun.misc.Unsafe}, documented stable on API 10+ without touching internal ART
     * structures) — not {@code LSPass}, which the library's own README says cannot reach core
     * platform API, exactly what every target here is.
     *
     * <p>Read-only: only calls {@code getMethod()}, never {@code invoke()}. Cannot itself break
     * anything even if the whole hypothesis is wrong.
     */
    private static void hiddenApiSelfTest() {
        log("hiddenapi BEFORE: " + probeHiddenMethodVisibility());
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions(
                    "Landroid/", "Lcom/android/", "Ljava/lang/");
            log("hiddenapi: setHiddenApiExemptions OK");
        } catch (Throwable t) {
            log("hiddenapi: setHiddenApiExemptions FAILED: " + t);
        }
        log("hiddenapi AFTER: " + probeHiddenMethodVisibility());
    }

    /** @return "VISIBLE (...)" / "HIDDEN (NoSuchMethodException)" / "INCONCLUSIVE ..." — never throws. */
    private static String probeHiddenMethodVisibility() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("setDisplayToSingleTaskInstance", int.class);
            return "VISIBLE (" + m + ")";
        } catch (NoSuchMethodException nsme) {
            return "HIDDEN (NoSuchMethodException)";
        } catch (Throwable t) {
            return "INCONCLUSIVE " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** v1.2.70 hardening: lower our OOM score so Linux's low-memory killer
     *  reaches for foreground apps before us. -900 sits just above the framework
     *  reserved range (-1000..-900 used by system_server etc.).
     *
     *  <p>Best-effort, and it is <i>expected</i> to fail on DiLink 3: that ROM's SELinux policy
     *  denies the shell domain write access to proc_oom_adj, so the open itself returns EACCES
     *  (79 of 80 DiLink 3 reports in the corpus; every DiLink 4 / 5.0 / 5.1 / AAOS report
     *  succeeds). The earlier claim here that "uid=2000 can always write to its own
     *  /proc/self/oom_score_adj" was wrong. The daemon runs fine without it — it only loses a
     *  priority hint — so this is logged as a state, not as a failure to chase. */
    private static void hardenAgainstOom() {
        try (FileOutputStream fos = new FileOutputStream("/proc/self/oom_score_adj")) {
            fos.write("-900".getBytes());
            log("oom_score_adj=-900 set");
        } catch (Throwable t) {
            log("oom_score_adj not settable, continuing without the priority hint"
                    + " (expected on DiLink 3 — SELinux denies shell): " + t);
        }
    }

    /** v1.2.70 hardening: atomic PID-lock acquisition.
     *
     *  <p>Strategy:
     *  <ol>
     *    <li>Read any existing PID file.</li>
     *    <li>If the recorded PID is still alive AND points to a
     *        {@code dashcast_proxy} process (verified via {@code /proc/PID/cmdline}),
     *        refuse to start — a canonical daemon already exists.</li>
     *    <li>Otherwise (stale file or no file), atomically rewrite it with
     *        our own PID and continue.</li>
     *  </ol>
     *
     *  <p>This replaces the broken shell-side {@code flock} (see v1.2.69
     *  cascade) with an in-JVM check that has no toybox/util-linux dependency. */
    private static boolean acquirePidLock() {
        try {
            File pidFile = new File(PID_FILE);
            // createNewFile() is atomic (O_CREAT|O_EXCL) — eliminates TOCTOU between
            // exists() check and FileOutputStream creation.
            boolean created = pidFile.createNewFile();
            if (!created) {
                // File already existed — check if a live daemon owns it.
                String existing = readSmallFile(pidFile).trim();
                if (!existing.isEmpty()) {
                    int otherPid = -1;
                    try { otherPid = Integer.parseInt(existing); } catch (NumberFormatException ignore) {}
                    if (otherPid > 0 && otherPid != Process.myPid() && isLiveDaemon(otherPid)) {
                        return false;
                    }
                }
                // Stale or empty file — fall through and overwrite with our PID.
            }
            try (FileOutputStream fos = new FileOutputStream(pidFile)) {
                fos.write(Integer.toString(Process.myPid()).getBytes());
            }
            return true;
        } catch (Throwable t) {
            log("acquirePidLock error (allowing start): " + t);
            // Fail-open: better to risk a duplicate (caught by stale-kill) than
            // to refuse to start because /data/local/tmp had a transient glitch.
            return true;
        }
    }

    private static String readSmallFile(File f) throws Exception {
        try (InputStream is = new java.io.FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[256];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toString();
        }
    }

    /** True iff {@code /proc/PID/cmdline} exists and contains "dashcast_proxy". */
    private static boolean isLiveDaemon(int pid) {
        try {
            File cmdline = new File("/proc/" + pid + "/cmdline");
            if (!cmdline.exists()) return false;
            String s = readSmallFile(cmdline);
            return s != null && s.contains(PROC_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeVersionFile() {
        try (FileOutputStream fos = new FileOutputStream(new File(VERSION_FILE))) {
            fos.write(Integer.toString(com.byd.dashcast.BuildConfig.VERSION_CODE).getBytes());
        } catch (Throwable ignore) {}
    }

    /** Remove the PID file on JVM shutdown. Pure best-effort — a SIGKILL'd
     *  daemon will leave a stale file behind, which the bootstrap script
     *  detects via {@code /proc/$PID/comm} sanity check. */
    private static void installPidShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread("pid-cleanup") {
                @Override public void run() {
                    // Only clean up if THIS process still owns the lock. A duplicate daemon
                    // that lost the race and suicided (healPidLock -> System.exit) must NOT
                    // delete the SURVIVOR's PID file — otherwise a fresh bootstrap sees no
                    // lock and spawns a spurious third daemon during the ~10s until the
                    // survivor rewrites it.
                    int owner = -1;
                    try { owner = Integer.parseInt(readSmallFile(new File(PID_FILE)).trim()); }
                    catch (Throwable ignore) {}
                    if (owner != Process.myPid()) return;
                    try { new File(PID_FILE).delete(); } catch (Throwable ignore) {}
                    try { new File(TRIGGER_FILE).delete(); } catch (Throwable ignore) {}
                    try { new File(VERSION_FILE).delete(); } catch (Throwable ignore) {}
                }
            });
        } catch (Throwable ignore) {
            // shutdown hooks may be disallowed in some app_process contexts —
            // not fatal, stale file is recoverable.
        }
    }

    /** Watch {@link #TRIGGER_FILE} for CREATE/MODIFY events. The bootstrap
     *  script touches that file to ask us to re-broadcast the binder — used
     *  when the app process restarts but our daemon survived (setsid).
     *
     *  <p>v1.3.10: watches the PARENT DIRECTORY instead of the file itself.
     *  On Android 12 (DL5, uid=2000, app_process64), inotify watches on a
     *  specific file inode are silently dropped when the shell creates the
     *  file with O_TRUNC (which may allocate a new inode). A directory-level
     *  watch captures CREATE/CLOSE_WRITE on any child including our trigger
     *  file, regardless of inode recycling. */
    private static void installTriggerObserver() {
        try {
            // Ensure the trigger file and its parent directory exist.
            File triggerFile = new File(TRIGGER_FILE);
            File parentDir   = triggerFile.getParentFile();
            try { triggerFile.createNewFile(); } catch (Throwable ignore) {}
            // v1.3.10: watch the DIRECTORY (more robust on Android 12).
            // onEvent path argument will be just the filename, not full path.
            final String triggerName = triggerFile.getName();
            sTriggerObserver = new FileObserver(parentDir,
                    FileObserver.MODIFY | FileObserver.CREATE | FileObserver.CLOSE_WRITE) {
                @Override public void onEvent(int event, String path) {
                    if (triggerName.equals(path)) {
                        log("rebroadcast trigger received via dir-watch (event=" + event + ")");
                        emitBroadcast();
                    }
                }
            };
            sTriggerObserver.startWatching();
            log("trigger observer armed on dir " + parentDir + " (filter=" + triggerName + ")");
        } catch (Throwable t) {
            log("installTriggerObserver failed: " + t);
        }
    }

    /** v1.2.70 hardening: periodic self-check thread.
     *
     *  <p>Responsibilities:
     *  <ul>
     *    <li>Re-create the trigger file if it was deleted (some vendors
     *        wipe {@code /data/local/tmp} on certain events).</li>
     *    <li>Re-arm the {@link FileObserver} if {@link #sTriggerObserver}
     *        is null or was stopped.</li>
     *    <li>Detect that our PID file was clobbered (another daemon spawned
     *        in parallel and won the race) → suicide so the survivor stays
     *        canonical.</li>
     *    <li>v1.3.9: poll trigger file mtime every 1s as backup for
     *        FileObserver unreliability observed on DL5 (inotify events not
     *        delivered in uid=2000 / app_process64 context).</li>
     *  </ul>
     *
     *  <p>Daemon thread, started as DAEMON so it never blocks JVM shutdown. */
    private static void installSelfHealHeartbeat() {
        Thread t = new Thread("dashcast-self-heal") {
            @Override public void run() {
                // v1.3.9 — record the initial mtime so we only react to
                // FUTURE writes, not the file that was there when we started.
                long lastTriggerMtime = new File(TRIGGER_FILE).lastModified();
                int tick = 0;
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
                // fall to 10 s. Worst case after the ramp is that a trigger write is noticed 10 s
                // late INSTEAD OF 1 s late, and only when the FileObserver has ALSO failed. That
                // lengthens a cold bootstrap; it cannot break an already-connected projection,
                // and the app-side retry path (ProxyClient.callWithRetry pre-flight reconnect)
                // is unchanged.
                final int RAMP_TICKS = 60;      // ~60 s of 1 Hz polling after daemon start
                final int HEAL_EVERY_RAMP = 10; // heal ~10 s during the ramp
                final int HEAL_EVERY_SLOW = 3;  // heal ~30 s after it (3 x 10 s)
                while (true) {
                    try {
                        Thread.sleep(tick < RAMP_TICKS ? 1_000L : 10_000L);
                    } catch (InterruptedException ignore) {
                        return;
                    }
                    // v1.3.9 — every tick: poll trigger file mtime.
                    // This is the primary recovery path when FileObserver
                    // silently stops delivering events on DL5.
                    try {
                        File f = new File(TRIGGER_FILE);
                        long mtime = f.lastModified();
                        if (mtime != lastTriggerMtime) {
                            lastTriggerMtime = mtime;
                            log("trigger poll: mtime changed → rebroadcast");
                            emitBroadcast();
                        }
                    } catch (Throwable th) { log("trigger poll: " + th); }
                    // Full self-heal (file + pid lock). Held at ~10 s during the ramp and ~30 s
                    // after it, so the heal cadence stays roughly constant in wall-clock terms
                    // even though the poll interval changes underneath it.
                    ++tick;
                    if (tick % (tick < RAMP_TICKS ? HEAL_EVERY_RAMP : HEAL_EVERY_SLOW) == 0) {
                        try { healTriggerFile(); } catch (Throwable th) { log("heal trigger: " + th); }
                        try { healPidLock();     } catch (Throwable th) { log("heal pid: " + th); }
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        log("self-heal heartbeat armed (1s poll + 10s heal for 60s, then 10s poll + 30s heal)");
    }

    private static void healTriggerFile() {
        File f = new File(TRIGGER_FILE);
        if (!f.exists()) {
            try { f.createNewFile(); log("trigger file re-created"); } catch (Throwable ignore) {}
            // v1.3.10: directory watch survives trigger file deletion (watches
            // the parent dir inode, not the file inode). Only re-arm if the
            // observer itself is gone.
        }
        if (sTriggerObserver == null) {
            installTriggerObserver();
        }
    }

    private static void healPidLock() {
        File pidFile = new File(PID_FILE);
        if (!pidFile.exists()) {
            // Someone wiped our PID file; rewrite it.
            try (FileOutputStream fos = new FileOutputStream(pidFile)) {
                fos.write(Integer.toString(Process.myPid()).getBytes());
            } catch (Throwable ignore) {}
            return;
        }
        try {
            String contents = readSmallFile(pidFile).trim();
            int recorded = -1;
            try { recorded = Integer.parseInt(contents); } catch (NumberFormatException ignore) {}
            if (recorded > 0 && recorded != Process.myPid() && isLiveDaemon(recorded)) {
                // Another daemon stole the lock and is alive — yield to it.
                log("self-heal: lock stolen by pid=" + recorded + " → suicide");
                System.exit(0);
            }
        } catch (Throwable ignore) {}
    }

    /** Reflective hop to obtain a usable system {@link Context} from inside {@code app_process}. */
    private static Context acquireSystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Object ctx    = at.getMethod("getSystemContext").invoke(thread);
            return (Context) ctx;
        } catch (Throwable t) {
            log("acquireSystemContext failed: " + t);
            return null;
        }
    }

    private static void renameProcess() {
        try {
            Method m = Process.class.getDeclaredMethod("setArgV0", String.class);
            m.invoke(null, PROC_NAME);
        } catch (Throwable ignore) {
            // not fatal — process keeps its app_process / --nice-name argv[0]
        }
    }

    /**
     * Binder published to the app. Uses raw {@link Binder#onTransact} (no AIDL
     * codegen needed — keeps the build simple and the wire format obvious).
     */
    static final class ProxyBinder extends Binder {

        ProxyBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // Caller-identity gate: the binder is now discoverable via ServiceManager (for the
            // app-side authenticity cross-check) and TXN_EXEC runs arbitrary shell as uid 2000, so
            // only the app (+ system / the daemon's own uid) may drive any verb. The per-case
            // enforceInterface below is NOT authentication. Falls OPEN if the app uid is unresolved
            // → can never block the legitimate app→daemon path.
            int callingUid = Binder.getCallingUid();
            if (!isAllowedCaller(callingUid)) {
                log("ProxyBinder: rejected transact code=" + code + " from uid=" + callingUid);
                if (reply != null) reply.writeException(
                        new SecurityException("caller uid " + callingUid + " not permitted"));
                return true;
            }
            switch (code) {
                case TXN_PING: {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeLong(System.currentTimeMillis());
                    }
                    return true;
                }
                case TXN_WHOAMI: {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(Process.myUid());
                        reply.writeInt(Process.myPid());
                        reply.writeString(PROTOCOL_VERSION);
                    }
                    return true;
                }
                case TXN_EXEC: {
                    data.enforceInterface(DESCRIPTOR);
                    String cmd = data.readString();
                    ProxyShell.Result er = ProxyShell.exec(cmd);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(er.exit);
                        reply.writeString(er.output);
                    }
                    return true;
                }
                case TXN_PROBE_PHASE4: {
                    data.enforceInterface(DESCRIPTOR);
                    String result;
                    try {
                        result = Phase4Probes.runAll(sSystemContext);
                    } catch (Throwable t) {
                        // Probe harness must never crash the daemon — fall back to a
                        // synthetic single-token error so the client side still parses.
                        result = "P0=FAIL_OTHER:harness " + t.getClass().getSimpleName()
                                + " " + (t.getMessage() == null ? "" : t.getMessage());
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(result);
                    }
                    return true;
                }
                case TXN_SET_OVERSCAN: {
                    data.enforceInterface(DESCRIPTOR);
                    int displayId = data.readInt();
                    int l = data.readInt();
                    int t = data.readInt();
                    int r = data.readInt();
                    int b = data.readInt();
                    try {
                        Phase4DisplayVerbs.setOverscan(displayId, l, t, r, b);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        // Surface the real cause to the client so it can fall back to
                        // the shell path with full diagnostic context.
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            // writeException needs a real Exception subclass; wrap if necessary.
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_GET_PIDS: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    String pids;
                    try {
                        pids = Phase4ProcessVerbs.getPidsByPackage(pkg);
                    } catch (Throwable t) {
                        // Pure-Java /proc scan should never throw, but guard anyway:
                        // surface as a normal exception so the client falls back to shell.
                        if (reply != null) {
                            Exception wrap = (t instanceof Exception)
                                    ? (Exception) t
                                    : new RuntimeException(t.getClass().getSimpleName() + ": " + t.getMessage());
                            reply.writeException(wrap);
                        }
                        return true;
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(pids);
                    }
                    return true;
                }
                case TXN_AUTOCONTAINER_SEND_INFO: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    int info = data.readInt();
                    String str = data.readString();
                    try {
                        Phase4ProcessVerbs.autoContainerSendInfo(type, info, str);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_AUTOCONTAINER_SEND_INFO_RESULT: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    int info = data.readInt();
                    String str = data.readString();
                    try {
                        int result = Phase4ProcessVerbs.autoContainerSendInfoResult(type, info, str);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(result);
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_AUTOCONTAINER_SEND_INFO2: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    byte[] payload = data.createByteArray();
                    try {
                        Phase4ProcessVerbs.autoContainerSendInfo2(type, payload);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_FORCE_STOP_PACKAGE: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    int userId = data.readInt();
                    try {
                        Phase4ProcessVerbs.forceStopPackage(pkg, userId);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_CREATE_VIRTUAL_DISPLAY: {
                    data.enforceInterface(DESCRIPTOR);
                    String name = data.readString();
                    int w     = data.readInt();
                    int h     = data.readInt();
                    int dpi   = data.readInt();
                    int vflag = data.readInt();
                    android.view.Surface surface = data.readInt() != 0
                            ? android.view.Surface.CREATOR.createFromParcel(data)
                            : null;
                        android.os.IBinder owner = data.dataAvail() > 0
                            ? data.readStrongBinder() : null;
                    try {
                        int displayId = Phase4DisplayVerbs.createVirtualDisplay(
                            sSystemContext, name, w, h, dpi, surface, vflag, owner);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(displayId);
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    } finally {
                        // CREATOR produced a daemon-local wrapper. DisplayManagerService/VD now
                        // owns the producer reference; release this temporary Java/native handle.
                        if (surface != null) surface.release();
                    }
                    return true;
                }
                case TXN_RELEASE_VIRTUAL_DISPLAY: {
                    data.enforceInterface(DESCRIPTOR);
                    int displayId = data.readInt();
                    try {
                        Phase4DisplayVerbs.releaseVirtualDisplay(displayId);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_LAUNCH_AND_FORCE: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    String cls = data.readInt() != 0 ? data.readString() : null;
                    int    did = data.readInt();
                    int    w   = data.readInt();
                    int    h   = data.readInt();
                    try {
                        String log = Phase4TaskVerbs.launchAndForce(pkg, cls, did, w, h);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeString(log == null ? "" : log);
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_MOVE_AND_RESIZE: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    int    did = data.readInt();
                    int    l   = data.readInt();
                    int    t   = data.readInt();
                    int    r   = data.readInt();
                    int    b   = data.readInt();
                    try {
                        String log = Phase4TaskVerbs.moveAndResize(pkg, did, l, t, r, b);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeString(log == null ? "" : log);
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_CLEAN_FISSION_STACKS: {
                    data.enforceInterface(DESCRIPTOR);
                    int did = data.readInt();
                    try {
                        String log = Phase4TaskVerbs.cleanFissionStacks(did);
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeString(log == null ? "" : log);
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_FIND_TASK_FOR_PACKAGE: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    int taskId = Phase4TaskVerbs.findTaskIdForPackage(pkg);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(taskId);
                    }
                    return true;
                }
                case TXN_FIND_TASK_LOCATION: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    com.byd.dashcast.infrastructure.task.TaskLocation location =
                            Phase4TaskVerbs.findTaskLocationForPackage(pkg);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(location.getStatus().getWireCode());
                        reply.writeInt(location.getTaskId());
                        reply.writeInt(location.getDisplayId());
                    }
                    return true;
                }
                case TXN_CANCEL_FISSION_WATCHDOG: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    boolean cancelled = Phase4TaskVerbs.cancelFissionWatchdog(pkg);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(cancelled ? 1 : 0);
                    }
                    return true;
                }
                case TXN_REMOVE_TASK: {
                    data.enforceInterface(DESCRIPTOR);
                    int taskId = data.readInt();
                    try {
                        Phase4TaskVerbs.removeTask(taskId);
                        if (reply != null) {
                            reply.writeNoException();
                        }
                    } catch (Throwable ex) {
                        Throwable cause = ex;
                        if (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) {
                            cause = ex.getCause();
                        }
                        if (reply != null) {
                            Exception wrap = (cause instanceof Exception)
                                    ? (Exception) cause
                                    : new RuntimeException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            reply.writeException(wrap);
                        }
                    }
                    return true;
                }
                case TXN_CAN_NAVI_STATUS: {
                    data.enforceInterface(DESCRIPTOR);
                    int status = data.readInt();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int rc = CanWriteVerbs.setInt(ctx, CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, status);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_INSTRUMENT_INT: {
                    data.enforceInterface(DESCRIPTOR);
                    int featureId = data.readInt();
                    int value     = data.readInt();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int rc = CanWriteVerbs.setInt(ctx, featureId, value);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_INSTRUMENT_BYTES: {
                    data.enforceInterface(DESCRIPTOR);
                    int    featureId = data.readInt();
                    byte[] bytes     = data.createByteArray();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int rc = CanWriteVerbs.setBytes(ctx, featureId, bytes == null ? new byte[0] : bytes);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_SETTING_INT: {
                    data.enforceInterface(DESCRIPTOR);
                    int featureId = data.readInt();
                    int value     = data.readInt();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int rc = CanWriteVerbs.settingSetInt(ctx, featureId, value);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_BATCH: {
                    data.enforceInterface(DESCRIPTOR);
                    int count = data.readInt();
                    try {
                        if (count <= 0 || count > com.byd.dashcast.system.CanBatchOperation.MAX_BATCH_SIZE) {
                            throw new IllegalArgumentException("invalid CAN batch size " + count);
                        }
                        final Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        com.byd.dashcast.system.CanBatchOperation.Writer writer =
                                new com.byd.dashcast.system.CanBatchOperation.Writer() {
                            @Override public void setNaviStatus(int status) throws Throwable {
                                CanWriteVerbs.setInt(ctx,
                                        CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, status);
                            }
                            @Override public void setInstrumentInt(int featureId, int value)
                                    throws Throwable {
                                CanWriteVerbs.setInt(ctx, featureId, value);
                            }
                            @Override public void setInstrumentBytes(int featureId, byte[] bytes)
                                    throws Throwable {
                                CanWriteVerbs.setBytes(ctx, featureId,
                                        bytes == null ? new byte[0] : bytes);
                            }
                            @Override public void setSettingInt(int featureId, int value)
                                    throws Throwable {
                                CanWriteVerbs.settingSetInt(ctx, featureId, value);
                            }
                        };
                        for (int i = 0; i < count; i++) {
                            int type = data.readInt();
                            int featureId = data.readInt();
                            int intValue = data.readInt();
                            byte[] bytes = data.createByteArray();
                            com.byd.dashcast.system.CanBatchOperation.fromWire(
                                    type, featureId, intValue, bytes).execute(writer);
                        }
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(count);
                        }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_INSTRUMENT_GET: {
                    data.enforceInterface(DESCRIPTOR);
                    int featureId = data.readInt();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int v = CanWriteVerbs.getInt(ctx, featureId);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(v); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_SETTING_GET: {
                    data.enforceInterface(DESCRIPTOR);
                    int featureId = data.readInt();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int v = CanWriteVerbs.settingGetInt(ctx, featureId);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(v); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_LISTEN_START: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        String r = CanFeedbackListener.startSetting(ctx);
                        if (reply != null) { reply.writeNoException(); reply.writeString(r); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_LISTEN_DRAIN: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        String r = CanFeedbackListener.drain();
                        if (reply != null) { reply.writeNoException(); reply.writeString(r); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_AAOS_HAL_PROBE: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        String r = AaosDisplayHalProbe.probe();
                        if (reply != null) { reply.writeNoException(); reply.writeString(r); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_LISTEN_CLEAR: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        CanFeedbackListener.clear();
                        if (reply != null) reply.writeNoException();
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_LISTEN_MARK: {
                    data.enforceInterface(DESCRIPTOR);
                    String label = data.readString();
                    try {
                        CanFeedbackListener.mark(label == null ? "" : label);
                        if (reply != null) reply.writeNoException();
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_CAN_SETTING_DOUBLE: {
                    data.enforceInterface(DESCRIPTOR);
                    int featureId = data.readInt();
                    double value  = data.readDouble();
                    try {
                        Context ctx = sWrappedContext;
                        if (ctx == null) throw new IllegalStateException("wrapped context unavailable");
                        int rc = CanWriteVerbs.settingSetDouble(ctx, featureId, value);
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_READ_FILE_CHUNK: {
                    data.enforceInterface(DESCRIPTOR);
                    String path  = data.readString();
                    long   off   = data.readLong();
                    int    maxLen = data.readInt();
                    try {
                        byte[] chunk = readFileChunk(path, off, maxLen);
                        if (reply != null) { reply.writeNoException(); reply.writeByteArray(chunk); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_FISSION_GET_AUTOCAR_DISPLAY: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        String report = FissionHostSvcVerbs.getAutoCarDisplay();
                        if (reply != null) { reply.writeNoException(); reply.writeString(report); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_AUTOCONTAINER_REGISTER_CALLBACK: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        int rc = Phase4ProcessVerbs.autoContainerRegisterCallback();
                        if (reply != null) { reply.writeNoException(); reply.writeInt(rc); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_PROJECTION_TRACE_START: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        FissionHostSvcVerbs.startTrace();
                        if (reply != null) reply.writeNoException();
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case TXN_PROJECTION_TRACE_DRAIN: {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        String report = FissionHostSvcVerbs.drainTrace();
                        if (reply != null) { reply.writeNoException(); reply.writeString(report); }
                    } catch (Throwable ex) {
                        if (reply != null) reply.writeException(wrapThrowable(ex));
                    }
                    return true;
                }
                case INTERFACE_TRANSACTION: {
                    if (reply != null) reply.writeString(DESCRIPTOR);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    /**
     * Read up to {@code maxLen} bytes of {@code path} starting at {@code offset}. Runs in the
     * daemon (uid 2000 = shell), which can read {@code /data/local/tmp} files that SELinux hides
     * from the app uid. Returns an empty array at/after EOF so the caller's pull loop terminates.
     * {@code maxLen} is clamped to a Binder-safe ceiling.
     */
    private static byte[] readFileChunk(String path, long offset, int maxLen) throws java.io.IOException {
        if (path == null) throw new java.io.FileNotFoundException("null path");
        final int CEIL = 512 * 1024; // keep well under the ~1 MB Binder transaction limit
        if (maxLen <= 0) return new byte[0];
        if (maxLen > CEIL) maxLen = CEIL;
        File f = new File(path);
        long size = f.length();
        if (offset < 0 || offset >= size) return new byte[0];
        int toRead = (int) Math.min((long) maxLen, size - offset);
        byte[] buf = new byte[toRead];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            raf.seek(offset);
            raf.readFully(buf);
        }
        return buf;
    }

    /** Unwrap InvocationTargetException and ensure we always hand a real Exception to
     *  {@link android.os.Parcel#writeException} (which only accepts Exception, not Throwable). */
    static Exception wrapThrowable(Throwable t) {
        Throwable cause = t;
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            cause = t.getCause();
        }
        if (isParcelEncodable(cause)) return (Exception) cause;
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
        return new IllegalStateException(
                cause.getClass().getName() + ": " + cause.getMessage(), cause);
    }

    /** The exact set {@link android.os.Parcel#writeException} can encode. Anything else is code 0. */
    private static boolean isParcelEncodable(Throwable t) {
        return t instanceof SecurityException
                || t instanceof android.os.BadParcelableException
                || t instanceof IllegalArgumentException
                || t instanceof NullPointerException
                || t instanceof IllegalStateException
                || t instanceof android.os.NetworkOnMainThreadException
                || t instanceof UnsupportedOperationException
                // By name: android.os.ServiceSpecificException is a hidden API and is not in the
                // compileSdk 33 stubs, but it IS one of the eight the platform can encode, and a
                // binder verb can genuinely receive one from a system service.
                || "android.os.ServiceSpecificException".equals(t.getClass().getName());
    }

    /** Package-visible so verb classes (e.g. the AutoContainer callback listener in
     *  {@link Phase4ProcessVerbs}) can write into the same daemon transcript — the one section of
     *  a bug report ({@code --- PROXYDAEMON LOG ---}) that survives a logcat flood, unlike
     *  {@code android.util.Log}. */
    static void log(String s) {
        System.out.println("[dashcast_proxy] " + s);
    }
}
