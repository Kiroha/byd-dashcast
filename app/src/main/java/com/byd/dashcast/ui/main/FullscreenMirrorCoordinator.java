package com.byd.dashcast.ui.main;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.byd.dashcast.util.AppLogger;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

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
public final class FullscreenMirrorCoordinator {

    private static final String TAG = "FullscreenMirrorCoordinator";

    public interface Host {
        /** Stop the cluster mirror before the card resizes. */
        void onMirrorShouldStop();
        /** Trigger a postDelayed(250ms) surface recreation + mirror restart. */
        void onMirrorRestartAfterDelay();
        /** Set or clear immersive system-UI flags. */
        void setFullscreenImmersive(boolean on);
    }

    private final FloatingActionButton mBtnExitFullscreen;
    private final View                 mVNavRail;
    private final View                 mVTopBar;         // hidden permanently; only GONE on enter
    private final View                 mCardHeroStatus;  // same
    private final View                 mLlAppListSection;
    private final View                 mTvPreviewSection;
    private final View                 mGridMainActions;
    private final View                 mCardClusterPreview;
    private final View                 mLlRightPaneContent;
    private final View                 mSvRightPane;
    private final ViewGroup            mControlPanel;
    private final FrameLayout          mRootOverlay;
    private final Host                 mHost;

    private boolean                mIsFullscreen         = false;
    private ViewGroup              mPanelOriginalParent  = null;
    private int                    mPanelOriginalIndex   = -1;
    private ViewGroup.LayoutParams mPanelOriginalLp      = null;
    private int                    mSavedPreviewHeightPx = -1;
    private float                  mSavedPreviewWeight   = 0f;
    private int                    mSavedInnerLLHeight   = ViewGroup.LayoutParams.WRAP_CONTENT;

    public FullscreenMirrorCoordinator(FloatingActionButton btnExitFullscreen,
                                        View vNavRail, View vTopBar,
                                        View cardHeroStatus, View llAppListSection,
                                        View tvPreviewSection, View gridMainActions,
                                        View cardClusterPreview, View llRightPaneContent,
                                        View svRightPane,
                                        ViewGroup controlPanel, FrameLayout rootOverlay,
                                        Host host) {
        mBtnExitFullscreen  = btnExitFullscreen;
        mVNavRail           = vNavRail;
        mVTopBar            = vTopBar;
        mCardHeroStatus     = cardHeroStatus;
        mLlAppListSection   = llAppListSection;
        mTvPreviewSection   = tvPreviewSection;
        mGridMainActions    = gridMainActions;
        mCardClusterPreview = cardClusterPreview;
        mLlRightPaneContent = llRightPaneContent;
        mSvRightPane        = svRightPane;
        mControlPanel       = controlPanel;
        mRootOverlay        = rootOverlay;
        mHost               = host;

        if (mBtnExitFullscreen != null) {
            mBtnExitFullscreen.setOnClickListener(v -> exit());
        }
    }

    public boolean isFullscreen() { return mIsFullscreen; }

    public void enter() {
        if (mIsFullscreen) return;
        if (mCardClusterPreview == null) return;
        mIsFullscreen = true;
        AppLogger.i(TAG, "Entering fullscreen mirror");

        // Stop mirror BEFORE resizing (avoids stale-surface black preview).
        mHost.onMirrorShouldStop();

        setVisibility(mVNavRail,          View.GONE);
        setVisibility(mVTopBar,           View.GONE);
        setVisibility(mLlAppListSection,  View.GONE);
        setVisibility(mCardHeroStatus,    View.GONE);
        setVisibility(mTvPreviewSection,  View.GONE);
        setVisibility(mGridMainActions,   View.GONE);

        // Expand inner right-pane LL to fill svRightPane height.
        if (mLlRightPaneContent != null && mSvRightPane != null) {
            ViewGroup.LayoutParams llLp = mLlRightPaneContent.getLayoutParams();
            mSavedInnerLLHeight = llLp.height;
            int h = mSvRightPane.getHeight();
            if (h <= 0) h = mSvRightPane.getResources().getDisplayMetrics().heightPixels;
            llLp.height = h;
            mLlRightPaneContent.setLayoutParams(llLp);
        }

        // Expand preview card via weight (height=0 + weight=1 in LinearLayout).
        LinearLayout.LayoutParams clp =
                (LinearLayout.LayoutParams) mCardClusterPreview.getLayoutParams();
        mSavedPreviewHeightPx = clp.height;
        mSavedPreviewWeight   = clp.weight;
        clp.height = 0;
        clp.weight = 1f;
        mCardClusterPreview.setLayoutParams(clp);

        if (mBtnExitFullscreen != null) mBtnExitFullscreen.setVisibility(View.VISIBLE);

        // Reveal the cluster control panel (v1.2.85: fullscreen-only).
        if (mControlPanel != null) mControlPanel.setVisibility(View.VISIBLE);

        // Reparent control panel into root overlay so it floats over the preview.
        if (mControlPanel != null && mRootOverlay != null
                && mControlPanel.getParent() instanceof ViewGroup
                && mControlPanel.getParent() != mRootOverlay) {
            mPanelOriginalParent = (ViewGroup) mControlPanel.getParent();
            mPanelOriginalIndex  = mPanelOriginalParent.indexOfChild(mControlPanel);
            mPanelOriginalLp     = mControlPanel.getLayoutParams();
            mPanelOriginalParent.removeView(mControlPanel);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM);
            mRootOverlay.addView(mControlPanel, flp);
        }

