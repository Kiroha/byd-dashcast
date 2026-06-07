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
        // Reuse a single 256-byte buffer per calling thread (build 195 / P3).
        // The daemon's binder pool dispatches concurrent transactions on a
        // small set of long-lived threads, so a ThreadLocal yields one buffer
        // per thread for the lifetime of the daemon — eliminating ~241 alloc
        // per scan on a typical BYD device, on a hot path (pidof poll every
        // ~5 s while a cluster app is alive).
        byte[] buf = sCmdlineBuf.get();
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

    /** Per-thread reusable scratch buffer for {@link #getPidsByPackage} (P3). */
    private static final ThreadLocal<byte[]> sCmdlineBuf = new ThreadLocal<byte[]>() {
        @Override protected byte[] initialValue() { return new byte[256]; }
    };

    // ─── AutoContainer (Phase 4c) ──────────────────────────────────────────

    /** Service name as registered with {@code ServiceManager}. Confirmed by probe P9. */
    private static final String AUTOCONTAINER_SVC = "AutoContainer";
    /** Transaction code for {@code sendInfo(int type, int info, String str)} — the
     *  same code the BYD ROM accepts via {@code service call AutoContainer 2 …}. */
    private static final int TXN_SEND_INFO = 2;

    private static volatile IBinder sAutoContainerBinder;
    private static volatile String  sAutoContainerDescriptor;

    // 1.2.31 — hooked once on first cache; clears the cache when the
    // AutoContainer host process dies so the next call re-resolves.
    private static final IBinder.DeathRecipient sAutoContainerDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            synchronized (Phase4Verbs.class) {
                sAutoContainerBinder = null;
                sAutoContainerDescriptor = null;
            }
        }
    };

    /**
     * Resolve (and cache) the live {@link IBinder} for the {@code AutoContainer}
     * service plus the descriptor it advertises via {@code INTERFACE_TRANSACTION}.
     *
     * <p>We read the descriptor at runtime rather than hard-coding
     * {@code "android.os.IAutoContainer"} so future OEM rebrands of the service
     * (descriptor renamed but transaction code unchanged) still go through.
     *
     * <p>Cache invalidation: a {@link IBinder.DeathRecipient} clears the cache
     * when the host process dies. Subsequent calls observe a stale reference
     * via {@link IBinder#isBinderAlive()} (local check, 0 IPC) and re-resolve.
     */
    private static IBinder autoContainerBinder() throws Throwable {
        IBinder b = sAutoContainerBinder;
        if (b != null && b.isBinderAlive()) return b;
        synchronized (Phase4Verbs.class) {
            b = sAutoContainerBinder;
            if (b != null && b.isBinderAlive()) return b;
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
            // 1.2.31 — best-effort death hook so isBinderAlive() stays accurate
            // on the fast path; if linkToDeath fails (binder already dead in the
            // tiny race window) we just don't cache.
            try { b.linkToDeath(sAutoContainerDeath, 0); }
            catch (Throwable t) { return b; /* don't cache a dead binder */ }
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

    // ─── IActivityManager (Phase 4d) ───────────────────────────────────────

    private static volatile Object sActivityManager;
    private static volatile Method sForceStopPackage;

    private static Object activityManager() throws Throwable {
        Object am = sActivityManager;
        if (am != null) return am;
        synchronized (Phase4Verbs.class) {
            am = sActivityManager;
            if (am != null) return am;
            try {
                Class<?> c = Class.forName("android.app.ActivityManager");
                am = c.getMethod("getService").invoke(null);
            } catch (Throwable ignore) {
                Class<?> c = Class.forName("android.app.ActivityManagerNative");
                am = c.getMethod("getDefault").invoke(null);
            }
            if (am == null) throw new IllegalStateException("no IActivityManager");
            sActivityManager = am;
            return am;
        }
    }

    private static Method forceStopMethod(Object am) throws Throwable {
        Method m = sForceStopPackage;
        if (m != null) return m;
        synchronized (Phase4Verbs.class) {
            m = sForceStopPackage;
            if (m != null) return m;
            m = am.getClass().getMethod("forceStopPackage", String.class, int.class);
            sForceStopPackage = m;
            return m;
        }
    }

    /**
     * Equivalent of {@code am force-stop <packageName>}.
     *
     * <p>Probe P3 (build 173) confirmed {@code IActivityManager.forceStopPackage}
     * is callable from uid 2000 on the BYD Seal EU (Android 10).
     * Replaces the {@code dadb.shell("am force-stop …")} fork used by
     * {@code AdbLocalClient.restoreBydOnCluster} and {@code restoreOriginCluster}
     * — both run at the end of every projection session.
     *
     * <p>{@code userId} is the Android user ID; pass {@code -1} for
     * {@code UserHandle.USER_ALL} (legacy {@code am force-stop} default).
     */
    public static void forceStopPackage(String packageName, int userId) throws Throwable {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("packageName empty");
        }
        Object am = activityManager();
        Method m = forceStopMethod(am);
        m.invoke(am, packageName, userId);
    }

    // ─── VirtualDisplay relay (Phase 5 — Cluster mini-mode POC) ────────────
    //
    // OpenBYD-style technique: an app process cannot create a PUBLIC/TRUSTED
    // VirtualDisplay because CAPTURE_VIDEO_OUTPUT is signature|privileged.
    // The daemon however runs as uid 2000 (shell) which is granted that
    // permission, so it can create the VD on the app's behalf and stream the
    // composited output back into the Surface the app passed via Parcel.
    //
    // Callers MUST eventually invoke {@link #releaseVirtualDisplay} — Java GC
    // on the daemon side does NOT release the VD on its own; an unreleased
    // VD keeps a SurfaceFlinger composition layer alive and pins the producer
    // endpoint of the caller's BufferQueue.
    //
    // @since v1.2.39 build 235 — Phase 5a (Cluster mini-mode POC).

    private static final java.util.concurrent.ConcurrentHashMap<Integer, android.hardware.display.VirtualDisplay>
            sVirtualDisplays = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Create a VirtualDisplay on behalf of the calling app and return its
     * {@link android.view.Display#getDisplayId() display id}. The provided
     * {@link android.view.Surface} is the VD's output target — typically a
     * surface obtained from a {@code SurfaceView} the caller already posted
     * on the cluster overlay so the VD's content shows in the small region
     * occupied by that SurfaceView, while the rest of the native cluster UI
     * remains visible behind it.
     *
     * <p>The returned VD is retained in a static map so the GC does not
     * release the underlying {@code IVirtualDisplay} token. Callers must
     * release it via {@link #releaseVirtualDisplay} once the SurfaceView's
     * surface is destroyed (or the overlay torn down).
     *
     * <p>{@code flags} mirrors {@code DisplayManager.VIRTUAL_DISPLAY_FLAG_*}.
     * OpenBYD 2.0 uses {@code 322 = PRESENTATION | SUPPORTS_TOUCH |
     * DESTROY_CONTENT_ON_REMOVAL} for its cluster mini-window.
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
        // The daemon runs as uid 2000 (shell) but {@code sSystemContext} is the
        // "android" system package context (uid 1000). DisplayManagerService
        // matches the calling uid against the Context's packageName via
        // {@code AppOpsManager.checkPackage} \u2014 so we MUST go through a
        // {@code com.android.shell} package context, which is the package
        // legitimately tied to uid 2000.
        //
        // Without this, build 235 surfaced:
        //   SecurityException: packageName must match the calling uid
        // on the BYD Seal EU (Android 10) at the very first createVirtualDisplay.
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

    /** Release every VD held by the daemon. Best-effort cleanup helper. */
    public static void releaseAllVirtualDisplays() {
        for (java.util.Map.Entry<Integer, android.hardware.display.VirtualDisplay> e
                : sVirtualDisplays.entrySet()) {
            try { e.getValue().release(); } catch (Throwable ignore) {}
        }
        sVirtualDisplays.clear();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Task relocation primitives (Phase 5b — "OpenBYD launchAndForce")
    // ──────────────────────────────────────────────────────────────────────
    //
    // canPlaceEntityOnDisplay gates only *new* startActivity calls. Once a
    // task already exists, IActivityTaskManager.moveTaskToDisplay / resizeTask
    // / setFocusedRootTask move it freely — including non-resizeable
    // activities like Waze that would otherwise be bounced with the
    // "secondary display" toast. Shell uid (2000) has
    // MANAGE_ACTIVITY_STACKS + INTERNAL_SYSTEM_WINDOW (granted to the shell
    // role) so these reflection calls succeed. This is the technique
    // OpenBYD 2.0 uses verbatim (CarControlImpl.launchAndForce).

    /** Lookup the taskId hosting {@code packageName} (any activity).
     *  Returns -1 if no such task exists. Best-effort: reads
     *  ActivityTaskManager.getService().getTasks(maxNum,false,false) and
     *  inspects each RecentTaskInfo.topActivity. */
    public static int findTaskIdForPackage(String packageName) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            // BYD fork of Android 10 doesn't expose getTasks(int,boolean,boolean)
            // (the stock AOSP API 29 signature). Enumerate all overloads of
            // `getTasks` on the Proxy class and invoke whichever one we find,
            // filling extra args with safe defaults (false / 0).
            Method getTasks = null;
            for (Method cand : iAtm.getClass().getMethods()) {
                if ("getTasks".equals(cand.getName())) { getTasks = cand; break; }
            }
            if (getTasks == null) {
                android.util.Log.w("Phase4Verbs", "findTaskIdForPackage: no getTasks method on " + iAtm.getClass());
                return -1;
            }
            Class<?>[] pt = getTasks.getParameterTypes();
            Object[] args = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == int.class)          args[i] = (i == 0 ? 64 : 0);
                else if (pt[i] == boolean.class) args[i] = false;
                else                              args[i] = null;
            }
            Object res = getTasks.invoke(iAtm, args);
            if (!(res instanceof java.util.List)) return -1;
            for (Object task : (java.util.List<?>) res) {
                if (task == null) continue;
                android.content.ComponentName topActivity =
                        (android.content.ComponentName) readFieldNoThrow(task, "topActivity");
                android.content.ComponentName baseActivity =
                        (android.content.ComponentName) readFieldNoThrow(task, "baseActivity");
                String pkg = (topActivity != null ? topActivity.getPackageName()
                            : baseActivity != null ? baseActivity.getPackageName() : null);
                if (packageName.equals(pkg)) {
                    Object id = readFieldNoThrow(task, "taskId");
                    if (id == null) id = readFieldNoThrow(task, "id");
                    if (id instanceof Integer) return (Integer) id;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("Phase4Verbs", "findTaskIdForPackage(" + packageName + ") failed: " + t);
        }
        return -1;
    }

    /** Reflection helper — returns field value or null on miss. */
    private static Object readFieldNoThrow(Object target, String fieldName) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Move an existing task to {@code displayId} via reflection.
     *  Enumerates getMethods() to catch any BYD-renamed variant
     *  (moveTaskToDisplay / moveRootTaskToDisplay / moveTopActivityToDisplay …)
     *  with 2 int params. Dumps every method whose name contains "Display"
     *  or "Stack" on failure for next-iteration RE. */
    public static String moveTaskToDisplay(int taskId, int displayId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method target = null;
            String[] preferred = {
                    "moveRootTaskToDisplay",
                    "moveTaskToDisplay",
                    "moveTopActivityToDisplay",
                    "reparentTaskToDisplay"
            };
            Method[] methods = iAtm.getClass().getMethods();
            outer:
            for (String wanted : preferred) {
                for (Method m : methods) {
                    if (!m.getName().equals(wanted)) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        target = m;
                        break outer;
                    }
                }
            }
            if (target == null) {
                StringBuilder dump = new StringBuilder(
                        "ERR moveTaskToDisplay: no (int,int) variant. Candidates: ");
                boolean first = true;
                for (Method m : methods) {
                    String n = m.getName();
                    if (n.contains("Display") || n.contains("Stack") || n.contains("Task")) {
                        if (!first) dump.append(", ");
                        dump.append(n).append('(');
                        Class<?>[] pt = m.getParameterTypes();
                        for (int i = 0; i < pt.length; i++) {
                            if (i > 0) dump.append(',');
                            dump.append(pt[i].getSimpleName());
                        }
                        dump.append(')');
                        first = false;
                    }
                }
                return dump.toString();
            }
            target.invoke(iAtm, taskId, displayId);
            return "OK " + target.getName() + "(" + taskId + "," + displayId + ")";
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR moveTaskToDisplay: " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage();
        }
    }

    /** BYD Seal EU / Android 10 stack-based move :
     *  1) flip task to FREEFORM via AOSP setTaskWindowingMode (also flips
     *     containing stack — required for subsequent resizeTask to be
     *     accepted; BYD's setCustomTaskWindowingMode flips only the task
     *     hint and leaves the stack in fullscreen, which causes
     *     "resizeTask not allowed on task")
     *  2) find the task's stackId + current displayId via getAllStackInfos()
     *  3) moveStackToDisplay(stackId, displayId), skipped if already on
     *     target (BYD throws IllegalArgumentException for same-display moves) */
    public static String moveTaskToDisplayViaStack(int taskId, int displayId) {
        StringBuilder log = new StringBuilder();
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);

            // Step A : flip task (and containing stack) to FREEFORM.
            //   AOSP setTaskWindowingMode(int,int,boolean) with toTop=true
            //   internally calls stack.setWindowingMode(mode) — works on BYD too.
            //   Only fall back to setCustomTaskWindowingMode if AOSP variant
            //   is missing (it isn't on BYD Seal but we keep the path for
            //   forks that strip it).
            try {
                Method setWm;
                String which;
                try {
                    setWm = iAtm.getClass().getMethod(
                            "setTaskWindowingMode", int.class, int.class, boolean.class);
                    which = "setTaskWindowingMode";
                } catch (NoSuchMethodException nsm) {
                    setWm = iAtm.getClass().getMethod(
                            "setCustomTaskWindowingMode", int.class, int.class, boolean.class);
                    which = "setCustomTaskWindowingMode";
                }
                setWm.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/);
                log.append("OK ").append(which).append('(').append(taskId).append(",FREEFORM)");
            } catch (Throwable wmEx) {
                Throwable c = (wmEx instanceof java.lang.reflect.InvocationTargetException
                        && wmEx.getCause() != null) ? wmEx.getCause() : wmEx;
                log.append("WARN setTaskWindowingMode: ").append(c.getClass().getSimpleName())
                   .append(" — ").append(c.getMessage());
            }
            log.append(" ; ");

            // Step B : find stackId AND its current displayId.
            int stackId = -1, currentDisplayId = -1;
            try {
                Method getAll = iAtm.getClass().getMethod("getAllStackInfos");
                Object res = getAll.invoke(iAtm);
                java.util.List<?> stacks;
                if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
                else if (res != null && res.getClass().isArray())
                    stacks = java.util.Arrays.asList((Object[]) res);
                else stacks = java.util.Collections.emptyList();
                for (Object si : stacks) {
                    if (si == null) continue;
                    int sid = -1, did = -1;
                    int[] tids = null;
                    try {
                        java.lang.reflect.Field fStack = si.getClass().getField("stackId");
                        sid = fStack.getInt(si);
                    } catch (NoSuchFieldException ignore) {}
                    try {
                        java.lang.reflect.Field fDid = si.getClass().getField("displayId");
                        did = fDid.getInt(si);
                    } catch (NoSuchFieldException ignore) {}
                    try {
                        java.lang.reflect.Field fTaskIds = si.getClass().getField("taskIds");
                        tids = (int[]) fTaskIds.get(si);
                    } catch (NoSuchFieldException ignore) {}
                    if (tids != null) {
                        for (int t : tids) {
                            if (t == taskId) {
                                stackId = sid;
                                currentDisplayId = did;
                                break;
                            }
                        }
                    }
                    if (stackId != -1) break;
                }
            } catch (Throwable lookupEx) {
                log.append("WARN getAllStackInfos: ").append(lookupEx.getClass().getSimpleName())
                   .append(" — ").append(lookupEx.getMessage()).append(" ; ");
            }
            log.append("stackId=").append(stackId).append(" currentDisplay=")
               .append(currentDisplayId).append(" ; ");
            if (stackId < 0) {
                log.append("ERR no stack for task=").append(taskId);
                return log.toString();
            }

            // Step C : moveStackToDisplay — only if not already on target.
            if (currentDisplayId == displayId) {
                log.append("SKIP moveStackToDisplay (already on display ")
                   .append(displayId).append(')');
                return log.toString();
            }
            Method mv = iAtm.getClass().getMethod(
                    "moveStackToDisplay", int.class, int.class);
            mv.invoke(iAtm, stackId, displayId);
            log.append("OK moveStackToDisplay(").append(stackId).append(',')
               .append(displayId).append(')');
            return log.toString();
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            log.append("ERR moveTaskToDisplayViaStack: ")
               .append(cause.getClass().getSimpleName())
               .append(" — ").append(cause.getMessage());
            return log.toString();
        }
    }

    /** Resize an existing task. RESIZE_MODE_FORCED = 1 (skip user-driven gate).
     *  Pass all-zero bounds to clear (full display).
     *  Enumerates resize* signatures so we can adapt to BYD/AOSP variants. */
    public static String resizeTaskRect(int taskId, int l, int t, int r, int b) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = (l == 0 && t == 0 && r == 0 && b == 0)
                    ? null : new android.graphics.Rect(l, t, r, b);

            // Try, in order :
            //   resizeTask(int, Rect, int)              — Android 10 stock
            //   resizeTask(int, Rect)                   — older
            //   resizeDockedStack / resizeStack         — fallback
            Method m = null;
            Object[] args = null;
            Method[] methods = iAtm.getClass().getMethods();
            for (Method cand : methods) {
                if (!cand.getName().equals("resizeTask")) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class
                        && pt[2] == int.class) {
                    m = cand; args = new Object[]{taskId, bounds, 1}; break;
                }
                if (pt.length == 2 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class) {
                    m = cand; args = new Object[]{taskId, bounds};
                }
            }
            if (m == null) {
                StringBuilder dump = new StringBuilder("ERR resizeTask: no variant. Candidates: ");
                boolean first = true;
                for (Method cand : methods) {
                    if (!cand.getName().toLowerCase().contains("resize")) continue;
                    if (!first) dump.append(", ");
                    dump.append(cand.getName()).append('(');
                    Class<?>[] pt = cand.getParameterTypes();
                    for (int i = 0; i < pt.length; i++) {
                        if (i > 0) dump.append(',');
                        dump.append(pt[i].getSimpleName());
                    }
                    dump.append(')');
                    first = false;
                }
                return dump.toString();
            }
            m.invoke(iAtm, args);
            return "OK " + m.getName() + "(" + taskId + ","
                    + (bounds == null ? "null" : bounds.toShortString()) + ")";
        } catch (Throwable ex) {
            Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException
                    && ex.getCause() != null) ? ex.getCause() : ex;
            String msg = cause.getMessage();
            return "ERR resizeTask: " + cause.getClass().getSimpleName()
                    + " — " + (msg == null ? cause.toString() : msg);
        }
    }

    /** Set focused task — drives input + brings task to top of its display. */
    public static String setFocusedRootTask(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m;
            try {
                m = iAtm.getClass().getMethod("setFocusedRootTask", int.class);
            } catch (NoSuchMethodException nsm) {
                m = iAtm.getClass().getMethod("setFocusedTask", int.class);
            }
            m.invoke(iAtm, taskId);
            return "OK " + m.getName() + "(" + taskId + ")";
        } catch (Throwable t) {
            return "ERR setFocusedRootTask: " + t.getClass().getSimpleName() + " — " + t.getMessage();
        }
    }

    /** Mark a display as single-task instance. BYD/AOSP API: side-effect on most
     *  forks is that the display becomes "system-trusted" for activity launch —
     *  bypasses the secondary-display gate that kicks Waze back to display 0. */
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

    /** Force a task to be resizeable. resizeMode values (AOSP):
     *  0=UNRESIZEABLE 1=CROP_WINDOWS 2=RESIZEABLE 3=RESIZEABLE_AND_PIPABLE 4=FORCE_RESIZEABLE. */
    public static String setTaskResizeable(int taskId, int resizeMode) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("setTaskResizeable", int.class, int.class);
            m.invoke(iAtm, taskId, resizeMode);
            return "OK setTaskResizeable(" + taskId + "," + resizeMode + ")";
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR setTaskResizeable: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    /** Diagnostic: ask AMS if {@code packageName} is allowed to start on {@code displayId}. */
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

    /** Set a task's windowing mode to FREEFORM (5) using the best available ATM API.
     *  Needed after moveStackToDisplay(), which creates a new FULLSCREEN stack on the
     *  target display and overrides the task's prior FREEFORM mode. */
    public static String setTaskWindowingModeFreeform(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            try {
                Method m = iAtm.getClass().getMethod(
                        "setTaskWindowingMode", int.class, int.class, boolean.class);
                m.invoke(iAtm, taskId, 5 /*WINDOWING_MODE_FREEFORM*/, true /*toTop*/);
                return "OK setTaskWindowingMode(" + taskId + ",FREEFORM,true)";
            } catch (NoSuchMethodException e1) {
                Method m = iAtm.getClass().getMethod(
                        "setCustomTaskWindowingMode", int.class, int.class, boolean.class);
                m.invoke(iAtm, taskId, 5, true);
                return "OK setCustomTaskWindowingMode(" + taskId + ",FREEFORM,true)";
            }
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR setTaskWindowingModeFreeform: " + c.getClass().getSimpleName()
                    + " — " + c.getMessage();
        }
    }

    /** Full OpenBYD 2.0 launchAndForce sequence : am start &rarr; poll task id &rarr;
     *  move + resize + focus, with an async watchdog that re-anchors the task if it
     *  bounces back to display 0 (Waze FLAG_ACTIVITY_LAUNCH_ADJACENT / launchToSide).
     *
     *  <p>Empirically the only path that reliably lands an app in FREEFORM
     *  windowing mode on the BYD fission cluster display (which lacks
     *  {@code FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT}) is the AOSP
     *  {@code am start --windowingMode 5 --display N} flag combo, observed in
     *  Dilink5 Dashboard ({@code c0.k} case 12). Setting the windowing mode
     *  at launch bypasses the AOSP {@code canChangeWindowingMode} gate that
     *  silently no-ops {@code setTaskWindowingMode} on an already-running task.
     *  We always force-stop the package first (via {@code -S}) so a re-launch
     *  takes effect even when the task already exists.
     *
     *  <p>Returns a multi-line log of every step so the caller can render it. */
    public static String launchAndForce(String packageName, String activityClass,
                                        int displayId, int width, int height) {
        StringBuilder log = new StringBuilder();
        log.append("== launchAndForce ").append(packageName)
           .append(activityClass != null ? "/" + activityClass : "")
           .append(" → display=").append(displayId)
           .append(' ').append(width).append('x').append(height).append(" ==\n");
        android.util.Log.i("Phase4Verbs", "FISSION launchAndForce START pkg=" + packageName
                + " displayId=" + displayId + " " + width + "x" + height);
        try {
            // Pre-cleanup : nuke any zombie split-screen-primary / freeform
            // stack left on the target display by a previous session.
            // Without this, AMS routes the new task into the orphan stack and
            // throws "Can only have one child on stack ... mode=split-screen-
            // primary" on the second launch (regression seen 1.2.61 → 1.2.62).
            log.append(cleanFissionStacks(displayId));

            // Always force-stop : if the task already exists we need a clean
            // relaunch so AMS observes the --windowingMode flag (a running task
            // keeps its existing stack's mode otherwise).
            log.append("$ am force-stop ").append(packageName).append('\n');
            String stopOut = execShell("am force-stop " + packageName, 3000);
            log.append(stopOut == null ? "(no output)" : stopOut).append('\n');

            // Resolve component if caller didn't provide one.
            String cmpFlat = activityClass != null
                    ? packageName + "/" + activityClass : null;
            if (cmpFlat == null) {
                String resolveCmd = "cmd package resolve-activity --brief"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " " + packageName;
                log.append("$ ").append(resolveCmd).append('\n');
                String resolveOut = execShell(resolveCmd, 3000);
                log.append(resolveOut == null ? "(no output)" : resolveOut).append('\n');
                if (resolveOut != null) {
                    for (String line : resolveOut.split("\\r?\\n")) {
                        line = line.trim();
                        if (line.contains("/") && !line.startsWith("[err]")
                                && !line.equals(packageName)) {
                            cmpFlat = line;
                            break;
                        }
                    }
                }
                log.append("resolved component = ").append(cmpFlat).append('\n');
            }

            boolean started = false;

            // Dilink5 Dashboard pattern : -S -W --windowingMode 5 --display N pkg/cls.
            // -S  : stop first if running (already done above, kept defensively).
            // -W  : wait for launch result.
            // --windowingMode 5 : FREEFORM — the only mode where resizeTask is
            //                     accepted on a display that doesn't itself
            //                     advertise FLAG_SUPPORTS_FREEFORM.
            if (cmpFlat != null) {
                String cmd = "am start-activity -S -W"
                        + " --windowingMode 5"
                        + " --display " + displayId
                        + " --activity-no-animation"
                        + " -n " + cmpFlat;
                android.util.Log.i("Phase4Verbs", "FISSION am start: " + cmd);
                log.append("$ ").append(cmd).append('\n');
                String out = execShell(cmd, 5000);
                log.append(out == null ? "(no output)" : out).append('\n');
                started = (out == null || !out.contains("Error:"));
            }

            // Fallback : bare MAIN with -p when component resolution failed.
            if (!started) {
                String cmd = "am start-activity -S -W"
                        + " --windowingMode 5"
                        + " --display " + displayId
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " --activity-no-animation"
                        + " -p " + packageName;
                log.append("$ ").append(cmd).append('\n');
                String out = execShell(cmd, 5000);
                log.append(out == null ? "(no output)" : out).append('\n');
                started = (out == null || !out.contains("Error:"));
            }

            // Poll up to ~5 s for the task to appear.
            int taskId = -1;
            for (int i = 1; i <= 16 && taskId <= 0; i++) {
                try { Thread.sleep(300); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                taskId = findTaskIdForPackage(packageName);
            }
            log.append("findTask(post-poll) = ").append(taskId).append('\n');

            if (taskId <= 0) {
                log.append("FAIL: no task discovered for ").append(packageName).append('\n');
                return log.toString();
            }

            // Placement sequence mirrors DevTool handleLaunchAndForce exactly:
            //  1. setDisplayToSingleTaskInstance
            //  2. getAllStackInfos → resolve stackId (RunningTaskInfo.stackId may be stale)
            //  3. FREEFORM pre-move  (setTaskWindowingMode on existing stack)
            //  4. moveStackToDisplay (creates new FULLSCREEN stack → moves task)
            //     — tolerated if already on target display (throws but we catch)
            //  5. FREEFORM post-move (new stack defaults to FULLSCREEN, must re-apply)
            //  6. resizeTask         (succeeds now that stack IS in FREEFORM mode)
            //  7. setFocusedTask
            // This is the ONLY sequence confirmed to work on BYD DiLink 3.0.
            log.append("  ").append(setDisplayToSingleTaskInstance(displayId)).append('\n');
            log.append("  ").append(setTaskResizeable(taskId, 4 /*FORCE_RESIZEABLE*/)).append('\n');

            // Resolve real stackId via getAllStackInfos (same as DevTool Step 3b).
            int stackId = -1;
            try {
                Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                Object ia = ac.getMethod("getService").invoke(null);
                Object res = ia.getClass().getMethod("getAllStackInfos").invoke(ia);
                java.util.List<?> ss;
                if (res instanceof java.util.List) ss = (java.util.List<?>) res;
                else if (res != null && res.getClass().isArray())
                    ss = java.util.Arrays.asList((Object[]) res);
                else ss = java.util.Collections.emptyList();
                outer2:
                for (Object si : ss) {
                    int[] tids;
                    try { tids = (int[]) si.getClass().getField("taskIds").get(si); }
                    catch (Exception ignore) { continue; }
                    if (tids == null) continue;
                    for (int t : tids) {
                        if (t == taskId) {
                            try { stackId = si.getClass().getField("stackId").getInt(si); }
                            catch (Exception ignore) {}
                            break outer2;
                        }
                    }
                }
                log.append("  stackId=").append(stackId).append('\n');
            } catch (Throwable ex) {
                log.append("  WARN getAllStackInfos: ").append(ex.getMessage()).append('\n');
            }

            // FREEFORM pre-move: flip task+stack to FREEFORM before the move.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            // moveStackToDisplay: even if task is already on displayId, calling
            // this creates the FREEFORM stack context that allows resizeTask.
            // Throws "already on same display" when the direct-launch path was
            // used — that is non-fatal; pre-FREEFORM already applied.
            if (stackId >= 0) {
                try {
                    Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                    Object ia = ac.getMethod("getService").invoke(null);
                    java.lang.reflect.Method mv = ia.getClass().getMethod(
                            "moveStackToDisplay", int.class, int.class);
                    mv.invoke(ia, stackId, displayId);
                    log.append("  OK moveStackToDisplay(").append(stackId).append(',')
                       .append(displayId).append(")\n");
                } catch (Throwable ex) {
                    Throwable c = (ex instanceof java.lang.reflect.InvocationTargetException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    log.append("  moveStackToDisplay: ").append(c.getClass().getSimpleName())
                       .append(" — ").append(c.getMessage()).append('\n');
                }
            }

            // FREEFORM post-move: moveStackToDisplay creates a FULLSCREEN stack;
            // re-apply FREEFORM so resizeTask is accepted and Waze's
            // canAddActivity() check sees FREEFORM and skips the redirect.
            log.append("  ").append(setTaskWindowingModeFreeform(taskId)).append('\n');

            log.append("  ").append(resizeTaskRect(taskId, 0, 0, width, height)).append('\n');
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n');
            // Async watchdog — polls getAllStackInfos() every 500 ms.
            // Apps like Waze fire FLAG_ACTIVITY_LAUNCH_ADJACENT ~1.3–2.8 s after
            // FreeMapAppActivity appears (launchToSide), bouncing the task to
            // display 0. Once detected, re-anchor: FREEFORM pre → moveStackToDisplay
            // → FREEFORM post (new stack defaults to FULLSCREEN) → resize → focus.
            // One successful re-move is sufficient; cap at 20 × 500 ms = 10 s.
            final int wTaskId = taskId;
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 20; iter++) {
                        try { Thread.sleep(500); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); return;
                        }
                        if (iter < 4) continue; // skip first 2 s, let app settle
                        int curDisplay = -1;
                        try {
                            Class<?> ac = Class.forName("android.app.ActivityTaskManager");
                            Object ia = ac.getMethod("getService").invoke(null);
                            Object res = ia.getClass().getMethod("getAllStackInfos").invoke(ia);
                            java.util.List<?> ss;
                            if (res instanceof java.util.List) ss = (java.util.List<?>) res;
                            else if (res != null && res.getClass().isArray())
                                ss = java.util.Arrays.asList((Object[]) res);
                            else ss = java.util.Collections.emptyList();
                            outer:
                            for (Object si : ss) {
                                int[] tids;
                                try { tids = (int[]) si.getClass().getField("taskIds").get(si); }
                                catch (Exception ignore) { continue; }
                                if (tids == null) continue;
                                for (int t : tids) {
                                    if (t == wTaskId) {
                                        try { curDisplay = si.getClass()
                                                .getField("displayId").getInt(si); }
                                        catch (Exception ignore) {}
                                        break outer;
                                    }
                                }
                            }
                        } catch (Throwable pollEx) {
                            android.util.Log.w("Phase4Verbs",
                                    "WATCHDOG poll err: " + pollEx.getMessage());
                            continue;
                        }
                        if (curDisplay < 0) return;    // task gone
                        if (curDisplay == displayId) continue; // still on target
                        android.util.Log.i("Phase4Verbs",
                                "WATCHDOG: task " + wTaskId + " on display " + curDisplay
                                + " — re-anchoring to " + displayId);
                        // FREEFORM pre + moveStackToDisplay (Android 10 primary API)
                        moveTaskToDisplayViaStack(wTaskId, displayId);
                        // FREEFORM post-move: new stack defaults to FULLSCREEN
                        setTaskWindowingModeFreeform(wTaskId);
                        resizeTaskRect(wTaskId, 0, 0, width, height);
                        setFocusedRootTask(wTaskId);
                        android.util.Log.i("Phase4Verbs", "WATCHDOG: re-anchor done");
                        return;
                    }
                    android.util.Log.d("Phase4Verbs", "WATCHDOG: ended without bounce");
                } catch (Throwable t) {
                    android.util.Log.w("Phase4Verbs",
                            "WATCHDOG unexpected: " + t.getMessage());
                }
            }, "fission-watchdog").start();
            log.append("WATCHDOG started (20×500ms, detects from T+2s)\n");
            log.append("FINISH: launchAndForce complete.\n");
            android.util.Log.i("Phase4Verbs", "FISSION launchAndForce DONE pkg=" + packageName
                    + " taskId=" + taskId + " displayId=" + displayId + " watchdog=running");
        } catch (Throwable t) {
            log.append("EXCEPTION: ").append(t).append('\n');
            android.util.Log.e("Phase4Verbs", "FISSION launchAndForce EXCEPTION: " + t);
        }
        return log.toString();
    }

    /** Destroy every non-fullscreen, non-home stack on {@code displayId}.
     *  Recovery verb for the fission display when an earlier session left a
     *  zombie stack in {@code split-screen-primary} (or freeform) mode :
     *  AOSP's {@code ActivityStackSupervisor} then routes every new task into
     *  that stack and throws {@code IllegalStateException: Can only have one
     *  child on stack=...mode=split-screen-primary} on the second launch.
     *
     *  <p>Iterates {@code IActivityTaskManager.getAllStackInfos()}, filters
     *  by {@code displayId}, and calls {@code removeStack(stackId)} on each
     *  stack whose {@code windowingMode} is not {@code FULLSCREEN(1)} and
     *  {@code activityType} is not {@code HOME(2)}.
     *
     *  <p>Returns a multi-line log line of every stack inspected and removed.
     *  Safe to call repeatedly — on a clean display the loop simply finds
     *  nothing to remove. */
    public static String cleanFissionStacks(int displayId) {
        StringBuilder log = new StringBuilder();
        log.append("== cleanFissionStacks display=").append(displayId).append(" ==\n");
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Object res = iAtm.getClass().getMethod("getAllStackInfos").invoke(iAtm);
            java.util.List<?> stacks;
            if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
            else if (res != null && res.getClass().isArray())
                stacks = java.util.Arrays.asList((Object[]) res);
            else { log.append("no stacks returned\n"); return log.toString(); }

            // Resolve removeStack(int) once.
            Method removeStack = null;
            for (Method cand : iAtm.getClass().getMethods()) {
                if (!"removeStack".equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 1 && pt[0] == int.class) { removeStack = cand; break; }
            }
            if (removeStack == null) {
                log.append("WARN: no removeStack(int) on ATM proxy\n");
            }

            int removed = 0, kept = 0;
            for (Object si : stacks) {
                if (si == null) continue;
                Integer sid = (Integer) readFieldNoThrow(si, "stackId");
                Integer did = (Integer) readFieldNoThrow(si, "displayId");
                Integer wm  = (Integer) readFieldNoThrow(si, "windowingMode");
                Integer at  = (Integer) readFieldNoThrow(si, "activityType");
                if (sid == null || did == null) continue;
                if (did != displayId) continue;
                int wmV = wm != null ? wm : -1;
                int atV = at != null ? at : -1;
                // FULLSCREEN=1 → leave (normal projection lives here).
                // HOME=2      → leave (default fallback Activity).
                if (wmV == 1 || atV == 2) {
                    log.append("  keep   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV).append('\n');
                    kept++;
                    continue;
                }
                // DEFENSIVE: if both fields are unreadable (-1/-1) the
                // StackInfo class shape differs on this ROM and we cannot
                // safely classify. Removing such a stack risks emptying the
                // display, which triggers a system_server NPE in AOSP's
                // ActivityDisplay.createStackUnchecked on the next
                // `am start --windowingMode 5 --display N`. Keep it.
                if (wmV == -1 && atV == -1) {
                    log.append("  keep?  stackId=").append(sid)
                       .append(" wm=-1 at=-1 (unreadable, defensive keep)\n");
                    kept++;
                    continue;
                }
                if (removeStack == null) {
                    log.append("  SKIP   stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" (no removeStack verb)\n");
                    continue;
                }
                try {
                    removeStack.invoke(iAtm, (int) sid);
                    log.append("  REMOVE stackId=").append(sid)
                       .append(" wm=").append(wmV).append(" at=").append(atV).append('\n');
                    removed++;
                } catch (Throwable rex) {
                    Throwable c = (rex instanceof java.lang.reflect.InvocationTargetException
                            && rex.getCause() != null) ? rex.getCause() : rex;
                    log.append("  ERR    stackId=").append(sid).append(": ")
                       .append(c.getClass().getSimpleName())
                       .append(": ").append(c.getMessage()).append('\n');
                }
            }
            log.append("done — removed=").append(removed).append(" kept=").append(kept).append('\n');
        } catch (Throwable t) {
            log.append("EXCEPTION: ").append(t).append('\n');
        }
        return log.toString();
    }

    /** Look up the stackId containing {@code taskId} via {@code getAllStackInfos()}.
     *  Returns -1 if not found. */
    public static int findStackIdForTask(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Object res = iAtm.getClass().getMethod("getAllStackInfos").invoke(iAtm);
            java.util.List<?> stacks;
            if (res instanceof java.util.List) stacks = (java.util.List<?>) res;
            else if (res != null && res.getClass().isArray())
                stacks = java.util.Arrays.asList((Object[]) res);
            else return -1;
            for (Object si : stacks) {
                if (si == null) continue;
                int sid;
                int[] tids;
                try {
                    sid = si.getClass().getField("stackId").getInt(si);
                    tids = (int[]) si.getClass().getField("taskIds").get(si);
                } catch (NoSuchFieldException e) { continue; }
                if (tids == null) continue;
                for (int t : tids) if (t == taskId) return sid;
            }
        } catch (Throwable ignore) {}
        return -1;
    }

    /** Flip a STACK's windowing mode via every known ATM verb. Tries, in order :
     *  setActivityStackWindowingMode, setStackWindowingMode (multiple arities),
     *  setActivityStackWindowingModeForced. Returns a log line. */
    public static String setStackWindowingMode(int stackId, int mode) {        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            String[] names = {
                    "setActivityStackWindowingMode",
                    "setStackWindowingMode",
                    "setActivityStackWindowingModeForced",
            };
            for (String name : names) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if (!cand.getName().equals(name)) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    try {
                        if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                            cand.invoke(iAtm, stackId, mode);
                            return "OK " + name + "(" + stackId + "," + mode + ")";
                        }
                        if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class
                                && pt[2] == boolean.class) {
                            cand.invoke(iAtm, stackId, mode, true);
                            return "OK " + name + "(" + stackId + "," + mode + ",true)";
                        }
                    } catch (Throwable inv) {
                        Throwable c = (inv instanceof java.lang.reflect.InvocationTargetException
                                && inv.getCause() != null) ? inv.getCause() : inv;
                        return "ERR " + name + ": " + c.getClass().getSimpleName()
                                + " — " + c.getMessage();
                    }
                }
            }
            return "SKIP setStackWindowingMode: no candidate method";
        } catch (Throwable t) {
            return "ERR setStackWindowingMode: " + t.getClass().getSimpleName()
                    + " — " + t.getMessage();
        }
    }

    /** Resize a stack directly. Different code path than resizeTask — does NOT
     *  hit the {@code canResizeTask()} gate (which requires FREEFORM windowing
     *  mode). Useful when the display lacks {@code FLAG_SUPPORTS_FREEFORM}. */
    public static String resizeStackRect(int stackId, int l, int t, int r, int b) {        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = (l == 0 && t == 0 && r == 0 && b == 0)
                    ? null : new android.graphics.Rect(l, t, r, b);
            Method[] methods = iAtm.getClass().getMethods();
            Method m = null;
            Object[] args = null;
            for (Method cand : methods) {
                if (!cand.getName().equals("resizeStack")) continue;
                Class<?>[] pt = cand.getParameterTypes();
                // AOSP Android 10 signature: (int, Rect, boolean, boolean, boolean, int)
                if (pt.length == 6 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class
                        && pt[2] == boolean.class && pt[3] == boolean.class
                        && pt[4] == boolean.class && pt[5] == int.class) {
                    m = cand;
                    args = new Object[]{stackId, bounds, true, true, false, -1};
                    break;
                }
                if (pt.length == 2 && pt[0] == int.class
                        && pt[1] == android.graphics.Rect.class) {
                    m = cand;
                    args = new Object[]{stackId, bounds};
                }
            }
            if (m == null) return "SKIP resizeStack: no matching variant";
            m.invoke(iAtm, args);
            return "OK resizeStack(" + stackId + ","
                    + (bounds == null ? "null" : bounds.toShortString()) + ")";
        } catch (Throwable ex) {
            Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException
                    && ex.getCause() != null) ? ex.getCause() : ex;
            return "ERR resizeStack: " + cause.getClass().getSimpleName()
                    + " — " + cause.getMessage();
        }
    }

    /** Dump every ATM method whose name matches one of the given lowercase
     *  substrings — used to RE the right reflection target at runtime. */
    public static String dumpAtmMethods(String[] substrings) {        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            StringBuilder out = new StringBuilder("ATM methods matching ")
                    .append(java.util.Arrays.toString(substrings)).append(":\n");
            java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
            for (Method m : iAtm.getClass().getMethods()) {
                String nm = m.getName().toLowerCase();
                boolean match = false;
                for (String s : substrings) if (nm.contains(s)) { match = true; break; }
                if (!match) continue;
                StringBuilder sig = new StringBuilder(m.getName()).append('(');
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) {
                    if (i > 0) sig.append(',');
                    sig.append(pt[i].getSimpleName());
                }
                sig.append(')');
                sorted.add(sig.toString());
            }
            for (String s : sorted) out.append("  ").append(s).append('\n');
            return out.toString();
        } catch (Throwable t) {
            return "ERR dumpAtmMethods: " + t;
        }
    }

    /** Read a task's current bounds via {@code getTaskBounds(int)} — diagnostic
     *  to verify whether a preceding resize/move actually took effect. */
    public static String getTaskBoundsVerb(int taskId) {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            Method m = iAtm.getClass().getMethod("getTaskBounds", int.class);
            Object res = m.invoke(iAtm, taskId);
            return "getTaskBounds(" + taskId + ") = "
                    + (res instanceof android.graphics.Rect
                            ? ((android.graphics.Rect) res).toShortString() : String.valueOf(res));
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) ? t.getCause() : t;
            return "ERR getTaskBounds: " + c.getClass().getSimpleName() + " — " + c.getMessage();
        }
    }

    /** BYD-specific verb that takes a Rect alongside a windowing mode hint.
     *  Empirically present on BYD Seal EU / Android 10 — used by BYD's own
     *  WindowManagement / Dilink5Dashboard to place floating windows on the
     *  fission cluster display, where standard FREEFORM is not supported.
     *
     *  <p>Signature observed at runtime :
     *  {@code setCustomTaskWindowingModeSplitScreenPrimary(int taskId,
     *  int mode, boolean onTop, boolean animate, Rect bounds, boolean ?)}
     *
     *  <p>Tries multiple {@code mode} values in this order :
     *  5=FREEFORM, 3=SPLIT_SCREEN_PRIMARY. Returns the first {@code OK}
     *  result, or the last {@code ERR}. */
    public static String setTaskWindowingModeWithBounds(int taskId,
                                                        int l, int t, int r, int b) {
        StringBuilder log = new StringBuilder();
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object iAtm = atmCls.getMethod("getService").invoke(null);
            android.graphics.Rect bounds = new android.graphics.Rect(l, t, r, b);

            // Method candidates (BYD-custom first, AOSP split-screen fallback).
            String[] names = {
                    "setCustomTaskWindowingModeSplitScreenPrimary",
                    "setTaskWindowingModeSplitScreenPrimary",
            };
            // Try mode 5 (FREEFORM) then 3 (SPLIT_SCREEN_PRIMARY) then 0
            // (createMode TOP_OR_LEFT for the AOSP variant).
            int[] modes = {5, 3, 0};

            Method target = null;
            String targetName = null;
            for (String nm : names) {
                for (Method cand : iAtm.getClass().getMethods()) {
                    if (!cand.getName().equals(nm)) continue;
                    Class<?>[] pt = cand.getParameterTypes();
                    if (pt.length == 6
                            && pt[0] == int.class && pt[1] == int.class
                            && pt[2] == boolean.class && pt[3] == boolean.class
                            && pt[4] == android.graphics.Rect.class
                            && pt[5] == boolean.class) {
                        target = cand;
                        targetName = nm;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target == null) return "SKIP setTaskWindowingModeWithBounds: no matching variant";

            Throwable lastEx = null;
            for (int mode : modes) {
                try {
                    target.invoke(iAtm, taskId, mode,
                            true /*onTop*/, false /*animate*/, bounds, false /*showRecents*/);
                    log.append("OK ").append(targetName).append('(')
                       .append(taskId).append(",mode=").append(mode)
                       .append(',').append(bounds.toShortString()).append(')');
                    return log.toString();
                } catch (Throwable ex) {
                    lastEx = (ex instanceof java.lang.reflect.InvocationTargetException
                            && ex.getCause() != null) ? ex.getCause() : ex;
                    log.append("[").append(mode).append("→")
                       .append(lastEx.getClass().getSimpleName())
                       .append(": ").append(lastEx.getMessage()).append("] ");
                }
            }
            return "ERR " + targetName + " all modes failed: " + log;
        } catch (Throwable ex) {
            return "ERR setTaskWindowingModeWithBounds: "
                    + ex.getClass().getSimpleName() + " — " + ex.getMessage();
        }
    }

    /** Move + resize a task on the BYD cluster fission display.
     *
     *  <p>This is the EXACT v1.2.61 sequence, validated in v1.2.70 as the
     *  only pipeline that reliably repositions Waze on every slider drag
     *  (auto-apply included) without crashing system_server. See
     *  {@code doc_api/CLUSTER_RESIZE_SEQUENCE.md} for the full rationale.
     *
     *  <p><b>Do not</b> add `if/else` gating, mode checks, or fallbacks
     *  (especially not {@code launchAndForce}) — every previous "smart"
     *  variant regressed because {@code StackInfo.windowingMode} is
     *  unreadable on this ROM (always -1) and {@code am start --windowingMode
     *  5 --display 1} triggers a system_server NPE in
     *  {@code createStackUnchecked}. The cascade as a whole is the point. */
    public static String moveAndResize(String packageName, int displayId,
                                       int l, int t, int r, int b) {
        StringBuilder log = new StringBuilder();
        log.append("== moveAndResize ").append(packageName)
           .append(" → display=").append(displayId)
           .append(" rect=[").append(l).append(',').append(t).append(',')
           .append(r).append(',').append(b).append("] ==\n");
        try {
            int taskId = findTaskIdForPackage(packageName);
            log.append("findTask = ").append(taskId).append('\n');
            if (taskId <= 0) {
                log.append("FAIL: no task for ").append(packageName)
                   .append(" — launch the app first via launchAndForce.\n");
                return log.toString();
            }
            // EXACT v1.2.61 sequence — proven to repeatedly move + resize
            // Waze on every slider drag (even in auto-apply mode). Each
            // verb contributes; the cascade as a whole is the point.
            //   - setDisplayToSingleTaskInstance     → loosens display
            //   - moveTaskToDisplayViaStack          → wakes the stack
            //   - setStackWindowingMode(stackId, 5) → FREEFORM via reflect
            //   - resizeStackRect                    → stack-level bounds
            //   - setTaskWindowingModeWithBounds    → BYD atomic flip+rect
            //   - resizeTaskRect                     → task-level bounds
            log.append("  ").append(setDisplayToSingleTaskInstance(displayId)).append('\n');
            log.append("  ").append(moveTaskToDisplayViaStack(taskId, displayId)).append('\n');
            log.append("  ").append(setTaskResizeable(taskId, 4)).append('\n');
            int stackId = findStackIdForTask(taskId);
            log.append("stackId = ").append(stackId).append('\n');
            if (stackId > 0) {
                log.append("  ").append(setStackWindowingMode(stackId, 5)).append('\n');
                log.append("  ").append(resizeStackRect(stackId, l, t, r, b)).append('\n');
            }
            log.append("  ").append(setTaskWindowingModeWithBounds(taskId, l, t, r, b)).append('\n');
            log.append("  ").append(resizeTaskRect(taskId, l, t, r, b)).append('\n');
            log.append("  ").append(setFocusedRootTask(taskId)).append('\n');
            log.append("  ").append(getTaskBoundsVerb(taskId)).append('\n');
            log.append("FINISH: moveAndResize complete.\n");
        } catch (Throwable ex) {
            log.append("EXCEPTION: ").append(ex).append('\n');
        }
        return log.toString();
    }

    /** Minimal shell exec used by launchAndForce. Reads both stdout + stderr,
     *  bounded by {@code timeoutMs}. Returns null on hard failure. */
    private static String execShell(String command, int timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            final Process proc = p;
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[4096];
                try (java.io.InputStream is = proc.getInputStream();
                     java.io.InputStream es = proc.getErrorStream()) {
                    int n;
                    while ((n = is.read(buf)) > 0) out.append(new String(buf, 0, n));
                    while ((n = es.read(buf)) > 0) out.append("[err] ").append(new String(buf, 0, n));
                } catch (Throwable ignore) {}
            }, "phase4-shell-reader");
            reader.setDaemon(true);
            reader.start();
            p.waitFor();
            reader.join(Math.max(200, timeoutMs / 4));
            return out.toString().trim();
        } catch (Throwable t) {
            return "[execShell EXCEPTION] " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }
}
