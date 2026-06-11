package com.byd.dashcast.ui.main;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.ClusterService;
import com.byd.dashcast.R;
import com.byd.dashcast.dashboard.ClusterMirrorManager;

/**
 * Owns the TextureView / SurfaceTexture / Surface lifecycle for the cluster preview mirror.
 *
 * All methods must be called on the main thread.
 */
public final class MirrorCoordinator {

    private static final String TAG = "MirrorCoordinator";

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

    public MirrorCoordinator(TextureView textureView, FrameLayout frameMirror,
                              TextView placeholder, Host host) {
        mTextureView = textureView;
        mFrameMirror = frameMirror;
        mPlaceholder = placeholder;
        mHost        = host;
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

    /** Attempts to start the mirror using whichever path is available. */
    public void attemptStart() {
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc == null || mMirrorSurface == null || !mMirrorSurface.isValid()) return;

        ClusterMirrorManager mm = svc.getMirrorManager();
        if (mm.isMirrorActive()) {
            AppLogger.d(TAG, "attemptStart: mirror already active");
            mTextureView.setVisibility(View.VISIBLE);
            setPlaceholderVisible(false);
            return;
        }

        int viewW = mTextureView.getWidth();
        int viewH = mTextureView.getHeight();
        if (viewW <= 0 || viewH <= 0) {
            AppLogger.d(TAG, "attemptStart: view not yet measured " + viewW + "x" + viewH);
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
        ClusterService svc = mHost.getClusterServiceIfBound();
        if (svc != null) svc.getMirrorManager().stopMirror();
        setPlaceholderVisible(true);
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
