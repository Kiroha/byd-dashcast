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
import com.byd.dashcast.proxy.daemon.CanWriteVerbs
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.system.CanBusController
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
    private lateinit var hudLabel: TextView
    private var hudMode = 0
    private var recording = false
    private lateinit var recBtn: Button
    private lateinit var recGrid: LinearLayout
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD nav bench (DL3)"

        // Start the daemon push-feedback listener as soon as this page opens, so it is already
        // listening BEFORE the tester (re)starts the OEM nav. The daemon respawns on app update
        // (resetting the listener), and the OEM sets the HUD mode once at nav-start — if we only
        // registered at "② Read" time we'd miss that push. The listener persists + keeps a
        // last-known value, so the OEM HUD-mode push is captured whenever it happens. Guarded.
        Thread {
            try { com.byd.dashcast.proxy.ProxyClient.canListenStart() } catch (_: Throwable) {}
        }.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "DiLink 3 HUD test. Park first. Do the steps in order — each one uploads a zip automatically."
            textSize = 13f
        })

        // ── STEP 1 — one-tap full test ──────────────────────────────────────
        root.addView(sectionHeader("STEP 1 — Full test (one tap)"))
        root.addView(hint("Runs everything, then asks if the HUD showed the nav. Watch the HUD + cluster."))
        runBtn = Button(this).apply {
            text = "▶▶  RUN FULL HUD TEST → ZIP"
            isAllCaps = false
            setOnClickListener { runDiagnostic() }
        }
        root.addView(runBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })

        // ── STEP 2 — learn how the CAR drives the HUD (while OEM nav is guiding) ──
        root.addView(sectionHeader("STEP 2 — While the CAR's own navigation is guiding"))
        root.addView(hint("Start the built-in car navigation on a real route (a turn coming up), HUD on. " +
                "Then tap BOTH buttons below."))
        root.addView(Button(this).apply {
            text = "①  Capture OEM HUD baseline → ZIP"
            isAllCaps = false
            setOnClickListener { runOemBaseline() }
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })
        root.addView(Button(this).apply {
            text = "②  Read HUD nav mode → ZIP"
            isAllCaps = false
            setOnClickListener { runHudStateRead() }
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })

        // ── GUIDANCE RECORDER — correlate the HUD arrow with CAN events (drive + tap) ──
        root.addView(sectionHeader("▶ Guidance recorder (drive + tap the arrow)"))
        root.addView(hint("Tap START, then start the car nav on the HUD. A PASSENGER taps the button " +
                "matching the arrow shown on the HUD each time it changes. Tap STOP when done → uploads a zip. " +
                "Drive safely — passenger only."))
        recBtn = Button(this).apply {
            text = "▶  Guidance recorder — START"
            isAllCaps = false
            setOnClickListener { toggleRecording() }
        }
        root.addView(recBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })
        recGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
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
            recGrid.addView(row)
        }
        root.addView(recGrid)

        // ── ADVANCED — manual HUD mode explorer (optional) ──────────────────
        root.addView(sectionHeader("Advanced — HUD mode explorer (optional)"))
        root.addView(hint("Set one mode, photograph the HUD, step to the next."))
        hudLabel = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "HUD: ?   MODE = 0"
        }
        root.addView(hudLabel)
        val rowSw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowSw.addView(miniBtn("HUD ON") { setHudSwitch(true) }, eq())
        rowSw.addView(miniBtn("HUD OFF") { setHudSwitch(false) }, eq())
        root.addView(rowSw)
        val rowMode = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowMode.addView(miniBtn("◀ Mode −") { stepMode(-1) }, eq())
        rowMode.addView(miniBtn("Mode + ▶") { stepMode(+1) }, eq())
        root.addView(rowMode)
        root.addView(miniBtn("▶ Feed nav 10 s at this mode") { feedNav() })

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
            .setMessage("On the WINDSHIELD HUD (the projection on the glass) — did the nav appear " +
                    "(turn arrow + distance + TEST)? On 'No', note which HUD mode (if any) showed " +
                    "something. (The cluster already works.)")
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

    // ── P1 — HUD mode explorer ──────────────────────────────────────────────
    private fun setHudSwitch(on: Boolean) = bg {
        val rc = CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_SWITCH, if (on) 1 else 0)
        log("HUD switch=${if (on) 1 else 0} rc=$rc")
        runOnUiThread { hudLabel.text = "HUD: ${if (on) "ON" else "OFF"}   MODE = $hudMode" }
    }

    private fun stepMode(delta: Int) {
        hudMode = (hudMode + delta).coerceIn(0, 12)
        hudLabel.text = "HUD: (mode set)   MODE = $hudMode"
        bg {
            val rc = CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_MODE, hudMode)
            log("HUD MODE = $hudMode rc=$rc  → photograph the WINDSHIELD HUD now")
        }
    }

    private fun feedNav() {
        log("feeding nav 10 s — watch the WINDSHIELD HUD at MODE = $hudMode")
        bg {
            repeat(12) {
                try { HudAutoNaviBroadcast.sendGuide(this, HudAutoNaviBroadcast.AMAP_ICON_RIGHT, 300, "TEST", 1200, 720) }
                catch (_: Throwable) {}
                Thread.sleep(800)
            }
            log("nav feed done (MODE = $hudMode)")
        }
    }

    // ── P4 — OEM nav baseline ───────────────────────────────────────────────
    private fun runOemBaseline() {
        log("──── OEM HUD baseline ──── make sure the REAL OEM nav is RUNNING on the HUD!")
        bg {
            val work = HudDiagnosticBundle.collectOemBaseline(this) { log(it) }
            val zip = HudDiagnosticBundle.zipDir(work)
            log("baseline zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 OEM HUD baseline — ${Build.PRODUCT}")
        }
    }

    /** Reads the HUD/nav feedback features while the OEM nav guides → captures the OEM's nav-HUD mode. */
    private fun runHudStateRead() {
        log("──── Read HUD nav mode ──── the OEM nav must be ACTIVELY guiding on the HUD now!")
        bg {
            val work = HudDiagnosticBundle.collectHudStateRead(this) { log(it) }
            val zip = HudDiagnosticBundle.zipDir(work)
            log("HUD-state zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 HUD nav-mode read — ${Build.PRODUCT}")
        }
    }

    // ── Guidance recorder — timestamped CAN events + user ground-truth taps ──
    private fun toggleRecording() = if (!recording) startGuidanceRecording() else stopGuidanceRecording()

    private fun startGuidanceRecording() {
        recording = true
        recBtn.text = "■  STOP recording → ZIP"
        recGrid.visibility = View.VISIBLE
        log("──── guidance recording STARTED ──── start the car nav on the HUD; a passenger taps the arrow on each change.")
        bg {
            try { com.byd.dashcast.proxy.ProxyClient.canListenStart() } catch (_: Throwable) {}
            try { com.byd.dashcast.proxy.ProxyClient.canListenClear() } catch (_: Throwable) {}  // fresh + reset timestamp clock
        }
    }

    /** A passenger tapped the maneuver shown on the HUD → timestamped ground-truth marker in the log. */
    private fun mark(code: String) {
        if (!recording) { log("(tap START first)"); return }
        bg { try { com.byd.dashcast.proxy.ProxyClient.canListenMark(code) } catch (_: Throwable) {} }
        log("● $code")
    }

    private fun stopGuidanceRecording() {
        recording = false
        recBtn.text = "▶  Guidance recorder — START"
        recGrid.visibility = View.GONE
        log("──── recording STOPPED — building zip ────")
        bg {
            val drained = try { com.byd.dashcast.proxy.ProxyClient.canListenDrain() ?: "" }
                          catch (t: Throwable) { "drain ERR: ${t.message}" }
            val work = File(cacheDir, "hud_guidance_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                .apply { mkdirs() }
            val header = "=== HUD GUIDANCE RECORDING (timestamped CAN events + user TAP markers) ===\n" +
                    "${com.byd.dashcast.BuildConfig.VERSION_NAME} (${com.byd.dashcast.BuildConfig.VERSION_CODE}) — " +
                    "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n" +
                    "Format: [t=<s>] evt 0x<featureId>=<int> [buf=<hex>]  |  [t=<s>] TAP <maneuver>\n\n"
            File(work, "01_guidance.txt").writeText(header + drained)
            val props = try {
                com.byd.dashcast.proxy.ProxyClient.runShell(
                    "getprop 2>/dev/null | grep -iE 'hud|fission_single_os|model|inswver'") ?: ""
            } catch (_: Throwable) { "" }
            File(work, "02_props.txt").writeText(props)
            val zip = HudDiagnosticBundle.zipDir(work)
            log("guidance zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 HUD guidance recording — ${Build.PRODUCT}")
        }
    }

    private fun uploadZip(zip: File, caption: String) {
        if (!TelegramBugReporter.isConfigured()) {
            log("Telegram not configured — zip at ${zip.absolutePath}"); return
        }
        TelegramBugReporter.send(this, zip, caption, HUD_TEST_THREAD, object : TelegramBugReporter.Callback {
            override fun onSent() { log("✓ sent to Telegram (topic $HUD_TEST_THREAD).") }
            override fun onFailed(message: String) { log("✗ upload failed: $message — zip at ${zip.absolutePath}") }
        })
    }

    // ── tiny view + thread helpers ──────────────────────────────────────────
    private inline fun bg(crossinline work: () -> Unit) {
        Thread { try { work() } catch (t: Throwable) { log("ERR: ${t.javaClass.simpleName}: ${t.message}") } }.start()
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun miniBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun eq() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        /** Telegram topic (message_thread_id) for HUD diagnostics — t.me/c/3712642112/2701. */
        private const val HUD_TEST_THREAD = "2701"
    }
}
