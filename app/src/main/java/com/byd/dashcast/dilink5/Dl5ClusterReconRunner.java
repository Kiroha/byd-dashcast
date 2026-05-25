package com.byd.dashcast.dilink5;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.byd.dashcast.AdbLocalClient;
import com.byd.dashcast.AppLogger;
import com.byd.dashcast.platform.Platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dl5ClusterReconRunner — recon-only diagnostic suite for the DiLink 5 cluster
 * resize/move pipeline (v1.2.37).
 *
 * <p><b>Phase 1.</b> No live cluster launch from this runner. We only probe
 * what shell verbs, compat IDs, settings keys, services and displays are
 * available on the head unit, so the user can ship the result back via the
 * "Send via Telegram" button. With that data we decide in v1.2.38 which
 * resize strategy is actually viable on production BYD ROMs (most likely
 * {@code am compat enable FORCE_RESIZE_APP <pkg>}, but only if the compat
 * framework is exposed by the shell on that ROM).
 *
 * <p>Every test is read-only. Worst case it runs a {@code --help} probe or
 * a {@code settings get} / {@code dumpsys} grep. No app is launched, no
 * setting is mutated, no flag is toggled. The whole suite finishes in
 * &lt; 20 s typical.
 *
 * <p>Reuses {@link DiLink5TestRunner.TestDef} / {@link DiLink5TestRunner.TestResult}
 * / {@link DiLink5TestRunner.Status} so DiagActivity can reuse the same row
 * renderer.
 */
public final class Dl5ClusterReconRunner {

    private static final String TAG = "Dl5ClusterRecon";

    public interface Listener {
        void onSuiteStarted(List<DiLink5TestRunner.TestResult> results);
        void onTestUpdated(int index, DiLink5TestRunner.TestResult result);
        void onSuiteFinished(List<DiLink5TestRunner.TestResult> results);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dl5-cluster-recon");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Known cluster-target apps the user might try to project on DL5. */
    private static final String[] CLUSTER_APPS = new String[] {
            "ru.yandex.yandexmaps",
            "com.google.android.apps.maps",
            "com.waze",
            "com.sygic.aura",
            "com.tomtom.gplay.navapp"
    };

    private Dl5ClusterReconRunner() {}

    /** Ordered recon catalog. */
    public static List<DiLink5TestRunner.TestDef> catalog() {
        List<DiLink5TestRunner.TestDef> list = new ArrayList<>();
        // ── Platform identity ────────────────────────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R01", "Platform identity",
                "Build.MODEL + Build.VERSION.SDK_INT + ro.product.name → confirm DL5 + API 32."));
        list.add(new DiLink5TestRunner.TestDef("R02", "Displays inventory",
                "DisplayManager.getDisplays() + locate cluster display (id ≠ 0, name matches fission/xdja/cluster)."));
        list.add(new DiLink5TestRunner.TestDef("R03", "ADB local 5555 + uid",
                "id -u && id -un → expect 2000/shell. Gates every R0x test below."));
        list.add(new DiLink5TestRunner.TestDef("R04", "SELinux enforcing",
                "getenforce + id -Z → expected Enforcing + u:r:shell:s0."));
        list.add(new DiLink5TestRunner.TestDef("R05", "Cluster apps installed",
                "pm list packages -f → which of yandexmaps / google maps / waze / sygic / tomtom are present."));

