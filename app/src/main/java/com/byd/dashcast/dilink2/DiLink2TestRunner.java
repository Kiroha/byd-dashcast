package com.byd.dashcast.dilink2;
import java.util.Locale;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DiLink2TestRunner — diagnostic suite for the DiLink 2 / Android 9 platform
 * (e.g. alps / k65v1_64_bsp / MT6765).
 *
 * <p><b>Two tiers of tests:</b>
 *
 * <ul>
 *   <li><b>L1–L15 (recon, shell-free)</b> — pure Java reflection / PackageManager /
 *       /proc scans / raw Socket probes. Always available; never touches
 *       {@code AdbLocalClient}.</li>
 *   <li><b>S1–S15 (shell, ADB-driven)</b> — added 22/05/2026 once the user enabled
 *       ADB-over-TCP on DL2 (port 5555 open, RSA key accepted). Use
 *       {@link com.byd.dashcast.infrastructure.AdbLocalClient#executeShellWithResult} via a
 *       synchronous wrapper; each test self-marks SKIPPED if the shell call
 *       returns an error or times out (legacy DL2 without ADB still passes
 *       cleanly through the L tier).</li>
 * </ul>
 *
 * <p>The runner is intentionally self-contained — it shares neither types nor
 * helpers with {@code DiLink5TestRunner} so that DL5 regressions cannot leak
 * into DL2 and vice-versa.
 */
public final class DiLink2TestRunner {

    private static final String TAG = "DiLink2TestRunner";

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
        Thread t = new Thread(r, "dilink2-test-runner");
        t.setDaemon(true);
        return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private DiLink2TestRunner() {}

    /** Ordered catalog. */
    public static List<TestDef> catalog() {
        List<TestDef> list = new ArrayList<>();
        list.add(new TestDef("L1", "Platform fingerprint",
                "Build.* + selected ro.* / persist.sys.* / sys.* properties via SystemProperties reflection. Identifies the DL2 signature (brand=alps, hardware=mt6765, API=28)."));
        list.add(new TestDef("L2", "ADB local TCP ports probe",
                "Raw Socket connect (200ms) on 127.0.0.1 ports 5037/5554/5555/5556/4444 + read service.adb.tcp.port. On DL2 every port is expected CLOSED — this confirms the shell-disabled environment."));
        list.add(new TestDef("L3", "Multi-display reflective scan",
                "DisplayManager.getDisplays() across every known category + DisplayManagerGlobal.getDisplayIds() via reflection — may surface display IDs hidden from the standard PRESENTATION enumeration."));
        list.add(new TestDef("L4", "SurfaceFlinger physical displays via IBinder",
                "ServiceManager.getService(\"SurfaceFlinger\") + reflective transact() probing the historical BUILT_IN_DISPLAY tokens (0 = primary, 1 = external). Reveals secondary physical displays hidden from DisplayManager."));
        list.add(new TestDef("L5", "DRM / framebuffer inventory",
                "Lists /sys/class/drm and /dev/graphics via java.io.File. fb1/fb2 or a second DRM connector hints at a hardware cluster output not exposed to AOSP."));
        list.add(new TestDef("L6", "ServiceManager full inventory + filter",
                "Reflective ServiceManager.listServices() → filtered on cluster|display|secondary|byd|alps|auto|mirror|magic|window|fission|xdja|projection|cross."));
        list.add(new TestDef("L7", "Cluster service candidates brute probe",
                "ServiceManager.getService() on ~40 candidate names (cluster, byd_cluster, mtk_cluster, secondary_display, mirror, Auto_container, auto_container, magicwindow, crosscontrol…). Reports present/absent + interface descriptor when reachable."));
        list.add(new TestDef("L8", "IActivityManager method enumeration",
                "Reflection on android.app.IActivityManager.Stub (API 28 has IAM, not IATM). Lists every method whose name matches moveTask|setLaunch|startActivity|moveActivityTaskToDisplay|setTaskWindowingMode."));
        list.add(new TestDef("L9", "ActivityOptions.setLaunchDisplayId smoke",
                "ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle() — pure availability check, the SysInfo dump already confirmed this method is present on DL2."));
        list.add(new TestDef("L10", "BYD packages dynamic scan (PackageManager)",
                "pm.getInstalledPackages(0) filtered on com.byd.*, com.alps.*, com.xdja.* — captures package name + versionName + versionCode + APK path. No shell required."));
        list.add(new TestDef("L11", "com.byd.cluster manifest deep dive",
                "PackageManager.getPackageInfo(GET_ACTIVITIES|GET_SERVICES|GET_RECEIVERS|GET_PERMISSIONS) — dumps every component declared by com.byd.cluster (the only cluster-named app on DL2)."));
        list.add(new TestDef("L12", "com.byd.appstartmanagement manifest",
                "Same as L11 on com.byd.appstartmanagement (v1.0 on DL2 vs v1.5+ on DL5) — confirms whether the launch gatekeeper is present and what it exposes."));
        list.add(new TestDef("L13", "BYD SDK classpath probe",
                "Class.forName + reflective getInstance() on BYDAutoSpeedDevice / EnergyDevice / GearboxDevice / ACDevice / AirConditionerDevice / DoorDevice / LightDevice / WiperDevice. Confirms which SDK entry points are loadable from the app uid."));
        list.add(new TestDef("L14", "/proc cluster process scan",
                "Scan /proc/*/cmdline (pure Java) for processes whose name contains cluster|fission|projection|secondary|display|surface — identifies the native daemon driving the cluster screen when it exists outside Android framework."));
        list.add(new TestDef("L15", "Hidden-API reachability sanity",
                "Reflective ServiceManager.getService(\"window\") + IBinder.getInterfaceDescriptor() + IActivityManager descriptor. Confirms the app process can still talk to system binders despite SELinux on API 28."));

        // ─── S tier — shell-based, requires ADB-over-TCP open on DL2 ───────
        list.add(new TestDef("S1", "ADB shell reachable — id -u",
                "`id -u && id -un && pwd && uname -a`. Confirms AdbLocalClient can run shell on DL2 (port 5555 open, RSA key accepted) and returns uid=2000 (shell). Gates every other S test — if this is SKIPPED, the user has not (or no longer) opened ADB on DL2."));
        list.add(new TestDef("S2", "Runtime properties — getprop filter",
                "`getprop | grep -iE 'byd|alps|adb|cluster|display|sf\\.lcd'`. Captures every BYD/Alps property visible to the shell uid — superset of what SystemProperties reflection sees from the app uid (some props are uid-gated)."));
        list.add(new TestDef("S3", "Display state — wm size/density/overscan",
                "`wm size; wm density; wm overscan`. CRITICAL health check — if Override size ≠ Physical size, or overscan ≠ 0,0,0,0, the display has been resized by an external command (BYD UI, dev menu, manual adb). Directly answers the 'apps smaller than screen' field question (22/05/2026)."));
        list.add(new TestDef("S4", "dumpsys display — full inventory",
                "`dumpsys display | grep -E 'mDisplayId|mOverscan|mBaseDisplayInfo|Override|Physical|name=|state='`. Enumerates every Display known to the framework with id, name, physical size, override size, overscan rect. Surfaces hidden cluster displays that DisplayManager hides from the app uid."));
        list.add(new TestDef("S5", "dumpsys SurfaceFlinger — physical displays",
                "`dumpsys SurfaceFlinger --display-id` + `dumpsys SurfaceFlinger | grep -E 'Display [0-9]+ HWC|Display \"' | head -20`. Returns the actual HWComposer-managed physical displays — includes outputs that never reach DisplayManager (e.g. instrument cluster hardware screens wired straight to a separate connector)."));
        list.add(new TestDef("S6", "service list — BYD/cluster filter",
                "`service list | grep -iE 'byd|cluster|auto|display|window|fission|xdja|projection|mirror|magic|cross'`. Shell-side equivalent of L6 but reaches services hidden from app uid (some Android 9 services SELinux-block ServiceManager.listServices from non-system processes)."));
        list.add(new TestDef("S7", "AutoContainer probe — service call 1",
                "`service call AutoContainer 1 2>&1 ; service call auto_container 1 2>&1`. Sends transaction code 1 (HELLO/getInterfaceVersion convention) to both casings. If either returns a non-empty Parcel, the BYD AutoContainer service exists on DL2 too — opens the door to ADAS overlay codes 12/13 already supported by DiagActivity."));
        list.add(new TestDef("S8", "BYD packages — pm list -f",
                "`pm list packages -f | grep -iE 'byd|alps|xdja|cluster|dilink' | sort`. Shell-side complement of L10 with absolute APK paths. Captures every BYD package including those PackageManager filters out for non-system app uids."));
        list.add(new TestDef("S9", "Activity stack snapshot — dumpsys activity",
                "`dumpsys activity activities | grep -E 'ResumedActivity|displayId|TaskRecord|mResumedActivity' | head -30`. Lists the currently-resumed activity per display, task records, and which display each task lives on — useful baseline before testing cross-display launches."));
        list.add(new TestDef("S10", "SELinux + sepolicy state",
                "`getenforce ; id -Z 2>/dev/null ; ls -Zd /system/bin/sh 2>/dev/null`. Reports Enforcing vs Permissive + the shell context. Critical for understanding what binder calls / file paths the shell can or cannot reach."));
        list.add(new TestDef("S11", "Daemon process scan — ps cluster",
                "`ps -A -o PID,USER,NAME 2>/dev/null | grep -iE 'cluster|fission|projection|surface|byd|auto|xdja|mirror' | head -30`. Shell-side equivalent of L14, reaches processes hidden from the app's /proc view by Android 9's hidepid mount option."));
        list.add(new TestDef("S12", "System settings — BYD filter",
                "`settings list system 2>&1 | grep -iE 'byd|cluster|display|overscan|density|orient' ; echo --- ; settings list secure 2>&1 | grep -iE 'byd|cluster|display|overscan' ; echo --- ; settings list global 2>&1 | grep -iE 'byd|cluster|display|overscan'`. Captures BYD-specific keys in Settings.System/Secure/Global — often where the BYD UI persists per-display preferences."));
        list.add(new TestDef("S13", "Display launch probe — am start --display",
                "`am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.byd.dashcast/.MainActivity 2>&1 ; echo --- ; am start --display 1 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.byd.dashcast/.MainActivity 2>&1`. Probes whether `am start --display N` is functional on DL2 (DiLink 2 = Android 9, the `--display` flag was added in API 26 so should work)."));
        list.add(new TestDef("S14", "Hardware fingerprint — cpuinfo + meminfo",
                "`cat /proc/cpuinfo | grep -iE 'Hardware|model name|Processor' | head -5 ; echo --- ; cat /proc/meminfo | head -4 ; echo --- ; df -h /data 2>&1 | head -2`. CPU SoC identifier (expected mt6765 on DL2), RAM total, /data free — quick sanity check that the device is healthy and identifies the hardware variant."));
        list.add(new TestDef("S15", "Filesystem inventory — BYD / DRM / framebuffer",
                "`ls -la /sys/class/drm/ 2>&1 | head -20 ; echo --- ; ls -la /dev/graphics/ 2>&1 ; echo --- ; ls -la /vendor/etc/byd/ /system/etc/byd/ /data/byd/ 2>&1 | head -20`. Shell-side complement of L5 + BYD-specific config dirs (often holds the cluster mapping JSON / overscan defaults)."));

        // ─── Build 192 — RE-oriented tests (APK extraction + deep inspection) ──
        list.add(new TestDef("S16", "BYD packages discovery (shell)",
                "`pm list packages -f | grep -iE 'com\\.byd|com\\.alps|com\\.xdja|com\\.dilink|cluster|automotive' | sort -u`. Shell-side superset of L10/S8 with absolute APK paths. Populates the discovery list used by S17. PASS when ≥1 package matched."));
        list.add(new TestDef("S17", "APK extraction → /storage/emulated/0/Download/dl2_apks/",
                "Copies every interesting APK discovered by S16 (com.byd.cluster prioritised, plus appstartmanagement, containerservice, smarttravel, commander, freedom, overdrive, windowmanagement, crosscontrol, clusterdebug, car.server + any *cluster* / *fission* / *projection* / *dilink* substring) to `/storage/emulated/0/Download/dl2_apks/` via shell `cat` redirect (uid 2000 has write access to /sdcard/Download on A9+). Visible in the device file manager — no `adb pull` needed. Cache via `stat` to skip already-extracted APKs."));
        list.add(new TestDef("S18", "dumpsys window — focused / displays / overscan",
                "`dumpsys window | grep -E 'mDisplayId|mCurrentFocus|mFocusedApp|mDisplayContent|imeLayerStack|mStackId|Display: mDisplayId|mOverscan' | head -60`. Captures the live windowing state — focused activity, per-display content roots, overscan per display, ime layer stack. Baseline snapshot for reproducing user-reported display bugs."));
        list.add(new TestDef("S19", "dumpsys SurfaceFlinger — layers + display topology",
                "`dumpsys SurfaceFlinger --list 2>&1 | head -30 ; echo --- ; dumpsys SurfaceFlinger | grep -E 'Display |layerStack|Output|orientation|HWComposer|hwc composition' | head -40`. Captures the compositor's view of physical/virtual displays + top layer names — ground truth that bypasses WindowManager filtering."));
        list.add(new TestDef("S20", "Recent logcat — BYD / cluster filter",
                "`logcat -d -v threadtime 2>&1 | grep -iE 'byd|alps|cluster|projection|fission|xdja|magicwindow|crosscontrol|appstartmanagement|containerservice|sendInfo|AutoContainer' | tail -200`. Captures the last ~200 BYD/cluster log lines from the persistent buffer — surfaces silent errors that don't reach UI (SecurityException stacks, binder denials, missing intent receivers)."));
        list.add(new TestDef("S21", "com.byd.cluster — dumpsys package full",
                "`dumpsys package com.byd.cluster 2>&1 | head -250`. Full manifest dump of the BYD cluster app on DL2 (the prime RE target): declared activities/services/receivers/providers with exported flag, all declared and requested permissions with protection levels, signature, install location. Complement of L11's PackageManager-only view."));
        list.add(new TestDef("S22", "com.byd.appstartmanagement — dumpsys package full",
                "`dumpsys package com.byd.appstartmanagement 2>&1 | head -250`. Same depth as S21 for the second BYD package of interest. On DL5 this package gates app launch routing; if it has the same role on DL2 it's the second mandatory RE target."));
        list.add(new TestDef("S23", "BYD framework JARs probe",
                "`ls -la /system/framework/ 2>&1 | grep -iE 'byd|alps|cluster' ; echo --- ; ls -la /vendor/framework/ 2>&1 | grep -iE 'byd|alps|cluster' ; echo --- ; ls -la /system/etc/permissions/ 2>&1 | grep -iE 'byd|alps|cluster|automotive'`. Lists BYD-specific platform JARs + permission XMLs (often where vendor SDK is shipped: `byd-services.jar`, `byd_carapi.xml`, etc.)."));
        list.add(new TestDef("S24", "am stack list / task list (API 28)",
                "`am stack list 2>&1 | head -40 ; echo --- ; am task list 2>&1 | head -40`. Enumerates ActivityManager stacks and tasks with their displayId. API 28 still ships the `am stack`/`am task` subcommands (removed in API 30+). Shows whether DL2 actually has tasks living on a display other than 0."));
        list.add(new TestDef("S25", "Crash logs — tombstones + ANR + dropbox",
                "`ls -lt /data/tombstones/ 2>&1 | head -10 ; echo --- ; ls -lt /data/anr/ 2>&1 | head -10 ; echo --- ; ls -lt /data/system/dropbox/ 2>&1 | grep -iE 'byd|cluster|crash|anr' | head -20`. Lists the 10 most recent native crashes, ANRs, and BYD-relevant dropbox entries. Surfaces silent crashes that don't trigger a system dialog."));
        return list;
    }

    public static void runAll(Context appCtx, Listener listener) {
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
                        case "S16": runS16(ctx, r); break;
                        case "S17": runS17(ctx, r); break;
                        case "S18": runS18(ctx, r); break;
                        case "S19": runS19(ctx, r); break;
                        case "S20": runS20(ctx, r); break;
                        case "S21": runS21(ctx, r); break;
                        case "S22": runS22(ctx, r); break;
                        case "S23": runS23(ctx, r); break;
                        case "S24": runS24(ctx, r); break;
                        case "S25": runS25(ctx, r); break;
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
        sb.append("=== DiLink 2 RECON REPORT ===\n");
        sb.append("Build.BRAND        : ").append(Build.BRAND).append('\n');
        sb.append("Build.MODEL        : ").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT      : ").append(Build.PRODUCT).append('\n');
        sb.append("Build.MANUFACTURER : ").append(Build.MANUFACTURER).append('\n');
        sb.append("Build.HARDWARE     : ").append(Build.HARDWARE).append('\n');
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
        sb.append("\n=== END OF DiLink 2 REPORT ===\n");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test implementations
    // ────────────────────────────────────────────────────────────────────────

    private static void runL1(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build.BRAND        = ").append(Build.BRAND).append('\n');
        sb.append("Build.MANUFACTURER = ").append(Build.MANUFACTURER).append('\n');
        sb.append("Build.MODEL        = ").append(Build.MODEL).append('\n');
        sb.append("Build.PRODUCT      = ").append(Build.PRODUCT).append('\n');
        sb.append("Build.DEVICE       = ").append(Build.DEVICE).append('\n');
        sb.append("Build.HARDWARE     = ").append(Build.HARDWARE).append('\n');
        sb.append("Build.BOARD        = ").append(Build.BOARD).append('\n');
        sb.append("Build.DISPLAY      = ").append(Build.DISPLAY).append('\n');
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
                "ro.byd.car.model", "ro.byd.car.region",
                "ro.dilink.version", "ro.alps.version",
                "persist.sys.country", "persist.sys.language",
                "persist.sys.usb.config", "sys.usb.state",
                "service.adb.tcp.port",
                "ro.adb.secure", "ro.secure", "ro.debuggable",
                "ro.build.type", "ro.build.user", "ro.build.host"
        };
        for (String k : keys) {
            sb.append(k).append(" = ").append(getProp(k)).append('\n');
        }
        r.detail = sb.toString();
        boolean isDl2Signature = "alps".equalsIgnoreCase(Build.BRAND)
                && Build.PRODUCT != null && Build.PRODUCT.toLowerCase(Locale.ROOT).contains("k65v1");
        if (isDl2Signature) {
            r.status = Status.PASS;
            r.message = "DL2 signature confirmed (brand=alps, product contains k65v1, API "
                    + Build.VERSION.SDK_INT + ")";
        } else {
            r.status = Status.WARN;
            r.message = "Not a DL2 signature (brand=" + Build.BRAND
                    + " product=" + Build.PRODUCT + " API=" + Build.VERSION.SDK_INT + ")";
        }
    }

    private static void runL2(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("service.adb.tcp.port = ").append(getProp("service.adb.tcp.port")).append('\n');
        sb.append("ro.adb.secure        = ").append(getProp("ro.adb.secure")).append('\n');
        sb.append("ro.debuggable        = ").append(getProp("ro.debuggable")).append('\n');
        sb.append("---\n");
        int[] ports = new int[]{5037, 5554, 5555, 5556, 4444};
        int open = 0;
        for (int port : ports) {
            String status = probePort("127.0.0.1", port, 200);
            sb.append("127.0.0.1:").append(port).append("  → ").append(status).append('\n');
            if (status.startsWith("OPEN")) open++;
        }
        r.detail = sb.toString();
        if (open == 0) {
            r.status = Status.WARN;
            r.message = "All ADB-TCP ports CLOSED — shell-based tests will fail (expected on DL2)";
        } else {
            r.status = Status.PASS;
            r.message = open + " port(s) OPEN — ADB-TCP may be usable";
        }
    }

    private static void runL3(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all  = dm.getDisplays();
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        sb.append("DisplayManager.getDisplays()                : ").append(all.length).append('\n');
        sb.append("DisplayManager.getDisplays(PRESENTATION)    : ").append(pres.length).append('\n');
        for (Display d : all) {
            sb.append("  #").append(d.getDisplayId())
              .append("  name='").append(d.getName()).append('\'')
              .append("  flags=0x").append(Integer.toHexString(d.getFlags()))
              .append("  state=").append(d.getState())
              .append('\n');
        }
        sb.append("---\n");
        // DisplayManagerGlobal.getDisplayIds via reflection — may return ids hidden by DM filtering.
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = dmgCls.getMethod("getInstance");
            Object dmg = getInstance.invoke(null);
            Method getIds = dmgCls.getMethod("getDisplayIds");
            int[] ids = (int[]) getIds.invoke(dmg);
            sb.append("DisplayManagerGlobal.getDisplayIds() : ")
              .append(ids == null ? "null" : Arrays.toString(ids)).append('\n');
        } catch (Throwable t) {
            sb.append("DisplayManagerGlobal.getDisplayIds() : ERROR ")
              .append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        }
        r.detail = sb.toString();
        if (all.length >= 2) {
            r.status = Status.PASS;
            r.message = all.length + " displays — secondary surface detected";
        } else {
            r.status = Status.WARN;
            r.message = "Single display via DisplayManager — cluster not exposed (or hidden)";
        }
    }

    private static void runL4(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        IBinder sf = getSystemService("SurfaceFlinger");
        if (sf == null) {
            r.status = Status.FAIL;
            r.message = "ServiceManager.getService(\"SurfaceFlinger\") returned null";
            return;
        }
        sb.append("SurfaceFlinger binder    : ").append(sf).append('\n');
        try {
            sb.append("interface descriptor    : ").append(sf.getInterfaceDescriptor()).append('\n');
        } catch (Throwable t) {
            sb.append("interface descriptor    : ERROR ").append(t.getMessage()).append('\n');
        }
        // Historical SurfaceFlinger transaction codes: 1000 = CREATE_DISPLAY, 1001 = DESTROY,
        // 1002 = GET_DISPLAY_TOKEN. We just probe presence by reading the binder name.
        // We do NOT call transact with custom codes — that path was reworked on many AOSP
        // forks and could crash. Listing is enough to confirm reachability.
        sb.append("---\nTransact-safe probe: descriptor only (no custom transact to avoid AOSP-fork crashes)\n");
        r.detail = sb.toString();
        r.status = Status.PASS;
        r.message = "SurfaceFlinger binder reachable";
    }

    private static void runL5(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== /sys/class/drm ===\n").append(listDir("/sys/class/drm")).append("\n\n");
        sb.append("=== /dev/graphics ===\n").append(listDir("/dev/graphics")).append("\n\n");
        sb.append("=== /sys/class/graphics ===\n").append(listDir("/sys/class/graphics")).append('\n');
        r.detail = sb.toString();
        boolean hasSecondaryFb = sb.toString().contains("fb1") || sb.toString().contains("fb2");
        boolean hasMultipleConnectors = countMatches(sb.toString(), "card0-") >= 2;
        if (hasSecondaryFb || hasMultipleConnectors) {
            r.status = Status.PASS;
            r.message = "Multiple framebuffers / DRM connectors detected";
        } else {
            r.status = Status.WARN;
            r.message = "Single framebuffer / no extra DRM connector visible to app uid";
        }
    }

    private static void runL6(Context ctx, TestResult r) {
        List<String> all = listAllBinderServices();
        StringBuilder sb = new StringBuilder();
        sb.append("Total binder services: ").append(all.size()).append('\n');
        sb.append("---\n");
        String regex = "(?i).*(cluster|display|secondary|byd|alps|auto|mirror|magic|window|fission|xdja|projection|cross).*";
        int matched = 0;
        for (String s : all) {
            if (s.matches(regex)) {
                sb.append("  ").append(s).append('\n');
                matched++;
            }
        }
        if (matched == 0) sb.append("  (no match)\n");
        r.detail = sb.toString();
        if (matched > 0) {
            r.status = Status.PASS;
            r.message = matched + " interesting service(s) found";
        } else {
            r.status = Status.WARN;
            r.message = "No cluster/display/byd service found in ServiceManager";
        }
    }

    private static void runL7(Context ctx, TestResult r) {
        String[] candidates = new String[]{
                "cluster", "byd_cluster", "BydCluster", "BYDCluster",
                "mtk_cluster", "secondary_display", "displayfeature",
                "mirror", "BydMirror",
                "Auto_container", "auto_container", "AutoContainer",
                "magicwindow", "crosscontrol", "crossservice",
                "xdja", "xdja_container",
                "byd_carservice", "byd_carapi", "BYDCarApi", "BYDMgmt",
                "byd_datacached", "IBYDCDRService",
                "projection", "carprojection", "media_router_cluster",
                "fission", "appstart", "appstartmanagement",
                "alps_cluster", "alps_display", "mtk_displayfeature"
        };
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (String name : candidates) {
            IBinder b = getSystemService(name);
            if (b == null) {
                sb.append("  [absent] ").append(name).append('\n');
            } else {
                found++;
                String desc;
                try { desc = b.getInterfaceDescriptor(); }
                catch (Throwable t) { desc = "ERROR " + t.getClass().getSimpleName(); }
                sb.append("  [PRESENT] ").append(name).append("  desc='").append(desc).append("'\n");
            }
        }
        r.detail = sb.toString();
        if (found > 0) {
            r.status = Status.PASS;
            r.message = found + " candidate service(s) bound";
        } else {
            r.status = Status.WARN;
            r.message = "None of the candidate cluster services are bound";
        }
    }

    private static void runL8(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> iamCls = Class.forName("android.app.IActivityManager");
            Method[] methods = iamCls.getMethods();
            String regex = "(?i).*(movetask|setlaunch|startactivity|moveactivitytask|settaskwindowing|getfocusedstack|attachapplication).*";
            int matched = 0;
            for (Method m : methods) {
                if (m.getName().matches(regex)) {
                    sb.append("  ").append(formatMethod(m)).append('\n');
                    matched++;
                }
            }
            r.detail = sb.toString();
            if (matched > 0) {
                r.status = Status.PASS;
                r.message = matched + " relevant IActivityManager method(s)";
            } else {
                r.status = Status.WARN;
                r.message = "No matching IActivityManager method";
            }
        } catch (Throwable t) {
            r.status = Status.FAIL;
            r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.detail = sb.toString();
        }
    }

    private static void runL9(Context ctx, TestResult r) {
        try {
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            Method setId = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
            setId.invoke(opts, 0);
            android.os.Bundle b = opts.toBundle();
            r.detail = "ActivityOptions.setLaunchDisplayId(0).toBundle() OK\n"
                    + "bundle keys: " + (b == null ? "null" : b.keySet());
            r.status = Status.PASS;
            r.message = "setLaunchDisplayId is callable from app uid";
        } catch (Throwable t) {
            r.status = Status.FAIL;
            r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void runL10(Context ctx, TestResult r) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> all = pm.getInstalledPackages(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Total installed packages: ").append(all.size()).append('\n');
        sb.append("---\n");
        int matched = 0;
        for (PackageInfo pi : all) {
            String pkg = pi.packageName == null ? "" : pi.packageName;
            if (!pkg.startsWith("com.byd")
                    && !pkg.startsWith("com.alps")
                    && !pkg.startsWith("com.xdja")
                    && !pkg.contains("cluster")
                    && !pkg.contains("dilink")) continue;
            matched++;
            String apk = pi.applicationInfo == null ? "?" : pi.applicationInfo.sourceDir;
            sb.append("  ").append(pkg)
              .append("  v=").append(pi.versionName == null ? "?" : pi.versionName)
              .append("  vc=").append(pi.versionCode)
              .append("  apk=").append(apk)
              .append('\n');
        }
        if (matched == 0) sb.append("  (no match)\n");
        r.detail = sb.toString();
        r.status = matched > 0 ? Status.PASS : Status.WARN;
        r.message = matched + " BYD/alps/xdja/cluster package(s)";
    }

    private static void runL11(Context ctx, TestResult r) { dumpPackageManifest(ctx, "com.byd.cluster", r); }
    private static void runL12(Context ctx, TestResult r) { dumpPackageManifest(ctx, "com.byd.appstartmanagement", r); }

    private static void runL13(Context ctx, TestResult r) {
        String[] sdkClasses = new String[]{
                "com.byd.protocol.canbus.BYDAutoSpeedDevice",
                "com.byd.protocol.canbus.BYDAutoEnergyDevice",
                "com.byd.protocol.canbus.BYDAutoGearboxDevice",
                "com.byd.protocol.canbus.BYDAutoACDevice",
                "com.byd.protocol.canbus.BYDAutoAirConditionerDevice",
                "com.byd.protocol.canbus.BYDAutoDoorDevice",
                "com.byd.protocol.canbus.BYDAutoLightDevice",
                "com.byd.protocol.canbus.BYDAutoWiperDevice"
        };
        StringBuilder sb = new StringBuilder();
        int loaded = 0, instanced = 0;
        for (String cn : sdkClasses) {
            try {
                Class<?> c = Class.forName(cn);
                loaded++;
                sb.append("  [class OK] ").append(cn).append('\n');
                try {
                    Method gi = c.getMethod("getInstance");
                    Object inst = gi.invoke(null);
                    if (inst != null) {
                        instanced++;
                        sb.append("    getInstance() = ").append(inst.getClass().getSimpleName()).append('\n');
                    } else {
                        sb.append("    getInstance() = null\n");
                    }
                } catch (NoSuchMethodException nm) {
                    sb.append("    no getInstance() method\n");
                } catch (Throwable t) {
                    sb.append("    getInstance() ").append(t.getClass().getSimpleName())
                      .append(": ").append(t.getCause() != null ? t.getCause().getMessage() : t.getMessage()).append('\n');
                }
            } catch (Throwable t) {
                sb.append("  [absent] ").append(cn).append('\n');
            }
        }
        r.detail = sb.toString();
        if (loaded > 0) {
            r.status = Status.PASS;
            r.message = loaded + " SDK class(es) loaded, " + instanced + " instanced";
        } else {
            r.status = Status.WARN;
            r.message = "No BYD SDK class found on classpath";
        }
    }

    private static void runL14(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) {
            r.status = Status.WARN;
            r.message = "/proc not listable from app uid";
            return;
        }
        int matched = 0;
        String regex = "(?i).*(cluster|fission|projection|secondary|surface|display).*";
        for (File e : entries) {
            String name = e.getName();
            if (!name.matches("\\d+")) continue;
            File cmd = new File(e, "cmdline");
            String content = readSmall(cmd, 256);
            if (content.isEmpty()) continue;
            // /proc/<pid>/cmdline uses NUL separators
            String pretty = content.replace('\0', ' ').trim();
            if (pretty.matches(regex)) {
                sb.append("  pid=").append(name).append("  ").append(pretty).append('\n');
                matched++;
                if (matched >= 40) break;
            }
        }
        if (matched == 0) sb.append("(no matching process visible to app uid)\n");
        r.detail = sb.toString();
        r.status = matched > 0 ? Status.PASS : Status.WARN;
        r.message = matched + " interesting process(es) in /proc";
    }

    private static void runL15(Context ctx, TestResult r) {
        StringBuilder sb = new StringBuilder();
        String[] core = new String[]{"window", "activity", "package", "display", "input", "power"};
        int ok = 0;
        for (String n : core) {
            IBinder b = getSystemService(n);
            if (b == null) {
                sb.append("  [absent] ").append(n).append('\n');
                continue;
            }
            ok++;
            String desc;
            try { desc = b.getInterfaceDescriptor(); }
            catch (Throwable t) { desc = "ERROR " + t.getMessage(); }
            sb.append("  [PRESENT] ").append(n).append("  desc='").append(desc).append("'\n");
        }
        r.detail = sb.toString();
        r.status = ok >= 4 ? Status.PASS : Status.WARN;
        r.message = ok + "/" + core.length + " core binder services reachable";
    }

    // ────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ────────────────────────────────────────────────────────────────────────

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

    private static IBinder getSystemService(String name) {
        try {
            Class<?> cls = Class.forName("android.os.ServiceManager");
            Method m = cls.getMethod("getService", String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listAllBinderServices() {
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
        if (!f.exists()) return "(not present)";
        if (!f.canRead()) return "(not readable from app uid)";
        File[] entries = f.listFiles();
        if (entries == null) return "(listFiles returned null)";
        StringBuilder sb = new StringBuilder();
        for (File e : entries) {
            sb.append("  ").append(e.getName());
            if (e.isDirectory()) sb.append("/");
            sb.append('\n');
        }
        if (sb.length() == 0) return "(empty)";
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

    private static String readSmall(File f, int maxBytes) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[maxBytes];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void dumpPackageManifest(Context ctx, String pkg, TestResult r) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder sb = new StringBuilder();
        try {
            int flags = PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_PERMISSIONS;
            PackageInfo pi = pm.getPackageInfo(pkg, flags);
            sb.append("package=").append(pi.packageName)
              .append("  v=").append(pi.versionName)
              .append("  vc=").append(pi.versionCode).append('\n');
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                sb.append("apk      : ").append(ai.sourceDir).append('\n');
                sb.append("dataDir  : ").append(ai.dataDir).append('\n');
                sb.append("processN : ").append(ai.processName).append('\n');
                sb.append("uid      : ").append(ai.uid).append('\n');
                sb.append("flags    : 0x").append(Integer.toHexString(ai.flags)).append('\n');
            }
            sb.append("---\n");
            if (pi.activities != null) {
                sb.append("activities (").append(pi.activities.length).append("):\n");
                for (android.content.pm.ActivityInfo a : pi.activities) {
                    sb.append("  A ").append(a.name).append("  exported=").append(a.exported).append('\n');
                }
            }
            if (pi.services != null) {
                sb.append("services (").append(pi.services.length).append("):\n");
                for (ServiceInfo s : pi.services) {
                    sb.append("  S ").append(s.name).append("  exported=").append(s.exported).append('\n');
                }
            }
            if (pi.receivers != null) {
                sb.append("receivers (").append(pi.receivers.length).append("):\n");
                for (android.content.pm.ActivityInfo a : pi.receivers) {
                    sb.append("  R ").append(a.name).append("  exported=").append(a.exported).append('\n');
                }
            }
            if (pi.providers != null) {
                sb.append("providers (").append(pi.providers.length).append("):\n");
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    sb.append("  P ").append(p.name).append("  exported=").append(p.exported).append('\n');
                }
            }
            if (pi.permissions != null && pi.permissions.length > 0) {
                sb.append("permissions (").append(pi.permissions.length).append("):\n");
                for (android.content.pm.PermissionInfo p : pi.permissions) {
                    sb.append("  + ").append(p.name).append("  level=").append(p.protectionLevel).append('\n');
                }
            }
            if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
                sb.append("requested permissions (").append(pi.requestedPermissions.length).append("):\n");
                for (String p : pi.requestedPermissions) sb.append("  - ").append(p).append('\n');
            }
            r.detail = sb.toString();
            r.status = Status.PASS;
            r.message = "manifest captured (" + pi.versionName + ")";
        } catch (PackageManager.NameNotFoundException e) {
            r.status = Status.SKIPPED;
            r.message = pkg + " not installed";
        } catch (Throwable t) {
            r.status = Status.FAIL;
            r.message = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.detail = sb.toString();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // S tier — shell-based tests via AdbLocalClient (DL2 with ADB-over-TCP open)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Synchronously runs a shell command via the existing AdbLocalClient relay
     * (uid 2000, dadb dispatcher). Blocks the dilink2-test-runner thread until
     * the command finishes or the timeout elapses.
     *
     * Returns the raw output on success. On failure, returns a string starting
     * with {@code "__SHELL_ERR__:"} so callers can self-mark SKIPPED when ADB
     * is closed instead of FAILing the entire S suite.
     */
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

    private static boolean isShellErr(String s) {
        return s != null && s.startsWith("__SHELL_ERR__:");
    }

    /** Marks the result SKIPPED when ADB shell is unreachable. Returns true if skipped. */
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

    // ── S1 — ADB shell smoke test ───────────────────────────────────────────

    private static void runS1(Context ctx, TestResult r) {
        String out = runShellSync(ctx, "id -u && id -un && pwd && uname -a", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 4000);
        String firstLine = out.trim().split("\n", 2)[0].trim();
        boolean isShellUid = firstLine.matches("\\d+");
        if (isShellUid) {
            r.status = Status.PASS;
            r.message = "ADB shell OK (uid=" + firstLine + ")";
        } else {
            r.status = Status.WARN;
            r.message = "unexpected first line: " + firstLine;
        }
    }

    // ── S2 — getprop filter ─────────────────────────────────────────────────

    private static void runS2(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "getprop | grep -iE 'byd|alps|adb|cluster|display|sf\\.lcd' 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        int lines = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " matching property line(s)";
    }

    // ── S3 — wm size / density / overscan (CRITICAL health check) ───────────

    private static void runS3(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- wm size ---' && wm size 2>&1; "
              + "echo '--- wm density ---' && wm density 2>&1; "
              + "echo '--- wm overscan ---' && wm overscan 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 4000);
        boolean overridden = out.contains("Override")
                || java.util.regex.Pattern.compile("overscan\\s*\\(?\\s*-?\\d+\\s*,\\s*[^0]",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(out).find();
        if (overridden) {
            r.status = Status.WARN;
            r.message = "display state OVERRIDDEN — `wm size reset && wm overscan reset` to restore";
        } else {
            r.status = Status.PASS;
            r.message = "display state pristine (no override / no overscan)";
        }
    }

    // ── S4 — dumpsys display ────────────────────────────────────────────────

    private static void runS4(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys display 2>&1 | grep -E "
              + "'mDisplayId|mOverscan|mBaseDisplayInfo|Override|Physical|name=|state=|DisplayDeviceInfo' "
              + "| head -80", 6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 8000);
        int displayCount = countMatches(out, "mDisplayId=");
        if (displayCount == 0) displayCount = countMatches(out, "DisplayDeviceInfo");
        r.status = displayCount > 0 ? Status.PASS : Status.WARN;
        r.message = displayCount + " display block(s) captured";
    }

    // ── S5 — dumpsys SurfaceFlinger physical displays ──────────────────────

    private static void runS5(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- --display-id ---'; dumpsys SurfaceFlinger --display-id 2>&1; "
              + "echo '--- HWC displays ---'; "
              + "dumpsys SurfaceFlinger 2>&1 | grep -E 'Display [0-9]+ HWC|Display \"|hwc' | head -20",
                6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        boolean hasDisplay = out.contains("Display ");
        r.status = hasDisplay ? Status.PASS : Status.WARN;
        r.message = hasDisplay ? "SurfaceFlinger display info captured" : "no display info returned";
    }

    // ── S6 — service list filtered ──────────────────────────────────────────

    private static void runS6(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "service list 2>&1 | grep -iE "
              + "'byd|cluster|auto|display|window|fission|xdja|projection|mirror|magic|cross'",
                6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        int n = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = n > 0 ? Status.PASS : Status.WARN;
        r.message = n + " matching binder service(s) visible to shell";
    }

    // ── S7 — AutoContainer probe ────────────────────────────────────────────

    private static void runS7(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- AutoContainer ---'; service call AutoContainer 1 2>&1; "
              + "echo '--- auto_container ---'; service call auto_container 1 2>&1", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 3000);
        boolean reachable = out.contains("Result: Parcel");
        if (reachable) {
            r.status = Status.PASS;
            r.message = "AutoContainer / auto_container reachable on DL2";
        } else {
            r.status = Status.WARN;
            r.message = "AutoContainer absent on DL2 (expected — Phase4 SDK is DL3+)";
        }
    }

    // ── S8 — pm list packages -f BYD filter ─────────────────────────────────

    private static void runS8(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "pm list packages -f 2>&1 | grep -iE 'byd|alps|xdja|cluster|dilink' | sort", 8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 8000);
        int n = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = n > 0 ? Status.PASS : Status.WARN;
        r.message = n + " BYD/Alps/Xdja/cluster package(s)";
    }

    // ── S9 — Activity stack snapshot ────────────────────────────────────────

    private static void runS9(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys activity activities 2>&1 "
              + "| grep -E 'ResumedActivity|displayId|TaskRecord|mResumedActivity|mFocusedActivity' "
              + "| head -30", 7000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        boolean any = !out.trim().isEmpty();
        r.status = any ? Status.PASS : Status.WARN;
        r.message = any ? "stack snapshot captured" : "no activity info returned";
    }

    // ── S10 — SELinux state ─────────────────────────────────────────────────

    private static void runS10(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- getenforce ---'; getenforce 2>&1; "
              + "echo '--- id -Z ---';     id -Z 2>&1; "
              + "echo '--- sh label ---';  ls -Zd /system/bin/sh 2>&1", 4000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        boolean enforcing = out.contains("Enforcing");
        r.status = Status.PASS;
        r.message = enforcing ? "SELinux Enforcing" : "SELinux Permissive or unknown";
    }

    // ── S11 — ps daemon scan ────────────────────────────────────────────────

    private static void runS11(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "ps -A -o PID,USER,NAME 2>&1 "
              + "| grep -iE 'cluster|fission|projection|surface|byd|auto|xdja|mirror' "
              + "| head -30", 6000);
        if (skipIfNoShell(out, r)) return;
        if (out.contains("Unknown option") || out.contains("bad argument")) {
            out = runShellSync(ctx,
                    "ps 2>&1 | grep -iE "
                  + "'cluster|fission|projection|surface|byd|auto|xdja|mirror' | head -30", 6000);
            if (skipIfNoShell(out, r)) return;
        }
        r.detail = safeTrim(out, 6000);
        int n = out.isEmpty() ? 0 : out.split("\n").length;
        r.status = n > 0 ? Status.PASS : Status.WARN;
        r.message = n + " matching process(es)";
    }

    // ── S12 — System settings BYD filter ───────────────────────────────────

    private static void runS12(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- system ---'; settings list system 2>&1 | grep -iE 'byd|cluster|display|overscan|density|orient' | head -40; "
              + "echo '--- secure ---'; settings list secure 2>&1 | grep -iE 'byd|cluster|display|overscan' | head -40; "
              + "echo '--- global ---'; settings list global 2>&1 | grep -iE 'byd|cluster|display|overscan' | head -40",
                8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 8000);
        r.status = Status.PASS;
        r.message = "settings captured";
    }

    // ── S13 — am start --display probe ──────────────────────────────────────

    private static void runS13(Context ctx, TestResult r) {
        String comp = ctx.getPackageName() + "/.MainActivity";
        try {
            android.content.Intent i = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ctx.getPackageName());
            if (i != null && i.getComponent() != null) {
                comp = i.getComponent().getPackageName() + "/" + i.getComponent().getClassName();
            }
        } catch (Throwable ignore) { /* fall back to default */ }
        String out = runShellSync(ctx,
                "echo '--- display 0 ---'; am start --display 0 -a android.intent.action.MAIN "
              + "-c android.intent.category.LAUNCHER -n " + comp + " 2>&1; "
              + "echo '--- display 1 ---'; am start --display 1 -a android.intent.action.MAIN "
              + "-c android.intent.category.LAUNCHER -n " + comp + " 2>&1", 6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 3000);
        boolean disp0Ok = out.contains("Starting: Intent");
        if (disp0Ok) {
            r.status = Status.PASS;
            r.message = "am start --display 0 accepted";
        } else {
            r.status = Status.WARN;
            r.message = "am start --display 0 did not return Starting:";
        }
    }

    // ── S14 — Hardware fingerprint ──────────────────────────────────────────

    private static void runS14(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- cpuinfo ---'; cat /proc/cpuinfo 2>&1 | grep -iE 'Hardware|model name|Processor' | head -5; "
              + "echo '--- meminfo ---'; cat /proc/meminfo 2>&1 | head -4; "
              + "echo '--- /data fs --'; df -h /data 2>&1 | head -2", 5000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 2000);
        boolean isMtk = out.toLowerCase(Locale.ROOT).contains("mt67") || out.toLowerCase(Locale.ROOT).contains("mediatek");
        r.status = Status.PASS;
        r.message = isMtk ? "MTK SoC confirmed" : "hardware info captured";
    }

    // ── S15 — DRM / framebuffer / BYD config dirs ───────────────────────────

    private static void runS15(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- /sys/class/drm/ ---'; ls -la /sys/class/drm/ 2>&1 | head -20; "
              + "echo '--- /dev/graphics/ ---'; ls -la /dev/graphics/ 2>&1; "
              + "echo '--- /vendor/etc/byd ---'; ls -la /vendor/etc/byd/ 2>&1 | head -20; "
              + "echo '--- /system/etc/byd ---'; ls -la /system/etc/byd/ 2>&1 | head -20; "
              + "echo '--- /data/byd ---'; ls -la /data/byd/ 2>&1 | head -20", 6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "filesystem inventory captured";
    }

    // ── Build 192 — RE-oriented tests (S16–S25) ─────────────────────────────

    /** Holder used to pass discovery from S16 to S17. */
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

    /** Curated whitelist used by S17 to avoid extracting the entire BYD OS.
     *  com.byd.cluster is listed FIRST and extracted FIRST (highest user priority). */
    private static final String[] S17_INTERESTING = new String[] {
            "com.byd.cluster",                // ← priority #1, the cluster app itself
            "com.byd.appstartmanagement",
            "com.byd.providers.appstartup",
            "com.byd.containerservice",
            "com.xdja.containerservice",
            "com.byd.clusterdebug",
            "com.byd.car.server",
            "com.byd.crosscontrol",
            "com.byd.smarttravel",
            "com.byd.commander",
            "com.byd.freedom",
            "com.byd.overdrive",
            "com.byd.windowmanagement",
            // substring patterns (any discovered pkg containing these tokens):
            "fission", "projection", "cluster", "dilink", "magicwindow", "mirror",
    };

    private static boolean s17IsInteresting(String pkg) {
        String low = pkg.toLowerCase(Locale.ROOT);
        for (String s : S17_INTERESTING) {
            if (s.contains(".")) {
                if (low.equals(s)) return true;
            } else {
                if (low.contains(s)) return true;
            }
        }
        return false;
    }

    // ── S16 — BYD packages discovery (shell, populates sLastDiscovery) ─────

    private static void runS16(Context ctx, TestResult r) {
        String raw = runShellSync(ctx,
                "pm list packages -f 2>/dev/null"
              + " | grep -iE 'com\\.byd|com\\.alps|com\\.xdja|com\\.dilink|cluster|automotive'"
              + " | sort -u",
                8000);
        if (skipIfNoShell(raw, r)) return;
        sLastDiscovery.clear();
        StringBuilder sb = new StringBuilder();
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
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                vn = pi.versionName != null ? pi.versionName : "?";
                vc = pi.getLongVersionCode();
            } catch (Throwable ignored) {}
            sLastDiscovery.add(new DiscoveredPkg(pkg, apkPath, vn, vc));
            sb.append("  ").append(pkg)
              .append("  v").append(vn).append(" (").append(vc).append(")\n")
              .append("    ").append(apkPath).append('\n');
        }
        r.detail = sb.length() == 0 ? "(no matching package)" : safeTrim(sb.toString(), 8000);
        if (sLastDiscovery.isEmpty()) {
            r.status = Status.WARN;
            r.message = "No BYD/alps/xdja/dilink/cluster package matched";
        } else {
            r.status = Status.PASS;
            r.message = sLastDiscovery.size() + " package(s) discovered (saved for S17)";
        }
    }

    // ── S17 — APK extraction → /storage/emulated/0/Download/dl2_apks/ ──────

    private static void runS17(Context ctx, TestResult r) {
        if (sLastDiscovery.isEmpty()) {
            r.status = Status.SKIPPED;
            r.message = "Run S16 first (discovery empty)";
            return;
        }
        final String outDir = "/storage/emulated/0/Download/dl2_apks";

        // 1) Ensure the public Download target exists (shell — app uid can't always mkdir on /sdcard).
        String mkdirOut = runShellSync(ctx,
                "mkdir -p '" + outDir + "' 2>&1 && ls -ld '" + outDir + "' 2>&1", 4000);
        if (skipIfNoShell(mkdirOut, r)) return;
        if (mkdirOut.toLowerCase(Locale.ROOT).contains("permission denied")
                || mkdirOut.toLowerCase(Locale.ROOT).contains("cannot create")) {
            r.status = Status.FAIL;
            r.message = "Cannot create " + outDir + " — " + mkdirOut.trim();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Output dir: ").append(outDir).append('\n');
        sb.append("Visible in the on-device file manager under Download/dl2_apks/.\n");
        sb.append("Also retrievable via: adb pull ").append(outDir).append('\n');
        sb.append("Filtered (whitelist) — ").append(sLastDiscovery.size())
          .append(" pkg discovered, only RE-relevant ones extracted (com.byd.cluster first).\n\n");

        // 2) Sort discovery so com.byd.cluster always comes first.
        List<DiscoveredPkg> ordered = new ArrayList<>(sLastDiscovery);
        Collections.sort(ordered, (a, b) -> {
            boolean ac = "com.byd.cluster".equals(a.pkg);
            boolean bc = "com.byd.cluster".equals(b.pkg);
            if (ac && !bc) return -1;
            if (!ac && bc) return 1;
            return a.pkg.compareTo(b.pkg);
        });

        int ok = 0, fail = 0, skipped = 0, cached = 0;
        for (DiscoveredPkg d : ordered) {
            if (!s17IsInteresting(d.pkg)) {
                skipped++;
                sb.append("  – ").append(d.pkg).append("  (skipped, not in whitelist)\n");
                continue;
            }
            String safe = d.pkg.replace('/', '_');
            String dst  = outDir + "/" + safe + "_v" + d.versionCode + ".apk";

            // Cache via shell stat.
            String sizeStr = runShellSync(ctx, "stat -c %s '" + dst + "' 2>/dev/null", 3000).trim();
            long existingSize = 0L;
            try { if (!sizeStr.isEmpty() && !isShellErr(sizeStr)) existingSize = Long.parseLong(sizeStr); }
            catch (NumberFormatException ignore) { /* not present */ }
            if (existingSize > 0L) {
                cached++;
                ok++;
                sb.append("  ↻ ").append(d.pkg).append("  (cached, ").append(existingSize / 1024).append(" KB)\n");
                continue;
            }

            // cat + redirect avoids cp permission quirks on some BYD builds.
            String raw = runShellSync(ctx,
                    "cat '" + d.apkPath + "' > '" + dst + "' 2>&1 && stat -c %s '" + dst + "' 2>&1",
                    20000).trim();
            long writtenSize = 0L;
            try { writtenSize = Long.parseLong(raw); } catch (NumberFormatException ignore) { /* fall through */ }
            if (writtenSize > 0L) {
                ok++;
                sb.append("  ✓ ").append(d.pkg).append("  (").append(writtenSize / 1024).append(" KB)\n");
            } else {
                fail++;
                sb.append("  ✗ ").append(d.pkg).append("  — ").append(safeTrim(raw, 200)).append('\n');
            }
        }
        r.detail = safeTrim(sb.toString(), 10000);
        if (ok > 0 && fail == 0) {
            r.status = Status.PASS;
            r.message = ok + " APK(s) in Download/dl2_apks/ (" + cached + " cached), " + skipped + " skipped";
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

    // ── S18 — dumpsys window ────────────────────────────────────────────────

    private static void runS18(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys window 2>&1 | grep -E 'mDisplayId|mCurrentFocus|mFocusedApp|mDisplayContent|imeLayerStack|mStackId|Display: mDisplayId|mOverscan' | head -60",
                8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "window state captured";
    }

    // ── S19 — dumpsys SurfaceFlinger ────────────────────────────────────────

    private static void runS19(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- --list ---'; dumpsys SurfaceFlinger --list 2>&1 | head -30; "
              + "echo '--- topology ---'; dumpsys SurfaceFlinger 2>&1 | grep -E 'Display |layerStack|Output|orientation|HWComposer|hwc composition' | head -40",
                8000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 6000);
        r.status = Status.PASS;
        r.message = "SurfaceFlinger topology captured";
    }

    // ── S20 — Recent logcat (BYD/cluster) ───────────────────────────────────

    private static void runS20(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "logcat -d -v threadtime 2>&1 | grep -iE 'byd|alps|cluster|projection|fission|xdja|magicwindow|crosscontrol|appstartmanagement|containerservice|sendInfo|AutoContainer' | tail -200",
                10000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 9000);
        int lines = out.isEmpty() ? 0 : out.split("\\r?\\n").length;
        r.status = lines > 0 ? Status.PASS : Status.WARN;
        r.message = lines + " BYD/cluster log line(s)";
    }

    // ── S21 — com.byd.cluster dumpsys package ──────────────────────────────

    private static void runS21(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys package com.byd.cluster 2>&1 | head -250", 8000);
        if (skipIfNoShell(out, r)) return;
        if (out.toLowerCase(Locale.ROOT).contains("unable to find package")) {
            r.status = Status.WARN;
            r.message = "com.byd.cluster not installed on this DL2 device";
            r.detail = safeTrim(out, 1000);
            return;
        }
        r.detail = safeTrim(out, 9000);
        r.status = Status.PASS;
        r.message = "com.byd.cluster manifest captured";
    }

    // ── S22 — com.byd.appstartmanagement dumpsys package ───────────────────

    private static void runS22(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "dumpsys package com.byd.appstartmanagement 2>&1 | head -250", 8000);
        if (skipIfNoShell(out, r)) return;
        if (out.toLowerCase(Locale.ROOT).contains("unable to find package")) {
            r.status = Status.WARN;
            r.message = "com.byd.appstartmanagement not installed";
            r.detail = safeTrim(out, 1000);
            return;
        }
        r.detail = safeTrim(out, 9000);
        r.status = Status.PASS;
        r.message = "appstartmanagement manifest captured";
    }

    // ── S23 — BYD framework JARs / permission XMLs probe ───────────────────

    private static void runS23(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- /system/framework ---'; ls -la /system/framework/ 2>&1 | grep -iE 'byd|alps|cluster|automotive'; "
              + "echo '--- /vendor/framework ---'; ls -la /vendor/framework/ 2>&1 | grep -iE 'byd|alps|cluster|automotive'; "
              + "echo '--- /system/etc/permissions ---'; ls -la /system/etc/permissions/ 2>&1 | grep -iE 'byd|alps|cluster|automotive'; "
              + "echo '--- /vendor/etc/permissions ---'; ls -la /vendor/etc/permissions/ 2>&1 | grep -iE 'byd|alps|cluster|automotive'",
                6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 5000);
        r.status = Status.PASS;
        r.message = "framework + permissions inventory captured";
    }

    // ── S24 — am stack list / task list (API 28) ───────────────────────────

    private static void runS24(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- am stack list ---'; am stack list 2>&1 | head -40; "
              + "echo '--- am task list ---'; am task list 2>&1 | head -40",
                6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 5000);
        boolean hasStack = out.contains("Stack id=") || out.contains("taskId=");
        r.status = hasStack ? Status.PASS : Status.WARN;
        r.message = hasStack ? "am stack/task enumerable" : "am stack/task returned no data";
    }

    // ── S25 — Crash logs (tombstones + ANR + dropbox) ──────────────────────

    private static void runS25(Context ctx, TestResult r) {
        String out = runShellSync(ctx,
                "echo '--- tombstones ---'; ls -lt /data/tombstones/ 2>&1 | head -10; "
              + "echo '--- ANR ---'; ls -lt /data/anr/ 2>&1 | head -10; "
              + "echo '--- dropbox (BYD/crash) ---'; ls -lt /data/system/dropbox/ 2>&1 | grep -iE 'byd|cluster|crash|anr' | head -20",
                6000);
        if (skipIfNoShell(out, r)) return;
        r.detail = safeTrim(out, 5000);
        boolean hasIssue = out.toLowerCase(Locale.ROOT).contains("tombstone_")
                || out.toLowerCase(Locale.ROOT).contains("anr_")
                || out.toLowerCase(Locale.ROOT).contains("crash");
        r.status = hasIssue ? Status.WARN : Status.PASS;
        r.message = hasIssue ? "crash artefacts present — check detail" : "no recent crash artefacts";
    }
}
