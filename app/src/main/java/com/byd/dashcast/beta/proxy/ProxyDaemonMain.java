package com.byd.dashcast.beta.proxy;

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

/**
 * ProxyDaemonMain — entry point for the Beta Engine Component A daemon (v1.1.6+).
 *
 * <p>Started by {@link com.byd.dashcast.beta.BetaProxyClient} via {@code app_process64}
 * over a local-ADB pairing session, so the JVM inherits the {@code shell} UID
 * (2000) of the ADB connection.
 *
 * <p>Since Android 10+ SELinux denies {@code untrusted_app}→{@code shell}
 * {@code unix_stream_socket connectto} (the 1.1.5 failure mode), this version
 * does NOT expose a {@link android.net.LocalServerSocket}. Instead it follows
 * the pattern used by OpenBYD and our own {@code MirrorDaemon}:
 * <ol>
 *   <li>Acquire a system {@link Context} via reflective
 *       {@code ActivityThread.systemMain().getSystemContext()}.</li>
 *   <li>Publish a {@link Binder} subclass implementing the
 *       {@code com.byd.dashcast.beta.proxy.IProxyDaemon} contract.</li>
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

    /** AIDL-style descriptor for {@link Binder#attachInterface(android.os.IInterface, String)}. */
    public static final String DESCRIPTOR = "com.byd.dashcast.beta.proxy.IProxyDaemon";

    /** Broadcast action delivered to the app once the daemon is ready. */
    public static final String ACTION_PROXY_CONNECTED = "com.byd.dashcast.beta.PROXY_CONNECTED";

    /** Parcelable extra key carrying the daemon's {@link BinderParcelable}. */
    public static final String EXTRA_BINDER = "proxy_binder";

    /** App package that should receive the broadcast (must own the receiver). */
    public static final String TARGET_PKG = "com.byd.dashcast";

    /** Protocol version reported by {@link #TXN_WHOAMI}. Bump on any wire-incompatible change.
     *  v3 (build 235): adds {@link #TXN_CREATE_VIRTUAL_DISPLAY} and
     *  {@link #TXN_RELEASE_VIRTUAL_DISPLAY} (Phase 5a — cluster mini-mode POC).
     *  v7 (v1.2.63-beta, Phase A step 3): adds the PID-file + trigger-file
     *  rebroadcast plumbing (the wire protocol itself is unchanged, but the
     *  app uses {@code PROTOCOL_VERSION >= 7} to decide whether to attempt
     *  the fast-rebroadcast path during bootstrap).
     *  v8 (v1.2.70-beta, Phase A step 4): daemon hardening (OOM protection,
     *  atomic PID lock via in-JVM check, self-heal heartbeat every 10s).
     *  Wire protocol unchanged; bump signals to clients that suicide on
     *  lock-steal is now possible (helps log analysis).
     *  Purely additive — old clients keep working unchanged. */
    public static final String PROTOCOL_VERSION = "8";

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

    /** Transaction: no args → {@code long} (epoch ms). */
    public static final int TXN_PING   = android.os.IBinder.FIRST_CALL_TRANSACTION;        // 1
    /** Transaction: no args → {@code int uid, int pid, String ver}. */
    public static final int TXN_WHOAMI = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;    // 2
    /** Transaction: {@code String cmd} → {@code int exit, String combinedOutput}. */
    public static final int TXN_EXEC   = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;    // 3
    /** Transaction: no args → {@code String pipeSeparatedProbeResults}. Phase 4 feasibility. */
    public static final int TXN_PROBE_PHASE4 = android.os.IBinder.FIRST_CALL_TRANSACTION + 3; // 4
    /** Transaction: {@code int displayId, int l, int t, int r, int b} → nothing (or thrown exception).
     *  Phase 4a typed verb replacing {@code wm overscan L,T,R,B -d displayId}. */
    public static final int TXN_SET_OVERSCAN = android.os.IBinder.FIRST_CALL_TRANSACTION + 4; // 5
    /** Transaction: {@code String packageName} → {@code String spaceSeparatedPids}.
     *  Phase 4b typed verb replacing {@code pidof <pkg>} (state-poll hot path). */
    public static final int TXN_GET_PIDS = android.os.IBinder.FIRST_CALL_TRANSACTION + 5; // 6
    /** Transaction: {@code int type, int info, String str} → nothing (or thrown exception).
     *  Phase 4c typed verb replacing {@code service call AutoContainer 2 i32 … i32 … s16 "…"}
     *  used by {@code AdbLocalClient.sendInfo}. */
    public static final int TXN_AUTOCONTAINER_SEND_INFO = android.os.IBinder.FIRST_CALL_TRANSACTION + 6; // 7
    /** Transaction: {@code String packageName, int userId} → nothing (or thrown exception).
     *  Phase 4d typed verb replacing {@code am force-stop <pkg>} used by
     *  {@code AdbLocalClient.restoreBydOnCluster} / {@code restoreOriginCluster}
     *  (end-of-session teardown hot path). */
    public static final int TXN_FORCE_STOP_PACKAGE = android.os.IBinder.FIRST_CALL_TRANSACTION + 7; // 8
    /** Transaction: {@code String name, int w, int h, int dpi, int flags, Surface surface}
     *  → {@code int displayId}. Phase 5a — creates a VD on the daemon (uid 2000)
     *  with the {@code CAPTURE_VIDEO_OUTPUT} permission an app uid cannot hold.
     *  Mirrors OpenBYD 2.0 ClusterOverlayManager#launchOnVirtualDisplay. */
    public static final int TXN_CREATE_VIRTUAL_DISPLAY = android.os.IBinder.FIRST_CALL_TRANSACTION + 8; // 9
    /** Transaction: {@code int displayId} → nothing (or thrown exception).
     *  Phase 5a — releases a VD previously created by
     *  {@link #TXN_CREATE_VIRTUAL_DISPLAY}. */
    public static final int TXN_RELEASE_VIRTUAL_DISPLAY = android.os.IBinder.FIRST_CALL_TRANSACTION + 9; // 10
    /** Phase 5b — OpenBYD-style task relocation : am start, poll task id,
     *  loop 2&times; (moveTaskToDisplay + resizeTask + setFocusedRootTask).
     *  Bypasses {@code canPlaceEntityOnDisplay} which would otherwise reject
     *  non-resizeable activities (Waze, Maps, …) on secondary displays. */
    public static final int TXN_LAUNCH_AND_FORCE      = android.os.IBinder.FIRST_CALL_TRANSACTION + 10; // 11
    /** Phase 6 — move + resize an existing task in place (no am start).
     *  Args : {@code String pkg, int displayId, int l, int t, int r, int b}.
     *  Returns a multi-line log. Used by Diag's interactive move/resize UI
     *  on the fission display. */
    public static final int TXN_MOVE_AND_RESIZE       = android.os.IBinder.FIRST_CALL_TRANSACTION + 11; // 12

    /** Phase 6b — destroy every non-fullscreen, non-home stack on a display.
     *  Args : {@code int displayId}. Returns a multi-line log.
     *  Recovery verb for zombie split-screen-primary stacks that poison
     *  fission display launches. */
    public static final int TXN_CLEAN_FISSION_STACKS  = android.os.IBinder.FIRST_CALL_TRANSACTION + 12; // 13

    /** Set in {@link #main(String[])} once the system context is acquired, so
     *  {@link ProxyBinder} can hand it to {@link Phase4Probes} without re-acquiring. */
    private static volatile Context sSystemContext;

    /** Strong reference to the trigger {@link FileObserver}, kept alive for the
     *  lifetime of the daemon. {@code FileObserver} is delivered via a
     *  background thread internal to Android — no explicit Looper needed. */
    private static volatile FileObserver sTriggerObserver;

    /** Cached binder + intent re-used by {@link #emitBroadcast()} so a USR-1-
     *  style rebroadcast does not need to rebuild any state. */
    private static volatile ProxyBinder sBinder;

    private ProxyDaemonMain() {}

    public static void main(String[] args) {
        try {
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

    /** v1.2.70 hardening: lower our OOM score so Linux's low-memory killer
     *  reaches for foreground apps before us. uid=2000 can always write to
     *  its own /proc/self/oom_score_adj. -900 sits just above the framework
     *  reserved range (-1000..-900 used by system_server etc.). */
    private static void hardenAgainstOom() {
        try (FileOutputStream fos = new FileOutputStream("/proc/self/oom_score_adj")) {
            fos.write("-900".getBytes());
            log("oom_score_adj=-900 set");
        } catch (Throwable t) {
            log("hardenAgainstOom failed: " + t);
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
            if (pidFile.exists()) {
                String existing = readSmallFile(pidFile).trim();
                if (!existing.isEmpty()) {
                    int otherPid = -1;
                    try { otherPid = Integer.parseInt(existing); } catch (NumberFormatException ignore) {}
                    if (otherPid > 0 && otherPid != Process.myPid() && isLiveDaemon(otherPid)) {
                        return false;
                    }
                }
            }
            // Stale or absent: claim it.
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
                while (true) {
                    try {
                        Thread.sleep(1_000L);
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
                    // Every 10 ticks (10s): full self-heal (file + pid lock).
                    if (++tick % 10 == 0) {
                        try { healTriggerFile(); } catch (Throwable th) { log("heal trigger: " + th); }
                        try { healPidLock();     } catch (Throwable th) { log("heal pid: " + th); }
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
        log("self-heal heartbeat armed (1s poll + 10s heal)");
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
                    ExecResult er = runShell(cmd);
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
                        Phase4Verbs.setOverscan(displayId, l, t, r, b);
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
                        pids = Phase4Verbs.getPidsByPackage(pkg);
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
                        Phase4Verbs.autoContainerSendInfo(type, info, str);
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
                        Phase4Verbs.forceStopPackage(pkg, userId);
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
                    try {
                        int displayId = Phase4Verbs.createVirtualDisplay(
                                sSystemContext, name, w, h, dpi, surface, vflag);
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
                    }
                    return true;
                }
                case TXN_RELEASE_VIRTUAL_DISPLAY: {
                    data.enforceInterface(DESCRIPTOR);
                    int displayId = data.readInt();
                    try {
                        Phase4Verbs.releaseVirtualDisplay(displayId);
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
                        String log = Phase4Verbs.launchAndForce(pkg, cls, did, w, h);
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
                        String log = Phase4Verbs.moveAndResize(pkg, did, l, t, r, b);
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
                        String log = Phase4Verbs.cleanFissionStacks(did);
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
                case INTERFACE_TRANSACTION: {
                    if (reply != null) reply.writeString(DESCRIPTOR);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static final class ExecResult {
        final int    exit;
        final String output;
        ExecResult(int exit, String output) { this.exit = exit; this.output = output; }
    }

    private static ExecResult runShell(String cmd) {
        if (cmd == null) return new ExecResult(-1, "ERR null command");
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            // Build 195 / P4 — read the full stdout/stderr stream into a single
            // ByteArrayOutputStream then decode once. Replaces the per-line
            // BufferedReader + StringBuilder.append('\n') pattern which on a
            // 300-line dumpsys ate ~1000 String/StringBuilder allocations.
            // Trailing newlines are stripped to preserve the exact semantics
            // of the legacy line-by-line code (which never appended a final '\n').
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            byte[] chunk = new byte[4096];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(chunk)) > 0) baos.write(chunk, 0, n);
            }
            // 1.2.29 — bounded waitFor: a hung child (e.g. dumpsys on a wedged service,
            // pgrep on an MTK ROM that doesn't ship it) used to block the binder thread
            // forever. The pool has ~15 threads; N hangs = daemon unresponsive.
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                return new ExecResult(-1, "ERR timeout 30s");
            }
            int code = p.exitValue();
            String s = baos.toString("UTF-8");
            int end = s.length();
            while (end > 0) {
                char c = s.charAt(end - 1);
                if (c != '\n' && c != '\r') break;
                end--;
            }
            return new ExecResult(code, end == s.length() ? s : s.substring(0, end));
        } catch (Throwable t) {
            String msg = t.getMessage();
            return new ExecResult(-1, "ERR " + (msg == null ? t.getClass().getSimpleName() : msg));
        }
    }

    private static void log(String s) {
        System.out.println("[dashcast_proxy] " + s);
    }
}
