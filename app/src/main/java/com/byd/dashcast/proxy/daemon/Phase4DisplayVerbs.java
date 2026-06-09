package com.byd.dashcast.proxy.daemon;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Phase4DisplayVerbs — display-management verbs that run inside the daemon
 * process (uid 2000).
 *
 * <p>Covers three concern groups:
 * <ol>
 *   <li><b>IWindowManager</b> — overscan insets via the {@code window} service.</li>
 *   <li><b>VirtualDisplay</b> — create / release VDs on behalf of the app (Phase 5).</li>
 *   <li><b>ATM display policy</b> — {@code setDisplayToSingleTaskInstance},
 *       {@code isActivityStartAllowedOnDisplay}.</li>
 * </ol>
 *
 * @see Phase4ProcessVerbs
 * @see Phase4TaskVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
public final class Phase4DisplayVerbs {

    private Phase4DisplayVerbs() {}

    // ─── IWindowManager cache ─────────────────────────────────────────────

    private static volatile Object  sWindowManager;
    private static volatile IBinder sWindowManagerBinder;
    private static volatile Method  sSetOverscan;

    private static final IBinder.DeathRecipient sWindowManagerDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            synchronized (Phase4DisplayVerbs.class) {
                sWindowManager       = null;
                sWindowManagerBinder = null;
            }
        }
    };

    private static Object windowManager() throws Throwable {
        IBinder cached = sWindowManagerBinder;
        if (cached != null && cached.isBinderAlive()) return sWindowManager;
        synchronized (Phase4DisplayVerbs.class) {
            cached = sWindowManagerBinder;
            if (cached != null && cached.isBinderAlive()) return sWindowManager;
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "window");
            if (b == null) throw new IllegalStateException("no 'window' service");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            Object wm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            if (wm == null) throw new IllegalStateException("IWindowManager.asInterface returned null");
            try { b.linkToDeath(sWindowManagerDeath, 0); }
            catch (Throwable t) { return wm; }
            sWindowManagerBinder = b;
            sWindowManager       = wm;
            return wm;
        }
    }

    private static Method setOverscanMethod() throws Throwable {
        Method m = sSetOverscan;
        if (m != null) return m;
        synchronized (Phase4DisplayVerbs.class) {
            m = sSetOverscan;
            if (m != null) return m;
            Class<?> iface = Class.forName("android.view.IWindowManager");
            m = iface.getMethod("setOverscan",
                    int.class, int.class, int.class, int.class, int.class);
            sSetOverscan = m;
            return m;
        }
    }

    // ─── VirtualDisplay map ───────────────────────────────────────────────

    private static final java.util.concurrent.ConcurrentHashMap<Integer, android.hardware.display.VirtualDisplay>
            sVirtualDisplays = new java.util.concurrent.ConcurrentHashMap<>();

    // ─── Verbs ────────────────────────────────────────────────────────────

    /**
     * Equivalent of {@code wm overscan L,T,R,B -d displayId}.
     * Probe P1 (build 173) confirmed viable from uid 2000 on BYD Seal EU.
     */
    public static void setOverscan(int displayId, int left, int top, int right, int bottom)
            throws Throwable {
        Object wm = windowManager();
        Method m = setOverscanMethod();
        m.invoke(wm, displayId, left, top, right, bottom);
    }

    /**
     * Create a VirtualDisplay on behalf of the calling app and return its display id.
     * The daemon runs as uid 2000 (shell) — uses a {@code com.android.shell} package
     * context so DisplayManagerService's uid-package check passes.
     *
     * <p>Callers MUST eventually call {@link #releaseVirtualDisplay}; Java GC does NOT
     * release the underlying IVirtualDisplay token.
     */
    public static int createVirtualDisplay(android.content.Context sysCtx,
                                           String name, int width, int height, int dpi,
                                           android.view.Surface surface, int flags) {
        if (sysCtx == null) throw new IllegalStateException("system context null");
        if (name == null || name.isEmpty()) name = "DashCast_VD";
        if (width <= 0 || height <= 0 || dpi <= 0) {
            throw new IllegalArgumentException("bad geometry " + width + "x" + height + "@" + dpi);
        }
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("surface null or invalid");
        }
        android.content.Context shellCtx;
        try {
            shellCtx = sysCtx.createPackageContext("com.android.shell",
                    android.content.Context.CONTEXT_INCLUDE_CODE
                            | android.content.Context.CONTEXT_IGNORE_SECURITY);
        } catch (android.content.pm.PackageManager.NameNotFoundException nnfe) {
            throw new IllegalStateException("com.android.shell context unavailable", nnfe);
        }
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) shellCtx.getSystemService(
                        android.content.Context.DISPLAY_SERVICE);
        if (dm == null) throw new IllegalStateException("DisplayManager null");
        android.hardware.display.VirtualDisplay vd =
                dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        if (vd == null) throw new IllegalStateException("createVirtualDisplay returned null");
        android.view.Display d = vd.getDisplay();
        if (d == null) {
            try { vd.release(); } catch (Throwable ignore) {}
            throw new IllegalStateException("VirtualDisplay.getDisplay() null");
        }
        int id = d.getDisplayId();
        sVirtualDisplays.put(id, vd);
        return id;
    }

    /** Release a VD previously created via {@link #createVirtualDisplay}. No-op if unknown. */
    public static void releaseVirtualDisplay(int displayId) {
        android.hardware.display.VirtualDisplay vd = sVirtualDisplays.remove(displayId);
        if (vd != null) {
            try { vd.release(); } catch (Throwable ignore) {}
        }
    }

    /** Release every VD held by the daemon. Best-effort cleanup. */
    public static void releaseAllVirtualDisplays() {
        for (java.util.Map.Entry<Integer, android.hardware.display.VirtualDisplay> e
                : sVirtualDisplays.entrySet()) {
            try { e.getValue().release(); } catch (Throwable ignore) {}
        }
        sVirtualDisplays.clear();
    }

    /**
     * Mark a display as single-task instance. BYD/AOSP side-effect: the display
     * becomes "system-trusted" for activity launch, bypassing the secondary-display
     * gate that bounces apps like Waze back to display 0.
     */
    public static String setDisplayToSingleTaskInstance(int displayId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("setDisplayToSingleTaskInstance", int.class);
            m.invoke(iAtm, displayId);
            return "OK setDisplayToSingleTaskInstance(" + displayId + ")";
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR setDisplayToSingleTaskInstance: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    /** Diagnostic: ask ATM if {@code packageName} is allowed to start on {@code displayId}. */
    public static String isActivityStartAllowedOnDisplay(int displayId, String packageName) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN);
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            Method m = iAtm.getClass().getMethod("isActivityStartAllowedOnDisplay",
                    int.class, android.content.Intent.class, String.class, int.class);
            Object res = m.invoke(iAtm, displayId, intent, (String) null, 0);
            return "isActivityStartAllowedOnDisplay(" + displayId + "," + packageName + ") = " + res;
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR isActivityStartAllowedOnDisplay: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }
}
