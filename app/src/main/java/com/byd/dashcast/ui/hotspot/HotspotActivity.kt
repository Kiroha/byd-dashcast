package com.byd.dashcast.ui.hotspot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.ui.nav.NavRailLayouts
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.update.TetherFiUpdateChecker
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.AsyncOperationGate
import com.byd.dashcast.util.concurrent.GenerationGate
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

/**
 * Hotspot screen — TetherFi-only (v1.2.42 refonte).
 *
 * Drives the open-source `com.pyamsoft.tetherfi` Wi-Fi Direct group owner +
 * SOCKS5/HTTP proxy via its single exported control surface.
 *
 * v1.6.148 — this page is a CONTROLLER + VIEW only. The automatic keep-alive (probe → START)
 * belongs to [HotspotKeeper], which owns it app-wide; the switch here just arms it and the status
 * line is rendered from the live-stats snapshot. The three buttons remain explicit user actions.
 */
class HotspotActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private var tvUpdate: TextView? = null
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnOpen: MaterialButton
    private var swWatchdog: MaterialSwitch? = null
    private lateinit var swAutoStartBoot: MaterialSwitch
    private var tvWatchdogStatus: TextView? = null
    // v1.2.44 — clients + live stats
    private var tvClientsCount: TextView? = null
    private var llClientsList: LinearLayout? = null
    private var tvClientsEmpty: TextView? = null
    private var tvStatUptime: TextView? = null
    private lateinit var tvStatRx: TextView
    private lateinit var tvStatTx: TextView

    // ── Keep-alive status state (v1.6.148) ───────────────────────────────────
    // The page no longer owns a probe→START loop: [HotspotKeeper] is the single dispatcher
    // app-wide, and the label below is rendered from the live-stats snapshot this screen already
    // fetches every STATS_PERIOD_MS. Only the probe counter shown as "#N" lives here.
    private var watchdogChecks = 0

    /** Cached TetherFi presence — see [isTetherFiInstalledCached]. */
    private var tfInstalledCache = false
    private var tfInstalledCheckedMs = 0L

    // ── Live stats runtime state (v1.2.44) ───────────────────────────────────
    private val statsHandler = Handler(Looper.getMainLooper())
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private var tfUid = -1
    private var upStartElapsed = -1L
    private var rxBaseline = -1L
    private var txBaseline = -1L
    private var renderedClients: List<HClient>? = null
    private val statsGate = GenerationGate()
    private val statsOperationGate = AsyncOperationGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hotspot)

        tvStatus = findViewById(R.id.tv_tf_status)
        tvUpdate = findViewById(R.id.tv_tf_update)
        btnStart = findViewById(R.id.btn_tf_start)
        btnStop = findViewById(R.id.btn_tf_stop)
        btnToggle = findViewById(R.id.btn_tf_toggle)
        btnOpen = findViewById(R.id.btn_tf_open)
        swWatchdog = findViewById(R.id.sw_watchdog)
        swAutoStartBoot = findViewById(R.id.sw_autostart_boot)
        tvWatchdogStatus = findViewById(R.id.tv_watchdog_status)
        tvClientsCount = findViewById(R.id.tv_clients_count)
        llClientsList = findViewById(R.id.ll_clients_list)
        tvClientsEmpty = findViewById(R.id.tv_clients_empty)
        tvStatUptime = findViewById(R.id.tv_stat_uptime)
        tvStatRx = findViewById(R.id.tv_stat_rx)
        tvStatTx = findViewById(R.id.tv_stat_tx)

        // Restore persisted toggles so they survive across launches and feed BootReceiver. Each
        // switch binds ITS OWN pref and nothing else: the top one governs the continuous keeper,
        // the bottom one the one-shot start at vehicle boot. Set before the listeners below are
        // attached, so restoring state is not mistaken for a user action.
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        swWatchdog?.isChecked = prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, false)
        swAutoStartBoot.isChecked =
            prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnStart.setOnClickListener { invokeTetherFiTile(TF_ACTION_START) }
        btnStop.setOnClickListener { invokeTetherFiTile(TF_ACTION_STOP) }
        btnToggle.setOnClickListener { invokeTetherFiTile(TF_ACTION_TOGGLE) }
        btnOpen.setOnClickListener { openTetherFiOrInstall() }

        swWatchdog?.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, checked) }
            if (checked) {
                // Persist the keep-alive beyond this screen: it rides the always-on
                // ProxyKeeperService FG heartbeat, so the hotspot stays up even after the Hotspot
                // page / app is closed (INC-20260705-195419).
                com.byd.dashcast.proxy.ProxyKeeperService.ensureRunning(this)
                // v1.6.148 — the page does NOT start its own probe→START loop any more; it asks
                // the single owner for one immediate pass so the user still sees an instant
                // reaction to the toggle.
                HotspotKeeper.runImmediatePass(this, "watchdog switch on")
                updateWatchdogLabel(getString(R.string.hotspot_watchdog_probing))
            } else {
                updateWatchdogLabel(getString(R.string.hotspot_watchdog_idle))
            }
        }

        swAutoStartBoot.setOnCheckedChangeListener { _, checked ->
            // Its own, independent pref: the one-shot start at vehicle boot, owned by
            // BootReceiver. It does not arm the continuous keeper and does not touch its switch.
            getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, checked) }
            AppLogger.i(TAG, "hotspot autostart-boot " + if (checked) "ON" else "OFF")
        }

        tvUpdate?.setOnClickListener { v ->
            val tag = v.tag
            if (tag is String) {
                val it = Intent(Intent.ACTION_VIEW, tag.toUri())
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(it)
                } catch (t: Throwable) {
                    /* ignore */
                }
            }
        }

        // v1.2.44 — wire the navrail items so the Hotspot screen is consistent
        // with Diag / Sysinfo / Log / Settings (Hotspot itself is the active one).
        wireHotspotNavRail()
    }

    private fun wireHotspotNavRail() {
        val navLogo: View? = findViewById(R.id.iv_nav_logo_hot)
        val navApps: View? = findViewById(R.id.nav_apps_hot)
        val navSettings: View? = findViewById(R.id.nav_settings_hot)
        val navDiag: View? = findViewById(R.id.nav_diag_hot)
        val navSysinfo: View? = findViewById(R.id.nav_sysinfo_hot)
        val navLog: View? = findViewById(R.id.nav_log_hot)
        navLogo?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        navApps?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        navSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)); finish() }
        navDiag?.setOnClickListener { startActivity(Intent(this, DiagActivity::class.java)); finish() }
        navSysinfo?.setOnClickListener { startActivity(Intent(this, SysInfoActivity::class.java)); finish() }
        navLog?.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)); finish() }
        // v1.4.9-beta — Layouts
        NavRailLayouts.apply(this, R.id.nav_layouts_hot, true)
    }

    override fun onResume() {
        super.onResume()
        statsGate.invalidate()
        refreshTetherFiStatus()
        // Re-check upstream on every resume (cheap, ~10 KB JSON).
        checkForTetherFiUpdate()
        // v1.6.148 — no local watchdog to re-arm: HotspotKeeper keeps running app-wide on the
        // ProxyKeeperService heartbeat. Just show "probing" until the first stats snapshot below
        // lands (≤ STATS_PERIOD_MS) and repaints the real state.
        if (swWatchdog?.isChecked == true) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_probing))
        }
        // v1.2.44 — start the live stats ticker (always on while screen visible).
        statsHandler.removeCallbacks(statsTick)
        statsHandler.post(statsTick)
        // v1.2.44 — independent 1Hz uptime ticker.
        uptimeHandler.removeCallbacks(uptimeTick)
        uptimeHandler.post(uptimeTick)
    }

    override fun onPause() {
        super.onPause()
        statsGate.invalidate()
        statsHandler.removeCallbacks(statsTick)
        uptimeHandler.removeCallbacks(uptimeTick)
        // Nothing else to stop: the keep-alive loop is no longer owned by this Activity, so a
        // paused page can neither poll nor relaunch TetherFi behind the user's back.
    }

    private fun checkForTetherFiUpdate() {
        if (getInstalledVersion(TF_PKG) == null) {
            tvUpdate?.visibility = View.GONE
            return
        }
        TetherFiUpdateChecker.check(this, object : TetherFiUpdateChecker.Callback {
            override fun onResult(r: TetherFiUpdateChecker.Result) {
                if (isFinishing || isDestroyed) return
                val update = tvUpdate ?: return
                if (r.isUpdateAvailable) {
                    update.text = getString(
                        R.string.hotspot_tf_update_available,
                        r.installedVersionCode, r.remoteVersionCode
                    )
                    update.tag = r.releasePageUrl
                    update.visibility = View.VISIBLE
                } else {
                    update.visibility = View.GONE
                }
            }

            override fun onError(message: String?) {
                // Silent failure: keep the badge hidden.
                AppLogger.w(TAG, "TetherFi update check failed: $message")
                if (isFinishing || isDestroyed) return
                tvUpdate?.visibility = View.GONE
            }
        })
    }

    override fun onDestroy() {
        try {
            statsHandler.removeCallbacksAndMessages(null)
            uptimeHandler.removeCallbacksAndMessages(null)
        } catch (ignore: Throwable) {
        }
        super.onDestroy()
    }

    // ── TetherFi presence / actions ──────────────────────────────────────────

    /**
     * TetherFi presence, cached.
     *
     * [renderKeepAliveStatus] runs on the MAIN thread every STATS_PERIOD_MS (5 s, 4x the old
     * watchdog rate) and used to call `PackageManager.getPackageInfo` there — a synchronous IPC on
     * the UI thread, on a hot path, for an answer that only changes on an install or an uninstall.
     * Re-read at most every [TF_PRESENCE_TTL_MS], which still catches "TetherFi was uninstalled
     * while the page is open" well within a minute.
     */
    private fun isTetherFiInstalledCached(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (tfInstalledCheckedMs != 0L && now - tfInstalledCheckedMs < TF_PRESENCE_TTL_MS) {
            return tfInstalledCache
        }
        tfInstalledCheckedMs = now
        tfInstalledCache = getInstalledVersion(TF_PKG) != null
        return tfInstalledCache
    }

    private fun refreshTetherFiStatus() {
        val version = getInstalledVersion(TF_PKG)
        val installed = version != null
        // onResume already paid for the lookup — seed the cache with it.
        tfInstalledCache = installed
        tfInstalledCheckedMs = SystemClock.elapsedRealtime()
        if (installed) {
            tvStatus.text = getString(R.string.hotspot_tf_status_installed, version)
            tvStatus.setBackgroundColor(COLOR_OK_BG)
            tvStatus.setTextColor(COLOR_OK_TX)
        } else {
            tvStatus.setText(R.string.hotspot_tf_status_missing)
            tvStatus.setBackgroundColor(COLOR_KO_BG)
            tvStatus.setTextColor(COLOR_KO_TX)
        }
        btnStart.isEnabled = installed
        btnStop.isEnabled = installed
        btnToggle.isEnabled = installed
        // btn_open is always enabled — when missing, it opens the install page.
        btnOpen.setText(if (installed) R.string.hotspot_tf_open else R.string.hotspot_tf_install)
    }

    @Suppress("DEPRECATION")
    private fun getInstalledVersion(pkg: String): String? {
        return try {
            packageManager.getPackageInfo(pkg, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun invokeTetherFiTile(action: String) {
        if (getInstalledVersion(TF_PKG) == null) {
            Toast.makeText(this, R.string.hotspot_tf_status_missing, Toast.LENGTH_LONG).show()
            return
        }
        val it = Intent()
        it.setClassName(TF_PKG, TF_TILE_CLS)
        it.putExtra(TF_KEY_ACTION, action)
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            startActivity(it)
            // Brief visual feedback in the watchdog status field if it's idle.
            if (swWatchdog?.isChecked != true) {
                tvWatchdogStatus?.text = getString(R.string.hotspot_tf_dispatched, action)
            }
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                getString(
                    R.string.hotspot_tf_dispatch_failed, action,
                    t.javaClass.simpleName + ": " + t.message
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openTetherFiOrInstall() {
        if (getInstalledVersion(TF_PKG) != null) {
            val it = Intent()
            it.setClassName(TF_PKG, TF_MAIN_CLS)
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(it)
            } catch (t: Throwable) {
                Toast.makeText(
                    this,
                    getString(R.string.hotspot_tf_open_failed, t.message.toString()),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            val it = Intent(Intent.ACTION_VIEW, TF_RELEASE_URL.toUri())
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(it)
            } catch (t: Throwable) {
                Toast.makeText(this, TF_RELEASE_URL, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Keep-alive status (v1.6.148) ─────────────────────────────────────────
    //
    // The page's own 20 s probe→START loop is gone — it was a second dispatcher racing
    // HotspotKeeper's identical one, with its own cooldown state machine. The 5 s live-stats
    // snapshot ([statsTick]) already runs the same `dumpsys activity services` grep, so the label
    // is rendered from that (one shell loop fewer, and 4× fresher) and the counters come from the
    // keeper, so the user sees what the single process-wide owner is actually doing.

    /** Renders the keep-alive status line from a live-stats snapshot. */
    private fun renderKeepAliveStatus(up: Boolean, err: String?) {
        watchdogChecks++
        val probeId = watchdogChecks
        // The label describes the continuous keeper, so it renders only while ITS pref is on. This
        // gate comes FIRST: with the keeper switch off and the boot switch on, the branch below
        // used to overwrite the "Désactivée" label with "TetherFi désinstallé — surveillance
        // arrêtée", i.e. a stopped-watchdog notice under a switch that is already off.
        if (swWatchdog?.isChecked != true) return
        // TetherFi uninstalled while the page was open: same contract as before — tell the user
        // and switch the keep-alive off. Assigning an unchanged value fires no listener, so this
        // is naturally idempotent even though the renderer runs every 5 s.
        if (!isTetherFiInstalledCached()) {
            swWatchdog?.isChecked = false
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_tf_gone))
            return
        }
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (err != null) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_adb_err, ts, probeId, err))
            return
        }
        val restarts = HotspotKeeper.dispatchedStartCount()
        if (up) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_up, ts, probeId, restarts))
            return
        }
        // DOWN — show how long the keeper still has to wait before its next attempt, or that an
        // attempt is due right now. The page itself never dispatches here.
        val waitMs = HotspotKeeper.msUntilNextAttempt()
        if (waitMs > 0L) {
            val wait = ((waitMs + 999L) / 1000L).toInt()
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_cooldown, ts, probeId, wait))
        } else {
            // "relance #N" is an ORDINAL: the attempt that is due right now is the one after the
            // `restarts` already dispatched. Rendering the raw counter showed "relance #0" before
            // the very first attempt.
            updateWatchdogLabel(
                getString(R.string.hotspot_watchdog_restart, ts, probeId, restarts + 1))
        }
    }

    private fun updateWatchdogLabel(text: String) {
        tvWatchdogStatus?.text = text
    }

    // ── Live stats (v1.2.44) ─────────────────────────────────────────────────

    /** Called from [statsTick] on every snapshot (UP or DOWN). */
    private fun onProbeTransition(up: Boolean) {
        if (up && upStartElapsed < 0) {
            // DOWN → UP : start a new session.
            upStartElapsed = SystemClock.elapsedRealtime()
            ensureTetherFiUidCached()
            if (tfUid > 0) {
                rxBaseline = TrafficStats.getUidRxBytes(tfUid)
                txBaseline = TrafficStats.getUidTxBytes(tfUid)
            }
        } else if (!up && upStartElapsed > 0) {
            // UP → DOWN : tear session down so the stats card visibly resets.
            upStartElapsed = -1L
            rxBaseline = -1L
            txBaseline = -1L
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureTetherFiUidCached() {
        if (tfUid > 0) return
        tfUid = try {
            packageManager.getApplicationInfo(TF_PKG, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val operation = statsOperationGate.tryStart()
            if (operation == null) {
                statsHandler.postDelayed(this, STATS_PERIOD_MS)
                return
            }
            val generation = statsGate.capture()
            // One shell round trip supplies service state AND clients — and, since v1.6.148, the
            // keep-alive status line too (the page no longer runs a second identical probe).
            val cmd = "dumpsys activity services $TF_PKG" +
                " 2>/dev/null | grep -q ProxyForegroundService" +
                " && echo '${HotspotStatsPayload.STATE_UP}'" +
                " || echo '${HotspotStatsPayload.STATE_DOWN}'; " +
                "echo '${HotspotStatsPayload.CLIENTS}'; " +
                "dumpsys wifip2p 2>/dev/null; " +
                "echo '===ARP==='; " +
                "cat /proc/net/arp 2>/dev/null; " +
                "echo '===NEIGH==='; " +
                "ip neigh show 2>/dev/null"
            ShellGateway.execShellWithResult(this@HotspotActivity, cmd, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (!statsOperationGate.complete(operation)) return
                    if (!statsGate.isCurrent(generation)) return
                    val snapshot = HotspotStatsPayload.parse(out)
                    val clients = snapshot?.let { parseClients(it.clientsOutput) }
                    runOnUiThread {
                        if (!statsGate.isCurrent(generation)) return@runOnUiThread
                        if (snapshot != null) onProbeTransition(snapshot.serviceUp)
                        if (clients != null) renderClients(clients)
                        refreshStats()
                        // A snapshot we could not parse tells us nothing about the service, so it
                        // must not be reported as DOWN.
                        if (snapshot != null) renderKeepAliveStatus(snapshot.serviceUp, null)
                    }
                }

                override fun onError(err: String?) {
                    if (!statsOperationGate.complete(operation)) return
                    if (!statsGate.isCurrent(generation)) return
                    runOnUiThread {
                        if (!statsGate.isCurrent(generation)) return@runOnUiThread
                        refreshStats()
                        renderKeepAliveStatus(false, err)
                    }
                }
            })
            statsHandler.postDelayed(this, STATS_PERIOD_MS)
        }
    }

    /**
     * v1.2.44 — 1Hz uptime chrono. Updates only [tvStatUptime] so the user sees
     * the seconds visibly tick by, without firing an ADB probe.
     */
    private val uptimeTick = object : Runnable {
        override fun run() {
            tvStatUptime?.let { tv ->
                if (upStartElapsed > 0) {
                    val up = SystemClock.elapsedRealtime() - upStartElapsed
                    tv.text = formatUptime(up)
                } else {
                    tv.text = "—"
                }
            }
            uptimeHandler.postDelayed(this, UPTIME_TICK_MS)
        }
    }

    private fun refreshStats() {
        val uptimeView = tvStatUptime ?: return
        if (upStartElapsed < 0) {
            uptimeView.text = "—"
            tvStatRx.text = "—"
            tvStatTx.text = "—"
            return
        }
        val up = SystemClock.elapsedRealtime() - upStartElapsed
        uptimeView.text = formatUptime(up)
        if (tfUid > 0 && rxBaseline >= 0) {
            val rx = TrafficStats.getUidRxBytes(tfUid) - rxBaseline
            val tx = TrafficStats.getUidTxBytes(tfUid) - txBaseline
            tvStatRx.text = formatBytes(rx)
            tvStatTx.text = formatBytes(tx)
        } else {
            tvStatRx.text = "—"
            tvStatTx.text = "—"
        }
    }

    // ── Clients enumeration (v1.2.44) ─────────────────────────────────────────

    // Clients count is a plain integer (no localizable text) — String.valueOf in
    // the original Java; .toString() trips SetTextI18n on the newer Kotlin lint.
    @SuppressLint("SetTextI18n")
    private fun renderClients(clients: List<HClient>) {
        val list = llClientsList ?: return
        val count = tvClientsCount ?: return
        if (clients == renderedClients) return
        renderedClients = clients
        count.text = clients.size.toString()
        list.removeAllViews()
        if (clients.isEmpty()) {
            tvClientsEmpty?.visibility = View.VISIBLE
            return
        }
        tvClientsEmpty?.visibility = View.GONE
        for (c in clients) list.addView(buildClientRow(c))
    }

    private fun buildClientRow(c: HClient): View {
        val padH = dp(4)
        val padV = dp(8)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(padH, padV, padH, padV)

        val icon = ImageView(this)
        val iconLp = LinearLayout.LayoutParams(dp(20), dp(20))
        iconLp.marginEnd = dp(10)
        icon.layoutParams = iconLp
        icon.setImageResource(R.drawable.ic_devices)
        icon.setColorFilter(ContextCompat.getColor(this, R.color.md_on_surface_variant))

        val tvName = TextView(this)
        val nameLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tvName.layoutParams = nameLp
        tvName.text = if (!c.name.isNullOrEmpty()) c.name else friendlyMacName(c.mac)
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tvName.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface))
        tvName.isSingleLine = true
        tvName.ellipsize = TextUtils.TruncateAt.END

        val tvIp = TextView(this)
        tvIp.text = c.ip ?: c.mac
        tvIp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tvIp.typeface = Typeface.MONOSPACE
        tvIp.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))

        row.addView(icon)
        row.addView(tvName)
        row.addView(tvIp)
        return row
    }

    private fun friendlyMacName(mac: String?): String {
        // "aa:bb:cc:dd:ee:ff" → "Appareil ee:ff" / "Device ee:ff" / …
        val suffix = if (mac != null && mac.length >= 5) mac.substring(mac.length - 5) else ""
        return getString(R.string.hotspot_client_default_name, suffix)
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private data class HClient(val mac: String, val name: String?, val ip: String?)

    companion object {
        private const val TAG = "HotspotActivity"

        private const val TF_PKG = "com.pyamsoft.tetherfi"
        private const val TF_TILE_CLS = "com.pyamsoft.tetherfi.tile.ProxyTileActivity"
        private const val TF_MAIN_CLS = "com.pyamsoft.tetherfi.main.MainActivity"
        private const val TF_KEY_ACTION = "key_action"
        private const val TF_ACTION_START = "START"
        private const val TF_ACTION_STOP = "STOP"
        private const val TF_ACTION_TOGGLE = "TOGGLE"
        private const val TF_RELEASE_URL = "https://github.com/pyamsoft/tetherfusenet/releases/latest"

        // ── Status colors ────────────────────────────────────────────────────
        private val COLOR_OK_BG = 0xFFE6F4EA.toInt() // soft green
        private val COLOR_OK_TX = 0xFF1B5E20.toInt() // dark green
        private val COLOR_KO_BG = 0xFFFDECEA.toInt() // soft red
        private val COLOR_KO_TX = 0xFFB71C1C.toInt() // dark red

        private const val STATS_PERIOD_MS = 5_000L
        private const val UPTIME_TICK_MS = 1_000L

        /** How long a TetherFi presence lookup stays valid — see [isTetherFiInstalledCached]. */
        private const val TF_PRESENCE_TTL_MS = 30_000L

        private val P_MAC = Pattern.compile("([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
        private val P_NAME =
            Pattern.compile("deviceName[=:\\s]+([^,\\n\\r]+?)(?:,| deviceAddress| primary| status)")
        private val P_NEWLINE = Pattern.compile("\\r?\\n")
        private val P_SPACES = Pattern.compile("\\s+")
        private val P_IP_ADDR = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}")

        private fun formatUptime(ms: Long): String {
            if (ms < 0) return "—"
            val s = ms / 1000L
            val h = s / 3600L
            val m = (s % 3600L) / 60L
            val ss = s % 60L
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
            else String.format(Locale.US, "%02d:%02d", m, ss)
        }

        private fun formatBytes(b: Long): String {
            if (b < 0) return "—"
            if (b < 1024L) return "$b B"
            if (b < 1024L * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0)
            if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
            return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        }

        /** Pure parser of the combined dumpsys/arp/neigh ADB output (no Android deps). */
        private fun parseClients(adbOut: String?): List<HClient> {
            if (adbOut == null) return ArrayList()
            val sepArp = adbOut.indexOf("===ARP===")
            val sepNeigh = adbOut.indexOf("===NEIGH===")
            val dumpsys = if (sepArp > 0) adbOut.substring(0, sepArp) else adbOut
            val arp: String
            val neigh: String
            if (sepArp > 0 && sepNeigh > sepArp) {
                arp = adbOut.substring(sepArp, sepNeigh)
                neigh = adbOut.substring(sepNeigh)
            } else if (sepArp > 0) {
                arp = adbOut.substring(sepArp)
                neigh = ""
            } else {
                arp = ""
                neigh = ""
            }

            // Build MAC → IP map from /proc/net/arp + tag peer-interface entries.
            val mac2ip = HashMap<String, String>()
            val arpPeers = LinkedHashMap<String, String>()
            for (line in P_NEWLINE.split(arp)) {
                val cols = P_SPACES.split(line.trim())
                if (cols.size < 6) continue
                val ip = cols[0]
                val flags = cols[2]
                val mac = cols[3]
                val dev = cols[5]
                if (!P_MAC.matcher(mac).matches()) continue
                if ("00:00:00:00:00:00".equals(mac, ignoreCase = true)) continue
                if (!P_IP_ADDR.matcher(ip).matches()) continue
                val mlow = mac.lowercase(Locale.US)
                mac2ip[mlow] = ip
                // Flags 0x0 == incomplete entry (no reply yet); 0x2 = complete.
                val reachable = !"0x0".equals(flags, ignoreCase = true)
                val dlow = dev.lowercase(Locale.US)
                val onPeerInterface = dlow.startsWith("p2p-") || dlow.startsWith("p2p") ||
                    dlow.startsWith("ap") || dlow.contains("ap0") ||
                    dlow.contains("softap") || dlow.endsWith("-ap")
                if (reachable && onPeerInterface) {
                    arpPeers[mlow] = ip
                }
            }

            // Walk the dumpsys output looking for the WifiP2pGroup peers.
            val byMac = LinkedHashMap<String, HClient>()
            var inClientList = false
            var pendingName: String? = null
            for (raw in P_NEWLINE.split(dumpsys)) {
                val line = raw.trim()
                val low = line.lowercase(Locale.US)

                if (low.startsWith("client list") || low.startsWith("mclients") ||
                    low.startsWith("clients:")
                ) {
                    inClientList = true
                    continue
                }
                if (line.isEmpty()) {
                    inClientList = false
                    pendingName = null
                    continue
                }

                val nm = P_NAME.matcher(line)
                if (nm.find()) pendingName = nm.group(1)?.trim()

                val isClientLine = low.startsWith("client") || inClientList ||
                    low.contains("wifip2pdevice")
                if (!isClientLine) continue

                val mm = P_MAC.matcher(line)
                while (mm.find()) {
                    val mac = (mm.group(1) ?: continue).lowercase(Locale.US)
                    if (byMac.containsKey(mac)) continue
                    byMac[mac] = HClient(mac, pendingName, mac2ip[mac])
                    pendingName = null
                }
            }

            // ARP-on-peer-interface fallback (authoritative).
            for ((mac, ip) in arpPeers) {
                if (byMac.containsKey(mac)) continue
                byMac[mac] = HClient(mac, null, ip)
            }

            // `ip neigh show` fallback.
            for (line in P_NEWLINE.split(neigh)) {
                val low = line.lowercase(Locale.US)
                if (low.isEmpty() || low.startsWith("==")) continue
                if (low.contains(" failed") || low.contains(" incomplete")) continue
                val devIdx = low.indexOf(" dev ")
                if (devIdx < 0) continue
                val afterDev = low.substring(devIdx + 5).trim()
                val dev = P_SPACES.split(afterDev.trim(), 2)[0]
                val onPeerInterface = dev.startsWith("p2p-") || dev.startsWith("p2p") ||
                    dev.startsWith("ap") || dev.contains("ap0") ||
                    dev.contains("softap") || dev.endsWith("-ap")
                if (!onPeerInterface) continue
                val mm = P_MAC.matcher(line)
                if (!mm.find()) continue
                val mac = (mm.group(1) ?: continue).lowercase(Locale.US)
                if ("00:00:00:00:00:00".equals(mac, ignoreCase = true)) continue
                if (byMac.containsKey(mac)) continue
                var ip: String? = P_SPACES.split(line.trim(), 2)[0]
                if (ip != null && !P_IP_ADDR.matcher(ip).matches()) ip = null
                byMac[mac] = HClient(mac, null, ip)
            }
            return ArrayList(byMac.values)
        }
    }
}
