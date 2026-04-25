package com.byd.myapp.daemon;

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
 * Daemon MirrorDaemon — lancé via app_process (uid=2000 shell).
 *
 * Expose un Binder (IMirrorDaemon) pour :
 *   - TRANSACT_MIRROR_START  (1) : configurer un miroir SurfaceControl du display cluster
 *   - TRANSACT_INJECT_MOTION (2) : injecter un MotionEvent sur le display cluster
 *   - TRANSACT_INJECT_KEY    (3) : injecter un KeyEvent
 *   - TRANSACT_MIRROR_STOP   (4) : détruire le miroir
 *
 * Le Binder est diffusé via ACTION_DAEMON_READY au démarrage et sur demande
 * ACTION_REQUEST_BINDER. Seul uid=2000 peut appeler SurfaceControl.createDisplay()
 * et InputManager.injectInputEvent() sans permission supplémentaire.
 */
public class MirrorDaemon {

    private static final String TAG = "MirrorDaemon";

    // Actions broadcast
    public static final String ACTION_DAEMON_READY   = "com.byd.myapp.MIRROR_DAEMON_READY";
    public static final String ACTION_DAEMON_LAUNCH  = "com.byd.myapp.MIRROR_DAEMON_LAUNCH";
    public static final String ACTION_REQUEST_BINDER = "com.byd.myapp.MIRROR_REQUEST_BINDER";

    // Interface Binder
    public static final String DESCRIPTOR            = "com.byd.myapp.daemon.IMirrorDaemon";
    public static final int    TRANSACT_MIRROR_START  = 1;
    public static final int    TRANSACT_INJECT_MOTION = 2;
    public static final int    TRANSACT_INJECT_KEY    = 3;
    public static final int    TRANSACT_MIRROR_STOP   = 4;

    // État miroir (partagé entre threads via Binder thread pool)
    private static volatile IBinder sMirrorToken     = null;
    private static volatile int     sClusterDisplayId = 2;

    // InputManager (init une seule fois)
    private static Object  sInputManager    = null;
    private static Method  sInjectMethod    = null;
    private static Method  sSetDisplayId    = null;  // MotionEvent.setDisplayId — peut être null

    // ─────────────────────────────────────────────────────────────────────────

    /** Helper stdout thread-safe (Log.* va dans logcat, pas dans notre fichier redirigé) */
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
        out("main() démarrage uid=" + android.os.Process.myUid());
        try {
            android.os.Process.class.getMethod("setArgV0", String.class)
                    .invoke(null, "com.byd.myapp.mirrordaemon");
            out("setArgV0 OK");
        } catch (Exception ignored) {
            out("setArgV0 ignoré : " + ignored.getMessage());
        }

        Log.i(TAG, "Démarrage MirrorDaemon uid=" + android.os.Process.myUid());

        try {
            out("Looper.getMainLooper()=" + Looper.getMainLooper());
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            out("Looper prêt");

            // System context (via ActivityThread)
            out("Chargement ActivityThread...");
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            out("ActivityThread trouvé, appel systemMain()...");
            Object thread = atClass.getMethod("systemMain").invoke(null);
            out("systemMain() retourné : " + thread);
            Context context = (Context) thread.getClass()
                    .getMethod("getSystemContext").invoke(thread);
            out("getSystemContext() retourné : " + context);
            if (context == null) {
                err("Context null — abandon", null);
                Log.e(TAG, "Context null");
                return;
            }
            Log.i(TAG, "Context système OK");
            out("Context système OK");

            // Déverrouiller les APIs cachées
            out("unlockHiddenApis()...");
            unlockHiddenApis();
            out("unlockHiddenApis OK");

            // Initialiser InputManager
            out("initInputManager()...");
            initInputManager();
            out("initInputManager OK");

            // Créer notre Binder (effectively final pour l'inner class)
            out("Création MirrorBinder...");
            final IBinder daemonBinder = new MirrorBinder();
            out("MirrorBinder créé");

            // Enregistrer dans ServiceManager (accessible par uid=2000) :
            // Remplace registerReceiver (interdit depuis systemMain() — AMS rejette
            // l'IApplicationThread non enregistré → SecurityException).
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
                err("ServiceManager.addService ECHEC — broadcast seul", eSm);
            }

            // SUPPRIMÉ : registerReceiver → SecurityException depuis systemMain()
            // AMS vérifie que l'IApplicationThread est dans mPidsSelfLocked → refusé
            // pour un process app_process non passé par la séquence de démarrage normale.
            // Remplacement : ServiceManager.addService() ci-dessus + sendBroadcast initial.

            // Annoncer notre présence (sendBroadcast fonctionne depuis systemMain())
            out("broadcastBinder()...");
            broadcastBinder(context, daemonBinder);
            Log.i(TAG, "MirrorDaemon prêt — Binder diffusé.");
            out("MirrorDaemon PRÊT — Binder dans ServiceManager + broadcast envoyé — Looper.loop() démarré");

