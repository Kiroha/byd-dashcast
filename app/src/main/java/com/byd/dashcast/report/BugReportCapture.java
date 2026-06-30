package com.byd.dashcast.report;

import android.content.Context;
import android.os.Build;

import com.byd.dashcast.BuildConfig;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds a single, size-bounded diagnostic file for the in-app bug reporter.
 *
 * <p>Unlike the RE sniffer (which spawns long-lived background logcat processes),
 * this is a one-shot snapshot: it relies on the kernel logcat ring buffer, which
 * already retains the last few minutes of activity, so the report is retroactive
 * (the moments before the bug are captured) without any continuous logging.
 *
 * <p>The shell dump (uid=2000) writes logcat + dumpsys snapshots to the app's
 * external files dir; the in-memory DashCast journal and a header are appended
 * on the Java side. Total size stays well under ~1 MB thanks to {@code logcat -t}.
 */
public final class BugReportCapture {

    private static final String TAG       = "BugReportCapture";
    private static final String PREFIX    = "byd_bugreport_";
    private static final int    LOGCAT_LINES = 5000;

    public interface Callback {
        /** Called on the main thread with the finished file (never null on success). */
        void onReady(File file);
        /** Called on the main thread; {@code partial} is the best-effort file or null. */
        void onError(String message, File partial);
    }

    private BugReportCapture() {}

