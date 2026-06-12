package com.byd.dashcast.infrastructure;

import android.content.Context;
// LOT 4 — Bitmap/BitmapFactory imports removed (captureClusterDisplay deleted).
import android.os.SystemClock;

import com.byd.dashcast.proxy.DaemonConfig;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.util.AppLogger;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

import java.io.File;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("try")
public class AdbLocalClient {
    // Capped at 4 threads to avoid OutOfMemoryError or socket exhaustion
    // if the user hammers the UI triggering slow ADB commands.
    // Named daemon threads → easier debugging and won't keep the process alive.
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "adb-local-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private static final String TAG = "AdbLocalClient";

    /** ADB TCP port — same for Android 7–10 in developer mode */
    private static final int ADB_PORT = 5555;

    // ──────────────────────────────────────────────────────────────────────────
    // AutoContainer service name — DL3 vs DL5 dispatch
    //   DiLink 3.0 (BYD Seal EU / Atto 3 …) → "AutoContainer" (PascalCase)
    //   DiLink 5.0 (BYD-AUTO API 32)       → "auto_container" (snake_case)
    // Confirmed by D11/D12 PASS on DL5 (log 22/05/2026) and by user on DL3.
    // Helper must never throw (hot path called from background thread).
    // ──────────────────────────────────────────────────────────────────────────
    static String autoContainerSvcName(Context ctx) {
        try {
            if (ctx != null
                    && com.byd.dashcast.platform.Platform.get().isDiLink5(ctx)) {
                return "auto_container";
            }
        } catch (Throwable ignore) { /* DL3 safe default below */ }
        return "AutoContainer";
    }

    public static boolean isDiLink5Safe(Context ctx) {
        try {
            return ctx != null
                    && com.byd.dashcast.platform.Platform.get().isDiLink5(ctx);
        } catch (Throwable ignore) { return false; }
    }

    /** True if running on DiLink 2 (alps / k65v1, single display 0). */
    public static boolean isDiLink2Safe(Context ctx) {
        try {
            return ctx != null
                    && com.byd.dashcast.platform.Platform.get().isDiLink2(ctx);
        } catch (Throwable ignore) { return false; }
    }

    /**
     * DL2 SAFETY GUARD — matches any {@code wm overscan|size|density} subcommand
     * (with any arguments, anywhere in the line, including pipelines and chains).
     *
     * <p>On DiLink 2 (alps / k65v1 / MT6765 / API 28) there is only physical
     * display 0 (verified L3/L5 of the DL2 RECON REPORT 22/05/2026). The MTK
     * fork silently falls back to display 0 when {@code -d N} targets a
     * non-existent display id, which shrinks the user's main UI screen
     * (field report: user set margins 80/50 → main screen got smaller).
     * Any such command is therefore unconditionally blocked on DL2.
     */
    private static final java.util.regex.Pattern P_DISPLAY_RESIZE =
            java.util.regex.Pattern.compile("(?s)\\bwm\\s+(overscan|size|density)\\b");

    public static boolean isDisplayResizeCmd(String cmd) {
        if (cmd == null) return false;
        return P_DISPLAY_RESIZE.matcher(cmd).find();
    }

