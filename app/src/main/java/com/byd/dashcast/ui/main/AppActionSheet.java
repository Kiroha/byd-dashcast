package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.cluster.ClusterService;
import com.byd.dashcast.R;
import com.byd.dashcast.cluster.dpi.ClusterDpiPrefs;
import com.byd.dashcast.cluster.dpi.ClusterResizeActivity;
import com.byd.dashcast.model.AppInfo;

/**
 * Shows the per-app bottom sheet and DPI override dialogs.
 * Stateless — call {@link #show(AppInfo, Host)} from the host activity.
 */
public final class AppActionSheet {

    private static final String TAG = "AppActionSheet";

    private AppActionSheet() {}

    public interface Host {
        Context getContext();
        boolean isActivityAlive();
        void startActivity(Intent intent);
        ClusterService getClusterServiceIfBound();
        /** Package currently projected on the cluster. */
        String getCurrentClusterPkg();
        /** Package last sent to the main display. */
        String getMainDisplayPkg();
        void onSendToDashboard(AppInfo app);
        void onSendToMain(AppInfo app);
        void onKillApp(AppInfo app);
        void onToggleFavorite(AppInfo app);
        void onSetAutoLaunch(AppInfo app, boolean enable);
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public static void show(final AppInfo app, final Host host) {
        if (app == null || !host.isActivityAlive()) return;

        Context ctx = host.getContext();
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_actions, null);
        dialog.setContentView(v);

        ImageView icon     = v.findViewById(R.id.sheet_icon);
        TextView  name     = v.findViewById(R.id.sheet_name);
        TextView  pkg      = v.findViewById(R.id.sheet_pkg);
        com.google.android.material.materialswitch.MaterialSwitch swAuto =
                v.findViewById(R.id.sheet_sw_auto);
        View      rowFav   = v.findViewById(R.id.sheet_action_favorite);
        TextView  lblFav   = v.findViewById(R.id.sheet_lbl_favorite);
        View      rowToMain  = v.findViewById(R.id.sheet_action_to_main);
        View      rowToClus  = v.findViewById(R.id.sheet_action_to_cluster);
        View      rowResize  = v.findViewById(R.id.sheet_action_resize);
        View      rowDpi     = v.findViewById(R.id.sheet_action_dpi);
        TextView  tvDpiVal   = v.findViewById(R.id.sheet_dpi_value);
        View      rowKill    = v.findViewById(R.id.sheet_action_kill);

        icon.setImageDrawable(app.icon);
        name.setText(app.appName);
        pkg.setText(app.packageName);
        lblFav.setText(app.isFavorite
                ? R.string.sheet_remove_favorite
                : R.string.sheet_add_favorite);

        swAuto.setChecked(app.isAutoLaunch);
        swAuto.setOnCheckedChangeListener((b, checked) -> host.onSetAutoLaunch(app, checked));

        rowFav.setOnClickListener(vv -> {
            host.onToggleFavorite(app);
            dialog.dismiss();
        });

        final boolean isActive = app.packageName != null
                && app.packageName.equals(host.getCurrentClusterPkg());
        final boolean isOnMain = app.packageName != null
                && app.packageName.equals(host.getMainDisplayPkg());
        rowToMain.setVisibility(isActive ? View.VISIBLE : View.GONE);
        rowToClus.setVisibility(isOnMain || (!isActive && !isOnMain) ? View.VISIBLE : View.GONE);
        rowResize.setVisibility(isActive ? View.VISIBLE : View.GONE);
        rowKill.setVisibility((isActive || isOnMain) ? View.VISIBLE : View.GONE);

        rowToMain.setOnClickListener(vv -> { host.onSendToMain(app); dialog.dismiss(); });
        rowToClus.setOnClickListener(vv -> { host.onSendToDashboard(app); dialog.dismiss(); });

        rowResize.setOnClickListener(vv -> {
            ClusterService svc = host.getClusterServiceIfBound();
            int displayId = svc != null ? svc.getDisplayId() : 1;
            if (displayId < 0) displayId = 1;
            Intent it = new Intent(ctx, ClusterResizeActivity.class);
            it.putExtra(ClusterResizeActivity.EXTRA_PACKAGE, app.packageName);
            it.putExtra(ClusterResizeActivity.EXTRA_DISPLAY_ID, displayId);
            host.startActivity(it);
            dialog.dismiss();
        });

