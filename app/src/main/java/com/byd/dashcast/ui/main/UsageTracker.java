package com.byd.dashcast.ui.main;

import android.content.Context;
import android.content.SharedPreferences;

import com.byd.dashcast.R;
import com.byd.dashcast.data.prefs.ClusterPrefs;

/**
 * Tracks how long each cluster app is displayed and provides a summary dialog.
 * State ({@code mStartTime}) and persistence ({@code usage_ms_<pkg>} in ClusterPrefs)
 * are fully owned here; MainActivity just calls {@link #trackStart()}, {@link #trackStop},
 * and {@link #showStatsDialog()}.
 */
public final class UsageTracker {

    public interface Host {
        Context getContext();
    }

    private final Host mHost;
    private long mStartTime = 0;

    public UsageTracker(Host host) {
        mHost = host;
    }

    public void trackStart() {
        mStartTime = System.currentTimeMillis();
    }

    public void trackStop(String pkgName) {
        if (mStartTime <= 0 || pkgName == null) return;
        long elapsed = System.currentTimeMillis() - mStartTime;
        mStartTime = 0;
        if (elapsed < 1000) return;
        SharedPreferences prefs = mHost.getContext()
                .getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);
        long prev = prefs.getLong("usage_ms_" + pkgName, 0);
        prefs.edit().putLong("usage_ms_" + pkgName, prev + elapsed).apply();
    }

    public void showStatsDialog() {
        Context ctx = mHost.getContext();
        SharedPreferences prefs = ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE);
        java.util.Map<String, ?> all = prefs.getAll();
        java.util.List<String[]> stats = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith("usage_ms_") && entry.getValue() instanceof Long) {
                String pkg = entry.getKey().substring("usage_ms_".length());
                long ms = (Long) entry.getValue();
                String name = pkg;
                try {
                    android.content.pm.ApplicationInfo ai =
                            ctx.getPackageManager().getApplicationInfo(pkg, 0);
                    CharSequence label = ctx.getPackageManager().getApplicationLabel(ai);
                    if (label != null) name = label.toString();
                } catch (Exception ignored) {}
                stats.add(new String[]{name, formatDuration(ms)});
            }
        }
        if (stats.isEmpty()) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.usage_empty),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Collections.sort(stats, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        StringBuilder sb = new StringBuilder();
        for (String[] s : stats) sb.append(s[0]).append(" — ").append(s[1]).append("\n");

        new android.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.usage_title))
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(ctx.getString(R.string.usage_reset), (d, w) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    for (String key : prefs.getAll().keySet()) {
                        if (key.startsWith("usage_ms_")) editor.remove(key);
                    }
                    editor.apply();
                    android.widget.Toast.makeText(ctx,
                            ctx.getString(R.string.toast_usage_stats_reset),
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours   = minutes / 60;
        if (hours   > 0) return hours   + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