    /**
     * Returns true and logs a warning when {@code cmd} must be blocked because
     * it is a display-resize command running on DL2. Centralised so every
     * shell entry point (legacy {@code executeShell*}, {@link com.byd.dashcast.proxy.ShellGateway})
     * applies the same guard.
     */
    public static boolean blockDiLink2Resize(Context ctx, String cmd) {
        if (isDiLink2Safe(ctx) && isDisplayResizeCmd(cmd)) {
            AppLogger.w(TAG, "DL2 BLOCK: refused resize cmd \"" + cmd
                    + "\" — single display 0, would shrink main screen");
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------

    /**
     * Executes a raw shell command via local ADB (asynchronous).
     */
    public static void executeShell(final Context context, final String command) {
        if (blockDiLink2Resize(context, command)) return;
        final Context appCtx = context.getApplicationContext();
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(appCtx)) {
                    AdbShellResponse r = dadb.shell(command);
                    AppLogger.d(TAG, "executeShell: " + command + " -> " + r.getAllOutput().trim());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "executeShell ERROR for: " + command, e);
                }
            }
        });
    }

    /** Executes a shell command and returns the result via callback (background thread). */
    public static void executeShellWithResult(final Context context, final String command,
                                              final Callback callback) {
        if (blockDiLink2Resize(context, command)) {
            if (callback != null) callback.onError(
                    "blocked on DiLink 2: no cluster display (would shrink main screen)");
            return;
        }
        final Context appCtx = context.getApplicationContext();
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(appCtx)) {
                String output = dadb.shell(command).getAllOutput().trim();
                AppLogger.d(TAG, "executeShellWithResult: " + command + " -> " + output);
                if (callback != null) callback.onSuccess(output);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                AppLogger.e(TAG, "executeShellWithResult ERROR: " + command, e);
                if (callback != null) callback.onError(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });
    }

    public interface Callback {
        /** Called on a background thread when the connection + grants are complete. */
        void onSuccess(String report);
        /** Called if the connection fails (port closed, timeout, refused…). */
        void onError(String error);
    }

    // LOT 4 — BitmapCallback interface removed: only used by captureClusterDisplay
    // (also removed). No external caller across the codebase.

    // Grep pattern uses the [m] trick to prevent grep from matching its own cmdline.
    // "[m]irrordaemon" matches "mirrordaemon" in process names but not in the
    // grep cmdline (which literally contains "[m]irrordaemon").
    private static final String DAEMON_GREP = "grep -E '[m]irrordaemon'";
    private static final String KILL_DAEMON_CMD =
            "ps -A | " + DAEMON_GREP + " | awk '{print $2}'" +
            " | xargs -r kill -9 2>/dev/null; echo killed";

    public static void startMirrorDaemon(final Context context) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // Kill existing daemon if present.
                    // IMPORTANT: the daemon renames itself to "com.byd.dashcast.mirrordaemon" via
                    // setArgV0(), not "byd.mirror.daemon" → grep on both patterns.
                    String psOut = dadb.shell(
                            "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput().trim();
                    if (!psOut.isEmpty()) {
                        dadb.shell(KILL_DAEMON_CMD);
                        AppLogger.i(TAG, "Old MirrorDaemon(s) killed.");
                        Thread.sleep(500);
                    }
                    String apkPath = context.getPackageCodePath();
                    // Java timestamp → unique filename per launch
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(new java.util.Date());
                    final String logPath = "/data/local/tmp/mirrordaemon_" + ts + ".log";
                    final String latestLink = "/data/local/tmp/mirrordaemon_latest.log";
                    // setsid: detaches the process from the ADB session group
                    // → survives dadb connection close (otherwise SIGHUP possible)
                    // CLASSPATH inline (no export &&) as Commander APK does it
                    // -Xnoimage-dex2oat: avoids AOT crash at startup
                    String cmd = "setsid sh -c 'CLASSPATH=" + apkPath
                            + " /system/bin/app_process64 -Xnoimage-dex2oat /system/bin"
                            + " --nice-name=byd.mirror.daemon"
                            + " com.byd.dashcast.proxy.daemon.MirrorDaemon"
                            + " </dev/null >" + logPath + " 2>&1' &"
                            + " ln -sf " + logPath + " " + latestLink;
                    dadb.shell(cmd);
                    AppLogger.i(TAG, "MirrorDaemon launched → " + logPath);

                    // Verification: is the process alive after 3s?
                    Thread.sleep(3000);
                    String psCheck = dadb.shell(
                            "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput().trim();
                    if (!psCheck.isEmpty()) {
                        AppLogger.i(TAG, "MirrorDaemon ACTIVE ✓  " + psCheck);
                    } else {
                        AppLogger.e(TAG, "MirrorDaemon NOT FOUND after 3s — reading log:");
                        String logContent = safeOut(dadb.shell("cat " + logPath + " 2>&1").getAllOutput());
                        AppLogger.e(TAG, "mirrordaemon.log = [" + logContent + "]");
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "MirrorDaemon startup error", e);
                }
            }
        });
    }

    // ── Private helper — dadb connection (key already authorized, no popup) ───────────

    /** Lock for key generation: prevents TOCTOU if two ADB methods are called
     *  simultaneously on first launch (before .key/.pub files exist). */
    private static final Object sKeyLock = new Object();

    private static Dadb connect(Context context) throws Exception {
        File privateKey = new File(context.getFilesDir(), "adb.key");
        File publicKey  = new File(context.getFilesDir(), "adb.pub");
        AdbKeyPair keyPair;
        synchronized (sKeyLock) {
            if (!privateKey.exists() || !publicKey.exists()) {
                AdbKeyPair.generate(privateKey, publicKey);
            }
            keyPair = AdbKeyPair.read(privateKey, publicKey);
        }
        
        // Retry loop to give the user time to click 'Allow USB Debugging' if the popup appears
        int retries = 5;
        Exception lastE = null;
        while (retries > 0) {
            try {
                return Dadb.create("localhost", ADB_PORT, keyPair);
            } catch (Exception e) {
                lastE = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                AppLogger.w(TAG, "ADB connect exception (popup pending?), retrying in 2s... (" + retries + " left)");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                retries--;
            }
        }
        throw lastE;
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
    public static void grantOverlayPermission(final Context context, final Callback callback) {
        final Context appCtx = context.getApplicationContext();
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(appCtx)) {
                    String cmd = "appops set " + appCtx.getPackageName()
                            + " SYSTEM_ALERT_WINDOW allow";
                    AdbShellResponse r = dadb.shell(cmd + " 2>&1");
                    AppLogger.i(TAG, "grantOverlayPermission → " + cmd
                            + " → '" + r.getAllOutput().trim() + "'");
                    callback.onSuccess(r.getAllOutput().trim());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "grantOverlayPermission ERREUR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-overlay-grant
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
     * @param displayId  cluster display ID (1 on DiLink 3.0)
     */
    public static void restoreBydOnCluster(final Context context,
            final String targetPackage, // nullable: package to force-stop before restore
            final Callback callback) {
        // v1.2.78 — invalidate ClusterManager's fast-path flag NOW (synchronously, before
        // the async dispatch). Qt will return to native mode as soon as sendInfo(18)
        // lands; the VirtualDisplay persists, so subsequent activate() calls must
        // take the warm path (30→6s→16) instead of the true fast path.
        com.byd.dashcast.cluster.display.ClusterManager.notifyProjectionStopped();
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "Restoring BYD cluster"
                        + (targetPackage != null ? " (target=" + targetPackage + ")" : ""));
                // Phase 4d: try the typed daemon path for the whole sequence
                // (force-stop + sendInfo×2). On any failure we fall through to
                // the legacy shell sequence below so semantics are preserved.
                // DL5: skip typed path — Phase4ProcessVerbs hardcodes "AutoContainer".
                if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context)) {
                    final long t0 = SystemClock.elapsedRealtime();
                    try {
                        if (!ProxyClient.isConnected()) {
                            // Never call connect() from an executor thread — blocks 10–15 s.
                            // ProxyKeeperService reconnects in background; skip to legacy path.
                            throw new Exception("proxy not connected — skip typed path");
                        }
                        StringBuilder sb = new StringBuilder();
                        if (targetPackage != null && !targetPackage.isEmpty()) {
                            // Phase 4d.1 (build 180): userId=0 (current user) instead of -1.
                            // USER_ALL (-1) is silently no-op on some API 29 BYD framework
                            // builds — the call returned without throwing but the package
                            // process remained alive (Waze stayed visible on display 0
                            // after restoreBydOnCluster reported "typed ok").
                            ProxyClient.forceStopPackage(targetPackage, 0);
                            sb.append("force-stop ").append(targetPackage).append(" (typed,u=0)\n");
                            Thread.sleep(500);
                            verifyForceStop(targetPackage, sb);
                        }
                        ProxyClient.autoContainerSendInfo(1000, 18, "");
                        sb.append("sendInfo(18) : OK (typed)\n");
                        Thread.sleep(1000);
                        ProxyClient.autoContainerSendInfo(1000, 0, "");
                        sb.append("sendInfo(0)  : OK (typed)\n");
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.log(TAG, "beta restoreBydOnCluster typed ok (" + dt + "ms)");
                        callback.onSuccess("BYD restored \u2713 (typed)\n" + sb);
                        return;
                    } catch (Throwable t) {
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            callback.onError("interrupted");
                            return;
                        }
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.w(TAG, "beta restoreBydOnCluster typed failed after " + dt
                                + "ms, falling back to ADB shell: " + t.getMessage());
                        // fall through to legacy path
                    }
                }
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // 0. Force-stop target package BEFORE sendInfo(18).
                    // Without this, the app task (launched via trampoline on display 1) remains
                    // registered in ActivityManager: when sendInfo(18) releases the Qt surface,
                    // Android relocates the orphan task to display 0 → the app appears
                    // on the tablet's main screen.
                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        dadb.shell("am force-stop " + targetPackage + " 2>&1");
                        sb.append("force-stop ").append(targetPackage).append("\n");
                        Thread.sleep(500);
                    }

                    AdbShellResponse rStop = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    AdbShellResponse rRestore = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRestore.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "restoreBydOnCluster -> OK");
                    callback.onSuccess("BYD restored \u2713\n" + sb);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "restoreBydOnCluster ERROR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-restore-thread
    }

    /**
     * Origin cluster — restores the Qt cluster to the screen size configured by the user.
     *
     * Sequence:
     *   1. sendInfo(1000, 18)            — close projection (投屏关闭)          → wait 6s
     *   2. sendInfo(1000,  0)            — refresh Qt stream                   → wait 6s
     *   3. sendInfo(1000, screenSizeCmd) — switch Qt to the correct resolution
     *
     * @param screenSizeCmd  size code: 29=8.8" (Atto 3), 30=12.3" (Seal EU — CONFIRMED), 31=10.25" (Seal U-DMI)
     */
    public static void restoreOriginCluster(final Context context, final int screenSizeCmd,
            final String targetPackage, // nullable: package to force-stop before restore
            final Callback callback) {
        // v1.2.78 — see restoreBydOnCluster() above for rationale.
        com.byd.dashcast.cluster.display.ClusterManager.notifyProjectionStopped();
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "restoreOriginCluster screenSize=" + screenSizeCmd
                        + (targetPackage != null ? " target=" + targetPackage : ""));
                // Phase 4d: try the typed daemon path (force-stop + sendInfo×3).
                // Falls back to the legacy shell flow on any failure.
                // DL5: skip typed path — Phase4ProcessVerbs hardcodes "AutoContainer".
                if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context)) {
                    final long t0 = SystemClock.elapsedRealtime();
                    boolean callbackFired = false;
                    try {
                        if (!ProxyClient.isConnected()) {
                            // Never call connect() from an executor thread — blocks 10–15 s.
                            // ProxyKeeperService reconnects in background; skip to legacy path.
                            throw new Exception("proxy not connected — skip typed path");
                        }
                        StringBuilder sb = new StringBuilder();
                        if (targetPackage != null && !targetPackage.isEmpty()) {
                            // Phase 4d.1 (build 180): see restoreBydOnCluster above.
                            ProxyClient.forceStopPackage(targetPackage, 0);
                            sb.append("force-stop ").append(targetPackage).append(" (typed,u=0)\n");
                            Thread.sleep(500);
                            verifyForceStop(targetPackage, sb);
                        }
                        ProxyClient.autoContainerSendInfo(1000, 18, "");
                        sb.append("sendInfo(18) : OK (typed)\n");
                        Thread.sleep(2000);
                        ProxyClient.autoContainerSendInfo(1000, 0, "");
                        sb.append("sendInfo(0)  : OK (typed)\n");
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.log(TAG, "beta restoreOriginCluster typed ok (" + dt + "ms)");
                        // Fire callback now \u2014 UI unblocked, ClusterManager state cleaned up.
                        // screenSizeCmd is cosmetic and completes in background.
                        callbackFired = true;
                        callback.onSuccess("Origin cluster restored \u2713 (typed)\n" + sb);
                        Thread.sleep(3000);
                        ProxyClient.autoContainerSendInfo(1000, screenSizeCmd, "");
                        AppLogger.log(TAG, "restoreOriginCluster screenSize(cmd=" + screenSizeCmd + ") sent in background");
                        return;
                    } catch (Throwable t) {
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            return; // callback already fired; background screenSizeCmd aborted
                        }
                        if (callbackFired) {
                            // Background screenSizeCmd failed \u2014 log only, callback already done
                            AppLogger.w(TAG, "restoreOriginCluster background screenSize failed: " + t.getMessage());
                            return;
                        }
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.w(TAG, "beta restoreOriginCluster typed failed after " + dt
                                + "ms, falling back to ADB shell: " + t.getMessage());
                        // fall through to legacy path
                    }
                }
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Force-stop target package before restore (same reason as
                    // restoreBydOnCluster: avoid task relocation to display 0).
                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        dadb.shell("am force-stop " + targetPackage + " 2>&1");
                        sb.append("force-stop ").append(targetPackage).append("\n");
                        Thread.sleep(500);
                    }

                    AdbShellResponse rStop = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");
                    Thread.sleep(2000);

                    AdbShellResponse rRefresh = dadb.shell(
                        "service call " + autoContainerSvcName(context) + " 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRefresh.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "restoreOriginCluster -> OK (screenSize in background)");
                    // Fire callback now \u2014 UI unblocked, ClusterManager state cleaned up.
                    // screenSizeCmd is cosmetic and completes in background after 3s Qt settling.
                    callback.onSuccess("Origin cluster restored \u2713\n" + sb);
                    try {
                        Thread.sleep(3000);
                        dadb.shell("service call " + autoContainerSvcName(context)
                                + " 2 i32 1000 i32 " + screenSizeCmd + " s16 \"\" 2>&1");
                        AppLogger.log(TAG, "restoreOriginCluster screenSize(cmd=" + screenSizeCmd + ") sent in background");
                    } catch (Exception bg) {
                        if (bg instanceof InterruptedException) Thread.currentThread().interrupt();
                        AppLogger.w(TAG, "restoreOriginCluster background screenSize failed: " + bg.getMessage());
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "restoreOriginCluster ERROR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-origin-cluster-thread
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────────
    // sendInfo ADB relay — bypasses SecurityException (uid=10100 not in whitelist JSON)
    // dm-verity prevents patching /system/etc/container_comm_cfg.json on this hardware.
    // uid=2000 (shell ADB) passes checkSignatures() in AutoContainerService.
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sends sendInfo(type, infoInt, infoStr) to the AutoContainer service via ADB shell relay.
     *
     * Equivalent to: service call AutoContainer 2 i32 <type> i32 <infoInt> s16 "<infoStr>"
     * uid=2000 (shell) passes checkSignatures → no SecurityException.
     *
     * Callback is called from a background thread — use runOnUiThread if necessary.
     */
    public static void sendInfo(final Context context,
                                final int type, final int infoInt, final String infoStr,
                                final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                // Phase 4c: try the typed daemon path first. P13 (build 176)
                // proved binder.transact(2, ...) on AutoContainer is accepted
                // from uid 2000 with descriptor android.os.IAutoContainer.
                // On any failure we fall through to the legacy ADB shell
                // wrapper below — semantics are preserved for callers that
                // only inspect callback.onSuccess(String) for emptiness.
                // DL5: skip typed path — Phase4ProcessVerbs hardcodes the DL3 service
                // name ("AutoContainer") which does not exist on DL5.
                if (!DaemonConfig.isLegacyPathEnabled(context) && !isDiLink5Safe(context)) {
                    final long t0 = SystemClock.elapsedRealtime();
                    try {
                        if (!ProxyClient.isConnected()) {
                            // Never call connect() from an executor thread — blocks 10–15 s.
                            // ProxyKeeperService reconnects in background; skip to legacy path.
                            throw new Exception("proxy not connected — skip typed path");
                        }
                        ProxyClient.autoContainerSendInfo(type, infoInt, infoStr);
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.log(TAG, "beta sendInfo typed ok (" + dt + "ms): "
                                + type + "," + infoInt + ",\"" + (infoStr == null ? "" : infoStr) + "\"");
                        // Legacy wrapper returned `service call` stdout (Parcel
                        // hex dump). Typed path has no equivalent payload —
                        // empty string matches what every existing caller
                        // already expects (none of them parses the dump).
                        if (callback != null) callback.onSuccess("");
                        return;
                    } catch (Throwable t) {
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.w(TAG, "beta sendInfo typed failed after " + dt
                                + "ms, falling back to ADB shell: " + t.getMessage());
                        // fall through to legacy path
                    }
                }
                try (Dadb dadb = connect(context)) {
                    // Escape shell metacharacters inside the double-quoted argument:
                    //   \  → must be first to avoid double-escaping
                    //   "  → terminates the quoted string
                    //   $  → triggers variable / arithmetic / command expansion
                    //   `  → triggers command substitution
                    String safeStr = (infoStr != null ? infoStr : "")
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("$",  "\\$")
                            .replace("`",  "\\`");
                    String svc = autoContainerSvcName(context);
                    String cmd = "service call " + svc + " 2 i32 " + type
                               + " i32 " + infoInt + " s16 \"" + safeStr + "\" 2>&1";
                    AppLogger.log(TAG, "sendInfo ADB: " + cmd);
                    AdbShellResponse r = dadb.shell(cmd);
                    String out = r.getAllOutput().trim();
                    AppLogger.log(TAG, "sendInfo ADB(" + type + "," + infoInt + ") → " + out);
                    if (callback != null) callback.onSuccess(out);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendInfo ADB ERREUR", e);
                    if (callback != null) callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-sendinfo-thread
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
    public static void dumpSignatureAndPermissions(final Context context) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                final String dTag = "SigDump";
                try (Dadb dadb = connect(context)) {
                    String pkg = context.getPackageName();

                    AppLogger.i(dTag, "=== Build & shell uid ===");
                    AppLogger.i(dTag, "id: " + dadb.shell("id 2>&1").getAllOutput().trim());
                    AppLogger.i(dTag, "build.tags: " + dadb.shell(
                            "getprop ro.build.tags 2>&1").getAllOutput().trim());
                    AppLogger.i(dTag, "build.fingerprint: " + dadb.shell(
                            "getprop ro.build.fingerprint 2>&1").getAllOutput().trim());

                    AppLogger.i(dTag, "=== Notre APK (" + pkg + ") signature & version ===");
                    String ourSig = dadb.shell(
                            "dumpsys package " + pkg
                            + " | grep -E 'versionCode|versionName|signatures' "
                            + "| head -10 2>&1").getAllOutput().trim();
                    for (String line : ourSig.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== ROM/AutoContainer signature (com.xdja.containerservice) ===");
                    String romSig = dadb.shell(
                            "dumpsys package com.xdja.containerservice "
                            + "| grep -E 'signatures|sharedUser' | head -5 2>&1").getAllOutput().trim();
                    for (String line : romSig.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== Permissions granted to our app ===");
                    String perms = dadb.shell(
                            "dumpsys package " + pkg
                            + " | grep -E "
                            + "'INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS|"
                            + "BYDAUTO_SPEED|BYDAUTO_GEARBOX|granted=true|granted=false' "
                            + "| head -30 2>&1").getAllOutput().trim();
                    for (String line : perms.split("\n")) AppLogger.i(dTag, "  " + line);

                    AppLogger.i(dTag, "=== FIN dump ===");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(dTag, "dumpSignatureAndPermissions ERREUR", e);
                }
            }
        }); // adb-sigdump-thread
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
    public static void forceStopApp(final Context context, final String packageName,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "forceStop " + packageName + " ...");
                // Phase 7: typed daemon path — findTask + removeTask + forceStopPackage.
                // Replaces the 3-step ADB shell chain (dumpsys recents + am task remove
                // + TaskRemover app_process + am force-stop). Falls through to ADB on
                // any failure so semantics are fully preserved for callers.
                if (!DaemonConfig.isLegacyPathEnabled(context) && ProxyClient.isConnected()) {
                    final long t0 = SystemClock.elapsedRealtime();
                    try {
                        int taskId = ProxyClient.findTaskIdForPackage(packageName);
                        if (taskId >= 0) {
                            AppLogger.d(TAG, "forceStopApp typed: removeTask taskId=" + taskId);
                            ProxyClient.removeTask(taskId);
                            Thread.sleep(300);
                        }
                        ProxyClient.forceStopPackage(packageName, 0);
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.log(TAG, "forceStopApp typed ok (" + dt + "ms): "
                                + packageName + " taskId=" + taskId);
                        if (callback != null) callback.onSuccess("force-stop OK (typed)");
                        return;
                    } catch (Throwable t) {
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            if (callback != null) callback.onError("interrupted");
                            return;
                        }
                        long dt = SystemClock.elapsedRealtime() - t0;
                        AppLogger.w(TAG, "forceStopApp typed failed after " + dt
                                + "ms, falling back to ADB: " + t.getMessage());
                        // fall through to ADB path below
                    }
                }
                try (Dadb dadb = connect(context)) {
                    // Extract Task IDs associated with the package and remove them from Recents BEFORE force-stopping.
                    // On Android 10, dumpsys activity recents prints lines like:
                    //   * Recent #0: TaskRecord{3a9c7f5 #42 A=com.example.app U=0 StackId=1 sz=1}
                    // We extract the task ID (#42) using sed BRE — NOT grep -o (its \+ is unsupported
                    // on Android's busybox grep). We also must NOT match the recents index (#0).
                    String apkPath = context.getPackageCodePath();
                    String cleanRecentsCmd =
                            // 1. Find TaskRecord lines containing this package, extract the task ID
                            //    (the number inside "TaskRecord{<hash> #<ID> ...}")
                            "TASKS=$(dumpsys activity recents 2>/dev/null | grep 'TaskRecord' | grep -F '" + packageName + "' " +
                            "| sed -n 's/.*TaskRecord{[^ ]* #\\([0-9]*\\).*/\\1/p' | sort -u); " +
                            "echo \"[DashCast-recents] pkg=" + packageName + " tasks=$TASKS\"; " +
                            // 2. Remove each task: try IActivityTaskManager.removeTask() via reflection
                            //    (app_process, uid=2000 shell). Also try am task remove as OEM fallback.
                            "for t in $TASKS; do " +
                            "  am task remove $t 2>/dev/null; " +
                            "  export CLASSPATH=" + apkPath + "; " +
                            "  /system/bin/app_process64 -Xnoimage-dex2oat /system/bin com.byd.dashcast.proxy.daemon.TaskRemover \"$t\" 2>/dev/null; " +
                            "  /system/bin/app_process -Xnoimage-dex2oat /system/bin com.byd.dashcast.proxy.daemon.TaskRemover \"$t\" 2>/dev/null; " +
                            "done; ";
                    
                    AdbShellResponse r = dadb.shell(cleanRecentsCmd + "am force-stop " + packageName + " 2>&1 && echo STOPPED");
                    String out = r.getAllOutput().trim();
                    AppLogger.log(TAG, "am force-stop " + packageName + " -> " + out);
                    if (callback != null) {
                        if (out.contains("STOPPED") || out.isEmpty()) {
                            callback.onSuccess("force-stop OK");
                        } else {
                            callback.onError(out);
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "forceStopApp ERREUR", e);
                    if (callback != null) callback.onError(msg);
                }
            }
        }); // adb-forcestop-thread
    }

    // LOT 4 — captureClusterDisplay removed: dead code (0 caller across the
    // codebase, only referenced via reflection comment in AppLogger cleanup).
    // The cluster preview is now sourced from the mirror surface (no screencap).

    private static String safeOut(String s) {
        if (s == null) return "(null)";
        s = s.trim();
        return s.isEmpty() ? "(empty)" : s;
    }

    /**
     * Phase 4d.1 verification helper — after a typed forceStopPackage call,
     * queries the daemon for surviving PIDs of {@code pkg}. Logs a WARN line
     * if the kill was ineffective so we can spot silently-failing
     * IActivityManager.forceStopPackage invocations in device logs (root cause
     * of "Waze stays on display 0 after restoreBydOnCluster typed ok" in 179).
     */
    private static void verifyForceStop(String pkg, StringBuilder sb) {
        try {
            String pids = ProxyClient.getPidsByPackage(pkg);
            if (pids != null && !pids.trim().isEmpty()) {
                String alive = pids.trim();
                AppLogger.w(TAG, "beta force-stop ineffective for " + pkg
                        + " (pids=" + alive + ") — escalating kill -9");
                sb.append("  WARN: still alive, pids=").append(alive).append("\n");
                // v1.2.9 (Bug 1/2 défense en profondeur) : si IActivityManager
                // .forceStopPackage a échoué silencieusement (cas connu BYD AUTO
                // ROM avec certaines apps système-like), escalader avec kill -9
                // sur les PIDs survivants via le daemon (uid=2000, droit kill
                // sur process même uid).
                try {
                    String killCmd = "kill -9 " + alive.replaceAll("\\s+", " ");
                    ProxyClient.runShell(killCmd);
                    sb.append("  escalated: ").append(killCmd).append("\n");
                    // Petit délai pour laisser le kernel libérer les PIDs avant re-check.
                    try { Thread.sleep(200); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    String pids2 = ProxyClient.getPidsByPackage(pkg);
                    if (pids2 != null && !pids2.trim().isEmpty()) {
                        AppLogger.w(TAG, "verifyForceStop: " + pkg
                                + " STILL alive after kill -9 (pids=" + pids2.trim() + ")");
                        sb.append("  WARN: still alive after kill -9, pids=")
                                .append(pids2.trim()).append("\n");
                    } else {
                        AppLogger.i(TAG, "verifyForceStop: " + pkg
                                + " killed after escalation ✓");
                        sb.append("  verified killed after escalation\n");
                    }
                } catch (Throwable escalateError) {
                    AppLogger.w(TAG, "verifyForceStop: kill -9 escalation failed for "
                            + pkg + ": " + escalateError.getMessage());
                    sb.append("  WARN: escalation failed: ")
                            .append(escalateError.getMessage()).append("\n");
                }
            } else {
                sb.append("  verified killed\n");
            }
        } catch (Throwable t) {
            // Verification must not break the teardown sequence.
            AppLogger.w(TAG, "verifyForceStop(" + pkg + ") threw: " + t.getMessage());
        }
    }

}
