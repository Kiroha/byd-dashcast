package com.byd.dashcast.beta.proxy;

import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Phase4Verbs — production typed-Binder verbs that replace specific shell-fork
 * hot paths. Runs inside the daemon process (uid 2000).
 *
 * <p>Each method here corresponds to a single {@code wm …} / {@code am …}
 * command that the app used to fork via {@code sh -c}. They were all proven
 * viable from uid 2000 by the Phase 4 probe suite ({@link Phase4Probes},
 * build 173). Phase 4 wraps each one behind a dedicated binder transaction
 * so {@link ProxyDaemonMain} can dispatch a single {@code Parcel} to it
 * without going anywhere near {@code ProcessBuilder}.
 *
 * <p>Cached reflection: the {@code IWindowManager} stub class and the
 * {@code setOverscan} {@link Method} are resolved once and reused — avoids
 * repeating {@code Class.forName} on every call (the resize SeekBar can fire
 * 30+ overscan changes per second while dragging).
 *
 * @since v1.1.9 build 174 — Phase 4a.
 */
public final class Phase4Verbs {

    private Phase4Verbs() {}

    // ─── cached reflection (lazy, double-checked) ──────────────────────────

    private static volatile Object sWindowManager;
    private static volatile Method sSetOverscan;

    private static Object windowManager() throws Throwable {
        Object wm = sWindowManager;
        if (wm != null) return wm;
        synchronized (Phase4Verbs.class) {
            wm = sWindowManager;
            if (wm != null) return wm;
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            if (b == null) throw new IllegalStateException("no 'window' service");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            wm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            if (wm == null) throw new IllegalStateException("IWindowManager.asInterface returned null");
            sWindowManager = wm;
            return wm;
        }
    }

    private static Method setOverscanMethod() throws Throwable {
        Method m = sSetOverscan;
        if (m != null) return m;
        synchronized (Phase4Verbs.class) {
            m = sSetOverscan;
            if (m != null) return m;
            Class<?> iface = Class.forName("android.view.IWindowManager");
            m = iface.getMethod("setOverscan",
                    int.class, int.class, int.class, int.class, int.class);
            sSetOverscan = m;
            return m;
        }
    }

    // ─── verbs ─────────────────────────────────────────────────────────────

    /**
     * Equivalent of {@code wm overscan L,T,R,B -d displayId}.
     *
     * <p>Probe P1 (build 173) confirmed this works from uid 2000 on
     * BYD Seal EU (Android 10). Replaces the JVM-fork path that used to take
     * 70–275 ms per call ({@code ClusterService L525/L595}, {@code MainActivity
     * autoApplyInsetsIfNeeded}, resize SeekBar).
     */
    public static void setOverscan(int displayId, int left, int top, int right, int bottom)
            throws Throwable {
        Object wm = windowManager();
        Method m = setOverscanMethod();
        m.invoke(wm, displayId, left, top, right, bottom);
    }

