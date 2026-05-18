package com.byd.dashcast.daemon;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
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

import java.lang.reflect.Method;

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
public class MirrorDaemon {

    private static final String TAG = "MirrorDaemon";

    // Actions broadcast
    public static final String ACTION_DAEMON_READY  = "com.byd.dashcast.MIRROR_DAEMON_READY";

    // Interface Binder
    public static final String DESCRIPTOR            = "com.byd.dashcast.daemon.IMirrorDaemon";
    public static final int    TRANSACT_MIRROR_START  = 1;
    public static final int    TRANSACT_INJECT_MOTION = 2;
    public static final int    TRANSACT_INJECT_KEY    = 3;
    public static final int    TRANSACT_MIRROR_STOP   = 4;

    // Mirror state (shared between threads via Binder thread pool)
    private static volatile IBinder sMirrorToken     = null;
    private static volatile int     sClusterDisplayId = 2;

    // InputManager (init une seule fois, lu depuis les threads Binder → volatile)
    private static volatile Object  sInputManager    = null;
    private static volatile Method  sInjectMethod    = null;
    private static volatile Method  sSetDisplayId    = null;  // MotionEvent.setDisplayId — may be null

    // ─────────────────────────────────────────────────────────────────────────

    /** Thread-safe stdout helper (Log.* goes to logcat, not our redirected file) */
    private static void out(String msg) {
        System.out.println("[MirrorDaemon] " + msg);
        System.out.flush();
    }
    private static void err(String msg, Throwable t) {
        System.err.println("[MirrorDaemon][ERROR] " + msg);
        if (t != null) t.printStackTrace(System.err);
        System.err.flush();
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
            switch (code) {
                case TRANSACT_MIRROR_START: {
                    int layerStack    = data.readInt();
                    int clusterW      = data.readInt();
                    int clusterH      = data.readInt();
                    sClusterDisplayId = data.readInt();
                    int viewW         = data.readInt();
                    int viewH         = data.readInt();
                    Surface surface   = data.readParcelable(Surface.class.getClassLoader());
                    boolean ok = setupMirror(layerStack, clusterW, clusterH, viewW, viewH, surface);
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
    private static boolean setupMirror(int layerStack, int clusterW, int clusterH,
                                       int viewW, int viewH, Surface surface) {
        stopMirror();
        if (surface == null || !surface.isValid()) {
            Log.e(TAG, "setupMirror : surface invalide");
            return false;
        }
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // 1. Create the mirror display token
            Method createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String.class, boolean.class);
            createDisplay.setAccessible(true);
            sMirrorToken = (IBinder) createDisplay.invoke(null, "byd_myapp_mirror", false);
            if (sMirrorToken == null) {
                Log.e(TAG, "setupMirror : createDisplay → null");
                return false;
            }
            Log.i(TAG, "setupMirror : createDisplay token=" + sMirrorToken);

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
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
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
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"sh", "-c",
                                "dumpsys SurfaceFlinger 2>/dev/null"
                                + " | grep -iE 'byd_myapp_mirror|layerStack=" + layerStack + "'"});
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                p.waitFor();
                Log.i(TAG, "setupMirror SF dump :\n" + sb.toString().trim());
            } catch (Exception e) {
                Log.d(TAG, "SF dump read failed: " + e.getMessage());
            }

            Log.i(TAG, "setupMirror ✓ (Transaction) layerStack=" + layerStack
                    + " src=" + clusterW + "×" + clusterH
                    + " dst=" + drawW + "×" + drawH + " offset=(" + offX + "," + offY + ")");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "setupMirror failed", e);
            // If createDisplay succeeded but a later reflection step threw, the
            // SurfaceFlinger display token must be released — otherwise it leaks
            // for the lifetime of the daemon process. stopMirror() handles the
            // null case and clears sMirrorToken atomically.
            stopMirror();
            return false;
        }
    }

    private static void stopMirror() {
        IBinder token = sMirrorToken;
        if (token == null) return;
        sMirrorToken = null;
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

    // ── Input injection ───────────────────────────────────────────────────────

    private static void injectMotion(MotionEvent ev) {
        if (ev == null || sInputManager == null) return;
        try {
            if (sSetDisplayId != null) sSetDisplayId.invoke(ev, sClusterDisplayId);
            sInjectMethod.invoke(sInputManager, ev, 0 /* ASYNC */);
        } catch (Exception e) {
            Log.w(TAG, "injectMotion failed: " + e.getMessage());
        }
    }

    private static void injectKey(KeyEvent kev) {
        if (kev == null || sInputManager == null) return;
        try {
            sInjectMethod.invoke(sInputManager, kev, 0 /* ASYNC */);
        } catch (Exception e) {
            Log.w(TAG, "injectKey failed: " + e.getMessage());
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
            Log.i(TAG, "InputManager init OK");
        } catch (Exception e) {
            Log.e(TAG, "initInputManager failed", e);
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private static void broadcastBinder(Context context, IBinder binder) {
        Bundle extras = new Bundle();
        extras.putBinder("daemon_binder", binder);
        Intent intent = new Intent(ACTION_DAEMON_READY);
        intent.putExtras(extras);
        context.sendBroadcast(intent);
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
                    "setHiddenApiExemptions", new Class[]{String[].class});
            setExemptions.invoke(vmRuntime,
                    new Object[]{new String[]{"Landroid/", "Lcom/android/", "Ljava/lang/"}});
            Log.i(TAG, "unlockHiddenApis OK");
        } catch (Exception e) {
            Log.e(TAG, "unlockHiddenApis failed", e);
        }
    }
}
