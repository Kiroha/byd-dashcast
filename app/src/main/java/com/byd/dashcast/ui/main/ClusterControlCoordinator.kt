package com.byd.dashcast.ui.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.view.isVisible

import com.byd.dashcast.R
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.dpi.ClusterResizeActivity
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.data.prefs.ClusterPrefs

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
class ClusterControlCoordinator(
    private val mPanelClusterControl: LinearLayout?,  // outer panel (show/hide by fullscreen)
    private val mPanelControlsContent: LinearLayout?, // inner collapsible content (btnPanelToggle)
    private val mBtnPanelToggle: Button?,
    private val mBtnToggleResize: Button?,
    private val mTvControlAppName: TextView?,
    private val mBtnSplitLayout: Button?,
    private val mBtnRelaunch: Button?,
    private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        fun getClusterServiceIfBound(): ClusterService?
        fun getCurrentDashboardPkg(): String?
        fun onSplitLayoutRequested(anchor: View)
        fun onRelaunchRequested()
        /** Called when the user drags a resize seekbar — update the live inset overlay. */
        fun onInsetChanged(h: Int, v: Int)
        /** Called when the resize sub-panel is shown or hidden — show/hide the inset overlay. */
        fun onResizePanelToggled(visible: Boolean)
    }

    init {
        setup()
    }

    private fun setup() {
        // Panel content collapse/expand (inner content, not the outer panel)
        mBtnPanelToggle?.let { toggle ->
            toggle.setOnClickListener {
                val content = mPanelControlsContent ?: return@setOnClickListener
                val visible = content.isVisible
                content.visibility = if (visible) View.GONE else View.VISIBLE
                toggle.text = if (visible) "▲" else "▼"
            }
        }

        // v1.8.2 — "Adjust" now opens the hand-drawn rectangle editor directly. The symmetric
        // W/H seekbars it used to expand are gone: they wrote per-app inset margins that were
        // applied twice (launch bounds + display overscan) and cost 40% of the panel on the old
        // 80/50 default (INC-20260725-211405). One resize mechanism, one entry point.
        mBtnToggleResize?.setOnClickListener { openRectEditor() }

        // Split layout
        mBtnSplitLayout?.setOnClickListener { v -> mHost.onSplitLayoutRequested(v) }

        // Relaunch
        mBtnRelaunch?.setOnClickListener { mHost.onRelaunchRequested() }
    }

    /** Opens the hand-drawn rectangle editor for the app currently on the cluster. */
    private fun openRectEditor() {
        val svc = mHost.getClusterServiceIfBound()
        val pkg = mHost.getCurrentDashboardPkg()
        val clusterId = if (svc != null) svc.getDisplayId() else -1
        if (pkg == null || clusterId <= 0) {
            AppLogger.w(TAG, "openRectEditor: not ready (pkg=" + pkg + " display=" + clusterId + ")")
            return
        }
        try {
            val i = Intent(mHost.getContext(), ClusterResizeActivity::class.java)
            i.putExtra(ClusterResizeActivity.EXTRA_PACKAGE, pkg)
            i.putExtra(ClusterResizeActivity.EXTRA_DISPLAY_ID, clusterId)
            val saved = mHost.getContext()
                    .getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + pkg, null)
            if (saved != null) {
                i.putExtra(ClusterResizeActivity.EXTRA_INIT_LTRB, saved)
            }
            mHost.getContext().startActivity(i)
            AppLogger.i(TAG, "openRectEditor pkg=" + pkg + " display=" + clusterId)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "openRectEditor failed: " + t.message)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Shows the outer cluster control panel (called by FullscreenMirrorCoordinator on enter). */
    fun showPanel() {
        mPanelClusterControl?.visibility = View.VISIBLE
    }

    /** Hides the outer cluster control panel (called by FullscreenMirrorCoordinator on exit). */
    fun hidePanel() {
        mPanelClusterControl?.visibility = View.GONE
    }

    /** Resets the resize sub-panel to its closed state (call on app switch). */
    fun collapseResizePanel() {
        mBtnToggleResize?.text = mHost.getContext().getString(R.string.btn_adjust)
    }

    /** Updates the label in the control panel header to the currently projected app. */
    fun setControlAppName(name: String?) {
        mTvControlAppName?.text = name ?: ""
    }

    /**
     * Pre-arms the inner content panel to its expanded state (called before entering fullscreen
     * so the controls are visible the first time the outer panel becomes visible).
     */
    fun expandContent() {
        mPanelControlsContent?.visibility = View.VISIBLE
        mBtnPanelToggle?.text = "▼"
    }

    /**
     * Hides the resize affordance entirely on platforms where cluster task resize is
     * unsupported (DL5 / API 32+). Call once after coordinators are set up.
     */
    fun hideResizeIfUnsupported() {
        mBtnToggleResize?.visibility = View.GONE
    }

    /**
     * Updates the split button appearance to reflect whether split mode is active.
     * Active = one of the two 50% slots is occupied; inactive = full-screen.
     */
    fun setSplitActive(active: Boolean) {
        val btn = mBtnSplitLayout ?: return
        val ctx = mHost.getContext()
        if (active) {
            btn.text = ctx.getString(R.string.split_btn_exit)
            btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.split_active))
        } else {
            btn.text = ctx.getString(R.string.btn_cluster_split)
            btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.split_inactive))
        }
    }

    companion object {
        private const val TAG = "ClusterControlCoordinator"
    }
}
