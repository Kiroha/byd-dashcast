package com.byd.dashcast.report

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper

import androidx.core.content.edit

import com.byd.dashcast.R
import com.byd.dashcast.hud.HudCaptureSupport
import com.byd.dashcast.hud.MapNotificationListenerService
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger

import com.google.android.material.button.MaterialButton

import java.io.File

/**
 * Keyboard-free, 3-step bug reporter designed for in-car use.
 *
 * Step 1 — Category (6 large tiles, 2-column grid, tap only)
 * Step 2 — App (auto-detected from cluster display + "None" + "Other")
 * Step 3 — Issue (5 context-sensitive chips from string-arrays)
 *
 * On finish, captures a bounded log snapshot and uploads it straight to the
 * DashCast support Telegram channel. No text keyboard is ever shown.
 */
class BugWizardActivity : Activity() {

    // Views
    private lateinit var mFlipper: ViewFlipper
    private lateinit var mTvTitle: TextView
    private lateinit var mTvStatus: TextView
    private lateinit var mTvTgBanner: TextView
    private lateinit var mBtnBack: MaterialButton
    private lateinit var mDots: Array<TextView>
    private lateinit var mGridCat: GridLayout
    private lateinit var mLlApps: LinearLayout
    private lateinit var mLlIssues: LinearLayout

    // State
    private var mCategory = -1
    private var mHudArrowsAnswer = ""   // "yes"/"unknown" (HUD gate); "no" never reaches send
    private var mHudNavApp = ""         // which nav the user relies on for HUD arrows: "maps"/"waze"/"oem"/"other"
    private var mActiveNav = ""         // hint only: a known-unsupported nav process is RESIDENT (Telenav always is on EU DL3)
    private var mNavSeen = ""           // ground truth for triage: "yes (3m ago)" / "parse-fail (12s ago)" / "no"
    private var mAppPkg = ""
    private var mAppLabel = ""
    private var mSending = false

    /**
     * Re-opens an exit if the send has not reached a terminal state in time.
     *
     * Every nominal outcome ends the screen — onSent finishes, onFailed and the unconfigured
     * branch go through shareFallback which finishes, and a capture onError re-enables the
     * controls. So this is not about a permanent lock. It is about the window in between: from the
     * moment Send is tapped, Send, Back and Cancel are all disabled and onBackPressed delegates to
     * a goBack() that returns early on mSending, so there is no way off this screen at all. A slow
     * upload can hold that for the length of a connect plus a read timeout plus a retry, and the
     * person holding the phone is sitting in a car.
     *
     * After the budget the Cancel button comes back and says so. The send is not aborted — it may
     * still succeed, and its callbacks are already guarded against a destroyed Activity.
     */
    private var mSendWatchdog: Runnable? = null

    /** How long Send may hold the screen with no exit before Cancel is restored. */
    private val mSendWatchdogMs = 45_000L
    private var mTgHandle = ""

    // Step 2 (issue) — selection + optional free-text details, sent via an explicit button.
    private var mSelectedIssue: String? = null

    /**
     * Position of the chosen issue inside its category array, or -1.
     *
     * The stable identity of a symptom, and the reason it is an index rather than a slug: the nine
     * issue arrays are pinned to identical item counts across all thirteen locales — this screen
     * already indexes them BY POSITION to work at all — so position N means the same symptom in
     * every language. Inventing English slugs would mean 47 new strings that can drift out of sync
     * with the arrays they mirror; the index cannot.
     */
    private var mSelectedIssueIndex: Int = -1
    private var mDetailsField: EditText? = null
    private var mBtnSend: MaterialButton? = null
    private var mBtnCancel: View? = null
    private var mTvSelected: TextView? = null
    private val mIssueButtons = ArrayList<MaterialButton>()

