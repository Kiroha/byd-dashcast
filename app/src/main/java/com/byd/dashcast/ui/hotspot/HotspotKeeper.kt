package com.byd.dashcast.ui.hotspot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger

/**
 * HotspotKeeper — app-wide, persistent TetherFi keep-alive.
 *
 * The in-Activity watchdog ([HotspotActivity]) only ran while the Hotspot page was
 * resumed (onResume→startWatchdog, onPause→stopWatchdog), so the "always on" toggle
 * silently STOPPED keeping the hotspot alive the moment the user left the page
 * (reported INC-20260705-195419). This helper runs the SAME probe→restart loop from
 * the always-on [com.byd.dashcast.proxy.ProxyKeeperService] heartbeat, so the hotspot
 * stays up app-wide (page closed, app backgrounded) and is re-armed at boot / app launch.
 *
 * Note: on BYD DiLink ROMs the OS may not start DashCast at boot at all unless the app is
 * whitelisted for auto-start ("self-start management") — no code path can bypass that, so
 * boot-without-opening the app additionally requires that device setting.
 *
 * Gated on {@code PREF_HOTSPOT_WATCHDOG}; throttled to [PROBE_INTERVAL_MS]. Starting the
 * TetherFi tile is a background Activity launch, which Android 10+ blocks unless the app
 * holds a background-activity-launch exemption — {@code SYSTEM_ALERT_WINDOW} (DashCast's
 * floating button) is one, so we only fire when {@code canDrawOverlays()} is true.
 */
object HotspotKeeper {

    private const val TAG = "HotspotKeeper"
    private const val TF_PKG = "com.pyamsoft.tetherfi"
    private const val TF_TILE_CLS = "com.pyamsoft.tetherfi.tile.ProxyTileActivity"
    private const val TF_KEY_ACTION = "key_action"
    private const val TF_ACTION_START = "START"
    private const val PROBE_INTERVAL_MS = 20_000L
    private const val RESTART_COOLDOWN_MS = 30_000L

    @Volatile private var lastProbeMs = 0L
    @Volatile private var lastRestartMs = 0L
    @Volatile private var probeInFlight = false

    /** True if the user enabled the persistent hotspot keep-alive ("watchdog / always on"). */
    @JvmStatic
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, false)

    /**
     * Called from the ProxyKeeperService heartbeat. Cheap no-op when the pref is off,
     * throttled, a probe is already in flight, or TetherFi is not installed.
     */
    @JvmStatic
    fun maybeKeepAlive(ctx: Context) {
        if (!isEnabled(ctx)) return
        val now = SystemClock.elapsedRealtime()
        if (probeInFlight || now - lastProbeMs < PROBE_INTERVAL_MS) return
        val app = ctx.applicationContext
        if (!tetherFiInstalled(app)) return
        lastProbeMs = now
        probeInFlight = true
        // Same probe HotspotActivity uses: is TetherFi's ProxyForegroundService running?
        val cmd = "dumpsys activity services $TF_PKG" +
                " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN"
        ShellGateway.execShellWithResult(app, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                probeInFlight = false
                if (out == null || !out.contains("UP")) restart(app)
            }
            override fun onError(err: String?) {
                probeInFlight = false
            }
        })
    }

    private fun restart(app: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastRestartMs > 0 && now - lastRestartMs < RESTART_COOLDOWN_MS) return
        if (!Settings.canDrawOverlays(app)) {
            // Without a BAL exemption the OS silently drops the tile launch from the
            // background — surface it once so the cause is diagnosable.
            AppLogger.w(TAG, "TetherFi DOWN but no overlay permission — cannot start from "
                    + "background (Android BAL). Grant 'Display over other apps' or open the "
                    + "Hotspot page once.")
            return
        }
        lastRestartMs = now
        try {
            val i = Intent().apply {
                setClassName(TF_PKG, TF_TILE_CLS)
                putExtra(TF_KEY_ACTION, TF_ACTION_START)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            app.startActivity(i)
            AppLogger.i(TAG, "TetherFi DOWN → START dispatched (keeper)")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "TetherFi START failed: ${t.message}")
        }
    }

    private fun tetherFiInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TF_PKG, 0)
        true
    } catch (e: Exception) {
        false
    }
}