        final int curDpi = ClusterDpiPrefs.getDpi(ctx, app.packageName);
        if (curDpi > 0) {
            tvDpiVal.setText(ctx.getString(R.string.sheet_dpi_value_fmt, curDpi));
        } else {
            tvDpiVal.setText(R.string.sheet_dpi_value_default);
        }
        rowDpi.setOnClickListener(vv -> {
            dialog.dismiss();
            showDpiDialog(app, host);
        });

        rowKill.setOnClickListener(vv -> {
            host.onKillApp(app);
            dialog.dismiss();
        });

        dialog.show();
        // v0.9.73 — open fully expanded (skip the half-collapsed peek that hides actions).
        try {
            com.google.android.material.bottomsheet.BottomSheetBehavior<?> b = dialog.getBehavior();
            b.setSkipCollapsed(true);
            b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        } catch (Throwable t) {
            AppLogger.w(TAG, "BottomSheet expand failed: " + t.getMessage());
        }
    }

    // ── DPI dialogs ───────────────────────────────────────────────────────────

    // v1.2.81 — Cluster per-app DPI override picker.
    // Presets cover the common cluster-friendly densities; "Custom" accepts 96..480 dpi.
    // Applied at next launch on the cluster display via ClusterDpiManager.applyForLaunch.
    @android.annotation.SuppressLint("InflateParams")
    static void showDpiDialog(final AppInfo app, final Host host) {
        if (app == null || app.packageName == null || !host.isActivityAlive()) return;

        Context ctx = host.getContext();
        final int[] presets = { 120, 160, 200, 240, 280, 320 };
        final int current = ClusterDpiPrefs.getDpi(ctx, app.packageName);

        final String[] labels = new String[presets.length + 2];
        labels[0] = ctx.getString(R.string.dpi_default);
        for (int i = 0; i < presets.length; i++) {
            labels[i + 1] = ctx.getString(R.string.dpi_value_fmt, presets[i]);
        }
        labels[labels.length - 1] = ctx.getString(R.string.dpi_custom);

        int checked = 0;
        if (current > 0) {
            checked = labels.length - 1;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == current) { checked = i + 1; break; }
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.dpi_dialog_title, app.appName))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which == 0) {
                        ClusterDpiPrefs.setDpi(ctx, app.packageName, 0);
                        d.dismiss();
                        android.widget.Toast.makeText(ctx,
                                R.string.dpi_applied_next_launch,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else if (which == labels.length - 1) {
                        d.dismiss();
                        showDpiCustomDialog(app, current, host);
                    } else {
                        ClusterDpiPrefs.setDpi(ctx, app.packageName, presets[which - 1]);
                        d.dismiss();
                        android.widget.Toast.makeText(ctx,
                                R.string.dpi_applied_next_launch,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @android.annotation.SuppressLint("InflateParams")
    private static void showDpiCustomDialog(final AppInfo app, final int prefill,
                                            final Host host) {
        Context ctx = host.getContext();
        final android.widget.EditText input = new android.widget.EditText(ctx);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(ctx.getString(R.string.dpi_custom_hint,
                ClusterDpiPrefs.MIN_DPI, ClusterDpiPrefs.MAX_DPI));
        if (prefill > 0) input.setText(String.valueOf(prefill));

        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(ctx);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.dpi_custom)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int dpi;
                    try { dpi = Integer.parseInt(input.getText().toString().trim()); }
                    catch (NumberFormatException e) { dpi = 0; }
                    if (dpi < ClusterDpiPrefs.MIN_DPI || dpi > ClusterDpiPrefs.MAX_DPI) {
                        android.widget.Toast.makeText(ctx,
                                ctx.getString(R.string.dpi_custom_hint,
                                        ClusterDpiPrefs.MIN_DPI, ClusterDpiPrefs.MAX_DPI),
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ClusterDpiPrefs.setDpi(ctx, app.packageName, dpi);
                    android.widget.Toast.makeText(ctx,
                            R.string.dpi_applied_next_launch,
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
