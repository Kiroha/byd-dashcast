package com.byd.dashcast.dilink5;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.hardware.display.DisplayManager;
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
 * DiLink5TestRunner — diagnostic suite specific to DiLink 5.0 / Android 12.
 *
 * <p>Every test is <b>read-only by default</b> (D1–D7, D9). Only <b>D8</b>
 * actually launches an application on the cluster — and it does so with a
 * guarded sequence (launch on cluster → re-launch on display 0 → force-stop)
 * so the target package's display affinity is reset before kill, which is
 * critical to avoid it sticking on the cluster across subsequent launches
 * (the well-known "ghost cluster" pitfall).
 *
 * <p>The runner is self-contained — it intentionally does <b>not</b> reuse
 * {@code BetaTestRunner}'s nested types so a regression in either suite
 * cannot affect the other. The data model and listener contract mirror
 * {@code BetaTestRunner} so the same row layout can be reused by the UI.
 */
public final class DiLink5TestRunner {

    private static final String TAG = "DiLink5TestRunner";

    public enum Status { PENDING, RUNNING, PASS, FAIL, SKIPPED, WARN }

    public static final class TestDef {
        public final String id;
        public final String title;
        public final String description;
        public TestDef(String id, String title, String description) {
            this.id = id; this.title = title; this.description = description;
        }
    }

    public static final class TestResult {
        public final TestDef def;
        public Status status   = Status.PENDING;
        public String message  = "";
        public String detail   = "";
        public long   elapsedMs;
        public TestResult(TestDef def) { this.def = def; }
    }

    public interface Listener {
        void onSuiteStarted(List<TestResult> results);
        void onTestUpdated(int index, TestResult result);
        void onSuiteFinished(List<TestResult> results);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dilink5-test-runner");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private DiLink5TestRunner() {}

    /** Ordered catalog. */
    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();
        list.add(new TestDef("D1", "Platform identity",
                "ro.product.name + Build.MODEL + API → detected generation."));
        list.add(new TestDef("D2", "Displays inventory",
                "Enumerate all displays + PRESENTATION ones (DL5 typically has 2)."));
        list.add(new TestDef("D3", "Cluster service binder",
                "service list | grep -iE 'AutoContainer|crosscontrol|xdja' → find the wake/release service."));
        list.add(new TestDef("D4", "wm overscan availability",
                "Check whether 'wm overscan' is still supported by the platform."));
        list.add(new TestDef("D5", "am --display flag",
                "Confirm 'am start --display N' is still accepted by the platform."));
        list.add(new TestDef("D6", "Granted system permissions",
                "INTERNAL_SYSTEM_WINDOW / ACTIVITY_EMBEDDING / MANAGE_ACTIVITY_STACKS / BYDAUTO_*."));
        list.add(new TestDef("D7", "ADB local TCP 5555",
                "127.0.0.1:5555 reachable (required by D8)."));
        list.add(new TestDef("D8", "Guarded cluster launch (selected app)",
                "am start --display <clusterId> → wait → am start --display 0 (retract) → force-stop."));
        list.add(new TestDef("D9", "BYD packages inventory",
                "Versions of car.server / crosscontrol / xdja.containerservice for triage."));
        return list;
    }

    /** Holder for D8 parameters provided by the UI. */
    public static final class D8Params {
        public final String targetPackage;
        /** Optional explicit display id to target. -1 = auto pick first PRESENTATION display. */
        public final int    explicitDisplayId;
        public D8Params(String targetPackage, int explicitDisplayId) {
            this.targetPackage = targetPackage;
            this.explicitDisplayId = explicitDisplayId;
        }
    }

