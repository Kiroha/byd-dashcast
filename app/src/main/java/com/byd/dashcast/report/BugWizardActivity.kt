package com.byd.dashcast.report

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
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
    private var mAppPkg = ""
    private var mAppLabel = ""
    private var mSending = false
    private var mTgHandle = ""

    // Step 2 (issue) — selection + optional free-text details, sent via an explicit button.
    private var mSelectedIssue: String? = null
    private var mDetailsField: EditText? = null
    private var mBtnSend: MaterialButton? = null
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

        findViewById<View>(R.id.btn_wizard_cancel).setOnClickListener { finish() }
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
        buildAppPage()
        showStep(1)
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
        // Parse foreground activity on displayId=1 (cluster display).
        val cmd = "dumpsys activity activities" +
            " | grep -E 'displayId=1' -A 10" +
            " | grep 'realActivity'" +
            " | head -1"
        AdbLocalClient.executeShellWithResult(this, cmd, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String) {
                val pkg = parseRealActivity(out.trim())
                val label = if (pkg != null) labelFor(pkg) else null
                runOnUiThread { onDetectionResult(pkg, label) }
            }
            override fun onError(err: String) {
                runOnUiThread { onDetectionResult(null, null) }
            }
        })
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

        // Issue chips: a tap now just selects (highlights) the issue; the report is sent
        // by the explicit "Send" button below, so the optional free-text can be filled first.
        val issues = resources.getStringArray(ISSUE_ARRAYS[mCategory])
        for (issue in issues) {
            val btn = makeOutlinedButton(issue)
            btn.isCheckable = true
            btn.setOnClickListener { onIssuePicked(issue, btn) }
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
    private fun onIssuePicked(issue: String, picked: MaterialButton) {
        if (mSending) return
        mSelectedIssue = issue
        for (b in mIssueButtons) {
            b.isChecked = b === picked
        }
        mTvSelected?.text = getString(R.string.bug_wizard_selected_fmt, issue)
        mBtnSend?.isEnabled = true
    }

    private fun submitReport() {
        if (mSending || mSelectedIssue == null) return
        mSending = true
        mBtnSend?.isEnabled = false
        mBtnBack.isEnabled = false
        mTvStatus.visibility = View.VISIBLE
        mTvStatus.setText(R.string.bug_status_capturing)

        val details = mDetailsField?.text?.toString()?.trim() ?: ""
        val cats = resources.getStringArray(R.array.bug_categories)
        val caption = "Category: " + cats[mCategory] +
            "\nCategoryKey: " + CAT_KEYS.getOrElse(mCategory) { "other" } +
            "\nApp: " + (if (mAppPkg.isEmpty()) mAppLabel else "$mAppLabel ($mAppPkg)") +
            "\nIssue: " + mSelectedIssue +
            (if (details.isEmpty()) "" else "\nDetails: $details") +
            "\nDevice: " + BugReportCapture.deviceLine() +
            "\nVersion: " + BugReportCapture.versionLine() +
            (if (mTgHandle.isEmpty()) "" else "\nTelegram: $mTgHandle")

        BugReportCapture.capture(this, caption, object : BugReportCapture.Callback {
            override fun onReady(file: File) {
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
                mSending = false
                mBtnBack.isEnabled = true
                mBtnSend?.isEnabled = true
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
                    toSend = HudCaptureSupport.zipDir(work)
                    AppLogger.i(TAG, "bug bundle: report + $n screenshot(s) → ${toSend.name}")
                    // Shots have been captured into the zip → wipe the device-side originals now.
                    ClusterShotRecorder.clear(this)
                } else {
                    AppLogger.i(TAG, "bug bundle: no screenshots available — sending report only")
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "bug bundle failed (${t.message}) — sending report only")
                toSend = reportFile
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

    private fun shareFallback(file: File) {
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

        /** Parses "realActivity=com.waze/.FreeMapAppActivity" → "com.waze". */
        private fun parseRealActivity(line: String): String? {
            val eq = line.indexOf("realActivity=")
            if (eq < 0) return null
            val s = line.substring(eq + "realActivity=".length).trim()
            val slash = s.indexOf('/')
            return if (slash > 0) s.substring(0, slash) else (if (s.isEmpty()) null else s)
        }
    }
}
