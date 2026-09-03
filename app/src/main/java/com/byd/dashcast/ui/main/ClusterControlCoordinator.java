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
 * Owns the cluster control panel: resize entry point, split button, relaunch button,
 * and panel expand/collapse toggle.
 *
 * <h3>Responsibilities extracted from MainActivity</h3>
 * <ul>
 *   <li>"Adjust" button → the per-app hand-drawn rectangle editor (ClusterResizeActivity)
 *   <li>Panel content show/hide with {@code btnPanelToggle}
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
    private final Button        mBtnPanelToggle;
    private final Button        mBtnToggleResize;
    private final TextView      mTvControlAppName;
    private final Button        mBtnSplitLayout;
    private final Button        mBtnRelaunch;
    private final Host          mHost;

    public ClusterControlCoordinator(LinearLayout panelClusterControl,
                                      LinearLayout panelControlsContent,
                                      Button btnPanelToggle,
                                      Button btnToggleResize, TextView tvControlAppName,
                                      Button btnSplitLayout, Button btnRelaunch,
                                      Host host) {
        mPanelClusterControl  = panelClusterControl;
        mPanelControlsContent = panelControlsContent;
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

        // v1.8.2 — "Adjust" now opens the hand-drawn rectangle editor directly. The symmetric
        // W/H seekbars it used to expand are gone: they wrote per-app inset margins that were
        // applied twice (launch bounds + display overscan) and cost 40% of the panel on the old
        // 80/50 default (INC-20260725-211405). One resize mechanism, one entry point.
        if (mBtnToggleResize != null) {
            mBtnToggleResize.setOnClickListener(v -> openRectEditor());
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

    /** Opens the hand-drawn rectangle editor for the app currently on the cluster. */
    private void openRectEditor() {
        final ClusterService svc = mHost.getClusterServiceIfBound();
        final String pkg = mHost.getCurrentDashboardPkg();
        final int clusterId = (svc != null) ? svc.getDisplayId() : -1;
        if (pkg == null || clusterId <= 0) {
            AppLogger.w(TAG, "openRectEditor: not ready (pkg=" + pkg + " display=" + clusterId + ")");
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(
                    mHost.getContext(), com.byd.dashcast.cluster.dpi.ClusterResizeActivity.class);
            i.putExtra(com.byd.dashcast.cluster.dpi.ClusterResizeActivity.EXTRA_PACKAGE, pkg);
            i.putExtra(com.byd.dashcast.cluster.dpi.ClusterResizeActivity.EXTRA_DISPLAY_ID, clusterId);
            String saved = mHost.getContext()
                    .getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg, null);
            if (saved != null) {
                i.putExtra(com.byd.dashcast.cluster.dpi.ClusterResizeActivity.EXTRA_INIT_LTRB, saved);
            }
            mHost.getContext().startActivity(i);
            AppLogger.i(TAG, "openRectEditor pkg=" + pkg + " display=" + clusterId);
        } catch (Throwable t) {
            AppLogger.w(TAG, "openRectEditor failed: " + t.getMessage());
        }
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
        if (mBtnToggleResize != null)
            mBtnToggleResize.setText(
                    mHost.getContext().getString(com.byd.dashcast.R.string.btn_adjust));
    }

    /** Updates the label in the control panel header to the currently projected app. */
    public void setControlAppName(String name) {
        if (mTvControlAppName != null)
            mTvControlAppName.setText(name != null ? name : "");
    }

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
