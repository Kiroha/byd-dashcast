package com.byd.dashcast.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.report.ReportChannel
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.ui.diag.DiagActivity
import com.byd.dashcast.ui.diag.SysInfoActivity
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.ui.nav.NavRailHotspot
import com.byd.dashcast.ui.nav.NavRailLayouts
import com.byd.dashcast.update.OtaProgressUi
import com.byd.dashcast.update.UpdateChecker
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.LocaleHelper

/**
 * User-facing settings screen.
 *
 * Currently covers the cluster screen type (sendInfo cmd: 29 = 8.8", 30 = 12.3" Seal EU,
 * 31 = 10.25") plus the OTA, boot and diagnostic toggles.
 *
 * v1.8.2 — the display overscan margins card was removed: it applied its value twice (task
 * launch bounds + `wm overscan`), so the 80/50 default blacked out 40% of the cluster
 * (INC-20260725-211405). Shrinking an app is now solely the per-app hand-drawn rectangle.
 */
@SuppressLint("SetTextI18n")
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var rgClusterType: RadioGroup
    // Note: fields below are typed as CompoundButton so the layout can use either
    // <CheckBox> or <MaterialSwitch> without breaking the cast. Both inherit
    // setChecked/isChecked/setOnCheckedChangeListener from CompoundButton.
    private lateinit var cbPrerelease: CompoundButton
    private lateinit var cbBootAutoStart: CompoundButton
    private lateinit var cbShowCategoryFilters: CompoundButton
    private lateinit var cbReconnectPopup: CompoundButton
    private lateinit var cbQuickStop: CompoundButton
    private var cbAdasWindowFix: CompoundButton? = null
    private lateinit var cbUseOwnSim: CompoundButton
    private var cbCompactAppsPanel: CompoundButton? = null
    private var cbCaptureShots: CompoundButton? = null
    private lateinit var swLegacyPath: CompoundButton
    private lateinit var cbFissionMode: CompoundButton
    private lateinit var swFissionAutoLayout: CompoundButton
    private lateinit var swFissionPrecreateSlots: CompoundButton

    @Volatile
    private var mDestroyed = false
    private lateinit var mPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_header)
        }

        bindViews()
        loadPreferences()
        wireListeners()
        wireSettingsNavRail()
        // Populate dynamic version text ("0.9.2 · build 121").
        val tvVersion: TextView? = findViewById(R.id.tv_version_value)
        tvVersion?.text = BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE

        // "Rechercher une mise à jour" — runs the same OTA check as the 3-dot menu in MainActivity.
        val btnCheckUpdate: View? = findViewById(R.id.btn_check_update)
        btnCheckUpdate?.setOnClickListener {
            UpdateChecker.checkUpdate(this, OtaProgressUi.makeListener(this, true))
        }

        // "Code source" row — opens the GitHub repository.
        val rowSourceCode: View? = findViewById(R.id.row_source_code)
        rowSourceCode?.setOnClickListener { openUrl("https://github.com/Kiroha/byd-dashcast") }

        // Bug report delivery — the only place a normal user can pair this device.
        findViewById<View>(R.id.row_report_channel)?.setOnClickListener { showPairingDialog() }
        refreshReportChannelRow()
    }

    // ── Bug report delivery (pairing) ────────────────────────────────────────
    //
    // Credentials no longer ship in the APK, so a device that has not been paired keeps its
    // reports to itself. That is the safe default, but it is only acceptable if pairing is
    // something a user can actually do — hence a row with a visible state and a dialog, rather
    // than the developer-screen button this started as.

    /**
     * Reflects the pairing state into the row.
     *
     * Read off the main thread: the state lives in EncryptedSharedPreferences, and the first read
     * of the process builds a KeyStore-backed store — an IPC round trip that has no business on
     * the thread drawing this screen. [mDestroyed] guards the hop back.
     */
    private fun refreshReportChannelRow() {
        Thread {
            val paired = try { ReportChannel.isPairedOnDevice(this) } catch (_: Throwable) { false }
            if (mDestroyed) return@Thread
            runOnUiThread {
                if (mDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.tv_report_channel_state)?.setText(
                    if (paired) R.string.pairing_state_on else R.string.pairing_state_off)
                findViewById<ImageView>(R.id.iv_report_channel_state)?.visibility =
                    if (paired) View.VISIBLE else View.GONE
            }
        }.start()
    }

    /**
     * Two dialogs in one, because the two states need different verbs.
     *
     * Unpaired: explain what the file is for and offer the two ways in — the folder, or the
     * clipboard for a token received in a chat, where saving an attachment is the harder half.
     * Paired: say so plainly and offer to forget, which is the only action left that means
     * anything.
     */
    private fun showPairingDialog() {
        Thread {
            val paired = try { ReportChannel.isPairedOnDevice(this) } catch (_: Throwable) { false }
            if (mDestroyed) return@Thread
            runOnUiThread {
                if (mDestroyed || isFinishing) return@runOnUiThread
                val b = AlertDialog.Builder(this)
                    .setTitle(R.string.pairing_row_title)
                    .setNegativeButton(R.string.pairing_action_close, null)
                if (paired) {
                    b.setMessage(R.string.pairing_dialog_paired)
                        .setPositiveButton(R.string.pairing_action_forget) { _, _ -> forgetPairing() }
                } else {
                    b.setMessage(getString(R.string.pairing_dialog_howto, ReportChannel.IMPORT_NAME))
                        .setPositiveButton(R.string.pairing_action_import) { _, _ -> pairFromFolder() }
                        .setNeutralButton(R.string.pairing_action_paste) { _, _ -> pairFromClipboard() }
                }
                b.show()
            }
        }.start()
    }

    /** Looks for the provisioning file in Download, then in the maintainer's /data/local/tmp. */
    private fun pairFromFolder() {
        toast(getString(R.string.pairing_working))
        try {
            // Already asynchronous: the helper hands the work to its own executor and calls back
            // on a background thread, so the outcome has to hop to the UI thread itself.
            ReportChannel.importFromDevice(this) { outcome ->
                onPairingOutcome(
                    when {
                        outcome.startsWith("paired") -> R.string.pairing_result_ok
                        outcome.startsWith("no ") -> R.string.pairing_result_not_found
                        outcome.startsWith("provisioning file found") -> R.string.pairing_result_invalid
                        else -> R.string.pairing_result_unreadable
                    })
            }
        } catch (t: Throwable) {
            onPairingOutcome(R.string.pairing_result_unreadable)
        }
    }

    /**
     * Same parser, clipboard instead of a file.
     *
     * A tester who receives the credentials in a message can long-press, copy and paste, which
     * skips the part of the folder route they are most likely to get wrong: getting an attachment
     * out of a chat app and into Download with its name intact.
     */
    private fun pairFromClipboard() {
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Throwable) { null }
        if (text.isNullOrBlank()) {
            onPairingOutcome(R.string.pairing_result_clipboard_empty)
            return
        }
        Thread {
            // Writes to the encrypted store — never on the main thread.
            val applied = try { ReportChannel.applyProperties(this, text) } catch (_: Throwable) { 0 }
            onPairingOutcome(
                if (applied > 0) R.string.pairing_result_ok else R.string.pairing_result_invalid)
        }.start()
    }

    /** Drops the stored credentials. Reports then stay on the device, as they do out of the box. */
    private fun forgetPairing() {
        Thread {
            val ok = try { ReportChannel.clear(this) } catch (_: Throwable) { false }
            onPairingOutcome(
                if (ok) R.string.pairing_result_forgotten else R.string.pairing_result_unreadable)
        }.start()
    }

    /** Single exit for every pairing attempt: tell the user, then re-read the state. */
    private fun onPairingOutcome(msgRes: Int) {
        if (mDestroyed) return
        runOnUiThread {
            if (mDestroyed) return@runOnUiThread
            toast(getString(msgRes))
            refreshReportChannelRow()
        }
    }

    private fun toast(msg: String) {
        if (!mDestroyed) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Open the given URL in an external browser. Null-safe and intent-resolve-safe. */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.settings_no_browser_fmt, url),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mDestroyed = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    private fun wireSettingsNavRail() {
        val navApps: View? = findViewById(R.id.nav_apps_set)
        val navDiag: View? = findViewById(R.id.nav_diag_set)
        val navSysinfo: View? = findViewById(R.id.nav_sysinfo_set)
        val navLog: View? = findViewById(R.id.nav_log_set)
        val navLogo: View? = findViewById(R.id.iv_nav_logo_set)
        navApps?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        navDiag?.setOnClickListener { startActivity(Intent(this, DiagActivity::class.java)); finish() }
        navSysinfo?.setOnClickListener { startActivity(Intent(this, SysInfoActivity::class.java)); finish() }
        navLog?.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)); finish() }
        navLogo?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        // v1.2.44 — Hotspot navrail entry (DL3 + use_own_sim runtime-gated)
        NavRailHotspot.apply(this, R.id.nav_hotspot_set, true)
        // v1.4.9-beta — Layouts
        NavRailLayouts.apply(this, R.id.nav_layouts_set, true)
    }

    private fun bindViews() {
        rgClusterType = findViewById(R.id.rg_cluster_type)
        cbPrerelease = findViewById(R.id.cb_prerelease)
        cbBootAutoStart = findViewById(R.id.cb_boot_auto_start)
        cbShowCategoryFilters = findViewById(R.id.cb_show_category_filters)
        cbCaptureShots = findViewById(R.id.cb_capture_shots)
        cbReconnectPopup = findViewById(R.id.cb_reconnect_popup)
        cbQuickStop = findViewById(R.id.cb_quick_stop)
        cbAdasWindowFix = findViewById(R.id.cb_adas_window_fix)
        cbUseOwnSim = findViewById(R.id.cb_use_own_sim)
        cbCompactAppsPanel = findViewById(R.id.cb_compact_apps_panel)
        swLegacyPath = findViewById(R.id.sw_legacy_path)
        cbFissionMode = findViewById(R.id.cb_fission_mode)
        swFissionAutoLayout = findViewById(R.id.sw_fission_auto_layout)
        swFissionPrecreateSlots = findViewById(R.id.sw_fission_precreate_slots)
    }

    private fun loadPreferences() {
        mPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefs = mPrefs

        // Cluster type radio
        when (ClusterPrefs.getClusterType(this)) {
            29 -> rgClusterType.check(R.id.rb_88)
            31 -> rgClusterType.check(R.id.rb_1025)
            else -> rgClusterType.check(R.id.rb_123) // 30 = Seal EU
        }

        // Pre-release toggle
        cbPrerelease.isChecked = prefs.getBoolean(PREF_OTA_PRERELEASE, DEFAULT_OTA_PRERELEASE)

        // Auto Boot Projection toggle state
        cbBootAutoStart.isChecked = ClusterPrefs.isBootAutoStartEnabled(this)

        // Category filters toggle
        cbShowCategoryFilters.isChecked = prefs.getBoolean(PREF_SHOW_CATEGORY_FILTERS, false)
        // Rolling screenshot capture for bug reports (default ON; consent still required at send)
        cbCaptureShots?.isChecked =
            com.byd.dashcast.report.ClusterShotRecorder.isEnabled(this)

        // Reconnect popup toggle (default: disabled — users find it intrusive)
        cbReconnectPopup.isChecked = prefs.getBoolean(PREF_RECONNECT_POPUP, false)

        // Quick stop toggle (default: disabled — Stop button restores origin cluster
        // with size from Settings; when enabled, Stop only sends sendInfo 18 + 0).
        cbQuickStop.isChecked = prefs.getBoolean(PREF_QUICK_STOP, false)

        // ADAS Window Fix toggle (default: disabled — projection start sends only
        // sendInfo(16) without changing the cluster screen shape).
        cbAdasWindowFix?.isChecked = ClusterPrefs.isAdasWindowFixEnabled(this)

        // Use-own-SIM toggle (default: disabled). Controls visibility of the
        // Hotspot navrail entry.
        cbUseOwnSim.isChecked = prefs.getBoolean(PREF_USE_OWN_SIM, false)

        // v1.2.45 — Compact apps panel toggle (default OFF, historical layout)
        cbCompactAppsPanel?.isChecked = prefs.getBoolean(PREF_COMPACT_APPS_PANEL, false)

        swLegacyPath.isChecked = DaemonConfig.isLegacyPathEnabled(this)
        cbFissionMode.isChecked = DaemonConfig.isFissionModeEnabled(this)
        swFissionAutoLayout.isChecked = ClusterPrefs.isFissionAutoLayout(this)
        swFissionPrecreateSlots.isChecked = ClusterPrefs.isFissionPrecreateSlots(this)
        // DiLink 5 is auto-detected via Platform — the manual override toggle
        // was removed in 1.4.23.
    }

    private fun wireListeners() {
        // Cluster type: save immediately on selection change
        rgClusterType.setOnCheckedChangeListener { _, checkedId ->
            val cmd = when (checkedId) {
                R.id.rb_88 -> 29
                R.id.rb_123 -> 30
                R.id.rb_1025 -> 31
                else -> DEFAULT_CLUSTER_TYPE
            }
            ClusterPrefs.setClusterType(this, cmd)
            AppLogger.i("SettingsActivity", "cluster type → cmd=$cmd")
        }

        // Row click delegation — RadioButtons are non-clickable (clickable=false in XML),
        // and they live INSIDE LinearLayout rows (not direct RadioGroup children), so neither
        // the RadioButton nor the RadioGroup auto-check mechanism fires on tap. We forward
        // the row click to RadioGroup.check(rb_id), which itself triggers the listener above.
        val row88: View? = findViewById(R.id.row_cluster_88)
        val row123: View? = findViewById(R.id.row_cluster_123)
        val row1025: View? = findViewById(R.id.row_cluster_1025)
        row88?.setOnClickListener { rgClusterType.check(R.id.rb_88) }
        row123?.setOnClickListener { rgClusterType.check(R.id.rb_123) }
        row1025?.setOnClickListener { rgClusterType.check(R.id.rb_1025) }

        // Pre-release checkbox
        cbPrerelease.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_OTA_PRERELEASE, isChecked) }
            AppLogger.i("SettingsActivity", "ota_include_prerelease=$isChecked")
        }

        // Auto Start Projection checkbox
        cbBootAutoStart.setOnCheckedChangeListener { _, isChecked ->
            ClusterPrefs.setBootAutoStartEnabled(this, isChecked)
        }

        // Category filters checkbox
        cbShowCategoryFilters.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_SHOW_CATEGORY_FILTERS, isChecked) }
        }

        // Screenshot capture for bug reports (setEnabled also wipes existing shots when turned off)
        cbCaptureShots?.setOnCheckedChangeListener { _, isChecked ->
            com.byd.dashcast.report.ClusterShotRecorder.setEnabled(this, isChecked)
        }

        // Reconnect popup checkbox
        cbReconnectPopup.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_RECONNECT_POPUP, isChecked) }
        }

        // Quick stop checkbox
        cbQuickStop.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_QUICK_STOP, isChecked) }
        }

        // ADAS Window Fix checkbox
        cbAdasWindowFix?.setOnCheckedChangeListener { _, isChecked ->
            ClusterPrefs.setAdasWindowFixEnabled(this, isChecked)
            AppLogger.i("SettingsActivity", "adas_window_fix=$isChecked")
        }

        // Use-own-SIM checkbox — toggles visibility of the Hotspot navrail entry
        cbUseOwnSim.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_USE_OWN_SIM, isChecked) }
            AppLogger.i("SettingsActivity", "use_own_sim=$isChecked")
        }

        // v1.2.45 — Compact apps panel checkbox. Re-evaluated by MainActivity
        // on every onResume so the change applies as soon as the user returns
        // to the home screen (no activity restart needed).
        cbCompactAppsPanel?.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_COMPACT_APPS_PANEL, isChecked) }
            AppLogger.i("SettingsActivity", "compact_apps_panel=$isChecked")
        }

        // Legacy path — takes effect immediately, no restart needed.
        swLegacyPath.setOnCheckedChangeListener { _, isChecked ->
            DaemonConfig.setLegacyPathEnabled(this, isChecked)
            AppLogger.i("SettingsActivity", "legacy_path=$isChecked")
        }
        cbFissionMode.setOnCheckedChangeListener { _, isChecked ->
            DaemonConfig.setFissionModeEnabled(this, isChecked)
            AppLogger.i("SettingsActivity", "fission_mode=$isChecked")
        }
        swFissionAutoLayout.setOnCheckedChangeListener { _, isChecked ->
            ClusterPrefs.setFissionAutoLayout(this, isChecked)
            AppLogger.i("SettingsActivity", "fission_auto_layout=$isChecked")
        }
        swFissionPrecreateSlots.setOnCheckedChangeListener { _, isChecked ->
            ClusterPrefs.setFissionPrecreateSlots(this, isChecked)
            AppLogger.i("SettingsActivity", "fission_precreate_slots=$isChecked")
        }
    }

    companion object {
        // ── SharedPreferences file (shared with MainActivity / ClusterService) ───
        // Delegates to ClusterPrefs — the single source of truth for this string.
        const val PREFS_NAME = ClusterPrefs.PREFS_NAME

        // ── Cluster type ─────────────────────────────────────────────────────────
        private const val PREF_CLUSTER_TYPE = ClusterPrefs.KEY_CLUSTER_TYPE
        private const val DEFAULT_CLUSTER_TYPE = ClusterPrefs.CLUSTER_TYPE_DEFAULT // 12.3" — Seal EU

        // ── OTA pre-release ──────────────────────────────────────────────────────
        const val PREF_OTA_PRERELEASE = "ota_include_prerelease"
        const val DEFAULT_OTA_PRERELEASE = true

        // ── Boot / UI toggles ────────────────────────────────────────────────────
        const val PREF_BOOT_AUTO_START = ClusterPrefs.KEY_BOOT_AUTO_START
        const val PREF_SHOW_CATEGORY_FILTERS = "show_category_filters"
        const val PREF_RECONNECT_POPUP = "reconnect_popup_enabled"

        // ── Stop Projection behaviour ──────────────────────────────────────────────
        const val PREF_QUICK_STOP = "quick_stop_enabled"

        // ── ADAS Window Fix ────────────────────────────────────────────────────────
        const val PREF_ADAS_WINDOW_FIX = ClusterPrefs.KEY_ADAS_WINDOW_FIX
        const val PREF_USE_OWN_SIM = "use_own_sim"

        // v1.2.45 — Compact apps panel.
        const val PREF_COMPACT_APPS_PANEL = "compact_apps_panel"

        // v1.2.43 — Hotspot integration prefs (set from HotspotActivity, read at boot)
        const val PREF_HOTSPOT_AUTOSTART_BOOT = "hotspot_autostart_boot"
        const val PREF_HOTSPOT_WATCHDOG = "hotspot_watchdog_enabled"

        // ── Recent cluster apps (shared between MainActivity and FloatingRemoteButton) ──
        const val PREF_RECENT_APPS = "recent_cluster_apps"

        // Per-app hand-drawn cluster rectangle ("l,t,r,b"), set by ClusterResizeActivity.
        // v1.8.2 — this is now the ONLY way an app is shrunk on the cluster. The global
        // overscan and the symmetric per-app inset seekbars are gone: they were applied twice
        // (launch bounds + display overscan), so the 80/50 default removed 40% of the panel
        // (INC-20260725-211405). No rectangle = the app fills the cluster.
        const val PREF_CLUSTER_RECT_PREFIX = "cluster_rect_"
    }
}
