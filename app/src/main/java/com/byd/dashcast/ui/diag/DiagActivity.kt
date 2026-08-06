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
import com.byd.dashcast.proxy.ProxyClient
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
    private lateinit var probeBtn: Button
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

        probeBtn = Button(this).apply {
            text = "OEM cluster probes (read-only)"
            setOnClickListener { runOemProbes() }
        }
        root.addView(probeBtn)

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

    /**
     * Read-only probes for the DiLink 5.0 "OEM re-fronts its own map over the projected app"
     * investigation (INC-20260804-171617). Answers the questions the existing bug report cannot:
     * which package owns HOME (so we know what would break if anything were ever disabled), which
     * cluster map client the OEM is configured to use, whether ADB-over-TCP is set to survive a
     * power cycle, and whether uid 2000 may change component state at all.
     *
     * Every command is a READ of device state. The one apparent exception, `pm default-state`, is
     * deliberately aimed at **our own** component: it answers "may uid 2000 change component state
     * on this ROM?" (a SecurityException answers it) without touching anything that belongs to the
     * OEM. Pointing it at the OEM's cluster map — as an earlier draft did — would have been a real
     * write: `pm default-state` clears any per-user override AND force-stops the owning process,
     * i.e. it would have killed the live OEM cluster map on a tester's car in the name of a
     * "read-only" probe.
     *
     * Runs off the UI thread; output goes to the on-screen log and to the journal, so it lands in
     * the next bug report.
     */
    private fun runOemProbes() {
        probeBtn.isEnabled = false
        runOnUiThread { logView.text = "" }
        log("Running read-only OEM cluster probes — nothing is modified.")
        Thread({
            val probes = listOf(
                "HOME resolution" to
                    "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.HOME",
                "HOME candidates" to
                    "cmd package query-activities -a android.intent.action.MAIN " +
                    "-c android.intent.category.HOME 2>/dev/null | grep -iE 'packageName|name=' | head -40",
                "OEM cluster map client" to "settings get global byd_map_package",
                "ADB persistence props" to "getprop | grep -iE 'adb|tcp.port'",
                "Component-state permission" to
                    "pm default-state com.byd.dashcast/com.byd.dashcast.ui.diag.DiagActivity 2>&1",
                "Display 0 tasks" to
                    "dumpsys activity activities 2>/dev/null | grep -A 40 'Display #0' | " +
                    "grep -E 'Task\\{|type=|realActivity|topResumed' | head -40",
                "Cluster displays" to
                    "dumpsys display 2>/dev/null | grep -oE '\"[a-z_]*fission[A-Za-z0-9_]*\"' | sort -u",
                "OEM projection processes" to
                    "ps -A 2>/dev/null | grep -iE 'amapservice|launchermap|containerservice|fission'"
            )
            try {
                for ((label, cmd) in probes) {
                    log("── $label ──")
                    val out = try {
                        ProxyClient.runShell(cmd) ?: ""
                    } catch (t: Throwable) {
                        "ERR ${t.javaClass.simpleName}: ${t.message}"
                    }
                    log(if (out.isBlank()) "(empty)" else out.trim())
                }
                log("")
                log("Done. Send a bug report now so these results reach us.")
            } finally {
                // Always re-enable: runShell has no client-side timeout and can take the blocking
                // reconnect path (~31 s) on a car with no daemon, and anything thrown outside the
                // per-probe catch would otherwise leave the button dead until the screen is recreated.
                runOnUiThread { probeBtn.isEnabled = true }
            }
        }, "oem-cluster-probes").start()
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
