package com.byd.dashcast.ui.main;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.R;
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager;
import com.byd.dashcast.fission.FissionOrchestrator;
import com.byd.dashcast.ime.ClusterImeWatcherService;
import com.byd.dashcast.platform.Platform;

/**
 * Owns the TextureView / SurfaceTexture / Surface lifecycle for the cluster preview mirror.
 *
 * All methods must be called on the main thread.
 */
public final class MirrorCoordinator {

    private static final String TAG = "MirrorCoordinator";

    // Reusable arrays for touch forwarding — avoids per-event allocation at 60-120 Hz.
    // Cap at 16 pointers (= Android InputDispatcher limit, matches ClusterInputForwarder.MAX_POINTERS).
    private static final int MAX_FWD_POINTERS = 16;
    private final int[]   mFwdPointerIds = new int[MAX_FWD_POINTERS];
    private final float[] mFwdClusterXs  = new float[MAX_FWD_POINTERS];
    private final float[] mFwdClusterYs  = new float[MAX_FWD_POINTERS];
        private final MotionEvent.PointerProperties[] mLayoutPointerProperties =
            new MotionEvent.PointerProperties[MAX_FWD_POINTERS];
        private final MotionEvent.PointerCoords[] mLayoutPointerCoords =
            new MotionEvent.PointerCoords[MAX_FWD_POINTERS];

    public interface Host {
        Context getContext();
        ClusterService getClusterServiceIfBound();
        IBinder getDaemonBinder();
        void onPreviewClicked();
    }

    private final TextureView  mTextureView;
    private final FrameLayout  mFrameMirror;
    private final TextView     mPlaceholder;
    private final Host         mHost;

    private Surface mMirrorSurface;
    private boolean mLayoutMirrorActive;
    private String mLayoutMirrorPackage;
    private MirrorProjection mLayoutProjection;

    public MirrorCoordinator(TextureView textureView, FrameLayout frameMirror,
                              TextView placeholder, Host host) {
        mTextureView = textureView;
        mFrameMirror = frameMirror;
        mPlaceholder = placeholder;
        mHost        = host;
        for (int i = 0; i < MAX_FWD_POINTERS; i++) {
            mLayoutPointerProperties[i] = new MotionEvent.PointerProperties();
            mLayoutPointerCoords[i] = new MotionEvent.PointerCoords();
        }
        setup();
    }

    private void setup() {
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                mMirrorSurface = new Surface(st);
                AppLogger.i(TAG, "SurfaceTexture available " + w + "x" + h);
                attemptStart();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                mMirrorSurface = new Surface(st);
                AppLogger.d(TAG, "SurfaceTexture size changed " + w + "x" + h);
                // Stop the active mirror (if any) so attemptStart() below is not
                // short-circuited by isMirrorActive(). Without this the mirror keeps
                // projecting at the stale size (e.g. 349px on DL5 cold start) because
                // the weighted-card layout emits a wrong first pass before settling.
                // Bypass stopMirror() to avoid flashing the placeholder during resize.
                stopMirrorForRestart();
                attemptStart();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopMirror();
                if (mMirrorSurface != null) {
                    mMirrorSurface.release();
                    mMirrorSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { /* no-op */ }
        });

        // If the SurfaceTexture is already available (Activity recreated), init immediately.
        if (mTextureView.isAvailable()) {
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            if (st != null) {
                int w = mTextureView.getWidth();
                int h = mTextureView.getHeight();
                if (w > 0 && h > 0) st.setDefaultBufferSize(w, h);
                mMirrorSurface = new Surface(st);
            }
        }

        mFrameMirror.setOnClickListener(v -> mHost.onPreviewClicked());

