package com.byd.myapp.dashboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import com.byd.myapp.AppLogger;

import java.lang.reflect.Method;

/**
 * Capture périodique du display cluster (display 1) via SurfaceControl.screenshot().
 *
 * Requiert android.permission.READ_FRAME_BUFFER (signature-level, accordée avec platform.keystore).
 *
 * Taux de rafraîchissement : ~2,5 fps (400 ms) — suffisant pour navigation/GPS.
 * Résolution capturée : 1/4 des dimensions réelles du cluster (ex. 480×270 pour 1920×1080)
 * afin de réduire la charge mémoire et le temps de transfert.
 */
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";
    private static final long INTERVAL_MS = 400;

    public interface FrameCallback {
        /** Appelé sur le main thread avec le nouveau Bitmap cluster. */
        void onFrame(Bitmap bitmap, int clusterW, int clusterH);
        /** Appelé sur le main thread en cas d'erreur de capture. */
        void onError(String reason);
    }

    private final Context       mContext;
    private final Handler       mMainHandler = new Handler(Looper.getMainLooper());
    // Thread de capture persistant : évite de créer un nouveau Thread toutes les 400 ms.
    private HandlerThread       mCaptureThread;
    private Handler             mCaptureHandler;
    private volatile boolean    mRunning     = false;
    private int                 mDisplayId   = -1;
    private int                 mClusterW    = 1920;
    private int                 mClusterH    = 1080;
    // Compteur de session : incrémenté à chaque start() pour invalider les callbacks
    // de l'ancienne session de capture (thread starté avant stop() qui poste après start()).
    private int                 mSession     = 0;
    // Dernier bitmap rendu — recyclé avant d'afficher le suivant pour éviter la fuite mémoire.
    private Bitmap              mLastBitmap  = null;

    public ClusterMirrorManager(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Lance la boucle de capture pour le display donné. */
    public void start(int displayId, FrameCallback callback) {
        if (mRunning) stop();
        mDisplayId = displayId;
        mRunning   = true;
        mSession++;                // invalide les callbacks de l'ancienne session
        final int thisSession = mSession;
        // Récupérer les dimensions réelles du display
        DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            Display d = dm.getDisplay(displayId);
            if (d != null) {
                Point sz = new Point(1920, 1080);
                d.getRealSize(sz);
                mClusterW = sz.x;
                mClusterH = sz.y;
            }
        }
        AppLogger.i(TAG, "Miroir démarré — display=" + displayId
                + " " + mClusterW + "×" + mClusterH);
        // Démarrer le thread de capture persistant
        mCaptureThread  = new HandlerThread("cluster-mirror-cap");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());
        scheduleCapture(callback, thisSession);
    }

    /** Arrête la boucle de capture et libère les ressources. */
    public void stop() {
        mRunning = false;
        // Arrêter le HandlerThread de capture avant de vider le main handler
        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread  = null;
            mCaptureHandler = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (mLastBitmap != null && !mLastBitmap.isRecycled()) {
            mLastBitmap.recycle();
            mLastBitmap = null;
        }
        AppLogger.i(TAG, "Miroir arrêté");
    }

    public boolean isRunning() { return mRunning; }

    // ── Boucle de capture ────────────────────────────────────────────────────

    private void scheduleCapture(final FrameCallback callback, final int session) {
        // Planifié sur mCaptureHandler (HandlerThread dédié) — aucun new Thread() à chaque frame.
        mCaptureHandler.postDelayed(() -> {
            if (!mRunning || mSession != session) return;
            final Bitmap bmp = captureDisplay(mDisplayId);
            mMainHandler.post(() -> {
                // Double-check : stop()/start() peut avoir été appelé pendant la capture
                if (!mRunning || mSession != session) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp != null) {
                    // Recycler le bitmap précédent AVANT d'afficher le nouveau
                    // pour éviter l'accumulation en mémoire (~500 KB/frame à 2.5 fps).
                    if (mLastBitmap != null && !mLastBitmap.isRecycled()) {
                        mLastBitmap.recycle();
                    }
                    mLastBitmap = bmp;
                    callback.onFrame(bmp, mClusterW, mClusterH);
                } else {
                    callback.onError("SurfaceControl.screenshot() null");
                }
                scheduleCapture(callback, session);
            });
        }, INTERVAL_MS);
    }

    // ── Capture d'un frame ───────────────────────────────────────────────────

    private Bitmap captureDisplay(int displayId) {
        try {
            DisplayManager dm =
                    (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return null;
            Display display = dm.getDisplay(displayId);
            if (display == null) return null;

            IBinder token = getDisplayToken(display);
            if (token == null) {
                AppLogger.w(TAG, "getDisplayToken() null pour display=" + displayId);
                return null;
            }

            // Capturer à ¼ de la résolution pour économiser la mémoire
            int capW = mClusterW / 4;
            int capH = mClusterH / 4;
            return screenshotBitmap(token, capW, capH);

        } catch (Exception e) {
            AppLogger.e(TAG, "captureDisplay " + displayId + " échec", e);
            return null;
        }
    }

    /**
     * Récupère le token IBinder du display via la méthode cachée Display.getDisplayToken().
     * Accessible sur API 29 avec platform.keystore.
     */
    private IBinder getDisplayToken(Display display) {
        try {
            Method m = Display.class.getDeclaredMethod("getDisplayToken");
            m.setAccessible(true);
            return (IBinder) m.invoke(display);
        } catch (Exception e) {
            AppLogger.e(TAG, "getDisplayToken échec", e);
            return null;
        }
    }

    /**
     * Capture un Bitmap via android.view.SurfaceControl (méthode cachée).
     * Requiert READ_FRAME_BUFFER — accordée avec platform.keystore (confirmé API 29).
     *
     * Plusieurs signatures possibles selon la version ROM — essayées dans l'ordre.
     */
    private Bitmap screenshotBitmap(IBinder token, int w, int h) {
        Class<?> sc;
        try {
            sc = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException e) {
            AppLogger.e(TAG, "SurfaceControl introuvable");
            return null;
        }

        // Signature 1 : screenshot(IBinder, int, int, boolean, int) → Bitmap  (API 29 courant)
        try {
            Method m = sc.getDeclaredMethod("screenshot",
                    IBinder.class, int.class, int.class, boolean.class, int.class);
            m.setAccessible(true);
            return (Bitmap) m.invoke(null, token, w, h, false, 0);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            AppLogger.w(TAG, "screenshot sig1 échec", e);
        }

        // Signature 2 : screenshot(IBinder, int, int) → Bitmap
        try {
            Method m = sc.getDeclaredMethod("screenshot",
                    IBinder.class, int.class, int.class);
            m.setAccessible(true);
            return (Bitmap) m.invoke(null, token, w, h);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            AppLogger.w(TAG, "screenshot sig2 échec", e);
        }

        // Signature 3 : screenshot(Rect, int, int, boolean, int) → Bitmap (display principal)
        // (fallback si token non supporté — montrera l'écran principal, moins idéal)
        try {
            Method m = sc.getDeclaredMethod("screenshot",
                    android.graphics.Rect.class, int.class, int.class, boolean.class, int.class);
            m.setAccessible(true);
            return (Bitmap) m.invoke(null,
                    new android.graphics.Rect(0, 0, mClusterW, mClusterH), w, h, false, 0);
        } catch (Exception e) {
            AppLogger.e(TAG, "screenshot sig3 échec", e);
        }

        return null;
    }
}
