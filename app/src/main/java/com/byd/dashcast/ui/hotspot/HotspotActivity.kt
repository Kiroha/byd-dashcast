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
 * SOCKS5/HTTP proxy via its single exported control surface, with an optional
 * watchdog polling `dumpsys activity services` through [AdbLocalClient].
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

    // ── Watchdog runtime state ───────────────────────────────────────────────
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogProbeInFlight = false
    private var lastRestartElapsed = -1L
    private var watchdogRestarts = 0
    private var watchdogChecks = 0

    // ── Live stats runtime state (v1.2.44) ───────────────────────────────────
    private val statsHandler = Handler(Looper.getMainLooper())
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private var tfUid = -1
    private var upStartElapsed = -1L
    private var rxBaseline = -1L
    private var txBaseline = -1L

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

        // Restore persisted toggles so they survive across launches and feed BootReceiver.
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        swWatchdog?.isChecked = prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, false)
        swAutoStartBoot.isChecked = prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        btnStart.setOnClickListener { invokeTetherFiTile(TF_ACTION_START) }
        btnStop.setOnClickListener { invokeTetherFiTile(TF_ACTION_STOP) }
        btnToggle.setOnClickListener { invokeTetherFiTile(TF_ACTION_TOGGLE) }
        btnOpen.setOnClickListener { openTetherFiOrInstall() }

        swWatchdog?.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, checked) }
            if (checked) startWatchdog() else stopWatchdog(true)
        }

        swAutoStartBoot.setOnCheckedChangeListener { _, checked ->
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
        refreshTetherFiStatus()
        // Re-check upstream on every resume (cheap, ~10 KB JSON).
        checkForTetherFiUpdate()
        // Auto-restart watchdog polling if it was on and got paused.
        if (swWatchdog?.isChecked == true && watchdogChecks == 0) {
            startWatchdog()
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
        statsHandler.removeCallbacks(statsTick)
        uptimeHandler.removeCallbacks(uptimeTick)
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
        stopWatchdog(false)
        try {
            statsHandler.removeCallbacksAndMessages(null)
            uptimeHandler.removeCallbacksAndMessages(null)
        } catch (ignore: Throwable) {
        }
        super.onDestroy()
    }

    // ── TetherFi presence / actions ──────────────────────────────────────────

    private fun refreshTetherFiStatus() {
        val version = getInstalledVersion(TF_PKG)
        val installed = version != null
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

    // ── Watchdog ─────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogRestarts = 0
        watchdogChecks = 0
        lastRestartElapsed = -1L
        updateWatchdogLabel(getString(R.string.hotspot_watchdog_probing))
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogHandler.post(watchdogTick)
    }

    private fun stopWatchdog(userInitiated: Boolean) {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogProbeInFlight = false
        if (userInitiated) {
            tvWatchdogStatus?.text = getString(R.string.hotspot_watchdog_idle)
        }
    }

    private val watchdogTick = object : Runnable {
        override fun run() {
            if (watchdogProbeInFlight) {
                watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
                return
            }
            if (getInstalledVersion(TF_PKG) == null) {
                updateWatchdogLabel(getString(R.string.hotspot_watchdog_tf_gone))
                swWatchdog?.isChecked = false
                return
            }
            watchdogProbeInFlight = true
            watchdogChecks++
            val probeId = watchdogChecks
            // Echo UP/DOWN so we always come back through onSuccess().
            val cmd = "dumpsys activity services $TF_PKG" +
                " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN"
            ShellGateway.execShellWithResult(this@HotspotActivity, cmd, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    watchdogProbeInFlight = false
                    val up = out != null && out.contains("UP")
                    runOnUiThread { handleWatchdogResult(probeId, up, null) }
                }

                override fun onError(err: String?) {
                    watchdogProbeInFlight = false
                    runOnUiThread { handleWatchdogResult(probeId, false, err) }
                }
            })
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    private fun handleWatchdogResult(probeId: Int, up: Boolean, err: String?) {
        // v1.2.44 — feed the live-stats session lifecycle on every probe.
        if (err == null) onProbeTransition(up)
        if (swWatchdog?.isChecked != true) return // user disabled
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (err != null) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_adb_err, ts, probeId, err))
            return
        }
        if (up) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_up, ts, probeId, watchdogRestarts))
            return
        }
        // DOWN — apply cooldown before restarting.
        val now = SystemClock.elapsedRealtime()
        if (lastRestartElapsed > 0 && now - lastRestartElapsed < WATCHDOG_RESTART_COOLDOWN_MS) {
            val wait = (WATCHDOG_RESTART_COOLDOWN_MS - (now - lastRestartElapsed)) / 1000L
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_cooldown, ts, probeId, wait.toInt()))
            return
        }
        lastRestartElapsed = now
        watchdogRestarts++
        updateWatchdogLabel(getString(R.string.hotspot_watchdog_restart, ts, probeId, watchdogRestarts))
        AppLogger.i(TAG, "watchdog: TetherFi DOWN → firing START (restart #$watchdogRestarts)")
        invokeTetherFiTile(TF_ACTION_START)
    }

    private fun updateWatchdogLabel(text: String) {
        tvWatchdogStatus?.text = text
    }

    // ── Live stats (v1.2.44) ─────────────────────────────────────────────────

    /** Called from [handleWatchdogResult] on every probe (UP or DOWN). */
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
            // Fire a lightweight probe of our own so the stats card works even
            // when the user has the watchdog auto-restart switch OFF.
            val cmd = "dumpsys activity services $TF_PKG" +
                " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN"
            ShellGateway.execShellWithResult(this@HotspotActivity, cmd, object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    val up = out != null && out.contains("UP")
                    runOnUiThread { onProbeTransition(up); refreshStats() }
                }

                override fun onError(err: String?) {
                    runOnUiThread { refreshStats() }
                }
            })
            // Clients enumeration runs alongside the presence probe.
            refreshClients()
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

    private fun refreshClients() {
        // v1.2.44 — combine three reads in one ADB roundtrip; use sentinel separators.
        val cmd = "dumpsys wifip2p 2>/dev/null; " +
            "echo '===ARP==='; " +
            "cat /proc/net/arp 2>/dev/null; " +
            "echo '===NEIGH==='; " +
            "ip neigh show 2>/dev/null"
        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                val list = parseClients(out)
                runOnUiThread { renderClients(list) }
            }

            override fun onError(err: String?) {
                // Keep last render on transient ADB errors.
            }
        })
    }

    // Clients count is a plain integer (no localizable text) — String.valueOf in
    // the original Java; .toString() trips SetTextI18n on the newer Kotlin lint.
    @SuppressLint("SetTextI18n")
    private fun renderClients(clients: List<HClient>) {
        val list = llClientsList ?: return
        val count = tvClientsCount ?: return
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

    private class HClient(val mac: String, val name: String?, val ip: String?)

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

        // ── Watchdog tuning ──────────────────────────────────────────────────
        private const val WATCHDOG_PERIOD_MS = 20_000L
        private const val WATCHDOG_RESTART_COOLDOWN_MS = 30_000L

        // ── Status colors ────────────────────────────────────────────────────
        private val COLOR_OK_BG = 0xFFE6F4EA.toInt() // soft green
        private val COLOR_OK_TX = 0xFF1B5E20.toInt() // dark green
        private val COLOR_KO_BG = 0xFFFDECEA.toInt() // soft red
        private val COLOR_KO_TX = 0xFFB71C1C.toInt() // dark red

        private const val STATS_PERIOD_MS = 5_000L
        private const val UPTIME_TICK_MS = 1_000L

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