    // Cluster app detection
    private var mDetectedPkg = ""
    private var mDetectedLabel = ""
    private var mDetectionDone = false
    private var mAppPagePending = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_bug_wizard)

        mFlipper = findViewById(R.id.bug_wizard_flipper)
        mTvTitle = findViewById(R.id.tv_wizard_title)
        mTvStatus = findViewById(R.id.tv_wizard_status)
        mTvTgBanner = findViewById(R.id.tv_tg_handle_banner)
        mBtnBack = findViewById(R.id.btn_wizard_back)
        mGridCat = findViewById(R.id.grid_wizard_categories)
        mLlApps = findViewById(R.id.ll_wizard_apps)
        mLlIssues = findViewById(R.id.ll_wizard_issues)
        mDots = arrayOf(
            findViewById(R.id.dot_wizard_1),
            findViewById(R.id.dot_wizard_2),
            findViewById(R.id.dot_wizard_3))

        mBtnCancel = findViewById<View>(R.id.btn_wizard_cancel)
        mBtnCancel?.setOnClickListener { finish() }
        mBtnBack.setOnClickListener { goBack() }
        mTvTgBanner.setOnClickListener { showTgHandleDialog() }

        mTgHandle = loadTgHandle()
        if (mTgHandle.isEmpty()) {
            // First use: block on dialog before showing wizard
            showTgHandleDialogThen {
                buildCategoryPage()
                showStep(0)
                detectClusterApp()
            }
        } else {
            buildCategoryPage()
            showStep(0)
            detectClusterApp()
        }
        updateTgBanner()
    }

    // ── Step 0: category ─────────────────────────────────────────────────────

    private fun buildCategoryPage() {
        val cats = resources.getStringArray(R.array.bug_categories)
        mGridCat.removeAllViews()
        for (i in cats.indices) {
            val idx = i
            val btn = makeOutlinedButton(CAT_EMOJIS[i] + "  " + cats[i])

            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(i % 2, 1f)
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            lp.width = 0
            btn.layoutParams = lp
            btn.setOnClickListener { selectCategory(idx) }
            mGridCat.addView(btn)
        }
    }

    private fun selectCategory(cat: Int) {
        mCategory = cat
        // HUD category: first gate on whether the car's HUD can even DISPLAY turn arrows. Some HUD
        // firmwares can't; then DashCast can't add them either and the report is noise (it skews the
        // debug). If the user says "no", we explain and do NOT send anything.
        if (CAT_KEYS.getOrNull(cat) == "hud") {
            probeUnsupportedNav()   // hint: is a known-unsupported OEM nav resident right now?
            askHudArrowCapability()
            return
        }
        // Not the HUD flow: drop any HUD-only answers collected on a previous pass through the wizard
        // so they are not stapled onto an unrelated report after the user goes Back and re-picks.
        mHudArrowsAnswer = ""; mHudNavApp = ""; mActiveNav = ""; mNavSeen = ""
        buildAppPage()
        showStep(1)
    }

    /** Arrow-capability gate for HUD reports (see [selectCategory]). Yes/Unknown → continue the
     *  wizard (answer recorded in the report); No → explain there is nothing to fix and finish. */
    private fun askHudArrowCapability() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bug_hud_gate_title))
            .setMessage(getString(R.string.bug_hud_gate_msg))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.bug_hud_gate_yes)) { _, _ ->
                mHudArrowsAnswer = "yes"; askHudNavApp()
            }
            .setNeutralButton(getString(R.string.bug_hud_gate_unknown)) { _, _ ->
                mHudArrowsAnswer = "unknown"; askHudNavApp()
            }
            .setNegativeButton(getString(R.string.bug_hud_gate_no)) { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.bug_hud_no_arrows_msg))
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
            .show()
    }

    /**
     * Second HUD gate: WHICH nav app the user relies on for arrows. DashCast can only source HUD
     * arrows from Google Maps / Waze notifications; the OEM built-in nav (Telenav…) delivers binary
     * AIDL we can't read — and it already draws its OWN arrows on the HUD. This closes the blind spot
     * where an arrow-capable-firmware Telenav user truthfully answered "yes" to the first gate and
     * passed straight through, filing a report DashCast can't act on (INC-20260718-114114). Maps/Waze
     * → continue; OEM/Other → explain the limitation, then finish (noise avoided) or report anyway.
     */
    private fun askHudNavApp() {
        val opts = arrayOf(
            getString(R.string.bug_hud_nav_maps),
            getString(R.string.bug_hud_nav_waze),
            getString(R.string.bug_hud_nav_oem),
            getString(R.string.bug_wizard_other_app))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bug_hud_nav_title))
            .setCancelable(false)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> { mHudNavApp = "maps"; continueHudAfterNavApp() }
                    1 -> { mHudNavApp = "waze"; continueHudAfterNavApp() }
                    2 -> { mHudNavApp = "oem"; explainUnsupportedNav() }
                    else -> { mHudNavApp = "other"; explainUnsupportedNav() }
                }
            }
            .show()
    }

    /**
     * Supported nav app chosen — but DashCast only receives turn data while a route is actually being
     * guided (the nav app posts its turn-by-turn notification only then). If no supported nav
     * notification has arrived since the app started, the driver almost certainly never started a
     * route: that is the dominant cause of "no arrow on HUD" reports and the previous two gates let it
     * through, because the HUD *is* arrow-capable and the app *is* supported. Field evidence
     * (2026-07-18): a report filed with Waze idle on its "where to?" screen at 0 km/h. Explain, then
     * let them bail or report anyway.
     */
    private fun continueHudAfterNavApp() {
        // 1) No notification access. That ALONE explains "no arrow", it is fixable by the driver, and
        //    it must NOT be reported as "you never started a route" — the first version of this gate
        //    did exactly that, cancelling the very report its notification dump was added to diagnose.
        if (!hasNotificationAccess()) {
            mNavSeen = "notif-access-off"
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.bug_hud_no_notif_access_msg))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setNeutralButton(getString(R.string.bug_hud_nav_report_anyway)) { _, _ ->
                    buildAppPage(); showStep(1)
                }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        mNavSeen = MapNotificationListenerService.navSeenSummary(mHudNavApp)
        when (MapNotificationListenerService.recentNavStatus(mHudNavApp)) {
            // A route ran and we understood it → ordinary report, no interruption.
            MapNotificationListenerService.NAV_PARSED -> { buildAppPage(); showStep(1) }

            // The nav app WAS posting guidance but nothing parsed → a DashCast PARSER bug, and the
            // most valuable "no arrow" report there is. Never gate it away: straight through.
            MapNotificationListenerService.NAV_PARSE_FAIL -> { buildAppPage(); showStep(1) }

            // Nothing recent from that app at all → most likely no route was ever started.
            else -> AlertDialog.Builder(this)
                .setMessage(getString(R.string.bug_hud_no_route_msg))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.bug_hud_no_route_understood)) { _, _ -> finish() }
                .setNeutralButton(getString(R.string.bug_hud_nav_report_anyway)) { _, _ ->
                    buildAppPage(); showStep(1)
                }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    /**
     * Whether DashCast currently holds notification-listener access. Fails OPEN: a probe error must
     * never block a legitimate bug report.
     */
    private fun hasNotificationAccess(): Boolean = try {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners")
        flat != null && flat.contains(packageName)
    } catch (t: Throwable) {
        true
    }

    /** OEM/Other nav: tell the user DashCast only mirrors Maps/Waze, then let them bail (nothing to
     *  fix) or report anyway (recorded as HudNavApp so triage sees it is an unsupported-nav case). */
    private fun explainUnsupportedNav() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.bug_hud_nav_unsupported_msg))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.bug_hud_nav_understood)) { _, _ -> finish() }
            .setNeutralButton(getString(R.string.bug_hud_nav_report_anyway)) { _, _ ->
                buildAppPage(); showStep(1)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Fire-and-forget: asks the uid-2000 daemon for the process list and records any running
     * known-unsupported nav (Telenav…). Kicked off when the HUD category is chosen so the result is
     * ready by send time. Because such navs post NO notification, this presence probe is the only way
     * a "no arrow" report can name the actual culprit. No new permission — reuses the same privileged
     * shell path as [detectClusterApp].
     */
    private fun probeUnsupportedNav() {
        if (AdbLocalClient.isAdbTransportUnreachable()) return
        AdbLocalClient.executeShellWithResult(this, "ps -A 2>/dev/null",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String) {
                    val hit = MapNotificationListenerService.firstUnsupportedNavProcess(out)
                    if (!hit.isNullOrEmpty()) runOnUiThread { mActiveNav = hit }
                }
                override fun onError(err: String) {}
            }, AdbLocalClient.PROBE_IDLE_TIMEOUT_MS)
    }

    // ── Step 1: app ──────────────────────────────────────────────────────────

    private fun buildAppPage() {
        mLlApps.removeAllViews()
        if (mDetectionDone) {
            populateAppButtons()
        } else {
            val tv = TextView(this)
            tv.setText(R.string.bug_wizard_detecting)
            tv.setTextColor(resources.getColor(R.color.md_on_surface_variant, theme))
            tv.setPadding(0, dp(16), 0, dp(16))
            mLlApps.addView(tv)
            mAppPagePending = true
        }
    }

    private fun detectClusterApp() {
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            onDetectionResult(null, null)
            return
        }
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val displayId = ClusterDisplaySelection.choose(
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.map { it.displayId }?.toIntArray() ?: intArrayOf(),
            dm?.displays?.map { it.displayId }?.toIntArray() ?: intArrayOf()
        )
        if (displayId < 0) {
            onDetectionResult(null, null)
            return
        }
        // Parse the foreground activity on the actual cluster display (#1 on legacy DL3,
        // #2 on D50F_LC, and any future non-default PRESENTATION display).
        // -A 30, not 15: on API 29 the component line sits exactly 15 lines below the display
        // header, so the old window ended on it and any stack with a second task pushed it out.
        val cmd = "dumpsys activity activities" +
            " | grep -E 'Display #$displayId|displayId=$displayId' -A 30" +
            " | grep -E '${ForegroundPackageLine.GREP_ALTERNATION}'" +
            " | head -1"
        AdbLocalClient.executeShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String) {
                val pkg = ForegroundPackageLine.parse(out.trim())
                val label = if (pkg != null) labelFor(pkg) else null
                runOnUiThread { onDetectionResult(pkg, label) }
            }
            override fun onError(err: String) {
                runOnUiThread { onDetectionResult(null, null) }
            }
        }, AdbLocalClient.PROBE_IDLE_TIMEOUT_MS)
    }

    private fun onDetectionResult(pkg: String?, label: String?) {
        mDetectedPkg = pkg ?: ""
        mDetectedLabel = label ?: ""
        mDetectionDone = true
        if (mAppPagePending) {
            mAppPagePending = false
            mLlApps.removeAllViews()
            populateAppButtons()
        }
    }

    private fun populateAppButtons() {
        // 1) Detected cluster app (highlighted with primary-color stroke).
        if (mDetectedPkg.isNotEmpty()) {
            val lbl = if (mDetectedLabel.isEmpty()) mDetectedPkg else mDetectedLabel
            val btn = makeOutlinedButton("🎯  $lbl")
            btn.strokeColor = ColorStateList.valueOf(resources.getColor(R.color.md_primary, theme))
            btn.strokeWidth = dp(2)
            btn.setOnClickListener { selectApp(mDetectedPkg, lbl) }
            mLlApps.addView(btn)
        }

        // 2) "No specific app" (system / general issue).
        val noApp = getString(R.string.bug_wizard_no_app)
        val btnNone = makeOutlinedButton("—  $noApp")
        btnNone.setOnClickListener { selectApp("", noApp) }
        mLlApps.addView(btnNone)

        // 3) "Other / Unknown".
        val otherApp = getString(R.string.bug_wizard_other_app)
        val btnOther = makeOutlinedButton("❓  $otherApp")
        btnOther.setOnClickListener { selectApp("other", otherApp) }
        mLlApps.addView(btnOther)
    }

    private fun selectApp(pkg: String, label: String) {
        mAppPkg = pkg
        mAppLabel = label
        buildIssuePage()
        showStep(2)
    }

    // ── Step 2: issue ─────────────────────────────────────────────────────────

    private fun buildIssuePage() {
        mLlIssues.removeAllViews()
        mIssueButtons.clear()
        mSelectedIssue = null
        mSelectedIssueIndex = -1

        // Issue chips: a tap now just selects (highlights) the issue; the report is sent
        // by the explicit "Send" button below, so the optional free-text can be filled first.
        val issues = resources.getStringArray(ISSUE_ARRAYS[mCategory])
        for ((index, issue) in issues.withIndex()) {
            val btn = makeOutlinedButton(issue)
            btn.isCheckable = true
            btn.setOnClickListener { onIssuePicked(issue, index, btn) }
            mLlIssues.addView(btn)
            mIssueButtons.add(btn)
        }

        // ── Optional free-text details (the user can add anything in their own words) ──
        val lbl = TextView(this)
        lbl.setText(R.string.bug_wizard_details_label)
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val lblLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lblLp.setMargins(0, dp(18), 0, dp(4))
        lbl.layoutParams = lblLp
        mLlIssues.addView(lbl)

        val details = EditText(this)
        details.setHint(R.string.bug_wizard_details_hint)
        details.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        details.minLines = 2
        details.maxLines = 5
        details.gravity = Gravity.TOP or Gravity.START
        val etLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        details.layoutParams = etLp
        mLlIssues.addView(details)
        mDetailsField = details

        // ── Selected indicator + Send button ──
        val selected = TextView(this)
        selected.setText(R.string.bug_wizard_pick_issue)
        selected.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val selLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        selLp.setMargins(0, dp(12), 0, dp(4))
        selected.layoutParams = selLp
        mLlIssues.addView(selected)
        mTvSelected = selected

        val send = MaterialButton(this) // filled style (default)
        send.setText(R.string.bug_wizard_send)
        send.minimumHeight = dp(64)
        send.isEnabled = false
        val sendLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sendLp.setMargins(0, dp(8), 0, dp(8))
        send.layoutParams = sendLp
        send.setOnClickListener { submitReport() }
        mLlIssues.addView(send)
        mBtnSend = send
    }

    /** Marks the picked issue chip (single-choice) and enables the Send button. */
    private fun onIssuePicked(issue: String, index: Int, picked: MaterialButton) {
        if (mSending) return
        mSelectedIssue = issue
        mSelectedIssueIndex = index
        for (b in mIssueButtons) {
            b.isChecked = b === picked
        }
        mTvSelected?.text = getString(R.string.bug_wizard_selected_fmt, issue)
        mBtnSend?.isEnabled = true
    }

    /**
     * Send, gated on the one question the app has to ask before diagnostics leave the car.
     *
     * The ask sits here rather than at first launch because this is the moment it means something:
     * the user has just described a problem and wants it looked at. Asked at install time, before
     * they know the feature exists, the same question gets dismissed reflexively.
     *
     * Whatever they answer, the capture runs — a refusal turns the upload into the share sheet the
     * app already offers when no channel is configured, so the report is never lost, only kept in
     * their hands.
     */
    private fun submitReport() {
        if (mSending || mSelectedIssue == null) return
        ReportConsent.askThen(this) { doSubmitReport() }
    }

    private fun doSubmitReport() {
        if (mSending || mSelectedIssue == null) return
        mSending = true
        mBtnSend?.isEnabled = false
        mBtnBack.isEnabled = false
        // AUD-005 — Cancel was the only control with no guard on mSending, whereas goBack()
        // starts with `if (mSending) return`. Disabling it closes the race window in the nominal
        // case; the lifecycle guard in onReady covers every other destruction path.
        mBtnCancel?.isEnabled = false
        armSendWatchdog()
        mTvStatus.visibility = View.VISIBLE
        mTvStatus.setText(R.string.bug_status_capturing)

        val details = mDetailsField?.text?.toString()?.trim() ?: ""
        val cats = resources.getStringArray(R.array.bug_categories)
        // The caption is a SECOND egress, not a copy of the file. It is handed to
        // TelegramBugReporter.send() at three call sites independently of the report, so the
        // redaction applied in BugReportCapture.finish() never touched it — and it is the piece
        // that carries the driver's own free text, where an address or an e-mail is most likely to
        // have been typed by hand. It is also the file's metaHeader, so redacting it here covers
        // both. A few hundred bytes on the main thread, unlike the 4 MB body.
        val rawCaption = "Category: " + cats[mCategory] +
            "\nCategoryKey: " + CAT_KEYS.getOrElse(mCategory) { "other" } +
            (if (mHudArrowsAnswer.isEmpty()) "" else "\nHudArrows: $mHudArrowsAnswer") +
            (if (mHudNavApp.isEmpty()) "" else "\nHudNavApp: $mHudNavApp") +
            // Hint only — this nav is merely RESIDENT, which on EU DiLink 3 is always true of Telenav.
            // It does NOT mean it is the app guiding; read NavSeen for that.
            (if (mActiveNav.isEmpty()) "" else "\nActiveNav: $mActiveNav (resident, unsupported — hint only)") +
            // Ground truth for triage: "yes" a route ran and parsed · "parse-fail" the nav app WAS
            // guiding but DashCast could not parse it (a parser bug) · "stale" a route ran, long ago ·
            // "no" nothing · "notif-access-off" DashCast lacks notification access.
            (if (mNavSeen.isEmpty()) "" else "\nNavSeen: $mNavSeen") +
            "\nApp: " + (if (mAppPkg.isEmpty()) mAppLabel else "$mAppLabel ($mAppPkg)") +
            "\nIssue: " + mSelectedIssue +
            // The label is what a human reads; this is what a triager can group by. Without it the
            // same symptom reported in French, Arabic and Polish is three unrelated strings, and
            // the category line next to it already carries a stable key for exactly this reason.
            "\nIssueKey: " + CAT_KEYS.getOrElse(mCategory) { "other" } + "/" + mSelectedIssueIndex +
            (if (details.isEmpty()) "" else "\nDetails: $details") +
            "\nDevice: " + BugReportCapture.deviceLine() +
            "\nVersion: " + BugReportCapture.versionLine() +
            (if (mTgHandle.isEmpty()) "" else "\nTelegram: $mTgHandle")
        // The handle survives on its own line: Redactor keeps the `Telegram:` header verbatim so
        // the maintainer can still answer the report, and tokenises the same string only where it
        // reappears elsewhere.
        val caption = Redactor.redact(rawCaption).text

        BugReportCapture.capture(this, caption, object : BugReportCapture.Callback {
            override fun onReady(file: File) {
                // AUD-005 — lifecycle guard. This callback is posted to the main thread by
                // BugReportCapture AFTER a capture that takes tens of seconds (two logcat passes
                // plus ~20 dumpsys). If the user left the wizard meanwhile, the Activity is gone
                // and building a Dialog on it throws BadTokenException inside a bare main-looper
                // Runnable — uncaught, so the process dies exactly when the user is reporting a
                // bug. Same guard as bundleShotsThenDeliver() below.
                if (isFinishing || isDestroyed) {
                    // Consent for the screenshots can no longer be collected, so the safe default
                    // is to NOT attach them: deliver the report alone, headless, using the
                    // application context so no finished Activity is touched. The .txt is already
                    // on disk at this point, so nothing is lost either way.
                    if (TelegramBugReporter.isConfigured()) {
                        TelegramBugReporter.send(applicationContext, file, caption,
                            object : TelegramBugReporter.Callback {
                                override fun onSent() {}
                                override fun onFailed(message: String) {
                                    AppLogger.w(TAG, "headless report upload failed: $message")
                                }
                            })
                    } else {
                        AppLogger.w(TAG, "wizard gone before consent — report kept on disk: "
                            + file.absolutePath)
                    }
                    return
                }
                // Privacy gate (mirrors the HUD raw-recorder H3 pattern): if the rolling screenshot
                // recorder is on, ask — per send — whether to attach the recent captures, which may
                // show the user's screen (map, destination…). Consent is NOT persisted.
                if (ClusterShotRecorder.isEnabled(this@BugWizardActivity)) {
                    AlertDialog.Builder(this@BugWizardActivity)
                        .setTitle(R.string.bug_shots_consent_title)
                        .setMessage(R.string.bug_shots_consent_msg)
                        .setCancelable(false)
                        .setPositiveButton(R.string.bug_shots_consent_yes) { _, _ ->
                            bundleShotsThenDeliver(file, caption)
                        }
                        .setNegativeButton(R.string.bug_shots_consent_no) { _, _ ->
                            deliverReport(file, caption)
                        }
                        .show()
                } else {
                    deliverReport(file, caption)
                }
            }

            override fun onError(message: String, partial: File?) {
                disarmSendWatchdog()
                mSending = false
                mBtnBack.isEnabled = true
                mBtnSend?.isEnabled = true
                mBtnCancel?.isEnabled = true
                mTvStatus.text = getString(R.string.bug_status_error_fmt, message)
                if (partial != null) shareFallback(partial)
            }
        })
    }

    /** Uploads [file] (a report .txt or a report+shots .zip) via the bot, else the share sheet. */
    private fun deliverReport(file: File, caption: String) {
        if (TelegramBugReporter.isConfigured()) {
            mTvStatus.setText(R.string.bug_status_sending)
            TelegramBugReporter.send(this, file, caption, object : TelegramBugReporter.Callback {
                override fun onSent() {
                    disarmSendWatchdog()
                    Toast.makeText(this@BugWizardActivity, R.string.bug_sent_ok, Toast.LENGTH_LONG).show()
                    finish()
                }
                override fun onFailed(message: String) {
                    AppLogger.w(TAG, "bot upload failed: $message")
                    shareFallback(file)
                }
            })
        } else {
            shareFallback(file)
        }
    }

    /**
     * Pulls the recent screenshots off the device (via the daemon — the app can't read
     * /data/local/tmp on A13), zips them together with the report, then delivers the zip. All the
     * blocking I/O runs off the main thread; on any failure it falls back to sending the report
     * alone. Consent to include the shots was already given by the caller.
     */
    private fun bundleShotsThenDeliver(reportFile: File, caption: String) {
        mTvStatus.setText(R.string.bug_status_sending)
        Thread({
            var toSend = reportFile
            try {
                // Keep the "byd_bugreport_" prefix on the zip (work dir name = report base name) so
                // the Telegram pull script's filename filter matches it exactly like the plain .txt.
                val work = File(cacheDir, reportFile.nameWithoutExtension)
                if (work.exists()) work.deleteRecursively()
                work.mkdirs()
                reportFile.copyTo(File(work, reportFile.name), overwrite = true)
                val n = ClusterShotRecorder.pullShotsInto(this, work)
                if (n > 0) {
                    // Say what is actually in the envelope, in the envelope. The redaction footer
                    // was written before this method knew a single shot existed, so without this
                    // the file's own privacy statement describes half of what is being sent.
                    // Appended to the STAGED copy, which is recreated on every attempt, so a retry
                    // cannot stack the note twice.
                    try {
                        File(work, reportFile.name)
                            .appendText(ReportAttachmentNote.forShots(n))
                    } catch (t: Throwable) {
                        AppLogger.w(TAG, "could not note attachments in the report: ${t.message}")
                    }
                    // The archive must NOT land beside the work directory. cacheDir is not declared
                    // in file_paths.xml, so FileProvider.getUriForFile throws on anything inside it
                    // — and shareFallback swallows that exception, shows an error toast and calls
                    // finish(). The report was simply lost. Since the screenshot recorder defaults
                    // to ON, that was the MAJORITY path of this screen, not an edge case.
                    ReportStore.prune(this)
                    toSend = HudCaptureSupport.zipDir(
                        work, File(ReportStore.dir(this), work.name + ".zip"))
                    AppLogger.i(TAG, "bug bundle: report + $n screenshot(s) → ${toSend.name}")
                    // Shots have been captured into the zip → wipe the device-side originals now.
                    ClusterShotRecorder.clear(this)
                } else {
                    AppLogger.i(TAG, "bug bundle: no screenshots available — sending report only")
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "bug bundle failed (${t.message}) — sending report only")
                toSend = reportFile
            } finally {
                // The staging copy has served its purpose either way. Left behind it doubled the
                // footprint of every bundle in cacheDir, for no reader.
                try { File(cacheDir, reportFile.nameWithoutExtension).deleteRecursively() }
                catch (_: Throwable) { /* best-effort */ }
            }
            val finalFile = toSend
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    deliverReport(finalFile, caption)
                } else if (TelegramBugReporter.isConfigured()) {
                    // User left the wizard mid-bundle — still deliver (they had tapped send),
                    // headless, so the report isn't lost and no finished Activity is touched.
                    TelegramBugReporter.send(applicationContext, finalFile, caption,
                        object : TelegramBugReporter.Callback {
                            override fun onSent() {}
                            override fun onFailed(message: String) {
                                AppLogger.w(TAG, "headless bundle upload failed: $message")
                            }
                        })
                }
            }
        }, "bug-bundle").start()
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun showStep(step: Int) {
        mFlipper.displayedChild = step
        val titleIds = intArrayOf(
            R.string.bug_wizard_step_category,
            R.string.bug_wizard_step_app,
            R.string.bug_wizard_step_issue)
        mTvTitle.setText(titleIds[step])
        mBtnBack.visibility = if (step > 0) View.VISIBLE else View.GONE
        for (i in mDots.indices) {
            mDots[i].alpha = if (i == step) 1f else 0.3f
        }
    }

    private fun goBack() {
        if (mSending) return
        val cur = mFlipper.displayedChild
        if (cur > 0) showStep(cur - 1) else finish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDestroy() {
        // The posted Runnable holds this Activity; a send that outlives the screen must not.
        disarmSendWatchdog()
        super.onDestroy()
    }

    override fun onBackPressed() {
        goBack()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeOutlinedButton(text: String): MaterialButton {
        val btn = MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle)
        btn.text = text
        btn.minimumHeight = dp(72)
        btn.setPadding(dp(16), dp(10), dp(16), dp(10))
        btn.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(5), 0, dp(5))
        btn.layoutParams = lp
        return btn
    }

    private fun dp(dp: Int): Int = Math.round(dp * resources.displayMetrics.density)

    @Suppress("DEPRECATION")
    private fun labelFor(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    // ── Telegram handle ───────────────────────────────────────────────────────

    private fun loadTgHandle(): String =
        getSharedPreferences(PREFS_BUG, Context.MODE_PRIVATE).getString(PREF_TG_HANDLE, "") ?: ""

    private fun saveTgHandle(handle: String) {
        getSharedPreferences(PREFS_BUG, Context.MODE_PRIVATE).edit {
            putString(PREF_TG_HANDLE, handle)
        }
    }

    private fun updateTgBanner() {
        if (mTgHandle.isEmpty()) {
            mTvTgBanner.setText(R.string.bug_tg_banner_unset)
            mTvTgBanner.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
        } else {
            mTvTgBanner.text = getString(R.string.bug_tg_banner_set, mTgHandle)
            mTvTgBanner.setTextColor(resources.getColor(R.color.md_on_surface_variant, theme))
        }
    }

    /** Shows the dialog with the full explanation message (first use). Calls [then] on confirm or skip. */
    private fun showTgHandleDialogThen(then: Runnable) {
        showTgHandleDialogInternal(then)
    }

    /** Shows the dialog for subsequent edits (no mandatory callback). */
    private fun showTgHandleDialog() {
        showTgHandleDialogInternal(null)
    }

    private fun showTgHandleDialogInternal(onDismiss: Runnable?) {
        val et = EditText(this)
        et.setHint(R.string.bug_tg_hint)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        et.isSingleLine = true
        if (mTgHandle.isNotEmpty()) et.setText(mTgHandle)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = dp(24)
        container.setPadding(pad, dp(8), pad, dp(4))
        container.addView(et)

        AlertDialog.Builder(this)
            .setTitle(R.string.bug_tg_title)
            .setMessage(R.string.bug_tg_message)
            .setView(container)
            .setPositiveButton(R.string.bug_tg_confirm) { _, _ ->
                var raw = et.text.toString().trim()
                if (raw.isNotEmpty() && !raw.startsWith("@")) raw = "@$raw"
                mTgHandle = raw
                saveTgHandle(mTgHandle)
                updateTgBanner()
                onDismiss?.run()
            }
            .setNegativeButton(R.string.bug_tg_skip) { _, _ ->
                onDismiss?.run()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Says what happened before handing the report over.
     *
     * This is reached on a refusal and on every upload failure, and it used to fire the chooser and
     * call finish() with the status line still reading "Sending…". The tester saw a share sheet
     * appear over a screen claiming to be uploading, with nothing naming the file or the reason —
     * indistinguishable from a bug. The toast is the whole difference between "it failed and here
     * is your report" and "something odd happened".
     */
    private fun armSendWatchdog() {
        disarmSendWatchdog()
        val r = Runnable {
            mSendWatchdog = null
            if (isFinishing || isDestroyed) return@Runnable
            // Cancel only. Send stays disabled: a second submission while the first may still be
            // in flight is how a report gets delivered twice.
            mBtnCancel?.isEnabled = true
            mTvStatus.text = getString(R.string.bug_status_slow)
            AppLogger.w(TAG, "send exceeded ${mSendWatchdogMs}ms — Cancel restored")
        }
        mSendWatchdog = r
        mTvStatus.postDelayed(r, mSendWatchdogMs)
    }

    private fun disarmSendWatchdog() {
        mSendWatchdog?.let { mTvStatus.removeCallbacks(it) }
        mSendWatchdog = null
    }

    private fun shareFallback(file: File) {
        disarmSendWatchdog()
        try {
            mTvStatus.text = getString(R.string.bug_kept_locally_fmt, file.name)
            Toast.makeText(this, getString(R.string.bug_kept_locally_fmt, file.name),
                Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { /* never let a message cost the report */ }
        try {
            AppLogger.shareFile(this, file,
                getString(R.string.bug_share_subject),
                getString(R.string.bug_share_chooser))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.bug_status_error_fmt, e.message.orEmpty()),
                Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val TAG = "BugWizardActivity"
        private const val PREFS_BUG = "dashcast_bug_report"
        private const val PREF_TG_HANDLE = "tg_handle"

        // Category emojis — order must match the bug_categories string-array.
        private val CAT_EMOJIS = arrayOf("🧭", "📺", "📱", "🔊", "🔗", "❄️", "🖥️", "❓")

        // Canonical (stable, English) category keys for triage/analysis — order must match the
        // bug_categories string-array. Written to the report as "CategoryKey:" so incident analysis
        // can group across the TRANSLATED labels the user actually sees (Spiegelung/Yansıtma/…).
        private val CAT_KEYS = arrayOf("hud", "mirror", "app", "sound", "connect", "freeze", "simple", "other")

        // Issue string-arrays — order must match the bug_categories string-array.
        private val ISSUE_ARRAYS = intArrayOf(
            R.array.bug_issues_hud,
            R.array.bug_issues_mirror,
            R.array.bug_issues_app,
            R.array.bug_issues_sound,
            R.array.bug_issues_connect,
            R.array.bug_issues_freeze,
            R.array.bug_issues_simple,
            R.array.bug_issues_other)

    }
}
