package com.byd.dashcast.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.byd.dashcast.R
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.ui.hotspot.HotspotActivity
import com.byd.dashcast.ui.settings.SettingsActivity

/**
 * Owns the navigation rail status dot/text and wires the long-press overflow
 * menu trigger. The actual menu content is delegated to the Host so MainActivity
 * keeps ownership of all 9 programmatic menu items.
 */
class NavigationCoordinator(
    private val mStatusDot: View?,
    private val mTvDashboardStatus: TextView?,
    private val mIvNavLogo: ImageView?,
    private val mNavHotspot: View?,
    private val mHost: Host
) {

    interface Host {
        fun getContext(): Context
        fun onShowOverflowMenu(anchor: View)
        fun startActivity(intent: Intent)
    }

    private val mStatusDotDrawable: GradientDrawable

    init {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        if (mStatusDot != null) mStatusDot.background = drawable
        mStatusDotDrawable = drawable
        setupOverflowMenu()
        refreshHotspot()
    }

    private fun setupOverflowMenu() {
        if (mIvNavLogo == null) return
        mIvNavLogo.setOnLongClickListener { v ->
            mHost.onShowOverflowMenu(v)
            true
        }
    }

    // ── Status dot ────────────────────────────────────────────────────────────

    fun setStatusOff() {
        setDotColor(DOT_COLOR_OFF)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.main_cluster_status_off)
        }
    }

    fun setStatusPending() {
        setDotColor(DOT_COLOR_PENDING)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_starting_cluster)
        }
    }

    fun setStatusActivating() {
        setDotColor(DOT_COLOR_PENDING)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_activating_cluster)
        }
    }

    fun setStatusRestoring() {
        setDotColor(DOT_COLOR_PENDING)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_restoring_cluster)
        }
    }

    fun setStatusRestoringOrigin() {
        setDotColor(DOT_COLOR_PENDING)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_restoring_origin)
        }
    }

    fun setStatusDisconnected() {
        setDotColor(DOT_COLOR_OFF)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_disconnected)
        }
    }

    fun setStatusDashboardByd() {
        setDotColor(DOT_COLOR_ACTIVE)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            mTvDashboardStatus.setText(R.string.status_dashboard_byd)
        }
    }

    fun setStatusActive(appName: String?) {
        setDotColor(DOT_COLOR_ACTIVE)
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(Color.WHITE)
            if (appName != null)
                mTvDashboardStatus.text =
                    mHost.getContext().getString(R.string.status_dashboard_app, appName)
        }
    }

    fun setStatusText(text: String?) {
        if (mTvDashboardStatus != null) mTvDashboardStatus.text = text
    }

    private fun setDotColor(color: Int) {
        mStatusDotDrawable.setColor(color)
    }

    // ── Hotspot nav entry (DL3 + opt-in pref only) ────────────────────────────

    /** Shows or hides the hotspot nav entry based on platform and user preference. Safe to call repeatedly. */
    fun refreshHotspot() {
        if (mNavHotspot == null) return
        val ctx = mHost.getContext()
        val isDl3 = Platform.get().isDiLink3(ctx)
        val useOwnSim = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.PREF_USE_OWN_SIM, SettingsActivity.DEFAULT_USE_OWN_SIM)
        if (isDl3 && useOwnSim) {
            mNavHotspot.visibility = View.VISIBLE
            mNavHotspot.setOnClickListener {
                mHost.startActivity(Intent(ctx, HotspotActivity::class.java))
            }
        } else {
            mNavHotspot.visibility = View.GONE
            mNavHotspot.setOnClickListener(null)
        }
    }

    companion object {
        private const val TAG = "NavigationCoordinator"

        private const val DOT_COLOR_OFF     = 0xFF888888.toInt()
        private const val DOT_COLOR_PENDING = 0xFFFFC107.toInt()
        private const val DOT_COLOR_ACTIVE  = 0xFF4CAF50.toInt()
    }
}