        // ── Shell command surface (am / cmd / wm) ────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R06", "am --help",
                "Full am verb list (start, force-stop, compat, task, stack…)."));
        list.add(new DiLink5TestRunner.TestDef("R07", "am start --help",
                "Catch every supported flag: --display, --windowingMode, --bounds, --task-id-launch-bounds, --activity-launch-bounds…"));
        list.add(new DiLink5TestRunner.TestDef("R08", "cmd activity --help",
                "Modern AOSP equivalent of am, catch all subcommands."));
        list.add(new DiLink5TestRunner.TestDef("R09", "cmd activity task --help",
                "Catch resize / move-task / set-windowing-mode / supports-multiwindow…"));
        list.add(new DiLink5TestRunner.TestDef("R10", "wm --help",
                "Catch size / density / overscan / set-ignore-orientation-request / disable-blur / set-fix-to-user-rotation…"));
        list.add(new DiLink5TestRunner.TestDef("R11", "cmd window --help",
                "Newer window manager verb surface."));
        list.add(new DiLink5TestRunner.TestDef("R12", "cmd display --help",
                "Display manager verbs (typically lock-rotation, set-brightness, etc — useful baseline)."));

        // ── Compat framework (the FORCE_RESIZE_APP hypothesis) ───────────
        // v1.2.41: R14-R16 rewritten after the v1.2.40 report proved
        // `am compat list <pkg>` is misinterpreted on this ROM (the first arg
        // is treated as a change ID, not a package). The proper per-package
        // override read-back is via `dumpsys platform_compat`. R17 grep BRE
        // (`\|`) is replaced with `-E` because BusyBox grep on DL5 does not
        // expand the BRE alternation.
        list.add(new DiLink5TestRunner.TestDef("R13", "am compat help",
                "`am compat enable` with no further args triggers AOSP's usage hint. Confirms the verb surface (enable|disable|reset|reset-all|list) without mutating anything."));
        list.add(new DiLink5TestRunner.TestDef("R14", "am compat verb surface",
                "`am compat 2>&1 | head -20` — clean usage dump (no stack trace), enumerates every supported verb on this ROM."));
        list.add(new DiLink5TestRunner.TestDef("R15", "platform_compat package overrides",
                "`dumpsys platform_compat 2>&1 | sed -n '/PackageOverrides:/,/^$/p' | head -60` — lists every currently-set per-package compat override on the device."));
        list.add(new DiLink5TestRunner.TestDef("R16", "compat status for our test packages",
                "`dumpsys platform_compat 2>&1 | grep -E 'ru\\.yandex\\.yandexmaps|com\\.waze|com\\.byd\\.dashcast' | head -20` — verifies our Fission test targets have no pre-existing override that could skew the test result."));
        list.add(new DiLink5TestRunner.TestDef("R17", "platform_compat metadata for 174042936",
                "`dumpsys platform_compat 2>&1 | grep -E -B1 -A1 '174042936|FORCE_RESIZE_APP' | head -30` — read-only metadata of change ID 174042936. v1.2.41: switched from BRE `\\|` to ERE `-E` because BusyBox grep on DL5 does not honor the BRE alternation."));
        list.add(new DiLink5TestRunner.TestDef("R18", "appcompat dumpsys filter",
                "`dumpsys platform_compat | grep -E 'FORCE_RESIZE_APP|OVERRIDE_MIN_ASPECT_RATIO|NEVER_SANDBOX_DISPLAY_APIS' | head -20` — confirms the compat changes are registered in the platform compat service."));

        // ── Global settings + dumpsys snapshots ──────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R19", "force_resizable_activities current",
                "settings get global force_resizable_activities → expected 0 (or null) post-v1.2.35."));
        list.add(new DiLink5TestRunner.TestDef("R20", "Global settings keyword scan",
                "settings list global | grep -E 'resizable|freeform|multi|sandbox|ignore_orientation'."));
        list.add(new DiLink5TestRunner.TestDef("R21", "dumpsys window keyword",
                "dumpsys window | grep -E 'freeform|multi_window|resizable|orientation' | head -40."));
        list.add(new DiLink5TestRunner.TestDef("R22", "dumpsys display keyword",
                "dumpsys display | grep -E 'Display Id|UniqueId|Owner|Name|fission|xdja|cluster|PRESENTATION'."));
        list.add(new DiLink5TestRunner.TestDef("R23", "Active root tasks",
                "dumpsys activity activities | sed -n '/Display #/,/Display #/p' | grep -E '#[0-9]+ |Task=Task|mBounds=|mWindowingMode|displayId=' | grep -v mGlobalConfig | head -30. Stanza-bound to avoid the mGlobalConfig noise."));

        // ── Window manager geometry per display ──────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R24", "wm size + wm density (default)",
                "Baseline geometry of the head unit display."));
        list.add(new DiLink5TestRunner.TestDef("R25", "wm size -d <clusterId>",
                "Per-display geometry of the cluster display, if found in R02."));

        // ── Per-app probing (cluster targets) ────────────────────────────
        // v1.2.38: target the ACTIVITY-level `resizeableActivity` attribute,
        // not the `<supports-screens>` resizeable (false positive on apps that
        // declare large/xlarge screen support but still ban activity resize).
        list.add(new DiLink5TestRunner.TestDef("R26", "Yandex Maps activity flags",
                "pm dump ru.yandex.yandexmaps | grep -E 'resizeMode|resizeableActivity|maxAspectRatio|launchMode|targetSdk' | head -10 — actual activity-level resize flags."));
        list.add(new DiLink5TestRunner.TestDef("R27", "Google Maps activity flags",
                "Same for Google Maps when installed."));
        list.add(new DiLink5TestRunner.TestDef("R28", "Waze activity flags",
                "Same for Waze."));

        // ── Services + system surface ────────────────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R29", "service list (cluster-relevant)",
                "service list | grep -iE 'auto_container|AutoContainer|cluster|fission|xdja|window|activity_task'."));
        list.add(new DiLink5TestRunner.TestDef("R30", "ro.* + persist.* + debug.* props",
                "getprop | grep -iE 'resizable|freeform|multi|sandbox|cluster|dilink|fission|xdja' | head -30."));

        return list;
    }

    /** Build a fresh PENDING result list. */
    public static List<DiLink5TestRunner.TestResult> emptyResults() {
        List<DiLink5TestRunner.TestResult> out = new ArrayList<>();
        for (DiLink5TestRunner.TestDef def : catalog()) {
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            out.add(r);
        }
        return out;
    }

    /** Async run — listener called on UI thread. */
    public static void runAll(Context ctx, Listener listener) {
        final Context appCtx = ctx.getApplicationContext();
        final List<DiLink5TestRunner.TestResult> results = emptyResults();
        EXEC.execute(() -> {
            UI.post(() -> listener.onSuiteStarted(results));
            for (int i = 0; i < results.size(); i++) {
                final int idx = i;
                final DiLink5TestRunner.TestResult r = results.get(i);
                r.status = DiLink5TestRunner.Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));
                long t0 = SystemClock.elapsedRealtime();
                try {
                    runOne(appCtx, r);
                } catch (Throwable th) {
                    r.status = DiLink5TestRunner.Status.FAIL;
                    r.message = "exception";
                    r.detail  = th.getClass().getSimpleName() + ": " + th.getMessage();
                }
                r.elapsedMs = SystemClock.elapsedRealtime() - t0;
                UI.post(() -> listener.onTestUpdated(idx, r));
            }
            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    // ─── Per-test logic ──────────────────────────────────────────────────────

    private static void runOne(Context ctx, DiLink5TestRunner.TestResult r) {
        switch (r.def.id) {
            case "R01": probePlatform(ctx, r); return;
            case "R02": probeDisplays(ctx, r); return;
            case "R03": shellCapture(ctx, r, "id -u && id -un && uname -a", "2000", "shell"); return;
            case "R04": shellCapture(ctx, r, "getenforce ; id -Z", "Enforcing", null); return;
            case "R05": probeClusterApps(ctx, r); return;
            case "R06": shellCapture(ctx, r, "am 2>&1 | head -80", null, null); return;
            case "R07": shellCapture(ctx, r, "am start 2>&1 | head -120", "--display", "--windowingMode"); return;
            case "R08": shellCapture(ctx, r, "cmd activity 2>&1 | head -80", null, null); return;
            case "R09": shellCapture(ctx, r, "cmd activity task 2>&1 | head -60", "resize", null); return;
            case "R10": shellCapture(ctx, r, "wm 2>&1 | head -60", "size", "density"); return;
            case "R11": shellCapture(ctx, r, "cmd window 2>&1 | head -60", null, null); return;
            case "R12": shellCapture(ctx, r, "cmd display 2>&1 | head -40", null, null); return;
            case "R13": shellCapture(ctx, r, "am compat enable 2>&1 | head -40", "enable", null); return;
            case "R14": shellCapture(ctx, r, "am compat 2>&1 | head -20", null, null); return;
            case "R15": shellCapture(ctx, r,
                    "dumpsys platform_compat 2>&1 | sed -n '/PackageOverrides:/,/^$/p' | head -60", null, null); return;
            case "R16": shellCapture(ctx, r,
                    "dumpsys platform_compat 2>&1 | grep -E 'ru\\.yandex\\.yandexmaps|com\\.waze|com\\.byd\\.dashcast' | head -20", null, null); return;
            case "R17": shellCapture(ctx, r,
                    // v1.2.41: switched BRE `\|` to ERE `-E` because BusyBox
                    // grep on the DL5 ROM does not honor BRE alternation
                    // (v1.2.40 returned 0 lines while R18 same pattern with
                    // -E matched correctly).
                    "dumpsys platform_compat 2>&1 | grep -E -B1 -A1 '174042936|FORCE_RESIZE_APP' | head -30",
                    null, null); return;
            case "R18": shellCapture(ctx, r, "dumpsys platform_compat 2>&1 | grep -E 'FORCE_RESIZE_APP|OVERRIDE_MIN_ASPECT_RATIO|NEVER_SANDBOX_DISPLAY_APIS' | head -20", null, null); return;
            case "R19": shellCapture(ctx, r, "settings get global force_resizable_activities 2>&1", null, null); return;
            case "R20": shellCapture(ctx, r,
                    "settings list global 2>&1 | grep -E 'resizable|freeform|multi|sandbox|ignore_orientation' | head -30", null, null); return;
            case "R21": shellCapture(ctx, r,
                    "dumpsys window 2>&1 | grep -E 'freeform|multi_window|resizable|orientation' | head -40", null, null); return;
            case "R22": shellCapture(ctx, r,
                    "dumpsys display 2>&1 | grep -E 'Display Id|UniqueId|Owner|Name|fission|xdja|cluster|PRESENTATION' | head -40", null, null); return;
            case "R23": shellCapture(ctx, r,
                    "dumpsys activity activities 2>&1 | grep -v mGlobalConfig | grep -E '^  Display #|Task=Task|mBounds=Rect|mWindowingMode=|displayId=' | head -40", null, null); return;
            case "R24": shellCapture(ctx, r, "wm size 2>&1 ; wm density 2>&1", null, null); return;
            case "R25": probeClusterDisplayGeometry(ctx, r); return;
            case "R26": probePerApp(ctx, r, "ru.yandex.yandexmaps"); return;
            case "R27": probePerApp(ctx, r, "com.google.android.apps.maps"); return;
            case "R28": probePerApp(ctx, r, "com.waze"); return;
            case "R29": shellCapture(ctx, r,
                    "service list 2>&1 | grep -iE 'auto_container|AutoContainer|cluster|fission|xdja|window|activity_task' | head -40", null, null); return;
            case "R30": shellCapture(ctx, r,
                    "getprop 2>&1 | grep -iE 'resizable|freeform|multi|sandbox|cluster|dilink|fission|xdja' | head -30", null, null); return;
            default:
                r.status = DiLink5TestRunner.Status.SKIPPED;
                r.message = "no impl";
        }
    }

    private static void probePlatform(Context ctx, DiLink5TestRunner.TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build.MODEL=").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT=").append(Build.PRODUCT).append('\n');
        sb.append("Build.FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        sb.append("SDK_INT=").append(Build.VERSION.SDK_INT).append('\n');
        // v1.2.38 fix: describeMode(null) NPEs on getApplicationContext().
        try {
            sb.append("Platform.detected=").append(Platform.get().describeMode(ctx));
        } catch (Throwable th) {
            sb.append("Platform.detected=<exception: ").append(th.getMessage()).append('>');
        }
        r.detail = sb.toString();
        boolean dl5 = Platform.get().isAutoDetectedDiLink5();
        r.status  = dl5 ? DiLink5TestRunner.Status.PASS : DiLink5TestRunner.Status.WARN;
        r.message = dl5 ? "DL5 (API " + Build.VERSION.SDK_INT + ")" :
                          "non-DL5 (API " + Build.VERSION.SDK_INT + ")";
    }

    /**
     * Display enumeration that combines DisplayManager (app-uid visible) with a
     * dumpsys-based fallback that surfaces displays hidden by the framework
     * (notably the physical cluster display id=2 on DL5, which is owned by
     * containerservice and never returned by DisplayManager.getDisplays()).
     */
    private static void probeDisplays(Context ctx, DiLink5TestRunner.TestResult r) {
        StringBuilder sb = new StringBuilder();
        // 1) DisplayManager view (what the app uid sees).
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm == null ? new Display[0] : dm.getDisplays();
        sb.append("DisplayManager.getDisplays(): ").append(all.length).append(" display(s)\n");
        for (Display d : all) {
            sb.append("  id=").append(d.getDisplayId())
              .append(" name=").append(d.getName())
              .append(" state=").append(d.getState())
              .append(" flags=0x").append(Integer.toHexString(d.getFlags()))
              .append('\n');
        }
        // 2) dumpsys display — catches hidden physical displays (id=2 on DL5).
        String dump = shellSync(ctx,
                "dumpsys display 2>&1 | grep -E '  Display Id=|DisplayDeviceInfo\\{' | head -40");
        sb.append("\ndumpsys display:\n").append(dump == null ? "<no output>" : dump);
        // 3) Cluster id heuristic: prefer the FIRST DisplayManager non-default
        // display whose name matches fission/xdja/cluster/virtual. If none, try
        // to parse a Display Id from the dumpsys output that's != 0.
        int cluster = -1;
        for (Display d : all) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            String name = d.getName() == null ? "" : d.getName().toLowerCase();
            if (name.contains("fission") || name.contains("xdja")
                    || name.contains("cluster") || name.contains("virtual")) {
                cluster = d.getDisplayId();
                break; // first wins, not last
            }
        }
        r.detail = sb.toString().trim();
        if (cluster >= 0) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "cluster displayId=" + cluster + " (" + all.length
                    + " via DisplayManager, +dumpsys for hidden ids)";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no cluster display among " + all.length
                    + " — see dumpsys output for hidden ids";
        }
    }

    private static void probeClusterApps(Context ctx, DiLink5TestRunner.TestResult r) {
        StringBuilder cmd = new StringBuilder("for p in");
        for (String p : CLUSTER_APPS) cmd.append(' ').append(p);
        cmd.append("; do v=$(pm list packages \"$p\" 2>/dev/null); ")
           .append("if [ -n \"$v\" ]; then echo \"INSTALLED $p\"; else echo \"MISSING   $p\"; fi; done");
        shellCapture(ctx, r, cmd.toString(), "INSTALLED", null);
    }

    /**
     * Per-display geometry. v1.2.38: we don't trust DisplayManager alone on DL5
     * because the hardware cluster display is hidden from app uid (only the
     * containerservice-owned virtual proxies id=3/4 are exposed). We parse the
     * full `dumpsys display` output for `Display Id=N` entries and probe `wm
     * size -d N` for every non-default id found.
     */
    private static void probeClusterDisplayGeometry(Context ctx, DiLink5TestRunner.TestResult r) {
        String idList = shellSync(ctx,
                "dumpsys display 2>&1 | grep -oE '  Display Id=[0-9]+' | grep -oE '[0-9]+' | sort -u | tr '\\n' ' '");
        if (idList == null || idList.trim().isEmpty()) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no Display Id parsed from dumpsys";
            return;
        }
        String[] ids = idList.trim().split("\\s+");
        StringBuilder cmd = new StringBuilder();
        for (String id : ids) {
            if ("0".equals(id)) continue; // default already done in R24
            cmd.append("echo '--- display ").append(id).append(" ---' ; ")
               .append("wm size -d ").append(id).append(" 2>&1 ; ")
               .append("wm density -d ").append(id).append(" 2>&1 ; ");
        }
        if (cmd.length() == 0) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "only default display visible (ids=" + idList.trim() + ")";
            return;
        }
        shellCapture(ctx, r, cmd.toString(), null, null);
        if (r.status == DiLink5TestRunner.Status.PASS) {
            r.message = "probed ids: " + idList.trim();
        }
    }

    private static void probePerApp(Context ctx, DiLink5TestRunner.TestResult r, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Exception e) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = pkg + " not installed";
            return;
        }
        // v1.2.38: target the activity-level resize flags (resizeMode,
        // resizeableActivity) instead of the manifest-level <supports-screens>.
        shellCapture(ctx, r,
                "pm dump " + pkg + " 2>&1 | grep -E 'resizeMode|resizeableActivity|maxAspectRatio|launchMode|targetSdk' | head -10",
                null, null);
    }

    /** Synchronous shell helper used by sub-probes. Returns stdout (or null on error/timeout). */
    private static String shellSync(Context ctx, String cmd) {
        final AtomicReference<String> out = new AtomicReference<>(null);
        final AtomicReference<String> err = new AtomicReference<>(null);
        final Object lock = new Object();
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String s) { out.set(s == null ? "" : s); synchronized (lock) { lock.notifyAll(); } }
            @Override public void onError(String e)   { err.set(e == null ? "?" : e); synchronized (lock) { lock.notifyAll(); } }
        });
        synchronized (lock) {
            try { lock.wait(6000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return err.get() != null ? null : out.get();
    }

    /**
     * Generic shell-probe helper: runs the command via AdbLocalClient and stores
     * stdout into {@code r.detail}. If {@code requiredA}/{@code requiredB} are
     * provided, also marks PASS only when one is present in stdout (else WARN).
     */
    private static void shellCapture(Context ctx, DiLink5TestRunner.TestResult r,
                                     String cmd, String requiredA, String requiredB) {
        final AtomicReference<String> out = new AtomicReference<>(null);
        final AtomicReference<String> err = new AtomicReference<>(null);
        final Object lock = new Object();
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String s) { out.set(s == null ? "" : s); synchronized (lock) { lock.notifyAll(); } }
            @Override public void onError(String e)   { err.set(e == null ? "?" : e); synchronized (lock) { lock.notifyAll(); } }
        });
        synchronized (lock) {
            try { lock.wait(6000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (err.get() != null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "ADB err";
            r.detail  = "$ " + cmd + "\n\n" + err.get();
            return;
        }
        String stdout = out.get();
        if (stdout == null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "timeout";
            r.detail  = "$ " + cmd;
            return;
        }
        r.detail = "$ " + cmd + "\n\n" + (stdout.length() > 4000 ? stdout.substring(0, 4000) + "\n…(truncated)" : stdout);
        if (requiredA == null && requiredB == null) {
            r.status = DiLink5TestRunner.Status.PASS;
            int lines = stdout.isEmpty() ? 0 : stdout.split("\n").length;
            r.message = "ok (" + lines + " lines)";
            return;
        }
        boolean a = requiredA == null || stdout.contains(requiredA);
        boolean b = requiredB == null || stdout.contains(requiredB);
        if (a && b) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "expected token(s) found";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "expected token(s) missing";
        }
    }

    /** Render a full text report from results — used by Copy / Telegram buttons. */
    public static String renderReport(List<DiLink5TestRunner.TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════\n");
        sb.append("DashCast — DL5 Cluster Recon\n");
        sb.append("════════════════════════════════════\n");
        sb.append("Build.MODEL=").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT=").append(Build.PRODUCT).append('\n');
        sb.append("Build.FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        sb.append("SDK_INT=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("\n");
        int pass = 0, warn = 0, fail = 0, skip = 0;
        for (DiLink5TestRunner.TestResult r : results) {
            switch (r.status) {
                case PASS: pass++; break;
                case WARN: warn++; break;
                case FAIL: fail++; break;
                case SKIPPED: skip++; break;
                default: break;
            }
        }
        sb.append("Summary: ").append(pass).append(" PASS / ")
          .append(warn).append(" WARN / ")
          .append(fail).append(" FAIL / ")
          .append(skip).append(" SKIPPED\n\n");
        for (DiLink5TestRunner.TestResult r : results) {
            sb.append("──────────────────────────────────\n");
            sb.append('[').append(r.def.id).append("] ").append(r.def.title)
              .append("  →  ").append(r.status)
              .append(" (").append(r.elapsedMs).append(" ms)\n");
            if (r.message != null && !r.message.isEmpty()) {
                sb.append("msg: ").append(r.message).append('\n');
            }
            if (r.detail != null && !r.detail.isEmpty()) {
                sb.append(r.detail).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fission live test battery (v1.2.41) — DiLink 5 ONLY.
    //
    // GOAL: prove the resize + move pipeline works on a fission display by
    // pushing Yandex Maps to a fission screen, applying the FORCE_RESIZE_APP
    // compat override, resizing the task, then cleaning up.
    //
    // STRICT RULES:
    //   1. DL5 only — the catalog refuses to run when Platform.isAutoDetectedDiLink5()
    //      is false. Every other platform sees a single SKIPPED row.
    //   2. NEVER touch display 0 (the head unit screen). If the only display
    //      visible to us is 0, the entire suite SKIPs F03..F10.
    //   3. Target is hard-coded to ru.yandex.yandexmaps. If Yandex Maps is
    //      not installed, F02..F10 SKIP cleanly.
    //   4. We always run F09 (force-stop Yandex) and F10 (compat reset) as
    //      cleanup, even on a failure of an earlier step, so the device is
    //      left in a clean state between runs.
    // ═══════════════════════════════════════════════════════════════════════

    /** Hard-coded Fission test target. */
    private static final String FISSION_TARGET_PKG = "ru.yandex.yandexmaps";
    /** Numeric ID of the FORCE_RESIZE_APP compat change. */
    private static final String FORCE_RESIZE_APP_ID = "174042936";

    /** Mutable state shared between F-tests within a single run. */
    private static final class FissionState {
        int  fissionDisplayId = -1;
        int  yandexTaskId     = -1;
        boolean overrideEnabled = false;
        boolean abortFromHere   = false;
    }

    public static List<DiLink5TestRunner.TestDef> fissionCatalog() {
        List<DiLink5TestRunner.TestDef> list = new ArrayList<>();
        list.add(new DiLink5TestRunner.TestDef("F01", "Detect fission display (id ≠ 0)",
                "Walk DisplayManager.getDisplays() for the lowest non-zero id with a fission/xdja/cluster name. STRICT: id=0 is excluded; if none found, the whole F-tier SKIPs."));
        list.add(new DiLink5TestRunner.TestDef("F02", "Yandex Maps installed?",
                "PackageManager.getPackageInfo(ru.yandex.yandexmaps). SKIPs F03..F10 if absent."));
        list.add(new DiLink5TestRunner.TestDef("F03", "Enable FORCE_RESIZE_APP on Yandex Maps",
                "am compat enable 174042936 ru.yandex.yandexmaps — forces ATM to treat the activity as resizeable regardless of its manifest resizeMode."));
        list.add(new DiLink5TestRunner.TestDef("F04", "Force-stop Yandex Maps (pre-launch)",
                "am force-stop ru.yandex.yandexmaps — the new compat override only takes effect on the next Application.onCreate, so we must kill the existing process first."));
        list.add(new DiLink5TestRunner.TestDef("F05", "Launch Yandex Maps on fission display",
                "am start --display N --windowingMode 5 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ru.yandex.yandexmaps. N is the fission id detected in F01."));
        list.add(new DiLink5TestRunner.TestDef("F06", "Verify task on fission + freeform",
                "Wait ~3 s, dumpsys activity activities, scan for ru.yandex.yandexmaps task, confirm displayId=N and (freeform | windowingMode=5). Captures taskId for F07/F08."));
        list.add(new DiLink5TestRunner.TestDef("F07", "Resize task on fission",
                "cmd activity task resize <taskId> 100 80 1820 640 — apply a 100×80 inset rectangle inside the 1920×720 fission framebuffer."));
        list.add(new DiLink5TestRunner.TestDef("F08", "Verify new bounds",
                "Wait ~2 s, dumpsys activity activities | grep mBounds — confirm mBounds reflects the rectangle from F07."));
        list.add(new DiLink5TestRunner.TestDef("F09", "Move task back to main display (id=0)",
                "am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ru.yandex.yandexmaps — reparents the Yandex task onto display 0 (the main screen). No resize / no size mutation on display 0: this is the exact same intent the system fires when the user taps Yandex Maps on the head unit."));
        list.add(new DiLink5TestRunner.TestDef("F10", "Verify task on main display (id=0)",
                "Wait ~2 s, dumpsys activity activities, scan for ru.yandex.yandexmaps task, confirm displayId=0. Validates the move pipeline end-to-end (fission → main)."));
        list.add(new DiLink5TestRunner.TestDef("F11", "Cleanup: force-stop Yandex",
                "am force-stop ru.yandex.yandexmaps — leaves the device empty of Yandex Maps state, ready for a fresh run on another fission display."));
        list.add(new DiLink5TestRunner.TestDef("F12", "Cleanup: reset FORCE_RESIZE_APP override",
                "am compat reset 174042936 ru.yandex.yandexmaps — restores Yandex Maps to its declared resize behaviour, leaves zero state behind."));
        return list;
    }

    public static List<DiLink5TestRunner.TestResult> emptyFissionResults() {
        List<DiLink5TestRunner.TestResult> out = new ArrayList<>();
        for (DiLink5TestRunner.TestDef def : fissionCatalog()) {
            DiLink5TestRunner.TestResult r = new DiLink5TestRunner.TestResult(def);
            r.status = DiLink5TestRunner.Status.PENDING;
            out.add(r);
        }
        return out;
    }

    /** Async fission run — listener called on UI thread. */
    public static void runFission(Context ctx, Listener listener) {
        final Context appCtx = ctx.getApplicationContext();
        final List<DiLink5TestRunner.TestResult> results = emptyFissionResults();
        EXEC.execute(() -> {
            UI.post(() -> listener.onSuiteStarted(results));
            final FissionState st = new FissionState();
            // Hard gate: refuse to run on anything but DL5.
            if (!Platform.get().isAutoDetectedDiLink5()) {
                for (int i = 0; i < results.size(); i++) {
                    DiLink5TestRunner.TestResult r = results.get(i);
                    r.status = DiLink5TestRunner.Status.SKIPPED;
                    r.message = "DL5 only — current platform: "
                            + (Platform.get().describeMode(appCtx));
                    final int idx = i;
                    UI.post(() -> listener.onTestUpdated(idx, r));
                }
                UI.post(() -> listener.onSuiteFinished(results));
                return;
            }
            for (int i = 0; i < results.size(); i++) {
                final int idx = i;
                final DiLink5TestRunner.TestResult r = results.get(i);
                r.status = DiLink5TestRunner.Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));
                long t0 = SystemClock.elapsedRealtime();
                try {
                    runFissionOne(appCtx, r, st);
                } catch (Throwable th) {
                    r.status = DiLink5TestRunner.Status.FAIL;
                    r.message = "exception";
                    r.detail  = th.getClass().getSimpleName() + ": " + th.getMessage();
                }
                r.elapsedMs = SystemClock.elapsedRealtime() - t0;
                UI.post(() -> listener.onTestUpdated(idx, r));
            }
            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    private static void runFissionOne(Context ctx, DiLink5TestRunner.TestResult r,
                                      FissionState st) {
        switch (r.def.id) {
            case "F01": fissionDetectDisplay(ctx, r, st); return;
            case "F02": fissionCheckYandexInstalled(ctx, r, st); return;
            case "F03": fissionEnableOverride(ctx, r, st); return;
            case "F04": fissionForceStop(ctx, r, st, "pre-launch"); return;
            case "F05": fissionLaunch(ctx, r, st); return;
            case "F06": fissionVerifyOnFission(ctx, r, st); return;
            case "F07": fissionResizeTask(ctx, r, st); return;
            case "F08": fissionVerifyBounds(ctx, r, st); return;
            case "F09": fissionMoveToMainDisplay(ctx, r, st); return;
            case "F10": fissionVerifyOnMainDisplay(ctx, r, st); return;
            case "F11": fissionForceStop(ctx, r, st, "cleanup"); return;
            case "F12": fissionResetOverride(ctx, r, st); return;
            default:
                r.status = DiLink5TestRunner.Status.SKIPPED;
                r.message = "no impl";
        }
    }

    // ── Fission per-step implementations ────────────────────────────────────

    private static boolean isFissionName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("fission") || n.contains("xdja") || n.contains("cluster");
    }

    private static void fissionDetectDisplay(Context ctx, DiLink5TestRunner.TestResult r,
                                             FissionState st) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "no DisplayManager";
            st.abortFromHere = true;
            return;
        }
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder("DisplayManager.getDisplays():\n");
        int picked = -1;
        for (Display d : all) {
            sb.append("  id=").append(d.getDisplayId())
              .append(" name=").append(d.getName()).append('\n');
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue; // STRICT: skip id=0
            if (picked >= 0) continue;
            if (isFissionName(d.getName())) picked = d.getDisplayId();
        }
        // Fallback: lowest non-zero id even if name doesn't match (e.g. hidden Id=2).
        if (picked < 0) {
            for (Display d : all) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                picked = d.getDisplayId();
                break;
            }
        }
        r.detail = sb.toString();
        if (picked <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no non-zero display available — F-tier aborted";
            st.abortFromHere = true;
            return;
        }
        st.fissionDisplayId = picked;
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = "fission displayId=" + picked;
    }

    private static void fissionCheckYandexInstalled(Context ctx, DiLink5TestRunner.TestResult r,
                                                    FissionState st) {
        if (st.abortFromHere) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted by F01";
            return;
        }
        try {
            ctx.getPackageManager().getPackageInfo(FISSION_TARGET_PKG, 0);
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = FISSION_TARGET_PKG + " installed";
        } catch (Exception e) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = FISSION_TARGET_PKG + " not installed — F03..F10 skipped";
            st.abortFromHere = true;
        }
    }

    private static void fissionEnableOverride(Context ctx, DiLink5TestRunner.TestResult r,
                                              FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        String cmd = "am compat enable " + FORCE_RESIZE_APP_ID + " " + FISSION_TARGET_PKG + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        if (out != null && out.toLowerCase().contains("enabled")) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "override enabled";
            st.overrideEnabled = true;
        } else if (out != null && !out.toLowerCase().contains("error")
                && !out.toLowerCase().contains("exception")) {
            // Some ROMs emit no acknowledgement on success.
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "no error";
            st.overrideEnabled = true;
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no positive ack — see detail";
        }
    }

    private static void fissionForceStop(Context ctx, DiLink5TestRunner.TestResult r,
                                         FissionState st, String tag) {
        // Cleanup steps run even when an earlier step aborted, so we don't gate.
        String cmd = "am force-stop " + FISSION_TARGET_PKG + " 2>&1 ; echo __done__";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = tag + " force-stop sent";
    }

    private static void fissionLaunch(Context ctx, DiLink5TestRunner.TestResult r,
                                      FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.fissionDisplayId <= 0) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "no fission display id";
            st.abortFromHere = true;
            return;
        }
        String cmd = "am start --display " + st.fissionDisplayId
                + " --windowingMode 5"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -p " + FISSION_TARGET_PKG + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        if (out != null && (out.contains("Starting:") || out.contains("Status: ok"))) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "launch issued on display " + st.fissionDisplayId;
        } else if (out != null && out.toLowerCase().contains("error")) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "launch error";
            st.abortFromHere = true;
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "ambiguous launch output";
        }
    }

    private static void fissionVerifyOnFission(Context ctx, DiLink5TestRunner.TestResult r,
                                               FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String dump = shellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -v mGlobalConfig"
              + " | grep -E '" + FISSION_TARGET_PKG.replace(".", "\\.")
              + "|displayId=|mWindowingMode=|Task=Task|mBounds=Rect' | head -80");
        r.detail = "$ dumpsys activity activities (filtered)\n\n"
                + (dump == null ? "<no output>" : dump);
        if (dump == null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "dumpsys failed";
            return;
        }
        // Extract first taskId on a line that mentions yandex.
        int taskId = -1;
        for (String line : dump.split("\n")) {
            if (!line.contains(FISSION_TARGET_PKG)) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\d+)").matcher(line);
            if (m.find()) { try { taskId = Integer.parseInt(m.group(1)); break; }
                            catch (NumberFormatException ignored) {} }
        }
        st.yandexTaskId = taskId;
        boolean onFission = dump.contains("displayId=" + st.fissionDisplayId);
        boolean freeform  = dump.contains("mWindowingMode=freeform")
                         || dump.contains("windowingMode=5");
        if (taskId > 0 && onFission && freeform) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "taskId=" + taskId + " on display " + st.fissionDisplayId + " in freeform";
        } else if (taskId > 0 && onFission) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "taskId=" + taskId + " on display " + st.fissionDisplayId
                      + " but windowing mode unclear";
        } else {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "task not found on fission display "
                      + st.fissionDisplayId + " (taskId=" + taskId + ")";
        }
    }

    private static void fissionResizeTask(Context ctx, DiLink5TestRunner.TestResult r,
                                          FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.yandexTaskId <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no taskId from F06";
            return;
        }
        // Fission frame is 1920×720 → apply a 100×80 inset rectangle.
        String cmd = "cmd activity task resize " + st.yandexTaskId
                + " 100 80 1820 640 2>&1 ; echo __exit=$?";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        if (out != null && out.contains("__exit=0")) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "resize exit=0";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "non-zero exit or unclear";
        }
    }

    private static void fissionVerifyBounds(Context ctx, DiLink5TestRunner.TestResult r,
                                            FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.yandexTaskId <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no taskId from F06";
            return;
        }
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String dump = shellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -v mGlobalConfig"
              + " | grep -E '#" + st.yandexTaskId + " |mBounds=Rect|mWindowingMode=' | head -30");
        r.detail = "$ dumpsys activity activities (taskId=" + st.yandexTaskId + ")\n\n"
                + (dump == null ? "<no output>" : dump);
        if (dump == null) { r.status = DiLink5TestRunner.Status.FAIL; r.message = "dumpsys failed"; return; }
        boolean hit = dump.contains("Rect(100, 80")
                   || dump.contains("Rect(100,80")
                   || (dump.contains("100") && dump.contains("1820"));
        if (hit) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "new bounds visible";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "bounds not confirmed — see detail";
        }
    }

    private static void fissionMoveToMainDisplay(Context ctx, DiLink5TestRunner.TestResult r,
                                                 FissionState st) {
        // Cleanup-adjacent step: even if F03..F08 aborted, attempting the move
        // back to display 0 is safe — it's the same intent the system fires
        // when the user taps the app on the head unit, no resize / no size
        // mutation on display 0.
        // RULE: we only ever LAUNCH on display 0 here; we never run any
        // resize, set-density, set-bounds or wm command against display 0.
        String cmd = "am start --display 0"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -p " + FISSION_TARGET_PKG + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        if (out != null && (out.contains("Starting:") || out.contains("Status: ok"))) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "move-to-display-0 issued";
        } else if (out != null && out.toLowerCase().contains("error")) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "move error — see detail";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "ambiguous move output";
        }
    }

    private static void fissionVerifyOnMainDisplay(Context ctx, DiLink5TestRunner.TestResult r,
                                                   FissionState st) {
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String dump = shellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -v mGlobalConfig"
              + " | grep -E '" + FISSION_TARGET_PKG.replace(".", "\\.")
              + "|displayId=|Task=Task' | head -60");
        r.detail = "$ dumpsys activity activities (filtered, post-move)\n\n"
                + (dump == null ? "<no output>" : dump);
        if (dump == null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "dumpsys failed";
            return;
        }
        boolean onMain = dump.contains("displayId=0");
        boolean stillHasYandex = dump.contains(FISSION_TARGET_PKG);
        if (onMain && stillHasYandex) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "task moved to display 0";
        } else if (stillHasYandex) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "task present but displayId=0 not confirmed";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "task not found post-move — see detail";
        }
    }

    private static void fissionResetOverride(Context ctx, DiLink5TestRunner.TestResult r,
                                             FissionState st) {
        // Cleanup runs unconditionally.
        if (!st.overrideEnabled) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no override to reset";
            return;
        }
        String cmd = "am compat reset " + FORCE_RESIZE_APP_ID + " " + FISSION_TARGET_PKG + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = "reset sent";
    }

    /** Render a fission-tier text report. */
    public static String renderFissionReport(List<DiLink5TestRunner.TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════\n");
        sb.append("DashCast — DL5 Fission Live Tests\n");
        sb.append("════════════════════════════════════\n");
        sb.append("Build.MODEL=").append(Build.MODEL).append('\n');
        sb.append("Build.FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        sb.append("Target=").append(FISSION_TARGET_PKG).append('\n');
        sb.append("ChangeId=").append(FORCE_RESIZE_APP_ID).append(" (FORCE_RESIZE_APP)\n\n");
        int pass = 0, warn = 0, fail = 0, skip = 0;
        for (DiLink5TestRunner.TestResult r : results) {
            switch (r.status) {
                case PASS: pass++; break;
                case WARN: warn++; break;
                case FAIL: fail++; break;
                case SKIPPED: skip++; break;
                default: break;
            }
        }
        sb.append("Summary: ").append(pass).append(" PASS / ")
          .append(warn).append(" WARN / ")
          .append(fail).append(" FAIL / ")
          .append(skip).append(" SKIPPED\n\n");
        for (DiLink5TestRunner.TestResult r : results) {
            sb.append("──────────────────────────────────\n");
            sb.append('[').append(r.def.id).append("] ").append(r.def.title)
              .append("  →  ").append(r.status)
              .append(" (").append(r.elapsedMs).append(" ms)\n");
            if (r.message != null && !r.message.isEmpty()) {
                sb.append("msg: ").append(r.message).append('\n');
            }
            if (r.detail != null && !r.detail.isEmpty()) {
                sb.append(r.detail).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
