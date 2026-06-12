package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.byd.dashcast.R;
import com.byd.dashcast.fission.LayoutPrefs;
import com.byd.dashcast.fission.LayoutPreset;
import com.byd.dashcast.proxy.DaemonConfig;

import java.util.List;

/**
 * Owns the Layouts-mode UI in the main activity: the layout-row visibility and the
 * layout-switcher dialog. (The "open Fission" button was removed in 1.4.24 — layout
 * activation is driven by the auto-layout setting and the Layouts screen.)
 *
 * Call {@link #refresh()} from onCreate and onResume to recompute visibility.
 */
public final class FissionCoordinator {

    private static final String TAG = "FissionCoordinator";

    public interface Host {
        Context getContext();
        void startActivity(Intent intent);
    }

    private final View     mLlFissionLayoutRow;
    private final TextView mTvMainFissionLayout;
    private final Host     mHost;

    public FissionCoordinator(View llFissionLayoutRow,
                               TextView tvMainFissionLayout, View btnMainSwitchLayout,
                               Host host) {
        mLlFissionLayoutRow  = llFissionLayoutRow;
        mTvMainFissionLayout = tvMainFissionLayout;
        mHost                = host;

        if (btnMainSwitchLayout != null)
            btnMainSwitchLayout.setOnClickListener(v -> showMainLayoutSwitcher());

        refresh();
    }

    /** Recomputes layout-row visibility and label. Safe to call repeatedly. */
    public void refresh() {
        boolean enabled = DaemonConfig.isFissionModeEnabled(mHost.getContext());
        if (mLlFissionLayoutRow != null)
            mLlFissionLayoutRow.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) refreshFissionLayoutLabel();
    }

    private void refreshFissionLayoutLabel() {
        if (mTvMainFissionLayout == null) return;
        Context ctx = mHost.getContext();
        LayoutPreset fav = LayoutPrefs.getFavoriteLayout(ctx);
        if (fav == null) {
            mTvMainFissionLayout.setText(ctx.getString(R.string.fission_layout_mode_free));
        } else {
            mTvMainFissionLayout.setText(
                    ctx.getString(R.string.fission_layout_active_fmt, fav.name));
        }
    }

    private void showMainLayoutSwitcher() {
        Context ctx = mHost.getContext();
        List<LayoutPreset> presets = LayoutPrefs.load(ctx);
        String favId = LayoutPrefs.getFavoriteId(ctx);
        String[] names = new String[presets.size() + 1];
        names[0] = ctx.getString(R.string.fission_layout_mode_free);
        for (int i = 0; i < presets.size(); i++) {
            LayoutPreset p = presets.get(i);
            names[i + 1] = (p.id.equals(favId) ? "⭐ " : "") + p.name
                    + "  (" + p.slots.size() + " zones)";
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.fission_layout_switch))
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        LayoutPrefs.setFavoriteId(ctx, null);
                    } else {
                        LayoutPrefs.setFavoriteId(ctx, presets.get(which - 1).id);
                    }
                    refreshFissionLayoutLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
