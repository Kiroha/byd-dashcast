package com.byd.myapp.dashboard;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * Miroir cluster v2.36 — SurfaceControl.createDisplay + setDisplayLayerStack.
 *
 * Principe (identique à WindowManagement) :
 *   - SurfaceControl.createDisplay("mybyd_preview_mirror", false)
 *   - Transaction.setDisplayLayerStack(token, clusterLayerStack) → miroir du contenu cluster
 *   - Transaction.setDisplaySurface(token, ourSurface) → vers notre SurfaceView
 *   - Transaction.setDisplayProjection(token, 0, srcRect, destRect)
 *   → Le SurfaceFlinger composite le cluster dans notre surface. Pas de VirtualDisplay.
 *
 * Requiert : ACCESS_SURFACE_FLINGER (signature permission, accordée avec platform.keystore)
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    private static final int VDISPLAY_FLAGS = 320;

    // ── SurfaceControl mirror token (nouveau) ──────────────────────────────
    private IBinder mMirrorDisplayToken = null;
    private Surface mMirrorSurface      = null;

    // ── Local preview ────────────────────────────────────────────────────────
    private VirtualDisplay mPreviewVD    = null;
    private int            mPreviewDisplayId = -1;

    private boolean mMirrorActive = false;
    private int     mClusterW = 1920;
    private int     mClusterH = 720;

    public int     getClusterWidth()           { return mClusterW; }
    public int     getClusterHeight()          { return mClusterH; }
    public boolean isMirrorActive()            { return mMirrorActive; }
    public int     getPreviewDisplayId()       { return mPreviewDisplayId; }

    /**
     * Déverrouille les APIs cachées (SurfaceControl, Display.getLayerStack, etc.).
     */
    public static void unlockHiddenApis() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
            Class<?> vmRuntimeClass = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                    "setHiddenApiExemptions", new Class[]{String[].class});
            setExemptions.invoke(vmRuntime, new Object[]{
                    new String[]{"Landroid/", "Lcom/android/", "Ljava/lang/"}
            });
            AppLogger.i(TAG, "unlockHiddenApis OK — SurfaceControl accessible");
        } catch (Exception e) {
            AppLogger.w(TAG, "unlockHiddenApis ERREUR : " + e.getMessage());
        }
    }

    // ── MIROIR SURFACECONTROL (nouveau — v2.36) ────────────────────────────

    /**
     * Miroir du contenu du cluster dans la Surface fournie, via SurfaceControl.
     *
     * Equivalent à ce que fait WindowManagement via son daemon (uid=2000) :
     *   SurfaceControl.createDisplay + setDisplayLayerStack(clusterLayerStack) + setDisplaySurface
     *
     * Requiert ACCESS_SURFACE_FLINGER (signature permission).
     * En cas d'échec, retourne false → fallback screencap dans l'appelant.
     *
     * @param targetSurface  Surface de notre SurfaceView local (dans l'app)
     * @param viewW / viewH  Dimensions de la vue (pour la projection)
     */
    public boolean startMirror(Context context, Display clusterDisplay, Surface targetSurface,
                               int viewW, int viewH) {
        if (mMirrorActive) {
            AppLogger.d(TAG, "Mirror déjà actif");
            return true;
        }
        stopPreview();

        if (targetSurface == null || !targetSurface.isValid()) {
            AppLogger.e(TAG, "startMirror : targetSurface invalide");
            return false;
        }

        // Dimensions cluster
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x; mClusterH = sz.y;
        }

        // ── Tentative SurfaceControl mirror ───────────────────────────────
        try {
            // 1. Layer stack du cluster (API cachée)
            int layerStack = 0;
            try {
                Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
                getLayerStack.setAccessible(true);
                layerStack = (Integer) getLayerStack.invoke(clusterDisplay);
                AppLogger.d(TAG, "Cluster layerStack=" + layerStack);
            } catch (Exception e) {
                // Sur certaines ROM le layerStack == displayId
                layerStack = (clusterDisplay != null) ? clusterDisplay.getDisplayId() : 2;
                AppLogger.w(TAG, "getLayerStack échoué → fallback layerStack=" + layerStack);
            }

            // 2. Créer un display token pour notre miroir
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Method createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String.class, boolean.class);
            createDisplay.setAccessible(true);
            mMirrorDisplayToken = (IBinder) createDisplay.invoke(null,
                    "mybyd_preview_mirror", false);
            if (mMirrorDisplayToken == null) {
                throw new RuntimeException("SurfaceControl.createDisplay → null");
            }

            // 3. Projection : conserver le ratio (letterbox)
            float scale   = Math.min((float) viewW / mClusterW, (float) viewH / mClusterH);
            int   drawW   = (int) (mClusterW * scale);
            int   drawH   = (int) (mClusterH * scale);
            int   offsetX = (viewW  - drawW) / 2;
            int   offsetY = (viewH  - drawH) / 2;
            Rect srcRect  = new Rect(0, 0, mClusterW, mClusterH);
            Rect destRect = new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH);

            // 4. Transaction SurfaceControl (méthodes cachées)
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            Class<?> txClass = tx.getClass();

            Method setDisplaySurface = txClass.getDeclaredMethod("setDisplaySurface",
                    IBinder.class, Surface.class);
            setDisplaySurface.setAccessible(true);

            Method setDisplayLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                    IBinder.class, int.class);
            setDisplayLayerStack.setAccessible(true);

            Method setDisplayProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            setDisplayProjection.setAccessible(true);

            setDisplayLayerStack.invoke(tx, mMirrorDisplayToken, layerStack);
            setDisplaySurface.invoke(tx, mMirrorDisplayToken, targetSurface);
            setDisplayProjection.invoke(tx, mMirrorDisplayToken, 0, srcRect, destRect);
            tx.apply();

            mMirrorSurface = targetSurface;
            mMirrorActive  = true;
            // mPreviewDisplayId reste -1 (pas de VirtualDisplay — le contenu vient du cluster)
            AppLogger.i(TAG, "SurfaceControl mirror ✓ layerStack=" + layerStack
                    + " src=" + mClusterW + "×" + mClusterH
                    + " dest=" + drawW + "×" + drawH + " offset=(" + offsetX + "," + offsetY + ")");
            return true;

        } catch (Exception e) {
            AppLogger.e(TAG, "SurfaceControl mirror ECHEC (ACCESS_SURFACE_FLINGER ?) — utiliser startMirrorViaDaemon()", e);
            destroyMirrorToken();
            return false;
        }
    }

    /**
     * Miroir via le daemon MirrorDaemon (uid=2000) qui a ACCESS_SURFACE_FLINGER.
     * Le daemon reçoit la Surface via Binder et configure SurfaceControl (méthodes statiques).
     * Appel SYNCHRONE : le daemon répond 1 (succès) ou 0 (échec) → mMirrorActive reflète
     * la réalité, ce qui permet le fallback screencap si le daemon échoue.
     */
    public boolean startMirrorViaDaemon(IBinder daemonBinder, Display clusterDisplay,
                                        Surface targetSurface, int viewW, int viewH) {
        if (mMirrorActive) return true;
        if (daemonBinder == null || targetSurface == null || !targetSurface.isValid()) return false;

        // Dimensions cluster
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;
        }

        int clusterDisplayId = (clusterDisplay != null) ? clusterDisplay.getDisplayId() : 2;
        int layerStack;
        try {
            Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
            getLayerStack.setAccessible(true);
            layerStack = (Integer) getLayerStack.invoke(clusterDisplay);
        } catch (Exception e) {
            layerStack = clusterDisplayId;
            AppLogger.w(TAG, "getLayerStack échoué → fallback layerStack=" + layerStack);
        }

        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(com.byd.myapp.daemon.MirrorDaemon.DESCRIPTOR);
            data.writeInt(layerStack);
            data.writeInt(mClusterW);
            data.writeInt(mClusterH);
            data.writeInt(clusterDisplayId);
            data.writeInt(viewW);
            data.writeInt(viewH);
            data.writeParcelable(targetSurface, 0);
            // Appel synchrone (pas FLAG_ONEWAY) → réponse du daemon dans reply
            daemonBinder.transact(com.byd.myapp.daemon.MirrorDaemon.TRANSACT_MIRROR_START,
                    data, reply, 0);
            reply.readException();
            boolean daemonOk = reply.readInt() == 1;
            if (daemonOk) {
                mMirrorSurface = targetSurface;
                mMirrorActive  = true;
                AppLogger.i(TAG, "startMirrorViaDaemon ✓ layerStack=" + layerStack
                        + " " + mClusterW + "×" + mClusterH + " displayId=" + clusterDisplayId);
            } else {
                AppLogger.e(TAG, "startMirrorViaDaemon : daemon a rapporté un échec"
                        + " (vérifier logcat MirrorDaemon pour le détail)");
            }
            return daemonOk;
        } catch (Exception e) {
            AppLogger.e(TAG, "startMirrorViaDaemon échoué", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Demande au daemon d'arrêter le miroir SurfaceControl.
     */
    public void stopMirrorViaDaemon(IBinder daemonBinder) {
        if (daemonBinder == null) return;
        try {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(com.byd.myapp.daemon.MirrorDaemon.DESCRIPTOR);
            daemonBinder.transact(com.byd.myapp.daemon.MirrorDaemon.TRANSACT_MIRROR_STOP,
                    data, null, android.os.IBinder.FLAG_ONEWAY);
            data.recycle();
        } catch (Exception ignored) {}
        mMirrorActive  = false;
        mMirrorSurface = null;
        AppLogger.i(TAG, "stopMirrorViaDaemon envoyé");
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void destroyMirrorToken() {
        if (mMirrorDisplayToken != null) {
            try {
                Class<?> scClass = Class.forName("android.view.SurfaceControl");
                Method destroyDisplay = scClass.getDeclaredMethod("destroyDisplay",
                        IBinder.class);
                destroyDisplay.setAccessible(true);
                destroyDisplay.invoke(null, mMirrorDisplayToken);
            } catch (Exception ignored) {}
            mMirrorDisplayToken = null;
            mMirrorSurface = null;
        }
    }

    private void stopPreview() {
        mMirrorActive = false;
        mPreviewDisplayId = -1;
        if (mPreviewVD != null) {
            try { mPreviewVD.release(); } catch (Exception ignored) {}
            mPreviewVD = null;
        }
        destroyMirrorToken();
    }

    /**
     * Arrête le preview local (appelé depuis MainActivity.onStop).
     */
    public void stopMirror(Context context) {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager preview arrêté");
    }

    /**
     * Libère le preview.
     * À appeler uniquement depuis ClusterService.onDestroy().
     */
    public void release(Context context) {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager libéré");
    }
}
