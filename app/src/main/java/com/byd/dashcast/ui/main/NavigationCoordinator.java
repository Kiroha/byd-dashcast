package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.HotspotActivity;
import com.byd.dashcast.R;
import com.byd.dashcast.SettingsActivity;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.platform.Platform;

/**
 * Owns the navigation rail status dot/text and wires the long-press overflow
 * menu trigger. The actual menu content is delegated to the Host so MainActivity
 * keeps ownership of all 9 programmatic menu items.
 */
public final class NavigationCoordinator {

    private static final String TAG = "NavigationCoordinator";

    private static final int DOT_COLOR_OFF     = 0xFF888888;
    private static final int DOT_COLOR_PENDING = 0xFFFFC107;
    private static final int DOT_COLOR_ACTIVE  = 0xFF4CAF50;

    public interface Host {
        Context getContext();
        void onShowOverflowMenu(View anchor);
        void startActivity(Intent intent);
    }

    private final View                                              mStatusDot;
    private final android.graphics.drawable.GradientDrawable       mStatusDotDrawable;
    private final TextView                                          mTvDashboardStatus;
    private final ImageView                                         mIvNavLogo;
    private final View                                              mNavHotspot;
    private final Host                                              mHost;

    public NavigationCoordinator(View statusDot, TextView tvDashboardStatus,
                                  ImageView ivNavLogo, View navHotspot, Host host) {
        mStatusDot         = statusDot;
        mTvDashboardStatus = tvDashboardStatus;
        mIvNavLogo         = ivNavLogo;
        mNavHotspot        = navHotspot;
        mHost              = host;

        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (mStatusDot != null) mStatusDot.setBackground(drawable);
        mStatusDotDrawable = drawable;
        setupOverflowMenu();
        refreshHotspot();
    }

    private void setupOverflowMenu() {
        if (mIvNavLogo == null) return;
        mIvNavLogo.setOnLongClickListener(v -> {
            mHost.onShowOverflowMenu(v);
            return true;
        });
    }

    // ── Status dot ────────────────────────────────────────────────────────────

    public void setStatusOff() {
        setDotColor(DOT_COLOR_OFF);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.main_cluster_status_off);
        }
    }

    public void setStatusPending() {
        setDotColor(DOT_COLOR_PENDING);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_starting_cluster);
        }
    }

    public void setStatusActivating() {
        setDotColor(DOT_COLOR_PENDING);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_activating_cluster);
        }
    }

    public void setStatusRestoring() {
        setDotColor(DOT_COLOR_PENDING);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_restoring_cluster);
        }
    }

    public void setStatusRestoringOrigin() {
        setDotColor(DOT_COLOR_PENDING);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_restoring_origin);
        }
    }

    public void setStatusDisconnected() {
        setDotColor(DOT_COLOR_OFF);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_disconnected);
        }
    }

    public void setStatusDashboardByd() {
        setDotColor(DOT_COLOR_ACTIVE);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            mTvDashboardStatus.setText(R.string.status_dashboard_byd);
        }
    }

    public void setStatusActive(String appName) {
        setDotColor(DOT_COLOR_ACTIVE);
        if (mTvDashboardStatus != null) {
            mTvDashboardStatus.setTextColor(android.graphics.Color.WHITE);
            if (appName != null)
                mTvDashboardStatus.setText(
                        mHost.getContext().getString(R.string.status_dashboard_app, appName));
        }
    }

    public void setStatusText(String text) {
        if (mTvDashboardStatus != null) mTvDashboardStatus.setText(text);
    }

    private void setDotColor(int color) {
        if (mStatusDotDrawable != null) {
            mStatusDotDrawable.setColor(color);
        }
    }

    // ── Hotspot nav entry (DL3 + opt-in pref only) ────────────────────────────

    /** Shows or hides the hotspot nav entry based on platform and user preference. Safe to call repeatedly. */
    public void refreshHotspot() {
        if (mNavHotspot == null) return;
        Context ctx = mHost.getContext();
        boolean isDl3 = Platform.get().isDiLink3(ctx);
        boolean useOwnSim = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_USE_OWN_SIM, true);
        if (isDl3 && useOwnSim) {
            mNavHotspot.setVisibility(View.VISIBLE);
            mNavHotspot.setOnClickListener(v ->
                    mHost.startActivity(new Intent(ctx, HotspotActivity.class)));
        } else {
            mNavHotspot.setVisibility(View.GONE);
            mNavHotspot.setOnClickListener(null);
        }
    }
}
