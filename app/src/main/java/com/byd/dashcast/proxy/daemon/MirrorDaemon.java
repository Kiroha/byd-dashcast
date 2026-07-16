package com.byd.dashcast.proxy.daemon;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import com.byd.dashcast.util.concurrent.DeathLease;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.annotation.SuppressLint;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daemon MirrorDaemon — started via app_process (uid=2000 shell).
 *
 * Exposes a Binder (IMirrorDaemon) for:
 *   - TRANSACT_MIRROR_START  (1) : configure a SurfaceControl mirror of the cluster display
 *   - TRANSACT_INJECT_MOTION (2) : inject a MotionEvent on the cluster display
 *   - TRANSACT_INJECT_KEY    (3) : inject a KeyEvent
 *   - TRANSACT_MIRROR_STOP   (4) : destroy the mirror
 *
 * The Binder is broadcast via ACTION_DAEMON_READY at startup and on demand
 * ACTION_REQUEST_BINDER. Only uid=2000 can call SurfaceControl.createDisplay()
 * and InputManager.injectInputEvent() without additional permission.
 */
@SuppressWarnings("deprecation")
public class MirrorDaemon {

    private static final String TAG = "MirrorDaemon";

    /** Our app's uid, resolved once in main(); onTransact rejects callers that are not the app,
     *  system(1000), or the daemon's own uid. -1 = unresolved → fall open (never break the app). */
    private static volatile int sAppUid = -1;

    /** True if {@code uid} may drive the privileged MirrorBinder verbs. Falls OPEN when the app
     *  uid could not be resolved, so it can never block the legitimate app→daemon path. */
    private static boolean isAllowedCaller(int uid) {
        if (sAppUid == -1) return true;                     // unresolved → fall open, never break
        if (uid == 1000) return true;                       // system
        if (uid == android.os.Process.myUid()) return true; // the daemon's own uid (shell 2000)
        return (uid % 100000) == (sAppUid % 100000);        // our app (user-agnostic appId match)
    }

    // Actions broadcast
    public static final String ACTION_DAEMON_READY  = "com.byd.dashcast.MIRROR_DAEMON_READY";

    // Interface Binder — wire-protocol ID, must stay stable across package moves:
    // a daemon spawned by an older APK build keeps running across app updates and
    // enforces this exact token in onTransact().
    public static final String DESCRIPTOR            = "com.byd.dashcast.daemon.IMirrorDaemon";
    public static final int    TRANSACT_MIRROR_START  = 1;
    public static final int    TRANSACT_INJECT_MOTION = 2;
    public static final int    TRANSACT_INJECT_KEY    = 3;
    public static final int    TRANSACT_MIRROR_STOP   = 4;

    // ── Fission slot transacts (purely additive — transacts 1-4 unchanged) ──
    // 6-8 reserved (wire-format gap matching devtools numbering)
    /** TRANSACT 5 — create default full-screen overlay+VD (legacy CLUSTER_ATTACH). */
    public static final int TRANSACT_CLUSTER_ATTACH    = 5;
    /** TRANSACT 9 — resize a named slot overlay+VD in-place. */
    public static final int TRANSACT_RESIZE_SLOT       = 9;
    /** TRANSACT 10 — create a named overlay+VD slot for one app at a given rect. */
    public static final int TRANSACT_ATTACH_SLOT       = 10;
    /** TRANSACT 11 — release one named slot without stopping others. */
    public static final int TRANSACT_RELEASE_SLOT      = 11;
    /** TRANSACT 12 — activate a layout: create N overlay+VD slots. */
    public static final int TRANSACT_ACTIVATE_LAYOUT   = 12;
    /** TRANSACT 13 — deactivate layout: release all layout_ slots. */
    public static final int TRANSACT_DEACTIVATE_LAYOUT = 13;
    /** TRANSACT 14 — query the displayId for a named slot; returns -1 if not alive. */
    public static final int TRANSACT_QUERY_SLOT        = 14;
    /** TRANSACT 15 — move a package's task back to display 0 (teardown repatriation). */
    public static final int TRANSACT_MOVE_TO_DISPLAY0  = 15;
    /** Capture one frame of a layerStack (0=main, cluster stack=2/1) to a JPEG on disk.
     *  Params: int layerStack, int width, int height, int quality, String outPath.
     *  Reply: String status ("OK <path>" / "FAIL …"). Used by the bug-report screenshot recorder. */
    public static final int TRANSACT_CAPTURE_DISPLAY   = 16;

    // Mirror state (shared between threads via Binder thread pool)
    private static volatile IBinder sMirrorToken     = null;
    private static DeathLease       sMirrorOwnerLease = null;
    private static volatile int     sClusterDisplayId = 2;
    /** v1.2.7 — first-event trace flag; reset on each setupMirror to log once per session. */
    private static volatile boolean sMotionFirstLogged = false;
    private static volatile boolean sKeyFirstLogged    = false;

    // ── Fission slot state ────────────────────────────────────────────────────
    @SuppressLint("StaticFieldLeak") // application context, daemon process-scoped, safe
    private static volatile Context sContext    = null;
    @SuppressLint("StaticFieldLeak")
    private static volatile Context sSysContext = null;
    private static final ConcurrentHashMap<String, SlotInfo> sSlots = new ConcurrentHashMap<>();

    private static final class SlotInfo {
        final String pkg;
        int x, y, w, h;
        View overlayView;
        WindowManager overlayWM;
        VirtualDisplay vd;
        int displayId;

        SlotInfo(String pkg, int x, int y, int w, int h) {
            this.pkg = pkg; this.x = x; this.y = y; this.w = w; this.h = h;
        }

        synchronized void release() {
            if (vd != null) {
                try { vd.release(); }
                catch (Exception e) { out("[Fission] slot[" + pkg + "] VD release error: " + e.getMessage()); }
                vd = null;
            }
            final View view = overlayView;
            final WindowManager wm = overlayWM;
            overlayView = null; overlayWM = null;
            if (view != null && wm != null) {
                Runnable r = () -> {
                    try { wm.removeViewImmediate(view); }
                    catch (Exception e) { out("[Fission] slot[" + pkg + "] overlay remove error: " + e.getMessage()); }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) r.run();
                else new android.os.Handler(Looper.getMainLooper()).post(r);
            }
        }
    }

