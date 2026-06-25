package com.byd.dashcast.hud

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
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
 * HUD navigation bench (DiLink 3). ONE button runs the full DL3 HUD diagnostic
 * ([HudDiagnosticBundle]: feature-ID writes, dedicated SDK methods, AutoNavi
 * broadcast, framework scrape, environment capture, candidate APK pull) with a
 * progress bar + live log, then asks the visual result and uploads one zip to the
 * support Telegram topic.
 *
 * Dev-only screen, built programmatically (no layout/strings → no i18n burden).
 */
@android.annotation.SuppressLint("SetTextI18n")
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var runBtn: Button
    private lateinit var bar: ProgressBar
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD nav bench (DL3)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Runs the full DL3 HUD diagnostic — feature-ID writes, dedicated SDK methods, " +
                    "AutoNavi broadcast, framework scrape, environment + candidate APK capture — " +
                    "then asks whether the cluster showed the guidance and uploads ONE zip. " +
                    "Watch the progress below (~30–60 s)."
            textSize = 13f
        })

        runBtn = Button(this).apply {
            text = "▶▶  RUN FULL DL3 HUD DIAGNOSTIC → ZIP"
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
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL} — ${Build.PRODUCT}, API ${Build.VERSION.SDK_INT}")
        log("Ready. Tap the button to start.")
    }

    private fun runDiagnostic() {
        runBtn.isEnabled = false
        runBtn.text = "Running… (stay on this screen)"
        bar.visibility = View.VISIBLE
        log("──────── diagnostic started ────────")
        Thread({
            try {
                val work = HudDiagnosticBundle.collect(this) { msg -> log(msg) }
                log("collected → ${work.name}; asking visual result…")
                runOnUiThread { askVisualThenZip(work) }
            } catch (t: Throwable) {
                log("FAILED: ${t.javaClass.simpleName}: ${t.message}")
                resetUi()
            }
        }, "hud-diag").start()
    }

    /** Popup: did the cluster actually render the test guidance? Answer is baked into the zip. */
    private fun askVisualThenZip(work: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hud_visual_title)
            .setMessage(R.string.hud_visual_question)
            .setCancelable(false)
            .setPositiveButton(R.string.hud_visual_yes) { _, _ -> zipAndUpload(work, "YES") }
            .setNeutralButton(R.string.hud_visual_unsure) { _, _ -> zipAndUpload(work, "NOT SURE") }
            .setNegativeButton(R.string.hud_visual_no) { _, _ -> askVisualNoDetail(work) }
            .show()
    }

    /** On "No", offer an optional free-text note ("what did you see instead?") before upload. */
    private fun askVisualNoDetail(work: File) {
        val input = EditText(this).apply {
            hint = getString(R.string.hud_visual_no_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hud_visual_no)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.hud_visual_send) { _, _ ->
                val note = input.text.toString().trim()
                zipAndUpload(work, "NO" + if (note.isNotEmpty()) " — $note" else "")
            }
            .show()
    }

    private fun zipAndUpload(work: File, visual: String) {
        log("zipping + uploading (visual=$visual)…")
        Thread({
            try {
                File(work, "00_visual_result.txt").writeText("VISUAL RESULT: $visual\n")
                val zip = HudDiagnosticBundle.zipDir(work)
                log("zip ready: ${zip.name} (${zip.length() / 1024} KB)")
                if (!TelegramBugReporter.isConfigured()) {
                    log("Telegram not configured — zip saved at ${zip.absolutePath}")
                    resetUi(); return@Thread
                }
                TelegramBugReporter.send(this, zip,
                    "DL3 HUD full diagnostic — ${Build.PRODUCT} (visual=$visual)",
                    HUD_TEST_THREAD, object : TelegramBugReporter.Callback {
                        override fun onSent() { log("✓ sent to Telegram (topic $HUD_TEST_THREAD). Done — you can leave."); resetUi() }
                        override fun onFailed(message: String) { log("✗ upload failed: $message — zip at ${zip.absolutePath}"); resetUi() }
                    })
            } catch (t: Throwable) {
                log("zip/upload failed: ${t.javaClass.simpleName}: ${t.message}")
                resetUi()
            }
        }, "hud-zip").start()
    }

    private fun resetUi() = runOnUiThread {
        runBtn.isEnabled = true
        runBtn.text = "▶▶  RUN FULL DL3 HUD DIAGNOSTIC → ZIP"
        bar.visibility = View.GONE
    }

    private fun log(msg: String) = runOnUiThread {
        AppLogger.i("HudDiagBench", msg)
        out.append("[${ts.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        /** Telegram topic (message_thread_id) for HUD diagnostics — t.me/c/3712642112/2701. */
        private const val HUD_TEST_THREAD = "2701"
    }
}
