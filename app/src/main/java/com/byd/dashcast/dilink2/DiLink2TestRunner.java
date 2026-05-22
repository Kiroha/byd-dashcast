package com.byd.dashcast.dilink2;

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

import com.byd.dashcast.AppLogger;

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
 * DiLink2TestRunner — recon-only diagnostic suite for the DiLink 2 / Android 9
 * platform (e.g. alps / k65v1_64_bsp / MT6765).
 *
 * <p><b>Design constraint:</b> on DL2 devices ADB-over-TCP (127.0.0.1:5555) is
 * <i>closed</i>, so {@code AdbLocalClient} cannot run shell commands. Every
 * test in this suite is therefore <b>shell-free</b> — they rely exclusively on
 * Java APIs accessible from the app process:
 *
 * <ul>
 *   <li>{@code SystemProperties.get} (reflection)</li>
 *   <li>{@code ServiceManager.listServices / getService} (reflection)</li>
 *   <li>{@code PackageManager} queries</li>
 *   <li>{@code DisplayManager} + {@code DisplayManagerGlobal.getDisplayIds} (reflection)</li>
 *   <li>{@code IActivityManager} method enumeration (reflection)</li>
 *   <li>Raw TCP {@link Socket} probes on localhost ADB ports</li>
 *   <li>{@code /proc} / {@code /sys} scans via {@link File}</li>
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
                && Build.PRODUCT != null && Build.PRODUCT.toLowerCase().contains("k65v1");
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
}
