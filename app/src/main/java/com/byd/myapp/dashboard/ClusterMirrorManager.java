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
public class ClusterMirrorManager {

    private static final String TAG = "ClusterMirrorManager";

    private static final int VDISPLAY_FLAGS = 320;

    // ── SurfaceControl mirror token ──────────────────────────────────────────
    private IBinder mMirrorDisplayToken = null;
    private Surface mMirrorSurface      = null;

    // ── Local preview ────────────────────────────────────────────────────────
    private VirtualDisplay mPreviewVD    = null;
    private int            mPreviewDisplayId = -1;

    private boolean mMirrorActive = false;
    private int     mClusterW = 1920;
    private int     mClusterH = 1080;  // Match physical cluster resolution (Seal EU)

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
    public int     getPreviewDisplayId()       { return mPreviewDisplayId; }

    /** Returns the horizontal letterbox offset (pixels) used in the last setDisplayProjection call. */
    public int   getProjOffsetX() { return mProjOffsetX; }
    /** Returns the vertical letterbox offset (pixels) used in the last setDisplayProjection call. */
    public int   getProjOffsetY() { return mProjOffsetY; }
    /** Returns the scale factor used in the last setDisplayProjection call. 0 if not yet set. */
    public float getProjScale()   { return mProjScale; }

    /**
     * Unlocks hidden APIs (SurfaceControl, Display.getLayerStack, etc.).
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
    public boolean startMirror(Context context, Display clusterDisplay, Surface targetSurface,
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
            // 1. Cluster layer stack (@hide API)
            int layerStack = 0;
            try {
                Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
                getLayerStack.setAccessible(true);
                layerStack = (Integer) getLayerStack.invoke(clusterDisplay);
                AppLogger.d(TAG, "Cluster layerStack=" + layerStack);
            } catch (Exception e) {
                // On some ROMs layerStack == displayId
                layerStack = (clusterDisplay != null) ? clusterDisplay.getDisplayId() : 2;
                AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=" + layerStack);
            }

            // 2. Create a display token for our mirror
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Method createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String.class, boolean.class);
            createDisplay.setAccessible(true);
            mMirrorDisplayToken = (IBinder) createDisplay.invoke(null,
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

            // 4. SurfaceControl Transaction (@hide methods)
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
            // mPreviewDisplayId stays -1 (no VirtualDisplay — content comes from the cluster)
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
    public boolean startMirrorViaDaemon(IBinder daemonBinder, Display clusterDisplay,
                                        Surface targetSurface, int viewW, int viewH) {
        if (mMirrorActive) return true;
        if (daemonBinder == null || targetSurface == null || !targetSurface.isValid()) return false;

        // Cluster dimensions — always normalized to landscape.
        // getRealSize() may return portrait values (720×1920) if a previous app called
        // setRequestedOrientation(PORTRAIT) and rotated the display. Using those portrait
        // values as srcRect would mirror portrait content into a landscape surface.
        // The BYD Seal EU cluster VirtualDisplay is physically always created at 1920×720
        // by AutoDisplayService; any portrait configuration is a logical WM rotation only.
        if (clusterDisplay != null) {
            Point sz = new Point(1920, 720);
            clusterDisplay.getRealSize(sz);
            // Normalize: if portrait (w < h), swap to get landscape dimensions
            if (sz.x < sz.y) {
                AppLogger.w(TAG, "startMirrorViaDaemon: display reported portrait "
                        + sz.x + "×" + sz.y + " — normalizing to landscape "
                        + sz.y + "×" + sz.x);
                mClusterW = sz.y;
                mClusterH = sz.x;
            } else {
                mClusterW = sz.x;
                mClusterH = sz.y;
            }
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
            Method getLayerStack = Display.class.getDeclaredMethod("getLayerStack");
            getLayerStack.setAccessible(true);
            layerStack = (Integer) getLayerStack.invoke(clusterDisplay);
        } catch (Exception e) {
            layerStack = clusterDisplayId;
            AppLogger.w(TAG, "getLayerStack failed → fallback layerStack=" + layerStack);
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
            // Synchronous call (not FLAG_ONEWAY) → daemon reply in 'reply' parcel
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
                AppLogger.e(TAG, "startMirrorViaDaemon: daemon reported failure"
                        + " (check logcat for MirrorDaemon details)");
            }
            return daemonOk;
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
        try {
            Parcel data = Parcel.obtain();
            data.writeInterfaceToken(com.byd.myapp.daemon.MirrorDaemon.DESCRIPTOR);
            daemonBinder.transact(com.byd.myapp.daemon.MirrorDaemon.TRANSACT_MIRROR_STOP,
                    data, null, android.os.IBinder.FLAG_ONEWAY);
            data.recycle();
        } catch (Exception ignored) {}
        mMirrorActive  = false;
        mMirrorSurface = null;
        AppLogger.i(TAG, "stopMirrorViaDaemon sent");
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
        mProjScale = 0f;  // Reset: signals "not yet set" to touch mapping
        if (mPreviewVD != null) {
            try { mPreviewVD.release(); } catch (Exception ignored) {}
            mPreviewVD = null;
        }
        destroyMirrorToken();
    }

    /**
     * Stops the local preview (called from MainActivity.onStop).
     */
    public void stopMirror(Context context) {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager preview stopped");
    }

    /**
     * Releases the preview.
     * Must only be called from ClusterService.onDestroy().
     */
    public void release(Context context) {
        stopPreview();
        AppLogger.i(TAG, "ClusterMirrorManager released");
    }
}
