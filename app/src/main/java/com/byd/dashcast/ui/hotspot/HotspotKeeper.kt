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
    /** A probe whose callback never fired (ShellGateway executor wedged / an Error escaped
     *  AdbLocalClient's catch) is force-reset once it is this far past due, so probeInFlight
     *  can't stick true and silently kill the keep-alive. */
    private const val PROBE_STUCK_MS = PROBE_INTERVAL_MS * 3
    /** Give up relaunching TetherFi after this many consecutive failed attempts (it stays
     *  DOWN — the user stopped it on purpose, or it can't start) until a probe reports UP
     *  again. Stops an unbounded foreground-Activity relaunch storm every RESTART_COOLDOWN_MS. */
    private const val MAX_RESTART_ATTEMPTS = 5
    /** After giving up (MAX_RESTART_ATTEMPTS consecutive failures), wait this long then retry —
     *  so the ceiling is a BACKOFF, not a permanent latch: a transient cause that outlasts the
     *  burst (low-memory kill, TetherFi self-update) still self-heals without user action. */
    private const val GIVE_UP_COOLDOWN_MS = 5 * 60_000L

    @Volatile private var lastProbeMs = 0L
    @Volatile private var lastRestartMs = 0L
    @Volatile private var probeInFlight = false
    @Volatile private var consecutiveRestarts = 0
    @Volatile private var wasEnabled = false
    /** Monotonic probe id. Bumped on each dispatch AND on a stuck-reset, so a stale/late probe
     *  callback (whose generation no longer matches) is ignored instead of clobbering a newer
     *  probe's in-flight flag or resetting the give-up counter on out-of-date state. */
    @Volatile private var probeGeneration = 0

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
        val enabled = isEnabled(ctx)
        // Reset the give-up counter on the disabled→enabled edge so re-enabling the keep-alive
        // actually resumes it (the give-up log promises this), instead of staying wedged.
        if (enabled && !wasEnabled) consecutiveRestarts = 0
        wasEnabled = enabled
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        // Staleness guard: if a prior probe's callback was lost (ShellGateway's single-thread
        // executor wedged on a hung runShell, or an Error escaped AdbLocalClient's catch),
        // probeInFlight would stick true forever and silently kill the keep-alive. Force-reset
        // it once the in-flight probe is well past due (lastProbeMs marks its start).
        if (probeInFlight && now - lastProbeMs > PROBE_STUCK_MS) {
            AppLogger.w(TAG, "keep-alive probe stuck ${now - lastProbeMs}ms — force-reset")
            probeInFlight = false
            probeGeneration++ // invalidate the stuck probe's still-outstanding callback
        }
        if (probeInFlight || now - lastProbeMs < PROBE_INTERVAL_MS) return
        val app = ctx.applicationContext
        if (!tetherFiInstalled(app)) return
        lastProbeMs = now
        probeInFlight = true
        val gen = ++probeGeneration
        // Same probe HotspotActivity uses: is TetherFi's ProxyForegroundService running?
        val cmd = "dumpsys activity services $TF_PKG" +
                " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN"
        ShellGateway.execShellWithResult(app, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                if (gen != probeGeneration) return // superseded by a newer probe / a stuck-reset
                probeInFlight = false
                if (out == null || !out.contains("UP")) restart(app)
                else consecutiveRestarts = 0 // UP → keep-alive healthy, clear the give-up counter
            }
            override fun onError(err: String?) {
                if (gen != probeGeneration) return // superseded by a newer probe / a stuck-reset
                probeInFlight = false
            }
        })
    }

    private fun restart(app: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastRestartMs > 0 && now - lastRestartMs < RESTART_COOLDOWN_MS) return
        // Give-up ceiling: if TetherFi stays DOWN despite repeated restarts, stop dispatching a
        // foreground Activity launch every RESTART_COOLDOWN_MS forever. The counter resets to 0
        // as soon as a probe reports UP (onSuccess), so a normally-recovering hotspot is unaffected.
        if (consecutiveRestarts >= MAX_RESTART_ATTEMPTS) {
            // Backoff, not a permanent latch: after GIVE_UP_COOLDOWN_MS with no success, clear the
            // counter and retry, so a transient cause that outlasted the burst still self-heals.
            // (The prior code returned here forever — the counter only reset on an UP probe, but
            // nothing could bring TetherFi UP once the keeper stopped dispatching STARTs.)
            if (now - lastRestartMs >= GIVE_UP_COOLDOWN_MS) {
                AppLogger.i(TAG, "give-up cooldown elapsed — retrying TetherFi keep-alive")
                consecutiveRestarts = 0
            } else {
                if (consecutiveRestarts == MAX_RESTART_ATTEMPTS) {
                    AppLogger.w(TAG, "TetherFi still DOWN after $MAX_RESTART_ATTEMPTS restarts — "
                            + "backing off ${GIVE_UP_COOLDOWN_MS / 60_000}min (or until UP / re-enable)")
                    consecutiveRestarts++ // bump past the cap so this logs exactly once
                }
                return
            }
        }
        if (!Settings.canDrawOverlays(app)) {
            // Without a BAL exemption the OS silently drops the tile launch from the
            // background — surface it once so the cause is diagnosable.
            AppLogger.w(TAG, "TetherFi DOWN but no overlay permission — cannot start from "
                    + "background (Android BAL). Grant 'Display over other apps' or open the "
                    + "Hotspot page once.")
            return
        }
        lastRestartMs = now
        consecutiveRestarts++
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
