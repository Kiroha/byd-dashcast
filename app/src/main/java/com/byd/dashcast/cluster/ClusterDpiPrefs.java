package com.byd.dashcast.cluster;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
 * ClusterDpiPrefs — persistent per-package DPI override store for cluster launches.
 *
 * <p>Storage: a dedicated SharedPreferences file {@code cluster_dpi_per_app}.
 * Each entry maps a package name to a positive integer in [96, 480].
 * Absence (or any non-positive stored value) means "system default" → the
 * driver must call {@code wm density reset -d <displayId>} when restoring.
 *
 * <p>NEVER persists anything tied to display 0 (head unit); this class only
 * stores per-package preferences. The displayId guard lives in
 * {@link ClusterDpiManager}.
 *
 * @since v1.2.81-beta
 */
public final class ClusterDpiPrefs {

    private static final String PREFS = "cluster_dpi_per_app";

    /** Lower / upper bounds for an override entry (matches `wm density` accepted range). */
    public static final int MIN_DPI = 96;
    public static final int MAX_DPI = 480;

    private ClusterDpiPrefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * @return the stored override for {@code pkg}, or {@code 0} when no override
     *         is set (caller must treat 0 as "system default" / wm density reset).
     */
    public static int getDpi(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return 0;
        int v = sp(ctx).getInt(pkg, 0);
        if (v < MIN_DPI || v > MAX_DPI) return 0;
        return v;
    }

    /** Stores or clears the override for {@code pkg}. Pass 0 to remove. */
    public static void setDpi(Context ctx, String pkg, int dpi) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return;
        SharedPreferences.Editor ed = sp(ctx).edit();
        if (dpi <= 0) {
            ed.remove(pkg);
        } else {
            int clamped = Math.max(MIN_DPI, Math.min(MAX_DPI, dpi));
            ed.putInt(pkg, clamped);
        }
        ed.apply();
    }

    /** @return a sorted snapshot of all overrides (pkg → dpi). Never null. */
    public static Map<String, Integer> snapshot(Context ctx) {
        TreeMap<String, Integer> out = new TreeMap<>();
        if (ctx == null) return out;
        Map<String, ?> all = sp(ctx).getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) {
                int iv = (Integer) v;
                if (iv >= MIN_DPI && iv <= MAX_DPI) {
                    out.put(e.getKey(), iv);
                }
            }
        }
        return out;
    }
}
