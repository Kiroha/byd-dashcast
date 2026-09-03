package com.byd.dashcast.ui.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View

import com.byd.dashcast.platform.Platform
import com.byd.dashcast.ui.hotspot.HotspotActivity
import com.byd.dashcast.ui.settings.SettingsActivity

/**
 * Shared wiring for the « Hotspot » navrail entry that appears on every
 * secondary screen (Main, Diag, Sysinfo, Log, Settings) from v1.2.44 onwards.
 *
 * Runtime gating mirrors the historical logic in `MainActivity.refreshNavHotspot()`:
 * the entry is only shown when the device is a DiLink 3 head-unit AND the user has
 * not disabled the "I use my own SIM" toggle in [SettingsActivity] (default = true,
 * since v1.2.38 — see the same method's comment for the rationale).
 */
object NavRailHotspot {

    /**
     * Show + wire the navrail Hotspot button on a screen that has one.
     *
     * @param host        the calling Activity
     * @param viewId      the id of the navrail hotspot entry (e.g. `R.id.nav_hotspot_diag`)
     * @param finishOnNav whether to [Activity.finish] the host after navigating
     */
    @JvmStatic
    fun apply(host: Activity?, viewId: Int, finishOnNav: Boolean) {
        if (host == null) return
        val v = host.findViewById<View>(viewId) ?: return
        var isDl3 = false
        try {
            isDl3 = Platform.get().isDiLink3(host)
        } catch (ignore: Throwable) { /* Platform may not be initialised on some test paths. */ }
        val useOwnSim = host.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_USE_OWN_SIM, SettingsActivity.DEFAULT_USE_OWN_SIM)
        if (isDl3 && useOwnSim) {
            v.visibility = View.VISIBLE
            v.setOnClickListener {
                host.startActivity(Intent(host, HotspotActivity::class.java))
                if (finishOnNav) host.finish()
            }
        } else {
            v.visibility = View.GONE
            v.setOnClickListener(null)
        }
    }
}
