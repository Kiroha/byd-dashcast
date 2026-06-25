package com.byd.dashcast.hud

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated DX_BYD_AUTO (AAOS) cluster diagnostic. ONE button runs the full
 * [AaosDiagnosticBundle] (env + cluster/nav dumpsys + candidate system APKs +
 * experiments), shows **live progress** so the user can see it isn't stuck, then
 * zips and uploads everything to the support Telegram topic.
 *
 * Dev-only screen, built programmatically (no layout/strings → no i18n burden).
 */
@android.annotation.SuppressLint("SetTextI18n")
class AaosDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var runBtn: Button
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AAOS cluster diagnostic"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Collects the cluster environment, candidate system APKs (cluster renderer, " +
                    "nav, automotive services) and the AAOS gate results, then uploads ONE zip. " +
                    "Watch the progress below — it takes ~30–90 s."
            textSize = 13f
        })

        runBtn = Button(this).apply {
            text = "▶▶  RUN FULL AAOS DIAGNOSTIC → ZIP"
            isAllCaps = false
            setOnClickListener { runDiagnostic() }
        }
        root.addView(runBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(12) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        log(AaosDiagnosticBundle.header(this))
        log("Ready. Tap the button to start.")
    }

    private fun runDiagnostic() {
        runBtn.isEnabled = false
        runBtn.text = "Running… (do not leave this screen)"
        log("──────── diagnostic started ────────")
        Thread({
            try {
                val work = AaosDiagnosticBundle.collect(this) { msg -> log(msg) }
                log("zipping…")
                val zip = AaosDiagnosticBundle.zipDir(work)
                log("zip ready: ${zip.name} (${zip.length() / 1024} KB)")
                upload(zip)
            } catch (t: Throwable) {
                log("FAILED: ${t.javaClass.simpleName}: ${t.message}")
                resetButton()
            }
        }, "aaos-diag").start()
    }

    private fun upload(zip: File) {
        if (!TelegramBugReporter.isConfigured()) {
            log("Telegram not configured — zip saved at ${zip.absolutePath}")
            resetButton(); return
        }
        log("uploading to Telegram…")
        TelegramBugReporter.send(this, zip, "AAOS cluster diagnostic — ${Build.PRODUCT}",
            object : TelegramBugReporter.Callback {
                override fun onSent() { log("✓ sent to Telegram. Done — you can leave."); resetButton() }
                override fun onFailed(message: String) {
                    log("✗ upload failed: $message — zip at ${zip.absolutePath}"); resetButton()
                }
            })
    }

    private fun resetButton() = runOnUiThread {
        runBtn.isEnabled = true
        runBtn.text = "▶▶  RUN FULL AAOS DIAGNOSTIC → ZIP"
    }

    private fun log(msg: String) = runOnUiThread {
        AppLogger.i("AaosDiag", msg)
        out.append("[${ts.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }
}
