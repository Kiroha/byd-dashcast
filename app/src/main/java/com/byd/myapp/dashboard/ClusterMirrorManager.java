package com.byd.myapp.dashboard;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Miroir temps réel du display cluster (display 1) vers une SurfaceView sur l'écran principal.
 *
 * Deux stratégies testées en cascade :
 *   A) SurfaceControl.Transaction (API Android 10 recommandée)
 *   B) SurfaceControl.openTransaction() / closeTransaction() (méthodes statiques, dépréciées API 29)
 *
 * Requiert android.permission.ACCESS_SURFACE_FLINGER + READ_FRAME_BUFFER (signature BYD).
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    private IBinder mMirrorToken  = null;
    private boolean mMirrorActive = false;
    private int     mClusterW     = 1920;
    private int     mClusterH     = 720;

    public int     getClusterWidth()  { return mClusterW; }
    public int     getClusterHeight() { return mClusterH; }
    public boolean isMirrorActive()   { return mMirrorActive; }

    // ──────────────────────────────────────────────────────────────────────────

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
        if (viewW <= 0 || viewH <= 0) {
            AppLogger.e(TAG, "startMirror : dimensions vue invalides " + viewW + "×" + viewH);
            return false;
        }

        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            AppLogger.d(TAG, "SurfaceControl class OK");

            // ── 1. layerStack ────────────────────────────────────────────────
            int layerStack = resolveLayerStack(clusterDisplay);
            AppLogger.i(TAG, "layerStack=" + layerStack
                    + "  displayId=" + clusterDisplay.getDisplayId()
                    + "  displayName=" + clusterDisplay.getName());

            // ── 2. Dimensions réelles ────────────────────────────────────────
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;
            AppLogger.i(TAG, "cluster dims=" + mClusterW + "×" + mClusterH
                    + "  view=" + viewW + "×" + viewH);

            // ── 3. createDisplay ─────────────────────────────────────────────
            Method createDisplay = scClass.getMethod("createDisplay",
                    String.class, boolean.class);
            IBinder mirrorToken = (IBinder) createDisplay.invoke(null,
                    "byd_cluster_mirror", false);
            if (mirrorToken == null) {
                AppLogger.e(TAG, "createDisplay → null (permission ACCESS_SURFACE_FLINGER ?)");
                return false;
            }
            AppLogger.i(TAG, "mirrorToken=" + mirrorToken);

            // ── 4. Rects ─────────────────────────────────────────────────────
            float scale   = Math.min((float) viewW / mClusterW, (float) viewH / mClusterH);
            int   drawW   = Math.round(mClusterW * scale);
            int   drawH   = Math.round(mClusterH * scale);
            int   offsetX = (viewW - drawW) / 2;
            int   offsetY = (viewH - drawH) / 2;
            Rect  srcRect = new Rect(0, 0, mClusterW, mClusterH);
            Rect  dstRect = new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH);
            AppLogger.d(TAG, "srcRect=" + srcRect + " dstRect=" + dstRect
                    + " scale=" + scale);

            // ── 5a. Stratégie A : SurfaceControl.Transaction (Android 10) ────
            boolean ok = applyViaTransaction(scClass, mirrorToken,
                    targetSurface, layerStack, srcRect, dstRect);

            // ── 5b. Stratégie B : méthodes statiques (fallback) ───────────────
            if (!ok) {
                AppLogger.w(TAG, "Transaction failed → essai méthodes statiques");
                ok = applyViaStaticMethods(scClass, mirrorToken,
                        targetSurface, layerStack, srcRect, dstRect);
            }

            if (ok) {
                mMirrorToken  = mirrorToken;
                mMirrorActive = true;
                AppLogger.i(TAG, "Miroir démarré ✓");
            } else {
                // Nettoyer le token inutilisé
                try {
                    scClass.getMethod("destroyDisplay", IBinder.class)
                            .invoke(null, mirrorToken);
                } catch (Exception ignored) {}
                AppLogger.e(TAG, "Miroir ÉCHEC — les deux stratégies ont échoué");
            }
            return ok;

        } catch (Exception e) {
            AppLogger.e(TAG, "startMirror ERREUR", e);
            return false;
        }
    }

    // ── Stratégie A : SurfaceControl.Transaction ─────────────────────────────

    private boolean applyViaTransaction(Class<?> scClass, IBinder token,
            Surface surface, int layerStack, Rect src, Rect dst) {
        try {
            // Chercher la classe Transaction (inner class de SurfaceControl en API 29)
            Class<?> txClass = null;
            for (Class<?> inner : scClass.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Transaction")) {
                    txClass = inner;
                    break;
                }
            }
            if (txClass == null) {
                AppLogger.w(TAG, "Transaction inner class introuvable");
                return false;
            }
            AppLogger.d(TAG, "Transaction class found: " + txClass.getName());

            Constructor<?> ctor = txClass.getConstructor();
            ctor.setAccessible(true);
            Object tx = ctor.newInstance();

            Method setDisplaySurface = txClass.getMethod("setDisplaySurface",
                    IBinder.class, Surface.class);
            Method setDisplayLayerStack = txClass.getMethod("setDisplayLayerStack",
                    IBinder.class, int.class);
            Method setDisplayProjection = txClass.getMethod("setDisplayProjection",
                    IBinder.class, int.class, Rect.class, Rect.class);
            Method apply = txClass.getMethod("apply");

            setDisplaySurface.invoke(tx, token, surface);
            setDisplayLayerStack.invoke(tx, token, layerStack);
            setDisplayProjection.invoke(tx, token, Surface.ROTATION_0, src, dst);
            apply.invoke(tx);

            AppLogger.i(TAG, "Stratégie A (Transaction) → OK");
            return true;

        } catch (NoSuchMethodException e) {
            AppLogger.w(TAG, "Transaction méthode manquante: " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLogger.w(TAG, "Stratégie A échoué: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
            return false;
        }
    }

    // ── Stratégie B : méthodes statiques openTransaction/closeTransaction ────

    private boolean applyViaStaticMethods(Class<?> scClass, IBinder token,
            Surface surface, int layerStack, Rect src, Rect dst) {
        try {
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
                setDisplaySurface.invoke(null, token, surface);
                setDisplayLayerStack.invoke(null, token, layerStack);
                setDisplayProjection.invoke(null, token,
                        Surface.ROTATION_0, src, dst);
            } finally {
                closeTransaction.invoke(null);
            }

            AppLogger.i(TAG, "Stratégie B (static) → OK");
            return true;

        } catch (Exception e) {
            AppLogger.e(TAG, "Stratégie B échoué: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage());
            return false;
        }
    }

    // ── Résolution layerStack avec fallbacks ──────────────────────────────────

    private int resolveLayerStack(Display display) {
        // Tentative 1 : Display.getLayerStack() via reflection
        try {
            Method m = Display.class.getDeclaredMethod("getLayerStack");
            m.setAccessible(true);
            int ls = (int) m.invoke(display);
            AppLogger.d(TAG, "layerStack via reflection=" + ls);
            if (ls > 0) return ls;
            AppLogger.w(TAG, "getLayerStack()=" + ls + " (suspect, essai fallback)");
        } catch (Exception e) {
            AppLogger.w(TAG, "getLayerStack() reflection échoué: " + e.getMessage());
        }

        // Tentative 2 : utiliser displayId comme proxy du layerStack
        // Sur la plupart des ROMs Android BYD, layerStack = displayId * 0x10000 ou displayId
        int displayId = display.getDisplayId();
        AppLogger.w(TAG, "Fallback layerStack = displayId=" + displayId);
        return displayId;
    }

    // ─────────────────────────────────────────────────────────────────────────

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
