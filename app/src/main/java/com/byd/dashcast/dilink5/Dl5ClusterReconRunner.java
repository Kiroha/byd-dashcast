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
        list.add(new DiLink5TestRunner.TestDef("R13", "am compat --help",
                "Confirms the compat framework shell surface is present on this ROM."));
        list.add(new DiLink5TestRunner.TestDef("R14", "am compat list (first 60)",
                "All compat changes visible from shell — search for FORCE_RESIZE_APP / OVERRIDE_MIN_ASPECT_RATIO / NEVER_SANDBOX_DISPLAY_APIS."));
        list.add(new DiLink5TestRunner.TestDef("R15", "am compat get FORCE_RESIZE_APP self",
                "By-name lookup against our own package — confirms the symbolic name resolves."));
        list.add(new DiLink5TestRunner.TestDef("R16", "am compat get 174042936 self",
                "Numeric ID lookup (more robust on ROMs that strip change names)."));
        list.add(new DiLink5TestRunner.TestDef("R17", "am compat get NEVER_SANDBOX_DISPLAY_APIS self",
                "Related: prevents the display sandbox for non-resizable apps."));
        list.add(new DiLink5TestRunner.TestDef("R18", "am compat get OVERRIDE_MIN_ASPECT_RATIO self",
                "Related: aspect-ratio compat shim, relevant for cluster geometry."));

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
                "dumpsys activity activities | grep -E 'Stack|RootTask|displayId|mWindowingMode' | head -30."));

        // ── Window manager geometry per display ──────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R24", "wm size + wm density (default)",
                "Baseline geometry of the head unit display."));
        list.add(new DiLink5TestRunner.TestDef("R25", "wm size -d <clusterId>",
                "Per-display geometry of the cluster display, if found in R02."));

        // ── Per-app probing (cluster targets) ────────────────────────────
        list.add(new DiLink5TestRunner.TestDef("R26", "Yandex Maps probe",
                "pm dump ru.yandex.yandexmaps | grep -E 'resizeable|maxAspectRatio|launchMode' — only if installed."));
        list.add(new DiLink5TestRunner.TestDef("R27", "Google Maps probe",
                "pm dump com.google.android.apps.maps | grep -E 'resizeable|maxAspectRatio|launchMode'."));
        list.add(new DiLink5TestRunner.TestDef("R28", "Waze probe",
                "pm dump com.waze | grep -E 'resizeable|maxAspectRatio|launchMode'."));

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
            case "R01": probePlatform(r); return;
            case "R02": probeDisplays(ctx, r); return;
            case "R03": shellCapture(ctx, r, "id -u && id -un && uname -a", "uid=2000", "shell"); return;
            case "R04": shellCapture(ctx, r, "getenforce ; id -Z", "Enforcing", null); return;
            case "R05": probeClusterApps(ctx, r); return;
            case "R06": shellCapture(ctx, r, "am 2>&1 | head -80", null, null); return;
            case "R07": shellCapture(ctx, r, "am start 2>&1 | head -120", "--display", "--windowingMode"); return;
            case "R08": shellCapture(ctx, r, "cmd activity 2>&1 | head -80", null, null); return;
            case "R09": shellCapture(ctx, r, "cmd activity task 2>&1 | head -60", "resize", null); return;
            case "R10": shellCapture(ctx, r, "wm 2>&1 | head -60", "size", "density"); return;
            case "R11": shellCapture(ctx, r, "cmd window 2>&1 | head -60", null, null); return;
            case "R12": shellCapture(ctx, r, "cmd display 2>&1 | head -40", null, null); return;
            case "R13": shellCapture(ctx, r, "am compat 2>&1 | head -40", "enable", "disable"); return;
            case "R14": shellCapture(ctx, r, "am compat list 2>&1 | head -60", "FORCE_RESIZE_APP", null); return;
            case "R15": shellCapture(ctx, r, "am compat get FORCE_RESIZE_APP " + ctx.getPackageName() + " 2>&1", null, null); return;
            case "R16": shellCapture(ctx, r, "am compat get 174042936 " + ctx.getPackageName() + " 2>&1", null, null); return;
            case "R17": shellCapture(ctx, r, "am compat get NEVER_SANDBOX_DISPLAY_APIS " + ctx.getPackageName() + " 2>&1", null, null); return;
            case "R18": shellCapture(ctx, r, "am compat get OVERRIDE_MIN_ASPECT_RATIO " + ctx.getPackageName() + " 2>&1", null, null); return;
            case "R19": shellCapture(ctx, r, "settings get global force_resizable_activities 2>&1", null, null); return;
            case "R20": shellCapture(ctx, r,
                    "settings list global 2>&1 | grep -E 'resizable|freeform|multi|sandbox|ignore_orientation' | head -30", null, null); return;
            case "R21": shellCapture(ctx, r,
                    "dumpsys window 2>&1 | grep -E 'freeform|multi_window|resizable|orientation' | head -40", null, null); return;
            case "R22": shellCapture(ctx, r,
                    "dumpsys display 2>&1 | grep -E 'Display Id|UniqueId|Owner|Name|fission|xdja|cluster|PRESENTATION' | head -40", null, null); return;
            case "R23": shellCapture(ctx, r,
                    "dumpsys activity activities 2>&1 | grep -E 'Stack|RootTask|displayId|mWindowingMode' | head -30", null, null); return;
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

    private static void probePlatform(DiLink5TestRunner.TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build.MODEL=").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT=").append(Build.PRODUCT).append('\n');
        sb.append("Build.FINGERPRINT=").append(Build.FINGERPRINT).append('\n');
        sb.append("SDK_INT=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Platform.detected=").append(Platform.get().describeMode(null));
        r.detail = sb.toString();
        boolean dl5 = Platform.get().isAutoDetectedDiLink5();
        r.status  = dl5 ? DiLink5TestRunner.Status.PASS : DiLink5TestRunner.Status.WARN;
        r.message = dl5 ? "DL5 (API " + Build.VERSION.SDK_INT + ")" :
                          "non-DL5 (API " + Build.VERSION.SDK_INT + ")";
    }

    private static void probeDisplays(Context ctx, DiLink5TestRunner.TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) { r.status = DiLink5TestRunner.Status.FAIL; r.message = "no DisplayManager"; return; }
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        int cluster = -1;
        for (Display d : all) {
            sb.append("id=").append(d.getDisplayId())
              .append(" name=").append(d.getName())
              .append(" state=").append(d.getState())
              .append(" flags=").append(Integer.toHexString(d.getFlags()))
              .append('\n');
            String name = d.getName() == null ? "" : d.getName().toLowerCase();
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                    && (name.contains("fission") || name.contains("xdja")
                        || name.contains("cluster") || name.contains("virtual"))) {
                cluster = d.getDisplayId();
            }
        }
        r.detail = sb.toString().trim();
        if (cluster >= 0) {
            r.status = DiLink5TestRunner.Status.PASS;
            r.message = "cluster displayId=" + cluster + " (" + all.length + " total)";
        } else {
            r.status = DiLink5TestRunner.Status.WARN;
            r.message = "no cluster display found among " + all.length;
        }
    }

    private static void probeClusterApps(Context ctx, DiLink5TestRunner.TestResult r) {
        StringBuilder cmd = new StringBuilder("for p in");
        for (String p : CLUSTER_APPS) cmd.append(' ').append(p);
        cmd.append("; do v=$(pm list packages \"$p\" 2>/dev/null); ")
           .append("if [ -n \"$v\" ]; then echo \"INSTALLED $p\"; else echo \"MISSING   $p\"; fi; done");
        shellCapture(ctx, r, cmd.toString(), "INSTALLED", null);
    }

    private static void probeClusterDisplayGeometry(Context ctx, DiLink5TestRunner.TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        int cluster = -1;
        if (dm != null) {
            for (Display d : dm.getDisplays()) {
                String name = d.getName() == null ? "" : d.getName().toLowerCase();
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                        && (name.contains("fission") || name.contains("xdja")
                            || name.contains("cluster") || name.contains("virtual"))) {
                    cluster = d.getDisplayId(); break;
                }
            }
        }
        if (cluster < 0) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = "cluster display not detected (R02)";
            return;
        }
        shellCapture(ctx, r,
                "wm size -d " + cluster + " 2>&1 ; wm density -d " + cluster + " 2>&1", null, null);
    }

    private static void probePerApp(Context ctx, DiLink5TestRunner.TestResult r, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Exception e) {
            r.status = DiLink5TestRunner.Status.SKIPPED;
            r.message = pkg + " not installed";
            return;
        }
        shellCapture(ctx, r,
                "pm dump " + pkg + " 2>&1 | grep -E 'resizeable|maxAspectRatio|launchMode|targetSdk' | head -10",
                null, null);
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
}