            Looper.loop();
            out("Looper.loop() terminé (ne devrait pas arriver)");

        } catch (Exception e) {
            err("Crash MirrorDaemon", e);
            Log.e(TAG, "Crash MirrorDaemon", e);
        }
        out("main() terminé");
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
                    // Répondre au client (appel synchrone, pas oneway)
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(ok ? 1 : 0);
                    }
                    return true;
                }
                case TRANSACT_INJECT_MOTION: {
                    MotionEvent ev = data.readParcelable(MotionEvent.class.getClassLoader());
                    injectMotion(ev);
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
     * Configure le miroir via les méthodes STATIQUES de SurfaceControl (API deprecated mais
     * fonctionnelle sur Android 10 BYD ROM — identique à l'approche WindowManagement).
     * SurfaceControl.Transaction échoue silencieusement sur cette ROM.
     *
     * @return true si le miroir a été configuré avec succès
     */
    private static boolean setupMirror(int layerStack, int clusterW, int clusterH,
                                       int viewW, int viewH, Surface surface) {
        stopMirror();
        if (surface == null || !surface.isValid()) {
            Log.e(TAG, "setupMirror : surface invalide");
            return false;
        }
        boolean txOpen = false;
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // 1. Créer le display token de miroir
            Method createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String.class, boolean.class);
            createDisplay.setAccessible(true);
            sMirrorToken = (IBinder) createDisplay.invoke(null, "byd_myapp_mirror", false);
            if (sMirrorToken == null) {
                Log.e(TAG, "setupMirror : createDisplay → null");
                return false;
            }

            // 2. Projection letterbox (ratio préservé)
            float scale = Math.min((float) viewW / clusterW, (float) viewH / clusterH);
            int drawW   = (int) (clusterW * scale);
            int drawH   = (int) (clusterH * scale);
            int offX    = (viewW - drawW) / 2;
            int offY    = (viewH - drawH) / 2;
            Rect src = new Rect(0, 0, clusterW, clusterH);
            Rect dst = new Rect(offX, offY, offX + drawW, offY + drawH);

            // 3. Méthodes statiques SurfaceControl (approche WindowManagement)
            //    openTransaction / setDisplay* / closeTransaction
            Method openTx = scClass.getDeclaredMethod("openTransaction");
            openTx.setAccessible(true);
            openTx.invoke(null);
            txOpen = true;

            Method setLayerStack = scClass.getDeclaredMethod("setDisplayLayerStack",
                    IBinder.class, int.class);
            setLayerStack.setAccessible(true);
            setLayerStack.invoke(null, sMirrorToken, layerStack);

            Method setSurface = scClass.getDeclaredMethod("setDisplaySurface",
                    IBinder.class, Surface.class);
            setSurface.setAccessible(true);
            setSurface.invoke(null, sMirrorToken, surface);

            Method setProjection = scClass.getDeclaredMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setProjection.setAccessible(true);
            setProjection.invoke(null, sMirrorToken, 0, src, dst);

            Method closeTx = scClass.getDeclaredMethod("closeTransaction");
            closeTx.setAccessible(true);
            closeTx.invoke(null);
            txOpen = false;

            Log.i(TAG, "setupMirror ✓ (static API) layerStack=" + layerStack
                    + " src=" + clusterW + "×" + clusterH
                    + " dst=" + drawW + "×" + drawH + " offset=(" + offX + "," + offY + ")");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "setupMirror échoué", e);
            // S'assurer que la transaction est fermée en cas d'exception
            if (txOpen) {
                try {
                    Class<?> scClass2 = Class.forName("android.view.SurfaceControl");
                    Method closeTx2 = scClass2.getDeclaredMethod("closeTransaction");
                    closeTx2.setAccessible(true);
                    closeTx2.invoke(null);
                } catch (Exception ignored) {}
            }
            sMirrorToken = null;
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
            Log.w(TAG, "stopMirror : destroyDisplay échoué : " + e.getMessage());
        }
    }

    // ── Input injection ───────────────────────────────────────────────────────

    private static void injectMotion(MotionEvent ev) {
        if (ev == null || sInputManager == null) return;
        try {
            if (sSetDisplayId != null) sSetDisplayId.invoke(ev, sClusterDisplayId);
            sInjectMethod.invoke(sInputManager, ev, 0 /* ASYNC */);
        } catch (Exception e) {
            Log.w(TAG, "injectMotion échoué : " + e.getMessage());
        }
    }

    private static void injectKey(KeyEvent kev) {
        if (kev == null || sInputManager == null) return;
        try {
            sInjectMethod.invoke(sInputManager, kev, 0 /* ASYNC */);
        } catch (Exception e) {
            Log.w(TAG, "injectKey échoué : " + e.getMessage());
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
            Log.e(TAG, "initInputManager échoué", e);
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

    // ── App launch (hérité) ───────────────────────────────────────────────────

    private static void handleLaunch(Context c, Intent intent) {
        Log.i(TAG, "DAEMON_LAUNCH reçu");
        String pkg       = intent.getStringExtra("pkg");
        String cls       = intent.getStringExtra("cls");
        int displayId    = intent.getIntExtra("displayId", 0);
        int bl           = intent.getIntExtra("bounds_l", -1);
        int bt           = intent.getIntExtra("bounds_t", -1);
        int br           = intent.getIntExtra("bounds_r", -1);
        int bb           = intent.getIntExtra("bounds_b", -1);
        try {
            Intent launchIntent = new Intent();
            launchIntent.setComponent(new android.content.ComponentName(pkg, cls));
            launchIntent.addFlags(0x10008000); // NEW_TASK | CLEAR_TASK
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            if (bl >= 0 && br > bl && bb > bt) {
                opts.setLaunchBounds(new Rect(bl, bt, br, bb));
                try {
                    Method setWm = android.app.ActivityOptions.class
                            .getMethod("setLaunchWindowingMode", int.class);
                    setWm.invoke(opts, 5); // FREEFORM
                } catch (Exception ignored) {}
            }
            c.startActivity(launchIntent, opts.toBundle());
            Log.i(TAG, "Lancement ✓ " + pkg + "/" + cls + " display=" + displayId);
        } catch (Exception e) {
            Log.e(TAG, "handleLaunch échoué : " + pkg, e);
        }
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
            Log.e(TAG, "unlockHiddenApis échoué", e);
        }
    }
}
