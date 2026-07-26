package com.byd.dashcast.ui.main;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.proxy.ShellGateway;

/**
 * Owns the cluster control panel: resize seekbars, split button, relaunch button,
 * and panel expand/collapse toggle.
 *
 * <h3>Responsibilities extracted from MainActivity</h3>
 * <ul>
 *   <li>SeekBar W/H wiring → live {@code onInsetChanged} callback + {@code ClusterService.resizeActiveTask()}
 *   <li>Panel content show/hide with {@code btnPanelToggle}
 *   <li>Resize sub-panel toggle with {@code btnToggleResize} + overlay callback
 *   <li>Split layout button → FissionActivity launch
 *   <li>Relaunch button → {@code ClusterService.launchOnDashboard()}
 *   <li>Per-app control label update
 * </ul>
 */
public final class ClusterControlCoordinator {

    private static final String TAG = "ClusterControlCoordinator";

    public interface Host {
        Context getContext();
        ClusterService getClusterServiceIfBound();
        String getCurrentDashboardPkg();
        void onSplitLayoutRequested(android.view.View anchor);
        void onRelaunchRequested();
        /** Called when the user drags a resize seekbar — update the live inset overlay. */
        void onInsetChanged(int h, int v);
        /** Called when the resize sub-panel is shown or hidden — show/hide the inset overlay. */
        void onResizePanelToggled(boolean visible);
    }

    private final LinearLayout  mPanelClusterControl;  // outer panel (show/hide by fullscreen)
    private final LinearLayout  mPanelControlsContent; // inner collapsible content (btnPanelToggle)
    private final LinearLayout  mPanelResize;
    private final SeekBar       mSbResizeW;
    private final SeekBar       mSbResizeH;
    private final TextView      mTvResizeW;
    private final TextView      mTvResizeH;
    private final Button        mBtnResizeApply;
    private final Button        mBtnPanelToggle;
    private final Button        mBtnToggleResize;
    private final TextView      mTvControlAppName;
    private final Button        mBtnSplitLayout;
    private final Button        mBtnRelaunch;
    private final Host          mHost;

    public ClusterControlCoordinator(LinearLayout panelClusterControl,
                                      LinearLayout panelControlsContent,
                                      LinearLayout panelResize,
                                      SeekBar sbResizeW, SeekBar sbResizeH,
                                      TextView tvResizeW, TextView tvResizeH,
                                      Button btnResizeApply, Button btnPanelToggle,
                                      Button btnToggleResize, TextView tvControlAppName,
                                      Button btnSplitLayout, Button btnRelaunch,
                                      Host host) {
        mPanelClusterControl  = panelClusterControl;
        mPanelControlsContent = panelControlsContent;
        mPanelResize          = panelResize;
        mSbResizeW            = sbResizeW;
        mSbResizeH            = sbResizeH;
        mTvResizeW            = tvResizeW;
        mTvResizeH            = tvResizeH;
        mBtnResizeApply       = btnResizeApply;
        mBtnPanelToggle       = btnPanelToggle;
        mBtnToggleResize      = btnToggleResize;
        mTvControlAppName     = tvControlAppName;
        mBtnSplitLayout       = btnSplitLayout;
        mBtnRelaunch          = btnRelaunch;
        mHost                 = host;
        setup();
    }

