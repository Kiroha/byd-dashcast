package com.byd.dashcast.proxy.daemon;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase4Probes — feasibility tests for replacing {@code sh -c "wm ..." | "am ..."}
 * with direct typed Binder calls (Phase 4).
 *
 * <p>Each probe attempts a single native AOSP API call from the daemon process
 * (uid 2000, SELinux domain {@code shell}, no system signature). It returns a
 * structured status so the app side can decide which Phase 4 verbs are safe
 * to ship and which must stay on the shell path.
 *
 * <p>Status taxonomy:
 * <ul>
 *   <li>{@code PASS}            — call succeeded, return value sensible</li>
 *   <li>{@code FAIL_SECURITY}   — {@link SecurityException}, blocked by
 *       permission / SELinux → Phase 4 verb is NOT viable from uid 2000</li>
 *   <li>{@code FAIL_API}        — {@link NoSuchMethodException} /
 *       {@link ClassNotFoundException} → API signature differs on this OEM,
 *       need device-specific reflection</li>
 *   <li>{@code FAIL_NULL}       — service binder not found (SDK_INT mismatch
 *       or service renamed)</li>
 *   <li>{@code FAIL_OTHER}      — any other Throwable; detail carries class
 *       name + message for triage</li>
 * </ul>
 *
 * <p>The result format is a pipe-separated list of {@code Pn=STATUS:detail}
 * tokens, e.g. {@code "P1=PASS:setOverscan ok|P2=FAIL_SECURITY:perm WRITE_SECURE_SETTINGS"}.
 * Detail strings have any {@code |} replaced by {@code !} to keep the parser
 * trivial on the client side.
 */
public final class Phase4Probes {

    private Phase4Probes() {}

    public static String runAll(Context systemContext) {
        List<String> out = new ArrayList<>(13);
        out.add(probe("P1", () -> p1_setOverscan()));
        out.add(probe("P2", () -> p2_getInitialDisplaySize()));
        out.add(probe("P3", () -> p3_forceStopPackage()));
        out.add(probe("P4", () -> p4_killBackgroundProcesses()));
        out.add(probe("P5", () -> p5_getRunningTasks()));
        out.add(probe("P6", () -> p6_autoContainerSendInfo()));
        out.add(probe("P7", () -> p7_systemProperties()));
        out.add(probe("P8", () -> p8_procCmdlineScan()));
        out.add(probe("P9", () -> p9_listServices()));
        out.add(probe("P10", () -> p10_packageInstallerName(systemContext)));
        out.add(probe("P11", () -> p11_inputManagerProbe()));
        out.add(probe("P12", () -> p12_displayManagerListDisplays(systemContext)));
        out.add(probe("P13", () -> p13_autoContainerDirectSendInfo()));
        return joinPipe(out);
    }

    // ─── individual probes ─────────────────────────────────────────────────

    /** P1 — {@code IWindowManager.setOverscan(displayId, l, t, r, b)} signature
     *  reachability probe. Replaces the {@code wm overscan} JVM fork.
     *
     *  1.2.29 — Non-destructive: we no longer invoke setOverscan(0,…) on display 0,
     *  because on DL3/DL5 that overwrites any user-configured overscan margins to
     *  zero (audit finding). The probe now only verifies that IWindowManager is
     *  reachable AND that the setOverscan(int×5) signature is present. The real
     *  verb is exercised end-to-end by Phase4Verbs.setOverscan via the daemon path. */
    private static String p1_setOverscan() throws Throwable {
        IBinder wmb = getService("window");
        if (wmb == null) return "FAIL_NULL:no window service";
        Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
        Object wm = stub.getMethod("asInterface", IBinder.class).invoke(null, wmb);
        if (wm == null) return "FAIL_NULL:asInterface returned null";
        Class<?> iface = Class.forName("android.view.IWindowManager");
        try {
            iface.getMethod("setOverscan", int.class, int.class, int.class, int.class, int.class);
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:setOverscan(int,int,int,int,int) absent on API " + Build.VERSION.SDK_INT;
        }
        return "PASS:setOverscan(int x5) signature reachable on API " + Build.VERSION.SDK_INT;
    }

