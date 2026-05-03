package com.byd.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.util.HashMap;

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

public class AdbLocalClient {
    // Capped at 4 threads to avoid OutOfMemoryError or socket exhaustion
    // if the user hammers the UI triggering slow ADB commands.
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);

    private static final String TAG = "AdbLocalClient";

    /** ADB TCP port — same for Android 7–10 in developer mode */
    private static final int ADB_PORT = 5555;

    // -------------------------------------------------------------------------

    /**
     * Executes a raw shell command via local ADB (asynchronous).
     */
    public static void executeShell(final Context context, final String command) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
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
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(context)) {
                String output = safeOut(dadb.shell(command).getAllOutput()).trim();
                AppLogger.d(TAG, "executeShellWithResult: " + command + " -> " + output);
                if (callback != null) callback.onSuccess(output);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                AppLogger.e(TAG, "executeShellWithResult ERROR: " + command, e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    public interface Callback {
        /** Called on a background thread when the connection + grants are complete. */
        void onSuccess(String report);
        /** Called if the connection fails (port closed, timeout, refused…). */
        void onError(String error);
    }

    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
        void onError(String error);
    }

    // ── Freedom state ─────────────────────────────────────────────────────────

    /** Freedom state (com.xdja.clusterdemo) — required before any cluster projection. */
    public enum FreedomStatus {
        /** com.xdja.clusterdemo is not installed on the system. */
        NOT_INSTALLED,
        /** Installed but inactive: fission VirtualDisplay absent — display 1 inaccessible. */
        INACTIVE,
        /** Active in 全屏导航 mode: fission VirtualDisplay present — display 1 accessible. */
        ACTIVE
    }

    public interface FreedomStateCallback {
        void onResult(FreedomStatus status);
    }

    /**
     * Checks Freedom state via ADB (background thread).
     *
     * Two sequential tests:
     *   1. pm list packages com.xdja.clusterdemo → NOT_INSTALLED if absent
     *   2. ps -A | grep com.xdja.clusterdemo     → ACTIVE if process found, INACTIVE otherwise
     *
     * Note: Freedom OFF = display 1 absent from DisplayManager (confirmed 18/04/2026).
     * On ADB error, returns INACTIVE (will trigger startFreedom as fallback).
     */
    // Grep pattern uses the [m] trick to prevent grep from matching its own cmdline.
    // "[m]irrordaemon" matches "mirrordaemon" in process names but not in the
    // grep cmdline (which literally contains "[m]irrordaemon").
    private static final String DAEMON_GREP = "grep -E '[m]irrordaemon'";
    private static final String KILL_DAEMON_CMD =
            "ps -A | " + DAEMON_GREP + " | awk '{print $2}'" +
            " | xargs -r kill -9 2>/dev/null; echo killed";

    /**
     * Scans active MirrorDaemon processes and returns a human-readable summary.
     * Format: "PID  USER  NAME\n..." or "(no process found)"
     */
    public static void scanMirrorDaemon(final Context context, final Callback callback) {
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(context)) {
                String ps = safeOut(dadb.shell(
                        "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput()).trim();
                boolean found = !ps.isEmpty();
                int count = found ? ps.split("\n").length : 0;
                String msg = found
                        ? count + " MirrorDaemon process(es) detected:\n" + ps
                        : "(no active MirrorDaemon process)";
                AppLogger.i(TAG, "scanMirrorDaemon: " + msg);
                if (callback != null) callback.onSuccess(msg);
            } catch (Exception e) {
                AppLogger.e(TAG, "scanMirrorDaemon failed", e);
                if (callback != null) callback.onError("Scan error: " + e.getMessage());
            }
        });
    }

    /**
     * Kills all existing MirrorDaemon processes and confirms the result via callback.
     */
    public static void killMirrorDaemon(final Context context, final Callback callback) {
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(context)) {
                String before = safeOut(dadb.shell(
                        "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput()).trim();
                AppLogger.i(TAG, "killMirrorDaemon — before: " + (before.isEmpty() ? "(none)" : before));
                dadb.shell(KILL_DAEMON_CMD);
                Thread.sleep(800);
                String after = safeOut(dadb.shell(
                        "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput()).trim();
                boolean ok = after.isEmpty();
                String msg = ok
                        ? "MirrorDaemon(s) killed ✓"
                        : "Processes still running: " + after;
                AppLogger.i(TAG, "killMirrorDaemon — after: " + msg);
                if (ok) { if (callback != null) callback.onSuccess(msg); }
                else    { if (callback != null) callback.onError(msg); }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                AppLogger.e(TAG, "killMirrorDaemon failed", e);
                if (callback != null) callback.onError("Error: " + e.getMessage());
            }
        });
    }

    public static void startMirrorDaemon(final Context context) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // Kill existing daemon if present.
                    // IMPORTANT: the daemon renames itself to "com.byd.myapp.mirrordaemon" via
                    // setArgV0(), not "byd.mirror.daemon" → grep on both patterns.
                    String psOut = safeOut(dadb.shell(
                            "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput());
                    if (!psOut.trim().isEmpty()) {
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
                            + " com.byd.myapp.daemon.MirrorDaemon"
                            + " </dev/null >" + logPath + " 2>&1' &"
                            + " ln -sf " + logPath + " " + latestLink;
                    dadb.shell(cmd);
                    AppLogger.i(TAG, "MirrorDaemon launched → " + logPath);

                    // Verification: is the process alive after 3s?
                    Thread.sleep(3000);
                    String psCheck = safeOut(dadb.shell(
                            "ps -A | " + DAEMON_GREP + " 2>&1").getAllOutput());
                    if (!psCheck.trim().isEmpty()) {
                        AppLogger.i(TAG, "MirrorDaemon ACTIVE ✓  " + psCheck.trim());
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

    // Sniffer grep patterns: [x] trick to avoid auto-matching.
    // The sniffer spawns two background processes: logcat (capture) + sh/sleep (snapshots).
    private static final String SNIFFER_GREP =
            "grep -E '[l]ogcat -v threadtime|[s]leep 30'";
    public static final String SNIFFER_KILL_CMD =
            "rm -f /data/local/tmp/.sniffer_run; "
            + "for p in $(ps -A | awk '/[s]leep 15/ {print $2}; /[s]leep 5/ {print $2}; /[l]ogcat -v threadtime/ {print $2}; /[l]ogcat -b events/ {print $2}'); do kill -9 $p 2>/dev/null; done; "
            + "echo killed";

    /**
     * Scans active Sniffer processes (logcat + snapshot loop).
     */
    public static void scanSniffer(final Context context, final Callback callback) {
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(context)) {
                String logcatPs = safeOut(dadb.shell(
                        "ps -A | grep -E '[l]ogcat -v threadtime|[l]ogcat -b events' 2>&1").getAllOutput()).trim();
                String sleepPs = safeOut(dadb.shell(
                        "ps -A | grep -E '[s]leep 15|[s]leep 5' 2>&1").getAllOutput()).trim();
                boolean hasLogcat = !logcatPs.isEmpty();
                boolean hasLoop   = !sleepPs.isEmpty();
                String msg;
                if (!hasLogcat && !hasLoop) {
                    msg = "(no active Sniffer process)";
                } else {
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    if (hasLogcat) {
                        sb.append("logcat (capture):\n").append(logcatPs).append("\n");
                        count++;
                    }
                    if (hasLoop) {
                        sb.append("sleep/snapshot loop:\n").append(sleepPs);
                        count++;
                    }
                    msg = count + " Sniffer process(es) detected:\n" + sb.toString().trim();
                }
                AppLogger.i(TAG, "scanSniffer: " + msg);
                if (callback != null) callback.onSuccess(msg);
            } catch (Exception e) {
                AppLogger.e(TAG, "scanSniffer failed", e);
                if (callback != null) callback.onError("Scan error: " + e.getMessage());
            }
        });
    }

    /**
     * Kills all Sniffer processes (logcat + snapshot loop).
     */
    public static void killSniffer(final Context context, final Callback callback) {
        sExecutor.execute(() -> {
            try (Dadb dadb = connect(context)) {
                dadb.shell(SNIFFER_KILL_CMD);
                Thread.sleep(600);
                String logcatAfter = safeOut(dadb.shell(
                        "ps -A | grep -E '[l]ogcat -v threadtime|[l]ogcat -b events' 2>&1").getAllOutput()).trim();
                String sleepAfter = safeOut(dadb.shell(
                        "ps -A | grep -E '[s]leep 15|[s]leep 5' 2>&1").getAllOutput()).trim();
                boolean ok = logcatAfter.isEmpty() && sleepAfter.isEmpty();
                String msg = ok ? "Sniffer stopped ✓" : "Processes still active: " + logcatAfter + " " + sleepAfter;
                AppLogger.i(TAG, "killSniffer: " + msg);
                if (ok) { if (callback != null) callback.onSuccess(msg); }
                else    { if (callback != null) callback.onError(msg); }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                AppLogger.e(TAG, "killSniffer failed", e);
                if (callback != null) callback.onError("Error: " + e.getMessage());
            }
        });
    }

    public static void checkFreedomState(final Context context, final FreedomStateCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // 1. Is Freedom installed?
                    String pkgOut = safeOut(dadb.shell(
                            "pm list packages com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                    if (!pkgOut.contains("com.xdja.clusterdemo")) {
                        AppLogger.w(TAG, "checkFreedomState: not installed");
                        callback.onResult(FreedomStatus.NOT_INSTALLED);
                        return;
                    }
                    // 2. Is Freedom actually running in memory?
                    //    NOTE: we no longer check "dumpsys display | grep fission"!
                    //    The fission VirtualDisplay is managed by AutoDisplayService and is ALWAYS present
                    //    even when Freedom is force-stopped. We must check for the process instead.
                    String pidOut = safeOut(dadb.shell(
                            "ps -A | grep com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                    if (!pidOut.isEmpty()) {
                        AppLogger.i(TAG, "checkFreedomState: ACTIVE (process found)");
                        callback.onResult(FreedomStatus.ACTIVE);
                    } else {
                        AppLogger.i(TAG, "checkFreedomState: INACTIVE (process not found)");
                        callback.onResult(FreedomStatus.INACTIVE);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "checkFreedomState ERROR", e);
                    callback.onResult(FreedomStatus.INACTIVE); // fallback → startFreedom will be tried
                }
            }
        }); // adb-check-freedom
    }

    // -------------------------------------------------------------------------

    /**
     * Starts the local ADB connection in a background thread.
     *
     * Strategy:
     *  1. setprop persist.sys.acc.whitelist — BYD-specific mechanism (DiLink whitelist)
     *  2. abb_exec package grant — attempt via direct Binder (UID 1000 possible on some ROMs)
     *  3. BYD service enumeration via service list (for future proxy)
     */
    public static void connectAndGrant(final Context context, final Callback callback) {
        sExecutor.execute(() -> {
            try {
                File privateKey = new File(context.getFilesDir(), "adb.key");
                File publicKey  = new File(context.getFilesDir(), "adb.pub");
                boolean newKey  = !privateKey.exists() || !publicKey.exists();

                AppLogger.log(TAG, newKey
                        ? "New ADB key generated → popup expected"
                        : "Existing ADB key reloaded");
                AppLogger.log(TAG, "Connecting dadb → localhost:" + ADB_PORT + " …");
                try (Dadb dadb = connect(context)) {
                AppLogger.log(TAG, "ADB connection established ✓");

                StringBuilder sb = new StringBuilder();
                String pkg = context.getPackageName();

                // ── PASS 1: persist.sys.acc.whitelist (BYD DiLink mechanism) ─────────────
                sb.append("=== [1] BYD DiLink whitelist ===\n");

                // Read current value
                AdbShellResponse rGet = dadb.shell("getprop persist.sys.acc.whitelist 2>&1");
                String currentWhitelist = rGet.getAllOutput().trim();
                sb.append("Current value: '").append(currentWhitelist).append("'\n");

                // Add our package if not already present
                String newVal = currentWhitelist.isEmpty() ? pkg
                        : (currentWhitelist.contains(pkg) ? currentWhitelist
                        : currentWhitelist + "," + pkg);

                AdbShellResponse rSet = dadb.shell(
                        "setprop persist.sys.acc.whitelist \"" + newVal + "\" 2>&1 && echo SETPROP_OK");
                boolean setpropOk = rSet.getAllOutput().contains("SETPROP_OK");
                sb.append("setprop: ").append(setpropOk ? "OK" : "ERROR — " + rSet.getAllOutput().trim()).append("\n");

                if (setpropOk) {
                    // Verify the value was persisted
                    AdbShellResponse rVerify = dadb.shell("getprop persist.sys.acc.whitelist");
                    sb.append("Value after: '").append(rVerify.getAllOutput().trim()).append("'\n");
                    sb.append("\n⚠ Whitelist updated.\n")
                      .append("→ Fully close the app then relaunch it.\n")
                      .append("  If it works, *_GET permissions will be granted on next reboot.\n");
                } else {
                    sb.append("→ setprop refused (protected property on this ROM).\n");
                }
                sb.append("\n");

                // ── PASS 2: test pm grant on _COMMON (dangerous?) and _GET (signature) ──
                boolean abbSupported = dadb.supportsFeature("abb_exec");
                sb.append("\n=== [2] abb_exec available: ").append(abbSupported).append(" ===\n");

                // Check effective UID
                AdbShellResponse rUid = dadb.shell("id 2>&1");
                sb.append("Shell UID: ").append(rUid.getAllOutput().trim()).append("\n");

                // _COMMON: dangerous — pm grant works (all granted here)
                // _GET:    signature — always refused via pm grant
                String[] commonPerms = {
                    "android.permission.BYDAUTO_SPEED_COMMON",
                    "android.permission.BYDAUTO_ENERGY_COMMON",
                    "android.permission.BYDAUTO_GEARBOX_COMMON",
                    "android.permission.BYDAUTO_BODYWORK_COMMON",
                    "android.permission.BYDAUTO_AC_COMMON",
                    "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
                    "android.permission.BYDAUTO_ENGINE_COMMON",
                    "android.permission.BYDAUTO_INSTRUMENT_COMMON",
                    "android.permission.BYDAUTO_LIGHT_COMMON",
                    "android.permission.BYDAUTO_TYRE_COMMON",
                    "android.permission.BYDAUTO_RADAR_COMMON",
                    // SAFETYBELT_COMMON removed: unknown permission on Seal EU ROM
                    // "android.permission.BYDAUTO_SAFETYBELT_COMMON",
                };
                String[] getPerms = {
                    "android.permission.BYDAUTO_SPEED_GET",
                    "android.permission.BYDAUTO_ENERGY_GET",
                    "android.permission.BYDAUTO_GEARBOX_GET",
                };
                sb.append("\n── pm grant ALL _COMMON (dangerous) ──\n");
                for (String perm : commonPerms) {
                    String shortName = perm.replace("android.permission.BYDAUTO_", "");
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm + " 2>&1 && echo GRANTED || echo DENIED");
                    String out = r.getAllOutput().trim();
                    sb.append(shortName).append(": ").append(
                        out.contains("GRANTED") ? "OK ✓ (dangerous — granted)" :
                        out.contains("not a changeable") ? "SIGNATURE — not grantable via pm" :
                        out.contains("Unknown permission") ? "⚠️ Not available on this ROM" :
                        out).append("\n");
                }
                sb.append("── pm grant _GET (signature — for reference) ──\n");
                for (String perm : getPerms) {
                    String shortName = perm.replace("android.permission.BYDAUTO_", "");
                    AdbShellResponse r = dadb.shell("pm grant " + pkg + " " + perm + " 2>&1 && echo GRANTED || echo DENIED");
                    String out = r.getAllOutput().trim();
                    sb.append(shortName).append(": ").append(
                        out.contains("GRANTED") ? "OK ✓ (unexpected)" :
                        out.contains("not a changeable") ? "SIGNATURE (expected)" :
                        out).append("\n");
                }
                sb.append("\n");

                // ── PASS 3: BYD service enumeration (for future proxy) ─────────────────────
                sb.append("=== [3] Services BYD accessibles via shell ===\n");
                AdbShellResponse rSvc = dadb.shell(
                        "service list 2>/dev/null | grep -i 'byd\\|auto\\|vehicle\\|car' | head -20");
                sb.append(rSvc.getAllOutput().isEmpty() ? "(no BYD service found)\n" : rSvc.getAllOutput());

                // Check if /proc or /sys expose vehicle data
                AdbShellResponse rSys = dadb.shell(
                        "ls /sys/class/byd* /proc/byd* /data/system/byd* 2>/dev/null | head -10");
                if (!rSys.getAllOutput().trim().isEmpty()) {
                    sb.append("BYD system files:\n").append(rSys.getAllOutput().trim()).append("\n");
                }
                sb.append("\n");

                // ── Final permission state (raw dump + broad grep) ────────────────────────
                // BYD ROM format may differ from AOSP standard — dump the
                // "declared permissions" + "install permissions" + "runtime permissions" sections
                AdbShellResponse rFinal = dadb.shell(
                        "dumpsys package " + pkg + " 2>/dev/null | grep -iE 'bydauto|BYDAUTO|requested perm|install perm|runtime perm|grantedPermissions' | head -40");
                sb.append("=== Current permissions (raw dump) ===\n");
                String finalOut = rFinal.getAllOutput().trim();
                if (finalOut.isEmpty()) {
                    // Fallback : dump complet de la section permissions
                    AdbShellResponse rFull = dadb.shell(
                            "dumpsys package " + pkg + " 2>/dev/null | grep -A2 -E 'permission|Permission' | grep -iE 'byd|granted|denied' | head -30");
                    finalOut = rFull.getAllOutput().trim();
                }
                sb.append(finalOut.isEmpty() ? "(no entries — check APK is installed)" : finalOut).append("\n");

                AppLogger.log(TAG, "ADB local finished ✓");
                callback.onSuccess(sb.toString());
                }

            } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                AppLogger.e(TAG, "ADB local failed", e);
                AppLogger.log(TAG, "ADB local ERROR — " + msg);
                callback.onError(msg);
            }
        }); // adb-local-thread
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
        int retries = 15;
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
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String cmd = "appops set " + context.getPackageName()
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

    // ── Freedom: automatic startup ────────────────────────────────────────────
    /**
     * Configures Freedom (com.xdja.clusterdemo) in "全屏导航" (full-screen) mode,
     * then starts it via ADB shell.
     *
     * Mechanism: Freedom persists its navigation mode in
     *   /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
     * under the key "navigationType" (int) — Java-serialized HashMap (ObjectOutputStream).
     * Value 1 = 全屏导航 (full-screen projection, confirmed in car v2.29).
     * Without file (default) = navigationType absent → BootReceiver returns without creating VirtualDisplay.
     *
     * Sequence:
     *   1. force-stop Freedom (clean state before restart)
     *   2. write properties.xml with navigationType=1 (全屏导航)
     *   3. am broadcast BOOT_COMPLETED → BootReceiver reads the file and creates the VirtualDisplay
     *
     * Callback is called on an ADB background thread.
     */
    public static void startFreedom(final Context context, final Callback callback) {
        startFreedom(context, false, callback);
    }

    /**
     * @param skipDisplayCheck  true if the caller already knows the fission VirtualDisplay is absent
     *                          (e.g. just after checkFreedomState → INACTIVE). Avoids a redundant
     *                          second ADB round-trip to check the same thing.
     */
    public static void startFreedom(final Context context, final boolean skipDisplayCheck,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    // 1. Check if Freedom is already running in memory.
                    //    Skipped if skipDisplayCheck=true (caller already confirmed it's absent).
                    if (!skipDisplayCheck) {
                        String pidCheck = safeOut(dadb.shell(
                                "ps -A | grep com.xdja.clusterdemo 2>&1").getAllOutput()).trim();
                        if (!pidCheck.isEmpty()) {
                            AppLogger.i(TAG, "startFreedom: Freedom (com.xdja.clusterdemo) already active → not restarting");
                            callback.onSuccess("Freedom already active");
                            return;
                        }
                    } else {
                        AppLogger.d(TAG, "startFreedom: skip pidCheck (Freedom already confirmed inactive)");
                    }

                    // 2. Freedom inactive → safety force-stop before clean startup
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    AppLogger.i(TAG, "startFreedom: force-stop Freedom");
                    Thread.sleep(500);

                    // 3. Write properties.xml with navigationType=1 (全屏导航)
                    //    IMPORTANT: do NOT delete the file — navigationType=0 (default without file)
                    //    triggers immediate return in BootReceiver.setup() without creating the VirtualDisplay.
                    //    The file is a Java-serialized HashMap (ObjectOutputStream).
                    writeNavigationTypeFile(dadb);
                    AppLogger.i(TAG, "startFreedom: properties.xml written (navigationType=1 → 全屏导航)");

                    // 4. Start Freedom fully transparently.
                    //    Instead of opening MainActivity (which causes a visual flash on screen),
                    //    we simulate BOOT_COMPLETED. Freedom only needs to run its BootReceiver
                    //    to read our file and establish the Binder bridge.
                    AppLogger.i(TAG, "startFreedom: transparent startup via am broadcast BOOT_COMPLETED");
                    String startOut = safeOut(dadb.shell(
                            "am broadcast -a android.intent.action.BOOT_COMPLETED -n com.xdja.clusterdemo/com.byd.windowmanager.receivers.BootReceiver 2>&1"
                    ).getAllOutput()).trim();
                    AppLogger.i(TAG, "startFreedom am broadcast → " + startOut);
                    callback.onSuccess(startOut);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "startFreedom ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-start-freedom
    }

    /**
     * Writes /sdcard/Android/data/com.xdja.clusterdemo/data/properties.xml
     * with navigationType=1 (全屏导航 — full-screen projection).
     *
     * Freedom reads this file via ObjectInputStream → HashMap<String, Object>.
     * BootReceiver.setup() checks navigationType > 0 to trigger VirtualDisplay creation.
     * With the default value 0 (file absent), setup() returns immediately without action.
     */
    private static void writeNavigationTypeFile(Dadb dadb) throws Exception {
        // Serialize HashMap {"navigationType": Integer(1)} with Java ObjectOutputStream
        HashMap<String, Object> prefs = new HashMap<>();
        prefs.put("navigationType", Integer.valueOf(1));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(prefs);
        oos.close();
        byte[] bytes = baos.toByteArray();

        // Base64-encode and write via ADB shell (base64 available in Android toybox)
        String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        String dir  = "/sdcard/Android/data/com.xdja.clusterdemo/data";
        String path = dir + "/properties.xml";
        dadb.shell("mkdir -p " + dir + " 2>&1");
        String writeResult = safeOut(dadb.shell(
                "echo '" + b64 + "' | base64 -d > " + path + " 2>&1"
        ).getAllOutput()).trim();
        AppLogger.i(TAG, "writeNavigationTypeFile → '" + writeResult + "' (" + bytes.length + " bytes)");
    }


    // ── TEST 4 : Broadcast BOOT_COMPLETED vers le BootReceiver de Freedom ──────
    /**
     * Sends BOOT_COMPLETED directly to Freedom's BootReceiver without opening its UI.
     *
     * Sequence:
     *   1. am broadcast BOOT_COMPLETED → com.xdja.clusterdemo/.BootReceiver
     *   2. Wait 5s for the VirtualDisplay to appear
     *   3. dumpsys display | grep fission → verify if cluster VirtualDisplay was created
     *
     * If VirtualDisplay is created → startFreedom() can be replaced by this broadcast
     * (headless, no Freedom UI visible).
     */
    public static void sendBootReceiverBroadcast(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Snapshot AVANT
                    sb.append("── AVANT broadcast ──\n");
                    String before = safeOut(dadb.shell(
                            "dumpsys display 2>&1 | grep -i fission"
                    ).getAllOutput()).trim();
                    sb.append(before.isEmpty() ? "(aucun display fission)" : before).append("\n\n");
                    AppLogger.i(TAG, "TEST4 avant : " + (before.isEmpty() ? "vide" : before));

                    // Force-stop Freedom first for a clean state
                    sb.append("── Force-stop Freedom ──\n");
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    Thread.sleep(500);
                    sb.append("OK\n\n");

                    // Targeted BOOT_COMPLETED broadcast
                    sb.append("── am broadcast BOOT_COMPLETED → BootReceiver ──\n");
                    String broadcastOut = safeOut(dadb.shell(
                            "am broadcast -a android.intent.action.BOOT_COMPLETED" +
                            " -n com.xdja.clusterdemo/com.byd.windowmanager.receivers.BootReceiver 2>&1"
                    ).getAllOutput()).trim();
                    sb.append(broadcastOut).append("\n\n");
                    AppLogger.i(TAG, "TEST4 broadcast → " + broadcastOut);

                    // Wait 5s for VirtualDisplay creation
                    sb.append("── Waiting 5s (VirtualDisplay creation) ──\n");
                    Thread.sleep(5000);

                    // Snapshot AFTER
                    sb.append("── AFTER broadcast ──\n");
                    String after = safeOut(dadb.shell(
                            "dumpsys display 2>&1 | grep -i fission"
                    ).getAllOutput()).trim();
                    sb.append(after.isEmpty() ? "(aucun display fission)" : after).append("\n\n");
                    AppLogger.i(TAG, "TEST4 after: " + (after.isEmpty() ? "empty" : after));

                    // Conclusion
                    boolean created = !after.isEmpty() && after.contains("fission");
                    sb.append(created
                            ? "✅ VirtualDisplay created! Broadcast alone is sufficient → Freedom headless possible."
                            : "❌ VirtualDisplay absent. Le BootReceiver seul ne suffit pas.");
                    long ms = System.currentTimeMillis() - t0;
                    sb.append("\n\n(").append(ms).append(" ms)");

                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendBootReceiverBroadcast ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-test4-boot-receiver
    }


    /**
     * Activates the cluster in presentation mode (sendInfo 30 + 16 only).
     *
     *   1. sendInfo(1000, 30) — 12.3" size ALWAYS: only mode where ADAS screen is not stretched
     *   2. sendInfo(1000, 16) — Qt standby → releases display for projection
     *
     * Does NOT include sendInfo(18) or sendInfo(0) which are restore commands.
     */
    public static void activateClusterDisplay(final Context context, final Callback callback) {
        checkFreedomState(context, new FreedomStateCallback() {
            @Override public void onResult(FreedomStatus status) {
                if (status == FreedomStatus.INACTIVE) {
                    AppLogger.i(TAG, "activateClusterDisplay: Freedom inactive → starting first");
                    startFreedom(context, true, new Callback() {
                        @Override public void onSuccess(String ignored) {
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override public void run() { doActivateClusterDisplayLocked(context, callback); }
                            }, 2000);
                        }
                        @Override public void onError(String err) {
                            doActivateClusterDisplayLocked(context, callback);
                        }
                    });
                } else {
                    doActivateClusterDisplayLocked(context, callback);
                }
            }
        });
    }

    private static void doActivateClusterDisplayLocked(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Full confirmed sequence (logcat 03/05/2026, BYD Seal EU):
                    //   sendInfo(30) → 6s → sendInfo(16) → 6s → sendInfo(35) → VirtualDisplay in ~280ms
                    sb.append("── sendInfo(1000, 30) = 12.3\" profile ──\n");
                    AdbShellResponse r30 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(6000);

                    sb.append("\n── sendInfo(1000, 16) = 全屏投屏开启 Qt standby ──\n");
                    AdbShellResponse r16 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(r16.getAllOutput().trim()).append("\n");
                    Thread.sleep(6000);

                    sb.append("\n── sendInfo(1000, 35) = Di4.0 mode → VirtualDisplay creation ──\n");
                    AdbShellResponse r35 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 35 s16 \"\" 2>&1");
                    sb.append(r35.getAllOutput().trim()).append("\n");

                    AppLogger.endTiming(TAG, t0, "activateClusterDisplay finished");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "activateClusterDisplay ERREUR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-activate-cluster-thread
    }

    /**
     * TEST 10 — Cluster activation + restore sequence (Seal EU)
     *
     * Sequence:
     *   1. sendInfo(1000, 30) — Seal EU screen size (CONFIRMED 16/04/2026)
     *   2. wait 1s
     *   3. sendInfo(1000, 16) — Qt standby
     *   4. wait 2s
     *   5. sendInfo(1000, 18) — close projection (投屏关闭)
     *   6. wait 1s
     *   7. sendInfo(1000,  0) — refresh Qt stream
     *   8. Logcat AutoContainer
     */
    public static void runDisplayOneLaunch(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                long t0 = AppLogger.startTiming();
                AppLogger.i(TAG, "runDisplayOneLaunch started [" + Thread.currentThread().getName() + "]");
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    dadb.shell("logcat -c 2>&1");

                    // ── 1. sendInfo(30) — Seal EU screen size ─────────────────
                    sb.append("── sendInfo(1000, 30) = Seal EU screen size (12.3\") ──\n");
                    AdbShellResponse rSend30 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append(rSend30.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    // ── 2. sendInfo(16) — Qt standby ─────────────────────────
                    sb.append("\n── sendInfo(1000, 16) = Qt standby ──\n");
                    AdbShellResponse rSend16 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 16 s16 \"\" 2>&1");
                    sb.append(rSend16.getAllOutput().trim()).append("\n");
                    Thread.sleep(2000);

                    // ── 3. sendInfo(18) — fermer projection ──────────────────
                    sb.append("\n── sendInfo(1000, 18) = fermer projection (投屏关闭) ──\n");
                    AdbShellResponse rSend18 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append(rSend18.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    // ── 4. sendInfo(0) — refresh Qt stream ───────────────────
                    sb.append("\n── sendInfo(1000, 0) = refresh Qt stream ──\n");
                    AdbShellResponse rSend0 = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append(rSend0.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    // ── 5. Logcat ─────────────────────────────────────────────
                    sb.append("\n── Logcat (AutoContainer) ──\n");
                    AdbShellResponse rLog = dadb.shell(
                        "logcat -d 2>&1 | grep -iE 'AutoContainer|sendInfo' | tail -20");
                    sb.append(rLog.getAllOutput().trim().isEmpty() ? "(no entries)" : rLog.getAllOutput().trim()).append("\n");

                    AppLogger.endTiming(TAG, t0, "runDisplayOneLaunch finished");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "runDisplayOneLaunch ERREUR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-display1-thread
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
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "Restoring BYD cluster"
                        + (targetPackage != null ? " (target=" + targetPackage + ")" : ""));
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

                    // 1. Force-stop Freedom (com.xdja.clusterdemo).
                    //    Freedom is started automatically at our app launch (v1.86).
                    //    If still running, it reclaims display immediately after sendInfo(18)
                    //    and cancels the restore. Must stop it first.
                    dadb.shell("am force-stop com.xdja.clusterdemo 2>&1");
                    sb.append("force-stop Freedom\n");
                    Thread.sleep(500);

                    AdbShellResponse rStop = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");
                    Thread.sleep(1000);

                    AdbShellResponse rRestore = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
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
     *   1. sendInfo(1000, screenSizeCmd) — switch Qt to the correct resolution
     *   2. sendInfo(1000, 18)            — close projection (投屏关闭)
     *   3. sendInfo(1000,  0)            — refresh Qt stream
     *
     * @param screenSizeCmd  size code: 29=8.8" (Atto 3), 30=12.3" (Seal U-DMI), 31=10.25" (Seal EU)
     */
    public static void restoreOriginCluster(final Context context, final int screenSizeCmd,
            final String targetPackage, // nullable: package to force-stop before restore
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                AppLogger.log(TAG, "restoreOriginCluster screenSize=" + screenSizeCmd
                        + (targetPackage != null ? " target=" + targetPackage : ""));
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // Force-stop target package before restore (same reason as
                    // restoreBydOnCluster: avoid task relocation to display 0).
                    if (targetPackage != null && !targetPackage.isEmpty()) {
                        dadb.shell("am force-stop " + targetPackage + " 2>&1");
                        sb.append("force-stop ").append(targetPackage).append("\n");
                        Thread.sleep(500);
                    }

                    AdbShellResponse rSize = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 " + screenSizeCmd + " s16 \"\" 2>&1");
                    sb.append("sendInfo(").append(screenSizeCmd).append(") : ");
                    sb.append(rSize.getAllOutput().trim()).append("\n");

                    AdbShellResponse rStop = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 18 s16 \"\" 2>&1");
                    sb.append("sendInfo(18) : ").append(rStop.getAllOutput().trim()).append("\n");

                    AdbShellResponse rRefresh = dadb.shell(
                        "service call AutoContainer 2 i32 1000 i32 0 s16 \"\" 2>&1");
                    sb.append("sendInfo(0)  : ").append(rRefresh.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "restoreOriginCluster -> OK");
                    callback.onSuccess("Origin cluster restored \u2713\n" + sb);
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
                try (Dadb dadb = connect(context)) {
                    String safeStr = (infoStr != null ? infoStr : "").replace("\"", "\\\"");
                    String cmd = "service call AutoContainer 2 i32 " + type
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
     *   - dumpsys package com.byd.myapp | grep -E "Signature|signatures|version"
     *   - dumpsys package com.xdja.containerservice | grep -E "Signature|signatures"
     *   - pm dump com.byd.myapp | grep -E "INTERNAL_SYSTEM_WINDOW|MANAGE_ACTIVITY_STACKS|INJECT_EVENTS"
     *   - dumpsys package com.byd.myapp | grep -A 1 "install permissions:"
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

                    AppLogger.i(dTag, "=== Appels signature checks (test direct) ===");
                    // Tente un am start --display 1 minimal pour confirmer le verdict
                    AppLogger.i(dTag, "uid=2000 am start --display 1 (notre activity, dry run):");
                    String testLaunch = dadb.shell(
                            "am start-activity -W --display 1 "
                            + "-n " + pkg + "/.dashboard.ClusterTrampolineActivity 2>&1 "
                            + "| head -20").getAllOutput().trim();
                    for (String line : testLaunch.split("\n")) AppLogger.i(dTag, "  " + line);

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

    // ── TEST 12 : Sonde taille display cluster + essais cmd 29/30/31 + wm size ──

    /**
     * Tests different approaches to fix the cluster display resolution.
     *
     * The AutoDisplayService VirtualDisplay is created in 1920×1080 (default values
     * in decompiled com.xdja.containerservice code), but the physical panel is
     * ~1920×480 (ratio ~4:1). Result: vertical stretching.
     *
     * This test tries sequentially:
     *   1. Dump current state of display 1 (wm size, dumpsys display)
     *   2. sendInfo(1000, 29) — 切换到8.8寸屏 (might change Qt surface)
     *   3. Re-dump display 1 to see if dimensions changed
     *   4. sendInfo(1000, 30) — 切换到12.3寸屏 (restore original config)
     *   5. Try wm size 1920x480 -d 1 (force logical resolution)
     *   6. Post-wm size dump
     *   7. wm size reset -d 1 (cleanup)
     *
     * Result is a text report with before/after dumps for each command
     */
    public static void runClusterDisplaySizeTest(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    // ── 1. Initial state ─────────────────────────────────────
                    sb.append("=== [1] INITIAL CLUSTER DISPLAY STATE ===\n");

                    AdbShellResponse rSize = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 : ").append(rSize.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDensity = dadb.shell("wm density -d 1 2>&1");
                    sb.append("wm density -d 1 : ").append(rDensity.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dumpOut = rDump.getAllOutput().trim();
                    sb.append("dumpsys display id=1:\n").append(
                            dumpOut.isEmpty() ? "  (not found in dumpsys)" : dumpOut).append("\n");

                    // Surface info via SurfaceFlinger
                    AdbShellResponse rSf = dadb.shell(
                            "dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'fission|virtual|cluster' | head -5");
                    String sfOut = rSf.getAllOutput().trim();
                    if (!sfOut.isEmpty()) {
                        sb.append("SurfaceFlinger :\n").append(sfOut).append("\n");
                    }
                    sb.append("\n");

                    // ── 2. sendInfo(1000, 29) — switch 8.8" ──────────────────
                    sb.append("=== [2] sendInfo(1000, 29) — 切换到8.8寸屏 ===\n");
                    AdbShellResponse r29 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 29 s16 \"\" 2>&1");
                    sb.append("Result: ").append(r29.getAllOutput().trim()).append("\n");

                    // Wait for Qt to apply the change
                    Thread.sleep(1500);

                    AdbShellResponse rPost29 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 after cmd=29: ").append(rPost29.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump29 = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dump29 = rDump29.getAllOutput().trim();
                    sb.append("dumpsys display id=1 after cmd=29:\n").append(
                            dump29.isEmpty() ? "  (not found)" : dump29).append("\n\n");

                    // ── 3. sendInfo(1000, 30) — Seal EU mode (12.3") ─────────
                    sb.append("=== [3] sendInfo(1000, 30) — Seal EU (12.3\") — CONFIRMED 16/04/2026 ===\n");
                    AdbShellResponse r30 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append("Result: ").append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(1500);

                    AdbShellResponse rPost30 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 after cmd=30: ").append(rPost30.getAllOutput().trim()).append("\n");
                    sb.append("\n");

                    // ── 4. sendInfo(1000, 31) — switch 10.25" ────────────────
                    sb.append("=== [4] sendInfo(1000, 31) — 切换到10.25寸屏 ===\n");
                    AdbShellResponse r31 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 31 s16 \"\" 2>&1");
                    sb.append("Result: ").append(r31.getAllOutput().trim()).append("\n");
                    Thread.sleep(1500);

                    AdbShellResponse rPost31 = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 after cmd=31: ").append(rPost31.getAllOutput().trim()).append("\n");
                    sb.append("\n");

                    // Restore 12.3"
                    dadb.shell("service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    Thread.sleep(500);

                    // ── 5. wm size 1920x480 -d 1 ─────────────────────────────
                    sb.append("=== [5] wm size 1920x480 -d 1 ===\n");
                    AdbShellResponse rWm = dadb.shell("wm size 1920x480 -d 1 2>&1");
                    sb.append("Command result: ").append(rWm.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    AdbShellResponse rPostWm = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 after: ").append(rPostWm.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDumpWm = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -10");
                    String dumpWm = rDumpWm.getAllOutput().trim();
                    sb.append("dumpsys display id=1 after:\n").append(
                            dumpWm.isEmpty() ? "  (not found)" : dumpWm).append("\n\n");

                    // ── 6. Reset ──────────────────────────────────────────────
                    sb.append("=== [6] wm size reset -d 1 (cleanup) ===\n");
                    AdbShellResponse rReset = dadb.shell("wm size reset -d 1 2>&1");
                    sb.append("Result: ").append(rReset.getAllOutput().trim()).append("\n");

                    AdbShellResponse rFinal = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 final: ").append(rFinal.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "TEST 12 finished ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "TEST 12 ERROR", e);
                    callback.onError(msg);
                }
            }
        }); // adb-display-size-test
    }

    /**
     * Sends a cluster screen size change command to Qt.
     *   cmd 29 = 切换到8.8寸屏  (8.8" — BYD Atto 3)
     *   cmd 30 = 切换到12.3寸屏 (12.3" — Seal U-DMI, use on Seal EU to fix ADAS)
     *   cmd 31 = 切换到10.25寸屏 (10.25" — Seal EU native)
     * Returns a wm size -d 1 report before/after the command
     */
    public static void sendClusterScreenSize(final Context context, final int sizeCmd,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();

                    String label = sizeCmd == 29 ? "8.8\"" : sizeCmd == 30 ? "12.3\"" : "10.25\"";
                    sb.append("sendInfo(1000, ").append(sizeCmd).append(") → ").append(label).append("\n\n");

                    AdbShellResponse rBefore = dadb.shell("wm size -d 1 2>&1");
                    sb.append("Before: ").append(rBefore.getAllOutput().trim()).append("\n");

                    AdbShellResponse rCmd = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 " + sizeCmd + " s16 \"\" 2>&1");
                    sb.append("Cmd:    ").append(rCmd.getAllOutput().trim()).append("\n");

                    Thread.sleep(1500);

                    AdbShellResponse rAfter = dadb.shell("wm size -d 1 2>&1");
                    sb.append("After: ").append(rAfter.getAllOutput().trim()).append("\n");

                    AdbShellResponse rDump = dadb.shell(
                            "dumpsys display 2>/dev/null | grep -A5 'mDisplayId=1' | head -8");
                    String dump = rDump.getAllOutput().trim();
                    if (!dump.isEmpty())
                        sb.append("\ndumpsys display id=1 :\n").append(dump).append("\n");

                    AdbShellResponse rSf = dadb.shell(
                            "dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'fission|virtual' | head -3");
                    String sf = rSf.getAllOutput().trim();
                    if (!sf.isEmpty())
                        sb.append("\nSurfaceFlinger :\n").append(sf).append("\n");

                    AppLogger.log(TAG, "sendClusterScreenSize(" + sizeCmd + ") ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "sendClusterScreenSize(" + sizeCmd + ") ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * Resets the cluster display size to default:
     *   1. sendInfo(1000, 30) — 切换到12.3寸屏 (Qt default state, 1920×1080)
     *   2. wm size reset -d 1 — cancel any Android logical override
     * Use after testing cmd 29/31 which may have disrupted the display
     */
    public static void resetClusterDisplaySize(final Context context, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("🔄 Restoring default size\n\n");

                    AdbShellResponse r30 = dadb.shell(
                            "service call AutoContainer 2 i32 1000 i32 30 s16 \"\" 2>&1");
                    sb.append("sendInfo(1000,30) 切换到12.3寸屏 : ")
                      .append(r30.getAllOutput().trim()).append("\n");
                    Thread.sleep(500);

                    AdbShellResponse rReset = dadb.shell("wm size reset -d 1 2>&1");
                    sb.append("wm size reset -d 1: ").append(rReset.getAllOutput().trim()).append("\n");
                    Thread.sleep(300);

                    AdbShellResponse rFinal = dadb.shell("wm size -d 1 2>&1");
                    sb.append("wm size -d 1 final ").append(rFinal.getAllOutput().trim()).append("\n");

                    AppLogger.log(TAG, "resetClusterDisplaySize ✓");
                    callback.onSuccess(sb.toString());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "resetClusterDisplaySize ERREUR", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-display-reset
    }

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
                try (Dadb dadb = connect(context)) {
                    AdbShellResponse r = dadb.shell("am force-stop " + packageName + " 2>&1 && echo STOPPED");
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

    /**
     * Launches a third-party app via MirrorDaemon (uid=2000) with explicit FREEFORM bounds.
     */
    public static void launchDirectWithBounds(final Context context,
            final String targetPackage, final int displayId,
            final int left, final int top, final int right, final int bottom,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    android.content.pm.PackageManager pm = context.getPackageManager();
                    android.content.Intent li = pm.getLaunchIntentForPackage(targetPackage);
                    if (li == null) {
                        try {
                            android.content.pm.PackageInfo pi = pm.getPackageInfo(targetPackage, android.content.pm.PackageManager.GET_ACTIVITIES);
                            if (pi.activities != null && pi.activities.length > 0) {
                                li = new android.content.Intent();
                                li.setComponent(new android.content.ComponentName(targetPackage, pi.activities[0].name));
                            }
                        } catch (Exception ignored) {}
                    }
                    if (li == null || li.getComponent() == null) {
                        callback.onError("No activity found for " + targetPackage);
                        return;
                    }
                    
                    AppLogger.i(TAG, "Broadcast daemon_launch_bounds pour " + targetPackage);
                    android.content.Intent intent = new android.content.Intent("com.byd.myapp.MIRROR_DAEMON_LAUNCH");
                    intent.putExtra("pkg", li.getComponent().getPackageName());
                    intent.putExtra("cls", li.getComponent().getClassName());
                    intent.putExtra("displayId", displayId);
                    intent.putExtra("bounds_l", left);
                    intent.putExtra("bounds_t", top);
                    intent.putExtra("bounds_r", right);
                    intent.putExtra("bounds_b", bottom);
                    context.sendBroadcast(intent);
                    
                    callback.onSuccess("Bounds broadcast sent to Daemon.");
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.e(TAG, "launchDirectWithBounds failed", e);
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // adb-trampoline-bounds-thread
    }

    /**
     * Captures a frame of the cluster display via screencap (uid=2000 = guaranteed SurfaceFlinger access).
     * Saves to the app's external cache dir; the app can read it directly (no
     * READ_EXTERNAL_STORAGE required for the package-specific directory o 29).
     */
    public static void captureClusterDisplay(final Context context,
            final int displayId, final BitmapCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    File cacheDir = context.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = context.getCacheDir();
                    File outFile = new File(cacheDir, "cluster_live.png");
                    // Chemin ADB : /storage/emulated/0 → /sdcard (symlink standard)
                    String remotePath = outFile.getAbsolutePath()
                            .replace("/storage/emulated/0", "/sdcard");
                    dadb.shell("screencap -d " + displayId + " -p " + remotePath);
                    Bitmap bm = BitmapFactory.decodeFile(outFile.getAbsolutePath());
                    if (bm != null) {
                        callback.onBitmap(bm);
                    } else {
                        callback.onError("decodeFile null: " + outFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    AppLogger.w(TAG, "captureClusterDisplay erreur: " + e.getMessage());
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }); // screenshot-mirror-thread
    }

    /**
     * Reads a text file via ADB shell (cat) and copies it to getExternalFilesDir.
     * Avoids the need for READ_EXTERNAL_STORAGE to read files in
     */
    public interface ReadFileCallback {
        void onSuccess(java.io.File localCopy);
        void onError(String error);
    }

    public static void readFileViaAdb(final Context context, final String remotePath,
                                      final String localName, final ReadFileCallback callback) {
        sExecutor.execute(new Runnable() {
            @Override public void run() {
                try (Dadb dadb = connect(context)) {
                    String content = safeOut(
                            dadb.shell("cat " + remotePath + " 2>&1").getAllOutput());
                    if (content.contains("No such file") || content.equals("(empty)")) {
                        callback.onError("File not found: " + remotePath);
                        return;
                    }
                    java.io.File dst = new java.io.File(
                            context.getExternalFilesDir(null), localName);
                    try (java.io.FileWriter fw = new java.io.FileWriter(dst, false)) {
                        fw.write(content);
                    }
                    callback.onSuccess(dst);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private static String safeOut(String s) {
        if (s == null) return "(null)";
        s = s.trim();
        return s.isEmpty() ? "(emptyy)" : s;
    }

}