    private void setup() {
        // Panel content collapse/expand (inner content, not the outer panel)
        if (mBtnPanelToggle != null) {
            mBtnPanelToggle.setOnClickListener(v -> {
                if (mPanelControlsContent == null) return;
                boolean visible = mPanelControlsContent.getVisibility() == View.VISIBLE;
                mPanelControlsContent.setVisibility(visible ? View.GONE : View.VISIBLE);
                mBtnPanelToggle.setText(visible ? "▲" : "▼");
            });
        }

        // Resize sub-panel toggle
        if (mBtnToggleResize != null) {
            mBtnToggleResize.setOnClickListener(v -> {
                if (mPanelResize == null) return;
                boolean nowVisible = mPanelResize.getVisibility() != View.VISIBLE;
                mPanelResize.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
                mBtnToggleResize.setText(nowVisible
                        ? "▲ " + mHost.getContext().getString(
                                com.byd.dashcast.R.string.btn_adjust)
                        : mHost.getContext().getString(
                                com.byd.dashcast.R.string.btn_adjust));
                mHost.onResizePanelToggled(nowVisible);
            });
        }

        // SeekBar W — live overlay update on drag
        if (mSbResizeW != null) {
            mSbResizeW.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (mTvResizeW != null) mTvResizeW.setText(String.valueOf(progress));
                    if (fromUser)
                        mHost.onInsetChanged(progress,
                                mSbResizeH != null ? mSbResizeH.getProgress() : 0);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // SeekBar H — live overlay update on drag
        if (mSbResizeH != null) {
            mSbResizeH.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (mTvResizeH != null) mTvResizeH.setText(String.valueOf(progress));
                    if (fromUser)
                        mHost.onInsetChanged(
                                mSbResizeW != null ? mSbResizeW.getProgress() : 0, progress);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // Apply button (explicit tap)
        if (mBtnResizeApply != null) {
            mBtnResizeApply.setOnClickListener(v -> applyResize());
        }

        // Split layout
        if (mBtnSplitLayout != null) {
            mBtnSplitLayout.setOnClickListener(v -> mHost.onSplitLayoutRequested(v));
        }

        // Relaunch
        if (mBtnRelaunch != null) {
            mBtnRelaunch.setOnClickListener(v -> mHost.onRelaunchRequested());
        }
    }

    private void applyResize() {
        final ClusterService svc = mHost.getClusterServiceIfBound();
        final String pkg = mHost.getCurrentDashboardPkg();
        if (svc == null || pkg == null) {
            AppLogger.w(TAG, "applyResize: not ready (svc=" + svc + " pkg=" + pkg + ")");
            return;
        }
        final int w = mSbResizeW != null ? mSbResizeW.getProgress() : 0;
        final int h = mSbResizeH != null ? mSbResizeH.getProgress() : 0;

        // Persist inset values immediately on the main thread. Also drop any hand-drawn
        // rectangle for this app: the seekbar is now the source of truth (last tool wins),
        // so InsetAutoApplicator re-applies these symmetric insets and not a stale rect.
        mHost.getContext().getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(SettingsActivity.PREF_INSET_H_PREFIX + pkg, w)
                .putInt(SettingsActivity.PREF_INSET_V_PREFIX + pkg, h)
                .remove(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg)
                .apply();

        AppLogger.i(TAG, "applyResize " + w + "/" + h + " for " + pkg);

        // wm overscan — skipped on DL5 (API 30+ removed the command).
        final int clusterId = svc.getDisplayId();
        if (clusterId > 0 && !AdbLocalClient.isDiLink5Safe(mHost.getContext())) {
            ShellGateway.execShell(mHost.getContext(),
                    "wm overscan " + w + "," + h + "," + w + "," + h + " -d " + clusterId);
        }

        // findRunningTaskId() is blocking — run off the main thread.
        new Thread(() -> {
            int taskId = svc.findRunningTaskId(pkg);
            if (taskId > 0) {
                svc.resizeActiveTask(taskId, pkg);
                AppLogger.i(TAG, "applyResize taskId=" + taskId + " pkg=" + pkg);
            } else {
                AppLogger.w(TAG, "applyResize: task not found for " + pkg);
            }
        }, "resize-apply").start();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Shows the outer cluster control panel (called by FullscreenMirrorCoordinator on enter). */
    public void showPanel() {
        if (mPanelClusterControl != null)
            mPanelClusterControl.setVisibility(View.VISIBLE);
    }

    /** Hides the outer cluster control panel (called by FullscreenMirrorCoordinator on exit). */
    public void hidePanel() {
        if (mPanelClusterControl != null)
            mPanelClusterControl.setVisibility(View.GONE);
    }

    /** Resets the resize sub-panel to its closed state (call on app switch). */
    public void collapseResizePanel() {
        if (mPanelResize != null) mPanelResize.setVisibility(View.GONE);
        if (mBtnToggleResize != null)
            mBtnToggleResize.setText(
                    mHost.getContext().getString(com.byd.dashcast.R.string.btn_adjust));
    }

    /** Updates the label in the control panel header to the currently projected app. */
    public void setControlAppName(String name) {
        if (mTvControlAppName != null)
            mTvControlAppName.setText(name != null ? name : "");
    }

    /** Loads persisted inset values for the given package into the seekbars. */
    public void loadInsets(String packageName, int insetH, int insetV) {
        if (mSbResizeW != null) mSbResizeW.setProgress(insetH);
        if (mSbResizeH != null) mSbResizeH.setProgress(insetV);
        if (mTvResizeW != null) mTvResizeW.setText(String.valueOf(insetH));
        if (mTvResizeH != null) mTvResizeH.setText(String.valueOf(insetV));
    }

    /** Returns the current horizontal inset value from the seekbar. */
    public int getInsetH() { return mSbResizeW != null ? mSbResizeW.getProgress() : 0; }

    /** Returns the current vertical inset value from the seekbar. */
    public int getInsetV() { return mSbResizeH != null ? mSbResizeH.getProgress() : 0; }

    /**
     * Pre-arms the inner content panel to its expanded state (called before entering fullscreen
     * so the controls are visible the first time the outer panel becomes visible).
     */
    public void expandContent() {
        if (mPanelControlsContent != null)
            mPanelControlsContent.setVisibility(View.VISIBLE);
        if (mBtnPanelToggle != null)
            mBtnPanelToggle.setText("▼");
    }

    /**
     * Hides the resize affordance entirely on platforms where cluster task resize is
     * unsupported (DL5 / API 32+). Call once after coordinators are set up.
     */
    public void hideResizeIfUnsupported() {
        if (mBtnToggleResize != null) mBtnToggleResize.setVisibility(View.GONE);
        if (mPanelResize != null) mPanelResize.setVisibility(View.GONE);
    }

    /**
     * Updates the split button appearance to reflect whether split mode is active.
     * Active = one of the two 50% slots is occupied; inactive = full-screen.
     */
    public void setSplitActive(boolean active) {
        if (mBtnSplitLayout == null) return;
        Context ctx = mHost.getContext();
        if (active) {
            mBtnSplitLayout.setText(ctx.getString(com.byd.dashcast.R.string.split_btn_exit));
            mBtnSplitLayout.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ctx.getColor(com.byd.dashcast.R.color.split_active)));
        } else {
            mBtnSplitLayout.setText(ctx.getString(com.byd.dashcast.R.string.btn_cluster_split));
            mBtnSplitLayout.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ctx.getColor(com.byd.dashcast.R.color.split_inactive)));
        }
    }
}