    /**
     * Equivalent of {@code pidof <packageName>} — pure-Java scan of
     * {@code /proc/<pid>/cmdline} for processes whose argv[0] matches
     * {@code packageName} (exact match or {@code packageName:subprocess}).
     *
     * <p>Probe P8 (build 173) proved this scan returns in &lt; 1 ms on the
     * BYD Seal EU with 241 live processes. Replaces the {@code sh -c "pidof …"}
     * fork measured at 48–181 ms in build-174 device logs
     * ({@code MainActivity.reconcileDisplayState} /
     * {@code reconcileMainDisplayState}, runs every ~5 s while a cluster app
     * is alive).
     *
     * <p>Returns a space-separated list of PIDs (same shape as {@code pidof}'s
     * stdout) or an empty string if no process matches. Caller-side check
     * stays {@code output.trim().isEmpty()}.
     *
     * <p>This is a uid-2000-safe operation: the daemon's {@code shell} domain
     * can read every {@code /proc/<pid>/cmdline} thanks to Android's
     * {@code hidepid=0} default for the {@code shell} group (which is why
     * legacy {@code pidof} also worked).
     */
    public static String getPidsByPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return "";
        StringBuilder pids = null;
        for (File d : dirs) {
            String name = d.getName();
            if (!d.isDirectory()) continue;
            // Skip non-numeric /proc entries cheaply (no try/parse).
            boolean numeric = true;
            for (int i = 0, n = name.length(); i < n; i++) {
                char c = name.charAt(i);
                if (c < '0' || c > '9') { numeric = false; break; }
            }
            if (!numeric) continue;
            File cmd = new File(d, "cmdline");
            // canRead() is cheaper than catching the IOException — skip up front.
            if (!cmd.canRead()) continue;
            byte[] buf = new byte[256];
            int read;
            try (FileInputStream fis = new FileInputStream(cmd)) {
                read = fis.read(buf);
            } catch (Throwable ignore) { continue; }
            if (read <= 0) continue;
            // cmdline is NUL-separated; argv[0] runs up to the first 0x00.
            int end = 0;
            while (end < read && buf[end] != 0) end++;
            if (end == 0) continue;
            String argv0 = new String(buf, 0, end);
            // Match pidof semantics: argv[0] equals packageName, or starts with
            // 'packageName:' (Android sub-processes declared in the manifest).
            if (argv0.equals(packageName)
                    || (argv0.length() > packageName.length()
                        && argv0.startsWith(packageName)
                        && argv0.charAt(packageName.length()) == ':')) {
                if (pids == null) pids = new StringBuilder(name.length());
                else pids.append(' ');
                pids.append(name);
            }
        }
        return pids == null ? "" : pids.toString();
    }

    // ─── AutoContainer (Phase 4c) ──────────────────────────────────────────

    /** Service name as registered with {@code ServiceManager}. Confirmed by probe P9. */
    private static final String AUTOCONTAINER_SVC = "AutoContainer";
    /** Transaction code for {@code sendInfo(int type, int info, String str)} — the
     *  same code the BYD ROM accepts via {@code service call AutoContainer 2 …}. */
    private static final int TXN_SEND_INFO = 2;

    private static volatile IBinder sAutoContainerBinder;
    private static volatile String  sAutoContainerDescriptor;

    /**
     * Resolve (and cache) the live {@link IBinder} for the {@code AutoContainer}
     * service plus the descriptor it advertises via {@code INTERFACE_TRANSACTION}.
     *
     * <p>We read the descriptor at runtime rather than hard-coding
     * {@code "android.os.IAutoContainer"} so future OEM rebrands of the service
     * (descriptor renamed but transaction code unchanged) still go through.
     *
     * <p>Cache invalidation: if {@link IBinder#pingBinder} returns {@code false}
     * (service process restarted), the cache is cleared and re-resolved.
     */
    private static IBinder autoContainerBinder() throws Throwable {
        IBinder b = sAutoContainerBinder;
        if (b != null && b.pingBinder()) return b;
        synchronized (Phase4Verbs.class) {
            b = sAutoContainerBinder;
            if (b != null && b.pingBinder()) return b;
            Class<?> sm = Class.forName("android.os.ServiceManager");
            b = (IBinder) sm.getMethod("getService", String.class).invoke(null, AUTOCONTAINER_SVC);
            if (b == null) throw new IllegalStateException("no '" + AUTOCONTAINER_SVC + "' service");
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
                throw new IllegalStateException(AUTOCONTAINER_SVC + " advertised empty descriptor");
            }
            sAutoContainerDescriptor = descr;
            sAutoContainerBinder = b;
            return b;
        }
    }

    /**
     * Equivalent of {@code service call AutoContainer 2 i32 <type> i32 <info> s16 "<str>"}.
     *
     * <p>Probe P13 (build 176) confirmed {@code transact(2, …)} is accepted from
     * uid 2000 on the BYD Seal EU with descriptor {@code android.os.IAutoContainer}.
     * Replaces the {@code dadb.shell("service call AutoContainer 2 …")} relay
     * used by {@code AdbLocalClient.sendInfo}, eliminating both the shell parse
     * + escape of double-quoted arguments AND the {@code service} binary fork.
     *
     * <p>{@code readException()} surfaces any remote
     * {@code SecurityException} / {@code IllegalArgumentException} so the
     * client side can fall back to the legacy shell wrapper with full
     * diagnostic context.
     */
    public static void autoContainerSendInfo(int type, int info, String str) throws Throwable {
        IBinder b = autoContainerBinder();
        String descr = sAutoContainerDescriptor;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descr);
            data.writeInt(type);
            data.writeInt(info);
            data.writeString(str == null ? "" : str);
            b.transact(TXN_SEND_INFO, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
