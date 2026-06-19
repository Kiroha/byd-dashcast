package com.byd.dashcast.cluster.mirror;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.platform.Platform;
import com.byd.dashcast.proxy.daemon.MirrorDaemon;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cluster mirror — SurfaceControl.createDisplay + setDisplayLayerStack.
 *
 * Mechanism (identical to WindowManagement v1.2):
 *   - SurfaceControl.createDisplay("mybyd_preview_mirror", false)
 *   - Transaction.setDisplayLayerStack(token, clusterLayerStack) → mirrors cluster content
 *   - Transaction.setDisplaySurface(token, ourSurface) → to our TextureView
 *   - Transaction.setDisplayProjection(token, 0, srcRect, destRect)
 *   → SurfaceFlinger composites the cluster into our surface. No VirtualDisplay needed.
 *
 * Requires: ACCESS_SURFACE_FLINGER (signature permission, granted with platform.keystore)
 */
@SuppressWarnings("deprecation")
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    /** Guard to prevent repeated costly reflection calls if unlockHiddenApis is called more than once. */
    private static final AtomicBoolean sHiddenApisUnlocked = new AtomicBoolean(false);

    // ── Cached reflection Methods — resolved once, reused on every mirror start ──────────────
    private static volatile boolean  sMirrorMethodsCached     = false;
    private static Method sCachedGetLayerStack;
    private static Method sCachedCreateDisplay;
    private static Method sCachedSetDisplaySurface;
    private static Method sCachedSetDisplayLayerStack;
    private static Method sCachedSetDisplayProjection;
    private static Method sCachedDestroyDisplay;

    private static synchronized void ensureMirrorMethodsCached() throws Exception {
        if (sMirrorMethodsCached) return;
        sCachedGetLayerStack = Display.class.getDeclaredMethod("getLayerStack");
        sCachedGetLayerStack.setAccessible(true);
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        sCachedCreateDisplay = scClass.getDeclaredMethod("createDisplay", String.class, boolean.class);
        sCachedCreateDisplay.setAccessible(true);
        sCachedDestroyDisplay = scClass.getDeclaredMethod("destroyDisplay", IBinder.class);
        sCachedDestroyDisplay.setAccessible(true);
        Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
        sCachedSetDisplaySurface = txClass.getDeclaredMethod("setDisplaySurface",
                IBinder.class, Surface.class);
        sCachedSetDisplaySurface.setAccessible(true);
        sCachedSetDisplayLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                IBinder.class, int.class);
        sCachedSetDisplayLayerStack.setAccessible(true);
        sCachedSetDisplayProjection = txClass.getDeclaredMethod("setDisplayProjection",
                IBinder.class, int.class, Rect.class, Rect.class);
        sCachedSetDisplayProjection.setAccessible(true);
        sMirrorMethodsCached = true;
    }

    // ── SurfaceControl mirror token ───────────────────────────────────────────────
    private IBinder mMirrorDisplayToken = null;
    // Audit batch 2 — removed dead field mMirrorSurface (was assigned in
    // startMirror/startMirrorViaDaemon and nulled in stopPreview/destroyMirrorToken
    // but never read anywhere). The Surface lifecycle is owned by MainActivity
    // (TextureView's SurfaceTexture), we never needed our own reference.

    private boolean mMirrorActive = false;
    // v1.2.55-beta — tracks which path established the active mirror. When the
    // daemon Binder becomes available AFTER a direct-path mirror was put in
    // place, MainActivity uses this flag to detect the stale state and
    // restart via the daemon (which is the only path that actually streams
    // frames on DL3/DL5 because the app process lacks ACCESS_SURFACE_FLINGER).
    private boolean mMirrorViaDaemon = false;
    private int     mClusterW = 1920;
    private int     mClusterH = 720;   // Confirmed: fission_bg_xdjaVirtualSurface 1920×720 (dumpsys window 03/05/2026)

    // ── Projection parameters (set when setDisplayProjection is called) ───────
    // Stored with integer arithmetic to match the daemon's computation exactly.
    // Used by touch mapping so the offset/scale are always consistent with the
    // actual rendered projection, regardless of current view dimensions.
    private int   mProjOffsetX = 0;
    private int   mProjOffsetY = 0;
    private float mProjScale   = 0f;  // 0 means "not yet set"

    public int     getClusterWidth()           { return mClusterW; }
    public int     getClusterHeight()          { return mClusterH; }
    public boolean isMirrorActive()            { return mMirrorActive; }
    /** True iff the active mirror was established through the system-uid daemon path. */
    public boolean isMirrorViaDaemon()         { return mMirrorActive && mMirrorViaDaemon; }

    /** Returns the horizontal letterbox offset (pixels) used in the last setDisplayProjection call. */
    public int   getProjOffsetX() { return mProjOffsetX; }
    /** Returns the vertical letterbox offset (pixels) used in the last setDisplayProjection call. */
    public int   getProjOffsetY() { return mProjOffsetY; }
    /** Returns the scale factor used in the last setDisplayProjection call. 0 if not yet set. */
    public float getProjScale()   { return mProjScale; }

    /**
     * Unlocks hidden APIs (SurfaceControl, Display.getLayerStack, etc.).
     * Guarded by an AtomicBoolean so repeated calls (e.g. Activity re-create) are no-ops.
     */
    public static void unlockHiddenApis() {
        if (!sHiddenApisUnlocked.compareAndSet(false, true)) {
            AppLogger.d(TAG, "unlockHiddenApis: already unlocked, skipping");
            return;
        }
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
            Class<?> vmRuntimeClass = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                    "setHiddenApiExemptions", new Class<?>[]{String[].class});
            setExemptions.invoke(vmRuntime, new Object[]{
                    new String[]{"Landroid/", "Lcom/android/", "Ljava/lang/"}
            });
            AppLogger.i(TAG, "unlockHiddenApis OK — SurfaceControl accessible");
        } catch (Exception e) {
            AppLogger.w(TAG, "unlockHiddenApis ERROR: " + e.getMessage());
        }
    }

    // ── SURFACECONTROL MIRROR ─────────────────────────────────────────────────

    /**
     * Mirrors the cluster content into the provided Surface via SurfaceControl.
     *
     * Equivalent to what WindowManagement does via its daemon (uid=2000):
     *   SurfaceControl.createDisplay + setDisplayLayerStack(clusterLayerStack) + setDisplaySurface
     *
     * Requires ACCESS_SURFACE_FLINGER (signature permission).
     * Returns false on failure → caller falls back to screencap.
     *
     * @param targetSurface  Surface of our local TextureView (in-app)
     * @param viewW / viewH  View dimensions (for projection mapping)
     */
    @SuppressLint("NewApi")
    public boolean startMirror(Context ctx, Display clusterDisplay, Surface targetSurface,
                               int viewW, int viewH) {
        if (mMirrorActive) {
            AppLogger.d(TAG, "Mirror already active");
            return true;
        }
        stopPreview();

        if (targetSurface == null || !targetSurface.isValid()) {
            AppLogger.e(TAG, "startMirror: targetSurface is invalid");
            return false;
        }

        // Cluster dimensions
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x; mClusterH = sz.y;
        }

        // ── SurfaceControl mirror attempt ────────────────────────────────────
        try {
            ensureMirrorMethodsCached();

            // 1. Cluster layer stack (@hide API)
            int layerStack = 0;
            try {
                layerStack = (Integer) sCachedGetLayerStack.invoke(clusterDisplay);
                AppLogger.d(TAG, "Cluster layerStack=" + layerStack);
            } catch (Exception e) {
                // On some ROMs layerStack == displayId
                layerStack = (clusterDisplay != null) ? clusterDisplay.getDisplayId() : 2;
                AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=" + layerStack);
            }
            layerStack = applyDl5LayerStackOverride(ctx, layerStack);

            // 2. Create a display token for our mirror
            mMirrorDisplayToken = (IBinder) sCachedCreateDisplay.invoke(null,
                    "mybyd_preview_mirror", false);
            if (mMirrorDisplayToken == null) {
                throw new RuntimeException("SurfaceControl.createDisplay → null");
            }

            // 3. Projection: preserve aspect ratio (letterbox)
            // Use integer arithmetic to match MirrorDaemon.setupMirror() exactly.
            float scale   = Math.min((float) viewW / mClusterW, (float) viewH / mClusterH);
            int   drawW   = (int) (mClusterW * scale);
            int   drawH   = (int) (mClusterH * scale);
            int   offsetX = (viewW  - drawW) / 2;
            int   offsetY = (viewH  - drawH) / 2;
            Rect srcRect  = new Rect(0, 0, mClusterW, mClusterH);
            Rect destRect = new Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH);

            // Store projection params for touch coordinate mapping
            mProjOffsetX = offsetX;
            mProjOffsetY = offsetY;
            mProjScale   = scale;

            // 4. SurfaceControl Transaction (@hide methods — cached)
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();

            sCachedSetDisplayLayerStack.invoke(tx, mMirrorDisplayToken, layerStack);
            sCachedSetDisplaySurface.invoke(tx, mMirrorDisplayToken, targetSurface);
            sCachedSetDisplayProjection.invoke(tx, mMirrorDisplayToken, 0, srcRect, destRect);
            tx.apply();

            mMirrorActive  = true;
            mMirrorViaDaemon = false;
            AppLogger.i(TAG, "SurfaceControl mirror ✓ layerStack=" + layerStack
                    + " src=" + mClusterW + "×" + mClusterH
                    + " dest=" + drawW + "×" + drawH + " offset=(" + offsetX + "," + offsetY + ")");
            return true;

        } catch (Exception e) {
            AppLogger.e(TAG, "SurfaceControl mirror FAILED (ACCESS_SURFACE_FLINGER?) — use startMirrorViaDaemon()", e);
            destroyMirrorToken();
            return false;
        }
    }

    /**
     * Mirror via the MirrorDaemon (uid=2000) which holds ACCESS_SURFACE_FLINGER.
     * The daemon receives the Surface via Binder and configures SurfaceControl (static methods).
     * SYNCHRONOUS call: the daemon replies 1 (success) or 0 (failure) → mMirrorActive reflects
     * reality, which allows the screencap fallback if the daemon fails.
     */
    public boolean startMirrorViaDaemon(Context ctx, IBinder daemonBinder, Display clusterDisplay,
                                        Surface targetSurface, int viewW, int viewH) {
        if (mMirrorActive) return true;
        if (daemonBinder == null || targetSurface == null || !targetSurface.isValid()) return false;
        if (clusterDisplay == null) {
            AppLogger.w(TAG, "startMirrorViaDaemon: clusterDisplay null — mirror deferred until display is ready");
            return false;
        }

        // Cluster dimensions
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            mClusterW = sz.x;
            mClusterH = sz.y;
        }

        // Pre-compute projection params (identical formula to MirrorDaemon.setupMirror).
        // Stored here so touch mapping uses exact same offsets as the daemon's projection.
        {
            float scale = Math.min((float) viewW / mClusterW, (float) viewH / mClusterH);
            int drawW   = (int) (mClusterW * scale);
            int drawH   = (int) (mClusterH * scale);
            mProjOffsetX = (viewW - drawW) / 2;
            mProjOffsetY = (viewH - drawH) / 2;
            mProjScale   = scale;
        }

        int clusterDisplayId = (clusterDisplay != null) ? clusterDisplay.getDisplayId() : 2;
        int layerStack;
        try {
            ensureMirrorMethodsCached();
            layerStack = (Integer) sCachedGetLayerStack.invoke(clusterDisplay);
        } catch (Exception e) {
            layerStack = clusterDisplayId;
            AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=" + layerStack);
        }
        layerStack = applyDl5LayerStackOverride(ctx, layerStack);
        // v1.2.7 — On DL5 the daemon must inject touch on displayId=2 (composed fission output),
        // not on the shadow render displayId=3 whose framebufferSpace is 1×1 — see field test
        // 22/05/2026 (preview OK after layerStack 3→2 override but tactile not working until
        // we mirror that override on the daemon's setDisplayId target).
        clusterDisplayId = applyDl5DisplayIdOverride(ctx, clusterDisplayId);

        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            data.writeInt(layerStack);
            data.writeInt(mClusterW);
            data.writeInt(mClusterH);
            data.writeInt(clusterDisplayId);
            data.writeInt(viewW);
            data.writeInt(viewH);
            data.writeParcelable(targetSurface, 0);
            // Synchronous call (not FLAG_ONEWAY) → daemon reply in 'reply' parcel
            daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_START, data, reply, 0);
            reply.readException();
            boolean daemonOk = reply.readInt() == 1;
            if (daemonOk) {
                mMirrorActive  = true;
                mMirrorViaDaemon = true;
                AppLogger.i(TAG, "startMirrorViaDaemon ✓ layerStack=" + layerStack
                        + " " + mClusterW + "×" + mClusterH + " displayId=" + clusterDisplayId);
            } else {
                AppLogger.e(TAG, "startMirrorViaDaemon: daemon reported failure"
                        + " (check logcat for MirrorDaemon details)");
            }
            return daemonOk;
        } catch (android.os.DeadObjectException doe) {
            // v1.3.3 — silent binder death: kernel did not notify sDeath, so
            // the cached binder is stuck "alive". Eagerly invalidate so the
            // keeper re-bootstraps within HEARTBEAT_MS instead of leaving every
            // call broken until the user quits the app.
            com.byd.dashcast.proxy.ProxyClient.invalidateBinder("MirrorStart");
            AppLogger.e(TAG, "startMirrorViaDaemon DeadObjectException — binder invalidated", doe);
            return false;
        } catch (Exception e) {
            AppLogger.e(TAG, "startMirrorViaDaemon failed", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Requests the daemon to stop the SurfaceControl mirror.
     */
    public void stopMirrorViaDaemon(IBinder daemonBinder) {
        if (daemonBinder == null) return;
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR);
            // v0.9.77: SYNCHRONOUS stop (no FLAG_ONEWAY) so callers can immediately
            // restart the mirror at a different size (e.g. fullscreen toggle) without
            // a race where the daemon's stop is still queued behind the new start.
            daemonBinder.transact(MirrorDaemon.TRANSACT_MIRROR_STOP, data, reply, 0);
            try { reply.readException(); } catch (Throwable ignored) { /* daemon may not write reply */ }
        } catch (android.os.DeadObjectException doe) {
            // v1.3.3 — see comment in startMirrorViaDaemon.
            com.byd.dashcast.proxy.ProxyClient.invalidateBinder("MirrorStop");
            AppLogger.w(TAG, "stopMirrorViaDaemon DeadObjectException — binder invalidated");
        } catch (Exception e) {
            AppLogger.w(TAG, "stopMirrorViaDaemon transact failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
        mMirrorActive  = false;
        mMirrorViaDaemon = false;
        AppLogger.i(TAG, "stopMirrorViaDaemon done (sync)");
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * DL5 cluster architecture quirk: apps render on layerStack 3/4
     * (shared_fission_bg_XDJAScreenProjection_0/_1) which expose a shadow
     * framebuffer of 1×1 px — copying that layerStack into our preview yields
     * a black image. The actually-composited 1920×720 content displayed on
     * the physical cluster lives on layerStack=2 (fission_bg_XDJAScreenProjection).
     * Override 3/4 → 2 only on effective DL5. DL3 path untouched.
     */
    private static int applyDl5LayerStackOverride(Context ctx, int detectedLayerStack) {
        if (ctx == null) return detectedLayerStack;
        boolean dl5;
        try {
            dl5 = Platform.get().isDiLink5(ctx);
        } catch (Throwable t) {
            return detectedLayerStack;
        }
        if (!dl5) return detectedLayerStack;
        if (detectedLayerStack == 3 || detectedLayerStack == 4) {
            AppLogger.i(TAG, "DL5 override: layerStack " + detectedLayerStack
                    + " → 2 (mirror composed fission output)");
            return 2;
        }
        return detectedLayerStack;
    }

    /**
     * v1.2.7 — Symmetric to {@link #applyDl5LayerStackOverride}: on DL5, when the detected
     * displayId (used by the daemon's MotionEvent.setDisplayId target for touch injection) is
     * the shadow render display 3 or 4 (framebufferSpace 1×1), rewrite it to 2 — the WMS
     * displayId backing the composed 1920×720 cluster output where the user actually sees
     * the apps. On DL3 and on any other displayId value, returns the input unchanged.
     */
    private static int applyDl5DisplayIdOverride(Context ctx, int detectedDisplayId) {
        if (ctx == null) return detectedDisplayId;
        boolean dl5;
        try {
            dl5 = Platform.get().isDiLink5(ctx);
        } catch (Throwable t) {
            return detectedDisplayId;
        }
        if (!dl5) return detectedDisplayId;
        if (detectedDisplayId == 3 || detectedDisplayId == 4) {
            AppLogger.i(TAG, "DL5 override: displayId " + detectedDisplayId
                    + " → 2 (touch injection on composed cluster face)");
            return 2;
        }
        return detectedDisplayId;
    }

    private void destroyMirrorToken() {
        if (mMirrorDisplayToken != null) {
            try {
                ensureMirrorMethodsCached();
                sCachedDestroyDisplay.invoke(null, mMirrorDisplayToken);
            } catch (Exception e) {
                AppLogger.w(TAG, "destroyDisplay via reflection failed: " + e.getMessage());
            }
            mMirrorDisplayToken = null;
        }
    }

    private void stopPreview() {
        mMirrorActive = false;
        mMirrorViaDaemon = false;
        mProjScale = 0f;  // Reset: signals "not yet set" to touch mapping
        destroyMirrorToken();
    }

    /**
     * Stops the local preview (called from MainActivity.onStop).
     */
    public void stopMirror() {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager preview stopped");
    }

    /**
     * Releases the preview.
     * Must only be called from ClusterService.onDestroy().
     */
    public void release() {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager released");
    }
}
