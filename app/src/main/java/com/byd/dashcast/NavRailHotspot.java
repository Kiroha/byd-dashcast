package com.byd.dashcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;

/**
 * Shared wiring for the « Hotspot » navrail entry that appears on every
 * secondary screen (Main, Diag, Sysinfo, Log, Settings) from v1.2.44 onwards.
 *
 * <p>Runtime gating mirrors the historical logic in {@link MainActivity#refreshNavHotspot()}:
 * the entry is only shown when the device is a DiLink 3 head-unit AND the user has
 * not disabled the "I use my own SIM" toggle in {@link SettingsActivity} (default = true,
 * since v1.2.38 — see the same method's comment for the rationale).
 */
public final class NavRailHotspot {
    private NavRailHotspot() {}

    /**
     * Show + wire the navrail Hotspot button on a screen that has one.
     *
     * @param host         the calling Activity
     * @param viewId       the id of the navrail hotspot entry (e.g. {@code R.id.nav_hotspot_diag})
     * @param finishOnNav  whether to {@link Activity#finish()} the host after navigating
     */
    public static void apply(Activity host, int viewId, boolean finishOnNav) {
        if (host == null) return;
        View v = host.findViewById(viewId);
        if (v == null) return;
        boolean isDl3 = false;
        try {
            isDl3 = com.byd.dashcast.platform.Platform.get().isDiLink3(host);
        } catch (Throwable ignore) { /* Platform may not be initialised on some test paths. */ }
        boolean useOwnSim = host.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_USE_OWN_SIM, true);
        if (isDl3 && useOwnSim) {
            v.setVisibility(View.VISIBLE);
            final boolean finish = finishOnNav;
            v.setOnClickListener(view -> {
                host.startActivity(new Intent(host, HotspotActivity.class));
                if (finish) host.finish();
            });
        } else {
            v.setVisibility(View.GONE);
            v.setOnClickListener(null);
        }
    }
}
