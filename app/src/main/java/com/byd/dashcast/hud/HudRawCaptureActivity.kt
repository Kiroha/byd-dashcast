package com.byd.dashcast.hud

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Raw logcat recorder for HUD guidance decoding.
 *
 * The gold sample (log.docx) proved that an UNFILTERED logcat catches HUD signals our CAN
 * listener misses (the HUD switch feedback rode the BYDAutoLightDevice, not our setting/instrument
 * listeners). So this tool captures **everything**: START launches `logcat -b all` (all buffers,
 * `-v threadtime` timestamps) into a device-side file via the daemon. A passenger taps the arrow
 * shown on the HUD each time it changes; every tap is injected INTO the logcat itself via
 * `log -t DASHCAST_MARK`, so the marker shares the exact same clock as every other event —
 * perfect correlation. STOP kills logcat, pulls the file through the daemon, zips it and uploads
 * it to the HUD Telegram topic.
 *
 * Dev-only screen, built programmatically.
 */
@android.annotation.SuppressLint("SetTextI18n")
class HudRawCaptureActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var captureBtn: Button
    private lateinit var grid: LinearLayout
    private var capturing = false
    private var markCount = 0

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD raw logcat recorder"
        Thread { try { ProxyClient.connect(this) } catch (_: Throwable) {} }.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Capture un logcat NON filtré (tous les buffers, horodaté). Tape START, lance la " +
                    "navigation voiture sur le HUD, puis un PASSAGER tape la flèche affichée à chaque " +
                    "changement. STOP → ZIP → Telegram. Conduis prudemment — passager uniquement."
            textSize = 13f
        })

        captureBtn = Button(this).apply {
            text = "▶  START (logcat brut)"
            isAllCaps = false
            setOnClickListener { toggleCapture() }
        }
        root.addView(captureBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(10) })

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        listOf(
            listOf("straight" to "▲ Straight", "left" to "◀ Left", "right" to "Right ▶"),
            listOf("slight-left" to "↖ Slight L", "slight-right" to "↗ Slight R"),
            listOf("sharp-left" to "⤶ Sharp L", "sharp-right" to "⤷ Sharp R"),
            listOf("roundabout" to "◎ Roundabout", "uturn" to "↩ U-turn"),
            listOf("exit-left" to "⇤ Exit L", "exit-right" to "Exit R ⇥"),
            listOf("arrive" to "⚑ Arrive", "changed-other" to "⟳ Changed (?)")
        ).forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { (code, label) -> row.addView(miniBtn(label) { mark(code) }, eq()) }
            grid.addView(row)
        }
        root.addView(grid)

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
        log("Ready. Tape START.")
    }

    override fun onDestroy() {
        // If the screen is left (back / rotation / finish) while a capture is running without
        // tapping STOP, the detached `nohup logcat -b all` would keep writing an unbounded
        // all-buffers log to /data/local/tmp until reboot (and repeat launches stack more
        // processes). Kill the recorded PID and delete the abandoned capture file — a hard
        // abort (no zip/upload); a normal STOP still runs the graceful path.
        if (capturing) {
            capturing = false
            bg {
                sh(
                    "P=$REMOTE_PID; F=$REMOTE_LOG; " +
                    "PID=\$(cat \"\$P\" 2>/dev/null); " +
                    "[ -n \"\$PID\" ] && kill \"\$PID\" 2>/dev/null; " +
                    "sleep 1; " +
                    "[ -n \"\$PID\" ] && kill -9 \"\$PID\" 2>/dev/null; " +
                    "rm -f \"\$F\" \"\$P\" 2>/dev/null"
                )
            }
        }
        super.onDestroy()
    }

    private fun toggleCapture() = if (!capturing) startCapture() else stopCapture()

    private fun startCapture() {
        capturing = true
        markCount = 0
        captureBtn.text = "■  STOP → ZIP"
        grid.visibility = View.VISIBLE
        log("──── capture démarrée ──── lance la nav sur le HUD; un passager tape chaque flèche.")
        bg {
            val pid = sh(
                "F=$REMOTE_LOG; P=$REMOTE_PID; " +
                "rm -f \"\$F\"; " +
                "logcat -b all -c 2>/dev/null; " +
                "nohup logcat -v threadtime -b all > \"\$F\" 2>&1 & " +
                "echo \$! > \"\$P\"; " +
                "sleep 1; " +
                "log -t DASHCAST_MARK -p i \"CAPTURE START\"; " +
                "cat \"\$P\""
            ).trim()
            log("logcat lancé (pid=$pid). Écoute TOUT, sans filtre.")
        }
    }

    /** A passenger tapped the maneuver shown on the HUD → inject a marker INTO the logcat. */
    private fun mark(code: String) {
        if (!capturing) { log("(tape START d'abord)"); return }
        val n = ++markCount
        bg { sh("log -t DASHCAST_MARK -p i \"TAP $code #$n\"") }
        log("● $code  #$n")
    }

    private fun stopCapture() {
        capturing = false
        captureBtn.isEnabled = false
        captureBtn.text = "… envoi en cours"
        grid.visibility = View.GONE
        log("──── capture arrêtée — récupération + zip ────")
        bg {
            val size = sh(
                "F=$REMOTE_LOG; P=$REMOTE_PID; " +
                "log -t DASHCAST_MARK -p i \"CAPTURE STOP\"; " +
                "sleep 1; " +
                "PID=\$(cat \"\$P\" 2>/dev/null); " +
                "[ -n \"\$PID\" ] && kill \"\$PID\" 2>/dev/null; " +
                "sleep 1; " +
                "[ -n \"\$PID\" ] && kill -9 \"\$PID\" 2>/dev/null; " +
                "sync; wc -c < \"\$F\" 2>/dev/null"
            ).trim()
            log("logcat arrêté (${size} octets). Rapatriement…")

            val work = File(cacheDir, "hud_rawcap_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }
            File(work, "00_info.txt").writeText(
                "=== HUD RAW LOGCAT CAPTURE (unfiltered, all buffers, threadtime) ===\n" +
                "${com.byd.dashcast.BuildConfig.VERSION_NAME} (${com.byd.dashcast.BuildConfig.VERSION_CODE}) — " +
                "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n" +
                "Taps injected: $markCount (grep 'DASHCAST_MARK').\n" +
                "Correlate: each 'DASHCAST_MARK: TAP <arrow> #<n>' line shares the logcat clock with " +
                "the surrounding BYDAuto*/HUD events → the guidance code fired just before the tap.\n")

            val dest = File(work, "01_logcat.txt")
            val pulled = try { HudCaptureSupport.pullRemoteFile(REMOTE_LOG, dest) }
                         catch (t: Throwable) { log("pull ERR: ${t.message}"); 0L }
            log("logcat rapatrié: ${pulled / 1024} KB")
            File(work, "02_props.txt").writeText(
                sh("getprop 2>/dev/null | grep -iE 'hud|fission_single_os|model|inswver'"))

            val zip = HudCaptureSupport.zipDir(work)
            log("zip: ${zip.name} (${zip.length() / 1024} KB)")
            val caption = "DL3 HUD raw logcat capture — ${Build.PRODUCT} ($markCount taps)"
            runOnUiThread {
                captureBtn.isEnabled = true
                captureBtn.text = "▶  START (logcat brut)"
                confirmAndUpload(zip, caption)
            }
        }
    }

    /**
     * The capture is a FULL device-wide logcat (all apps, all buffers) — required for the HUD RE
     * correlation, but it can also contain other apps' log lines. Get explicit consent before it
     * leaves the device to the shared support channel; otherwise keep the zip local. The capture
     * itself is unchanged (still unfiltered), only the auto-upload is gated.
     */
    private fun confirmAndUpload(zip: File, caption: String) {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle("Envoyer la capture brute ?")
                .setMessage("Ce zip contient le logcat COMPLET de l'appareil (toutes les apps, tous " +
                        "les buffers). L'envoyer au canal support DashCast (Telegram) ?\n\n" +
                        "Sinon il reste en local :\n${zip.absolutePath}")
                .setCancelable(false)
                .setPositiveButton("Envoyer") { _, _ -> bg { uploadZip(zip, caption) } }
                .setNegativeButton("Garder local") { _, _ -> log("zip gardé en local: ${zip.absolutePath}") }
                .show()
        } catch (t: Throwable) {
            log("dialog ERR (${t.message}) — zip local: ${zip.absolutePath}")
        }
    }

    private fun uploadZip(zip: File, caption: String) {
        if (!TelegramBugReporter.isConfigured()) {
            log("Telegram non configuré — zip: ${zip.absolutePath}"); return
        }
        TelegramBugReporter.send(this, zip, caption, HudCaptureSupport.HUD_TEST_THREAD,
            object : TelegramBugReporter.Callback {
                override fun onSent() { log("✓ envoyé sur Telegram (topic ${HudCaptureSupport.HUD_TEST_THREAD}).") }
                override fun onFailed(message: String) { log("✗ échec envoi: $message — zip: ${zip.absolutePath}") }
            })
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private inline fun bg(crossinline work: () -> Unit) {
        Thread { try { work() } catch (t: Throwable) { log("ERR: ${t.javaClass.simpleName}: ${t.message}") } }.start()
    }

    private fun sh(cmd: String): String =
        try { ProxyClient.runShell(cmd) ?: "" }
        catch (t: Throwable) { "ERR [$cmd]: ${t.message}" }

    private fun log(msg: String) = runOnUiThread {
        AppLogger.i("HudRawCapture", msg)
        out.append("[${stamp.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun miniBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun eq() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        private const val REMOTE_LOG = "/data/local/tmp/dashcast_hudcap.log"
        private const val REMOTE_PID = "/data/local/tmp/dashcast_hudcap.pid"
    }
}
