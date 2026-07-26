package com.byd.dashcast.hud

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.R
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated DX_BYD_AUTO (AAOS) cluster diagnostic. ONE button:
 *  1. runs the full [AaosDiagnosticBundle] (env + cluster/nav dumpsys + APKs + gate experiments),
 *  2. runs the **Presentation probe** — the one untried, non-privileged path to put app
 *     content on the cluster: enumerate displays a normal app can see and try to show a
 *     [AaosClusterPresentation] on the cluster display,
 *  3. asks the visual result and uploads ONE zip to the support Telegram topic.
 *
 * Live progress + a progress bar so the user can see it isn't stuck.
 * Dev-only screen, built programmatically (no layout/strings → no i18n burden).
 */
@android.annotation.SuppressLint("SetTextI18n")
class AaosDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var runBtn: Button
    private lateinit var bar: ProgressBar
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ui = Handler(Looper.getMainLooper())
    private var presentation: AaosClusterPresentation? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AAOS cluster diagnostic"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Captures the cluster environment + candidate system APKs + gate experiments, " +
                    "then probes whether a normal app can render its own content on the cluster via " +
                    "the Presentation API (a big blue DASHCAST screen). Watch the cluster, answer the " +
                    "popup; everything is uploaded as ONE zip. ~40–90 s."
            textSize = 13f
        })
        runBtn = Button(this).apply {
            text = "▶▶  RUN FULL AAOS DIAGNOSTIC → ZIP"
            isAllCaps = false
            setOnClickListener { runDiagnostic() }
        }
        root.addView(runBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(12) })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })
        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(mp, mp).apply { topMargin = dp(12) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        log(AaosDiagnosticBundle.header(this))

        // Gate (defense-in-depth): this diagnostic is AAOS-only. On non-AAOS units
        // (DL3/DL5 fission ROMs) every AAOS probe is a no-op/noise, so disable running
        // it even if this screen is somehow reached without the DiagActivity button gate.
        if (!AaosClusterProbe.isAaos(this)) {
            runBtn.isEnabled = false
            runBtn.text = "AAOS-only — this unit is not Android Automotive"
            log("⛔ This unit is NOT Android Automotive (no android.hardware.type.automotive feature).")
            log("The AAOS cluster diagnostic does not apply here and has been disabled.")
            return
        }
        log("Ready. Tap the button to start.")
    }

    private fun runDiagnostic() {
        runBtn.isEnabled = false
        runBtn.text = "Running… (stay on this screen)"
        bar.visibility = View.VISIBLE
        log("──────── diagnostic started ────────")
        Thread({
            try {
                val work = AaosDiagnosticBundle.collect(this) { msg -> log(msg) }
                runOnUiThread { presentationProbe(work) }
            } catch (t: Throwable) {
                log("FAILED: ${t.javaClass.simpleName}: ${t.message}"); resetUi()
            }
        }, "aaos-diag").start()
    }

    /** The untried app-projection path: can a normal app show a Presentation on the cluster? */
    private fun presentationProbe(work: File) {
        log("▶ Presentation probe: enumerating displays…")
        val sb = StringBuilder("=== PRESENTATION PROBE ===\n")
        val dm = getSystemService(DisplayManager::class.java)
        val all = dm?.displays ?: emptyArray()
        all.forEach { d ->
            sb.append("display id=${d.displayId} name=\"${d.name}\" flags=0x${Integer.toHexString(d.flags)} state=${d.state}\n")
        }
        val pres = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: emptyArray()
        sb.append("presentation-category display ids: ${pres.map { it.displayId }}\n")

        val target: Display? = pres.firstOrNull { it.displayId != 0 }
            ?: all.firstOrNull { it.displayId == 1 }
            ?: all.firstOrNull { it.displayId != 0 }
        if (target == null) {
            sb.append("RESULT: no secondary display visible to a normal app — cannot present.\n")
            log("no secondary display visible — Presentation not possible")
            write(work, "15_presentation_probe.txt", sb.toString())
            askVisual(work, "N/A (no secondary display)")
            return
        }
        sb.append("target: id=${target.displayId} name=\"${target.name}\"\n")
        log("trying Presentation on display ${target.displayId} (\"${target.name}\")…")
        try {
            val p = AaosClusterPresentation(this, target)
            p.show()
            presentation = p
            sb.append("RESULT: Presentation.show() OK on display ${target.displayId}\n")
            log("✓ Presentation.show() OK — LOOK AT THE CLUSTER for ~8 s")
        } catch (t: Throwable) {
            sb.append("RESULT: Presentation.show() FAILED: ${t.javaClass.simpleName}: ${t.message}\n")
            log("✗ Presentation failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        write(work, "15_presentation_probe.txt", sb.toString())

        ui.postDelayed({
            try { presentation?.dismiss() } catch (_: Throwable) {}
            presentation = null
            askVisual(work, null)
        }, 8000)
    }

    /** Popup: did the DASHCAST test screen appear on the cluster? Answer goes into the zip. */
    private fun askVisual(work: File, forced: String?) {
        if (forced != null) { zipAndUpload(work, forced); return }
        // The 8s delayed runnable can fire after the user left the screen — never build/show a
        // dialog on a finishing/destroyed activity (BadTokenException). onDestroy also removes
        // the callback; this guards the narrow race where it is already dispatching.
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.hud_visual_title)
                .setMessage("Did the big blue \"DASHCAST\" screen appear on the instrument cluster?")
                .setCancelable(false)
                .setPositiveButton(R.string.hud_visual_yes) { _, _ -> zipAndUpload(work, "YES — Presentation shows on cluster") }
                .setNeutralButton(R.string.hud_visual_unsure) { _, _ -> zipAndUpload(work, "NOT SURE") }
                .setNegativeButton(R.string.hud_visual_no) { _, _ -> zipAndUpload(work, "NO") }
                .show()
        } catch (t: Throwable) {
            AppLogger.w("AaosDiag", "askVisual dialog skipped: ${t.message}")
        }
    }

    private fun zipAndUpload(work: File, visual: String) {
        log("zipping + uploading (Presentation visual=$visual)…")
        Thread({
            try {
                File(work, "00_presentation_visual.txt").writeText("PRESENTATION VISUAL RESULT: $visual\n")
                val zip = AaosDiagnosticBundle.zipDir(work)
                log("zip ready: ${zip.name} (${zip.length() / 1024} KB)")
                if (!TelegramBugReporter.isConfigured()) {
                    log("Telegram not configured — zip at ${zip.absolutePath}"); resetUi(); return@Thread
                }
                TelegramBugReporter.send(this, zip, "AAOS cluster diagnostic — ${Build.PRODUCT} (presentation=$visual)",
                    object : TelegramBugReporter.Callback {
                        override fun onSent() { log("✓ sent to Telegram. Done — you can leave."); resetUi() }
                        override fun onFailed(message: String) { log("✗ upload failed: $message — zip at ${zip.absolutePath}"); resetUi() }
                    })
            } catch (t: Throwable) {
                log("zip/upload failed: ${t.javaClass.simpleName}: ${t.message}"); resetUi()
            }
        }, "aaos-zip").start()
    }

    private fun write(dir: File, name: String, content: String) {
        try { File(dir, name).writeText(content) } catch (_: Throwable) {}
    }

    private fun resetUi() = runOnUiThread {
        runBtn.isEnabled = true
        runBtn.text = "▶▶  RUN FULL AAOS DIAGNOSTIC → ZIP"
        bar.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel the 8s delayed askVisual so it can't show an AlertDialog on a destroyed
        // activity (WindowManager.BadTokenException) — and so it can't leak this activity.
        ui.removeCallbacksAndMessages(null)
        try { presentation?.dismiss() } catch (_: Throwable) {}
        presentation = null
    }

    private fun log(msg: String) = runOnUiThread {
        AppLogger.i("AaosDiag", msg)
        out.append("[${ts.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }
}
