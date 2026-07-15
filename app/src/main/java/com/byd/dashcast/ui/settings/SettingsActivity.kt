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
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
 * Currently covers:
 *  1. Cluster screen type (sendInfo cmd: 29 = 8.8", 30 = 12.3" Seal EU, 31 = 10.25")
 *  2. Display overscan margins (left/right and top/bottom in pixels)
 *     Applied via: wm overscan LEFT,TOP,RIGHT,BOTTOM -d <cluster_display_id>
 */
@SuppressLint("SetTextI18n")
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var rgClusterType: RadioGroup
    private lateinit var sbInsetH: SeekBar
    private lateinit var sbInsetV: SeekBar
    private lateinit var tvInsetHValue: TextView
    private lateinit var tvInsetVValue: TextView
    private lateinit var btnApply: Button
    private lateinit var btnReset: Button
    private lateinit var tvResult: TextView
    private var tvOverscanSectionTitle: View? = null
    private var cardOverscan: View? = null
    // Note: fields below are typed as CompoundButton so the layout can use either
    // <CheckBox> or <MaterialSwitch> without breaking the cast. Both inherit
    // setChecked/isChecked/setOnCheckedChangeListener from CompoundButton.
    private lateinit var cbPrerelease: CompoundButton
    private lateinit var cbVisualMode: CompoundButton
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
    private lateinit var llSlidersMode: View
    private lateinit var llVisualMode: View
    private var flSafeZone: View? = null
    private lateinit var btnHMinus: Button
    private lateinit var btnHPlus: Button
    private lateinit var btnVMinus: Button
    private lateinit var btnVPlus: Button

    @Volatile
    private var mDestroyed = false
    private lateinit var mPrefs: SharedPreferences
    private var mSafeZoneParams: ViewGroup.MarginLayoutParams? = null

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
        applyDiLink2OverscanGuard()

        // Mockup top-bar Apply button: mirrors the in-card Apply action.
        val btnApplyTop: View? = findViewById(R.id.btn_apply_top)
        btnApplyTop?.setOnClickListener { btnApply.performClick() }

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
        sbInsetH = findViewById(R.id.sb_inset_h)
        sbInsetV = findViewById(R.id.sb_inset_v)
        tvInsetHValue = findViewById(R.id.tv_inset_h_value)
        tvInsetVValue = findViewById(R.id.tv_inset_v_value)
        btnApply = findViewById(R.id.btn_apply_overscan)
        btnReset = findViewById(R.id.btn_reset_overscan)
        tvResult = findViewById(R.id.tv_overscan_result)
        tvOverscanSectionTitle = findViewById(R.id.tv_overscan_section_title)
        cardOverscan = findViewById(R.id.card_overscan)
        cbPrerelease = findViewById(R.id.cb_prerelease)
        cbVisualMode = findViewById(R.id.cb_visual_mode)
        cbBootAutoStart = findViewById(R.id.cb_boot_auto_start)
        llSlidersMode = findViewById(R.id.ll_sliders_mode)
        llVisualMode = findViewById(R.id.ll_visual_overscan)
        btnHMinus = findViewById(R.id.btn_h_minus)
        btnHPlus = findViewById(R.id.btn_h_plus)
        btnVMinus = findViewById(R.id.btn_v_minus)
        btnVPlus = findViewById(R.id.btn_v_plus)
        flSafeZone = findViewById(R.id.fl_safe_zone)
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

        // Overscan sliders
        val h = prefs.getInt(PREF_INSET_H, DEFAULT_INSET_H)
        val v = prefs.getInt(PREF_INSET_V, DEFAULT_INSET_V)
        sbInsetH.progress = h
        sbInsetV.progress = v
        tvInsetHValue.text = "$h px"
        tvInsetVValue.text = "$v px"

        // Pre-release toggle
        cbPrerelease.isChecked = prefs.getBoolean(PREF_OTA_PRERELEASE, DEFAULT_OTA_PRERELEASE)

        // Visual Mode toggle state
        val visualMode = prefs.getBoolean(PREF_VISUAL_OVERSCAN_MODE, false)
        cbVisualMode.isChecked = visualMode
        updateVisualModeState(visualMode)
        updateVisualMockup()

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

        // H slider
        sbInsetH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                tvInsetHValue.text = "$value px"
                if (fromUser) saveInsets(value, sbInsetV.progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // V slider
        sbInsetV.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                tvInsetVValue.text = "$value px"
                if (fromUser) saveInsets(sbInsetH.progress, value)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Apply button
        btnApply.setOnClickListener { applyOverscan() }

        // Reset button
        btnReset.setOnClickListener {
            sbInsetH.progress = DEFAULT_INSET_H
            sbInsetV.progress = DEFAULT_INSET_V
            tvInsetHValue.text = "$DEFAULT_INSET_H px"
            tvInsetVValue.text = "$DEFAULT_INSET_V px"
            saveInsets(DEFAULT_INSET_H, DEFAULT_INSET_V)
            applyOverscan()
        }

        // Pre-release checkbox
        cbPrerelease.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_OTA_PRERELEASE, isChecked) }
            AppLogger.i("SettingsActivity", "ota_include_prerelease=$isChecked")
        }

        // Visual Mode checkbox
        cbVisualMode.setOnCheckedChangeListener { _, isChecked ->
            mPrefs.edit { putBoolean(PREF_VISUAL_OVERSCAN_MODE, isChecked) }
            updateVisualModeState(isChecked)
        }

        // Auto Start Projection checkbox
        cbBootAutoStart.setOnCheckedChangeListener { _, isChecked ->
            ClusterPrefs.setBootAutoStartEnabled(this, isChecked)
        }

        val dpadListener = View.OnClickListener { v ->
            var h = sbInsetH.progress
            var valV = sbInsetV.progress
            if (v === btnHMinus) h = maxOf(0, h - 10)
            if (v === btnHPlus) h = minOf(200, h + 10)
            if (v === btnVMinus) valV = maxOf(0, valV - 10)
            if (v === btnVPlus) valV = minOf(200, valV + 10)

            sbInsetH.progress = h
            sbInsetV.progress = valV
            updateVisualMockup()
            // To keep it real-time as requested:
            applyOverscan()
        }

        btnHMinus.setOnClickListener(dpadListener)
        btnHPlus.setOnClickListener(dpadListener)
        btnVMinus.setOnClickListener(dpadListener)
        btnVPlus.setOnClickListener(dpadListener)

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

    private fun updateVisualModeState(visual: Boolean) {
        llSlidersMode.visibility = if (visual) View.GONE else View.VISIBLE
        llVisualMode.visibility = if (visual) View.VISIBLE else View.GONE
    }

    private fun updateVisualMockup() {
        val sz = flSafeZone ?: return
        val h = sbInsetH.progress
        val v = sbInsetV.progress
        var p = mSafeZoneParams
        if (p == null) {
            p = sz.layoutParams as ViewGroup.MarginLayoutParams
            mSafeZoneParams = p
        }
        // Scale logic: Mockup is 320x120. Real cluster is 1920x720. Scale is 1/6.
        p.leftMargin = (h / 6f).toInt()
        p.rightMargin = (h / 6f).toInt()
        p.topMargin = (v / 6f).toInt()
        p.bottomMargin = (v / 6f).toInt()
        sz.requestLayout()
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private fun saveInsets(h: Int, v: Int) {
        mPrefs.edit {
            putInt(PREF_INSET_H, h)
            putInt(PREF_INSET_V, v)
        }
    }

    /**
     * Sends "wm overscan H,V,H,V -d <clusterId>" to the cluster display.
     * The cluster display id is resolved dynamically via [resolveClusterDisplayId]
     * (was hardcoded `-d 1` until build 189). If no cluster is detected
     * (e.g. DL2 without secondary display), the call is aborted with a user-visible
     * error — critical to prevent the BYD MTK ROM from silently applying the
     * overscan to display 0 (the main screen), as reported in the field on 22/05/2026.
     * The result is shown in tvResult.
     */
    private fun applyOverscan() {
        val h = sbInsetH.progress
        val v = sbInsetV.progress
        saveInsets(h, v)

        // DL2 HARD GUARD — never reach a wm command on DL2 even if the card
        // somehow got displayed (defence in depth on top of applyDiLink2OverscanGuard
        // and AdbLocalClient.blockDiLink2Resize). The MTK ROM silently falls back
        // to display 0 on missing -d N → shrinks the main screen.
        if (Platform.get().isDiLink2(this)) {
            tvResult.visibility = View.VISIBLE
            tvResult.setText(R.string.settings_warn_dl2_no_margins)
            AppLogger.w("SettingsActivity", "applyOverscan aborted: DL2 platform (no cluster display)")
            return
        }

        val clusterId = resolveClusterDisplayId()
        if (clusterId < 0) {
            tvResult.visibility = View.VISIBLE
            tvResult.setText(R.string.settings_warn_no_cluster)
            AppLogger.w(
                "SettingsActivity",
                "applyOverscan aborted: no cluster display found (h=$h v=$v)"
            )
            return
        }

        val cmd = "wm overscan $h,$v,$h,$v -d $clusterId"
        AppLogger.i("SettingsActivity", "applyOverscan → $cmd")

        ShellGateway.execShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(report: String) {
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    tvResult.visibility = View.VISIBLE
                    tvResult.text = getString(R.string.settings_overscan_applied, h, v)
                    AppLogger.i("SettingsActivity", "overscan applied OK h=$h v=$v")
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    tvResult.visibility = View.VISIBLE
                    tvResult.text = getString(R.string.settings_error_prefix_fmt, error.trim())
                    AppLogger.e("SettingsActivity", "overscan error: $error")
                }
            }
        })
    }

    /**
     * Resolves the cluster display id dynamically.
     *
     * Returns the id of the first non-default display reported by [DisplayManager]
     * (PRESENTATION category first, then any non-default). Returns `-1` when no
     * cluster display is present (typically DL2, which has only display 0). This
     * guard is critical: on DL2 the previous hardcoded `-d 1` was silently applied
     * to display 0 by the BYD MTK ROM, shrinking the main screen (field report
     * 22/05/2026).
     */
    private fun resolveClusterDisplayId(): Int {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return -1
            val presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            if (presentations != null) {
                for (d in presentations) {
                    if (d.displayId != Display.DEFAULT_DISPLAY) return d.displayId
                }
            }
            val all = dm.displays
            if (all != null) {
                for (d in all) {
                    if (d.displayId != Display.DEFAULT_DISPLAY) return d.displayId
                }
            }
        } catch (t: Throwable) {
            AppLogger.w("SettingsActivity", "resolveClusterDisplayId failed: " + t.message)
        }
        return -1
    }

    /**
     * Hides the overscan margins section on DL2 (alps / k65v1 / API 28) because the
     * platform has no cluster display — the previous hardcoded `wm overscan -d 1`
     * was silently applied to display 0 by the MTK ROM, shrinking the main screen.
     * Also hides on any device where no secondary display is detected at activity
     * launch (defensive — covers DL3/DL5 in a state where the cluster is not connected).
     */
    private fun applyDiLink2OverscanGuard() {
        var hide = false
        var reason: String? = null
        try {
            if (Platform.get().isDiLink2(this)) {
                hide = true
                reason = "DL2 platform detected (alps/k65v1) — no cluster display"
            } else if (resolveClusterDisplayId() < 0) {
                hide = true
                reason = "no secondary display present at launch"
            }
        } catch (t: Throwable) {
            AppLogger.w("SettingsActivity", "applyDiLink2OverscanGuard probe failed: " + t.message)
        }
        if (hide) {
            tvOverscanSectionTitle?.visibility = View.GONE
            cardOverscan?.visibility = View.GONE
            AppLogger.i("SettingsActivity", "Overscan section hidden: $reason")
        }
    }

    companion object {
        // ── SharedPreferences file (shared with MainActivity / ClusterService) ───
        // Delegates to ClusterPrefs — the single source of truth for this string.
        const val PREFS_NAME = ClusterPrefs.PREFS_NAME

        // ── Cluster type ─────────────────────────────────────────────────────────
        private const val PREF_CLUSTER_TYPE = ClusterPrefs.KEY_CLUSTER_TYPE
        private const val DEFAULT_CLUSTER_TYPE = ClusterPrefs.CLUSTER_TYPE_DEFAULT // 12.3" — Seal EU

        // ── Overscan inset ───────────────────────────────────────────────────────
        const val PREF_INSET_H = "overscan_inset_h"
        const val PREF_INSET_V = "overscan_inset_v"
        const val DEFAULT_INSET_H = 80
        const val DEFAULT_INSET_V = 50

        // ── OTA pre-release ──────────────────────────────────────────────────────
        const val PREF_OTA_PRERELEASE = "ota_include_prerelease"
        const val DEFAULT_OTA_PRERELEASE = true

        // ── Boot / UI toggles ────────────────────────────────────────────────────
        const val PREF_BOOT_AUTO_START = ClusterPrefs.KEY_BOOT_AUTO_START
        const val PREF_SHOW_CATEGORY_FILTERS = "show_category_filters"
        const val PREF_RECONNECT_POPUP = "reconnect_popup_enabled"
        private const val PREF_VISUAL_OVERSCAN_MODE = "visual_overscan_mode"

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

        // ── Per-app inset key prefixes (shared between MainActivity and ClusterService) ──
        const val PREF_INSET_H_PREFIX = "inset_h_"
        const val PREF_INSET_V_PREFIX = "inset_v_"

        // Per-app hand-drawn cluster rectangle ("l,t,r,b"), set by ClusterResizeActivity.
        // Takes precedence over the symmetric seekbar insets when present (the last tool
        // used wins: ClusterControlCoordinator removes it when the seekbar Apply is used).
        const val PREF_CLUSTER_RECT_PREFIX = "cluster_rect_"
    }
}