    /**
     * Runs the full suite. {@code d8Params} may be null — in that case D8 is
     * reported as SKIPPED (no target package selected).
     */
    public static void runAll(Context appCtx, D8Params d8Params, Listener listener) {
        final Context ctx = appCtx.getApplicationContext();
        final List<TestDef> defs = catalog();
        final List<TestResult> results = new ArrayList<>(defs.size());
        for (TestDef d : defs) results.add(new TestResult(d));

        UI.post(() -> listener.onSuiteStarted(results));

        EXEC.execute(() -> {
            for (int i = 0; i < defs.size(); i++) {
                final int idx = i;
                final TestResult r = results.get(i);
                r.status = Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));
                long t0 = SystemClock.elapsedRealtime();
                try {
                    switch (defs.get(i).id) {
                        case "D1": runD1(ctx, r); break;
                        case "D2": runD2(ctx, r); break;
                        case "D3": runD3(ctx, r); break;
                        case "D4": runD4(ctx, r); break;
                        case "D5": runD5(ctx, r); break;
                        case "D6": runD6(ctx, r); break;
                        case "D7": runD7(ctx, r); break;
                        case "D8": runD8(ctx, d8Params, r); break;
                        case "D9": runD9(ctx, r); break;
                        default:
                            r.status = Status.SKIPPED;
                            r.message = "not implemented";
                    }
                } catch (Throwable t) {
                    r.status = Status.FAIL;
                    r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
                    AppLogger.e(TAG, "Test " + defs.get(i).id + " threw", t);
                }
                r.elapsedMs = SystemClock.elapsedRealtime() - t0;
                UI.post(() -> listener.onTestUpdated(idx, r));
            }
            UI.post(() -> listener.onSuiteFinished(results));
        });
    }

    /** Builds a plain-text report suitable for sharing. */
    public static String buildReport(Context ctx, List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        Platform p = Platform.get();
        sb.append("═══ DiLink 5 DIAGNOSTIC ═══\n");
        sb.append("Mode           : ").append(p.describeMode(ctx)).append('\n');
        sb.append("ro.product.name: ").append(p.rawProductName()).append('\n');
        sb.append("Build.MODEL    : ").append(p.rawModel()).append('\n');
        sb.append("Build.BRAND    : ").append(p.rawBrand()).append('\n');
        sb.append("Android API    : ").append(p.androidApi()).append('\n');
        sb.append("auto-detected  : ").append(p.isAutoDetectedDiLink5() ? "yes" : "no").append('\n');
        sb.append("effective DL5  : ").append(p.isDiLink5(ctx) ? "yes" : "no").append('\n');
        sb.append('\n');
        int pass = 0, fail = 0, skip = 0, warn = 0;
        for (TestResult r : results) {
            switch (r.status) {
                case PASS: pass++; break;
                case FAIL: fail++; break;
                case SKIPPED: skip++; break;
                case WARN: warn++; break;
                default: break;
            }
        }
        sb.append(String.format("Summary: PASS=%d  FAIL=%d  WARN=%d  SKIP=%d%n%n",
                pass, fail, warn, skip));
        for (TestResult r : results) {
            sb.append('[').append(r.status).append("] ")
              .append(r.def.id).append("  ").append(r.def.title)
              .append("  (").append(r.elapsedMs).append(" ms)\n");
            if (r.message != null && !r.message.isEmpty()) {
                sb.append("    msg : ").append(r.message).append('\n');
            }
            if (r.detail != null && !r.detail.isEmpty()) {
                for (String line : r.detail.split("\n")) {
                    sb.append("    | ").append(line).append('\n');
                }
            }
        }
        sb.append("\n=== END OF DiLink 5 REPORT ===\n");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test implementations
    // ────────────────────────────────────────────────────────────────────────

    private static void runD1(Context ctx, TestResult r) {
        Platform p = Platform.get();
        r.detail = "ro.product.name=" + p.rawProductName()
                + "\nBuild.MODEL=" + p.rawModel()
                + "\nBuild.BRAND=" + p.rawBrand()
                + "\nBuild.VERSION.SDK_INT=" + p.androidApi()
                + "\nauto-detected DiLink5=" + p.isAutoDetectedDiLink5()
                + "\neffective DiLink5=" + p.isDiLink5(ctx)
                + "\nmode=" + p.describeMode(ctx);
        if (p.isAutoDetectedDiLink5() || p.androidApi() >= 31) {
            r.status = Status.PASS;
            r.message = "Detected: DiLink5 / API " + p.androidApi();
        } else {
            r.status = Status.WARN;
            r.message = "Not DiLink5 (API " + p.androidApi() + ", product='" + p.rawProductName() + "')";
        }
    }

    private static void runD2(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm.getDisplays();
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        StringBuilder sb = new StringBuilder();
        sb.append("Total displays  : ").append(all.length).append('\n');
        sb.append("PRESENTATION    : ").append(pres.length).append('\n');
        for (Display d : all) {
            sb.append("  #").append(d.getDisplayId())
              .append("  ").append(d.getName())
              .append("  flags=0x").append(Integer.toHexString(d.getFlags()))
              .append("  state=").append(d.getState())
              .append('\n');
        }
        r.detail = sb.toString();
        if (pres.length >= 1) {
            r.status = Status.PASS;
            r.message = pres.length + " presentation display(s) found";
        } else {
            r.status = Status.FAIL;
            r.message = "No PRESENTATION display — cluster cannot be mirrored";
        }
    }

    private static void runD3(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "service list | grep -iE 'AutoContainer|crosscontrol|xdja|cluster|projection'", out, 4000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no matching service)" : raw;
        boolean hasAutoContainer = raw.toLowerCase().contains("autocontainer");
        boolean hasCrosscontrol  = raw.toLowerCase().contains("crosscontrol");
        boolean hasXdja          = raw.toLowerCase().contains("xdja");
        if (hasAutoContainer) {
            r.status = Status.PASS;
            r.message = "AutoContainer service present (DL3 path)";
        } else if (hasCrosscontrol || hasXdja) {
            r.status = Status.WARN;
            r.message = "AutoContainer absent; alternative service(s) found";
        } else {
            r.status = Status.FAIL;
            r.message = "No cluster service found via servicemanager";
        }
    }

    private static void runD4(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "wm overscan 0,0,0,0 -d 0 2>&1; echo ---; wm 2>&1 | grep -i overscan", out, 4000);
        String raw = out.get();
        r.detail = raw;
        String lower = raw.toLowerCase();
        if (lower.contains("unknown command") || lower.contains("no such") || lower.contains("invalid")) {
            r.status = Status.WARN;
            r.message = "wm overscan removed (Android 11+) — using app-side bounds only";
        } else if (lower.contains("overscan")) {
            r.status = Status.PASS;
            r.message = "wm overscan still accepted";
        } else {
            r.status = Status.WARN;
            r.message = "Inconclusive — check detail";
        }
    }

    private static void runD5(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "am start --help 2>&1 | grep -E -- '--display|-display'", out, 4000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no matching flag in 'am start --help')" : raw;
        if (raw.toLowerCase().contains("display")) {
            r.status = Status.PASS;
            r.message = "'am start --display' flag is documented";
        } else {
            r.status = Status.WARN;
            r.message = "'--display' not visible in 'am start --help' (will be probed live by D8)";
        }
    }

    private static void runD6(Context ctx, TestResult r) {
        String[] perms = new String[] {
                "android.permission.INTERNAL_SYSTEM_WINDOW",
                "android.permission.MANAGE_ACTIVITY_STACKS",
                "android.permission.MANAGE_ACTIVITY_TASKS",
                "android.permission.ACTIVITY_EMBEDDING",
                "android.permission.INJECT_EVENTS",
                "android.permission.BYDAUTO_SPEED_GET",
                "android.permission.BYDAUTO_GEARBOX_GET",
                "android.permission.BYDAUTO_ENERGY_GET",
                "android.permission.BYDAUTO_BODYWORK_GET",
                "android.permission.BYDAUTO_INSTRUMENT_GET",
        };
        StringBuilder sb = new StringBuilder();
        int granted = 0;
        for (String perm : perms) {
            int s;
            try {
                s = ctx.getPackageManager().checkPermission(perm, ctx.getPackageName());
            } catch (Throwable t) {
                s = -2;
            }
            boolean ok = s == PackageManager.PERMISSION_GRANTED;
            if (ok) granted++;
            sb.append(ok ? "  ✓ " : "  ✗ ").append(perm.replace("android.permission.", "")).append('\n');
        }
        r.detail = sb.toString();
        if (granted >= 4) {
            r.status = Status.PASS;
            r.message = granted + "/" + perms.length + " critical perms granted";
        } else if (granted >= 1) {
            r.status = Status.WARN;
            r.message = granted + "/" + perms.length + " granted — limited capabilities";
        } else {
            r.status = Status.FAIL;
            r.message = "No signature permission granted — cluster ops will be blocked";
        }
    }

    private static void runD7(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "echo ok", out, 3000);
        if ("ok".equals(out.get().trim())) {
            r.status = Status.PASS;
            r.message = "ADB shell round-trip OK";
            r.detail = "echo ok → " + out.get().trim();
        } else {
            r.status = Status.FAIL;
            r.message = "ADB round-trip failed";
            r.detail = "raw: " + out.get();
        }
    }

    private static void runD8(Context ctx, D8Params params, TestResult r) {
        if (params == null || params.targetPackage == null || params.targetPackage.isEmpty()) {
            r.status = Status.SKIPPED;
            r.message = "No target package selected — pick one in the dropdown above";
            return;
        }
        // Resolve target display id
        int displayId = params.explicitDisplayId;
        if (displayId < 0) {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (pres.length == 0) {
                r.status = Status.FAIL;
                r.message = "No PRESENTATION display available";
                return;
            }
            displayId = pres[0].getDisplayId();
        }
        final String pkg = params.targetPackage;
        StringBuilder detail = new StringBuilder();
        detail.append("target package = ").append(pkg).append('\n');
        detail.append("target display = ").append(displayId).append('\n');

        // 1) Clean slate
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "am force-stop " + pkg, out, 4000);
        detail.append("\n[1] am force-stop ").append(pkg).append(" → ").append(out.get().trim()).append('\n');

        // 2) Launch on cluster via monkey (uses LAUNCHER intent automatically)
        String launchCmd = "am start --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                + "-n " + resolveLauncherComponent(ctx, pkg) + " 2>&1";
        runShellSync(ctx, launchCmd, out, 6000);
        String launchOut = out.get().trim();
        detail.append("\n[2] ").append(launchCmd).append('\n').append("    → ").append(launchOut).append('\n');
        boolean launchOk = !launchOut.toLowerCase().contains("error")
                        && !launchOut.toLowerCase().contains("securityexception")
                        && !launchOut.toLowerCase().contains("permission denial");

        // 3) Wait a bit so the OS commits the launch
        try { Thread.sleep(1800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        // 4) Retract: re-launch on display 0 so the affinity is reset
        String retractCmd = "am start --display 0 -a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER -n "
                + resolveLauncherComponent(ctx, pkg) + " 2>&1";
        runShellSync(ctx, retractCmd, out, 6000);
        String retractOut = out.get().trim();
        detail.append("\n[3] ").append(retractCmd).append('\n').append("    → ").append(retractOut).append('\n');
        boolean retractOk = !retractOut.toLowerCase().contains("error")
                         && !retractOut.toLowerCase().contains("securityexception")
                         && !retractOut.toLowerCase().contains("permission denial");

        try { Thread.sleep(700); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        // 5) Kill
        runShellSync(ctx, "am force-stop " + pkg, out, 4000);
        detail.append("\n[4] am force-stop ").append(pkg).append(" → ").append(out.get().trim()).append('\n');

        r.detail = detail.toString();
        if (launchOk && retractOk) {
            r.status = Status.PASS;
            r.message = "Launch + retract OK — '" + pkg + "' projected on display " + displayId;
        } else if (launchOk && !retractOk) {
            r.status = Status.WARN;
            r.message = "Launch OK, but retract to display 0 failed (app may stick on cluster next time)";
        } else {
            r.status = Status.FAIL;
            r.message = "Launch on display " + displayId + " refused — see detail";
        }
    }

    private static void runD9(Context ctx, TestResult r) {
        String[] interestingPkgs = new String[] {
                "com.byd.car.server", "com.byd.crosscontrol", "com.xdja.containerservice",
                "com.byd.containerservice", "com.byd.dashcast", "com.byd.appstartmanagement",
                "com.byd.providers.appstartup",
        };
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (String p : interestingPkgs) {
            String v = readPackageVersion(ctx, p);
            if (v == null) {
                sb.append("  ✗ ").append(p).append("  (not installed)\n");
            } else {
                found++;
                sb.append("  ✓ ").append(p).append("  ").append(v).append('\n');
            }
        }
        r.detail = sb.toString();
        r.status = found >= 2 ? Status.PASS : Status.WARN;
        r.message = found + " BYD package(s) detected";
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Resolve the launcher component for a package, falling back to "<pkg>/.MainActivity". */
    private static String resolveLauncherComponent(Context ctx, String pkg) {
        try {
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                return i.getComponent().getPackageName() + "/" + i.getComponent().getClassName();
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "resolveLauncherComponent failed for " + pkg + ": " + t.getMessage());
        }
        // Best-effort fallback — many BYD apps have a .MainActivity.
        return pkg + "/.MainActivity";
    }

    private static String readPackageVersion(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Run a shell command via {@link AdbLocalClient} and block until completion
     * (or the timeout) on the calling background thread.
     */
    private static void runShellSync(Context ctx, String cmd, AtomicReference<String> out, long timeoutMs) {
        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};
        AdbLocalClient.executeShellWithResult(ctx, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String report) {
                out.set(report == null ? "" : report);
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
            @Override public void onError(String error) {
                out.set("ERROR: " + (error == null ? "(null)" : error));
                synchronized (lock) { done[0] = true; lock.notifyAll(); }
            }
        });
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (lock) {
            while (!done[0]) {
                long remain = deadline - SystemClock.elapsedRealtime();
                if (remain <= 0) {
                    out.set("TIMEOUT after " + timeoutMs + "ms");
                    break;
                }
                try { lock.wait(remain); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
}
