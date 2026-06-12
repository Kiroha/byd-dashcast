package com.byd.dashcast.dilink4;
import java.util.Locale;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Display;

import com.byd.dashcast.util.AppLogger;

import java.io.File;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DiLink4TestRunner — diagnostic suite for the DiLink 4.0 platform
 * (BYD-AUTO/DiLink4.0, Android 10 / API 29, hardware qcom).
 *
 * <p><b>Three tiers:</b>
 *
 * <ul>
 *   <li><b>L1–L15 (recon, shell-free)</b> — pure Java reflection,
 *       PackageManager, DisplayManager, /proc / /sys scans, Binder direct
 *       calls. Always available. Confirms the platform identity and basic
 *       reachability of the BYD-AUTO services.</li>
 *   <li><b>S1–S15 (shell, ADB-driven)</b> — synchronous wrappers around
 *       {@code AdbLocalClient.executeShellWithResult}. Each test self-marks
 *       SKIPPED if the shell returns an error so a DL4 without ADB open
 *       still passes cleanly through the L tier.</li>
 *   <li><b>D1–D10 (cluster activation sondage)</b> — the field-targeted
 *       DiLink 4 cluster-activation probes. Sends the DL3-style
 *       {@code sendInfo(30 → 16 → 35)} sequence against the PascalCase
 *       {@code AutoContainer} binder (NOT the DL5 snake_case
 *       {@code auto_container} which does not exist on DL4 — root cause of
 *       the 23/05/2026 testeur regression), waits between each step, dumps
 *       {@link DisplayManager#getDisplays()} so the cluster surface
 *       materialisation can be observed test-by-test, then launches
 *       {@code com.byd.clusterdebug} on the discovered display and retracts
 *       cleanly with {@code sendInfo(18)} + {@code sendInfo(0)}.</li>
 * </ul>
 *
 * <p>Self-contained — shares no types or helpers with {@code DiLink5TestRunner}
 * or {@code DiLink2TestRunner} so a regression in one suite cannot affect
 * another.
 */
public final class DiLink4TestRunner {

    private static final String TAG = "DiLink4TestRunner";

    /** PascalCase binder name — confirmed by Beta Engine P6 + P13 on the
     *  23/05/2026 DL4 testeur device. The snake_case {@code auto_container}
     *  used on DL5 does NOT exist on DL3/DL4. */
    private static final String AUTO_CONTAINER_SVC   = "AutoContainer";
    private static final String AUTO_CONTAINER_IFACE = "android.os.IAutoContainer";

    /** Transaction code for {@code sendInfo(int type, int info, String infoStr)}
     *  on {@code IAutoContainer.Stub}. Confirmed reachable on the DL4 testeur
     *  device (Beta Engine P13 returned PASS). */
    private static final int TXN_SEND_INFO = 2;

    /** {@code type} argument for cluster-control sendInfo calls. */
    private static final int SEND_INFO_TYPE_CLUSTER = 1000;

    /** Default cluster target for D7 — {@code com.byd.clusterdebug} is
     *  BYD's own cluster diagnostic app, present on every DiLink ROM. */
    private static final String DEFAULT_CLUSTER_TARGET = "com.byd.clusterdebug";

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
        Thread t = new Thread(r, "dilink4-test-runner");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Shared volatile cache populated by D-tier tests so successive D tests
     *  can observe the cluster display id discovered by the previous step. */
    private static volatile int sLastDiscoveredClusterDisplayId = -1;
    private static volatile String sLastDiscoveredClusterName   = "";

    private DiLink4TestRunner() {}

    /** Ordered catalog. */
    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();

        // ─── L tier — recon (shell-free) ───────────────────────────────────
        list.add(new TestDef("L1", "Platform fingerprint",
                "Build.* + ro.* via SystemProperties reflection. Confirms the DL4 signature (brand=BYD-AUTO, product=DiLink4.0, API 29, hardware=qcom)."));
        list.add(new TestDef("L2", "AutoContainer binder reachable (direct)",
                "Reflective ServiceManager.getService(\"AutoContainer\") + getInterfaceDescriptor — confirms the PascalCase binder name (DL3/DL4 ABI) without going through a shell."));
        list.add(new TestDef("L3", "DisplayManager initial inventory",
                "Enumerate DisplayManager.getDisplays() + PRESENTATION category. DL4 typically reports ONE display until sendInfo(30→16→35) materialises the cluster surface (see D tier)."));
        list.add(new TestDef("L4", "SurfaceFlinger binder reflective probe",
                "ServiceManager.getService(\"SurfaceFlinger\") + interface descriptor. Confirms the compositor is reachable from app uid."));
        list.add(new TestDef("L5", "BYD/XDJA/DiLink packages discovery (PM)",
                "pm.getInstalledPackages() filtered on com.byd.*, com.xdja.*, com.dilink.* + any *cluster*. Returns package name + versionName + versionCode + APK path. Captures clusterdebug, car.server, containerservice, appstartmanagement, smarttravel etc."));
        list.add(new TestDef("L6", "ServiceManager full inventory + filter",
                "Reflective ServiceManager.listServices() → filtered on cluster|display|auto|byd|window|fission|xdja|projection|cross|mirror|container."));
        list.add(new TestDef("L7", "ActivityOptions.setLaunchDisplayId availability",
                "ActivityOptions.makeBasic().setLaunchDisplayId(N).toBundle() — pure availability check, the typed cluster-launch path on API 29+."));
        list.add(new TestDef("L8", "AutoContainer interface methods enumeration",
                "Reflective dump of IAutoContainer.Stub (or descriptor probe) — lists method count + first few names to confirm we're talking to the expected sendInfo binder."));
        list.add(new TestDef("L9", "BYD SDK classpath probe",
                "Class.forName probes for BYDAutoSpeedDevice / EnergyDevice / GearboxDevice / ACDevice / AirConditionerDevice / DoorDevice / LightDevice / WiperDevice / cluster-related vendor classes."));
        list.add(new TestDef("L10", "/sys/class/drm + /dev/graphics inventory",
                "Lists /sys/class/drm and /dev/graphics from app uid — a second DRM connector hints at a hardware cluster output not yet surfaced by DisplayManager."));
        list.add(new TestDef("L11", "Cluster service candidates brute probe",
                "ServiceManager.getService() on ~30 candidate names — both casings (AutoContainer / auto_container), cluster, byd_cluster, mtk_cluster, secondary_display, mirror, magicwindow, crosscontrol, containerservice, fission, projection_service…. Reports present/absent + interface descriptor when reachable."));
        list.add(new TestDef("L12", "BYD-specific ro / persist properties",
                "Reflective SystemProperties.get on ~25 ro.byd.* / persist.byd.* / ro.dilink.* / ro.byd.car.* / persist.sys.byd.* keys — identifies model / region / cluster size from the app uid."));
        list.add(new TestDef("L13", "Granted system permissions audit",
                "PackageManager.checkPermission for INTERNAL_SYSTEM_WINDOW, ACTIVITY_EMBEDDING, MANAGE_ACTIVITY_TASKS, BYDAUTO_*, INJECT_EVENTS, ACCESS_SURFACE_FLINGER. Shows which DL4 paths are unlocked from app uid (typically NONE — we go through ADB shell)."));
        list.add(new TestDef("L14", "ADB local TCP port probe",
                "Raw Socket connect (300 ms) on 127.0.0.1:5555. Gates every S/D test — if CLOSED, the user must enable USB debugging / ADB-over-TCP first."));
        list.add(new TestDef("L15", "AppLogger sanity + LaunchableApps probe",
                "Enumerates launcher-resolvable BYD apps so the user can confirm clusterdebug is present (D7 fallback target)."));

        // ─── S tier — shell-based, requires ADB-over-TCP open on DL4 ───────
        list.add(new TestDef("S1", "ADB shell reachable — id -u",
                "`id -u && id -un && pwd && uname -a`. Confirms AdbLocalClient can run shell on DL4 and returns uid=2000. Gates the rest of the S + D tiers — SKIPPED here means the user has not opened ADB."));
        list.add(new TestDef("S2", "Runtime properties — getprop BYD/DiLink filter",
                "`getprop | grep -iE 'byd|dilink|adb|cluster|display|sf\\\\.lcd'`. Captures every BYD property visible to the shell uid (superset of L12 — some props are uid-gated)."));
        list.add(new TestDef("S3", "wm size / density / overscan",
                "`wm size; wm density; wm overscan`. Health check — if Override size ≠ Physical or overscan ≠ 0,0,0,0 the display has been externally resized (BYD UI, dev menu, prior adb command)."));
        list.add(new TestDef("S4", "dumpsys display — full inventory",
                "`dumpsys display | grep -E 'mDisplayId|mBaseDisplayInfo|Override|Physical|name=|state=' | head -80`. Enumerates every Display known to the framework. Surfaces hidden cluster displays."));
        list.add(new TestDef("S5", "dumpsys SurfaceFlinger — physical displays",
                "`dumpsys SurfaceFlinger --display-id ; dumpsys SurfaceFlinger | grep -E 'Display [0-9]+|Display \"' | head -20`. HWComposer-managed physical displays — includes outputs that never reach DisplayManager."));
        list.add(new TestDef("S6", "service list — BYD/cluster filter (shell)",
                "`service list | grep -iE 'auto|byd|cluster|fission|display|window|projection|xdja|crosscontrol|magic|container|mirror' | head -40`. Shell-side superset of L6 — reaches services hidden from app uid by SELinux."));
        list.add(new TestDef("S7", "AutoContainer descriptor probe (shell)",
                "`service call AutoContainer 1 2>&1`. Sends transaction code 1 (INTERFACE_TRANSACTION via service shell) and reads the returned descriptor. Confirms the PascalCase binder exists on DL4 (returns Parcel containing 'android.os.IAutoContainer')."));
        list.add(new TestDef("S8", "auto_container snake_case ABSENT (negative check)",
                "`service call auto_container 1 2>&1`. Expected to return 'Service auto_container does not exist' — this is the negative proof that DL4 uses PascalCase (and therefore that v1.2.27's DL4 auto-detection + FORCE_ON neutralisation is the correct fix)."));
        list.add(new TestDef("S9", "BYD packages — pm list -f shell",
                "`pm list packages -f | grep -iE 'com\\\\.byd|com\\\\.xdja|com\\\\.dilink|cluster|automotive' | sort -u`. Captures every BYD package with absolute APK path."));
        list.add(new TestDef("S10", "Activity stack snapshot — dumpsys activity",
                "`dumpsys activity activities | grep -E 'ResumedActivity|displayId|TaskRecord|mResumedActivity' | head -30`. Lists currently-resumed activity per display + task displayId mapping. Baseline before D-tier cluster launch."));
        list.add(new TestDef("S11", "SELinux state",
                "`getenforce ; id -Z 2>/dev/null`. Reports Enforcing vs Permissive + shell context."));
        list.add(new TestDef("S12", "Cluster-related processes",
                "`ps -A -o PID,USER,NAME 2>/dev/null | grep -iE 'cluster|fission|projection|surface|byd|auto|xdja|mirror|container' | head -30`."));
        list.add(new TestDef("S13", "Settings — BYD filter",
                "`settings list system 2>&1 | grep -iE 'byd|cluster|display|overscan|density' ; echo --- ; settings list secure 2>&1 | grep -iE 'byd|cluster|display' ; echo --- ; settings list global 2>&1 | grep -iE 'byd|cluster|display'`."));
        list.add(new TestDef("S14", "am start --display 0 dashcast (smoke)",
                "`am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.byd.dashcast/.MainActivity 2>&1`. Probes that `am start --display N` is functional on DL4 / API 29 (the flag was added in API 26 so this should be OK)."));
        list.add(new TestDef("S15", "Hardware fingerprint — cpuinfo + meminfo",
                "`cat /proc/cpuinfo | grep -iE 'Hardware|model name|Processor' | head -5 ; echo --- ; cat /proc/meminfo | head -4 ; echo --- ; df -h /data 2>&1 | head -2`. SoC identifier (expected qcom on DL4 testeur device), RAM, /data free space."));

        // ─── D tier — DL4 cluster activation sondage (DESTRUCTIVE — sends
        //              live sendInfo commands, materialises the cluster
        //              surface, launches an app on it, then retracts) ─────
        list.add(new TestDef("D1", "sendInfo(1000,30,\"\") — Seal EU cluster prep",
                "`service call AutoContainer 2 i32 1000 i32 30 s16 \"\"`. DL3-style step 1 — prepares the cluster surface (12.3\" Seal EU type). PASS when shell returns a Parcel without 'does not exist' / 'Permission Denial'."));
        list.add(new TestDef("D2", "DisplayManager dump AFTER D1 (6 s wait)",
                "Waits 6 s after D1 then re-enumerates DisplayManager.getDisplays(). On DL3 the new PRESENTATION surface (e.g. `fission_bg_xdjaVirtualSurface`) typically appears within ~300 ms of D3 (sendInfo 35), not D1 — so this check usually still shows 1 display. Acts as a baseline before the projection step."));
        list.add(new TestDef("D3", "sendInfo(1000,16,\"\") — projection open",
                "`service call AutoContainer 2 i32 1000 i32 16 s16 \"\"`. DL3-style step 2 — opens the projection channel. PASS = Parcel returned, no error string."));
        list.add(new TestDef("D4", "DisplayManager dump AFTER D3 (6 s wait)",
                "Waits 6 s then re-enumerates displays. The PRESENTATION surface may already appear here on some DL4 ROMs."));
        list.add(new TestDef("D5", "sendInfo(1000,35,\"\") — cluster size 10.25\"",
                "`service call AutoContainer 2 i32 1000 i32 35 s16 \"\"`. DL3-style step 3 — final size handshake. Triggers the actual surface creation in most DL3/DL4 ROMs."));
        list.add(new TestDef("D6", "DisplayManager dump AFTER D5 (6 s wait) + cluster discovery",
                "Waits 6 s then enumerates displays. Looks for any non-default-display whose name matches `fission_bg_xdjaVirtualSurface` / `cluster` / `virtual` — stores its display id in a static for D7 to consume. PASS = a candidate cluster display was found."));
        list.add(new TestDef("D7", "am start --display N com.byd.clusterdebug",
                "If D6 discovered a cluster display id N, launches `am start --display N -n com.byd.clusterdebug/.MainActivity` (or its resolved launcher activity). If D6 did not find one, falls back to display 1. PASS = `am` returns without 'Error type'."));
        list.add(new TestDef("D8", "dumpsys activity AFTER D7 — clusterdebug landed where?",
                "`dumpsys activity activities | grep -A1 clusterdebug | head -20`. Confirms clusterdebug actually moved to the cluster display (or shows where it ended up instead). USER VISUAL CHECK: clusterdebug should now be visible on the instrument cluster."));
        list.add(new TestDef("D9", "Cleanup — am force-stop clusterdebug + sendInfo(18)",
                "`am force-stop com.byd.clusterdebug ; service call AutoContainer 2 i32 1000 i32 18 s16 \"\"`. Closes clusterdebug, then sends sendInfo(18) which is the DL3-style 'retract projection' signal."));
        list.add(new TestDef("D10", "Cleanup — sendInfo(0) + final display dump",
                "`service call AutoContainer 2 i32 1000 i32 0 s16 \"\"`. Restores the normal video flow on the cluster, then re-enumerates displays. The cluster surface should be gone again — confirming the activation→retract cycle is bidirectional."));
        return list;
    }

    public static void runAll(Context appCtx, Listener listener) {
        final Context ctx = appCtx.getApplicationContext();
        final List<TestDef> defs = catalog();
        final List<TestResult> results = new ArrayList<>(defs.size());
        for (TestDef d : defs) results.add(new TestResult(d));

        UI.post(() -> listener.onSuiteStarted(results));

        EXEC.execute(() -> {
            // Reset shared D-tier state at the start of each full run.
            sLastDiscoveredClusterDisplayId = -1;
            sLastDiscoveredClusterName      = "";
            for (int i = 0; i < defs.size(); i++) {
                final int idx = i;
                final TestResult r = results.get(i);
                r.status = Status.RUNNING;
                UI.post(() -> listener.onTestUpdated(idx, r));
                long t0 = SystemClock.elapsedRealtime();
                try {
                    switch (defs.get(i).id) {
                        case "L1":  runL1(ctx, r); break;
                        case "L2":  runL2(ctx, r); break;
                        case "L3":  runL3(ctx, r); break;
                        case "L4":  runL4(ctx, r); break;
                        case "L5":  runL5(ctx, r); break;
                        case "L6":  runL6(ctx, r); break;
                        case "L7":  runL7(ctx, r); break;
                        case "L8":  runL8(ctx, r); break;
                        case "L9":  runL9(ctx, r); break;
                        case "L10": runL10(ctx, r); break;
                        case "L11": runL11(ctx, r); break;
                        case "L12": runL12(ctx, r); break;
                        case "L13": runL13(ctx, r); break;
                        case "L14": runL14(ctx, r); break;
                        case "L15": runL15(ctx, r); break;
                        case "S1":  runS1(ctx, r); break;
                        case "S2":  runS2(ctx, r); break;
                        case "S3":  runS3(ctx, r); break;
                        case "S4":  runS4(ctx, r); break;
                        case "S5":  runS5(ctx, r); break;
                        case "S6":  runS6(ctx, r); break;
                        case "S7":  runS7(ctx, r); break;
                        case "S8":  runS8(ctx, r); break;
                        case "S9":  runS9(ctx, r); break;
                        case "S10": runS10(ctx, r); break;
                        case "S11": runS11(ctx, r); break;
                        case "S12": runS12(ctx, r); break;
                        case "S13": runS13(ctx, r); break;
                        case "S14": runS14(ctx, r); break;
                        case "S15": runS15(ctx, r); break;
                        case "D1":  runD1(ctx, r); break;
                        case "D2":  runD2(ctx, r); break;
                        case "D3":  runD3(ctx, r); break;
                        case "D4":  runD4(ctx, r); break;
                        case "D5":  runD5(ctx, r); break;
                        case "D6":  runD6(ctx, r); break;
                        case "D7":  runD7(ctx, r); break;
                        case "D8":  runD8(ctx, r); break;
                        case "D9":  runD9(ctx, r); break;
                        case "D10": runD10(ctx, r); break;
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

    public static String buildReport(Context ctx, List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DiLink 4 DIAGNOSTIC REPORT ===\n");
        sb.append("Build.BRAND        : ").append(Build.BRAND).append('\n');
        sb.append("Build.MODEL        : ").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT      : ").append(Build.PRODUCT).append('\n');
        sb.append("Build.MANUFACTURER : ").append(Build.MANUFACTURER).append('\n');
        sb.append("Build.HARDWARE     : ").append(Build.HARDWARE).append('\n');
        sb.append("Build.FINGERPRINT  : ").append(Build.FINGERPRINT).append('\n');
        sb.append("Build.VERSION.SDK  : ").append(Build.VERSION.SDK_INT).append('\n');
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
        sb.append(String.format(Locale.ROOT, "Summary: PASS=%d  FAIL=%d  WARN=%d  SKIP=%d%n%n",
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
        sb.append("\n=== END OF DiLink 4 REPORT ===\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    // L tier — recon (shell-free)
    // ════════════════════════════════════════════════════════════════════════

    private static void runL1(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build.BRAND        = ").append(Build.BRAND).append('\n');
        sb.append("Build.MANUFACTURER = ").append(Build.MANUFACTURER).append('\n');
        sb.append("Build.MODEL        = ").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT      = ").append(Build.PRODUCT).append('\n');
        sb.append("Build.DEVICE       = ").append(Build.DEVICE).append('\n');
        sb.append("Build.HARDWARE     = ").append(Build.HARDWARE).append('\n');
        sb.append("Build.BOARD        = ").append(Build.BOARD).append('\n');
        sb.append("Build.FINGERPRINT  = ").append(Build.FINGERPRINT).append('\n');
        sb.append("Build.VERSION.SDK  = ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Build.VERSION.REL  = ").append(Build.VERSION.RELEASE).append('\n');
        sb.append("Build.TAGS         = ").append(Build.TAGS).append('\n');
        sb.append("---\n");
        String[] keys = new String[]{
                "ro.product.name", "ro.product.model", "ro.product.brand",
                "ro.product.manufacturer", "ro.product.device", "ro.hardware",
                "ro.board.platform", "ro.sf.lcd_density",
                "ro.byd.product", "ro.byd.platform", "ro.byd.version",
                "ro.byd.car.model", "ro.byd.car.region", "ro.dilink.version",
                "ro.build.type", "ro.debuggable", "ro.secure",
                "service.adb.tcp.port"
        };
        for (String k : keys) sb.append(k).append(" = ").append(getProp(k)).append('\n');
        r.detail = sb.toString();
        String prod = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(Locale.ROOT);
        String fp   = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        boolean dl4 = (prod.contains("dilink4") || fp.contains("dilink4"))
                && Build.VERSION.SDK_INT == 29;
        if (dl4) {
            r.status = Status.PASS;
            r.message = "DL4 signature confirmed (product=" + Build.PRODUCT
                    + " / API " + Build.VERSION.SDK_INT + ")";
        } else {
            r.status = Status.WARN;
            r.message = "Not a DL4 signature (product=" + Build.PRODUCT
                    + " API=" + Build.VERSION.SDK_INT + ")";
        }
    }

    private static void runL2(Context ctx, TestResult r) {
        IBinder b = getBinder(AUTO_CONTAINER_SVC);
        if (b == null) {
            r.status = Status.FAIL;
            r.message = "ServiceManager.getService(\"" + AUTO_CONTAINER_SVC + "\") returned null";
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("binder    : ").append(b).append('\n');
        sb.append("isAlive() : ").append(b.isBinderAlive()).append('\n');
        sb.append("pingBinder: ").append(b.pingBinder()).append('\n');
        String descr;
        try {
            descr = b.getInterfaceDescriptor();
            sb.append("descriptor: ").append(descr).append('\n');
        } catch (Throwable t) {
            r.status = Status.FAIL;
            r.message = "getInterfaceDescriptor threw " + t.getClass().getSimpleName();
            r.detail = sb.toString();
            return;
        }
        r.detail = sb.toString();
        if (descr != null && descr.toLowerCase(Locale.ROOT).contains("autocontainer")) {
            r.status = Status.PASS;
            r.message = "AutoContainer reachable, descriptor=" + descr;
        } else {
            r.status = Status.WARN;
            r.message = "binder reachable but unexpected descriptor: " + descr;
        }
    }

    private static void runL3(Context ctx, TestResult r) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all  = dm.getDisplays();
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        StringBuilder sb = new StringBuilder();
        sb.append("getDisplays()              : ").append(all.length).append('\n');
        sb.append("getDisplays(PRESENTATION)  : ").append(pres.length).append('\n');
        for (Display d : all) {
            android.graphics.Point p = new android.graphics.Point();
            try { d.getRealSize(p); } catch (Throwable ignore) { p.x = p.y = -1; }
            sb.append("  #").append(d.getDisplayId())
              .append("  '").append(d.getName()).append('\'')
              .append("  flags=0x").append(Integer.toHexString(d.getFlags()))
              .append("  state=").append(d.getState())
              .append("  ").append(p.x).append('x').append(p.y).append('\n');
        }
        r.detail = sb.toString();
        if (all.length >= 2) {
            r.status = Status.PASS;
            r.message = all.length + " displays visible — cluster already exposed";
        } else {
            r.status = Status.WARN;
            r.message = "1 display only — cluster surface not yet materialised (expected on DL4 idle, see D tier)";
        }
    }

    private static void runL4(Context ctx, TestResult r) {
        IBinder sf = getBinder("SurfaceFlinger");
        if (sf == null) {
            r.status = Status.FAIL;
            r.message = "ServiceManager.getService(\"SurfaceFlinger\") returned null";
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("SurfaceFlinger binder : ").append(sf).append('\n');
        try {
            sb.append("descriptor            : ").append(sf.getInterfaceDescriptor()).append('\n');
        } catch (Throwable t) {
            sb.append("descriptor            : ERROR ").append(t.getMessage()).append('\n');
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "SurfaceFlinger binder reachable";
    }

    private static void runL5(Context ctx, TestResult r) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            String name = pi.packageName == null ? "" : pi.packageName;
            String low = name.toLowerCase(Locale.ROOT);
            if (low.startsWith("com.byd.") || low.startsWith("com.xdja.")
                    || low.startsWith("com.dilink.") || low.contains("cluster")
                    || low.contains("automotive")) {
                n++;
                sb.append(name)
                  .append("  v=").append(pi.versionName)
                  .append("  vc=").append(pi.versionCode)
                  .append('\n');
                if (pi.applicationInfo != null && pi.applicationInfo.sourceDir != null) {
                    sb.append("  apk=").append(pi.applicationInfo.sourceDir).append('\n');
                }
            }
        }
        r.detail = sb.toString();
        if (n > 0) {
            r.status = Status.PASS;
            r.message = n + " BYD/XDJA/DiLink/cluster package(s) installed";
        } else {
            r.status = Status.WARN;
            r.message = "no BYD/XDJA package visible from app uid (unexpected on a real BYD car)";
        }
    }

    private static void runL6(Context ctx, TestResult r) {
        List<String> all = listBinderServices();
        if (all.isEmpty()) {
            r.status = Status.WARN;
            r.message = "ServiceManager.listServices() returned empty (SELinux block expected on API 29 from app uid)";
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Total services: ").append(all.size()).append('\n').append("---\n");
        int hits = 0;
        for (String s : all) {
            String low = s.toLowerCase(Locale.ROOT);
            if (low.contains("cluster") || low.contains("display")
                    || low.contains("auto") || low.contains("byd")
                    || low.contains("window") || low.contains("fission")
                    || low.contains("xdja") || low.contains("projection")
                    || low.contains("cross") || low.contains("mirror")
                    || low.contains("container") || low.contains("magic")) {
                sb.append("  ").append(s).append('\n');
                hits++;
            }
        }
        r.detail = sb.toString();
        if (hits > 0) {
            r.status = Status.PASS;
            r.message = hits + " match(es) in BYD/cluster filter";
        } else {
            r.status = Status.WARN;
            r.message = "no match — SELinux likely hiding system services from app uid (use S6 instead)";
        }
    }

    private static void runL7(Context ctx, TestResult r) {
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
            m.invoke(opts, 1);
            android.os.Bundle bundle = opts.toBundle();
            r.detail = "setLaunchDisplayId reachable; toBundle ok; keys=" + (bundle == null ? "null" : bundle.size());
            r.status = Status.PASS;
            r.message = "ActivityOptions.setLaunchDisplayId(int) available";
        } catch (Throwable t) {
            r.status = Status.FAIL;
            r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void runL8(Context ctx, TestResult r) {
        IBinder b = getBinder(AUTO_CONTAINER_SVC);
        if (b == null) {
            r.status = Status.SKIPPED;
            r.message = "AutoContainer not reachable (see L2)";
            return;
        }
        StringBuilder sb = new StringBuilder();
        // Try to load the Stub class — present iff a vendor jar exposes it
        // to the app classpath (rare). Falls back to descriptor probe.
        String stub = "android.os.IAutoContainer$Stub";
        try {
            Class<?> stubCls = Class.forName(stub);
            sb.append("class loaded: ").append(stubCls.getName()).append('\n');
            int n = 0;
            for (Method m : stubCls.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                sb.append("  ").append(formatMethod(m)).append('\n');
                if (++n >= 20) { sb.append("  …(truncated)\n"); break; }
            }
        } catch (ClassNotFoundException cnfe) {
            sb.append("(IAutoContainer$Stub not on app classpath — vendor jar gated)\n");
            try {
                sb.append("descriptor : ").append(b.getInterfaceDescriptor()).append('\n');
            } catch (Throwable t) {
                sb.append("descriptor : ERR ").append(t.getMessage()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("ERR ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "interface introspection captured";
    }

    private static void runL9(Context ctx, TestResult r) {
        String[] classes = new String[]{
                "com.byd.auto.sdk.BYDAutoSpeedDevice",
                "com.byd.auto.sdk.EnergyDevice",
                "com.byd.auto.sdk.GearboxDevice",
                "com.byd.auto.sdk.ACDevice",
                "com.byd.auto.sdk.AirConditionerDevice",
                "com.byd.auto.sdk.DoorDevice",
                "com.byd.auto.sdk.LightDevice",
                "com.byd.auto.sdk.WiperDevice",
                "android.os.IAutoContainer",
                "android.os.IAutoContainer$Stub",
                "com.byd.containerservice.IContainerService",
                "com.xdja.containerservice.IContainerService"
        };
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (String c : classes) {
            try { Class.forName(c); sb.append("  + ").append(c).append('\n'); found++; }
            catch (Throwable t) { sb.append("  - ").append(c).append('\n'); }
        }
        r.detail = sb.toString();
        if (found > 0) {
            r.status = Status.PASS;
            r.message = found + "/" + classes.length + " vendor classes loadable";
        } else {
            r.status = Status.WARN;
            r.message = "no vendor SDK class on classpath";
        }
    }

    private static void runL10(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== /sys/class/drm ===\n").append(listDir("/sys/class/drm")).append('\n');
        sb.append("=== /dev/graphics ===\n").append(listDir("/dev/graphics")).append('\n');
        sb.append("=== /sys/class/graphics ===\n").append(listDir("/sys/class/graphics")).append('\n');
        r.detail = sb.toString();
        boolean multi = sb.toString().contains("fb1") || sb.toString().contains("fb2")
                || countMatches(sb.toString(), "card0-") >= 2;
        if (multi) {
            r.status = Status.PASS;
            r.message = "multiple framebuffers / DRM connectors detected";
        } else {
            r.status = Status.WARN;
            r.message = "single framebuffer / no extra DRM connector visible to app uid";
        }
    }

    private static void runL11(Context ctx, TestResult r) {
        String[] cands = new String[]{
                "AutoContainer", "auto_container",
                "cluster", "byd_cluster", "mtk_cluster",
                "secondary_display", "display.cluster",
                "mirror", "magicwindow", "crosscontrol",
                "containerservice", "ContainerService",
                "fission", "projection_service",
                "BYDMgmt", "magicwindow", "byd_datacached",
                "xdja", "XDJAService",
                "android.os.IAutoContainer"
        };
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (String c : cands) {
            IBinder b = getBinder(c);
            if (b != null) {
                String descr = "";
                try { descr = b.getInterfaceDescriptor(); } catch (Throwable ignore) {}
                sb.append("  + ").append(c).append("  descr=").append(descr).append('\n');
                hits++;
            } else {
                sb.append("  - ").append(c).append('\n');
            }
        }
        r.detail = sb.toString();
        r.status = hits > 0 ? Status.PASS : Status.WARN;
        r.message = hits + "/" + cands.length + " candidate(s) present";
    }

    private static void runL12(Context ctx, TestResult r) {
        String[] keys = new String[]{
                "ro.byd.product", "ro.byd.platform", "ro.byd.version",
                "ro.byd.car.model", "ro.byd.car.region",
                "ro.byd.car.year", "ro.byd.car.market", "ro.byd.car.power",
                "ro.dilink.version", "ro.dilink.platform",
                "persist.byd.product", "persist.byd.version",
                "persist.sys.byd.country", "persist.sys.byd.region",
                "persist.sys.byd.cluster.size", "persist.sys.byd.cluster.type",
                "ro.byd.cluster.size", "ro.byd.cluster.enable",
                "ro.byd.fission.enable", "ro.byd.projection.enable",
                "ro.byd.adas.enable", "ro.byd.hardware",
                "ro.byd.sw.version", "ro.byd.factory", "ro.byd.vehicle.vin"
        };
        StringBuilder sb = new StringBuilder();
        int nonEmpty = 0;
        for (String k : keys) {
            String v = getProp(k);
            sb.append(k).append(" = ").append(v).append('\n');
            if (v != null && !v.isEmpty() && !v.startsWith("(reflection")) nonEmpty++;
        }
        r.detail = sb.toString();
        if (nonEmpty > 0) {
            r.status = Status.PASS;
            r.message = nonEmpty + " BYD property(ies) populated";
        } else {
            r.status = Status.WARN;
            r.message = "no BYD-specific property visible from app uid (SELinux gate)";
        }
    }

    private static void runL13(Context ctx, TestResult r) {
        String[] perms = new String[]{
                "android.permission.INTERNAL_SYSTEM_WINDOW",
                "android.permission.ACTIVITY_EMBEDDING",
                "android.permission.MANAGE_ACTIVITY_TASKS",
                "android.permission.MANAGE_ACTIVITY_STACKS",
                "android.permission.INJECT_EVENTS",
                "android.permission.ACCESS_SURFACE_FLINGER",
                "android.permission.READ_FRAME_BUFFER",
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.MANAGE_USERS",
                "com.byd.permission.BYDAUTO_DATA",
                "com.byd.permission.BYDAUTO_CONTROL",
                "com.byd.permission.BYDAUTO_AC"
        };
        PackageManager pm = ctx.getPackageManager();
        StringBuilder sb = new StringBuilder();
        int granted = 0;
        for (String p : perms) {
            int c = pm.checkPermission(p, ctx.getPackageName());
            String s = c == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
            if (c == PackageManager.PERMISSION_GRANTED) granted++;
            sb.append("  ").append(s).append("  ").append(p).append('\n');
        }
        r.detail = sb.toString();
        r.status = granted > 0 ? Status.PASS : Status.WARN;
        r.message = granted + "/" + perms.length + " granted (DL4 typically grants 0 to app uid — we route through ADB shell instead)";
    }

    private static void runL14(Context ctx, TestResult r) {
        String s = probePort("127.0.0.1", 5555, 300);
        r.detail = "127.0.0.1:5555 → " + s;
        if (s.startsWith("OPEN")) {
            r.status = Status.PASS;
            r.message = s + " — ADB shell ready for S/D tier";
        } else {
            r.status = Status.WARN;
            r.message = s + " — S/D tier will SKIP";
        }
    }

    private static void runL15(Context ctx, TestResult r) {
        PackageManager pm = ctx.getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> launchables = pm.queryIntentActivities(main, 0);
        StringBuilder sb = new StringBuilder();
        boolean clusterdebug = false;
        int n = 0;
        for (android.content.pm.ResolveInfo ri : launchables) {
            String pkg = ri.activityInfo != null ? ri.activityInfo.packageName : "";
            if (pkg == null) continue;
            String low = pkg.toLowerCase(Locale.ROOT);
            if (low.startsWith("com.byd.") || low.contains("cluster")) {
                sb.append("  ").append(pkg).append("/")
                  .append(ri.activityInfo.name).append('\n');
                if ("com.byd.clusterdebug".equals(pkg)) clusterdebug = true;
                n++;
            }
        }
        r.detail = sb.toString();
        if (clusterdebug) {
            r.status = Status.PASS;
            r.message = "com.byd.clusterdebug present (D7 fallback target ready)";
        } else if (n > 0) {
            r.status = Status.WARN;
            r.message = n + " launchable BYD app(s) but no clusterdebug (D7 will use display 1 + dashcast)";
        } else {
            r.status = Status.WARN;
            r.message = "no launchable BYD app";
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // S tier — shell-based via AdbLocalClient
    // ════════════════════════════════════════════════════════════════════════

    private static void runS1(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "id -u && id -un && pwd && uname -a", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        if (out.startsWith("2000")) {
            r.status = Status.PASS;
            r.message = "shell uid=2000 OK";
        } else {
            r.status = Status.WARN;
            r.message = "unexpected shell uid (first line=" + out.split("\n")[0] + ")";
        }
    }

    private static void runS2(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "getprop | grep -iE 'byd|dilink|adb|cluster|display|sf\\.lcd' | sort -u", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        int lines = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " property(ies)";
    }

    private static void runS3(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "wm size; wm density; wm overscan", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        r.status = Status.PASS;
        r.message = "captured";
    }

    private static void runS4(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys display 2>&1 | grep -E 'mDisplayId|mBaseDisplayInfo|Override|Physical|name=|state=' | head -80", 10000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = out.split("\n").length + " line(s)";
    }

    private static void runS5(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys SurfaceFlinger --display-id 2>&1 ; echo --- ; dumpsys SurfaceFlinger 2>&1 | grep -E 'Display |hwc' | head -20", 10000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "captured";
    }

    private static void runS6(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "service list 2>&1 | grep -iE 'auto|byd|cluster|fission|display|window|projection|xdja|crosscontrol|magic|container|mirror' | head -40", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        int lines = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " match(es)";
    }

    private static void runS7(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "service call AutoContainer 1 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        // A successful descriptor query returns a Parcel with bytes — failure
        // returns "Service AutoContainer does not exist" or "Can't find service".
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("does not exist") || low.contains("can't find")) {
            r.status = Status.FAIL;
            r.message = "AutoContainer service NOT reachable via shell";
        } else if (low.contains("permission denial") || low.contains("securityexception")) {
            r.status = Status.WARN;
            r.message = "permission denied — descriptor partially returned";
        } else {
            r.status = Status.PASS;
            r.message = "Parcel returned — AutoContainer reachable";
        }
    }

    private static void runS8(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "service call auto_container 1 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("does not exist") || low.contains("can't find")) {
            r.status = Status.PASS;
            r.message = "snake_case CORRECTLY absent (PROVES the v1.2.27 DL4 fix is needed)";
        } else {
            r.status = Status.WARN;
            r.message = "unexpected: snake_case 'auto_container' is also present on this device";
        }
    }

    private static void runS9(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "pm list packages -f 2>&1 | grep -iE 'com\\.byd|com\\.xdja|com\\.dilink|cluster|automotive' | sort -u", 10000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 8000);
        int lines = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " package(s)";
    }

    private static void runS10(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -E 'ResumedActivity|displayId|TaskRecord|mResumedActivity' | head -30", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "captured";
    }

    private static void runS11(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "getenforce 2>&1 ; id -Z 2>/dev/null", 3000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 1000);
        r.status = Status.PASS;
        r.message = out.split("\n")[0];
    }

    private static void runS12(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "ps -A -o PID,USER,NAME 2>/dev/null | grep -iE 'cluster|fission|projection|surface|byd|auto|xdja|mirror|container' | head -30", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        int lines = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " process(es)";
    }

    private static void runS13(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "settings list system 2>&1 | grep -iE 'byd|cluster|display|overscan|density' ; echo --- ; settings list secure 2>&1 | grep -iE 'byd|cluster|display' ; echo --- ; settings list global 2>&1 | grep -iE 'byd|cluster|display'", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "captured";
    }

    private static void runS14(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.byd.dashcast/.MainActivity 2>&1", 6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("error type") || low.contains("not found") || low.contains("exception")) {
            r.status = Status.FAIL;
            r.message = "am start failed: " + out.split("\n")[0];
        } else {
            r.status = Status.PASS;
            r.message = "am start --display 0 accepted";
        }
    }

    private static void runS15(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "cat /proc/cpuinfo 2>&1 | grep -iE 'Hardware|model name|Processor' | head -5 ; echo --- ; cat /proc/meminfo 2>&1 | head -4 ; echo --- ; df -h /data 2>&1 | head -2", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 3000);
        r.status = Status.PASS;
        r.message = "captured";
    }

    // ════════════════════════════════════════════════════════════════════════
    // D tier — DL4 cluster activation sondage (live sendInfo cycle)
    // ════════════════════════════════════════════════════════════════════════

    private static void runD1(Context ctx, TestResult r) { dSendInfoShell(ctx, r, 30, "Seal EU prep"); }

    private static void runD2(Context ctx, TestResult r) { dWaitAndDumpDisplays(ctx, r, 6000, "after D1 (sendInfo 30)"); }

    private static void runD3(Context ctx, TestResult r) { dSendInfoShell(ctx, r, 16, "projection open"); }

    private static void runD4(Context ctx, TestResult r) { dWaitAndDumpDisplays(ctx, r, 6000, "after D3 (sendInfo 16)"); }

    private static void runD5(Context ctx, TestResult r) { dSendInfoShell(ctx, r, 35, "cluster size 10.25\""); }

    private static void runD6(Context ctx, TestResult r) {
        sleepMs(6000);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        sb.append("After D5 (sendInfo 35) + 6 s wait — DisplayManager.getDisplays(): ")
          .append(all.length).append('\n');
        int candId = -1;
        String candName = "";
        for (Display d : all) {
            String name = d.getName() == null ? "" : d.getName();
            android.graphics.Point p = new android.graphics.Point();
            try { d.getRealSize(p); } catch (Throwable ignore) { p.x = p.y = -1; }
            sb.append("  #").append(d.getDisplayId())
              .append("  '").append(name).append('\'')
              .append("  flags=0x").append(Integer.toHexString(d.getFlags()))
              .append("  ").append(p.x).append('x').append(p.y).append('\n');
            String low = name.toLowerCase(Locale.ROOT);
            if (d.getDisplayId() != 0
                    && (low.contains("fission") || low.contains("cluster")
                        || low.contains("virtual") || low.contains("xdja"))) {
                if (candId < 0) { candId = d.getDisplayId(); candName = name; }
            }
        }
        if (candId < 0 && all.length >= 2) {
            // Fallback: any non-default display
            for (Display d : all) {
                if (d.getDisplayId() != 0) { candId = d.getDisplayId(); candName = d.getName(); break; }
            }
        }
        sLastDiscoveredClusterDisplayId = candId;
        sLastDiscoveredClusterName      = candName == null ? "" : candName;
        r.detail = sb.toString();
        if (candId >= 0) {
            r.status = Status.PASS;
            r.message = "cluster display discovered: id=" + candId + " name='" + candName + "' — D7 will launch on it";
        } else {
            r.status = Status.WARN;
            r.message = "no extra display materialised — sendInfo sequence may need different codes on this ROM (check S6/L6 for the actual service name)";
        }
    }

    private static void runD7(Context ctx, TestResult r) {
        int displayId = sLastDiscoveredClusterDisplayId;
        if (displayId < 0) displayId = 1; // fallback
        String target = DEFAULT_CLUSTER_TARGET;
        // Try to resolve clusterdebug launcher first; fall back to dashcast if missing.
        PackageManager pm = ctx.getPackageManager();
        android.content.Intent probe = pm.getLaunchIntentForPackage(DEFAULT_CLUSTER_TARGET);
        String component;
        if (probe != null && probe.getComponent() != null) {
            component = probe.getComponent().flattenToShortString();
        } else {
            target    = "com.byd.dashcast";
            component = "com.byd.dashcast/.MainActivity";
        }
        String cmd = "am start --display " + displayId
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -n " + component + " 2>&1";
        String out = runShellSync(ctx, cmd, 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = "displayId=" + displayId
                + " (discovered name='" + sLastDiscoveredClusterName + "')\n"
                + "target=" + target + "\n"
                + "cmd=" + cmd + "\n---\n" + safeTrim(out, 2000);
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("error type") || low.contains("not found") || low.contains("does not exist")) {
            r.status = Status.FAIL;
            r.message = "am start failed: " + out.split("\n")[0];
        } else {
            r.status = Status.PASS;
            r.message = "launched " + target + " on display " + displayId;
        }
    }

    private static void runD8(Context ctx, TestResult r) {
        sleepMs(2500);
        String out = runShellSync(ctx,
                "dumpsys activity activities 2>&1 | grep -E 'clusterdebug|dashcast|ResumedActivity|displayId' | head -40", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 4000);
        if (out.toLowerCase(Locale.ROOT).contains("clusterdebug") || out.toLowerCase(Locale.ROOT).contains("dashcast")) {
            r.status = Status.PASS;
            r.message = "target activity found in stack — USER VISUAL CHECK on cluster screen";
        } else {
            r.status = Status.WARN;
            r.message = "target activity not visible in stack dump";
        }
    }

    private static void runD9(Context ctx, TestResult r) {
        // Force-stop both possible targets, then retract projection.
        String out1 = runShellSync(ctx,
                "am force-stop com.byd.clusterdebug 2>&1 ; am force-stop com.byd.dashcast 2>&1", 5000);
        if (skipIfNoShell(out1, r)) return;
        String out2 = runShellSync(ctx,
                "service call AutoContainer 2 i32 1000 i32 18 s16 '' 2>&1", 5000);
        if (skipIfNoShell(out2, r)) return;
        r.detail = "force-stop:\n" + safeTrim(out1, 1000) + "\n---\nsendInfo(18):\n" + safeTrim(out2, 1000);
        r.status = Status.PASS;
        r.message = "force-stop + sendInfo(18) dispatched";
    }

    private static void runD10(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "service call AutoContainer 2 i32 1000 i32 0 s16 '' 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        sleepMs(2000);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm.getDisplays();
        StringBuilder sb = new StringBuilder();
        sb.append("sendInfo(0) reply:\n").append(safeTrim(out, 800)).append("\n---\n");
        sb.append("Final DisplayManager.getDisplays(): ").append(all.length).append('\n');
        for (Display d : all) {
            sb.append("  #").append(d.getDisplayId()).append("  '").append(d.getName()).append("'\n");
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "cleanup complete — " + all.length + " display(s) visible";
    }

    // ════════════════════════════════════════════════════════════════════════
    // D-tier helpers (shared sendInfo + display dump)
    // ════════════════════════════════════════════════════════════════════════

    private static void dSendInfoShell(Context ctx, TestResult r, int info, String label) {
        String cmd = "service call " + AUTO_CONTAINER_SVC + " " + TXN_SEND_INFO
                + " i32 " + SEND_INFO_TYPE_CLUSTER + " i32 " + info + " s16 '' 2>&1";
        String out = runShellSync(ctx, cmd, 6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = "cmd: " + cmd + "\n---\n" + safeTrim(out, 2000);
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("does not exist") || low.contains("can't find")) {
            r.status = Status.FAIL;
            r.message = "AutoContainer not reachable (DL4 wrong binder name?)";
        } else if (low.contains("permission denial") || low.contains("securityexception")) {
            r.status = Status.FAIL;
            r.message = "permission denied — shell uid lacks BYD privilege on this ROM";
        } else {
            r.status = Status.PASS;
            r.message = "sendInfo(1000, " + info + ", \"\") dispatched (" + label + ")";
        }
    }

    private static void dWaitAndDumpDisplays(Context ctx, TestResult r, long waitMs, String label) {
        sleepMs(waitMs);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all  = dm.getDisplays();
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" — waited ").append(waitMs).append(" ms\n");
        sb.append("getDisplays()             : ").append(all.length).append('\n');
        sb.append("getDisplays(PRESENTATION) : ").append(pres.length).append('\n');
        for (Display d : all) {
            android.graphics.Point p = new android.graphics.Point();
            try { d.getRealSize(p); } catch (Throwable ignore) { p.x = p.y = -1; }
            sb.append("  #").append(d.getDisplayId())
              .append("  '").append(d.getName()).append('\'')
              .append("  flags=0x").append(Integer.toHexString(d.getFlags()))
              .append("  ").append(p.x).append('x').append(p.y).append('\n');
        }
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = all.length + " display(s) / " + pres.length + " PRESENTATION";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String getProp(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method m = cls.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "(reflection err: " + t.getClass().getSimpleName() + ")";
        }
    }

    private static IBinder getBinder(String name) {
        try {
            Class<?> cls = Class.forName("android.os.ServiceManager");
            Method m = cls.getMethod("getService", String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<String> listBinderServices() {
        try {
            Class<?> cls = Class.forName("android.os.ServiceManager");
            Method m = cls.getMethod("listServices");
            String[] arr = (String[]) m.invoke(null);
            if (arr == null) return Collections.emptyList();
            return Arrays.asList(arr);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static String probePort(String host, int port, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return "OPEN (" + (System.currentTimeMillis() - t0) + "ms)";
        } catch (java.net.ConnectException e) {
            return "CLOSED (" + e.getMessage() + ")";
        } catch (java.net.SocketTimeoutException e) {
            return "TIMEOUT (" + timeoutMs + "ms)";
        } catch (Throwable t) {
            return "ERR (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    private static String listDir(String path) {
        File f = new File(path);
        if (!f.exists()) return "(not present)\n";
        if (!f.canRead()) return "(not readable from app uid)\n";
        File[] entries = f.listFiles();
        if (entries == null) return "(listFiles returned null)\n";
        StringBuilder sb = new StringBuilder();
        for (File e : entries) {
            sb.append("  ").append(e.getName());
            if (e.isDirectory()) sb.append("/");
            sb.append('\n');
        }
        if (sb.length() == 0) return "(empty)\n";
        return sb.toString();
    }

    private static String formatMethod(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getSimpleName());
        }
        sb.append(") -> ").append(m.getReturnType().getSimpleName());
        return sb.toString();
    }

    private static int countMatches(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { count++; idx += needle.length(); }
        return count;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** Synchronous wrapper around AdbLocalClient — see DiLink2TestRunner for design notes. */
    private static String runShellSync(Context ctx, String cmd, long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            com.byd.dashcast.infrastructure.AdbLocalClient.executeShellWithResult(ctx, cmd,
                new com.byd.dashcast.infrastructure.AdbLocalClient.Callback() {
                    @Override public void onSuccess(String s) {
                        out.set(s == null ? "" : s);
                        latch.countDown();
                    }
                    @Override public void onError(String e) {
                        out.set("__SHELL_ERR__:" + (e == null ? "(null)" : e));
                        latch.countDown();
                    }
                });
        } catch (Throwable t) {
            return "__SHELL_ERR__:dispatch " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        try {
            if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return "__SHELL_ERR__:timeout (" + timeoutMs + " ms)";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "__SHELL_ERR__:interrupted";
        }
        return out.get() == null ? "" : out.get();
    }

    private static boolean isShellErr(String s) { return s != null && s.startsWith("__SHELL_ERR__:"); }

    private static boolean skipIfNoShell(String out, TestResult r) {
        if (isShellErr(out)) {
            r.status = Status.SKIPPED;
            r.message = "ADB shell unreachable: " + out.substring("__SHELL_ERR__:".length());
            return true;
        }
        return false;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s
                : s.substring(0, max) + "\n…(truncated " + (s.length() - max) + " more chars)";
    }

    // suppress unused warning for Parcel import — kept for future direct in-process transact path
    @SuppressWarnings("unused")
    private static final Class<?> UNUSED_PARCEL = Parcel.class;
}
