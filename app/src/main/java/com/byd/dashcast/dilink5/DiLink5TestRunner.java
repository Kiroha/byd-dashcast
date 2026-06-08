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
        list.add(new TestDef("D9", "BYD/XDJA/DiLink packages discovery",
                "Dynamic scan via 'pm list packages -f' — every com.byd.*, com.xdja.*, com.dilink.* and *cluster* package with versionName + versionCode + APK path."));
        list.add(new TestDef("D10", "Extract RE-relevant APKs",
                "Copies a curated whitelist (clusterdebug, car.server, crosscontrol, containerservice, appstartmanagement, smarttravel, commander, freedom, overdrive, windowmanagement + any *cluster* / *fission* / *projection* / *dilink5*) to /storage/emulated/0/Download/dashcast_apks/ — directly visible in the device file manager. Also wipes the legacy app-private location to avoid filling memory."));
        list.add(new TestDef("D11", "AutoContainer sendInfo refresh probe",
                "service call auto_container 2 i32 1000 i32 0 s16 \"\" — harmless refresh, confirms typed sendInfo path is reachable on DL5."));
        list.add(new TestDef("D12", "AutoContainer Qt standby probe",
                "sendInfo(16) → wait → sendInfo(18) — active Qt standby + close cycle (no projection, just protocol validation)."));
        list.add(new TestDef("D13", "IATM.setTaskWindowingMode reflection probe",
                "Try reflective call from app uid — expected SecurityException on un-signed app; confirms whether the typed DL5 windowing path is accessible."));
        list.add(new TestDef("D14", "Window manager state dump",
                "dumpsys window | grep -E 'Display #|stack|focused|mDisplayId' — real windowing state for cluster app diagnosis."));
        list.add(new TestDef("D15", "appstartmanagement package inspection",
                "dumpsys package com.byd.appstartmanagement (Permission/Receiver/Service/Activity) — understand the DL5 launch gatekeeper."));
        list.add(new TestDef("D16", "containerservice package inspection",
                "dumpsys package com.byd.containerservice (Permission/Receiver/Service) — understand the BYD-rebranded XDJA container service."));
        list.add(new TestDef("D17", "Proxy daemon spawn capability",
                "app_process64 + getuid sanity check from shell — validates that our Beta proxy daemon strategy can spawn on DL5/API 32 under SELinux."));
        list.add(new TestDef("D18", "SELinux context",
                "id + ps -Z (shell + our app) — SELinux domain identification for DL5 diagnostics."));
        list.add(new TestDef("D19", "Full system services inventory",
                "service list | grep -iE 'auto|byd|cluster|fission|display|window|projection|crosscontrol|xdja' — broader than D3."));
        list.add(new TestDef("D20", "IActivityTaskManager service presence",
                "service list | grep -i activity_task — confirms IATM binder available (required by D13 typed path)."));
        list.add(new TestDef("D21", "All displays — full inventory (no PRESENTATION filter)",
                "DisplayManager.getDisplays() + reflective DisplayInfo for every id: flags hex breakdown, ownerUid, ownerPackageName, type, size, density, removeMode — captures non-PRESENTATION displays (e.g. DL5 display #2)."));
        list.add(new TestDef("D22", "Display #2 owner / system-owned displays identification",
                "dumpsys SurfaceFlinger --display-id + dumpsys display + ps -A -o PID,USER,NAME to identify which process owns the non-PRESENTATION displays surfaced by D21/D14."));
        list.add(new TestDef("D23", "All warning lamps visual cycle (sendInfo 2 → 3 s → sendInfo 3)",
                "service call auto_container 2 i32 1000 i32 2 s16 \"\" — allume tous les warning lamps du cluster, attend 3 s, puis envoie sendInfo(3) pour les éteindre. À l’utilisateur de confirmer visuellement si les témoins se sont bien allumés pendant 3 s."));
        list.add(new TestDef("D24", "BYD-specific services probe",
                "service call on magicwindow, crossservice, mirror, BYDMgmt, byd_datacached, IBYDCDRService with interfaceDescriptor (code 0) and code 1 — confirms aliveness + captures interface name."));
        list.add(new TestDef("D25", "IActivityTaskManager methods enumeration",
                "Reflective dump of every public method on IActivityTaskManager.Stub (DL5 / API 32) — find the right signature for windowing-mode / display-attach since D13 setTaskWindowingMode(int,int,boolean) is absent."));
        list.add(new TestDef("D26", "am start --display N probe on each non-main display",
                "For each display in (2, 3, 4): launch a tiny canary (StandardADB or our own NoOp) and check via dumpsys activity activities where it actually landed — reveals which displays accept arbitrary apps without sendInfo prep."));
        list.add(new TestDef("D27", "BYD clusterdebug app launch probe",
                "Resolve com.byd.clusterdebug launcher activity, am start it on display 0, then dump its activity stack + logcat — the official BYD cluster diagnostic app, will tell us what the legitimate projection flow looks like."));
        list.add(new TestDef("D28", "Live logcat capture during sendInfo cycle",
                "logcat -c → sendInfo(16) → wait → logcat -d filtered — captures every BYD log line emitted during a real projection-start attempt on DL5 (no screen-size hint needed); reveals which component rejects/accepts and why."));
        list.add(new TestDef("D29", "Projection-related intent filters discovery",
                "pm query-intent-activities / query-services for actions: PROJECT, CLUSTER_PROJECTION, CAST, AutoDisplay, AppStartup — enumerates every intent BYD apps declare for projection so we can pick the official entry point."));
        list.add(new TestDef("D30", "SurfaceFlinger physical/virtual display topology",
                "dumpsys SurfaceFlinger --display-id + dumpsys SurfaceFlinger | grep -E 'Display|Layer' | head — ground-truth display topology from the compositor (bypasses WindowManager filtering)."));
        list.add(new TestDef("D31", "Live cluster projection end-to-end (sendInfo 16 + am start --display N + sendInfo 18/0)",
                "For each PRESENTATION display: sendInfo(1000,16) opens projection → wait 1.5 s → am start --display N <pkg> → wait 3 s (user observes cluster) → am force-stop → sendInfo(1000,18) closes projection → sendInfo(1000,0) restores normal video flow. Defaults to com.byd.clusterdebug (BYD's own cluster diagnostic app, always present on DL5). If a package is selected in the D8 dropdown, that one is used instead."));
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
                        case "D10": runD10(ctx, r); break;
                        case "D11": runD11(ctx, r); break;
                        case "D12": runD12(ctx, r); break;
                        case "D13": runD13(ctx, r); break;
                        case "D14": runD14(ctx, r); break;
                        case "D15": runD15(ctx, r); break;
                        case "D16": runD16(ctx, r); break;
                        case "D17": runD17(ctx, r); break;
                        case "D18": runD18(ctx, r); break;
                        case "D19": runD19(ctx, r); break;
                        case "D20": runD20(ctx, r); break;
                        case "D21": runD21(ctx, r); break;
                        case "D22": runD22(ctx, r); break;
                        case "D23": runD23(ctx, r); break;
                        case "D24": runD24(ctx, r); break;
                        case "D25": runD25(ctx, r); break;
                        case "D26": runD26(ctx, r); break;
                        case "D27": runD27(ctx, r); break;
                        case "D28": runD28(ctx, r); break;
                        case "D29": runD29(ctx, r); break;
                        case "D30": runD30(ctx, r); break;
                        case "D31": runD31(ctx, d8Params, r); break;
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

    /** Holder used to pass discovery from D9 to D10. */
    private static final class DiscoveredPkg {
        final String pkg;
        final String apkPath;
        final String versionName;
        final long   versionCode;
        DiscoveredPkg(String pkg, String apkPath, String versionName, long versionCode) {
            this.pkg = pkg; this.apkPath = apkPath; this.versionName = versionName; this.versionCode = versionCode;
        }
    }
    private static final List<DiscoveredPkg> sLastDiscovery = new ArrayList<>();

    private static void runD9(Context ctx, TestResult r) {
        // Dynamic scan: every com.byd.*, com.xdja.*, com.dilink.* and *cluster* package.
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "pm list packages -f 2>/dev/null"
                + " | grep -iE 'com\\.byd\\.|com\\.xdja\\.|com\\.dilink\\.|cluster|automotive'"
                + " | sort -u",
                out, 6000);
        String raw = out.get();
        sLastDiscovery.clear();
        StringBuilder sb = new StringBuilder();
        if (raw.isEmpty() || raw.startsWith("ERROR") || raw.startsWith("TIMEOUT")) {
            r.detail = "shell output: " + raw;
            r.status = Status.FAIL;
            r.message = "Package scan failed";
            return;
        }
        // pm list packages -f format: "package:/data/app/.../base.apk=<pkg>"
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("package:")) continue;
            int eq = line.lastIndexOf('=');
            if (eq < 0) continue;
            String pkg     = line.substring(eq + 1).trim();
            String apkPath = line.substring("package:".length(), eq).trim();
            String vn = "?";
            long   vc = -1;
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                vn = pi.versionName != null ? pi.versionName : "?";
                vc = pi.getLongVersionCode();
            } catch (Throwable ignored) {}
            sLastDiscovery.add(new DiscoveredPkg(pkg, apkPath, vn, vc));
            sb.append("  ").append(pkg)
              .append("  v").append(vn).append(" (").append(vc).append(")\n")
              .append("    ").append(apkPath).append('\n');
        }
        r.detail = sb.length() == 0 ? "(no matching package)" : sb.toString();
        if (sLastDiscovery.isEmpty()) {
            r.status = Status.WARN;
            r.message = "No BYD/XDJA/DiLink/cluster package matched";
        } else {
            r.status = Status.PASS;
            r.message = sLastDiscovery.size() + " package(s) discovered (saved for D10)";
        }
    }

    /** Curated whitelist used by D10 to avoid extracting the entire BYD OS. */
    private static final String[] D10_INTERESTING = new String[] {
            "com.byd.clusterdebug",
            "com.byd.car.server",
            "com.byd.crosscontrol",
            "com.byd.containerservice",
            "com.xdja.containerservice",
            "com.byd.appstartmanagement",
            "com.byd.providers.appstartup",
            "com.byd.smarttravel",
            "com.byd.commander",
            "com.byd.freedom",
            "com.byd.overdrive",
            "com.byd.windowmanagement",
            // substring patterns (any discovered pkg containing these tokens):
            "fission", "projection", "cluster", "dilink5", "automotive.cluster",
    };

    private static boolean d10IsInteresting(String pkg) {
        String low = pkg.toLowerCase();
        for (String s : D10_INTERESTING) {
            if (s.contains(".")) {
                if (low.equals(s)) return true;
            } else {
                if (low.contains(s)) return true;
            }
        }
        return false;
    }

    /**
     * D10 — Extracts whitelisted APKs to {@code /storage/emulated/0/Download/dashcast_apks/}
     * so the user can grab them directly via the device file manager (no adb pull needed).
     *
     * <p>The old app-private location ({@code getExternalFilesDir(null)/extracted_apks/}) was
     * effectively invisible to the user on most BYD file managers; we now actively wipe it so we
     * don't fill the device with orphaned copies build after build. Filesystem ops use the ADB
     * shell (uid 2000), which has unrestricted access to {@code /sdcard/Download/} on Android 10+.
     */
    private static void runD10(Context ctx, TestResult r) {
        if (sLastDiscovery.isEmpty()) {
            r.status = Status.SKIPPED;
            r.message = "Run D9 first (discovery empty)";
            return;
        }
        final String outDir = "/storage/emulated/0/Download/dashcast_apks";
        AtomicReference<String> out = new AtomicReference<>("");

        // 1) Wipe the legacy app-private extraction dir so it stops eating storage.
        StringBuilder cleanup = new StringBuilder();
        java.io.File legacy = ctx.getExternalFilesDir(null);
        if (legacy != null) {
            java.io.File legacyDir = new java.io.File(legacy, "extracted_apks");
            if (legacyDir.exists()) {
                runShellSync(ctx, "rm -rf '" + legacyDir.getAbsolutePath() + "' 2>&1", out, 8000);
                cleanup.append("Legacy dir wiped: ").append(legacyDir.getAbsolutePath()).append('\n');
            }
        }

        // 2) Ensure the public Download target exists (mkdir via shell — app uid can't on A10+).
        runShellSync(ctx, "mkdir -p '" + outDir + "' 2>&1 && ls -ld '" + outDir + "' 2>&1", out, 4000);
        String mkdirOut = out.get().trim();
        if (mkdirOut.toLowerCase().contains("permission denied")
                || mkdirOut.toLowerCase().contains("cannot create")) {
            r.status = Status.FAIL;
            r.message = "Cannot create " + outDir + " \u2014 " + mkdirOut;
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (cleanup.length() > 0) sb.append(cleanup).append('\n');
        sb.append("Output dir: ").append(outDir).append('\n');
        sb.append("Visible in the on-device file manager under Download/dashcast_apks/.\n");
        sb.append("Also retrievable via: adb pull ").append(outDir).append('\n');
        sb.append("Filtered (whitelist) \u2014 ").append(sLastDiscovery.size())
          .append(" pkg discovered, only RE-relevant ones extracted.\n\n");

        int ok = 0, fail = 0, skipped = 0, cached = 0;
        for (DiscoveredPkg d : sLastDiscovery) {
            if (!d10IsInteresting(d.pkg)) {
                skipped++;
                sb.append("  \u2013 ").append(d.pkg).append("  (skipped, not in whitelist)\n");
                continue;
            }
            String safe = d.pkg.replace('/', '_');
            String dst  = outDir + "/" + safe + "_v" + d.versionCode + ".apk";

            // Cache via shell stat (avoids relying on app uid being able to read /sdcard on A10+).
            runShellSync(ctx, "stat -c %s '" + dst + "' 2>/dev/null", out, 3000);
            String sizeStr = out.get().trim();
            long existingSize = 0L;
            try { if (!sizeStr.isEmpty()) existingSize = Long.parseLong(sizeStr); }
            catch (NumberFormatException ignore) { /* not present */ }
            if (existingSize > 0L) {
                cached++;
                ok++;
                sb.append("  \u21bb ").append(d.pkg).append("  (cached, ").append(existingSize / 1024).append(" KB)\n");
                continue;
            }

            // cat + redirect avoids cp permission quirks on some BYD builds.
            runShellSync(ctx, "cat '" + d.apkPath + "' > '" + dst + "' 2>&1 && stat -c %s '" + dst + "' 2>&1", out, 15000);
            String raw = out.get().trim();
            long writtenSize = 0L;
            try { writtenSize = Long.parseLong(raw); } catch (NumberFormatException ignore) { /* fall through */ }
            if (writtenSize > 0L) {
                ok++;
                sb.append("  \u2713 ").append(d.pkg).append("  (").append(writtenSize / 1024).append(" KB)\n");
            } else {
                fail++;
                sb.append("  \u2717 ").append(d.pkg).append("  \u2014 ").append(raw).append('\n');
            }
        }
        r.detail = sb.toString();
        if (ok > 0 && fail == 0) {
            r.status = Status.PASS;
            r.message = ok + " APK(s) in Download/dashcast_apks/ (" + cached + " cached), " + skipped + " skipped";
        } else if (ok > 0) {
            r.status = Status.WARN;
            r.message = ok + " ok (" + cached + " cached) / " + fail + " failed / " + skipped + " skipped";
        } else if (skipped > 0 && fail == 0) {
            r.status = Status.WARN;
            r.message = "No package matched the RE whitelist (" + skipped + " discovered)";
        } else {
            r.status = Status.FAIL;
            r.message = "No APK could be extracted";
        }
    }

    private static void runD11(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 0 s16 \"\" 2>&1", out, 4000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no output)" : raw;
        String lower = raw.toLowerCase();
        if (lower.contains("securityexception") || lower.contains("permission denial")) {
            r.status = Status.FAIL;
            r.message = "SecurityException \u2014 service rejects uid 2000";
        } else if (lower.contains("does not exist") || lower.contains("could not find service")) {
            r.status = Status.FAIL;
            r.message = "auto_container service not found";
        } else if (lower.contains("result:") || lower.contains("parcel")) {
            r.status = Status.PASS;
            r.message = "service call accepted (no SecurityException)";
        } else {
            r.status = Status.WARN;
            r.message = "Inconclusive \u2014 check raw output";
        }
    }

    private static void runD12(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 16 s16 \"\" 2>&1", out, 4000);
        sb.append("[sendInfo 16 / Qt standby]\n").append(out.get()).append('\n');
        try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 18 s16 \"\" 2>&1", out, 4000);
        sb.append("\n[sendInfo 18 / close]\n").append(out.get()).append('\n');
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 0 s16 \"\" 2>&1", out, 4000);
        sb.append("\n[sendInfo 0 / refresh]\n").append(out.get()).append('\n');
        String all = sb.toString().toLowerCase();
        r.detail = sb.toString();
        if (all.contains("securityexception") || all.contains("permission denial")) {
            r.status = Status.FAIL;
            r.message = "Qt standby cycle blocked by permission check";
        } else if (all.contains("does not exist") || all.contains("could not find service")) {
            r.status = Status.FAIL;
            r.message = "auto_container service not found";
        } else {
            r.status = Status.PASS;
            r.message = "Qt standby cycle accepted (16 \u2192 18 \u2192 0)";
        }
    }

    private static void runD13(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iatm = atmCls.getMethod("getService").invoke(null);
            sb.append("IATM getService: ").append(iatm != null ? "obtained" : "null").append('\n');
            if (iatm != null) {
                try {
                    iatm.getClass().getMethod("setTaskWindowingMode", int.class, int.class, boolean.class)
                            .invoke(iatm, 0, 4, true);
                    sb.append("setTaskWindowingMode(0,4,true): NO exception (unexpected on un-signed app)\n");
                    r.status = Status.WARN;
                    r.message = "Call did not throw \u2014 platform may silently no-op";
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                    sb.append("setTaskWindowingMode threw: ")
                      .append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
                    String cs = cause.getClass().getSimpleName();
                    if (cs.contains("Security") || cs.contains("Permission")) {
                        r.status = Status.WARN;
                        r.message = "SecurityException (expected) \u2014 typed path needs signature perms or proxy daemon";
                    } else if (cs.contains("IllegalArgument")) {
                        r.status = Status.PASS;
                        r.message = "Method reachable; threw IllegalArgument on bogus taskId (good news)";
                    } else {
                        r.status = Status.WARN;
                        r.message = cs + " \u2014 see detail";
                    }
                } catch (NoSuchMethodException nsme) {
                    sb.append("setTaskWindowingMode: method not present\n");
                    r.status = Status.FAIL;
                    r.message = "API absent on this DL5 build";
                }
            } else {
                r.status = Status.FAIL;
                r.message = "IATM service unavailable";
            }
        } catch (Throwable t) {
            sb.append("ATM resolve failed: ").append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append('\n');
            r.status = Status.FAIL;
            r.message = "ActivityTaskManager not resolvable";
        }
        r.detail = sb.toString();
    }

    private static void runD14(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys window 2>/dev/null | grep -E 'Display #|mDisplayId|imeLayerStack|focused|mStackId|mCurrentFocus|mDisplayContent' | head -80",
                out, 8000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no matching window state)" : raw;
        r.status = raw.isEmpty() ? Status.WARN : Status.PASS;
        r.message = raw.isEmpty() ? "dumpsys window returned nothing" : "Window state dumped";
    }

    private static void runD15(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys package com.byd.appstartmanagement 2>/dev/null"
                + " | grep -iE 'permission|receiver|service |activity |authority|provider|signature|version'"
                + " | head -120",
                out, 8000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(empty \u2014 package may not be installed)" : raw;
        if (raw.isEmpty()) {
            r.status = Status.WARN;
            r.message = "appstartmanagement not present or dumpsys empty";
        } else {
            r.status = Status.PASS;
            r.message = "Inventory captured (review detail)";
        }
    }

    private static void runD16(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "dumpsys package com.byd.containerservice 2>/dev/null"
                + " | grep -iE 'permission|receiver|service |activity |authority|provider|signature|version'"
                + " | head -120",
                out, 8000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(empty \u2014 package may not be installed)" : raw;
        if (raw.isEmpty()) {
            r.status = Status.WARN;
            r.message = "containerservice not present or dumpsys empty";
        } else {
            r.status = Status.PASS;
            r.message = "Inventory captured (review detail)";
        }
    }

    private static void runD17(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "id 2>&1; echo ---; which app_process64 app_process 2>&1; echo ---;"
                + " app_process64 -Xnoimage-dex2oat /system/bin --nice-name=dl5_probe -version 2>&1 | head -5; echo ---;"
                + " ls /system/bin/app_process64 2>&1",
                out, 6000);
        String raw = out.get();
        r.detail = raw;
        String lower = raw.toLowerCase();
        boolean hasApp = lower.contains("/system/bin/app_process");
        boolean denied = lower.contains("permission denied") || lower.contains("avc:");
        if (hasApp && !denied) {
            r.status = Status.PASS;
            r.message = "app_process64 reachable from shell uid \u2014 proxy daemon spawn viable";
        } else if (hasApp && denied) {
            r.status = Status.FAIL;
            r.message = "app_process64 present but SELinux denied \u2014 daemon spawn blocked";
        } else {
            r.status = Status.WARN;
            r.message = "app_process64 not found at expected path";
        }
    }

    private static void runD18(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "id 2>&1; echo ---; ps -Z -o LABEL,USER,PID,NAME 2>/dev/null | head -10",
                out, 4000);
        r.detail = out.get();
        r.status = out.get().isEmpty() ? Status.WARN : Status.PASS;
        r.message = out.get().isEmpty() ? "ps -Z returned empty" : "SELinux context captured";
    }

    private static void runD19(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "service list 2>/dev/null | grep -iE 'auto|byd|cluster|fission|display|window|projection|crosscontrol|xdja|task' | head -60",
                out, 6000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no matching service)" : raw;
        r.status = raw.isEmpty() ? Status.WARN : Status.PASS;
        r.message = raw.isEmpty() ? "No interesting service surfaced" : "Service inventory captured";
    }

    private static void runD20(Context ctx, TestResult r) {
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx,
                "service list 2>/dev/null | grep -iE 'activity_task|activitytask' | head -5",
                out, 4000);
        String raw = out.get();
        r.detail = raw.isEmpty() ? "(no activity_task service line)" : raw;
        if (raw.toLowerCase().contains("activitytask") || raw.toLowerCase().contains("activity_task")) {
            r.status = Status.PASS;
            r.message = "IActivityTaskManager binder present";
        } else {
            r.status = Status.WARN;
            r.message = "IATM service not visible in 'service list'";
        }
    }

    private static void runD21(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        sb.append("Total displays: ").append(all.length).append('\n');
        for (Display d : all) {
            int flags = d.getFlags();
            sb.append("  #").append(d.getDisplayId())
              .append("  name=\"").append(d.getName()).append("\"")
              .append("  flags=0x").append(Integer.toHexString(flags))
              .append("  state=").append(d.getState()).append('\n');
            sb.append("      flagBits:");
            if ((flags & 0x1)  != 0) sb.append(" PROTECTED");
            if ((flags & 0x2)  != 0) sb.append(" SECURE");
            if ((flags & 0x4)  != 0) sb.append(" PRIVATE");
            if ((flags & 0x8)  != 0) sb.append(" PRESENTATION");
            if ((flags & 0x10) != 0) sb.append(" ROUND");
            if ((flags & 0x20) != 0) sb.append(" CAN_SHOW_WITH_INSECURE_KEYGUARD");
            if ((flags & 0x40) != 0) sb.append(" SHOULD_SHOW_SYSTEM_DECORATIONS");
            if ((flags & 0x80) != 0) sb.append(" TRUSTED");
            sb.append('\n');
            // Reflective DisplayInfo for ownerUid/ownerPackageName/type
            try {
                java.lang.reflect.Method m = Display.class.getDeclaredMethod("getDisplayInfo", Class.forName("android.view.DisplayInfo"));
                Object info = Class.forName("android.view.DisplayInfo").getDeclaredConstructor().newInstance();
                m.setAccessible(true);
                m.invoke(d, info);
                Class<?> diCls = info.getClass();
                for (String f : new String[]{"type", "ownerUid", "ownerPackageName", "logicalWidth", "logicalHeight", "logicalDensityDpi", "removeMode", "uniqueId"}) {
                    try {
                        Object v = diCls.getDeclaredField(f).get(info);
                        sb.append("      ").append(f).append(" = ").append(String.valueOf(v)).append('\n');
                    } catch (NoSuchFieldException ignored) {}
                }
            } catch (Throwable t) {
                sb.append("      (DisplayInfo reflection failed: ").append(t.getClass().getSimpleName()).append(")\n");
            }
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = all.length + " display(s) inventoried";
    }

    private static void runD22(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "dumpsys display 2>/dev/null | grep -E 'Display id|mDisplayId|mOwnerUid|mOwnerPackageName|mType|mFlags|mName' | head -120", out, 8000);
        sb.append("=== dumpsys display ===\n").append(out.get()).append("\n\n");
        runShellSync(ctx, "dumpsys SurfaceFlinger --display-id 2>/dev/null | head -40", out, 6000);
        sb.append("=== SurfaceFlinger --display-id ===\n").append(out.get()).append("\n\n");
        runShellSync(ctx, "ps -A -o PID,USER,NAME 2>/dev/null | head -80", out, 4000);
        sb.append("=== ps -A (first 80 lines, helps identify PID owners seen in D14) ===\n").append(out.get());
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "Display ownership data captured";
    }

    private static void runD23(Context ctx, TestResult r) {
        // Visual cycle: sendInfo(2) = all warning lamps ON, wait 3 s, sendInfo(3) = OFF.
        // Confirmed via com.byd.clusterdebug v1.6.1.4 (code 2 = 所有警告灯点亮, code 3 = 所有警告灯熄灭).
        // No machine-readable feedback: relies on the user observing the cluster.
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");

        String cmdOn  = "service call auto_container 2 i32 1000 i32 2 s16 \"\" 2>&1";
        String cmdOff = "service call auto_container 2 i32 1000 i32 3 s16 \"\" 2>&1";

        sb.append("[sendInfo(2) — ALL WARNING LAMPS ON]\n").append(cmdOn).append('\n');
        runShellSync(ctx, cmdOn, out, 3000);
        sb.append(out.get().trim()).append("\n\n");
        String onResult = out.get();

        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        sb.append("[sendInfo(3) — ALL WARNING LAMPS OFF]\n").append(cmdOff).append('\n');
        runShellSync(ctx, cmdOff, out, 3000);
        sb.append(out.get().trim()).append("\n\n");
        String offResult = out.get();

        sb.append("→ Confirme visuellement sur le cluster :\n")
          .append("  • Les warning lamps se sont-ils allumés pendant ~3 s puis éteints ?\n")
          .append("  • Si OUI → auto_container.sendInfo accepté côté cluster (codes 2/3 opérationnels).\n")
          .append("  • Si NON → binder accepté mais pas répercuté visuellement (Qt cluster déconnecté ?).\n");
        r.detail = sb.toString();

        String low = (onResult + "\n" + offResult).toLowerCase();
        if (low.contains("service auto_container does not exist")) {
            r.status = Status.FAIL;
            r.message = "auto_container service absent — cycle impossible";
        } else if (low.contains("result: parcel")) {
            r.status = Status.WARN;
            r.message = "Cycle 2→3 envoyé — confirme visuellement les warning lamps (3 s)";
        } else {
            r.status = Status.WARN;
            r.message = "Cycle envoyé mais réponse binder inhabituelle — voir detail";
        }
    }

    private static void runD24(Context ctx, TestResult r) {
        String[] services = {"magicwindow", "crossservice", "mirror", "BYDMgmt", "byd_datacached",
                "IBYDCDRService", "DevOperatorService", "autoservice", "byd_updated_service",
                "cloud_server_app_service", "color_display"};
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        for (String svc : services) {
            sb.append("─── ").append(svc).append(" ───\n");
            runShellSync(ctx, "service check " + svc + " 2>&1; service call " + svc + " 0 2>&1 | head -3", out, 3000);
            sb.append(out.get().trim()).append("\n\n");
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = services.length + " service(s) probed";
    }

    private static void runD25(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> stub = Class.forName("android.app.IActivityTaskManager$Stub");
            sb.append("Stub class: ").append(stub.getName()).append('\n');
            Object iatm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            sb.append("Live binder: ").append(iatm != null ? iatm.getClass().getName() : "null").append('\n');
            Class<?> iface = Class.forName("android.app.IActivityTaskManager");
            java.lang.reflect.Method[] methods = iface.getDeclaredMethods();
            sb.append("\nIActivityTaskManager declared methods (").append(methods.length).append("):\n");
            // Filter to projection/windowing-relevant ones first
            StringBuilder hot = new StringBuilder();
            StringBuilder rest = new StringBuilder();
            for (java.lang.reflect.Method m : methods) {
                StringBuilder sig = new StringBuilder("  ").append(m.getName()).append("(");
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) { if (i > 0) sig.append(','); sig.append(pts[i].getSimpleName()); }
                sig.append(") : ").append(m.getReturnType().getSimpleName()).append('\n');
                String n = m.getName().toLowerCase();
                if (n.contains("windowing") || n.contains("display") || n.contains("task") || n.contains("launch") || n.contains("start") || n.contains("move")) {
                    hot.append(sig);
                } else {
                    rest.append(sig);
                }
            }
            sb.append("\n[HOT \u2014 windowing/display/task/launch/start/move]\n").append(hot);
            sb.append("\n[OTHER]\n").append(rest);
            r.status = Status.PASS;
            r.message = methods.length + " IATM methods enumerated";
        } catch (Throwable t) {
            sb.append("Reflection failed: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            r.status = Status.FAIL;
            r.message = "IATM reflection unavailable";
        }
        r.detail = sb.toString();
    }

    private static void runD26(Context ctx, TestResult r) {
        // For each non-main display, launch the standard ADB canary and check where it actually landed.
        String canary = "com.github.standardadb";
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (Display d : dm.getDisplays()) if (d.getDisplayId() != 0) ids.add(d.getDisplayId());
        // Also force-probe id 2 in case it's not in getDisplays() output
        ids.add(2);
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        for (int id : ids) {
            sb.append("─── display ").append(id).append(" ───\n");
            runShellSync(ctx, "am force-stop " + canary + " 2>&1; am start --display " + id
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                    + canary + "/com.github.standardadb.SplashActivity 2>&1", out, 4000);
            sb.append("[start] ").append(out.get().trim()).append('\n');
            try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            runShellSync(ctx, "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|displayId|" + canary + "' | head -20", out, 4000);
            sb.append("[where] ").append(out.get().trim()).append("\n\n");
        }
        runShellSync(ctx, "am force-stop " + canary + " 2>&1", out, 3000);
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "Probed " + ids.size() + " display(s) — see detail for actual landing";
    }

    private static void runD27(Context ctx, TestResult r) {
        String pkg = "com.byd.clusterdebug";
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "cmd package resolve-activity --brief " + pkg + " 2>&1; echo ---; "
                + "dumpsys package " + pkg + " 2>/dev/null | grep -E 'Activity|Service|Receiver|Permission' | head -40",
                out, 6000);
        sb.append("=== resolve + manifest ===\n").append(out.get()).append("\n\n");
        runShellSync(ctx, "logcat -c 2>&1; am start -n " + pkg + "/.MainActivity 2>&1", out, 4000);
        sb.append("=== am start ===\n").append(out.get()).append('\n');
        try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        runShellSync(ctx, "dumpsys activity activities 2>/dev/null | grep -E '" + pkg + "|displayId' | head -10", out, 4000);
        sb.append("\n=== where it landed ===\n").append(out.get()).append('\n');
        runShellSync(ctx, "logcat -d 2>&1 | grep -iE 'clusterdebug|cluster|auto_container|AutoContainer|projection|fission' | tail -40", out, 6000);
        sb.append("\n=== logcat trace ===\n").append(out.get()).append('\n');
        runShellSync(ctx, "am force-stop " + pkg + " 2>&1", out, 3000);
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "clusterdebug launch probed — review detail";
    }

    private static void runD28(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "logcat -c 2>&1", out, 3000);
        sb.append("=== logcat cleared ===\n\n");
        sb.append("=== sendInfo(1000, 16) projection ON ===\n");
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 16 s16 \"\" 2>&1", out, 4000);
        sb.append(out.get()).append('\n');
        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        sb.append("\n=== dumpsys display (post-sendInfo) ===\n");
        runShellSync(ctx, "dumpsys display 2>/dev/null | grep -E 'Display id|mDisplayId|mFlags|mState' | head -30", out, 5000);
        sb.append(out.get()).append('\n');
        sb.append("\n=== logcat filtered ===\n");
        runShellSync(ctx, "logcat -d -v threadtime 2>&1 | grep -iE 'auto_container|AutoContainer|cluster|projection|fission|XDJA|magicwindow|MagicWindow|containerservice' | tail -80", out, 8000);
        sb.append(out.get()).append('\n');
        // Always close so we don't leave the cluster in a weird state
        sb.append("\n=== cleanup sendInfo(1000, 18) + sendInfo(1000, 0) ===\n");
        runShellSync(ctx, "service call auto_container 2 i32 1000 i32 18 s16 \"\" 2>&1; sleep 1; service call auto_container 2 i32 1000 i32 0 s16 \"\" 2>&1", out, 6000);
        sb.append(out.get()).append('\n');
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "Live sendInfo cycle + logcat captured";
    }

    private static void runD29(Context ctx, TestResult r) {
        String[] actions = {
                "com.byd.action.PROJECT", "com.byd.action.CLUSTER_PROJECTION",
                "com.byd.containerservice.PROJECT", "com.byd.containerservice.START",
                "com.byd.appstartmanagement.START", "com.byd.appstartup.START",
                "android.intent.action.MAIN", // baseline check
                "com.byd.intent.action.CLUSTER", "com.byd.cluster.START",
                "byd.intent.action.AUTO_DISPLAY"
        };
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        for (String a : actions) {
            if (a.equals("android.intent.action.MAIN")) continue; // skip baseline noise
            sb.append("─── action: ").append(a).append(" ───\n");
            runShellSync(ctx, "cmd package query-intent-activities -a '" + a + "' 2>&1 | head -10; echo ---; "
                    + "cmd package query-intent-services -a '" + a + "' 2>&1 | head -10", out, 4000);
            sb.append(out.get().trim()).append("\n\n");
        }
        // Bonus: list every intent filter declared by the suspected gatekeepers
        sb.append("─── intent filters declared by containerservice + appstartmanagement ───\n");
        runShellSync(ctx, "dumpsys package com.byd.containerservice com.byd.appstartmanagement 2>/dev/null | grep -E 'Action:|Category:|Scheme:' | sort -u | head -60", out, 6000);
        sb.append(out.get()).append('\n');
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "Intent filter discovery captured";
    }

    private static void runD30(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> out = new AtomicReference<>("");
        runShellSync(ctx, "dumpsys SurfaceFlinger --display-id 2>/dev/null", out, 6000);
        sb.append("=== SurfaceFlinger --display-id ===\n").append(out.get()).append("\n\n");
        runShellSync(ctx, "dumpsys SurfaceFlinger 2>/dev/null | grep -E 'Display |layerStack|Output|orientation|hwc composition|HWComposer' | head -60", out, 8000);
        sb.append("=== SurfaceFlinger (filtered) ===\n").append(out.get()).append("\n\n");
        runShellSync(ctx, "dumpsys SurfaceFlinger --list 2>/dev/null | head -40", out, 6000);
        sb.append("=== SurfaceFlinger --list (top layers) ===\n").append(out.get()).append('\n');
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "SurfaceFlinger topology captured";
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

    /**
     * D31 — Live cluster projection end-to-end probe.
     *
     * <p>For each PRESENTATION display, drives the full visual cycle the user has so far had
     * to do by hand:
     * <ol>
     *   <li>{@code sendInfo(1000, 16)} — opens the projection tunnel (Qt switches to
     *       standby; the {@code shared_fission_bg_XDJAScreenProjection_*} layerStack becomes
     *       visible, initially empty/black);</li>
     *   <li>wait ~1.5 s so Qt repositions the overlay;</li>
     *   <li>{@code am start --display N <selected pkg>/<launcher activity>} — pushes a real
     *       activity onto the projection layerStack;</li>
     *   <li>wait ~3 s so the user can observe the cluster (this is the user-visible window);</li>
     *   <li>{@code am force-stop <selected pkg>};</li>
     *   <li>{@code sendInfo(1000, 18)} — closes the projection;</li>
     *   <li>{@code sendInfo(1000, 0)} — restores the normal cluster video flow;</li>
     *   <li>wait ~1.5 s to give a clear visual separation between iterations.</li>
     * </ol>
     *
     * <p>Reuses the package selected for D8 (no new UI). The package must already be installed
     * on the device — checked up front via {@link PackageManager#getPackageInfo(String, int)}.
     */
    private static void runD31(Context ctx, D8Params params, TestResult r) {
        // Default to BYD's own clusterdebug — always installed on DL5, designed for the cluster.
        // The D8 dropdown is an optional override for users who want to test their own app.
        final boolean overridden = params != null
                && params.targetPackage != null
                && !params.targetPackage.isEmpty();
        final String pkg = overridden ? params.targetPackage : "com.byd.clusterdebug";

        // 1) Make sure the package is actually installed.
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException nfe) {
            r.status = Status.FAIL;
            r.message = "Package '" + pkg + "' is not installed on this device";
            r.detail = "PackageManager.getPackageInfo threw NameNotFoundException for " + pkg + ".\n"
                     + (overridden
                         ? "You selected this package in the D8 dropdown but it isn't installed.\n"
                           + "Clear the D8 selection to fall back to com.byd.clusterdebug, or pick an installed package."
                         : "com.byd.clusterdebug should be present on every DL5 head unit \u2014 if it isn't,\n"
                           + "select an installed BYD package in the D8 dropdown to use instead.");
            return;
        }

        // 2) Collect target displays (PRESENTATION = the cluster-class displays).
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentation == null || presentation.length == 0) {
            r.status = Status.FAIL;
            r.message = "No PRESENTATION display available";
            return;
        }

        // 3) Resolve launcher component once (shell needs the full ComponentName for --display).
        String component = resolveLauncherComponent(ctx, pkg);
        if (component == null || component.isEmpty()) {
            r.status = Status.FAIL;
            r.message = "Could not resolve a launcher activity for '" + pkg + "'";
            return;
        }

        StringBuilder detail = new StringBuilder();
        detail.append("Target package : ").append(pkg)
              .append(overridden ? "  (D8 dropdown override)" : "  (default clusterdebug)").append('\n');
        detail.append("Launcher       : ").append(component).append('\n');
        detail.append("PRESENTATION displays found: ");
        for (int i = 0; i < presentation.length; i++) {
            if (i > 0) detail.append(", ");
            detail.append(presentation[i].getDisplayId());
        }
        detail.append("\n\n");

        AtomicReference<String> out = new AtomicReference<>("");
        int passes = 0, fails = 0;

        for (Display disp : presentation) {
            int displayId = disp.getDisplayId();
            detail.append("\u2500\u2500\u2500 display ").append(displayId).append(" (").append(disp.getName()).append(") \u2500\u2500\u2500\n");

            // a) Open projection
            runShellSync(ctx, "service call auto_container 2 i32 1000 i32 16 s16 \"\" 2>&1", out, 4000);
            detail.append("[a] sendInfo(1000, 16) projection ON  \u2192 ").append(out.get().trim()).append('\n');

            try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            // b) Push activity onto the projection layerStack
            String launchCmd = "am start --display " + displayId
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                    + " -n " + component + " 2>&1";
            runShellSync(ctx, launchCmd, out, 6000);
            String launchOut = out.get().trim();
            detail.append("[b] ").append(launchCmd).append('\n')
                  .append("    \u2192 ").append(launchOut).append('\n');

            // c) User-visible window
            try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            // d) Snapshot of where the activity landed (correlate visual with stack state).
            runShellSync(ctx,
                    "dumpsys activity activities 2>/dev/null"
                  + " | grep -E '" + java.util.regex.Pattern.quote(pkg) + "|displayId=|topResumed'"
                  + " | head -8 2>&1", out, 4000);
            detail.append("[d] activity stack snapshot:\n").append(out.get()).append('\n');

            // e) Kill and tear down the projection cleanly
            runShellSync(ctx, "am force-stop " + pkg + " 2>&1", out, 4000);
            detail.append("[e] am force-stop ").append(pkg).append(" \u2192 ").append(out.get().trim()).append('\n');

            runShellSync(ctx, "service call auto_container 2 i32 1000 i32 18 s16 \"\" 2>&1", out, 4000);
            detail.append("[f] sendInfo(1000, 18) projection OFF \u2192 ").append(out.get().trim()).append('\n');

            runShellSync(ctx, "service call auto_container 2 i32 1000 i32 0  s16 \"\" 2>&1", out, 4000);
            detail.append("[g] sendInfo(1000, 0)  restore video \u2192 ").append(out.get().trim()).append('\n');

            // Visual separation between iterations
            try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            // Pass criterion: shell accepted both the launch and the cleanup without obvious errors.
            String low = launchOut.toLowerCase();
            boolean launchOk = !low.contains("error")
                            && !low.contains("securityexception")
                            && !low.contains("permission denial");
            if (launchOk) passes++; else fails++;
            detail.append('\n');
        }

        detail.append("User question: on which display(s) did '").append(pkg)
              .append("' actually render on the cluster?\n");

        r.detail = detail.toString();
        if (fails == 0 && passes > 0) {
            r.status = Status.PASS;
            r.message = "Cycle run on " + passes + " display(s) \u2014 user to confirm visual rendering";
        } else if (passes > 0) {
            r.status = Status.WARN;
            r.message = passes + " ok / " + fails + " failed \u2014 see detail";
        } else {
            r.status = Status.FAIL;
            r.message = "Every am start --display refused";
        }
    }
}
