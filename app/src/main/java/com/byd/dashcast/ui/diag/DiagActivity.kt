package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.dashcast.hud.HudDiagActivity
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.util.AppLogger
import java.io.File

/**
 * Diagnostics host — rebuilt in Kotlin, English-only by project rule (SetTextI18n exempt).
 *
 * Hosts two tools:
 *  1. **BYD APK Extraction** — interoperability analysis of the OEM cluster, specifically why the
 *     `AutoContainer` activation call returns -1 on DiLink 5.1 while returning 0/1 on the models
 *     where projection works (9/9 trinket captures). The answer is in the OEM's own
 *     `com.xdja.containerservice`, running on the tester's own vehicle. One button, one flow:
 *     inventory + runtime context, select the OEM cluster APKs ([ApkExtractionPolicy]: firmware
 *     partitions, named targets first, budgeted under Telegram's 50 MB ceiling), zip, and upload
 *     via the already-configured report channel. Runs off the UI thread with a visible progress log.
 *  2. **HUD bench (DL3)** — opens [HudDiagActivity], which now includes the `sendInfo2(4, NaviInfo)`
 *     bench: the RE finding that the uid-2000 daemon can reach the same native channel BYD's own
 *     nav app uses to drive the HUD, tested independently of CAN and of notification parsing.
 */
class DiagActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var runBtn: Button
    @Volatile private var lastWork: File? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Diagnostics"; textSize = 20f })
        runBtn = Button(this).apply {
            text = "BYD APK Extraction"
            // On DiLink 3 / DiLink 5.0 the OEM firmware has already been fully extracted; the
            // button reports that and sends nothing. Everywhere else it runs the extraction.
            setOnClickListener { if (reComplete) showReCompleteMessage() else start() }
        }
        root.addView(runBtn)

        root.addView(Button(this).apply {
            text = "HUD bench (DL3) — sendInfo2 NaviInfo test"
            setOnClickListener { startActivity(Intent(this@DiagActivity, HudDiagActivity::class.java)) }
        })

        logView = TextView(this).apply {
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
            gravity = Gravity.TOP
        }
        root.addView(ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        setContentView(root)
        if (reComplete) {
            runBtn.text = "BYD APK Extraction — complete on this platform"
            showReCompleteMessage()
        } else {
            log("Ready. Extracts the OEM cluster APKs from this vehicle and sends them for analysis.")
        }
    }

    /** DiLink 3 / DiLink 5.0 are fully mined — see [ApkExtractionPolicy.isPlatformFullyMined]. */
    private val reComplete: Boolean by lazy {
        try {
            val p = Platform.get()
            ApkExtractionPolicy.isPlatformFullyMined(
                p.isDiLink3(this), p.isDiLink5(this), Build.VERSION.SDK_INT)
        } catch (t: Throwable) {
            // Fail OPEN: if platform detection throws, keep extraction available rather than
            // silently blocking it on a platform we might still need.
            false
        }
    }

    private fun showReCompleteMessage() {
        runOnUiThread { logView.text = "" }
        log("Reverse engineering complete for this platform (DiLink 3 / DiLink 5.0).")
        log("The maximum OEM firmware has already been extracted and analysed — nothing to")
        log("collect or send. Extraction stays available on the platforms still under study.")
    }

    private fun start() {
        // Defensive: the button should not reach here on a fully-mined platform, but never run
        // (or send) if it somehow does.
        if (reComplete) { showReCompleteMessage(); return }
        runBtn.isEnabled = false
        runOnUiThread { logView.text = "" }
        log("Collecting…")
        Thread({
            val zip: File = try {
                val plan = BydApkExtractionBundle.plan(this) { line -> log(line) }
                lastWork = plan.workDir
                log("Selected ${plan.accepted.size} OEM APK(s) + ${plan.acceptedNative.size} native, " +
                    "${plan.payloadBytes / 1024} KB" +
                    (if (plan.manifestSkips.isEmpty()) "" else " (${plan.manifestSkips.size} skipped — see manifest)"))
                BydApkExtractionBundle.materialize(plan) { line -> log(line) }
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
                resetButton()
                return@Thread
            }
            log("zip ready: ${zip.name} (${zip.length() / 1024} KB)")

            if (!TelegramBugReporter.isConfigured()) {
                log("Telegram not configured — zip kept locally at:\n${zip.absolutePath}")
                resetButton()
                return@Thread
            }
            log("uploading…")
            TelegramBugReporter.send(this, zip,
                "BYD APK extraction — ${BydApkExtractionBundle.header(this)}",
                object : TelegramBugReporter.Callback {
                    override fun onSent() {
                        log("✓ sent. Done — you can leave this screen.")
                        BydApkExtractionBundle.cleanup(lastWork); lastWork = null
                        resetButton()
                    }
                    override fun onFailed(message: String) {
                        log("✗ upload failed: $message\nzip kept locally at:\n${zip.absolutePath}")
                        resetButton()
                    }
                })
        }, "byd-apk-extract").start()
    }

    @SuppressLint("SetTextI18n")
    private fun log(line: String) = runOnUiThread {
        AppLogger.i(TAG, line)
        logView.append(line + "\n")
    }

    private fun resetButton() = runOnUiThread { runBtn.isEnabled = true }

    override fun onDestroy() {
        super.onDestroy()
        // Free cache if the tester left before the upload finished.
        BydApkExtractionBundle.cleanup(lastWork)
        lastWork = null
    }

    private companion object {
        const val TAG = "DiagActivity"
    }
}
