package com.byd.myapp.dashboard;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Display;
import android.view.Surface;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * Miroir temps réel du cluster via DisplayManager.createVirtualDisplay().
 *
 * Architecture v2.30 — inspirée de WindowManagement v1.2 / byd_dashboard :
 *   WindowManagement crée un TextureView, envoie sa Surface via Binder à byd_dashboard
 *   (app whitelistée BYD), qui appelle DisplayManager.createVirtualDisplay("remote_dashboard",
 *   1920, 720, 320, surface, flags=320). Les apps lancées sur ce displayId s'affichent
 *   directement dans le TextureView — aucun SurfaceControl, aucun SurfaceFlinger.
 *
 * Notre app reproduit exactement ce mécanisme SANS passer par byd_dashboard :
 *   - DisplayManager.createVirtualDisplay() avec flags=320 ne nécessite pas ACCESS_SURFACE_FLINGER
 *   - Flags 320 = VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL(256) | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH(64)
 *   - Les apps lancées sur le previewDisplayId s'affichent dans notre SurfaceView/TextureView
 *
 * Explication des anciens échecs :
 *   - SurfaceControl.createDisplay() → null : nécessite ACCESS_SURFACE_FLINGER (signature système) BLOQUÉ
 *   - ADB screencap 800ms : fonctionne mais janky
 *   - DisplayManager.createVirtualDisplay() → FONCTIONNE sans permission spéciale
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    // Flags identiques à byd_dashboard "remote_dashboard" VirtualDisplay
    // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL(256) | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH(64)
    private static final int VDISPLAY_FLAGS = 320;

    private VirtualDisplay mVirtualDisplay = null;
    private int mPreviewDisplayId = -1;
    private boolean mMirrorActive = false;
    private int mClusterW = 1920;
    private int mClusterH = 720;

    public int     getClusterWidth()      { return mClusterW; }
    public int     getClusterHeight()     { return mClusterH; }
    public boolean isMirrorActive()       { return mMirrorActive; }
    public int     getPreviewDisplayId()  { return mPreviewDisplayId; }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Déverrouille les APIs cachées Android via VMRuntime.setHiddenApiExemptions().
     * Conservé pour les autres usages (IActivityManager reflection, etc.).
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

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Crée un VirtualDisplay connecté à la Surface cible (SurfaceView ou TextureView).
     *
     * Les apps lancées sur getPreviewDisplayId() s'afficheront directement dans la vue.
     * Flags=320 : même valeur que byd_dashboard "remote_dashboard" — pas de permission requise.
     *
     * @param clusterDisplay Display cluster (pour les dimensions, peut être null → 1920×720)
     * @param targetSurface  Surface du SurfaceView ou TextureView où le rendu apparaîtra
     * @return true si le VirtualDisplay a été créé avec succès
     */
    public boolean startMirror(Context context, Display clusterDisplay, Surface targetSurface,
                               int viewW, int viewH) {
        // Si déjà actif avec un displayId valide, ne pas recréer
        if (mMirrorActive && mPreviewDisplayId > 0 && mVirtualDisplay != null) {
            AppLogger.d(TAG, "VirtualDisplay déjà actif : previewDisplayId=" + mPreviewDisplayId);
            return true;
        }

        stopMirror(context);

        if (targetSurface == null || !targetSurface.isValid()) {
            AppLogger.e(TAG, "startMirror : targetSurface invalide");
            return false;
        }

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            AppLogger.e(TAG, "startMirror : DisplayManager null");
            return false;
        }

        // Récupérer les dimensions réelles du cluster
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;
        } else {
            mClusterW = 1920;
            mClusterH = 720;
        }

        AppLogger.i(TAG, "DisplayManager.createVirtualDisplay(" +
                "\"mybyd_cluster_preview\", " + mClusterW + "×" + mClusterH +
                ", dpi=320, flags=" + VDISPLAY_FLAGS + ")");

        // Même appel que byd_dashboard "remote_dashboard" (dpi=320, flags=320)
        // Flags 320 = DESTROY_CONTENT_ON_REMOVAL(256) | SUPPORTS_TOUCH(64)
        // Aucune permission ACCESS_SURFACE_FLINGER requise pour ces flags sur Android 10
        mVirtualDisplay = dm.createVirtualDisplay(
                "mybyd_cluster_preview",
                mClusterW, mClusterH,
                320,           // dpi — même valeur que byd_dashboard
                targetSurface,
                VDISPLAY_FLAGS
        );

        if (mVirtualDisplay == null) {
            AppLogger.e(TAG, "createVirtualDisplay → null ! Vérifier les permissions.");
            return false;
        }

        mPreviewDisplayId = mVirtualDisplay.getDisplay().getDisplayId();
        mMirrorActive = true;
        AppLogger.i(TAG, "VirtualDisplay créé ✓ → previewDisplayId=" + mPreviewDisplayId
                + " name=mybyd_cluster_preview dims=" + mClusterW + "×" + mClusterH);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────

    public void stopMirror(Context context) {
        mMirrorActive = false;
        mPreviewDisplayId = -1;
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
                AppLogger.i(TAG, "VirtualDisplay relâché");
            } catch (Exception e) {
                AppLogger.w(TAG, "release VirtualDisplay : " + e.getMessage());
            }
            mVirtualDisplay = null;
        }
    }
}

