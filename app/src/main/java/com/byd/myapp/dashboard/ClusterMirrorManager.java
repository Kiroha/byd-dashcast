package com.byd.myapp.dashboard;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * Miroir temps réel du display cluster (display 1) vers une SurfaceView sur l'écran principal.
 *
 * Mécanisme : SurfaceControl.createDisplay() crée un display virtuel dont le layerStack
 * est configuré pour être identique à celui du display cluster. Tout ce qui est rendu
 * sur le cluster apparaît instantanément dans la SurfaceView (aucun screenshot, aucune latence).
 *
 * Requiert android.permission.READ_FRAME_BUFFER (signature-level, accordée avec platform.keystore).
 *
 * Usage :
 *   // Dans SurfaceHolder.Callback.surfaceCreated / surfaceChanged :
 *   mirrorManager.startMirror(clusterDisplay, holder.getSurface(), viewW, viewH);
 *   // Dans SurfaceHolder.Callback.surfaceDestroyed :
 *   mirrorManager.stopMirror();
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    private IBinder mMirrorToken  = null;
    private boolean mMirrorActive = false;
    private int     mClusterW     = 1920;
    private int     mClusterH     = 720;

    public int  getClusterWidth()   { return mClusterW; }
    public int  getClusterHeight()  { return mClusterH; }
    public boolean isMirrorActive() { return mMirrorActive; }

    /**
     * Démarre le miroir SurfaceControl du display cluster vers la surface cible.
     *
     * @param clusterDisplay  Display cluster (obtenu via DisplayManager, id=1)
     * @param targetSurface   Surface de la SurfaceView sur l'écran principal
     * @param viewW           Largeur actuelle de la SurfaceView (pixels)
     * @param viewH           Hauteur actuelle de la SurfaceView (pixels)
     * @return true si le miroir a démarré avec succès
     */
    public boolean startMirror(Display clusterDisplay, Surface targetSurface,
                               int viewW, int viewH) {
        stopMirror();
        if (clusterDisplay == null) {
            AppLogger.e(TAG, "startMirror : clusterDisplay null");
            return false;
        }
        if (targetSurface == null || !targetSurface.isValid()) {
            AppLogger.e(TAG, "startMirror : targetSurface invalide");
            return false;
        }

        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");

            // ── 1. Obtenir le layerStack du display cluster ──────────────────
            Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
            getLayerStack.setAccessible(true);
            int layerStack = (int) getLayerStack.invoke(clusterDisplay);

            // ── 2. Dimensions réelles du cluster ─────────────────────────────
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;

            // ── 3. Créer un display miroir (non sécurisé) ─────────────────────
            Method createDisplay = scClass.getMethod("createDisplay",
                    String.class, boolean.class);
            IBinder mirrorToken = (IBinder) createDisplay.invoke(null,
                    "byd_cluster_mirror", false);

            // ── 4. Calculer le rectangle destination (ratio préservé) ─────────
            float scale   = Math.min((float) viewW / mClusterW, (float) viewH / mClusterH);
            int   drawW   = Math.round(mClusterW * scale);
            int   drawH   = Math.round(mClusterH * scale);
            int   offsetX = (viewW - drawW) / 2;
            int   offsetY = (viewH - drawH) / 2;
            Rect srcRect  = new Rect(0, 0, mClusterW, mClusterH);
            Rect dstRect  = new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH);

            // ── 5. Transaction SurfaceControl ──────────────────────────────────
            Method openTransaction      = scClass.getMethod("openTransaction");
            Method closeTransaction     = scClass.getMethod("closeTransaction");
            Method setDisplaySurface    = scClass.getMethod("setDisplaySurface",
                    IBinder.class, Surface.class);
            Method setDisplayLayerStack = scClass.getMethod("setDisplayLayerStack",
                    IBinder.class, int.class);
            Method setDisplayProjection = scClass.getMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);

            openTransaction.invoke(null);
            try {
                setDisplaySurface.invoke(null, mirrorToken, targetSurface);
                setDisplayLayerStack.invoke(null, mirrorToken, layerStack);
                setDisplayProjection.invoke(null, mirrorToken,
                        Surface.ROTATION_0, srcRect, dstRect);
            } finally {
                closeTransaction.invoke(null);
            }

            mMirrorToken  = mirrorToken;
            mMirrorActive = true;
            AppLogger.i(TAG, "Miroir SurfaceControl démarré — layerStack=" + layerStack
                    + " cluster=" + mClusterW + "×" + mClusterH
                    + " view=" + viewW + "×" + viewH + " dst=" + dstRect);
            return true;

        } catch (Exception e) {
            AppLogger.e(TAG, "startMirror SurfaceControl ERREUR", e);
            return false;
        }
    }

    /** Arrête le miroir et libère le display SurfaceControl. */
    public void stopMirror() {
        if (mMirrorToken != null) {
            try {
                Class<?> scClass = Class.forName("android.view.SurfaceControl");
                Method destroyDisplay = scClass.getMethod("destroyDisplay", IBinder.class);
                destroyDisplay.invoke(null, mMirrorToken);
                AppLogger.i(TAG, "Miroir SurfaceControl détruit");
            } catch (Exception e) {
                AppLogger.e(TAG, "destroyDisplay ERREUR", e);
            }
            mMirrorToken  = null;
            mMirrorActive = false;
        }
    }
}