    /** P2 — read-only display size getter; if PASS we know we can read display
     *  metadata directly from IWindowManager. */
    private static String p2_getInitialDisplaySize() throws Throwable {
        IBinder wmb = getService("window");
        if (wmb == null) return "FAIL_NULL:no window service";
        Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
        Object wm = stub.getMethod("asInterface", IBinder.class).invoke(null, wmb);
        Class<?> iface = Class.forName("android.view.IWindowManager");
        Method m;
        try {
            m = iface.getMethod("getInitialDisplaySize", int.class, Point.class);
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:getInitialDisplaySize absent";
        }
        Point p = new Point();
        m.invoke(wm, 0, p);
        return "PASS:display0 = " + p.x + "x" + p.y;
    }

    /** P3 — {@code IActivityManager.forceStopPackage}. Replaces {@code am force-stop}. */
    private static String p3_forceStopPackage() throws Throwable {
        Object am = getActivityManagerService();
        if (am == null) return "FAIL_NULL:no IActivityManager";
        Method m;
        try {
            m = am.getClass().getMethod("forceStopPackage", String.class, int.class);
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:forceStopPackage(String,int) absent";
        }
        // Target a guaranteed-nonexistent package so we never disturb a real app.
        m.invoke(am, "com.example.dashcast.phase4.does.not.exist", -1);
        return "PASS:forceStopPackage accepted";
    }

    /** P4 — {@code IActivityManager.killBackgroundProcesses}. */
    private static String p4_killBackgroundProcesses() throws Throwable {
        Object am = getActivityManagerService();
        if (am == null) return "FAIL_NULL:no IActivityManager";
        Method m;
        try {
            m = am.getClass().getMethod("killBackgroundProcesses", String.class, int.class);
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:killBackgroundProcesses(String,int) absent";
        }
        m.invoke(am, "com.example.dashcast.phase4.does.not.exist", -1);
        return "PASS:killBackgroundProcesses accepted";
    }

    /** P5 — {@code IActivityManager.getRunningTasks(int)}. Replaces the
     *  {@code dumpsys activity recents} + regex pipeline used today. */
    private static String p5_getRunningTasks() throws Throwable {
        Object am = getActivityManagerService();
        if (am == null) return "FAIL_NULL:no IActivityManager";
        Method m;
        try {
            m = am.getClass().getMethod("getTasks", int.class);
        } catch (NoSuchMethodException ignore) {
            try {
                m = am.getClass().getMethod("getRunningTasks", int.class);
            } catch (NoSuchMethodException nse) {
                return "FAIL_API:no getTasks/getRunningTasks(int)";
            }
        }
        Object res = m.invoke(am, 20);
        int n = (res instanceof List) ? ((List<?>) res).size() : -1;
        return "PASS:" + m.getName() + " returned " + n + " task(s)";
    }

    /** P6 — Raw transact against the {@code AutoContainer} system service used
     *  by {@code AdbLocalClient.sendInfo}. PASS would let us replace the
     *  shell wrapper entirely. */
    private static String p6_autoContainerSendInfo() throws Throwable {
        IBinder b = getService("AutoContainer");
        if (b == null) return "FAIL_NULL:no AutoContainer service";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // Best-effort: not all OEM builds expose the same descriptor; try a
            // benign INTERFACE_TRANSACTION query first to confirm the binder is
            // alive and reachable from uid 2000.
            boolean ok = b.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0);
            String descr = reply.readString();
            return "PASS:AutoContainer reachable, descriptor=" + (descr == null ? "<null>" : descr)
                    + " (transact ret=" + ok + ")";
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** P7 — read + write {@link android.os.SystemProperties}. PASS on read is
     *  expected; write almost certainly fails for uid 2000 (sepolicy). */
    private static String p7_systemProperties() throws Throwable {
        Class<?> sp = Class.forName("android.os.SystemProperties");
        String release = (String) sp.getMethod("get", String.class)
                .invoke(null, "ro.build.version.release");
        String writeStatus;
        try {
            sp.getMethod("set", String.class, String.class)
                    .invoke(null, "debug.dashcast.probe", "1");
            writeStatus = "set-ok";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            writeStatus = "set-fail:" + cause.getClass().getSimpleName();
        }
        return "PASS:read=" + release + " write=" + writeStatus;
    }

