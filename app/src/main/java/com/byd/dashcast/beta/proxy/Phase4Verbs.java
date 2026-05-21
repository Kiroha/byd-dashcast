package com.byd.dashcast.beta.proxy;

import android.os.IBinder;

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
}
