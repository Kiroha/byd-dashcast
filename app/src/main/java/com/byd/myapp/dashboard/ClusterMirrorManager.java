package com.byd.myapp.dashboard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.TextureView;
import android.view.WindowManager;
import android.graphics.SurfaceTexture;
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

    // TYPE_APPLICATION_OVERLAY = 2038
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final int OVERLAY_FLAGS = 0x108;
    private static final int VDISPLAY_FLAGS = 320;

    // ── SurfaceControl mirror token (nouveau) ──────────────────────────────
    private IBinder mMirrorDisplayToken = null;
    private Surface mMirrorSurface      = null;

    // ── Cluster overlay (TextureView sur cluster) — conservé mais non utilisé ──
    private VirtualDisplay mClusterOverlayVD     = null;
    private Surface        mClusterOverlaySurface = null;
    private TextureView    mClusterOverlayView    = null;
    private WindowManager  mClusterOverlayWm      = null;
    private int            mClusterVirtualDisplayId = -1;

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
    public int     getClusterVirtualDisplayId(){ return mClusterVirtualDisplayId; }

    public interface ClusterOverlayCallback {
        void onOverlayDisplayReady(int displayId);
        void onOverlayFailed(String reason);
    }

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

    // ── CLUSTER OVERLAY (conservé pour compatibilité) ─────────────────────

    public void startClusterOverlay(final Context context, final Display clusterDisplay,
            final Handler mainHandler, final ClusterOverlayCallback callback) {
        if (clusterDisplay == null) {
            if (callback != null) callback.onOverlayFailed("clusterDisplay null");
            return;
        }
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    Point sz = new Point(1920, 720);
                    clusterDisplay.getRealSize(sz);
                    mClusterW = sz.x; mClusterH = sz.y;
                    final Context displayCtx = context.createDisplayContext(clusterDisplay);
                    final WindowManager wm =
                            (WindowManager) displayCtx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) {
                        if (callback != null) callback.onOverlayFailed("WindowManager null");
                        return;
                    }
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
                                AppLogger.e(TAG, "onSurfaceTextureAvailable ERREUR", e);
                                if (callback != null) mainHandler.post(new Runnable() {
                                    @Override public void run() { callback.onOverlayFailed(e.getMessage()); }
                                });
                            }
                        }
                        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
                        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
                    });
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            mClusterW, mClusterH, TYPE_APPLICATION_OVERLAY,
                            OVERLAY_FLAGS, PixelFormat.OPAQUE);
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
            AppLogger.e(TAG, "SurfaceControl mirror ECHEC (ACCESS_SURFACE_FLINGER ?)", e);
            destroyMirrorToken();
            // Fallback : VirtualDisplay (ne montre rien à moins qu'une app y soit lancée)
            return startMirrorVirtualDisplay(context, clusterDisplay, targetSurface, viewW, viewH);
        }
    }

    /** Fallback : VirtualDisplay (même approche qu'avant v2.36). */
    private boolean startMirrorVirtualDisplay(Context context, Display clusterDisplay,
                                              Surface targetSurface, int viewW, int viewH) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return false;
        AppLogger.i(TAG, "createVirtualDisplay preview " + mClusterW + "×" + mClusterH
                + " (fallback — ACCESS_SURFACE_FLINGER absent)");
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
     * L'overlay cluster reste actif dans ClusterService.
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