    // InputManager (init une seule fois, lu depuis les threads Binder → volatile)
    private static volatile Object  sInputManager    = null;
    private static volatile Method  sInjectMethod    = null;
    private static volatile Method  sSetDisplayId    = null;  // MotionEvent.setDisplayId — may be null
    private static volatile Method  sSetDisplayIdKey = null;  // KeyEvent.setDisplayId    — may be null (v1.2.11)

    // ─────────────────────────────────────────────────────────────────────────

    /** Thread-safe stdout helper — writes to both the redirected log file AND logcat. */
    private static void out(String msg) {
        System.out.println("[MirrorDaemon] " + msg);
        System.out.flush();
        Log.i(TAG, msg);   // logcat → captured by sniffer
    }
    private static void err(String msg, Throwable t) {
        System.err.println("[MirrorDaemon][ERROR] " + msg);
        if (t != null) t.printStackTrace(System.err);
        System.err.flush();
        Log.e(TAG, msg, t); // logcat → captured by sniffer
    }

    public static void main(String[] args) {
        out("main() start uid=" + android.os.Process.myUid());
        try {
            android.os.Process.class.getMethod("setArgV0", String.class)
                    .invoke(null, "com.byd.dashcast.mirrordaemon");
            out("setArgV0 OK");
        } catch (Exception ignored) {
            out("setArgV0 ignored: " + ignored.getMessage());
        }

        Log.i(TAG, "Starting MirrorDaemon uid=" + android.os.Process.myUid());

        try {
            out("Looper.getMainLooper()=" + Looper.getMainLooper());
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            out("Looper ready");

            // System context (via ActivityThread)
            out("Loading ActivityThread...");
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            out("ActivityThread found, calling systemMain()...");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            out("systemMain() returned: " + thread);
            Context context = (Context) thread.getClass()
                    .getMethod("getSystemContext").invoke(thread);
            out("getSystemContext() returned: " + context);
            if (context == null) {
                err("Context null — abandon", null);
                Log.e(TAG, "Context null");
                return;
            }
            Log.i(TAG, "System context OK");
            out("System context OK");

            // Save contexts for Fission slot operations (overlay + VD creation)
            sSysContext = context;
            try {
                sContext = context.createPackageContext("com.android.shell", 0);
                out("Fission: shell package context OK");
            } catch (Exception ePkg) {
                out("Fission: shell package context failed, fallback to system context: " + ePkg.getMessage());
                sContext = context;
            }

            // Unlock hidden APIs
            out("unlockHiddenApis()...");
            unlockHiddenApis();
            out("unlockHiddenApis OK");

            // Initialiser InputManager
            out("initInputManager()...");
            initInputManager();
            out("initInputManager OK");

            // Create our Binder (effectively final for the inner class)
            out("Creating MirrorBinder...");
            final IBinder daemonBinder = new MirrorBinder();
            out("MirrorBinder created");

            // Resolve our app's uid once so onTransact can reject untrusted callers (the binder is
            // also discoverable via ServiceManager). Fall open on any failure (sAppUid stays -1),
            // so this can never break the legitimate app→daemon path.
            try {
                sAppUid = context.getPackageManager()
                        .getPackageUid(com.byd.dashcast.BuildConfig.APPLICATION_ID, 0);
                out("app uid resolved: " + sAppUid);
            } catch (Throwable t) {
                out("app uid resolution failed (fall open): " + t.getMessage());
            }

            // Enregistrer dans ServiceManager (accessible par uid=2000) :
            // Remplace registerReceiver (interdit depuis systemMain() — AMS rejette
            // the unregistered IApplicationThread → SecurityException).
            out("ServiceManager.addService(byd_mirror_daemon)...");
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                // Android 10 : addService(String, IBinder, boolean, int)
                try {
                    Method addSvc = smClass.getDeclaredMethod("addService",
                            String.class, IBinder.class, boolean.class, int.class);
                    addSvc.setAccessible(true);
                    addSvc.invoke(null, "byd_mirror_daemon", daemonBinder, false, 0);
                    out("ServiceManager.addService (4-arg) OK");
                } catch (NoSuchMethodException e2) {
                    // Fallback : addService(String, IBinder)
                    Method addSvc = smClass.getDeclaredMethod("addService",
                            String.class, IBinder.class);
                    addSvc.setAccessible(true);
                    addSvc.invoke(null, "byd_mirror_daemon", daemonBinder);
                    out("ServiceManager.addService (2-arg) OK");
                }
            } catch (Exception eSm) {
                err("ServiceManager.addService FAILED — broadcast only", eSm);
            }

            // REMOVED: registerReceiver → SecurityException since systemMain()
            // AMS verifies that the IApplicationThread is in mPidsSelfLocked → refused
            // for an app_process not going through the normal startup sequence.
            // Replacement: ServiceManager.addService() above + initial sendBroadcast.

            // Announce our presence (sendBroadcast works from systemMain())
            out("broadcastBinder()...");
            broadcastBinder(context, daemonBinder);
            Log.i(TAG, "MirrorDaemon ready — Binder broadcast.");
            out("MirrorDaemon READY — Binder in ServiceManager + broadcast sent — Looper.loop() started");

            Looper.loop();
            out("Looper.loop() ended (should not happen)");

        } catch (Exception e) {
            err("Crash MirrorDaemon", e);
            Log.e(TAG, "Crash MirrorDaemon", e);
        }
        out("main() ended");
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    static class MirrorBinder extends Binder {
        MirrorBinder() { attachInterface(null, DESCRIPTOR); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            data.enforceInterface(DESCRIPTOR);
            // Caller-identity gate: the binder is published in the global ServiceManager and can be
            // obtained by any process, but only the app (+ system/self) may drive these privileged
            // input-injection / trusted-display / task verbs. enforceInterface alone is NOT auth.
            int callingUid = Binder.getCallingUid();
            if (!isAllowedCaller(callingUid)) {
                Log.w(TAG, "MirrorBinder: rejected transact code=" + code + " from uid=" + callingUid);
                if (reply != null) reply.writeException(
                        new SecurityException("caller uid " + callingUid + " not permitted"));
                return true; // consumed (rejected) — do not run the privileged verb
            }
            switch (code) {
                case TRANSACT_MIRROR_START: {
                    int layerStack    = data.readInt();
                    int clusterW      = data.readInt();
                    int clusterH      = data.readInt();
                    sClusterDisplayId = data.readInt();
                    int viewW         = data.readInt();
                    int viewH         = data.readInt();
                    Surface surface   = data.readParcelable(Surface.class.getClassLoader());
                    // Additive wire field: old clients omit it; current clients provide a
                    // process-owned token so an app crash cannot orphan the mirror display.
                    IBinder owner = data.dataAvail() > 0 ? data.readStrongBinder() : null;
                    boolean ok;
                    try {
                        stopMirror();
                        boolean ownerAttached = attachMirrorOwner(owner);
                        ok = ownerAttached && setupMirror(layerStack, clusterW, clusterH,
                                viewW, viewH, surface, owner != null);
                        if (!ok) stopMirror();
                    } finally {
                        // readParcelable created a daemon-local wrapper. SurfaceFlinger acquired
                        // the producer reference during setupMirror; this wrapper is no longer owned.
                        if (surface != null) surface.release();
                    }
                    // Reply to the client (synchronous call, not oneway)
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                }
                case TRANSACT_INJECT_MOTION: {
                    MotionEvent ev = data.readParcelable(MotionEvent.class.getClassLoader());
                    try {
                        injectMotion(ev);
                    } finally {
                        if (ev != null) ev.recycle();
                    }
                    return true;
                }
                case TRANSACT_INJECT_KEY: {
                    KeyEvent kev = data.readParcelable(KeyEvent.class.getClassLoader());
                    injectKey(kev);
                    return true;
                }
                case TRANSACT_MIRROR_STOP: {
                    stopMirror();
                    return true;
                }
                case TRANSACT_CLUSTER_ATTACH:   return handleClusterAttach(data, reply);
                case TRANSACT_RESIZE_SLOT:       return handleResizeSlot(data, reply);
                case TRANSACT_ATTACH_SLOT:       return handleAttachSlot(data, reply);
                case TRANSACT_RELEASE_SLOT:      return handleReleaseSlot(data, reply);
                case TRANSACT_ACTIVATE_LAYOUT:   return handleActivateLayout(data, reply);
                case TRANSACT_DEACTIVATE_LAYOUT: return handleDeactivateLayout(data, reply);
                case TRANSACT_QUERY_SLOT:        return handleQuerySlot(data, reply);
                case TRANSACT_MOVE_TO_DISPLAY0:  return handleMoveToDisplay0(data, reply);
                case TRANSACT_CAPTURE_DISPLAY: {
                    int layerStack = data.readInt();
                    int w          = data.readInt();
                    int h          = data.readInt();
                    int quality    = data.readInt();
                    String outPath = data.readString();
                    int maxFiles   = data.readInt();
                    int maxAgeMin  = data.readInt();
                    String status  = captureLayerStackToJpeg(layerStack, w, h, quality, outPath,
                            maxFiles, maxAgeMin);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeString(status);
                    }
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    // ── SurfaceControl mirror ─────────────────────────────────────────────────

    /**
     * Configures the mirror via STATIC methods of SurfaceControl (deprecated API but
     * functional on Android 10 BYD ROM — identical to the WindowManagement approach).
     * SurfaceControl.Transaction fails silently on this ROM.
     *
     * @return true if the mirror was configured successfully
     */
    @SuppressLint("NewApi")
    private static synchronized boolean setupMirror(int layerStack, int clusterW, int clusterH,
                                                    int viewW, int viewH, Surface surface,
                                                    boolean ownerRequired) {
        if (ownerRequired && (sMirrorOwnerLease == null || !sMirrorOwnerLease.isActive())) {
            out("setupMirror refused: client owner already dead");
            return false;
        }
        // v1.2.7 — reset per-session first-event trace so M7 captures the next injection chain.
        sMotionFirstLogged = false;
        sKeyFirstLogged    = false;
        out("setupMirror BEGIN layerStack=" + layerStack
                + " cluster=" + clusterW + "x" + clusterH
                + " view=" + viewW + "x" + viewH
                + " surface=" + (surface == null ? "null" : ("valid=" + surface.isValid())));
        if (surface == null || !surface.isValid()) {
            Log.e(TAG, "setupMirror : surface invalide");
            out("setupMirror FAIL surface invalide");
            return false;
        }
        SurfaceControl.Transaction tx = null;
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // 1. Create the mirror display token
            Method createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String.class, boolean.class);
            createDisplay.setAccessible(true);
            sMirrorToken = (IBinder) createDisplay.invoke(null, "byd_myapp_mirror", false);
            if (sMirrorToken == null) {
                Log.e(TAG, "setupMirror : createDisplay → null");
                out("setupMirror FAIL createDisplay returned null (DL5 SurfaceControl quirk?)");
                return false;
            }
            Log.i(TAG, "setupMirror : createDisplay token=" + sMirrorToken);
            out("setupMirror createDisplay OK token=" + sMirrorToken);

            // 2. Letterbox projection (preserved ratio)
            float scale = Math.min((float) viewW / clusterW, (float) viewH / clusterH);
            int drawW   = (int) (clusterW * scale);
            int drawH   = (int) (clusterH * scale);
            int offX    = (viewW - drawW) / 2;
            int offY    = (viewH - drawH) / 2;
            Rect src = new Rect(0, 0, clusterW, clusterH);
            Rect dst = new Rect(offX, offY, offX + drawW, offY + drawH);
            Log.i(TAG, "setupMirror : src=" + src + " dst=" + dst
                    + " surface.valid=" + surface.isValid());

            // 3. SurfaceControl.Transaction — instance methods via reflection.
            //    IMPORTANT: we use Transaction (not the static methods) because that is
            //    what worked in v2.43. Static methods (openTransaction/
            //    closeTransaction) are available on this ROM but produce a black
            //    screen with no error — behavior observed in v2.45.
            tx = new SurfaceControl.Transaction();
            Class<?> txClass = tx.getClass();

            Method setLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                    IBinder.class, int.class);
            setLayerStack.setAccessible(true);
            setLayerStack.invoke(tx, sMirrorToken, layerStack);
            Log.i(TAG, "setupMirror : setDisplayLayerStack(" + layerStack + ") OK");

            Method setSurface = txClass.getDeclaredMethod("setDisplaySurface",
                    IBinder.class, Surface.class);
            setSurface.setAccessible(true);
            setSurface.invoke(tx, sMirrorToken, surface);
            Log.i(TAG, "setupMirror : setDisplaySurface OK");

            Method setProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setProjection.setAccessible(true);
            setProjection.invoke(tx, sMirrorToken, 0, src, dst);
            Log.i(TAG, "setupMirror : setDisplayProjection OK");

            tx.apply();
            Log.i(TAG, "setupMirror : tx.apply() OK");

            // 4. Post-setup verification via dumpsys SurfaceFlinger
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c",
                                "dumpsys SurfaceFlinger 2>/dev/null"
                                + " | grep -iE 'byd_myapp_mirror|layerStack=" + layerStack + "'"});
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                // Audit batch 1 — also close stderr/stdin so the Process doesn't
                // keep file descriptors open until GC (previously leaked one FD per setupMirror).
                try { p.getErrorStream().close(); } catch (Exception ignored) { }
                try { p.getOutputStream().close(); } catch (Exception ignored) { }
                p.waitFor();
                Log.i(TAG, "setupMirror SF dump :\n" + sb.toString().trim());
                out("setupMirror SF dump (layerStack=" + layerStack + "):\n"
                        + (sb.length() == 0 ? "(empty — token NOT in SurfaceFlinger!)" : sb.toString().trim()));
            } catch (Exception e) {
                Log.d(TAG, "SF dump read failed: " + e.getMessage());
                out("setupMirror SF dump read failed: " + e.getMessage());
            } finally {
                if (p != null) { try { p.destroy(); } catch (Exception ignored) { } }
            }

            Log.i(TAG, "setupMirror ✓ (Transaction) layerStack=" + layerStack
                    + " src=" + clusterW + "×" + clusterH
                    + " dst=" + drawW + "×" + drawH + " offset=(" + offX + "," + offY + ")");
            out("setupMirror DONE ok=true layerStack=" + layerStack
                    + " dst=" + drawW + "x" + drawH + " off=(" + offX + "," + offY + ")");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "setupMirror failed", e);
            out("setupMirror EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // If createDisplay succeeded but a later reflection step threw, the
            // SurfaceFlinger display token must be released — otherwise it leaks
            // for the lifetime of the daemon process. stopMirror() handles the
            // null case and clears sMirrorToken atomically.
            stopMirror();
            return false;
        } finally {
            // Safe after apply(): close releases only this native transaction builder. Keeping it
            // in finally also covers reflection failures between construction and apply().
            if (tx != null) try { tx.close(); } catch (Throwable ignored) {}
        }
    }

    // ── One-shot layerStack capture (bug-report screenshot recorder) ──────────

    /**
     * Captures ONE frame of {@code layerStack} into a JPEG at {@code outPath}, using the exact
     * same SurfaceControl mirror primitives as {@link #setupMirror} (the only path proven to work
     * on these BYD ROMs) but pointed at an {@link ImageReader} surface so the daemon can read the
     * pixels back. `screencap -d N` cannot be used for the cluster: it silently falls back to
     * display 0 on a virtual display. Runs entirely inside the uid-2000 daemon (the only process
     * that may call SurfaceControl.createDisplay); writes to /data/local/tmp (uid-2000-writable,
     * A13-safe). The capture display is torn down in finally, so nothing leaks.
     *
     * @return "OK &lt;path&gt; &lt;bytes&gt;B" on success, or "FAIL …" / "EXCEPTION …".
     */
    /** Serializes captures against each other WITHOUT sharing the mirror's monitor — a slow/failed
     *  capture (up to ~1.5s waiting for a frame) must never block a visible mirror START/STOP. */
    private static final Object sCaptureLock = new Object();

    @SuppressLint({"NewApi", "WrongConstant"}) // RGBA_8888 is a PixelFormat, correct for display capture
    private static String captureLayerStackToJpeg(int layerStack, int w, int h,
                                                  int quality, String outPath,
                                                  int maxFiles, int maxAgeMin) {
      synchronized (sCaptureLock) {
        if (w <= 0 || h <= 0 || outPath == null) return "FAIL bad-args";
        IBinder token = null;
        ImageReader reader = null;
        Image image = null;
        Bitmap bmp = null;
        SurfaceControl.Transaction tx = null;
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            Surface surface = reader.getSurface();

            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Method createDisplay = scClass.getDeclaredMethod("createDisplay", String.class, boolean.class);
            createDisplay.setAccessible(true);
            token = (IBinder) createDisplay.invoke(null, "byd_shot_capture", false);
            if (token == null) return "FAIL createDisplay-null";

            tx = new SurfaceControl.Transaction();
            Class<?> txClass = tx.getClass();
            Method setLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack", IBinder.class, int.class);
            setLayerStack.setAccessible(true);
            setLayerStack.invoke(tx, token, layerStack);
            Method setSurface = txClass.getDeclaredMethod("setDisplaySurface", IBinder.class, Surface.class);
            setSurface.setAccessible(true);
            setSurface.invoke(tx, token, surface);
            Method setProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setProjection.setAccessible(true);
            Rect full = new Rect(0, 0, w, h);
            setProjection.invoke(tx, token, 0, full, full);
            tx.apply();

            // Wait for SurfaceFlinger to composite one frame into the reader (up to ~1.5s).
            for (int i = 0; i < 30 && image == null; i++) {
                Thread.sleep(50);
                image = reader.acquireLatestImage();
            }
            if (image == null) return "FAIL no-frame";

            bmp = imageToBitmap(image, w, h);
            if (bmp == null) return "FAIL decode-null";

            File out = new File(outPath);
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) { //noinspection ResultOfMethodCallIgnored
                dir.mkdirs(); }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, quality)), fos);
            }
            long bytes = out.length();
            // Enforce the ring buffer HERE, in-process, right after the write — same channel that
            // produced the file. (The app-side shell prune uses ADB-TCP, which can drop while this
            // binder path keeps writing; pruning here makes the bound transport-independent, so the
            // shots can never grow without limit — the user's hard constraint.)
            pruneShotDir(dir, maxFiles, maxAgeMin);
            out("captureLayerStack ok stack=" + layerStack + " " + w + "x" + h
                    + " -> " + outPath + " (" + bytes + "B)");
            return "OK " + outPath + " " + bytes + "B";
        } catch (Throwable t) {
            out("captureLayerStack EXCEPTION stack=" + layerStack + ": " + t);
            return "EXCEPTION " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (image != null) { try { image.close(); } catch (Throwable ignore) {} }
            if (bmp != null)   { try { bmp.recycle(); } catch (Throwable ignore) {} }
            if (reader != null){ try { reader.close(); } catch (Throwable ignore) {} }
            // Close the SurfaceControl.Transaction deterministically (it holds a native
            // SurfaceComposerClient transaction freed only on GC otherwise); this runs on a 15s cadence.
            if (tx != null)    { try { tx.close(); } catch (Throwable ignore) {} }
            if (token != null) {
                try {
                    Method destroy = Class.forName("android.view.SurfaceControl")
                            .getDeclaredMethod("destroyDisplay", IBinder.class);
                    destroy.setAccessible(true);
                    destroy.invoke(null, token);
                } catch (Throwable e) {
                    Log.w(TAG, "captureLayerStack: destroyDisplay failed: " + e.getMessage());
                }
            }
        }
      }
    }

    /**
     * Ring-buffer prune of the shots dir: keep at most {@code maxFiles} newest JPEGs and drop any
     * older than {@code maxAgeMin} minutes. Pure java.io in the uid-2000 daemon (no shell), so it
     * runs on the same channel as the write and can never lag behind it.
     */
    private static void pruneShotDir(File dir, int maxFiles, int maxAgeMin) {
        if (dir == null) return;
        try {
            File[] files = dir.listFiles((d, name) ->
                    name.startsWith("shot_") && name.endsWith(".jpg"));
            if (files == null || files.length == 0) return;
            long cutoff = maxAgeMin > 0
                    ? System.currentTimeMillis() - maxAgeMin * 60_000L : Long.MIN_VALUE;
            // Newest first.
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < files.length; i++) {
                boolean overCount = maxFiles > 0 && i >= maxFiles;
                boolean tooOld    = files[i].lastModified() < cutoff;
                if (overCount || tooOld) { //noinspection ResultOfMethodCallIgnored
                    files[i].delete(); }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pruneShotDir failed: " + t.getMessage());
        }
    }

    /** RGBA_8888 {@link Image} → {@link Bitmap}, handling the plane's row-stride padding. */
    private static Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride   = planes[0].getRowStride();
        int rowPadding  = rowStride - pixelStride * w;
        int stridePx    = w + (pixelStride == 0 ? 0 : rowPadding / pixelStride);
        Bitmap padded = Bitmap.createBitmap(stridePx, h, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (stridePx == w) return padded;
        // Crop away the stride padding on the right edge.
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
        padded.recycle();
        return cropped;
    }

    private static synchronized boolean attachMirrorOwner(IBinder owner) {
        clearMirrorOwnerLease();
        if (owner == null) {
            out("setupMirror legacy client: no death lease");
            return true;
        }
        try {
            DeathLease lease = DeathLease.attach(new BinderDeathOwner(owner), () -> {
                out("mirror owner died — releasing transient mirror");
                stopMirror();
            });
            sMirrorOwnerLease = lease;
            return lease.isActive();
        } catch (Throwable t) {
            out("setupMirror owner link failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return false;
        }
    }

    private static void clearMirrorOwnerLease() {
        DeathLease lease = sMirrorOwnerLease;
        sMirrorOwnerLease = null;
        if (lease != null) lease.close();
    }

    private static synchronized void stopMirror() {
        clearMirrorOwnerLease();
        IBinder token = sMirrorToken;
        sMirrorToken = null;
        if (token != null) {
            try {
                Class<?> scClass = Class.forName("android.view.SurfaceControl");
                Method destroyDisplay = scClass.getDeclaredMethod("destroyDisplay", IBinder.class);
                destroyDisplay.setAccessible(true);
                destroyDisplay.invoke(null, token);
                Log.i(TAG, "stopMirror ✓");
            } catch (Exception e) {
                Log.w(TAG, "stopMirror: destroyDisplay failed: " + e.getMessage());
            }
        }
    }

    // ── Input injection ───────────────────────────────────────────────────────

    private static void injectMotion(MotionEvent ev) {
        if (ev == null || sInputManager == null) {
            if (!sMotionFirstLogged) {
                sMotionFirstLogged = true;
                out("injectMotion FAIL pre-check: ev=" + (ev != null) + " im=" + (sInputManager != null));
            }
            return;
        }
        try {
            if (sSetDisplayId != null) sSetDisplayId.invoke(ev, sClusterDisplayId);
            Object r = sInjectMethod.invoke(sInputManager, ev, 0 /* ASYNC */);
            if (!sMotionFirstLogged) {
                sMotionFirstLogged = true;
                out("injectMotion FIRST OK displayId=" + sClusterDisplayId
                        + " setDisplayIdAvail=" + (sSetDisplayId != null)
                        + " action=" + ev.getActionMasked()
                        + " x=" + (int) ev.getX() + " y=" + (int) ev.getY()
                        + " ret=" + r);
            }
        } catch (Exception e) {
            Log.w(TAG, "injectMotion failed: " + e.getMessage());
            out("injectMotion EXCEPTION displayId=" + sClusterDisplayId
                    + " action=" + ev.getActionMasked() + " err=" + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static void injectKey(KeyEvent kev) {
        if (kev == null || sInputManager == null) return;
        try {
            // v1.2.11 — route the KeyEvent to the cluster display, same as MotionEvent.
            // Without this, keys go to the globally focused window (= our own
            // KeyboardBridgeActivity on display 0) and never reach the cluster
            // app. Mirrors the touch-injection displayId pattern.
            if (sSetDisplayIdKey != null) {
                try { sSetDisplayIdKey.invoke(kev, sClusterDisplayId); }
                catch (Exception ignored) { /* fall through, inject anyway */ }
            }
            sInjectMethod.invoke(sInputManager, kev, 0 /* ASYNC */);
            if (!sKeyFirstLogged) {
                sKeyFirstLogged = true;
                out("injectKey FIRST OK displayId=" + sClusterDisplayId
                        + " setDisplayIdAvail=" + (sSetDisplayIdKey != null)
                        + " keyCode=" + kev.getKeyCode() + " action=" + kev.getAction());
            }
        } catch (Exception e) {
            Log.w(TAG, "injectKey failed: " + e.getMessage());
            out("injectKey EXCEPTION keyCode=" + kev.getKeyCode() + " err=" + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static void initInputManager() {
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            sInputManager = getInstance.invoke(null);
            sInjectMethod = imClass.getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            sInjectMethod.setAccessible(true);
            try {
                sSetDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                sSetDisplayId.setAccessible(true);
            } catch (Exception ignored) { /* ROM sans setDisplayId */ }
            try {
                sSetDisplayIdKey = KeyEvent.class.getDeclaredMethod("setDisplayId", int.class);
                sSetDisplayIdKey.setAccessible(true);
            } catch (Exception ignored) { /* ROM sans KeyEvent.setDisplayId */ }
            Log.i(TAG, "InputManager init OK");
        } catch (Exception e) {
            Log.e(TAG, "initInputManager failed", e);
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    // 1.2.30 — custom signature permission tag used as the receiverPermission on
    // the ACTION_DAEMON_READY broadcast. Only apps signed with the same cert as
    // this APK (= our own app) hold the permission and can receive the binder.
    // The fallback ServiceManager.getService("byd_mirror_daemon") path still
    // works for ROMs where receiverPermission filtering misbehaves.
    public static final String PERM_DAEMON_READY = "com.byd.dashcast.permission.DAEMON_READY";

    private static void broadcastBinder(Context context, IBinder binder) {
        Bundle extras = new Bundle();
        extras.putBinder("daemon_binder", binder);
        Intent intent = new Intent(ACTION_DAEMON_READY);
        intent.putExtras(extras);
        context.sendBroadcast(intent, PERM_DAEMON_READY);
    }

    // ── Hidden API unlock ─────────────────────────────────────────────────────

    private static void unlockHiddenApis() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
            Class<?> vmRuntimeClass = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", null);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                    "setHiddenApiExemptions", new Class<?>[]{String[].class});
            setExemptions.invoke(vmRuntime,
                    new Object[]{new String[]{"Landroid/", "Lcom/android/", "Ljava/lang/"}});
            Log.i(TAG, "unlockHiddenApis OK");
        } catch (Exception e) {
            Log.e(TAG, "unlockHiddenApis failed", e);
        }
    }

    // ── Fission handlers ──────────────────────────────────────────────────────
    // NOTE: data.enforceInterface(DESCRIPTOR) is already called by onTransact —
    // do NOT call it again inside these handlers.

    private static boolean handleClusterAttach(Parcel data, Parcel reply) {
        @SuppressWarnings("unused") int layerStack = data.readInt(); // wire compat
        int w = data.readInt(), h = data.readInt();
        out("[Fission] CLUSTER_ATTACH " + w + "×" + h);
        SlotInfo existing = sSlots.remove("__default__");
        if (existing != null) existing.release();
        SlotInfo slot = new SlotInfo("__default__", 0, 0, w, h);
        Surface surface = tryAttachSlotOverlay(slot);
        if (surface == null) { reply.writeNoException(); reply.writeInt(0); return true; }
        int displayId = createTrustedVdForSlot(slot, surface, "dashcast_cluster_default");
        if (displayId < 0) { slot.release(); reply.writeNoException(); reply.writeInt(0); return true; }
        sSlots.put("__default__", slot);
        out("[Fission] CLUSTER_ATTACH OK displayId=" + displayId);
        reply.writeNoException(); reply.writeInt(1);
        reply.writeParcelable(surface, 0); reply.writeInt(displayId);
        return true;
    }

    private static boolean handleAttachSlot(Parcel data, Parcel reply) {
        String pkg = data.readString();
        int x = data.readInt(), y = data.readInt();
        int w = data.readInt(), h = data.readInt();
        out("[Fission] ATTACH_SLOT pkg=" + pkg + " (" + x + "," + y + "," + w + "×" + h + ")");
        SlotInfo existing = sSlots.remove(pkg);
        if (existing != null) existing.release();
        SlotInfo slot = new SlotInfo(pkg, x, y, w, h);
        Surface surface = tryAttachSlotOverlay(slot);
        if (surface == null) { reply.writeNoException(); reply.writeInt(0); return true; }
        int displayId = createTrustedVdForSlot(slot, surface,
                "dashcast_slot_" + pkg.replace('.', '_'));
        if (displayId < 0) { slot.release(); reply.writeNoException(); reply.writeInt(0); return true; }
        sSlots.put(pkg, slot);
        out("[Fission] ATTACH_SLOT OK pkg=" + pkg + " displayId=" + displayId);
        reply.writeNoException(); reply.writeInt(1);
        reply.writeParcelable(surface, 0); reply.writeInt(displayId);
        return true;
    }

    private static boolean handleReleaseSlot(Parcel data, Parcel reply) {
        String pkg = data.readString();
        out("[Fission] RELEASE_SLOT pkg=" + pkg);
        SlotInfo slot = sSlots.remove(pkg);
        if (slot != null) slot.release();
        reply.writeNoException(); reply.writeInt(1);
        return true;
    }

    private static boolean handleResizeSlot(Parcel data, Parcel reply) {
        String pkg = data.readString();
        int x = data.readInt(), y = data.readInt();
        int w = data.readInt(), h = data.readInt();
        out("[Fission] RESIZE_SLOT pkg=" + pkg + " (" + x + "," + y + "," + w + "×" + h + ")");
        SlotInfo slot = sSlots.get(pkg);
        if (slot == null) { reply.writeNoException(); reply.writeInt(0); return true; }
        final CountDownLatch latch = new CountDownLatch(1);
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            try {
                WindowManager.LayoutParams lp = createOverlayLayoutParams(null, w, h);
                lp.x = x; lp.y = y;
                slot.overlayWM.updateViewLayout(slot.overlayView, lp);
                ((SurfaceView) slot.overlayView).getHolder().setFixedSize(w, h);
                slot.x = x; slot.y = y; slot.w = w; slot.h = h;
            } catch (Exception e) {
                out("[Fission] RESIZE_SLOT overlay error: " + e.getMessage());
            } finally { latch.countDown(); }
        });
        try { latch.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (latch.getCount() == 0) {
            try { slot.vd.resize(w, h, 160); }
            catch (Exception e) { out("[Fission] RESIZE_SLOT VD error: " + e.getMessage()); }
        } else {
            out("[Fission] RESIZE_SLOT overlay timed out — VD resize skipped to avoid size mismatch");
        }
        reply.writeNoException(); reply.writeInt(1);
        return true;
    }

    private static boolean handleActivateLayout(Parcel data, Parcel reply) {
        int n = data.readInt();
        out("[Fission] ACTIVATE_LAYOUT n=" + n);
        for (String key : new java.util.ArrayList<>(sSlots.keySet())) {
            if (key.startsWith("layout_")) {
                SlotInfo s = sSlots.remove(key); if (s != null) s.release();
            }
        }
        reply.writeNoException(); reply.writeInt(n);
        for (int i = 0; i < n; i++) {
            String label = data.readString();
            int x = data.readInt(), y = data.readInt();
            int w = data.readInt(), h = data.readInt();
            String safe = label.replaceAll("[^A-Za-z0-9_]", "_");
            String key  = "layout_" + safe + "_" + i;
            SlotInfo slot = new SlotInfo(key, x, y, w, h);
            Surface surface = tryAttachSlotOverlay(slot);
            if (surface == null) { reply.writeInt(-1); continue; }
            int displayId = createTrustedVdForSlot(slot, surface, "dashcast_layout_" + safe);
            if (displayId < 0) { slot.release(); reply.writeInt(-1); continue; }
            sSlots.put(key, slot);
            out("[Fission] ACTIVATE_LAYOUT [" + label + "] → displayId=" + displayId);
            reply.writeInt(displayId);
        }
        return true;
    }

    private static boolean handleQuerySlot(Parcel data, Parcel reply) {
        String pkg = data.readString();
        SlotInfo slot = sSlots.get(pkg);
        int displayId = (slot != null && slot.vd != null) ? slot.displayId : -1;
        out("[Fission] QUERY_SLOT pkg=" + pkg + " → displayId=" + displayId);
        reply.writeNoException();
        reply.writeInt(displayId);
        return true;
    }

    private static boolean handleMoveToDisplay0(Parcel data, Parcel reply) {
        String pkg = data.readString();
        out("[Fission] MOVE_TO_DISPLAY0 pkg=" + pkg);
        int taskId = Phase4TaskVerbs.findTaskIdForPackage(pkg);
        String result;
        if (taskId <= 0) {
            result = "no task for " + pkg;
        } else {
            result = Phase4TaskVerbs.moveTaskToDisplay(taskId, 0);
        }
        out("[Fission] MOVE_TO_DISPLAY0 result: " + result);
        reply.writeNoException();
        reply.writeString(result);
        return true;
    }

    private static boolean handleDeactivateLayout(Parcel data, Parcel reply) {
        out("[Fission] DEACTIVATE_LAYOUT");
        for (String key : new java.util.ArrayList<>(sSlots.keySet())) {
            if (key.startsWith("layout_")) {
                SlotInfo s = sSlots.remove(key); if (s != null) s.release();
            }
        }
        reply.writeNoException(); reply.writeInt(1);
        return true;
    }

    // ── Fission helpers ───────────────────────────────────────────────────────

    /** Returns the cluster display (id ≥ 1). Refuses to return display 0 under any circumstances. */
    private static Display resolveClusterDisplay() {
        Context dmCtx = (sSysContext != null) ? sSysContext : sContext;
        if (dmCtx == null) { out("[Fission] resolveClusterDisplay: no context"); return null; }
        DisplayManager dm = dmCtx.getSystemService(DisplayManager.class);
        if (dm == null) return null;
        Display d = dm.getDisplay(1);
        if (d != null) {
            if (d.getDisplayId() == 0) {
                out("SAFETY: resolveClusterDisplay: display(1) resolved to id=0 — REFUSED");
                return null;
            }
            return d;
        }
        for (Display candidate : dm.getDisplays()) {
            String name = candidate.getName();
            if (name == null) continue;
            if (name.toLowerCase(Locale.US).contains("cluster")
                    || name.toLowerCase(Locale.US).contains("fission")) {
                if (candidate.getDisplayId() == 0) {
                    out("SAFETY: resolveClusterDisplay: name-match resolved to id=0 — REFUSED");
                    continue;
                }
                return candidate;
            }
        }
        return null;
    }

    /** Creates an overlay SurfaceView on the cluster display and returns its Surface. */
    private static Surface tryAttachSlotOverlay(SlotInfo slot) {
        try {
            out("[ATTACH_SLOT] step1: resolveClusterDisplay pkg=" + slot.pkg);
            Display target = resolveClusterDisplay();
            if (target == null) {
                out("[ATTACH_SLOT] FAIL: cluster display not found");
                return null;
            }
            out("[ATTACH_SLOT] step2: targetDisplay=" + target.getDisplayId()
                    + " latch setup pkg=" + slot.pkg);
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Surface> surfaceRef = new AtomicReference<>();
            final AtomicReference<String>  errorRef   = new AtomicReference<>();
            // Set true when the binder thread gives up (timeout / invalid surface). The
            // attach runnable checks it under the slot monitor after wm.addView so a
            // late-running attach removes its own window instead of leaking it.
            final java.util.concurrent.atomic.AtomicBoolean aborted =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            Runnable attach = () -> {
                try {
                    // Use the shell-identity context (pkg="com.android.shell", uid=2000).
                    // On Android 12 (DL5) WMS strictly checks context.getPackageName() against
                    // the calling uid — sSysContext has pkg="android" (uid=1000) which fails.
                    Context base = (sContext != null) ? sContext : sSysContext;
                    Context displayCtx = null;
                    try { displayCtx = base.createDisplayContext(target); }
                    catch (Exception e) {
                        out("[ATTACH_SLOT] createDisplayContext: " + e.getMessage());
                    }
                    boolean hasRes = displayCtx != null && displayCtx.getResources() != null;
                    Context viewCtx = hasRes ? displayCtx : base;
                    out("[ATTACH_SLOT] step3: displayCtx=" + (displayCtx != null ? "ok" : "null")
                            + " resources=" + (hasRes ? "ok" : "null") + " pkg=" + slot.pkg);

                    // Grant OP_SYSTEM_ALERT_WINDOW (op=24) to our uid via AppOps
                    if (sContext != null) {
                        try {
                            Object appOps = sContext.getSystemService(Context.APP_OPS_SERVICE);
                            Method setMode = appOps.getClass().getMethod(
                                    "setMode", int.class, int.class, String.class, int.class);
                            setMode.setAccessible(true);
                            setMode.invoke(appOps, 24, android.os.Process.myUid(),
                                    sContext.getPackageName(), 0);
                            out("[ATTACH_SLOT] OP_SYSTEM_ALERT_WINDOW granted");
                        } catch (Exception appE) {
                            out("[ATTACH_SLOT] AppOps skipped (" + appE.getMessage() + ")");
                        }
                    }

                    SurfaceView sv = new SurfaceView(viewCtx);
                    sv.getHolder().setFixedSize(slot.w, slot.h);
                    sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                        @Override public void surfaceCreated(SurfaceHolder h) {
                            Surface s = h.getSurface();
                            if (s != null && s.isValid()) {
                                out("[ATTACH_SLOT] surfaceCreated valid pkg=" + slot.pkg);
                                surfaceRef.compareAndSet(null, s); latch.countDown();
                            }
                        }
                        @Override public void surfaceChanged(SurfaceHolder h, int f, int w2, int h2) {
                            Surface s = h.getSurface();
                            if (s != null && s.isValid()) {
                                out("[ATTACH_SLOT] surfaceChanged valid " + w2 + "x" + h2
                                        + " pkg=" + slot.pkg);
                                surfaceRef.compareAndSet(null, s); latch.countDown();
                            }
                        }
                        @Override public void surfaceDestroyed(SurfaceHolder h) {
                            out("[ATTACH_SLOT] surfaceDestroyed pkg=" + slot.pkg);
                        }
                    });

                    WindowManager wm = (displayCtx != null)
                            ? displayCtx.getSystemService(WindowManager.class) : null;
                    if (wm == null) {
                        errorRef.set("WM null for display " + target.getDisplayId());
                        latch.countDown(); return;
                    }
                    WindowManager.LayoutParams lp = createOverlayLayoutParams(target, slot.w, slot.h);
                    lp.x = slot.x; lp.y = slot.y;
                    out("[ATTACH_SLOT] step4: wm.addView display=" + target.getDisplayId()
                            + " pos=" + lp.x + "," + lp.y + " size=" + slot.w + "x" + slot.h);
                    wm.addView(sv, lp);
                    // Publish under the slot monitor and re-check the abort flag: if the
                    // binder thread already timed out, remove this just-added window here
                    // (we are on the main looper — the correct thread for removeView) rather
                    // than leak an orphaned TYPE_SYSTEM_OVERLAY in the permanent daemon.
                    synchronized (slot) {
                        if (aborted.get()) {
                            try { wm.removeViewImmediate(sv); }
                            catch (Exception ignore) {}
                        } else {
                            slot.overlayView = sv;
                            slot.overlayWM = wm;
                        }
                    }
                } catch (Exception e) {
                    out("[ATTACH_SLOT] error: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                    errorRef.set(e.getMessage()); latch.countDown();
                }
            };

            if (Looper.myLooper() == Looper.getMainLooper()) attach.run();
            else new android.os.Handler(Looper.getMainLooper()).post(attach);

            if (!latch.await(2, TimeUnit.SECONDS)) {
                out("[ATTACH_SLOT] TIMEOUT 2s pkg=" + slot.pkg);
                abortOverlayAttach(slot, aborted); return null;
            }
            if (errorRef.get() != null) {
                out("[ATTACH_SLOT] FAIL: " + errorRef.get());
                abortOverlayAttach(slot, aborted); return null;
            }
            Surface surface = surfaceRef.get();
            if (surface == null || !surface.isValid()) {
                out("[ATTACH_SLOT] FAIL: no valid surface pkg=" + slot.pkg);
                abortOverlayAttach(slot, aborted); return null;
            }
            out("[ATTACH_SLOT] OK surface valid pkg=" + slot.pkg
                    + " display=" + target.getDisplayId());
            return surface;
        } catch (Exception e) {
            out("[ATTACH_SLOT] exception: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Abort an in-flight overlay attach after a failure: mark the attach runnable to
     * self-remove if it runs late, then release any window it already added. Prevents an
     * orphaned TYPE_SYSTEM_OVERLAY window (+ Surface) accumulating in this permanent daemon
     * on every slow/failed attach. The slot monitor orders this against the runnable's
     * publish so the window is removed exactly once (here, or by the late runnable).
     */
    private static void abortOverlayAttach(SlotInfo slot,
            java.util.concurrent.atomic.AtomicBoolean aborted) {
        synchronized (slot) {
            aborted.set(true);
        }
        slot.release();
    }

    /** Creates a TRUSTED VirtualDisplay for the given slot. Never returns display id=0. */
    @SuppressLint("WrongConstant")
    private static int createTrustedVdForSlot(SlotInfo slot, Surface surface, String vdName) {
        try {
            out("[CREATE_VD] name=" + vdName + " size=" + slot.w + "x" + slot.h + " dpi=160");
            DisplayManager dm = sContext.getSystemService(DisplayManager.class);
            VirtualDisplay vd = null;
            try {
                // flags 1346 = PRESENTATION(2)|SUPPORTS_TOUCH(64)|DESTROY_ON_REMOVAL(256)|TRUSTED(1024)
                vd = dm.createVirtualDisplay(vdName, slot.w, slot.h, 160, surface, 1346);
                if (vd != null) out("[CREATE_VD] TRUSTED OK name=" + vdName);
            } catch (Exception e) {
                out("[CREATE_VD] TRUSTED failed, fallback flags=322: " + e.getMessage());
            }
            if (vd == null) {
                // flags 322 = PRESENTATION(2)|SUPPORTS_TOUCH(64)|DESTROY_ON_REMOVAL(256)
                vd = dm.createVirtualDisplay(vdName, slot.w, slot.h, 160, surface, 322);
                if (vd != null) out("[CREATE_VD] fallback OK name=" + vdName);
            }
            if (vd == null) { out("[CREATE_VD] FAIL: both flags failed name=" + vdName); return -1; }
            slot.vd = vd;
            slot.displayId = vd.getDisplay().getDisplayId();
            // Safety guard: new VD must never land on display 0
            if (slot.displayId == 0) {
                out("SAFETY: createTrustedVdForSlot: VD got displayId=0 — RELEASING");
                vd.release(); slot.vd = null; return -1;
            }
            out("[CREATE_VD] OK displayId=" + slot.displayId + " name=" + vdName);
            return slot.displayId;
        } catch (Exception e) {
            out("[Fission] createTrustedVdForSlot error: " + e.getMessage());
            return -1;
        }
    }

    private static WindowManager.LayoutParams createOverlayLayoutParams(
            Display target, int w, int h) {
        // TYPE_SYSTEM_OVERLAY (2006): INTERNAL_SYSTEM_WINDOW is granted to shell uid=2000.
        int overlayType = 2006; // TYPE_SYSTEM_OVERLAY: INTERNAL_SYSTEM_WINDOW granted to shell uid=2000
        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, overlayType, flags, android.graphics.PixelFormat.OPAQUE);
        lp.setTitle("dashcast_fission_overlay");
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;
        // WMS resolves TYPE_SYSTEM_OVERLAY permission against this package — must be "com.android.shell"
        // (uid=2000) which holds INTERNAL_SYSTEM_WINDOW. Matches DevTool pattern.
        lp.packageName = "com.android.shell";
        out("[Fission] createOverlayLayoutParams type=" + overlayType
                + " size=" + w + "×" + h
                + (target != null ? " display=" + target.getDisplayId() : "")
                + " pkg=" + lp.packageName);
        return lp;
    }
}