    /** Device line for the report header and the Telegram caption. */
    public static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " · " + Build.PRODUCT
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /** Version line for the report header and the Telegram caption. */
    public static String versionLine() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    /**
     * Captures a bug-report file. {@code metaHeader} (the user's Title/Steps/Result
     * block) is written at the very top so it is readable without scrolling.
     * Runs the shell dump in the background; {@code cb} fires on the main thread.
     */
    public static void capture(Context context, String metaHeader, Callback cb) {
        final Context app = context.getApplicationContext();
        final File outFile = newFile(app);
        final String p = outFile.getAbsolutePath();

        // One-shot shell dump. logcat -t bounds the size; dumpsys grabs the
        // cluster/display/window state that matters for projection bugs.
        String cmd =
              "echo '=== DASHCAST BUG REPORT ===' > " + p
            + " ; date >> " + p
            // ── PERF SNAPSHOT (kept near the top so it's readable without scrolling) ──
            // CPU: one toybox top iteration, sorted by CPU. Shows whether dashcast_proxy /
            // surfaceflinger / our app are still busy at rest (e.g. residual mirror after a stop).
            + " ; echo '--- CPU TOP (snapshot) ---' >> " + p
            + " ; top -b -n 1 2>/dev/null | head -45 >> " + p
            + " ; echo '--- PROCESSES (byd/xdja/daemon/proxy/sf) ---' >> " + p
            + " ; ps -A 2>/dev/null | grep -iE 'byd|xdja|daemon|dilink|cluster|app_process|dashcast|proxy|surfaceflinger' >> " + p
            // Memory: app PSS / heap / native — to spot growth or leaks across sessions.
            + " ; echo '--- MEMINFO (com.byd.dashcast) ---' >> " + p
            + " ; dumpsys meminfo com.byd.dashcast 2>/dev/null >> " + p
            // Graphics: frame stats / jank — directly relevant to perceived responsiveness.
            + " ; echo '--- GFXINFO (frame stats) ---' >> " + p
            + " ; dumpsys gfxinfo com.byd.dashcast 2>/dev/null | head -60 >> " + p
            // ── LOGS ──
            + " ; echo '--- LOGCAT (last " + LOGCAT_LINES + " lines) ---' >> " + p
            + " ; logcat -d -t " + LOGCAT_LINES + " -v threadtime >> " + p + " 2>&1"
            + " ; echo '--- LOGCAT EVENTS (last 500) ---' >> " + p
            + " ; logcat -b events -d -t 500 -v threadtime >> " + p + " 2>&1"
            // Tag-filtered logcat on window/launch/cluster/car events. The main buffer floods
            // fast (the prior DX_BYD_AUTO report had no 'waze'/'neusoft' line left), so a -t
            // window over only these tags is far more likely to still hold the moment a
            // third-party app was launched on the cluster and (not) shown.
            + " ; echo '--- LOGCAT (window/cluster/car tags) ---' >> " + p
            + " ; logcat -d -t 1500 -v threadtime WindowManager:I ActivityTaskManager:I ActivityManager:I CarService:I CAR.CLUSTER:V CAR.UXR:V CarUxRestrictions:V ClusterRenderingService:V InstrumentClusterRenderingService:V ActivityBlocking:V CarLaunch:V DisplayManagerService:I '*:S' >> " + p + " 2>&1"
            // ── CLUSTER / DISPLAY STATE ──
            + " ; echo '--- DISPLAYS ---' >> " + p
            + " ; dumpsys display 2>/dev/null >> " + p
            + " ; echo '--- WINDOW STACKS ---' >> " + p
            + " ; dumpsys activity activities 2>/dev/null"
            + "   | grep -E 'Stack #|Task id|taskId|displayId|realActivity|mResumed|Display #|TaskDisplayArea' >> " + p
            // Per-display activity/task context for every NON-ZERO display (not only the
            // expected cluster #1). -A on each "Display #N" header surfaces extra/unknown
            // screens (secondary cluster, HUD, presentation displays) on variants we don't
            // model yet — display *existence* incl. hidden/off ones is already in DISPLAYS above.
            + " ; echo '--- ACTIVITIES PER DISPLAY (non-zero) ---' >> " + p
            + " ; dumpsys activity activities 2>/dev/null"
            + "   | grep -A 40 -E 'Display #[1-9][0-9]*' | head -200 >> " + p
            // Task→display hierarchy: which TaskDisplayArea a launched app actually lands in.
            // On OEM-presented clusters (e.g. DX_BYD_AUTO "HDMI Screen") third-party apps stay in
            // the DefaultTaskDisplayArea and never reach the cluster → "app not shown".
            + " ; echo '--- ACTIVITY CONTAINERS (display areas) ---' >> " + p
            + " ; dumpsys activity containers 2>/dev/null | head -120 >> " + p
            // System services — to discover the OEM cluster/projection channel (analogous to
            // auto_container) on variants where the usual activation service is absent.
            + " ; echo '--- SERVICES (service list) ---' >> " + p
            + " ; service list 2>/dev/null >> " + p
            // ── AUTOCONTAINER NATIVE BACKEND (DL3 cluster projection) ──
            // "no AutoContainerNative" = the Java service com.xdja.containerservice (registered as
            // "AutoContainer") is up but ServiceManager.checkService("AutoContainerNative") is null,
            // i.e. the SEPARATE native daemon "AutoContainerNative" isn't registered/running. The
            // Java package is present + same version on working cars — the native daemon is the real
            // differentiator. Capture: is AutoContainerNative registered? which init service starts
            // it + its state? is the native process alive?
            + " ; echo '--- AUTOCONTAINER (native backend) ---' >> " + p
            + " ; service list 2>/dev/null | grep -iE 'container|cluster' >> " + p
            + " ; echo '-- init.svc (container/xdja/fission/cluster) --' >> " + p
            + " ; getprop 2>/dev/null | grep -iE 'init.svc' | grep -iE 'contain|xdja|fission|cluster|vdc' >> " + p
            + " ; echo '-- processes (xdja/container, unfiltered) --' >> " + p
            + " ; ps -A 2>/dev/null | grep -iE 'xdja|container' >> " + p
            // ── CLUSTER GATING DEEP-DIVE ──
            // Why does only the OEM system nav render on the cluster while third-party apps,
            // placed on the SAME display at the WM level, do not? (DX_BYD_AUTO / Android
            // Automotive INC-20260623-222919). Everything below is read-only discovery.
            //
            // Platform identity: `android.hardware.type.automotive` feature is the authoritative
            // "is this AAOS?" check; props identify the exact product / build.
            + " ; echo '--- FEATURES / PROPS (platform id) ---' >> " + p
            + " ; pm list features 2>/dev/null | grep -iE 'automotive|car|cluster|display' >> " + p
            + " ; getprop 2>/dev/null | grep -iE 'automotive|cluster|ro.product|ro.build.flavor|ro.build.version|security_patch|ro.vendor|fingerprint|dilink|byd|car|display' | head -100 >> " + p
            // Android Automotive Car service: cluster config, app-focus owner (navigation focus),
            // display assignment and any per-package allow-list that gates the instrument cluster.
            // AAOS distraction/driving gating — prime suspect: non-"distractionOptimized"
            // (and non-privileged) apps are blocked on car displays while driving, so only the
            // system nav renders on the cluster. CarUxRestrictions + driving state reveal it.
            + " ; echo '--- CAR UX RESTRICTIONS / DRIVING STATE ---' >> " + p
            + " ; dumpsys car_service 2>/dev/null | grep -iE 'restriction|distraction|driving|drive_state|drivestate|ux_restriction|uxr|optimized|requires_distraction|parked|moving|blocking|blocked|requiresDistraction|allowlist|allowed|occupant|zone|do_activit|do-activit' | head -150 >> " + p
            + " ; echo '--- CAR SERVICE (cluster/focus/nav/display) ---' >> " + p
            + " ; dumpsys car_service 2>/dev/null | grep -iE 'cluster|navigation|focus|display|launch|allow|whitelist|package|instrument|projection' | head -200 >> " + p
            // Per-display WINDOW state: which windows are actually on each display, their
            // visibility / z-order / flags — shows whether a launched third-party window reaches
            // the cluster surface or is dropped, vs the system nav which renders fine.
            + " ; echo '--- WINDOW DISPLAYS ---' >> " + p
            + " ; dumpsys window displays 2>/dev/null | head -300 >> " + p
            + " ; echo '--- WINDOW FOCUS (per display) ---' >> " + p
            + " ; dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp|mInputMethodTarget|imeLayeringTarget|Display: mDisplayId|mDisplayId=' | head -40 >> " + p
            // Candidate OEM cluster/nav packages — the system nav that DOES project is one of these.
            + " ; echo '--- PACKAGES (nav/map/cluster/car) ---' >> " + p
            + " ; pm list packages 2>/dev/null | grep -iE 'nav|map|cluster|instrument|neusoft|byd|car' >> " + p
            // Privilege / distraction-optimization of nav/cluster candidate packages: compare the
            // system nav (which DOES render on the cluster) against third-party apps that don't.
            // distractionOptimized + FLAG_SYSTEM/privileged are the likely differentiators.
            + " ; echo '--- PACKAGE FLAGS (nav/cluster candidates) ---' >> " + p
            + " ; for x in $(pm list packages 2>/dev/null | grep -iE 'neusoft|nav|map|cluster|instrument' | cut -d: -f2) ; do echo \"## $x\" ; dumpsys package \"$x\" 2>/dev/null | grep -iE 'flags=|pkgFlags|privateFlags|sharedUser|codePath|distraction|versionName' ; done 2>/dev/null | head -120 >> " + p
            // Cluster/projection permission declarations — who is even allowed to drive the cluster.
            + " ; echo '--- PERMISSIONS (cluster/car/projection) ---' >> " + p
            + " ; dumpsys package permissions 2>/dev/null | grep -iE 'cluster|instrument|projection|car.permission|distraction' | head -60 >> " + p
            // DashCast's OWN granted permissions/flags on this platform — to verify the app (and
            // the uid-2000 daemon that launches apps) hold what they need on a new ROM, e.g. any
            // car/cluster/projection signature permissions.
            + " ; echo '--- DASHCAST PACKAGE (grants/flags) ---' >> " + p
            + " ; dumpsys package com.byd.dashcast 2>/dev/null | grep -iE 'versionName|flags=|pkgFlags|privateFlags|sharedUser|granted=true|cluster|instrument|projection' | head -90 >> " + p
            // Input → display/viewport mapping: which input device feeds the cluster display, to
            // tell a touch-routing problem apart from a rendering problem on projected apps.
            + " ; echo '--- INPUT (display/viewport) ---' >> " + p
            + " ; dumpsys input 2>/dev/null | grep -iE 'DisplayViewport|displayId|uniqueId|viewport|cluster' | head -60 >> " + p
            // Car service CLI (READ-ONLY): the usage dump lists what this build lets us query, and
            // get-do-activities answers directly whether an app's activities are distraction-
            // optimized (allowed to run while driving) — the suspected gate for the cluster.
            + " ; echo '--- CAR SERVICE CLI (usage + do-activities) ---' >> " + p
            + " ; cmd car_service 2>&1 | head -60 >> " + p
            + " ; for x in $(pm list packages 2>/dev/null | grep -iE 'neusoft|nav|map|cluster|instrument' | cut -d: -f2) ; do echo \"## get-do-activities $x\" ; cmd car_service get-do-activities \"$x\" 2>&1 | head -8 ; done 2>/dev/null | head -90 >> " + p
            // Vendor HALs — a cluster/instrument/display HAL would be the OEM's native channel.
            + " ; echo '--- VENDOR HALS (cluster/display/nav) ---' >> " + p
            + " ; lshal 2>/dev/null | grep -iE 'cluster|instrument|display|nav|automotive|vehicle' | head -40 >> " + p
            // Running services that hint at a cluster/instrument renderer or nav bridge.
            + " ; echo '--- RUNNING SERVICES (cluster/instrument/nav) ---' >> " + p
            + " ; dumpsys activity services 2>/dev/null | grep -iE 'cluster|instrument|nav|neusoft|projection|ClusterRenderingService' | head -40 >> " + p
            // Settings that may whitelist which package is allowed on the cluster/secondary display.
            + " ; echo '--- SETTINGS (cluster/display/nav/car) ---' >> " + p
            + " ; (settings list global ; settings list secure ; settings list system) 2>/dev/null"
            + "   | grep -iE 'cluster|display|nav|car|projection|instrument' | head -80 >> " + p
            // SurfaceFlinger: full layer z-order (ALL packages, not only byd) + per-display
            // composition state — to see whether a third-party layer exists on the cluster layerStack.
            + " ; echo '--- SURFACEFLINGER (all layers, z-order) ---' >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null | head -150 >> " + p
            // SurfaceFlinger cluster/mirror layers — reveals a leftover mirror token after a stop.
            + " ; echo '--- SURFACEFLINGER (cluster/mirror layers) ---' >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'byd|mirror|xdja|fission|layerStack|displayId' | head -40 >> " + p
            // ── DAEMON LOGS ──
            + " ; echo '--- MIRRORDAEMON LOG ---' >> " + p
            + " ; cat /data/local/tmp/mirrordaemon_latest.log 2>/dev/null | tail -200 >> " + p
            + " ; echo '--- PROXYDAEMON LOG ---' >> " + p
            + " ; cat /data/local/tmp/dashcast_proxy.log 2>/dev/null | tail -200 >> " + p
            + " ; echo '=== END SHELL DUMP ===' >> " + p;

        AdbLocalClient.executeShellWithResult(app, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                finish(app, outFile, metaHeader, cb, null);
            }
            @Override public void onError(String err) {
                // The shell dump failed (ADB down) — still ship the in-memory
                // journal + metadata so the report is never empty.
                AppLogger.w(TAG, "shell dump failed, journal-only report: " + err);
                finish(app, outFile, metaHeader, cb, err);
            }
        });
    }

    /** Appends the metadata header (top) and the in-memory journal (bottom), then reports. */
    private static void finish(Context app, File outFile, String metaHeader,
                               Callback cb, String shellError) {
        try {
            String shellBody = outFile.exists() ? readFile(outFile) : "";
            StringBuilder sb = new StringBuilder(shellBody.length() + 8192);
            sb.append(metaHeader != null ? metaHeader : "").append("\n\n");
            sb.append("Device: ").append(deviceLine()).append('\n');
            sb.append("Version: ").append(versionLine()).append('\n');
            if (shellError != null)
                sb.append("[shell dump unavailable: ").append(shellError).append("]\n");
            sb.append("\n════════ SHELL DUMP ════════\n").append(shellBody);
            sb.append("\n════════ DASHCAST JOURNAL ════════\n").append(AppLogger.get());

            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write(sb.toString());
            }
            post(app, () -> cb.onReady(outFile));
        } catch (IOException e) {
            AppLogger.e(TAG, "finish() write failed", e);
            post(app, () -> cb.onError(e.getMessage(),
                    outFile.exists() ? outFile : null));
        }
    }

    private static File newFile(Context app) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, PREFIX + ts + ".txt");
    }

    private static String readFile(File f) throws IOException {
        byte[] buf = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off);
        }
    }

    private static void post(Context app, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