        mHost.setFullscreenImmersive(true);
        mCardClusterPreview.postDelayed(() -> mHost.onMirrorRestartAfterDelay(), 250);
    }

    public void exit() {
        if (!mIsFullscreen) return;
        mIsFullscreen = false;
        AppLogger.i(TAG, "Exiting fullscreen mirror");

        // Stop mirror BEFORE resize.
        mHost.onMirrorShouldStop();

        setVisibility(mVNavRail,         View.VISIBLE);
        // vTopBar and cardHeroStatus are permanently hidden (v1.2.83) — do NOT restore.
        setVisibility(mLlAppListSection, View.VISIBLE);
        setVisibility(mTvPreviewSection, View.VISIBLE);
        setVisibility(mGridMainActions,  View.VISIBLE);

        // Restore inner LL height.
        if (mLlRightPaneContent != null) {
            ViewGroup.LayoutParams llLp = mLlRightPaneContent.getLayoutParams();
            llLp.height = mSavedInnerLLHeight;
            mLlRightPaneContent.setLayoutParams(llLp);
        }

        // Restore preview card to saved dimensions.
        if (mCardClusterPreview != null) {
            LinearLayout.LayoutParams clp =
                    (LinearLayout.LayoutParams) mCardClusterPreview.getLayoutParams();
            float density = mCardClusterPreview.getResources().getDisplayMetrics().density;
            clp.height = (mSavedPreviewHeightPx > 0) ? mSavedPreviewHeightPx
                    : (int) (320 * density);
            clp.weight = mSavedPreviewWeight;
            mCardClusterPreview.setLayoutParams(clp);
        }

        if (mBtnExitFullscreen != null) mBtnExitFullscreen.setVisibility(View.GONE);

        // Restore control panel to its original parent.
        if (mControlPanel != null && mPanelOriginalParent != null
                && mControlPanel.getParent() == mRootOverlay) {
            mRootOverlay.removeView(mControlPanel);
            int idx = (mPanelOriginalIndex >= 0
                    && mPanelOriginalIndex <= mPanelOriginalParent.getChildCount())
                    ? mPanelOriginalIndex : mPanelOriginalParent.getChildCount();
            if (mPanelOriginalLp != null) {
                mPanelOriginalParent.addView(mControlPanel, idx, mPanelOriginalLp);
            } else {
                mPanelOriginalParent.addView(mControlPanel, idx);
            }
            mPanelOriginalParent = null;
            mPanelOriginalIndex  = -1;
            mPanelOriginalLp     = null;
        }

        // Hide cluster control panel on exit (fullscreen-only, v1.2.85).
        if (mControlPanel != null) mControlPanel.setVisibility(View.GONE);

        mHost.setFullscreenImmersive(false);
        if (mCardClusterPreview != null) {
            mCardClusterPreview.postDelayed(() -> mHost.onMirrorRestartAfterDelay(), 250);
        }
    }

    private static void setVisibility(View v, int visibility) {
        if (v != null) v.setVisibility(visibility);
    }
}
