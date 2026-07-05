package com.byd.dashcast.dilink5;
import java.util.Locale;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
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
        /**
         * v1.2.48 — interactive Yes/No prompt requested by the runner thread.
         * Implementations should show a modal dialog and invoke {@code callback}
         * with the user's choice. Default = NO (so any pre-v1.2.48 listener
         * keeps working without changes).
         */
        default void onPromptYesNo(String title, String message,
                                   java.util.function.Consumer<Boolean> callback) {
            callback.accept(false);
        }
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
            String name = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
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

    /**
     * v1.2.41 default Fission test target. Kept as fallback for the legacy
     * {@link #runFission(Context, Listener)} overload; v1.2.56+ callers should
     * pass an explicit package via {@link #runFission(Context, String, Listener)}.
     */
    private static final String DEFAULT_FISSION_TARGET_PKG = "ru.yandex.yandexmaps";
    /** Numeric ID of the FORCE_RESIZE_APP compat change. */
    private static final String FORCE_RESIZE_APP_ID = "174042936";

    /**
     * v1.2.56 — remembered for {@link #renderFissionReport(java.util.List)}.
     * Updated by {@link #runFission(Context, String, Listener)}. Default value
     * preserves the v1.2.41 wording for any caller still using the no-target overload.
     */
    private static volatile String sLastFissionTarget = DEFAULT_FISSION_TARGET_PKG;

    /** Mutable state shared between F-tests within a single run. */
    private static final class FissionState {
        boolean abortFromHere   = false;
        boolean overrideEnabled = false;
        /** v1.2.56 — package the user picked for this run. */
        String  targetPkg       = DEFAULT_FISSION_TARGET_PKG;
        /** v1.2.56 — human-readable label of {@link #targetPkg} (defaults to pkg). */
        String  targetLabel     = DEFAULT_FISSION_TARGET_PKG;
        /** Resolved launcher component for {@link #targetPkg}, e.g.
         *  "ru.yandex.yandexmaps/.SplashScreen". Filled by F02. */
        String  targetActivity  = null;
        /** v1.2.48 — set by F01 when the cluster projection was opened by
         *  this run. F16 only sends the close sequence if true, so we don't
         *  tear down a projection the user had started manually. */
        boolean projectionOpenedByUs = false;
        /** v1.2.48 — the display id the user confirmed as the cluster screen
         *  (2, 3 or 4). -1 = no confirmation yet. */
        int confirmedClusterDisplay = -1;
        /** v1.2.48 — taskId of Yandex Maps as last extracted on the confirmed
         *  cluster display, used by F10/F11/F12. */
        int yandexTaskIdOnCluster = -1;
        /** v1.2.72 — stackId hosting {@link #yandexTaskIdOnCluster}, captured
         *  by F10 and consumed by F11. -1 = unknown. */
        int targetStackId = -1;
        /** v1.2.72 — verb name (if any) that actually mutated mBounds during
         *  F11. Empty = none worked. Used by F12 message. */
        String winningVerb = "";
        /** v1.2.48 — last display id where Yandex was successfully launched
         *  (used by the corresponding prompt step to gate its execution). */
        int lastLaunchedDisplay = -1;
    }

    /** Candidate cluster display ids tried in order by the F-tier (v1.2.48). */
    private static final int[] FISSION_CANDIDATE_DISPLAYS = { 2, 3, 4 };

    public static List<DiLink5TestRunner.TestDef> fissionCatalog() {
        List<DiLink5TestRunner.TestDef> list = new ArrayList<>();
        list.add(new DiLink5TestRunner.TestDef("F01",
                "Activate DL5 projection (cluster mirror)",
                "service call auto_container 2 i32 1000 i32 16 s16 \"\" — opens the BYD cluster projection so fission displays (id 2/3/4) become routable. Required before any cluster-targeted am start can be observed."));
        list.add(new DiLink5TestRunner.TestDef("F02",
                "Target app installed + launcher resolved?",
                "PackageManager.getPackageInfo(<targetPkg>) + getLaunchIntentForPackage to resolve the actual launcher activity. SKIPs the rest of the suite if absent or unresolvable."));
        list.add(new DiLink5TestRunner.TestDef("F03",
                "Enable FORCE_RESIZE_APP on target app",
                "am compat enable 174042936 <targetPkg> — forces ATM to treat the activity as resizeable regardless of its manifest resizeMode."));
        list.add(new DiLink5TestRunner.TestDef("F04",
                "Display 2 — force-stop + launch target",
                "am force-stop + am start --display 2 --windowingMode 5 -n <component>."));
        list.add(new DiLink5TestRunner.TestDef("F05",
                "Display 2 — user confirms visible on cluster?",
                "Interactive Yes/No prompt. YES → mark display 2 as cluster, skip F06..F09. NO → move target back to display 0 + force-stop, then proceed to display 3."));
        list.add(new DiLink5TestRunner.TestDef("F06",
                "Display 3 — force-stop + launch target",
                "SKIPPED if user already confirmed display 2."));
        list.add(new DiLink5TestRunner.TestDef("F07",
                "Display 3 — user confirms visible on cluster?",
                "SKIPPED if previous display was confirmed. NO → move back + force-stop, try display 4."));
        list.add(new DiLink5TestRunner.TestDef("F08",
                "Display 4 — force-stop + launch target",
                "SKIPPED if user already confirmed display 2 or 3."));
        list.add(new DiLink5TestRunner.TestDef("F09",
                "Display 4 — user confirms visible on cluster?",
                "SKIPPED if previous display was confirmed. NO → no cluster display identified, resize tests F10..F12 will SKIP."));
        // v1.2.72 — DL5 resize battery REFOCUSED. Field reports v1.2.59 +
        // v1.2.60 proved 6 shell avenues dead (set-task-windowing-mode,
        // task resize silent no-op, set-display-windowing-mode,
        // --activity-launch-bounds, …). The 3 probes below replace the
        // 7 scattered ones and go straight at the remaining shell-shaped
        // hole: resize the STACK (not the task), via every verb that ever
        // existed for it.
        list.add(new DiLink5TestRunner.TestDef("F10",
                "Topology — capture taskId / stackId / windowing-mode + verb inventory",
                "Capture the full state needed by F11: extracted taskId of the target app on the "
              + "confirmed cluster display, its containing stackId, current mBounds + mWindowingMode, "
              + "and a compact inventory of resize-relevant verbs (`cmd activity stack`, "
              + "`cmd activity task`, `am stack`, `wm`). Pure read-only."));
        list.add(new DiLink5TestRunner.TestDef("F11",
                "DL3 chain via shell — try every stack-resize verb in sequence",
                "Single atomic shell script that reproduces the DL3 production resize chain via "
              + "every shell variant. Step 1 unlocks `cmd activity task resizeable <taskId> 2`. "
              + "Step 2 tries 4 verbs in order — `am stack resize <stackId>`, "
              + "`cmd activity stack resize <stackId>`, `cmd window stack resize <stackId>`, then "
              + "the already-known `cmd activity task resize <taskId>` (in case resizeable=2 unlocked "
              + "it). Each step's exit code + dumpsys diff is logged, and the probe reports which "
              + "(if any) actually changed mBounds. PASS = at least one verb mutated the rect."));
        list.add(new DiLink5TestRunner.TestDef("F12",
                "Verify final bounds + windowing-mode",
                "Wait ~2 s, dumpsys activity activities scoped to the target task — confirm mBounds "
              + "reflects the rectangle attempted by F11 and report the winning verb if any."));
        list.add(new DiLink5TestRunner.TestDef("F13",
                "Move target back to display 0",
                "am start --display 0 -n <component> — reparents the task onto the head unit. No resize / no size mutation on display 0."));
        list.add(new DiLink5TestRunner.TestDef("F14",
                "Cleanup: force-stop target app",
                "am force-stop <targetPkg> — leaves zero state behind."));
        list.add(new DiLink5TestRunner.TestDef("F15",
                "Cleanup: reset FORCE_RESIZE_APP override",
                "am compat reset 174042936 <targetPkg> — restores the target app to its declared resize behaviour."));
        list.add(new DiLink5TestRunner.TestDef("F16",
                "Cleanup: close DL5 projection (if opened by F01)",
                "service call auto_container 2 i32 1000 i32 18 + i32 0 — restores Qt video stream. SKIPPED if F01 did not open the projection in this run."));
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

    /** Async fission run — listener called on UI thread. Uses the v1.2.41 default target (Yandex Maps). */
    public static void runFission(Context ctx, Listener listener) {
        runFission(ctx, DEFAULT_FISSION_TARGET_PKG, DEFAULT_FISSION_TARGET_PKG, listener);
    }

    /**
     * v1.2.56 — async fission run against an explicit target package picked
     * by the user. {@code targetPkg} must be a launchable app; if null or
     * blank, falls back to {@link #DEFAULT_FISSION_TARGET_PKG}.
     */
    public static void runFission(Context ctx, String targetPkg, Listener listener) {
        runFission(ctx, targetPkg, targetPkg, listener);
    }

    /**
     * v1.2.56 — async fission run with explicit package + human-readable label.
     */
    public static void runFission(Context ctx, String targetPkg, String targetLabel,
                                  Listener listener) {
        final String finalTarget = (targetPkg == null || targetPkg.trim().isEmpty())
                ? DEFAULT_FISSION_TARGET_PKG : targetPkg.trim();
        final String finalLabel = (targetLabel == null || targetLabel.trim().isEmpty())
                ? finalTarget : targetLabel.trim();
        sLastFissionTarget = finalTarget;
        final Context appCtx = ctx.getApplicationContext();
        final List<DiLink5TestRunner.TestResult> results = emptyFissionResults();
        EXEC.execute(() -> {
            UI.post(() -> listener.onSuiteStarted(results));
            final FissionState st = new FissionState();
            st.targetPkg = finalTarget;
            st.targetLabel = finalLabel;
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
                    runFissionOne(appCtx, r, st, listener);
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
                                      FissionState st, Listener listener) {
        switch (r.def.id) {
            case "F01": fissionActivateProjection(ctx, r, st); return;
            case "F02": fissionCheckYandexInstalled(ctx, r, st); return;
            case "F03": fissionEnableOverride(ctx, r, st); return;
            case "F04": fissionAttemptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[0]); return;
            case "F05": fissionPromptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[0], listener); return;
            case "F06": fissionAttemptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[1]); return;
            case "F07": fissionPromptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[1], listener); return;
            case "F08": fissionAttemptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[2]); return;
            case "F09": fissionPromptDisplay(ctx, r, st, FISSION_CANDIDATE_DISPLAYS[2], listener); return;
            case "F10": fissionCaptureTopology(ctx, r, st); return;
            case "F11": fissionDl3ChainViaShell(ctx, r, st); return;
            case "F12": fissionVerifyFinalBounds(ctx, r, st); return;
            case "F13": fissionMoveBackToMain(ctx, r, st); return;
            case "F14": fissionForceStop(ctx, r, st, "cleanup"); return;
            case "F15": fissionResetOverride(ctx, r, st); return;
            case "F16": fissionCloseProjection(ctx, r, st); return;
            default:
                r.status = DiLink5TestRunner.Status.SKIPPED;
                r.message = "no impl";
        }
    }

    // ── Fission per-step implementations ────────────────────────────────────

    /** F01 — open the BYD cluster projection so fission displays become routable. */
    private static void fissionActivateProjection(Context ctx, DiLink5TestRunner.TestResult r,
                                                  FissionState st) {
        String cmd = "service call " + com.byd.dashcast.infrastructure.AdbLocalClient.autoContainerSvcName(ctx) + " 2 i32 1000 i32 16 s16 \"\" 2>&1";
        String out = shellSync(ctx, cmd);
        StringBuilder dt = new StringBuilder();
        dt.append("$ ").append(cmd).append("\n\n").append(out == null ? "<no output>" : out);
        // Also snapshot DisplayManager for diagnostic — fission displays may
        // or may not be visible to apps depending on the platform build.
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            dt.append("\n\nDisplayManager.getDisplays():\n");
            for (Display d : dm.getDisplays()) {
                dt.append("  id=").append(d.getDisplayId())
                  .append(" name=").append(d.getName()).append('\n');
            }
        }
        dt.append("\n(candidate displays to probe: ")
          .append(Arrays.toString(FISSION_CANDIDATE_DISPLAYS)).append(")");
        r.detail = dt.toString();
        st.projectionOpenedByUs = true;
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = "projection open requested (sendInfo 16)";
        // Give compositor a moment to wire the fission displays before F04.
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static void fissionCheckYandexInstalled(Context ctx, DiLink5TestRunner.TestResult r,
                                                    FissionState st) {
        if (st.abortFromHere) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier";
            return;
        }
        try {
            ctx.getPackageManager().getPackageInfo(st.targetPkg, 0);
        } catch (Exception e) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = st.targetPkg + " not installed — F03..F16 skipped";
            st.abortFromHere = true;
            return;
        }
        android.content.Intent launchIntent =
                ctx.getPackageManager().getLaunchIntentForPackage(st.targetPkg);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = st.targetPkg + " installed but no launcher activity — F03..F16 skipped";
            st.abortFromHere = true;
            return;
        }
        st.targetActivity = launchIntent.getComponent().flattenToShortString();
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = st.targetPkg + " installed, launcher=" + st.targetActivity;
    }

    private static void fissionEnableOverride(Context ctx, DiLink5TestRunner.TestResult r,
                                              FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        String cmd = "am compat enable " + FORCE_RESIZE_APP_ID + " " + st.targetPkg + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        if (out != null && out.toLowerCase(Locale.ROOT).contains("enabled")) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "override enabled";
            st.overrideEnabled = true;
        } else if (out != null && !out.toLowerCase(Locale.ROOT).contains("error")
                && !out.toLowerCase(Locale.ROOT).contains("exception")) {
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
        String cmd = "am force-stop " + st.targetPkg + " 2>&1 ; echo __done__";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = tag + " force-stop sent";
    }

    /**
     * v1.2.48 — F04/F06/F08. Force-stop Yandex then launch it on the given
     * fission candidate display. SKIPs cleanly if (a) earlier abort, (b) a
     * previous candidate was already confirmed by the user, (c) F02 didn't
     * resolve a launcher component.
     */
    private static void fissionAttemptDisplay(Context ctx, DiLink5TestRunner.TestResult r,
                                              FissionState st, int displayId) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.confirmedClusterDisplay > 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "display " + st.confirmedClusterDisplay + " already confirmed as cluster";
            return;
        }
        if (st.targetActivity == null) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no resolved launcher (F02)";
            return;
        }
        StringBuilder dt = new StringBuilder();
        // Force-stop so the FORCE_RESIZE_APP override picks up on next launch.
        String fsCmd = "am force-stop " + st.targetPkg + " 2>&1 ; echo __done__";
        String fsOut = shellSync(ctx, fsCmd);
        dt.append("$ ").append(fsCmd).append("\n").append(fsOut == null ? "<no output>" : fsOut).append("\n\n");
        try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        // Launch on the candidate display, request freeform.
        String launchCmd = "am start --display " + displayId
                + " --windowingMode 5"
                + " -n " + st.targetActivity + " 2>&1";
        String launchOut = shellSync(ctx, launchCmd);
        dt.append("$ ").append(launchCmd).append("\n").append(launchOut == null ? "<no output>" : launchOut);
        r.detail = dt.toString();
        String lo = launchOut == null ? "" : launchOut.toLowerCase(Locale.ROOT);
        if (lo.contains("unable to resolve")) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "unable to resolve intent on display " + displayId;
        } else if (lo.contains("does not exist") || lo.contains("invalid display")
                || lo.contains("no display")) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "display " + displayId + " not accessible on this platform";
        } else if (launchOut != null && (launchOut.contains("Starting:")
                || launchOut.contains("Status: ok")
                || launchOut.contains("delivered to top"))) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "Yandex launched on display " + displayId;
            st.lastLaunchedDisplay = displayId;
            // Let the activity settle before the user looks at the cluster.
            try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        } else if (lo.contains("error")) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "launch error on display " + displayId + " — see detail";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "ambiguous launch output on display " + displayId;
            // Still try the prompt — the activity may have started anyway.
            st.lastLaunchedDisplay = displayId;
            try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * v1.2.48 — F05/F07/F09. Synchronous user prompt: "Is Yandex visible on
     * the cluster screen?". YES → record this display as the cluster. NO →
     * move Yandex back to display 0 + force-stop so the next candidate
     * launches from a clean slate.
     */
    private static void fissionPromptDisplay(Context ctx, DiLink5TestRunner.TestResult r,
                                             FissionState st, int displayId, Listener listener) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.confirmedClusterDisplay > 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "display " + st.confirmedClusterDisplay + " already confirmed";
            return;
        }
        if (st.lastLaunchedDisplay != displayId) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "launch on display " + displayId + " did not succeed — no prompt";
            return;
        }
        String title = ctx.getString(
                com.byd.dashcast.R.string.diag_dl5_fission_prompt_title);
        String msg = ctx.getString(
                com.byd.dashcast.R.string.diag_dl5_fission_prompt_msg_fmt,
                st.targetLabel, displayId);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean answer =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        UI.post(() -> listener.onPromptYesNo(title, msg, ans -> {
            answer.set(ans != null && ans);
            latch.countDown();
        }));
        boolean timedOut = false;
        try {
            if (!latch.await(180, java.util.concurrent.TimeUnit.SECONDS)) timedOut = true;
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        boolean yes = answer.get();
        StringBuilder dt = new StringBuilder();
        dt.append("prompt title : ").append(title).append('\n');
        dt.append("prompt msg   : ").append(msg).append('\n');
        dt.append("user answer  : ").append(timedOut ? "TIMEOUT" : (yes ? "YES" : "NO")).append('\n');
        if (timedOut) {
            r.detail = dt.toString();
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "user prompt timed out (180 s) on display " + displayId;
            return;
        }
        if (yes) {
            st.confirmedClusterDisplay = displayId;
            // Best-effort: extract the target task id for the resize step.
            StringBuilder dbg = new StringBuilder();
            int tid = extractYandexTaskId(ctx, st.targetPkg, dbg);
            st.yandexTaskIdOnCluster = tid;
            dt.append("extracted taskId = ").append(tid).append('\n');
            dt.append("\n--- task lookup debug ---\n").append(dbg);
            r.detail = dt.toString();
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "user confirmed cluster on display " + displayId
                    + (tid > 0 ? " (taskId=" + tid + ")" : " (taskId not extracted)");
        } else {
            // Move Yandex back to display 0 + force-stop so the next candidate
            // launches from a clean slate.
            String mb = shellSync(ctx, "am start --display 0 -n " + st.targetActivity + " 2>&1");
            dt.append("\n$ am start --display 0 -n ").append(st.targetActivity).append('\n')
              .append(mb == null ? "<no output>" : mb).append('\n');
            try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            String fs = shellSync(ctx, "am force-stop " + st.targetPkg + " 2>&1 ; echo __done__");
            dt.append("\n$ am force-stop ").append(st.targetPkg).append('\n')
              .append(fs == null ? "<no output>" : fs).append('\n');
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            r.detail = dt.toString();
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "user said NO — display " + displayId + " is not the cluster";
            // Reset so the next attempt can run its own launch.
            st.lastLaunchedDisplay = -1;
        }
    }

    /**
     * v1.2.49 — robust extraction of the (only) currently-alive Yandex
     * task id. Since we force-stop Yandex before every candidate launch,
     * exactly one Yandex task exists at the time we ask. We therefore no
     * longer need to scope the match by displayId (which depended on a
     * ROM-specific dump layout). We retry a few times with backoff because
     * the task may not be fully registered immediately after the user's
     * YES tap. The raw filtered dumpsys output is returned via the
     * {@code debugOut} sink for the caller to attach to its report.
     */
    private static int extractYandexTaskId(Context ctx, String targetPkg, StringBuilder debugOut) {
        final int[] delaysMs = { 0, 250, 500, 1000 };
        String escaped = java.util.regex.Pattern.quote(targetPkg);
        // Use a simple, shell-safe filter token (last 2 dot-segments) for the
        // first grep; the precise regex match happens client-side via pTask/pAltId.
        String[] parts = targetPkg.split("\\.");
        String shellTok = parts.length >= 2
                ? parts[parts.length - 2] + "\\." + parts[parts.length - 1]
                : targetPkg;
        java.util.regex.Pattern pTask =
                java.util.regex.Pattern.compile("Task\\{[^}]*" + escaped + "[^}]*\\}");
        java.util.regex.Pattern pId = java.util.regex.Pattern.compile("#(\\d+)");
        java.util.regex.Pattern pAltId =
                java.util.regex.Pattern.compile("taskId=(\\d+)[^\\n]*" + escaped);
        for (int attempt = 0; attempt < delaysMs.length; attempt++) {
            if (delaysMs[attempt] > 0) {
                try { Thread.sleep(delaysMs[attempt]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            // Filter dumpsys down to the few lines that mention the target pkg
            // so the debug capture stays small.
            String out = shellSync(ctx,
                    "dumpsys activity activities 2>&1 | grep -nE 'Task\\{|taskId=|" + shellTok
                            + "' | grep -E '" + shellTok + "' | head -20");
            if (debugOut != null) {
                debugOut.append("attempt #").append(attempt + 1)
                        .append(" (after ").append(delaysMs[attempt]).append(" ms):\n");
                debugOut.append(out == null || out.isEmpty()
                        ? "  <no " + targetPkg + " lines>\n" : out + "\n");
            }
            if (out == null || out.isEmpty()) continue;
            // First try the Task{... yandexmaps ...} pattern.
            java.util.regex.Matcher mt = pTask.matcher(out);
            if (mt.find()) {
                java.util.regex.Matcher mid = pId.matcher(mt.group());
                if (mid.find()) {
                    try { return Integer.parseInt(mid.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            // Fallback: taskId=NNN ... <targetPkg> on the same line.
            java.util.regex.Matcher ma = pAltId.matcher(out);
            if (ma.find()) {
                try { return Integer.parseInt(ma.group(1)); }
                catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    /**
     * v1.2.48 — F10. Set windowing mode = freeform (5) on the Yandex task
     * on the confirmed cluster display. Required because fission displays
     * declare mDisplayWindowingMode=fullscreen, so tasks land in fullscreen
     * even when launched with --windowingMode 5; in fullscreen mode the
     * later `cmd activity task resize` is silently a no-op.
     */
    /**
     * v1.2.72 — F10. Capture taskId / stackId / current bounds / windowing
     * mode + a compact verb inventory. Pure read-only. Pre-flight for F11.
     */
    private static void fissionCaptureTopology(Context ctx, DiLink5TestRunner.TestResult r,
                                               FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.confirmedClusterDisplay <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no cluster display confirmed by user"; return;
        }
        // (Re-)extract taskId if not already known.
        if (st.yandexTaskIdOnCluster <= 0) {
            StringBuilder dbg = new StringBuilder();
            st.yandexTaskIdOnCluster = extractYandexTaskId(ctx, st.targetPkg, dbg);
        }
        StringBuilder dt = new StringBuilder();
        if (st.yandexTaskIdOnCluster <= 0) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no taskId extracted for display " + st.confirmedClusterDisplay;
            r.detail = "extractYandexTaskId returned -1 \u2014 F11 will SKIP";
            return;
        }
        int taskId = st.yandexTaskIdOnCluster;
        // Single shell roundtrip: bounds + stack/rootTask hints + verb help.
        String cmd =
            "echo '===== task bounds + stack containment =====' ; "
              + "dumpsys activity activities 2>&1 | grep -B2 -A 40 -E '#" + taskId + "[^0-9]' "
              + "| grep -E 'mBounds=Rect|mWindowingMode=|mAppBounds=|StackId=|stackId=|Stack #|in stack|rootTaskId=|rootOfTask=|Task\\{' "
              + "| head -35 ; "
              + "echo ; echo '===== focused root-task markers for this task =====' ; "
              + "dumpsys activity activities 2>&1 | grep -E 'mPreferredTopFocusableRootTask|mLastFocusedRootTask|rootOfTask=true' "
              + "| grep -E '#" + taskId + "[^0-9]' | head -12 ; "
              + "echo ; echo '===== cmd activity stack help =====' ; "
              + "cmd activity stack help 2>&1 | head -30 ; echo __exit_a_stack=$? ; "
              + "echo ; echo '===== cmd activity task help =====' ; "
              + "cmd activity task help 2>&1 | head -30 ; echo __exit_a_task=$? ; "
              + "echo ; echo '===== am stack help =====' ; "
              + "am stack help 2>&1 | head -20 ; echo __exit_am_stack=$? ; "
              + "echo ; echo '===== wm ?? =====' ; "
              + "wm 2>&1 | head -15 ; echo __exit_wm=$?";
        String out = shellSync(ctx, cmd);
        dt.append("$ topology probe (taskId=").append(taskId)
          .append(", displayId=").append(st.confirmedClusterDisplay).append(")\n\n")
          .append(out == null ? "<no output>" : out);
        // Extract stackId (try several patterns).
        if (out != null) {
            java.util.regex.Matcher m1 = java.util.regex.Pattern
                    .compile("Stack #(\\d+)").matcher(out);
            if (m1.find()) {
                try { st.targetStackId = Integer.parseInt(m1.group(1)); }
                catch (NumberFormatException ignored) {}
            }
            if (st.targetStackId <= 0) {
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("[Ss]tackId=(\\d+)").matcher(out);
                if (m2.find()) {
                    try { st.targetStackId = Integer.parseInt(m2.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (st.targetStackId <= 0) {
                java.util.regex.Matcher m3 = java.util.regex.Pattern
                        .compile("rootTaskId=(\\d+)").matcher(out);
                if (m3.find()) {
                    try { st.targetStackId = Integer.parseInt(m3.group(1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (st.targetStackId <= 0) {
                boolean ownRoot = out.contains("rootOfTask=true")
                        && out.contains("#" + taskId + " ");
                if (!ownRoot) {
                    ownRoot = out.contains("mPreferredTopFocusableRootTask")
                            && out.contains("#" + taskId + " ");
                }
                if (ownRoot) {
                    st.targetStackId = taskId;
                    dt.append("\n[v1.3.3] stackId fallback = taskId (")
                      .append(taskId)
                      .append(") because task is its own root task (Android 12+).\n");
                }
            }
        }
        // Verb inventory flags.
        boolean hasStackResize = out != null
                && (out.contains("stack resize")
                 || out.contains("resize-stack")
                 || out.contains("resizeStack")
                 || out.contains("Argument expected after \"stack\""));
        boolean hasTaskResize  = out != null && out.contains("task resize");
        if (!hasTaskResize && out != null && out.contains("Argument expected after \"task\"")) {
            hasTaskResize = true;
        }
        boolean hasResizeable  = out != null && out.contains("resizeable");

        StringBuilder msg = new StringBuilder();
        msg.append("taskId=").append(taskId);
        msg.append(", stackId=").append(st.targetStackId > 0 ? st.targetStackId : "?");
        msg.append(", verbs: stack-resize=").append(hasStackResize ? "Y" : "N");
        msg.append(", task-resize=").append(hasTaskResize ? "Y" : "N");
        msg.append(", resizeable=").append(hasResizeable ? "Y" : "N");
        r.message = msg.toString();
        r.detail = dt.toString();
        r.status = (st.targetStackId > 0) ? DiLink5TestRunner.Status.PASS
                                          : DiLink5TestRunner.Status.WARN;
    }

    /**
     * v1.2.72 — F11. DL3 chain reproduced via shell. Runs every known resize
     * verb in sequence inside ONE shell roundtrip, captures each verb's exit
     * code + a dumpsys snapshot, then reports which (if any) actually changed
     * the task's mBounds. The novel insight tested here vs all prior probes:
     * resize the STACK (not the task), via {@code am stack resize} /
     * {@code cmd activity stack resize}. The shell verb for
     * {@code IActivityTaskManager.resizeStack(stackId, Rect)} has never been
     * tried on DL5 before.
     */
    private static void fissionDl3ChainViaShell(Context ctx, DiLink5TestRunner.TestResult r,
                                                FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.yandexTaskIdOnCluster <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no taskId (F10)"; return;
        }
                int taskId  = st.yandexTaskIdOnCluster;
                int stackId = st.targetStackId > 0 ? st.targetStackId : taskId;
                boolean stackIdIsFallback = (st.targetStackId <= 0);
        String rect = "100 80 1820 640";
                String stackBlock =
                                "echo ; echo '--- step 2a: am stack resize ' " + stackId + " ' " + rect
                            + (stackIdIsFallback ? " (fallback id=taskId) ---' ; " : " ---' ; ")
                            + "am stack resize " + stackId + " " + rect + " 2>&1 ; echo __exit_am_stack_resize=$? ; "
                            + "sleep 1 ; "
                            + "echo ; echo '--- step 2b: cmd activity stack resize ' " + stackId + " ' " + rect + " ---' ; "
                            + "cmd activity stack resize " + stackId + " " + rect + " 2>&1 ; echo __exit_a_stack_resize=$? ; "
                            + "sleep 1 ; "
                            + "echo ; echo '--- step 2c: cmd window stack resize ' " + stackId + " ' " + rect + " ---' ; "
                            + "cmd window stack resize " + stackId + " " + rect + " 2>&1 ; echo __exit_w_stack_resize=$? ; "
                            + "sleep 1 ; ";
        String cmd =
                "echo '--- step 1: cmd activity task resizeable ' " + taskId + " ' 2 ---' ; "
              + "cmd activity task resizeable " + taskId + " 2 2>&1 ; echo __exit_resizeable=$? ; "
              + "sleep 1 ; "
              + stackBlock
              + "echo ; echo '--- step 3: cmd activity task resize ' " + taskId + " ' " + rect + " ---' ; "
              + "cmd activity task resize " + taskId + " " + rect + " 2>&1 ; echo __exit_task_resize=$? ; "
              + "sleep 2 ; "
              + "echo ; echo '--- step 4: dumpsys bounds for task ' " + taskId + " ' ---' ; "
              + "dumpsys activity activities 2>&1 | grep -A 30 -E '#" + taskId + "[^0-9]' "
              + "| grep -E 'mBounds=Rect|mWindowingMode=|mAppBounds=' | head -20";
        String out = shellSync(ctx, cmd);
        r.detail = "$ DL3 chain via shell (taskId=" + taskId
            + ", stackId=" + stackId + (stackIdIsFallback ? " [fallback=taskId]" : "") + ")\n\n"
                + (out == null ? "<no output>" : out);
        if (out == null) {
            r.status = DiLink5TestRunner.Status.FAIL;
            r.message = "shell unavailable"; return;
        }
        boolean newBounds = false;
        for (String line : out.split("\n")) {
            if (!line.contains("mBounds=Rect")) continue;
            if ((line.contains("(100, 80") || line.contains("(100,80"))
                    && line.contains("1820")) { newBounds = true; break; }
        }
        // Identify the winning verb (best-effort) by scanning which step
        // exited 0 AND was followed by a successful bounds mutation. We
        // can't perfectly disambiguate inside a single roundtrip, but we
        // can report which verbs were even accepted (exit=0 without
        // "Unknown command").
        String lo = out.toLowerCase(Locale.ROOT);
        StringBuilder accepted = new StringBuilder();
        if (out.contains("__exit_resizeable=0")    && !lo.contains("unknown command\nexit_resizeable")) accepted.append("resizeable, ");
        if (out.contains("__exit_am_stack_resize=0"))       accepted.append("am-stack-resize, ");
        if (out.contains("__exit_a_stack_resize=0"))        accepted.append("cmd-activity-stack-resize, ");
        if (out.contains("__exit_w_stack_resize=0"))        accepted.append("cmd-window-stack-resize, ");
        if (out.contains("__exit_task_resize=0"))           accepted.append("task-resize, ");
        String acceptedStr = accepted.length() > 0
                ? accepted.substring(0, accepted.length() - 2) : "<none>";
        st.winningVerb = newBounds ? acceptedStr : "";
        if (newBounds) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "BREAKTHROUGH \u2014 bounds mutated; accepted verbs: " + acceptedStr;
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no verb mutated bounds; accepted verbs: " + acceptedStr;
        }
    }

    /**
     * v1.2.72 — F12. Re-dump bounds for the target task and report whether
     * the rect attempted by F11 stuck. Mostly a clean human-readable summary
     * row in the report (the diagnostic data already lives in F11.detail).
     */
    private static void fissionVerifyFinalBounds(Context ctx, DiLink5TestRunner.TestResult r,
                                                 FissionState st) {
        if (st.abortFromHere) { r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "aborted earlier"; return; }
        if (st.yandexTaskIdOnCluster <= 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no taskId from F10"; return;
        }
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String dump = shellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -v mGlobalConfig"
              + " | grep -A 25 -E '#" + st.yandexTaskIdOnCluster + "[^0-9]'"
              + " | grep -E 'mBounds=Rect|mWindowingMode=|mAppBounds=' | head -20");
        r.detail = "$ dumpsys activity activities (taskId=" + st.yandexTaskIdOnCluster + ", -A 25 scope)\n\n"
                + (dump == null ? "<no output>" : dump);
        if (dump == null) { r.status = DiLink5TestRunner.Status.FAIL; r.message = "dumpsys failed"; return; }
        boolean hit = false;
        for (String line : dump.split("\n")) {
            if (!line.contains("mBounds=Rect")) continue;
            if ((line.contains("(100, 80") || line.contains("(100,80"))
                    && line.contains("1820")) { hit = true; break; }
        }
        if (hit) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "new bounds visible"
                    + (st.winningVerb.isEmpty() ? "" : " (winner: " + st.winningVerb + ")");
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "bounds not confirmed \u2014 see F11 detail";
        }
    }

    // ─── v1.2.72 — DL5 resize battery refocused ───
    // Removed in v1.2.72 (proven dead by field reports):
    //   F12A fissionProbeVerbInventory  → folded into F10 topology probe
    //   F12B fissionProbeDisplayFreeform → set-display-windowing-mode stripped
    //   F12C fissionProbeLaunchTimeBounds → --activity-launch-bounds unknown option
    //   F12E fissionProbeTaskResizeableShell → folded into F11 step 1

    /** v1.2.48 — F13. Move Yandex back to display 0. No size mutation on id=0. */
    private static void fissionMoveBackToMain(Context ctx, DiLink5TestRunner.TestResult r,
                                              FissionState st) {
        if (st.targetActivity == null) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no resolved launcher (F02)";
            return;
        }
        String cmd = "am start --display 0 -n " + st.targetActivity + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        String lo = out == null ? "" : out.toLowerCase(Locale.ROOT);
        if (lo.contains("unable to resolve")) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "unable to resolve intent";
        } else if (out != null && (out.contains("Starting:") || out.contains("Status: ok"))
                && !lo.contains("error")) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "move-to-display-0 issued";
        } else if (lo.contains("error")) {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "move error — see detail";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "ambiguous move output";
        }
    }

    private static void fissionResetOverride(Context ctx, DiLink5TestRunner.TestResult r,
                                             FissionState st) {
        if (!st.overrideEnabled) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "no override to reset";
            return;
        }
        String cmd = "am compat reset " + FORCE_RESIZE_APP_ID + " " + st.targetPkg + " 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = "reset sent";
    }

    /** v1.2.48 — F16. Close the cluster projection if F01 opened it. */
    private static void fissionCloseProjection(Context ctx, DiLink5TestRunner.TestResult r,
                                               FissionState st) {
        if (!st.projectionOpenedByUs) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "projection not opened by this run";
            return;
        }
        String svc = com.byd.dashcast.infrastructure.AdbLocalClient.autoContainerSvcName(ctx);
        String cmd =
                "service call " + svc + " 2 i32 1000 i32 18 s16 \"\" 2>&1 ; "
              + "sleep 1 ; "
              + "service call " + svc + " 2 i32 1000 i32 0 s16 \"\" 2>&1";
        String out = shellSync(ctx, cmd);
        r.detail = "$ " + cmd + "\n\n" + (out == null ? "<no output>" : out);
        r.status = DiLink5TestRunner.Status.PASS;
        r.message = "close + restore sent (sendInfo 18 + 0)";
    }

    /** Render a fission-tier text report. */
    public static String renderFissionReport(List<DiLink5TestRunner.TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════\n");
        sb.append("DashCast — DL5 Fission Live Tests\n");
        sb.append("════════════════════════════════════\n");
        sb.append("Build.MODEL=").append(Build.MODEL).append('\n');
        sb.append("Build.FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        sb.append("Target=").append(sLastFissionTarget).append('\n');
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
