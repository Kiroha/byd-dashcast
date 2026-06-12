package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.DiagActivity;
import com.byd.dashcast.LocaleHelper;
import com.byd.dashcast.LogActivity;
import com.byd.dashcast.OtaProgressUi;
import com.byd.dashcast.R;
import com.byd.dashcast.SettingsActivity;
import com.byd.dashcast.SysInfoActivity;
import com.byd.dashcast.UpdateChecker;
import com.byd.dashcast.WelcomeActivity;

/**
 * Builds and shows the main overflow PopupMenu. Stateless — call {@link #show(View, Host)}.
 */
public final class OverflowMenuHelper {

    private static final String TAG = "OverflowMenuHelper";

    private OverflowMenuHelper() {}

    public interface Host {
        Context getContext();
        void startActivity(Intent intent);
        boolean isAppListGridMode();
        void toggleAppListViewMode();
        void onOriginCluster();
        void showUsageStats();
    }

    public static void show(View anchor, final Host host) {
        Context ctx = host.getContext();
        PopupMenu popup = new PopupMenu(ctx, anchor);

        // Group 0: user actions
        popup.getMenu().add(0, 1, 0, ctx.getString(R.string.menu_settings));
        popup.getMenu().add(0, 5, 1, ctx.getString(R.string.menu_language));
        popup.getMenu().add(0, 6, 2, ctx.getString(R.string.menu_check_updates));
        popup.getMenu().add(0, 7, 3,
                host.isAppListGridMode()
                        ? ctx.getString(R.string.menu_view_list)
                        : ctx.getString(R.string.menu_view_grid));
        popup.getMenu().add(0, 8, 4, ctx.getString(R.string.btn_origin_cluster));
        popup.getMenu().add(0, 9, 5, ctx.getString(R.string.menu_usage_stats));
        // Group 1: dev tools (with divider)
        popup.getMenu().add(1, 2, 6, ctx.getString(R.string.menu_diagnostic));
        popup.getMenu().add(1, 3, 7, ctx.getString(R.string.menu_system_report));
        popup.getMenu().add(1, 4, 8, ctx.getString(R.string.menu_log));

        // Visual divider between groups (API 28+, safe on our API 29 target)
        try {
            popup.getMenu().getClass()
                    .getDeclaredMethod("setGroupDividerEnabled", boolean.class)
                    .invoke(popup.getMenu(), true);
        } catch (Exception ignored) {
            AppLogger.d(TAG, "setGroupDividerEnabled unavailable: " + ignored.getMessage());
        }

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        host.startActivity(new Intent(ctx, SettingsActivity.class));
                        return true;
                    case 7:
                        host.toggleAppListViewMode();
                        android.widget.Toast.makeText(ctx,
                                host.isAppListGridMode()
                                        ? ctx.getString(R.string.toast_grid_mode_enabled)
                                        : ctx.getString(R.string.toast_list_mode_enabled),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    case 8:
                        host.onOriginCluster();
                        return true;
                    case 2:
                        host.startActivity(new Intent(ctx, DiagActivity.class));
                        return true;
                    case 3:
                        host.startActivity(new Intent(ctx, SysInfoActivity.class));
                        return true;
                    case 4:
                        host.startActivity(new Intent(ctx, LogActivity.class));
                        return true;
                    case 5:
                        SharedPreferences p = ctx.getSharedPreferences(
                                LocaleHelper.PREF_FILE, Context.MODE_PRIVATE);
                        p.edit().remove(LocaleHelper.PREF_SETUP_DONE).apply();
                        Intent welcome = new Intent(ctx, WelcomeActivity.class);
                        welcome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        host.startActivity(welcome);
                        return true;
                    case 6:
                        UpdateChecker.checkUpdate(ctx,
                                OtaProgressUi.makeListener(
                                        (android.app.Activity) ctx, true));
                        return true;
                    case 9:
                        host.showUsageStats();
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }
}
