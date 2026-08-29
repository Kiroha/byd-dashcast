package com.byd.dashcast.report;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.byd.dashcast.BuildConfig;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.proxy.ProxyClient;
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
 * on the Java side. The logcat sections are bounded by `-t`, and are written BEFORE the
 * {@code tail} line ceiling — so the file stays a few MB even on a log-spamming unit.
 *
 * <p>SECTION ORDER IS LOAD-BEARING, and so is the size budget that protects it. The dump is read
 * back with a hard byte cap taken from the START of the file ({@link #readFile}), so whatever is
 * written LAST is what a size overflow deletes. Logcat stays first and dumpsys second on purpose:
 * the logcat sections are ordered oldest→newest, so truncating them would eat the most RECENT
 * lines — the very moments being reported. The {@code -t} bounds keep the file well under the cap,
 * and {@link #readFile} shouts if it is ever reached. Do not "fix" this by swapping the order;
 * fix it by lowering the {@code -t} bounds.
 */
public final class BugReportCapture {

    private static final String TAG       = "BugReportCapture";
    /** Filename prefix for generated bug reports. Public because {@code AppLogger.PRUNED_PREFIXES}
     *  must reference it — the sweeper and this name are one contract, and duplicating the
     *  literal is what let them drift apart and strand every report on disk. */
    public static final String PREFIX    = "byd_bugreport_";

    /**
     * Line-count cap kept ONLY as the fallback for a ROM whose logcat refuses {@code -t <time>}.
     */
    private static final int    LOGCAT_FALLBACK_LINES = 5000;

    /**
     * Hard byte cap applied when the shell dump is read back, taken from the START of the file.
     * The {@code -t} bounds on the logcat sections keep a real report an order of magnitude below
     * this, so it is a last-resort guard rather than a working limit — but it cuts at the END of
     * the file, so if it ever bites it removes the dumpsys sections. {@link #readFile} therefore
     * says so out loud instead of letting a truncated report look like a device that produced
     * nothing (which is exactly how INC-20260727-203241 nearly went undiagnosed).
     */
    private static final long   REPORT_BODY_MAX_BYTES = 4L * 1024L * 1024L;

    /**
     * Trailer the A13 staged read-back appends when it had to cut the body ON THE DEVICE, followed
     * by the true size in bytes. The shell is the only place that knows that size; the banner
     * itself is built here so both truncation paths word it identically.
     */
    private static final String TRUNC_MARKER = "@@DASHCAST_BODY_TRUNCATED@@";



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
     * The shell dump, as one string, so what it asks for can be asserted.
     *
     * <p>Extracted for one reason: every defect this command has had was a silently
     * empty section, never a crash. A `head` exhausted on the wrong display, a filterspec
     * naming tags this ROM does not carry, a grep alternation missing the service that
     * actually logs — each produced a report that looked complete and answered nothing,
     * and none of them could fail a test while the command was a local variable.
     *
     * @param p the path every section appends to.
     */
    static String buildShellDump(String p) {
        return
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
            // Notification access: the HUD nav path is notification-driven, so a revoked listener
            // grant looks EXACTLY like "the nav app posts nothing". Report only OUR OWN grant as a
            // boolean — dumping the raw setting would upload the driver's full list of
            // notification-reading apps (an app inventory) to the support channel.
            + " ; echo '--- NOTIFICATION ACCESS (dashcast) ---' >> " + p
            + " ; { settings get secure enabled_notification_listeners 2>/dev/null"
            + "     | grep -q com.byd.dashcast && echo granted || echo NOT-granted ; } >> " + p
            // ── LOGS ──
            // TIME-ANCHORED, not line-counted. `logcat -t <N lines>` is worthless on a unit with
            // a log-spamming third party: on the DL4 car com.andrerinas.headunitrevived sprays
            // MediaCodec stack traces continuously, so `-t 5000` captured 3.2 SECONDS while the
            // activation being investigated had happened ~100 s earlier (INC-20260727-203241) —
            // the evidence had been evicted from our window even though it was still in the ring
            // buffer. `-t '<MM-DD HH:MM:SS.mmm>'` takes everything the buffer still holds since
            // that instant, so a spammer can no longer squeeze the window; the buffer size caps
            // the report either way, so this cannot blow the file up. `|| ` falls back to the old
            // line count if a ROM rejects the time form.
            // `tail` (not `head`) applies the safety ceiling from the RECENT end, so the cap can
            // only ever discard the oldest lines of the window, never the incident itself.
            //
            // The fallback is triggered by an EMPTY result, not by a non-zero exit. `||` alone
            // was strictly worse than the fixed window it replaced: a logcat that ACCEPTS
            // `-t <time>` but retains nothing newer than the anchor exits 0 with no output, so
            // the section came out empty and the fallback never ran. Staging into a temp file
            // (uid 2000 owns /data/local/tmp on every supported unit — it is where the daemon
            // logs read below already live) makes `[ -s ]` cover BOTH failure shapes with one
            // test, and is idempotent if the shell layer replays the command.
            + " ; echo '--- LOGCAT (last " + LOGCAT_FALLBACK_LINES + " lines) ---' >> " + p
            + " ; logcat -d -t " + LOGCAT_FALLBACK_LINES + " -v threadtime >> " + p + " 2>&1"
            + " ; echo '--- LOGCAT EVENTS (last 2000) ---' >> " + p
            // 2000, not 500: the events buffer is where task/stack creation and display routing are
            // actually visible (wm_task_created, am_create_activity, wm_stack_created), and it is
            // the buffer that reconstructed INC-20260815-181820 — where 500 lines barely covered
            // the window. It is also the only one that helps when the filtered WM/ATM section comes
            // back empty, as it does on DiLink 3.
            + " ; logcat -b events -d -t 2000 -v threadtime >> " + p + " 2>&1"
            // Tag-filtered logcat on window/launch/cluster/display/car events, over the SAME time
            // window. DisplayManagerService is at :V, not :I: the OEM DL4 whitelist refusals that
            // proved the root cause ("getDisplayIdsInternal isPermittedApp:false", "forbid
            // for:<uid>", "reject for:<uid> to get id") are all logged at DEBUG level, so the old
            // :I threshold dropped exactly the lines that mattered. ClusterManager:V adds our own
            // activation trace so both sides of the story are in one section.
            // Bounded by `-t`, exactly as before: this section is written BEFORE the dumpsys
            // sections, so any unbounded growth here is what would evict them.
            + " ; echo '--- LOGCAT (window/cluster/display/car tags) ---' >> " + p
            // Phase4TaskVerbs:I carries the daemon's own launch/watchdog transcript, including the
            // "WATCHDOG cluster-top" z-order observation. It has to be in THIS filtered section:
            // the unfiltered one is exactly what a watchdog log flood evicts, which is how
            // INC-20260804-171617 lost its framework evidence. Plain alphanumeric tag — no
            // filterspec risk.
            // Bound the OUTPUT, not the input — the same shape the OEM pass below uses, and for
            // the same reason. `-t 1500` tails the BUFFER and the tag filter is applied after, so
            // on a unit logging ~400 lines/s the window covered the last 3.8 seconds: in
            // INC-20260826-194829 this section came back with two buffer separators and zero lines.
            // It is the section that would have carried the WindowManager evidence for the Android
            // Auto fix.
            + " ; logcat -d -t 20000 -v threadtime WindowManager:I ActivityTaskManager:I ActivityManager:I CarService:I CAR.CLUSTER:V CAR.UXR:V CarUxRestrictions:V ClusterRenderingService:V InstrumentClusterRenderingService:V ActivityBlocking:V CarLaunch:V DisplayManagerService:V ClusterManager:V Phase4TaskVerbs:I '*:S' | tail -300 >> " + p + " 2>&1"
            // The OEM's own cluster projection manager, matched by CONTENT rather than by tag.
            // INC-20260804-171617 (DiLink 5.0) was root-caused to that service re-fronting its map
            // onto the cluster display ~1-2 s after every launch, yet the report contained ZERO of
            // its lines: its tag is "[Cluster]-BydProjectionService", which cannot be added to the
            // tag-filtered spec above without risking the whole filterspec (brackets/dashes), and a
            // malformed spec would silently cost us the section on EVERY platform. A separate
            // grep-based pass cannot break anything else and works whatever the exact tag is.
            + " ; echo '--- OEM CLUSTER PROJECTION (BydProjectionService / container) ---' >> " + p
            // -t 20000, not a small window: the failure this section exists to document is
            // accompanied by a log flood, and a line window is worthless on a spamming unit (see
            // the same reasoning above). Output is bounded by `tail -300` regardless, so a wider
            // window costs capture time only, never report size.
            + " ; logcat -d -t 20000 -v threadtime 2>/dev/null"
            + "   | grep -iE 'BydProjectionService|projectionmanager|AutoDisplayService|AutoSharedDisplay|START_MAP_VIEW|MeterActivity|stopContentProjection|byd_map_package|xdja_AutoContainerService'"
            + "   | tail -300 >> " + p + " 2>&1"
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
            + " ; service list 2>/dev/null | grep -iE 'container|cluster|fission|magicwindow' >> " + p
            + " ; echo '-- init.svc (container/xdja/fission/cluster) --' >> " + p
            + " ; getprop 2>/dev/null | grep -iE 'init.svc' | grep -iE 'contain|xdja|fission|cluster|vdc' >> " + p
            + " ; echo '-- init.svc (FULL list, sorted) --' >> " + p
            + " ; getprop 2>/dev/null | grep -E 'init.svc' | sort >> " + p
            + " ; echo '-- fission/xdja/cluster props (non-init.svc) --' >> " + p
            + " ; getprop 2>/dev/null | grep -iE 'fission|xdja|container|cluster' | grep -viE 'init.svc' | head -60 >> " + p
            + " ; echo '-- FissionCluster vendor dir / version --' >> " + p
            + " ; ls -d /vendor/*ission* /vendor/*luster* /odm/*ission* 2>/dev/null >> " + p
            + " ; echo '-- processes (xdja/container/fission, unfiltered) --' >> " + p
            + " ; ps -A 2>/dev/null | grep -iE 'xdja|container|fission|cluster' >> " + p
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
            // ── HUD (windshield head-up display) — now a production turn-by-turn nav feature on
            // DL3, so a "HUD nav" bug report needs the HUD MCU firmware (inswver = SX<NNN>, which
            // pins arrow-capability) and any HUD-related system props.
            + " ; echo '--- HUD (firmware inswver + props) ---' >> " + p
            + " ; getprop 2>/dev/null | grep -iE 'inswver|hud' >> " + p
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
            // SurfaceFlinger, twice — and until now neither pass ever reached the cluster.
            //
            // INC-20260826-194829: both sections together held 27 `layerStack=` lines and all 27
            // said 0. The first burned 66 of its 150 lines on `connections (count=66)` and stopped
            // 13 lines into a list of 86 visible layers; the second spent its 40 on display 0,
            // because a bare `layerStack` matches every layer there before reaching the cluster.
            // Meanwhile the mirror WAS alive — the daemon's own dump names byd_myapp_mirror in the
            // same report. SurfaceFlinger is the only window onto a mirror created with
            // SurfaceControl.createDisplay (it never appears in `dumpsys display`), so the one
            // leak check this report has was structurally blind on DiLink 3.
            //
            // Anchored past the preamble rather than truncated into it — and reduced to one line
            // per layer, because the anchor alone was not enough. This ROM prints ~10 lines per
            // layer, so `head -150` covered 18 of the 86 the header counts, and they are the BOTTOM
            // of the stack (Display Root, wallpaper). The cluster is emitted last, so the pass that
            // exists to show what is on top could never reach it. Corpus range is 60-116 layers;
            // 150 raw lines was never enough on any car.
            + " ; echo '--- SURFACEFLINGER (visible layers, z-order) ---' >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null | sed -n '/Visible layers/,$p'"
            + "   | grep -E '^[+*] |layerStack=' | head -300 >> " + p
            // The cluster pass, narrowed so it cannot be spent on display 0: `layerStack= *[1-9]`
            // skips the default display entirely, which is what a bare `layerStack` could not do.
            //
            // The spaces are not decoration. SurfaceFlinger pads the per-layer field on this ROM —
            // `layerStack=   0, z=` — while the composition-display block prints it unpadded. The
            // first version of this pattern had no ` *` and was "verified" against a hand-written
            // sample that used the unpadded form, so it matched ZERO lines of the real dump in
            // INC-20260826-194829. Checked against the report itself this time.
            // The bound is a backstop at four times the real size, not a window — the same grep
            // without one is what SurfaceDaemon.auditMirrorInSurfaceFlinger already uses, and it
            // finds the mirror token every time.
            + " ; echo '--- SURFACEFLINGER (cluster/mirror layers) ---' >> " + p
            + " ; dumpsys SurfaceFlinger 2>/dev/null"
            + "   | grep -iE 'byd_myapp_mirror|DisplayDevice|layerStack= *[1-9]|xdja|fission|ScreenProjection|Composition Display State'"
            + "   | head -200 >> " + p
            // ── DAEMON LOGS ──
            // 400, not 200: two captures (INC-20260614-131051/-131118) already contained
            // exactly 200 lines, i.e. the window was full and the start of the session was
            // gone. One layout attach now costs ~21 lines, so a 3-zone activation followed by
            // a few mirror restarts on the way back to the main screen would push the
            // [ATTACH_SLOT] block out entirely. ~32 KB — nowhere near the body cap that a
            // previous over-raise of the LOGCAT depth blew through.
            + " ; echo '--- MIRRORDAEMON LOG ---' >> " + p
            + " ; cat /data/local/tmp/mirrordaemon_latest.log 2>/dev/null | tail -400 >> " + p
            + " ; echo '--- PROXYDAEMON LOG ---' >> " + p
            + " ; cat /data/local/tmp/dashcast_proxy.log 2>/dev/null | tail -200 >> " + p
            + " ; echo '=== END SHELL DUMP ===' >> " + p;
    }

    /**
     * Captures a bug-report file. {@code metaHeader} (the user's Title/Steps/Result
     * block) is written at the very top so it is readable without scrolling.
     * Runs the shell dump in the background; {@code cb} fires on the main thread.
     */
    public static void capture(Context context, String metaHeader, Callback cb) {
        final Context app = context.getApplicationContext();
        final File outFile = newFile(app);

        // Android 13+ (DL5.1 / trinket): the shell (uid 2000) is DENIED write access to the
        // app's /sdcard/Android/data/<pkg> dir by scoped storage, so redirecting the dump
        // there fails with EACCES and only the journal survives (INC-20260715-191229). uid 2000
        // DOES own /data/local/tmp, so on A13 stage the dump there and `cat` it back through the
        // same shell round-trip (the app cannot read /data/local/tmp on A13, but the shell can);
        // finish() then writes the final report into the app's own external dir (which the app
        // CAN write). On Android 10 (DL3/DL4) uid 2000 can still write the external dir, so keep
        // writing straight into the final file — no extra round-trip, zero behaviour change.
        final boolean stageInTmp = Build.VERSION.SDK_INT >= 33;
        final String p = stageInTmp
                ? "/data/local/tmp/" + outFile.getName()
                : outFile.getAbsolutePath();

        // A transport already classified as unreachable cannot produce a shell dump. Do not
        // enqueue behind wedged ADB workers: create the guaranteed journal-only report off-main.
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            final String diagnosis = AdbLocalClient.adbTransportDiagnosis();
            Thread offline = new Thread(() ->
                finish(app, outFile, metaHeader, cb, diagnosis, null),
                "bugreport-offline");
            offline.setDaemon(true);
            offline.start();
            return;
        }

        // One-shot shell dump. logcat -t bounds the size; dumpsys grabs the
        // cluster/display/window state that matters for projection bugs.
        String cmd = buildShellDump(p);

        if (stageInTmp) {
            // Sweep leftovers BEFORE this run writes anything. The `rm -f` below only runs when
            // the dump reaches the end of the command, so a cancelled or crashed capture strands
            // a multi-megabyte UNREDACTED dump in a shell-readable directory, and nothing else in
            // the app ever removes one. Skip the path this run is about to write: captures are
            // dispatched on a pool, so a bare wildcard could race a concurrent one.
            cmd = "for f in /data/local/tmp/" + PREFIX + "*.txt ; do"
                + " [ \"$f\" = '" + p + "' ] || rm -f \"$f\" ; done 2>/dev/null ; " + cmd;

            // Emit the staged body to stdout (the only way back to the app, which can't read
            // /data/local/tmp) and delete the temp file. Every dump command above redirects into
            // $p, so stdout is otherwise empty and `out` carries exactly the body. Uses the
            // non-logging shell path so the ~1 MB body never lands in the journal.
            //
            // The cap is applied HERE, on the device, because this is the last point upstream of
            // the unbounded String the transport builds. readFile() holds the only other copy of
            // the cap and is never called on this path, so without this the A13 body is unbounded
            // through the transport and every redaction pass. The size is only knowable on the
            // device, so the shell just flags the cut with a marker and finish()'s caller appends
            // the same banner readFile() uses — one wording, two paths.
            //
            // `head -c` runs ONLY when the dump is actually over the cap. Under it — which is
            // every real report — the command stays the plain `cat` it has always been, so a ROM
            // whose head lacks -c cannot silently reduce every DL5.1 report to head's default ten
            // lines. In the overflow branch `|| cat` is the same safety net: better a report too
            // big than a report gutted.
            cmd += " ; _sz=$(wc -c < " + p + " 2>/dev/null | tr -d ' \\n')"
                 + " ; [ -n \"$_sz\" ] || _sz=0"
                 + " ; if [ \"$_sz\" -gt " + REPORT_BODY_MAX_BYTES + " ] ; then"
                 + " { head -c " + REPORT_BODY_MAX_BYTES + " " + p + " 2>/dev/null"
                 + " || cat " + p + " 2>/dev/null ; }"
                 + " ; echo ; echo \"" + TRUNC_MARKER + " $_sz\""
                 + " ; else cat " + p + " 2>/dev/null ; fi"
                 + " ; rm -f " + p + " 2>/dev/null";
        }

        final AdbLocalClient.Callback dumpCb = new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                // A13: `out` is the staged body. A10: body is in outFile → let finish() read it.
                finish(app, outFile, metaHeader, cb, null,
                        stageInTmp ? applyStagedTruncation(out) : null);
            }
            @Override public void onError(String err) {
                // The shell dump failed (ADB down) — still ship the in-memory
                // journal + metadata so the report is never empty.
                AppLogger.w(TAG, "shell dump failed, journal-only report: " + err);
                finish(app, outFile, metaHeader, cb, err, null);
            }
        };
        // Both branches run the SAME dump, which pauses between expensive dumpsys sections on a
        // transport that stays silent throughout — that is what REPORT_IDLE_TIMEOUT_MS exists for.
        // The A13 branch already resolved to it; the A10 branch was falling through to the 60 s
        // wedged-transport default, so a slow DL3 shell was cut off mid-dump. Name the constant at
        // both call sites so the asymmetry cannot come back. (Do NOT raise SHELL_IDLE_TIMEOUT_MS:
        // it is the dead-adbd detector for every other shell call in the app.)
        if (stageInTmp) {
            AdbLocalClient.executeShellWithResultUnlogged(
                    app, cmd, dumpCb, AdbLocalClient.REPORT_IDLE_TIMEOUT_MS);
        } else {
            AdbLocalClient.executeShellWithResult(
                    app, cmd, dumpCb, AdbLocalClient.REPORT_IDLE_TIMEOUT_MS);
        }
    }

    /**
     * Appends the metadata header (top) and the in-memory journal (bottom), then reports.
     *
     * @param preReadShellBody the shell dump body already read back over the shell (A13 staged
     *     path); when non-null it is used verbatim and {@code outFile} is NOT read (on A13 the
     *     app cannot read that path). When null, the body is read from {@code outFile} (A10).
     */
    // Package-private, not private, so RedactionCallSiteTest can drive it. That test is the only
    // thing that proves the artefact LEAVING this emitter is filtered: Redactor's own tests cover
    // the rules as a pure function, and would all still pass if the call at :448 were deleted.
    static void finish(Context app, File outFile, String metaHeader,
                       Callback cb, String shellError, String preReadShellBody) {
        // Build the body first. This must NEVER sink the report — on any failure fall back
        // to a journal-only body so the tester still gets something to send.
        String content;
        try {
            String shellBody = (preReadShellBody != null) ? preReadShellBody
                    : (outFile.exists() ? readFile(outFile) : "");
            StringBuilder sb = new StringBuilder(shellBody.length() + 8192);
            sb.append(metaHeader != null ? metaHeader : "").append("\n\n");
            sb.append("Device: ").append(deviceLine()).append('\n');
            sb.append("Version: ").append(versionLine()).append('\n');
            sb.append("\n════════ HUD STATE (push-feedback) ════════\n").append(hudStateSnapshot());
            if (shellError != null)
                sb.append("[shell dump unavailable: ").append(shellError).append("]\n");
            sb.append("\n════════ SHELL DUMP ════════\n").append(shellBody);
            sb.append("\n════════ DASHCAST JOURNAL ════════\n").append(AppLogger.get());
            content = sb.toString();
        } catch (Throwable t) {
            AppLogger.e(TAG, "finish() body build failed — journal-only", t);
            content = (metaHeader != null ? metaHeader : "")
                    + "\n\nDevice: " + deviceLine()
                    + "\nVersion: " + versionLine()
                    + "\n[report body build failed: " + t + "]\n"
                    + "\n════════ DASHCAST JOURNAL ════════\n" + AppLogger.get();
        }
        // AUD-004 — the single choke point. By this line `content` holds everything a report will
        // ever contain: the wizard's header, the device and version lines, the HUD state, both
        // unfiltered logcat passes, the ~20 dumpsys sections and the journal. One call covers the
        // lot; redacting earlier would mean redacting in five places and eventually missing one.
        //
        // Off the main thread by construction — finish() is reached from the shell callback or from
        // the offline capture thread, never from the UI — so six regex passes over a body capped at
        // REPORT_BODY_MAX_BYTES cost nothing a user can feel. Both read-back paths enforce that cap
        // before they get here: readFile() on A10, the staged `head -c` on A13.
        //
        // FAILS OPEN, on purpose, and says so. Redactor already isolates its rules so one bad
        // pattern cannot take the others down; if the whole call still throws, the report goes out
        // as it would have before this commit existed — which is the state the consent notice
        // describes — rather than being lost. What must never happen is shipping unfiltered text
        // while looking filtered, so the report carries the failure in its own body.
        String redactionNote;
        try {
            Redactor.Result r = Redactor.redact(content);
            content = r.text;
            redactionNote = r.summary();
        } catch (Throwable t) {
            redactionNote = "REDACTION FAILED (" + t.getClass().getSimpleName()
                    + ") — this report was NOT filtered";
            AppLogger.e(TAG, "redaction failed — shipping unfiltered", t);
        }
        // The pass above can only speak for the text. A bundle carries screenshots the
        // redactor never sees, and the wizard appends their count below once it knows.
        content = content + "\n════════ REDACTION ════════\n" + redactionNote
                + "\nscope: text only — any file attached alongside this report is NOT "
                + "covered by the pass above.\n";

        final String body = content;

        // Primary target is EXTERNAL (so the uid-2000 shell can co-write the dump into it).
        // But when the shell dump never ran (daemon / ADB-TCP down) the external dir is often
        // ALSO unwritable on the same broken DL5.1 ROMs (getExternalFilesDir threw), so writing
        // there fails and the report is lost — exactly what the tester reported on 2026-07-04.
        // Fall back to INTERNAL storage, which the app can always write and which the
        // FileProvider (<files-path>) already shares, so an offline report is ALWAYS produced.
        try {
            try (FileWriter fw = new FileWriter(outFile)) { fw.write(body); }
            post(app, () -> cb.onReady(outFile));
            return;
        } catch (Throwable extErr) {
            AppLogger.w(TAG, "external write failed — falling back to internal storage", extErr);
        }
        try {
            File internal = new File(app.getFilesDir(), outFile.getName());
            try (FileWriter fw = new FileWriter(internal)) { fw.write(body); }
            AppLogger.i(TAG, "offline bug report written to internal storage: "
                    + internal.getAbsolutePath());
            post(app, () -> cb.onReady(internal));
        } catch (Throwable intErr) {
            AppLogger.e(TAG, "internal fallback write failed too", intErr);
            post(app, () -> cb.onError(String.valueOf(intErr), null));
        }
    }

    /**
     * Best-effort HUD push-feedback snapshot for the report: 0x38B0001C = HUD switch (1=on,2=off),
     * 0x38B0000D / 0x42E00008 = display mode (1..6). get() is push-only, so this reads the daemon
     * listener's last-known values (kept registered app-wide by ProxyKeeperService). Never throws.
     */
    private static String hudStateSnapshot() {
        // canListenStart/canListenDrain go through callWithRetry; on a cold/down daemon the
        // pre-flight reconnect would BLOCK this thread (an adb-local pool worker driving the
        // offline bug report) for the bootstrap timeout — precisely the daemon-down scenario the
        // report exists to capture. Opt this thread out of the blocking bootstrap (mirrors F6 /
        // ShellGateway): the verbs fail fast and we fall back to the "(no HUD…)" line.
        ProxyClient.setNonBlockingReconnect(true);
        try {
            try { ProxyClient.canListenStart(); } catch (Throwable ignore) { /* ensure registered */ }
            String s = ProxyClient.canListenDrain();
            return (s == null || s.isEmpty())
                    ? "(no HUD push-feedback captured — listener cold / not a HUD platform)\n" : s;
        } catch (Throwable t) {
            return "(HUD state unavailable: " + t + ")\n";
        } finally {
            ProxyClient.setNonBlockingReconnect(false); // pooled thread is reused — restore default
        }
    }

    /**
     * The external files dir built by hand, bypassing the AppOps-gated getter.
     *
     * Public and shared because the WRITER and the SWEEPER must agree on it. They did not:
     * {@link #newFile} falls back here when getExternalFilesDir() throws, and reports land here on
     * the DL5.1 / Android 13 ROMs where it does — while AppLogger.pruneOldFiles resolved the same
     * throw to null and skipped external storage entirely. So on the one platform family this
     * fallback exists for, every report written was a report never pruned, which is exactly the
     * unbounded growth pruneOldFiles was added to stop.
     *
     * Same contract shape as {@link #PREFIX} and AppLogger.PRUNED_PREFIXES: one definition, two
     * readers. Duplicating the literal is what let them drift the first time.
     */
    public static File canonicalExternalFilesDir(Context app) {
        return new File("/storage/emulated/0/Android/data/" + app.getPackageName() + "/files");
    }

    private static File newFile(Context app) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File dir = null;
        // getExternalFilesDir() routes through StorageManagerService (AppOps package/uid check) and
        // can THROW SecurityException ("callingPackage does not match UID") on some DL5.1 / Android 13
        // ROMs (reported 2026-07-04: the Bug Report feature stopped generating). The report file must
        // live on EXTERNAL storage because the shell (uid 2000) writes the dump into it — internal
        // filesDir is not writable by the shell. So on failure, build the canonical external app-files
        // path directly: it bypasses the throwing API, the shell can write there (as the successful
        // dumps already do), and the app can read/write its own external dir.
        try {
            dir = app.getExternalFilesDir(null);
        } catch (Throwable t) {
            AppLogger.w(TAG, "getExternalFilesDir threw (" + t.getClass().getSimpleName()
                    + ") — using canonical external path");
        }
        if (dir == null) {
            dir = canonicalExternalFilesDir(app);
        }
        if (!dir.exists()) {
            try { dir.mkdirs(); } catch (Throwable ignore) { /* best-effort */ }
        }
        return new File(dir, PREFIX + ts + ".txt");
    }

    /**
     * Reads the shell dump back, capped at {@value #REPORT_BODY_MAX_BYTES} bytes FROM THE START.
     *
     * <p>The cap is a last-resort guard — the {@code -t} bounds keep the report well under it, so
     * it should never be reached. If it ever is, the cut lands at the END of the file, i.e. it
     * silently removes the
     * dumpsys sections (DISPLAYS, WINDOW STACKS, SURFACEFLINGER, CAR SERVICE, daemon logs), which
     * is indistinguishable from "the device produced nothing" when triaging. So a truncation now
     * says so, in the report and in the journal.
     */
    private static String readFile(File f) throws IOException {
        final long len = f.length();
        byte[] buf = new byte[(int) Math.min(len, REPORT_BODY_MAX_BYTES)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            String body = new String(buf, 0, off);
            if (len <= REPORT_BODY_MAX_BYTES) return body;
            AppLogger.w(TAG, "shell dump truncated: " + len + " B on disk, cap "
                    + REPORT_BODY_MAX_BYTES + " B — the trailing dumpsys sections are MISSING");
            return body + truncationBanner(len);
        }
    }

    /**
     * The one wording for "this report is missing its tail", shared by the A10 read-back
     * ({@link #readFile}) and the A13 staged read-back ({@link #applyStagedTruncation}).
     *
     * @param actualBytes the dump's true size, or a negative value if it could not be read back.
     */
    private static String truncationBanner(long actualBytes) {
        return "\n\n!!!!!!!! REPORT TRUNCATED !!!!!!!!\n"
                + "The shell dump was "
                + (actualBytes >= 0 ? actualBytes + " bytes" : "larger than the cap")
                + "; only the first " + REPORT_BODY_MAX_BYTES + " were kept.\n"
                + "Everything after this point (the remaining dumpsys sections: DISPLAYS, "
                + "WINDOW STACKS, SURFACEFLINGER, CAR SERVICE, daemon logs) IS MISSING — it "
                + "was NOT absent on the device.\n"
                + "Lower the logcat -t bounds in BugReportCapture.\n"
                + "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n";
    }

    /**
     * Turns the shell's truncation trailer into the human banner, on the A13 staged path.
     *
     * <p>Package-private for the test: this is the only place that decides whether a DL5.1 report
     * announces its own truncation, and a report that stays silent about it is how a cut dump gets
     * triaged as "the device produced nothing".
     */
    static String applyStagedTruncation(String out) {
        if (out == null) return null;
        final int i = out.lastIndexOf(TRUNC_MARKER);
        // Only ever a trailer. Bounding the search to the tail means a body that happens to quote
        // the marker (a previous report pasted into a log, say) cannot truncate the next one.
        if (i < 0 || out.length() - i > TRUNC_MARKER.length() + 24) return out;
        long actual = -1L;
        try {
            actual = Long.parseLong(out.substring(i + TRUNC_MARKER.length()).trim());
        } catch (Throwable ignore) { /* -1: the banner still says the body was cut */ }
        AppLogger.w(TAG, "staged shell dump truncated on device: " + actual + " B, cap "
                + REPORT_BODY_MAX_BYTES + " B — the trailing dumpsys sections are MISSING");
        // trimEnd would also eat the blank line the banner opens with; strip only the trailer.
        return out.substring(0, i) + truncationBanner(actual);
    }

    private static void post(Context app, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
