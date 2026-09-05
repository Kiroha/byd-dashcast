package com.byd.dashcast.ui.main

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.byd.dashcast.util.AppLogger
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * State machine for entering and exiting pseudo-fullscreen mirror mode.
 *
 * Mirrors the exact behavior of MainActivity.enterFullscreenMirror() /
 * exitFullscreenMirror() including: card weight expansion, control panel
 * reparenting, immersive flags, and mirror stop/restart delegation.
 *
 * NOTE: vTopBar and cardHeroStatus are permanently hidden in the layout
 * (v1.2.83) and are NOT restored on exit — this matches the original.
 */
class FullscreenMirrorCoordinator(
    private val mBtnExitFullscreen: FloatingActionButton?,
    private val mVNavRail: View?,
    private val mVTopBar: View?,        // hidden permanently; only GONE on enter
    private val mCardHeroStatus: View?, // same
    private val mLlAppListSection: View?,
    private val mTvPreviewSection: View?,
    private val mGridMainActions: View?,
    private val mCardClusterPreview: View?,
    private val mLlRightPaneContent: View?,
    private val mSvRightPane: View?,
    private val mControlPanel: ViewGroup?,
    private val mRootOverlay: FrameLayout?,
    private val mHost: Host
) {

    interface Host {
        /** Stop the cluster mirror before the card resizes. */
        fun onMirrorShouldStop()

        /** Trigger a postDelayed(250ms) surface recreation + mirror restart. */
        fun onMirrorRestartAfterDelay()

        /** Set or clear immersive system-UI flags. */
        fun setFullscreenImmersive(on: Boolean)
    }

    private var mIsFullscreen = false
    private var mPanelOriginalParent: ViewGroup? = null
    private var mPanelOriginalIndex = -1
    private var mPanelOriginalLp: ViewGroup.LayoutParams? = null
    private var mSavedPreviewHeightPx = -1
    private var mSavedPreviewWeight = 0f
    private var mSavedInnerLLHeight = ViewGroup.LayoutParams.WRAP_CONTENT

    init {
        mBtnExitFullscreen?.setOnClickListener { exit() }
    }

    fun isFullscreen(): Boolean = mIsFullscreen

    fun enter() {
        if (mIsFullscreen) return
        if (mCardClusterPreview == null) return
        mIsFullscreen = true
        AppLogger.i(TAG, "Entering fullscreen mirror")

        // Stop mirror BEFORE resizing (avoids stale-surface black preview).
        mHost.onMirrorShouldStop()

        setVisibility(mVNavRail, View.GONE)
        setVisibility(mVTopBar, View.GONE)
        setVisibility(mLlAppListSection, View.GONE)
        setVisibility(mCardHeroStatus, View.GONE)
        setVisibility(mTvPreviewSection, View.GONE)
        setVisibility(mGridMainActions, View.GONE)

        // Expand inner right-pane LL to fill svRightPane height.
        if (mLlRightPaneContent != null && mSvRightPane != null) {
            val llLp = mLlRightPaneContent.layoutParams
            mSavedInnerLLHeight = llLp.height
            var h = mSvRightPane.height
            if (h <= 0) h = mSvRightPane.resources.displayMetrics.heightPixels
            llLp.height = h
            mLlRightPaneContent.layoutParams = llLp
        }

        // Expand preview card via weight (height=0 + weight=1 in LinearLayout).
        val clp = mCardClusterPreview.layoutParams as LinearLayout.LayoutParams
        mSavedPreviewHeightPx = clp.height
        mSavedPreviewWeight = clp.weight
        clp.height = 0
        clp.weight = 1f
        mCardClusterPreview.layoutParams = clp

        if (mBtnExitFullscreen != null) mBtnExitFullscreen.visibility = View.VISIBLE

        // Reveal the cluster control panel (v1.2.85: fullscreen-only).
        if (mControlPanel != null) mControlPanel.visibility = View.VISIBLE

        // Reparent control panel into root overlay so it floats over the preview.
        if (mControlPanel != null && mRootOverlay != null
            && mControlPanel.parent is ViewGroup
            && mControlPanel.parent !== mRootOverlay
        ) {
            val panelParent = mControlPanel.parent as ViewGroup
            mPanelOriginalParent = panelParent
            mPanelOriginalIndex = panelParent.indexOfChild(mControlPanel)
            mPanelOriginalLp = mControlPanel.layoutParams
            panelParent.removeView(mControlPanel)
            val flp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            mRootOverlay.addView(mControlPanel, flp)
        }

        mHost.setFullscreenImmersive(true)
        mCardClusterPreview.postDelayed({ mHost.onMirrorRestartAfterDelay() }, 250)
    }

    fun exit() {
        if (!mIsFullscreen) return
        mIsFullscreen = false
        AppLogger.i(TAG, "Exiting fullscreen mirror")

        // Stop mirror BEFORE resize.
        mHost.onMirrorShouldStop()

        setVisibility(mVNavRail, View.VISIBLE)
        // vTopBar and cardHeroStatus are permanently hidden (v1.2.83) — do NOT restore.
        setVisibility(mLlAppListSection, View.VISIBLE)
        setVisibility(mTvPreviewSection, View.VISIBLE)
        setVisibility(mGridMainActions, View.VISIBLE)

        // Restore inner LL height.
        if (mLlRightPaneContent != null) {
            val llLp = mLlRightPaneContent.layoutParams
            llLp.height = mSavedInnerLLHeight
            mLlRightPaneContent.layoutParams = llLp
        }

        // Restore preview card to saved dimensions.
        if (mCardClusterPreview != null) {
            val clp = mCardClusterPreview.layoutParams as LinearLayout.LayoutParams
            val density = mCardClusterPreview.resources.displayMetrics.density
            clp.height = if (mSavedPreviewHeightPx > 0) mSavedPreviewHeightPx
            else (320 * density).toInt()
            clp.weight = mSavedPreviewWeight
            mCardClusterPreview.layoutParams = clp
        }

        if (mBtnExitFullscreen != null) mBtnExitFullscreen.visibility = View.GONE

        // Restore control panel to its original parent.
        val panelParent = mPanelOriginalParent
        if (mControlPanel != null && panelParent != null
            && mControlPanel.parent === mRootOverlay
        ) {
            mRootOverlay.removeView(mControlPanel)
            val idx = if (mPanelOriginalIndex >= 0
                && mPanelOriginalIndex <= panelParent.childCount
            ) mPanelOriginalIndex else panelParent.childCount
            val panelLp = mPanelOriginalLp
            if (panelLp != null) {
                panelParent.addView(mControlPanel, idx, panelLp)
            } else {
                panelParent.addView(mControlPanel, idx)
            }
            mPanelOriginalParent = null
            mPanelOriginalIndex = -1
            mPanelOriginalLp = null
        }

        // Hide cluster control panel on exit (fullscreen-only, v1.2.85).
        if (mControlPanel != null) mControlPanel.visibility = View.GONE

        mHost.setFullscreenImmersive(false)
        if (mCardClusterPreview != null) {
            mCardClusterPreview.postDelayed({ mHost.onMirrorRestartAfterDelay() }, 250)
        }
    }

    companion object {
        private const val TAG = "FullscreenMirrorCoordinator"

        private fun setVisibility(v: View?, visibility: Int) {
            if (v != null) v.visibility = visibility
        }
    }
}
