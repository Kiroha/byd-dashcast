package com.byd.myapp.daemon;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import java.lang.reflect.Method;

public class DaemonMirrorLogic {
    private static final String TAG = "MirrorDaemon";

    public static IBinder mMirrorToken = null;
    public static Class<?> sSurfaceControlClass = null;

    public static void stop() {
        if (mMirrorToken != null && sSurfaceControlClass != null) {
            try {
                sSurfaceControlClass.getMethod("destroyDisplay", IBinder.class).invoke(null, mMirrorToken);
                Log.i(TAG, "Miroir SurfaceControl détruit par le Daemon.");
            } catch (Exception e) {
                Log.e(TAG, "Erreur destroyDisplay", e);
            }
            mMirrorToken = null;
        }
    }

    public static void start(Surface targetSurface, int viewW, int viewH, int clusterW, int clusterH, int layerStack) {
        stop();
        try {
            sSurfaceControlClass = Class.forName("android.view.SurfaceControl");
            Method createDisplay = sSurfaceControlClass.getMethod("createDisplay", String.class, boolean.class);
            mMirrorToken = (IBinder) createDisplay.invoke(null, "byd_daemon_mirror", false);
            if (mMirrorToken == null) {
                mMirrorToken = (IBinder) createDisplay.invoke(null, "byd_daemon_mirror", true);
            }
            if (mMirrorToken == null) {
                Log.e(TAG, "Echec createDisplay par Daemon (null).");
                return;
            }

            float scale = Math.min((float) viewW / clusterW, (float) viewH / clusterH);
            int drawW = Math.round(clusterW * scale);
            int drawH = Math.round(clusterH * scale);
            int offsetX = (viewW - drawW) / 2;
            int offsetY = (viewH - drawH) / 2;

            Rect srcRect = new Rect(0, 0, clusterW, clusterH);
            Rect dstRect = new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH);

            boolean ok = applyViaTransaction(mMirrorToken, targetSurface, layerStack, srcRect, dstRect);
            if (!ok) {
                ok = applyViaStaticMethods(mMirrorToken, targetSurface, layerStack, srcRect, dstRect);
            }

            if (ok) {
                Log.i(TAG, "Miroir Daemon (SurfaceControl) démarré avec succès.");
            } else {
                stop();
                Log.e(TAG, "Echec application de la projection.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur startMirrorNatively", e);
            stop();
        }
    }

    private static boolean applyViaTransaction(IBinder token, Surface surface, int layerStack, Rect src, Rect dst) {
        try {
            Class<?> txClass = null;
            for (Class<?> inner : sSurfaceControlClass.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Transaction")) {
                    txClass = inner;
                    break;
                }
            }
            if (txClass == null) return false;

            Object tx = txClass.getConstructor().newInstance();
            Method setDisplaySurface = txClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            Method setDisplayLayerStack = txClass.getMethod("setDisplayLayerStack", IBinder.class, int.class);
            Method setDisplayProjection = txClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
            Method apply = txClass.getMethod("apply");

            setDisplaySurface.invoke(tx, token, surface);
            setDisplayLayerStack.invoke(tx, token, layerStack);
            setDisplayProjection.invoke(tx, token, Surface.ROTATION_0, src, dst);
            apply.invoke(tx);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "applyViaTransaction echoue : " + e.getMessage());
            return false;
        }
    }

    private static boolean applyViaStaticMethods(IBinder token, Surface surface, int layerStack, Rect src, Rect dst) {
        try {
            Method openTransaction = sSurfaceControlClass.getMethod("openTransaction");
            Method closeTransaction = sSurfaceControlClass.getMethod("closeTransaction");
            Method setDisplaySurface = sSurfaceControlClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
            Method setDisplayLayerStack = sSurfaceControlClass.getMethod("setDisplayLayerStack", IBinder.class, int.class);
            Method setDisplayProjection = sSurfaceControlClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);

            openTransaction.invoke(null);
            try {
                setDisplaySurface.invoke(null, token, surface);
                setDisplayLayerStack.invoke(null, token, layerStack);
                setDisplayProjection.invoke(null, token, Surface.ROTATION_0, src, dst);
            } finally {
                closeTransaction.invoke(null);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "applyViaStaticMethods echoue : " + e.getMessage());
            return false;
        }
    }
}