        // Touch → map coordinates → inject on cluster display
        mTextureView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // v0.9.79 — prevent NestedScrollView (or any ancestor) from stealing the
                // gesture once the finger moves past touchSlop, otherwise vertical drags
                // and pinch gestures get cancelled mid-flight.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                forwardTouchFromMirror(event);
                return true;
            }
        });
    }

    /** Called when a daemon Binder arrives after Activity start. */
    public void onDaemonBinderAvailable(IBinder daemonBinder) {
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc != null) {
            ClusterMirrorManager mm = svc.getMirrorManager();
            if (mm.isMirrorActive() && !mm.isMirrorViaDaemon()) {
                AppLogger.i(TAG, "Daemon arrived after direct-path mirror — restarting via daemon");
                stopMirror();
            }
        }
        if (mMirrorSurface != null && mMirrorSurface.isValid()
                && mFrameMirror.getVisibility() == View.VISIBLE) {
            attemptStart();
        }
    }

    /** Rebinds the existing TextureView when the selected headless Layout slot changes. */
    public void onLayoutTargetChanged() {
        FissionOrchestrator.LayoutMirrorTarget target =
                FissionOrchestrator.getSelectedLayoutMirrorTarget();
        String nextPackage = target != null ? target.pkg : null;
        if (mLayoutMirrorActive && java.util.Objects.equals(nextPackage, mLayoutMirrorPackage)) {
            return;
        }
        if (!mLayoutMirrorActive && nextPackage == null) return;
        stopMirrorForRestart();
        if (mMirrorSurface != null && mMirrorSurface.isValid()
                && mFrameMirror.getVisibility() == View.VISIBLE) {
            attemptStart();
        }
    }

    /** Attempts to start the mirror using whichever path is available. */
    public void attemptStart() {
        if (mMirrorSurface == null || !mMirrorSurface.isValid()) return;

        int viewW = mTextureView.getWidth();
        int viewH = mTextureView.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            AppLogger.d(TAG, "attemptStart: view not yet measured " + viewW + "x" + viewH);
            return;
        }

        FissionOrchestrator.LayoutMirrorTarget layoutTarget =
                FissionOrchestrator.getSelectedLayoutMirrorTarget();
        if (layoutTarget != null) {
            if (mLayoutMirrorActive && layoutTarget.pkg.equals(mLayoutMirrorPackage)) {
                mTextureView.setVisibility(View.VISIBLE);
                setPlaceholderVisible(false);
                return;
            }
            stopNormalMirror();
            FissionOrchestrator.LayoutMirrorTarget started =
                    FissionOrchestrator.startSelectedLayoutMirror(mMirrorSurface, viewW, viewH);
            MirrorProjection projection = started != null
                    ? MirrorProjection.create(started.width, started.height, viewW, viewH) : null;
            if (started != null && projection != null) {
                mLayoutMirrorActive = true;
                mLayoutMirrorPackage = started.pkg;
                mLayoutProjection = projection;
                mTextureView.setVisibility(View.VISIBLE);
                setPlaceholderVisible(false);
                AppLogger.i(TAG, "Layout mirror active pkg=" + started.pkg
                        + " displayId=" + started.displayId
                        + " content=" + started.width + "x" + started.height);
            } else {
                clearLayoutMirrorState();
                mTextureView.setVisibility(View.GONE);
                if (mPlaceholder != null) mPlaceholder.setText(R.string.mirror_unavailable);
                setPlaceholderVisible(true);
            }
            return;
        }

        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror();
            clearLayoutMirrorState();
        }

        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null) return;

        ClusterMirrorManager mm = svc.getMirrorManager();
        if (mm.isMirrorActive()) {
            AppLogger.d(TAG, "attemptStart: mirror already active");
            mTextureView.setVisibility(View.VISIBLE);
            setPlaceholderVisible(false);
            return;
        }

        Context ctx = mHost.getContext();
        Display clusterDisplay = null;
        int displayId = svc.getDisplayId();
        if (displayId >= 0) {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) clusterDisplay = dm.getDisplay(displayId);
        }

        boolean mirrorOk = false;
        IBinder daemonBinder = mHost.getDaemonBinder();
        if (daemonBinder != null) {
            mirrorOk = mm.startMirrorViaDaemon(
                    ctx, daemonBinder, clusterDisplay, mMirrorSurface, viewW, viewH);
        }
        if (!mirrorOk) {
            mirrorOk = mm.startMirror(ctx, clusterDisplay, mMirrorSurface, viewW, viewH);
        }

        if (mirrorOk) {
            mTextureView.setVisibility(View.VISIBLE);
            setPlaceholderVisible(false);
        } else {
            mTextureView.setVisibility(View.GONE);
            if (mPlaceholder != null) mPlaceholder.setText(R.string.mirror_unavailable);
            setPlaceholderVisible(true);
        }
    }

    public void stopMirror() {
        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror();
            clearLayoutMirrorState();
        }
        stopNormalMirror();
        setPlaceholderVisible(true);
    }

    private void stopMirrorForRestart() {
        if (mLayoutMirrorActive) {
            FissionOrchestrator.stopSelectedLayoutMirror();
            clearLayoutMirrorState();
        }
        stopNormalMirror();
    }

    private void stopNormalMirror() {
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null) return;
        ClusterMirrorManager mirror = svc.getMirrorManager();
        if (mirror.isMirrorViaDaemon()) mirror.stopMirrorViaDaemon(mHost.getDaemonBinder());
        mirror.stopMirror();
    }

    private void clearLayoutMirrorState() {
        mLayoutMirrorActive = false;
        mLayoutMirrorPackage = null;
        mLayoutProjection = null;
    }

    public void showPreview() {
        mFrameMirror.setVisibility(View.VISIBLE);
        attemptStart();
    }

    public void hidePreview() {
        stopMirror();
        mFrameMirror.setVisibility(View.GONE);
    }

    /** Recreates the Surface from the current SurfaceTexture and restarts the mirror. */
    public void recreateSurfaceAndRestart() {
        try {
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            int w = mTextureView.getWidth();
            int h = mTextureView.getHeight();
            if (st == null || w <= 0 || h <= 0) {
                AppLogger.w(TAG, "recreateSurface: not ready (w=" + w + " h=" + h + ")");
                return;
            }
            st.setDefaultBufferSize(w, h);
            if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
            mMirrorSurface = new Surface(st);
            AppLogger.i(TAG, "recreateSurface: new surface " + w + "x" + h);
            attemptStart();
        } catch (Throwable t) {
            AppLogger.w(TAG, "recreateSurface failed: " + t.getMessage());
        }
    }

    public Surface getMirrorSurface() { return mMirrorSurface; }
    public boolean isPreviewVisible() { return mFrameMirror.getVisibility() == View.VISIBLE; }

    /**
     * Maps touch coordinates from the mirror TextureView to the cluster display and injects them.
     * The SurfaceControl projection preserves the ratio (letterboxing), so we recalculate
     * the offset the same way setDisplayProjection did.
     */
    private void forwardTouchFromMirror(MotionEvent event) {
        if (mLayoutMirrorActive && mLayoutProjection != null) {
            forwardTouchToLayout(event);
            return;
        }
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null) return;
        com.byd.dashcast.cluster.mirror.ClusterInputForwarder forwarder = svc.getInputForwarder();
        if (forwarder == null) return;

        ClusterMirrorManager mirror = svc.getMirrorManager();
        if (mirror == null) return;

        // Use the projection params stored when setDisplayProjection was called.
        // This guarantees the touch offset/scale matches the actual rendered projection,
        // even if the view was resized since mirror start (avoids touch offset bugs).
        float scale   = mirror.getProjScale();
        // Reject non-finite scale too: a degenerate projection (e.g. a transient
        // 0-size cluster display) can leave scale = Infinity/NaN, which "scale <= 0f"
        // does not catch — it would map every touch to the clamp boundary.
        if (!Float.isFinite(scale) || scale <= 0f) return;  // Mirror not yet fully initialized

        float offsetX = mirror.getProjOffsetX();
        float offsetY = mirror.getProjOffsetY();
        int   clusterW = mirror.getClusterWidth();
        int   clusterH = mirror.getClusterHeight();
        if (clusterW <= 0 || clusterH <= 0) return;

        int pointerCount = Math.min(event.getPointerCount(), MAX_FWD_POINTERS);
        if (pointerCount <= 0) return;

        for (int i = 0; i < pointerCount; i++) {
            mFwdPointerIds[i] = event.getPointerId(i);
            float cx = (event.getX(i) - offsetX) / scale;
            float cy = (event.getY(i) - offsetY) / scale;
            mFwdClusterXs[i] = Math.max(0, Math.min(cx, clusterW - 1));
            mFwdClusterYs[i] = Math.max(0, Math.min(cy, clusterH - 1));
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            int ai = event.getActionIndex();
            if (ai >= 0 && ai < pointerCount) {
                AppLogger.d(TAG, "touch → ptrs=" + pointerCount
                        + " action=" + event.getActionMasked()
                        + " idx=" + ai
                        + " view(" + (int)event.getX(ai) + "," + (int)event.getY(ai) + ")"
                        + " off=(" + (int)offsetX + "," + (int)offsetY + ")"
                        + " scale=" + String.format(java.util.Locale.US, "%.3f", scale)
                        + " cluster=(" + (int)mFwdClusterXs[ai] + "," + (int)mFwdClusterYs[ai]
                        + ")/" + clusterW + "×" + clusterH);
            }
        }

        forwarder.forwardTouchFinalMulti(
                mFwdPointerIds,
                mFwdClusterXs,
                mFwdClusterYs,
                event.getActionMasked(),
                event.getActionIndex(),
                pointerCount
        );

        // v1.3.3 — DL5 only: after the finger is lifted from the cluster mirror,
        // wait 350 ms (enough for the cluster app to move input focus) then check
        // whether a focused editable node is visible on the cluster. If so,
        // auto-launch the keyboard bridge. This is additive to the event-driven
        // path in ClusterImeWatcherService (TYPE_VIEW_FOCUSED), which can miss
        // events when the ROM returns displayId=-1 on secondary-display a11y events.
        // Guard: only on DL5, only on ACTION_UP (lift), only when mirror is active.
        int actionMasked = event.getActionMasked();
        if ((actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_POINTER_UP)
                && ClusterService.sIsRunning) {
            try {
                if (Platform.get().isDiLink5(mHost.getContext())) {
                    mTextureView.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ClusterImeWatcherService
                                        .checkAndLaunchBridgeIfNeeded(mHost.getContext());
                            } catch (Throwable t) {
                                AppLogger.e(TAG, "auto-keyboard post-touch check failed", t);
                            }
                        }
                    }, 350);
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "auto-keyboard DL5 guard check failed", t);
            }
        }
    }

    private void forwardTouchToLayout(MotionEvent event) {
        MirrorProjection projection = mLayoutProjection;
        if (projection == null) return;
        int pointerCount = Math.min(event.getPointerCount(), MAX_FWD_POINTERS);
        if (pointerCount <= 0) return;

        for (int i = 0; i < pointerCount; i++) {
            event.getPointerProperties(i, mLayoutPointerProperties[i]);
            event.getPointerCoords(i, mLayoutPointerCoords[i]);
            mLayoutPointerCoords[i].x = projection.mapX(event.getX(i));
            mLayoutPointerCoords[i].y = projection.mapY(event.getY(i));
        }

        MotionEvent mapped = null;
        try {
            mapped = MotionEvent.obtain(event.getDownTime(), event.getEventTime(),
                    event.getAction(), pointerCount,
                    mLayoutPointerProperties, mLayoutPointerCoords,
                    event.getMetaState(), event.getButtonState(),
                    event.getXPrecision(), event.getYPrecision(),
                    event.getDeviceId(), event.getEdgeFlags(),
                    InputDevice.SOURCE_TOUCHSCREEN, event.getFlags());
                FissionOrchestrator.injectSelectedLayoutMotion(mapped);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                AppLogger.d(TAG, "Layout touch pkg=" + mLayoutMirrorPackage
                        + " view=(" + (int) event.getX() + "," + (int) event.getY() + ")"
                        + " slot=(" + (int) mLayoutPointerCoords[0].x + ","
                        + (int) mLayoutPointerCoords[0].y + ")");
            }
        } catch (Exception error) {
            AppLogger.e(TAG, "Layout touch injection failed", error);
        } finally {
            if (mapped != null) mapped.recycle();
        }
    }

    public void destroy() {
        stopMirror();
        if (mMirrorSurface != null) {
            mMirrorSurface.release();
            mMirrorSurface = null;
        }
    }

    private void setPlaceholderVisible(boolean visible) {
        if (mPlaceholder != null) {
            mPlaceholder.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