    /** P8 — sanity baseline: pure Java scan of {@code /proc/*\/cmdline}. Should
     *  always PASS — if it fails, the daemon is heavily sandboxed and Phase 4
     *  is doomed regardless of any Binder permission. */
    private static String p8_procCmdlineScan() throws Throwable {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return "FAIL_OTHER:/proc not readable";
        int scanned = 0, matched = 0;
        String needle = "com.byd.dashcast";
        byte[] buf = new byte[256];
        for (File d : dirs) {
            String name = d.getName();
            if (!d.isDirectory()) continue;
            boolean numeric = true;
            for (int i = 0; i < name.length(); i++) {
                if (name.charAt(i) < '0' || name.charAt(i) > '9') { numeric = false; break; }
            }
            if (!numeric) continue;
            File cmd = new File(d, "cmdline");
            if (!cmd.canRead()) continue;
            scanned++;
            int n;
            try (FileInputStream fis = new FileInputStream(cmd)) {
                n = fis.read(buf);
            } catch (Throwable ignore) { continue; }
            if (n <= 0) continue;
            String s = new String(buf, 0, n).replace('\0', ' ').trim();
            if (s.contains(needle)) matched++;
        }
        return "PASS:scanned " + scanned + " procs, matched " + matched + " '" + needle + "'";
    }

    /** P9 — {@code ServiceManager.listServices()}. PASS confirms broad
     *  enumeration access (useful for runtime service discovery). */
    private static String p9_listServices() throws Throwable {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method m;
        try {
            m = sm.getMethod("listServices");
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:listServices absent";
        }
        Object res = m.invoke(null);
        String[] arr = (String[]) res;
        // Cherry-pick a few we care about for Phase 4 to confirm visibility.
        List<String> wanted = Arrays.asList("window", "activity", "package", "input",
                "display", "AutoContainer", "media_session", "audio");
        StringBuilder sb = new StringBuilder("PASS:");
        sb.append(arr == null ? 0 : arr.length).append(" services; ");
        List<String> all = arr == null ? java.util.Collections.emptyList() : Arrays.asList(arr);
        for (String w : wanted) {
            sb.append(w).append('=').append(all.contains(w) ? '1' : '0').append(' ');
        }
        return sb.toString().trim();
    }

    /** P10 — {@code IPackageManager.getInstallerPackageName}. Cheap read API to
     *  prove typed PM access works from uid 2000. */
    private static String p10_packageInstallerName(Context systemContext) throws Throwable {
        IBinder pmb = getService("package");
        if (pmb == null) return "FAIL_NULL:no package service";
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        Object pm = stub.getMethod("asInterface", IBinder.class).invoke(null, pmb);
        Class<?> iface = Class.forName("android.content.pm.IPackageManager");
        Method m;
        try {
            m = iface.getMethod("getInstallerPackageName", String.class);
        } catch (NoSuchMethodException nse) {
            return "FAIL_API:getInstallerPackageName absent";
        }
        Object res = m.invoke(pm, "com.byd.dashcast");
        return "PASS:installer=" + (res == null ? "<null>" : String.valueOf(res));
    }

    /** P11 — Confirm {@code IInputManager} stub is reachable; injectInputEvent
     *  is the known-working path used by the existing input forwarder, so we
     *  just confirm the stub is asInterface-able from this process. */
    private static String p11_inputManagerProbe() throws Throwable {
        IBinder ib = getService("input");
        if (ib == null) return "FAIL_NULL:no input service";
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        Object im = stub.getMethod("asInterface", IBinder.class).invoke(null, ib);
        if (im == null) return "FAIL_NULL:asInterface returned null";
        // Don't actually inject — just walk the API surface to confirm reflection works.
        Class<?> iface = Class.forName("android.hardware.input.IInputManager");
        Method[] methods = iface.getDeclaredMethods();
        boolean hasInject = false;
        for (Method m : methods) {
            if ("injectInputEvent".equals(m.getName())) { hasInject = true; break; }
        }
        return "PASS:IInputManager bound, injectInputEvent " + (hasInject ? "present" : "ABSENT")
                + " (api surface=" + methods.length + " methods)";
    }

    /** P12 — {@code DisplayManager.getDisplays()} via the system context. The
     *  daemon already holds a system Context — confirm the framework managers
     *  attached to it are usable (replaces several {@code dumpsys display} pipes). */
    private static String p12_displayManagerListDisplays(Context systemContext) throws Throwable {
        if (systemContext == null) return "FAIL_NULL:no system context";
        Object dm = systemContext.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return "FAIL_NULL:DISPLAY_SERVICE null";
        Method m = dm.getClass().getMethod("getDisplays");
        Object[] displays = (Object[]) m.invoke(dm);
        StringBuilder sb = new StringBuilder("PASS:");
        sb.append(displays.length).append(" display(s):");
        for (Object d : displays) {
            Method getId   = d.getClass().getMethod("getDisplayId");
            Method getName = d.getClass().getMethod("getName");
            sb.append(" ").append(getId.invoke(d)).append('/').append(getName.invoke(d));
        }
        return sb.toString();
    }

