package com.byd.myapp.dashboard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.graphics.SurfaceTexture;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * Miroir cluster v2.32 — Architecture "byd_dashboard style" sans ACCESS_SURFACE_FLINGER.
 *
 * Deux VirtualDisplays :
 *
 * A) CLUSTER OVERLAY (startClusterOverlay) :
 *    createDisplayContext(clusterDisplay) + WindowManager.addView(TextureView, TYPE=2038)
 *    → TextureView physiquement sur le cluster (Display 2)
 *    → createVirtualDisplay(textureView.surface) → mClusterVirtualDisplayId
 *    → apps lancées sur mClusterVirtualDisplayId rendent dans la TextureView → cluster
 *    Requiert : INTERNAL_SYSTEM_WINDOW (nous l'avons ✓)
 *
 * B) LOCAL PREVIEW (startMirror) :
 *    createVirtualDisplay(localSurfaceView.surface) → mPreviewDisplayId
 *    → apps lancées sur mPreviewDisplayId rendent dans notre SurfaceView (touchable)
 *    Aucune permission spéciale requise.
 *
 * Inspiré de SecondaryDisplayService.java (byd_dashboard) :
 *   WindowManager.LayoutParams(-1, -1, 2038, 768, -1)
 *   WindowManagerGlobal.addView(textureView, params, clusterDisplay)
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    // TYPE_APPLICATION_OVERLAY = 2038, même valeur que byd_dashboard
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    // Flags: FLAG_NOT_FOCUSABLE(8) | FLAG_LAYOUT_IN_SCREEN(256)
    private static final int OVERLAY_FLAGS = 0x108;
    // VirtualDisplay flags = DESTROY_CONTENT_ON_REMOVAL(256) | SUPPORTS_TOUCH(64)
    // NOTE: PUBLIC(1) non inclus → createVirtualDisplay sans PUBLIC ne requiert pas CAPTURE_VIDEO_OUTPUT
    // Le lancement depuis uid=10100 doit passer par IActivityManager (display owner check)
    private static final int VDISPLAY_FLAGS = 320;

    // ── A. Cluster overlay (TextureView sur le cluster physique) ─────────────
    private VirtualDisplay mClusterOverlayVD     = null;
    private Surface        mClusterOverlaySurface = null;
    private TextureView    mClusterOverlayView    = null;
    private WindowManager  mClusterOverlayWm      = null;
    private int            mClusterVirtualDisplayId = -1;

    // ── B. Local preview (SurfaceView dans notre app) ────────────────────────
    private VirtualDisplay mPreviewVD    = null;
    private int            mPreviewDisplayId = -1;

    private boolean mMirrorActive = false;
    private int     mClusterW = 1920;
    private int     mClusterH = 720;

    public int     getClusterWidth()           { return mClusterW; }
    public int     getClusterHeight()          { return mClusterH; }
    public boolean isMirrorActive()            { return mMirrorActive; }
    public int     getPreviewDisplayId()       { return mPreviewDisplayId; }
    public int     getClusterVirtualDisplayId(){ return mClusterVirtualDisplayId; }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Callback pour le résultat de l'overlay cluster.
     */
    public interface ClusterOverlayCallback {
        void onOverlayDisplayReady(int displayId);
        void onOverlayFailed(String reason);
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Déverrouille les APIs cachées.
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

    // ── A. CLUSTER OVERLAY ────────────────────────────────────────────────────

    /**
     * Place un TextureView invisible sur le cluster (Display 2) via createDisplayContext(),
     * puis crée un VirtualDisplay connecté à cette surface.
     *
     * Les apps lancées sur getClusterVirtualDisplayId() rendent dans ce TextureView
     * → s'affichent sur le cluster physique.
     *
     * Doit être appelé sur le main thread ou via mainHandler.
     */
    public void startClusterOverlay(final Context context, final Display clusterDisplay,
            final Handler mainHandler, final ClusterOverlayCallback callback) {

        if (clusterDisplay == null) {
            AppLogger.e(TAG, "startClusterOverlay : clusterDisplay null");
            if (callback != null) callback.onOverlayFailed("clusterDisplay null");
            return;
        }

        // Doit s'exécuter sur le main thread (création de View)
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    // Dimensions réelles du cluster
                    Point sz = new Point(1920, 720);
                    clusterDisplay.getRealSize(sz);
                    mClusterW = sz.x;
                    mClusterH = sz.y;

                    // Contexte ciblant le cluster → WindowManager pour ce display
                    final Context displayCtx = context.createDisplayContext(clusterDisplay);
                    final WindowManager wm =
                            (WindowManager) displayCtx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) {
                        AppLogger.e(TAG, "WindowManager null pour cluster display");
                        if (callback != null) callback.onOverlayFailed("WindowManager null");
                        return;
                    }

                    // TextureView qui sera le "canvas" du cluster
                    TextureView tv = new TextureView(displayCtx);
                    tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                        @Override
                        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                            try {
                            AppLogger.i(TAG, "Cluster overlay surface disponible " + w + "×" + h);
                            Surface surface = new Surface(st);
                            mClusterOverlaySurface = surface;

                            DisplayManager dm = (DisplayManager)
                                    context.getSystemService(Context.DISPLAY_SERVICE);
                            if (dm == null) {
                                if (callback != null) mainHandler.post(new Runnable() {
                                    @Override public void run() { callback.onOverlayFailed("DisplayManager null"); }
                                });
                                return;
                            }

                            // VirtualDisplay connecté à la surface de la TextureView
                            // → les apps qui rendent sur ce display s'affichent sur le cluster
                            mClusterOverlayVD = dm.createVirtualDisplay(
                                    "mybyd_cluster_overlay",
                                    mClusterW, mClusterH, 320, surface, VDISPLAY_FLAGS);

                            if (mClusterOverlayVD != null) {
                                mClusterVirtualDisplayId =
                                        mClusterOverlayVD.getDisplay().getDisplayId();
                                AppLogger.i(TAG, "Cluster overlay VirtualDisplay ✓ → id="
                                        + mClusterVirtualDisplayId
                                        + " dims=" + mClusterW + "×" + mClusterH);
                                if (callback != null) {
                                    final int id = mClusterVirtualDisplayId;
                                    mainHandler.post(new Runnable() {
                                        @Override public void run() { callback.onOverlayDisplayReady(id); }
                                    });
                                }
                            } else {
                                AppLogger.e(TAG, "createVirtualDisplay overlay → null");
                                if (callback != null) mainHandler.post(new Runnable() {
                                    @Override public void run() { callback.onOverlayFailed("VirtualDisplay null"); }
                                });
                            }
                            } catch (Exception e) {
                                // SecurityException si PUBLIC flag sans CAPTURE_VIDEO_OUTPUT, etc.
                                AppLogger.e(TAG, "onSurfaceTextureAvailable ERREUR", e);
                                if (callback != null) mainHandler.post(new Runnable() {
                                    @Override public void run() { callback.onOverlayFailed(e.getMessage()); }
                                });
                            }
                        }

                        @Override
                        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                            AppLogger.i(TAG, "Cluster overlay surface détruite");
                            return true;
                        }

                        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
                    });

                    // Paramètres identiques à byd_dashboard SecondaryDisplayService :
                    // TYPE_APPLICATION_OVERLAY (2038), flags comme byd_dashboard (768=0x300)
                    // Nous utilisons INTERNAL_SYSTEM_WINDOW donc 2038 est autorisé
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            mClusterW, mClusterH,
                            TYPE_APPLICATION_OVERLAY,
                            OVERLAY_FLAGS,
                            PixelFormat.OPAQUE
                    );
                    params.gravity = Gravity.LEFT | Gravity.TOP;

                    mClusterOverlayWm   = wm;
                    mClusterOverlayView = tv;
                    wm.addView(tv, params);

                    AppLogger.i(TAG, "TextureView overlay ajouté sur cluster display="
                            + clusterDisplay.getDisplayId()
                            + " (" + mClusterW + "×" + mClusterH + ")");

                } catch (Exception e) {
                    AppLogger.e(TAG, "startClusterOverlay ERREUR", e);
                    if (callback != null) callback.onOverlayFailed(e.getMessage());
                }
            }
        });
    }

    // ── B. LOCAL PREVIEW ─────────────────────────────────────────────────────

    /**
     * Crée un VirtualDisplay local connecté à la Surface de notre SurfaceView.
     * Les apps lancées sur getPreviewDisplayId() s'affichent dans notre app (touchable).
     */
    public boolean startMirror(Context context, Display clusterDisplay, Surface targetSurface,
                               int viewW, int viewH) {
        if (mMirrorActive && mPreviewDisplayId > 0 && mPreviewVD != null) {
            AppLogger.d(TAG, "Preview VirtualDisplay déjà actif : id=" + mPreviewDisplayId);
            return true;
        }
        stopPreview();

        if (targetSurface == null || !targetSurface.isValid()) {
            AppLogger.e(TAG, "startMirror : targetSurface invalide");
            return false;
        }

        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return false;

        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;
        }

        AppLogger.i(TAG, "createVirtualDisplay preview " + mClusterW + "×" + mClusterH);
        mPreviewVD = dm.createVirtualDisplay(
                "mybyd_cluster_preview", mClusterW, mClusterH, 320, targetSurface, VDISPLAY_FLAGS);

        if (mPreviewVD == null) {
            AppLogger.e(TAG, "createVirtualDisplay preview → null");
            return false;
        }

        mPreviewDisplayId = mPreviewVD.getDisplay().getDisplayId();
        mMirrorActive = true;
        AppLogger.i(TAG, "Preview VirtualDisplay ✓ → id=" + mPreviewDisplayId);
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void stopPreview() {
        mMirrorActive = false;
        mPreviewDisplayId = -1;
        if (mPreviewVD != null) {
            try { mPreviewVD.release(); } catch (Exception ignored) {}
            mPreviewVD = null;
        }
    }

    private void stopClusterOverlay() {
        mClusterVirtualDisplayId = -1;
        if (mClusterOverlayVD != null) {
            try { mClusterOverlayVD.release(); } catch (Exception ignored) {}
            mClusterOverlayVD = null;
        }
        if (mClusterOverlaySurface != null) {
            try { mClusterOverlaySurface.release(); } catch (Exception ignored) {}
            mClusterOverlaySurface = null;
        }
        if (mClusterOverlayWm != null && mClusterOverlayView != null) {
            try { mClusterOverlayWm.removeView(mClusterOverlayView); } catch (Exception ignored) {}
            mClusterOverlayWm   = null;
            mClusterOverlayView = null;
        }
    }

    /**
     * Arrête uniquement le preview local (appelé depuis MainActivity.onStop).
     * L'overlay cluster sur le display physique reste actif dans ClusterService.
     */
    public void stopMirror(Context context) {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager preview arrêté (overlay cluster toujours actif)");
    }

    /**
     * Libère TOUT : preview + overlay cluster.
     * À appeler uniquement depuis ClusterService.onDestroy().
     */
    public void release(Context context) {
        stopPreview();
        stopClusterOverlay();
        AppLogger.i(TAG, "ClusterMirrorManager libéré (preview + overlay)");
    }
}
