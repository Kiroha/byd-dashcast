package com.byd.dashcast.cluster.dpi;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.R;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * v1.2.71 — Fullscreen editor that lets the user position/resize a fission-cluster
 * app via direct manipulation (drag + 4 corner handles) over a live mirror of
 * the cluster display.
 *
 * <p>Intent extras:
 * <ul>
 *   <li>{@link #EXTRA_PACKAGE} (String) — package of the app already on cluster.</li>
 *   <li>{@link #EXTRA_DISPLAY_ID} (int)  — cluster display id (fission, typically 1).</li>
 *   <li>{@link #EXTRA_INIT_LTRB} (int[4]) — optional starting rect in cluster coords;
 *       defaults to full cluster.</li>
 * </ul>
 *
 * <p>UX flow:
 * <ol>
 *   <li>Mirror displays cluster pixels into a {@link TextureView} (best-effort
 *       via the daemon path; falls back to a dark canvas).</li>
 *   <li>{@link ResizeFrameView} draws the editable rectangle and routes touches.</li>
 *   <li>Every frame change schedules a throttled {@code moveAndResize} call (60ms).</li>
 *   <li><b>Annuler</b> reverts to the initial rect (one final apply) then finishes.</li>
 *   <li><b>Valider</b> just finishes (last applied rect is already in place).</li>
 * </ol>
 *
 * <p><b>Hands off</b> the v1.2.70 cascade in {@code Phase4TaskVerbs.moveAndResize} — we
 * only feed it rectangles. Do not add bounds-massaging or pre/post hooks here.
 */
public class ClusterResizeActivity extends Activity
        implements TextureView.SurfaceTextureListener, ResizeFrameView.Listener {

    private static final String TAG = "ClusterResize";

    public static final String EXTRA_PACKAGE    = "pkg";
    public static final String EXTRA_DISPLAY_ID = "displayId";
    public static final String EXTRA_INIT_LTRB  = "initLtrb";

    private static final int APPLY_THROTTLE_MS = 60;

    private static final java.util.concurrent.ExecutorService sResizeExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cluster-resize-apply");
                t.setDaemon(true);
                return t;
            });

    /** v1.2.84 — persisted across launches so the user only sees the warning once
     *  per activation request (they re-confirm if they previously disabled it). */
    private static final String PREFS_NAME = "dashcast";
    private static final String PREF_LIVE_MODE = "cluster_resize_live_mode";

    private TextureView        mTexture;
    private ResizeFrameView    mFrame;
    private TextView           mCoords;
    private Button             mCancel;
    private Button             mOk;
    private Button             mClose;
    private MaterialSwitch     mSwLive;
    /** v1.2.84 — when false (default), drag/preset/release does NOT push to the
     *  cluster; only Valider / Annuler commit. When true, every frame change is
     *  applied live (legacy behaviour) — guarded by a confirmation popup. */
    private boolean            mLiveMode = false;
    private String  mPkg;
    private int     mDisplayId = 1;
    private int[]   mInitRect;     // [l,t,r,b]

    private ClusterMirrorManager mMirror;
    private boolean              mMirrorStarted = false;
    private Surface              mActiveSurface = null;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Object  mApplyLock = new Object();
    private int[]   mPendingRect;       // [l,t,r,b] or null
    private boolean mApplyScheduled = false;
    private long    mLastApplyTs    = 0;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_cluster_resize);

        Intent it = getIntent();
        mPkg       = it.getStringExtra(EXTRA_PACKAGE);
        mDisplayId = it.getIntExtra(EXTRA_DISPLAY_ID, 1);
        mInitRect  = it.getIntArrayExtra(EXTRA_INIT_LTRB);
        if (mPkg == null || mPkg.isEmpty()) {
            Toast.makeText(this, R.string.resize_no_package, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mTexture = findViewById(R.id.resize_texture);
        mFrame   = findViewById(R.id.resize_frame);
        mCoords  = findViewById(R.id.resize_coords);
        mCancel  = findViewById(R.id.resize_btn_cancel);
        mOk      = findViewById(R.id.resize_btn_ok);
        mClose   = findViewById(R.id.resize_btn_close);
        mSwLive  = findViewById(R.id.resize_sw_live);

        ClusterMirrorManager.unlockHiddenApis();
        mMirror = new ClusterMirrorManager();

        int cw = mMirror.getClusterWidth();
        int ch = mMirror.getClusterHeight();
        if (mInitRect == null || mInitRect.length != 4) {
            mInitRect = new int[]{ 0, 0, cw, ch };
        }
        mFrame.setClusterSize(cw, ch);
        mFrame.setFrame(mInitRect[0], mInitRect[1], mInitRect[2], mInitRect[3]);
        mFrame.setListener(this);
        updateCoordsLabel(mInitRect[0], mInitRect[1], mInitRect[2], mInitRect[3]);

        // v1.2.72 — Presets (car ergonomics: one-tap to common layouts).
        final int CW = cw, CH = ch;
        findViewById(R.id.resize_preset_full).setOnClickListener(v ->
                applyPreset(0, 0, CW, CH));
        findViewById(R.id.resize_preset_left).setOnClickListener(v ->
                applyPreset(0, 0, CW / 2, CH));
        findViewById(R.id.resize_preset_right).setOnClickListener(v ->
                applyPreset(CW / 2, 0, CW, CH));
        findViewById(R.id.resize_preset_center).setOnClickListener(v -> {
            int w = (int) (CW * 0.60f), h = (int) (CH * 0.60f);
            int l = (CW - w) / 2, t = (CH - h) / 2;
            applyPreset(l, t, l + w, t + h);
        });

        mTexture.setSurfaceTextureListener(this);

        // v1.2.84 — restore live-mode preference (default OFF). The switch reflects
        // the stored value but does NOT trigger the warning popup on initial bind;
        // the popup only fires on a fresh user toggle from OFF → ON.
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mLiveMode = prefs.getBoolean(PREF_LIVE_MODE, false);
        mSwLive.setChecked(mLiveMode);
        mSwLive.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !mLiveMode) {
                // OFF → ON requested: confirm with a popup that warns about
                // flashes / artefacts on the cluster screen.
                new MaterialAlertDialogBuilder(ClusterResizeActivity.this)
                        .setTitle(R.string.resize_live_warn_title)
                        .setMessage(R.string.resize_live_warn_msg)
                        .setPositiveButton(R.string.resize_live_warn_ok, (d, w) -> {
                            mLiveMode = true;
                            prefs.edit().putBoolean(PREF_LIVE_MODE, true).apply();
                            AppLogger.i(TAG, "live mode ON (user confirmed)");
                        })
                        .setNegativeButton(android.R.string.cancel, (d, w) ->
                                mSwLive.setChecked(false))
                        .setOnCancelListener(d -> mSwLive.setChecked(false))
                        .show();
            } else if (!checked) {
                mLiveMode = false;
                prefs.edit().putBoolean(PREF_LIVE_MODE, false).apply();
                AppLogger.i(TAG, "live mode OFF");
            }
        });

        mCancel.setOnClickListener(v -> {
            // Annuler: revert to the initial rectangle visually + commit it, then exit.
            int[] r = mInitRect;
            mFrame.setFrame(r[0], r[1], r[2], r[3]);
            updateCoordsLabel(r[0], r[1], r[2], r[3]);
            scheduleApply(r[0], r[1], r[2], r[3], /*commit*/ true);
            finish();
        });
        mOk.setOnClickListener(v -> {
            // v1.2.84 — Valider applies the current frame to the cluster but does NOT
            // close the activity, so the user can keep adjusting. To leave, use the
            // floating Close button (top-end) or Annuler (revert + close).
            Rect rc = mFrame.getFrame();
            scheduleApply(rc.left, rc.top, rc.right, rc.bottom, /*commit*/ true);
        });
        mClose.setOnClickListener(v -> finish());

        AppLogger.d(TAG, "onCreate pkg=" + mPkg + " display=" + mDisplayId
                + " init=[" + mInitRect[0] + "," + mInitRect[1] + ","
                + mInitRect[2] + "," + mInitRect[3] + "] liveMode=" + mLiveMode);
    }

    @Override
    protected void onDestroy() {
        if (mFrame != null) mFrame.clearGestureExclusion();
        try { mUi.removeCallbacksAndMessages(null); } catch (Throwable ignore) {}
        stopMirrorSafely();
        super.onDestroy();
    }

    /** v1.2.72 — Apply a preset rectangle: updates frame + commits immediately. */
    private void applyPreset(int l, int t, int r, int b) {
        mFrame.setFrame(l, t, r, b);
        updateCoordsLabel(l, t, r, b);
        // v1.2.84 — only push to the cluster when live mode is on; otherwise the
        // user explicitly validates via the Valider button.
        if (mLiveMode) {
            scheduleApply(l, t, r, b, /*commit*/ true);
        }
    }

    // ── Mirror plumbing ────────────────────────────────────────────────────

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (mMirrorStarted) return;
        Display clusterDisplay = null;
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) clusterDisplay = dm.getDisplay(mDisplayId);
        mActiveSurface = new Surface(st);
        boolean ok = false;
        IBinder daemon = ProxyClient.getDaemonBinder();
        if (daemon != null) {
            try {
                ok = mMirror.startMirrorViaDaemon(this, daemon, clusterDisplay, mActiveSurface, w, h);
            } catch (Throwable t) {
                AppLogger.w(TAG, "startMirrorViaDaemon threw: " + t.getMessage());
            }
        }
        if (!ok) {
            try {
                ok = mMirror.startMirror(this, clusterDisplay, mActiveSurface, w, h);
            } catch (Throwable t) {
                AppLogger.w(TAG, "startMirror threw: " + t.getMessage());
            }
        }
        mMirrorStarted = ok;
        AppLogger.i(TAG, "mirror started=" + ok + " (view=" + w + "x" + h + ")");
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { /* no-op */ }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stopMirrorSafely();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { /* no-op */ }

    private void stopMirrorSafely() {
        if (mMirrorStarted && mMirror != null) {
            IBinder daemon = ProxyClient.getDaemonBinder();
            try {
                if (daemon != null) mMirror.stopMirrorViaDaemon(daemon);
                else                mMirror.stopMirror();
            } catch (Throwable t) {
                AppLogger.w(TAG, "stopMirror threw: " + t.getMessage());
            }
            mMirrorStarted = false;
        }
        if (mActiveSurface != null) {
            try {
                mActiveSurface.release();
            } catch (Throwable t) {
                AppLogger.w(TAG, "release active surface threw: " + t.getMessage());
            }
            mActiveSurface = null;
        }
    }

    // ── Frame callbacks (touch) ────────────────────────────────────────────

    @Override
    public void onFrameChanged(int l, int t, int r, int b, boolean commit) {
        updateCoordsLabel(l, t, r, b);
        // v1.2.84 — by default the frame moves silently; only push to the cluster
        // when the user opted into live mode. Without live mode the user must press
        // Valider to commit the rectangle.
        if (mLiveMode) {
            scheduleApply(l, t, r, b, commit);
        }
    }

    private void updateCoordsLabel(int l, int t, int r, int b) {
        mCoords.setText(getString(R.string.resize_coords_fmt,
                l, t, r, b, r - l, b - t));
    }

    // ── Throttled apply ────────────────────────────────────────────────────

    private void scheduleApply(int l, int t, int r, int b, boolean commit) {
        synchronized (mApplyLock) {
            mPendingRect = new int[]{ l, t, r, b };
            if (commit) {
                // Immediate dispatch for final commits.
                mUi.removeCallbacks(mApplyRunnable);
                mUi.post(mApplyRunnable);
                return;
            }
            if (mApplyScheduled) return;
            long now   = System.currentTimeMillis();
            long delay = Math.max(0, APPLY_THROTTLE_MS - (now - mLastApplyTs));
            mApplyScheduled = true;
            mUi.postDelayed(mApplyRunnable, delay);
        }
    }

    private final Runnable mApplyRunnable = new Runnable() {
        @Override public void run() {
            int[] rect;
            synchronized (mApplyLock) {
                mApplyScheduled = false;
                if (mPendingRect == null) return;
                rect = mPendingRect;
                mPendingRect = null;
                mLastApplyTs = System.currentTimeMillis();
            }
            final int l = rect[0], t = rect[1], r = rect[2], b = rect[3];
            sResizeExec.execute(() -> {
                try {
                    if (!ProxyClient.isConnected()) {
                        ProxyClient.connect(ClusterResizeActivity.this);
                    }
                    String log = ProxyClient.moveAndResize(mPkg, mDisplayId, l, t, r, b);
                    AppLogger.d(TAG, "moveAndResize [" + l + "," + t + "," + r + "," + b + "] → " + log);
                } catch (Throwable th) {
                    AppLogger.w(TAG, "moveAndResize failed: " + th.getMessage());
                }
            });
        }
    };

    // ── System UI ──────────────────────────────────────────────────────────

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