    /** P13 — direct typed {@code transact(code=2)} on the {@code AutoContainer}
     *  binder, equivalent of {@code service call AutoContainer 2 i32 1000 i32 30 s16 ""}.
     *  This is the actual production call shape used by
     *  {@code AdbLocalClient.sendInfo}. P6 confirmed only that the binder is
     *  reachable and the descriptor is {@code android.os.IAutoContainer}; P13
     *  confirms the transaction itself goes through (no signature/uid check
     *  blocking the call). The chosen payload — {@code sendInfo(1000, 30, "")} —
     *  is the idempotent "Seal EU screen size" notification used by the
     *  diagnostic dump button, so even on PASS it has no visible side effect
     *  beyond what the user already triggers when opening Diag → ADB. */
    private static String p13_autoContainerDirectSendInfo() throws Throwable {
        IBinder b = getService("AutoContainer");
        if (b == null) return "FAIL_NULL:no AutoContainer service";
        // First confirm the descriptor matches what we expect — if the OEM
        // changed it, the writeInterfaceToken below would land on the wrong
        // server-side interface check and we want a clean diagnostic instead.
        String descr;
        Parcel d0 = Parcel.obtain();
        Parcel r0 = Parcel.obtain();
        try {
            b.transact(IBinder.INTERFACE_TRANSACTION, d0, r0, 0);
            descr = r0.readString();
        } finally {
            r0.recycle();
            d0.recycle();
        }
        if (descr == null || descr.isEmpty()) {
            return "FAIL_OTHER:no descriptor from AutoContainer";
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descr);
            data.writeInt(1000);  // type
            data.writeInt(30);    // info — Seal EU screen size notification
            data.writeString(""); // infoStr — empty for size queries
            boolean ok = b.transact(2, data, reply, 0);
            // readException() throws on remote SecurityException / IllegalArgument
            // — exactly what we need to triage whether direct transact is allowed.
            reply.readException();
            return "PASS:sendInfo(1000,30,\"\") via transact(2) on " + descr
                    + " (ret=" + ok + ")";
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /** Reflective {@code ServiceManager.getService(name)}. */
    private static IBinder getService(String name) throws Throwable {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
    }

    /** Reflective {@code ActivityManager.getService()} (API 26+) with fallback to
     *  the pre-O {@code ActivityManagerNative.getDefault()}. */
    private static Object getActivityManagerService() throws Throwable {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            Method m = am.getMethod("getService");
            return m.invoke(null);
        } catch (Throwable ignore) {
            Class<?> amn = Class.forName("android.app.ActivityManagerNative");
            Method m = amn.getMethod("getDefault");
            return m.invoke(null);
        }
    }

    private interface ProbeBody { String run() throws Throwable; }

    /** Run a single probe, normalising its outcome into the {@code Pn=STATUS:detail} grammar. */
    private static String probe(String id, ProbeBody body) {
        try {
            String r = body.run();
            return id + "=" + sanitise(r);
        } catch (Throwable t) {
            // Reflection wraps the real exception inside InvocationTargetException —
            // unwrap once so the message reflects what actually failed.
            Throwable cause = t;
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                cause = t.getCause();
            }
            String cls = cause.getClass().getSimpleName();
            String msg = cause.getMessage();
            String cat;
            if (cause instanceof SecurityException) cat = "FAIL_SECURITY";
            else if (cause instanceof NoSuchMethodException) cat = "FAIL_API";
            else if (cause instanceof ClassNotFoundException) cat = "FAIL_API";
            else cat = "FAIL_OTHER";
            return id + "=" + cat + ":" + cls + (msg == null ? "" : (" " + sanitise(msg)));
        }
    }

    /** Strip pipe + newline from a free-form string to keep the wire format unambiguous. */
    private static String sanitise(String s) {
        if (s == null) return "";
        return s.replace('|', '!').replace('\n', ' ').replace('\r', ' ');
    }

    private static String joinPipe(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Parse the result string back into {@code Pn → "STATUS:detail"} pairs. Used by the
     *  test runner on the client side to attribute each token to its TestDef. */
    public static java.util.Map<String, String> parse(String wire) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (wire == null || wire.isEmpty()) return out;
        for (String tok : wire.split("\\|")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            out.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return out;
    }

}
