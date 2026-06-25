package com.byd.dashcast.hud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.R
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.CanWriteVerbs
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.system.CanBusController
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic bench for the BYD instrument-cluster / HUD navigation API (DL3).
 *
 * Two tools to find why the HUD nav doesn't show on DL3:
 *  1. **Scrape** — dump the REAL `INSTRUMENT_*` feature-ID constants from the car
 *     framework ([HudFeatureScraper]). DL3 IDs differ from the OpenBYD DiLink-5.1
 *     set DashCast currently hardcodes, so a write can silently target the wrong
 *     register.
 *  2. **Bench** — fire nav ON/OFF + a test guidance via [CanBusController] (the
 *     existing daemon path), plus a RAW `featureId + value` write so any candidate
 *     ID (current / repo / scraped) can be validated live against the cluster.
 *
 * Dev-only screen; built programmatically (no layout/strings → no i18n burden).
 */
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var fidField: EditText
    private lateinit var valField: EditText
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD nav bench (DL3)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(header("0 · One-tap test (results sent automatically)"))
        root.addView(hint("Runs the full nav sequence with the verified IDs and uploads the result to the support Telegram topic — no typing needed. Then just look at the cluster."))
        root.addView(button("▶  RUN HUD SELF-TEST & SEND") { runHudSelfTest() })

        root.addView(header("1 · Scrape real feature IDs from this car"))
        root.addView(button("Scrape BYD instrument/nav IDs") { runScrape() })

        root.addView(header("2 · Activate / test the HUD nav"))
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navRow.addView(button("Nav ON") { bg { CanBusController.setNaviActive(true); log("setNaviActive(true) ok") } }, rowLp())
        navRow.addView(button("Nav OFF") { bg { CanBusController.setNaviActive(false); log("setNaviActive(false) ok") } }, rowLp())
        root.addView(navRow)
        root.addView(button("Test guidance: turn-right 300 m (rc per write)") {
            bg {
                // Raw writes with the CORRECT feature IDs so each SDK return code is shown
                // (rc=0 = accepted; negative = rejected/unknown feature). Then look at the cluster.
                log("SETTING_NAVI_SCREEN=3 rc=" +
                        CanBusController.setSettingFeature(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3))
                log("SEND_NAVI_STATUS=active rc=" +
                        CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, CanWriteVerbs.NAVI_STATUS_ACTIVE))
                log("GUIDE_SIMPLE=turn-right rc=" +
                        CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, CanBusController.ICON_TURN_RIGHT))
                log("FRONT_CROSSING=300m rc=" +
                        CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, 300))
                log("NEXT_PATHNAME=TEST rc=" +
                        CanBusController.setFeatureBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, "TEST".toByteArray(Charsets.UTF_8)))
                log("→ now LOOK AT THE CLUSTER: turn-right arrow + 300 m + 'TEST'?")
            }
        })

        root.addView(header("3 · Raw write (try any featureId)"))
        root.addView(hint("featureId — hex (0x43D10010) or decimal. Try your IDs, the repo's, or a scraped one."))
        fidField = field("0x43D10010", InputType.TYPE_CLASS_TEXT)
        root.addView(fidField)
        valField = field("2", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        root.addView(valField)
        val rawRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawRow.addView(button("Send int") { sendRawInt() }, rowLp())
        rawRow.addView(button("Send text→bytes") { sendRawBytes() }, rowLp())
        root.addView(rawRow)
        root.addView(button("Print verified DL3 feature IDs") { printKnownIds() })

        root.addView(header("4 · AAOS cluster (DX_BYD_AUTO only — no-op on DiLink)"))
        root.addView(hint("Switch the cluster mode via a vendor VHAL command WHILE an app is on Display 1, then look at the panel. Or probe the display-proxy HAL that owns the panel."))
        for ((name, prop) in AaosClusterProbe.CANDIDATES) {
            root.addView(button("%s = 1  (0x%08X)".format(name, prop)) { aaosSetProp(prop, 1) })
        }
        root.addView(button("Send VHAL int (free featureId + value above)") { aaosSetFree() })
        root.addView(button("Probe display-proxy HAL") {
            if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return@button }
            bg { log(AaosClusterProbe.probeDisplayProxy()) }
        })

        // ── Output + export (testers can't always send a bug report — let them share/copy
        //    the bench log directly, on any platform) ──
        root.addView(header("Output — export the results"))
        val exportRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(button("Share log") { shareLog() }, rowLp())
        exportRow.addView(button("Copy log") { copyLog() }, rowLp())
        exportRow.addView(button("Clear") { out.text = ""; logUi("cleared") }, rowLp())
        root.addView(exportRow)

        out = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val outScroll = ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
            ).apply { topMargin = dp(12) }
        }
        root.addView(outScroll)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Make sure the proxy daemon is connected so the bench writes can reach it.
        bg {
            if (!ProxyClient.isConnected()) {
                ProxyClient.connect(this)
                log("ProxyClient.connect() — connected=${ProxyClient.isConnected()}")
            } else {
                log("ProxyClient already connected")
            }
        }
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private fun runScrape() {
        log("scraping… (see below)")
        bg { log(HudFeatureScraper.scrape()) }
    }

    /**
     * One-tap test for end users: runs the full nav-guidance sequence with the verified
     * feature IDs, captures every SDK return code, then auto-uploads the result to the
     * dedicated Telegram topic via the bug-report bot — no manual input, no share sheet.
     */
    private fun runHudSelfTest() {
        log("HUD self-test starting…")
        bg {
            if (!ProxyClient.isConnected()) ProxyClient.connect(this)
            val sb = StringBuilder()
            sb.append("=== DASHCAST HUD SELF-TEST ===\n")
            sb.append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} — ${Build.PRODUCT}, API ${Build.VERSION.SDK_INT}\n")
            sb.append("ProxyClient connected: ${ProxyClient.isConnected()}\n\n")
            fun step(label: String, write: () -> Int) {
                val line = try { "$label → rc=${write()}" }
                           catch (t: Throwable) { "$label → EXCEPTION ${t.javaClass.simpleName}: ${t.message}" }
                sb.append(line).append('\n')
                log(line)
            }
            step("SETTING_NAVI_SCREEN=3") { CanBusController.setSettingFeature(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3) }
            step("SEND_NAVI_STATUS=active") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, CanWriteVerbs.NAVI_STATUS_ACTIVE) }
            step("GUIDE_SIMPLE=turn-right") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, CanBusController.ICON_TURN_RIGHT) }
            step("FRONT_CROSSING=300m") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, 300) }
            step("NEXT_PATHNAME=TEST") { CanBusController.setFeatureBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, "TEST".toByteArray(Charsets.UTF_8)) }
            step("NAVI_MILEAGE=1200m") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_NAVI_MILEAGE, 1200) }
            step("NAVI_HOUR=0") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_NAVI_HOUR, 0) }
            step("NAVI_MINUTE=12") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_NAVI_MINUTE, 12) }
            sb.append("\nrc=0 = SDK accepted the write (negative = rejected / unknown feature).\n")
            // Capture the visual result in-app (a popup) so we don't depend on the tester
            // typing a reply in Telegram — the answer is baked into the uploaded report.
            runOnUiThread { askVisualResult(sb.toString()) }
        }
    }

    /** Popup asking the tester whether the cluster actually rendered the test guidance. */
    private fun askVisualResult(report: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.hud_visual_title)
            .setMessage(R.string.hud_visual_question)
            .setCancelable(false)
            .setPositiveButton(R.string.hud_visual_yes) { _, _ ->
                uploadSelfTest(report + "\nVISUAL RESULT: YES\n")
            }
            .setNeutralButton(R.string.hud_visual_unsure) { _, _ ->
                uploadSelfTest(report + "\nVISUAL RESULT: NOT SURE\n")
            }
            .setNegativeButton(R.string.hud_visual_no) { _, _ -> askVisualNoDetail(report) }
            .show()
    }

    /** On "No", offer an optional free-text note ("what did you see instead?") before upload. */
    private fun askVisualNoDetail(report: String) {
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
                uploadSelfTest(report + "\nVISUAL RESULT: NO" +
                        (if (note.isNotEmpty()) " — $note" else "") + "\n")
            }
            .show()
    }

    /** Writes the self-test report to a file and uploads it to the HUD Telegram topic. */
    private fun uploadSelfTest(report: String) {
        if (!TelegramBugReporter.isConfigured()) {
            log("Telegram bot not configured in this build — tap 'Share log' to send manually.")
            return
        }
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "hud_selftest_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            file.writeText(report)
            log("uploading self-test to Telegram…")
            TelegramBugReporter.send(this, file, "DashCast HUD self-test — ${Build.PRODUCT}", HUD_TEST_THREAD,
                object : TelegramBugReporter.Callback {
                    override fun onSent() { logUi("✓ self-test sent to Telegram (topic $HUD_TEST_THREAD)") }
                    override fun onFailed(message: String) { logUi("✗ Telegram failed: $message — tap 'Share log' instead") }
                })
        } catch (t: Throwable) {
            log("self-test upload failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun sendRawInt() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val v = valField.text.toString().trim().toIntOrNull() ?: return logUi("bad value")
        bg {
            val rc = CanBusController.setFeatureInt(fid, v)
            log("setFeatureInt(0x%08X, %d) → rc=%d".format(fid, v, rc))
        }
    }

    private fun sendRawBytes() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val s = valField.text.toString()
        bg {
            val rc = CanBusController.setFeatureBytes(fid, s.toByteArray(Charsets.UTF_8))
            log("setFeatureBytes(0x%08X, \"%s\") → rc=%d".format(fid, s, rc))
        }
    }

    private fun aaosSetProp(propId: Int, value: Int) {
        if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return }
        bg { log(AaosClusterProbe.setCarIntProperty(this, propId, 0, value)) }
    }

    private fun aaosSetFree() {
        val fid = parseId(fidField.text.toString()) ?: return logUi("bad featureId")
        val v = valField.text.toString().trim().toIntOrNull() ?: return logUi("bad value")
        if (!AaosClusterProbe.isAaos(this)) { logUi("not AAOS — skipped"); return }
        bg { log(AaosClusterProbe.setCarIntProperty(this, fid, 0, v)) }
    }

    private fun printKnownIds() {
        log(
            "Verified DL3/DL5.1 instrument feature IDs (from the BYDAutoFeatureIds scrape):\n" +
            "SEND_NAVI_STATUS    0x43E0003A   (value 2=active, 4=stop)\n" +
            "GUIDE_SIMPLE        0x43F01010   (value = turn icon id, e.g. 2=right)\n" +
            "GUIDE_ROAD_DIST     0x43F01030\n" +
            "FRONT_CROSSING      0x43F01018   (value = metres)\n" +
            "NEXT_PATHNAME       0x43FA1008   (bytes = UTF-8 road name)\n" +
            "NAVI_TRIP M/H/MIN/S 0x43F02028 / 0x43F02010 / 0x43F02018 / 0x43F0201E\n" +
            "LEAD_MSG 0x43F08010   DIST_TARGET 0x43F08018\n" +
            "SETTING_NAVI_SCREEN 0x4C10E015   (value 3, via SettingDevice)"
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Write the full session log to a file and share it (Telegram/email/…). */
    private fun shareLog() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val name = "hud_bench_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val file = File(dir, name)
            file.writeText(out.text.toString())
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "DashCast HUD bench log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share HUD bench log"))
            logUi("exported → $file")
        } catch (t: Throwable) {
            logUi("share failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Fallback when sharing isn't convenient: copy the full log to the clipboard. */
    private fun copyLog() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("HUD bench log", out.text))
            logUi("copied to clipboard")
        } catch (t: Throwable) {
            logUi("copy failed: ${t.message}")
        }
    }

    /** Accepts "0x..", "..h" hex or decimal; values up to 0xFFFFFFFF fit a signed int. */
    private fun parseId(raw: String): Int? = try {
        java.lang.Long.decode(raw.trim()).toInt()
    } catch (t: Throwable) { null }

    private inline fun bg(crossinline work: () -> Unit) {
        Thread {
            try { work() } catch (t: Throwable) { log("ERROR: ${t.javaClass.simpleName}: ${t.message}") }
        }.start()
    }

    private fun log(msg: String) = runOnUiThread { logUi(msg) }

    // Single output sink: EVERYTHING shown on screen (results, validation errors,
    // "not AAOS — skipped", connection state…) is also written to the in-app journal,
    // so the bug report captures the full bench session — no screen-only blind spot
    // (INC-20260625-174650). logUi is always called on the UI thread.
    private fun logUi(msg: String) {
        AppLogger.i("HudDiagBench", msg)
        out.append("[${ts.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    // ── tiny view builders ──────────────────────────────────────────────────

    private fun header(t: String) = TextView(this).apply {
        text = t
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(initial: String, type: Int) = EditText(this).apply {
        setText(initial)
        inputType = type
        gravity = Gravity.START
    }

    private fun rowLp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        /** Telegram topic (message_thread_id) for HUD self-test results — t.me/c/3712642112/2701. */
        private const val HUD_TEST_THREAD = "2701"
    }
}
