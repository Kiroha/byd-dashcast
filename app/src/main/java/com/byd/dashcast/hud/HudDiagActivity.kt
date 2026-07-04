package com.byd.dashcast.hud

import android.content.Intent
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
import com.byd.dashcast.platform.Platform
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
 * DL3 HUD bench — rebuilt around the proven HUD-control ground truth extracted from the OEM
 * `com.byd.carsettings` HalSetter logcat (log.docx): each windshield-HUD control maps to a
 * BYDAutoSettingDevice feature id (ECU 0x4C1 / sub 0xE).
 *
 * Three tools:
 *  1. **Confirm the discoveries** — we send each HUD command ourselves and, after every command,
 *     ask the tester whether the expected effect happened (OK / KO). The answers + SDK result
 *     codes are zipped and uploaded to Telegram.
 *  2. **Raw logcat recorder** — opens [HudRawCaptureActivity]: an unfiltered logcat capture with
 *     on-screen arrow buttons whose taps are injected into the log, to decode the turn-by-turn
 *     guidance codes while driving.
 *  3. **CAN → HUD bench** — we write nav guidance ourselves (BYDAutoInstrumentDevice: icon +
 *     distance + road + status) with the OEM nav OFF, then ask whether the windshield HUD showed
 *     an arrow — the decisive test of whether the HUD MCU consumes our CAN frames (arrow-capable
 *     firmware only; the inswver firmware id is captured in every zip to pin the threshold).
 *
 * Dev-only screen, built programmatically (no layout/strings → no i18n burden).
 */
@android.annotation.SuppressLint("SetTextI18n")
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var confirmBtn: Button
    private lateinit var benchBtn: Button
    private lateinit var bar: ProgressBar

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    // ── confirmation-sequence state ─────────────────────────────────────────
    private val report = StringBuilder()
    private var stepIdx = 0

    /** One HUD command to confirm: it performs the writes ([run] returns a log line) and asks [question]. */
    private data class Step(val id: String, val title: String, val question: String, val run: () -> String)

    private val steps: List<Step> by lazy {
        listOf(
            Step("HUD_ON", "1/6 — Allumer le HUD",
                "Le HUD s'est-il ALLUMÉ (image projetée sur le pare-brise) ?") {
                "SET_HUD_SWITCH(0x4C10E023)=1  rc=${setInt(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON)}"
            },
            Step("HUD_ADAS", "2/6 — Affichage ADAS / overlay",
                "Un élément ADAS / overlay est-il apparu (ou disparu puis réapparu) sur le HUD ?") {
                val off = setInt(CanWriteVerbs.SET_HUD_OPTION_DISPLAY, 0); sleep(1200)
                val on = setInt(CanWriteVerbs.SET_HUD_OPTION_DISPLAY, 1)
                "SET_HUD_OPTION_DISPLAY(0x4C10E030) off→on  rc=$off/$on"
            },
            Step("HUD_BRIGHT", "3/6 — Luminosité",
                "La LUMINOSITÉ du HUD a-t-elle varié (sombre → clair) pendant le test ?") {
                ramp("SET_HUD_BRIGHTNESS(0x4C10E018)", intArrayOf(2, 6, 11, 8)) {
                    setInt(CanWriteVerbs.SET_HUD_BRIGHTNESS, it)
                }
            },
            Step("HUD_HEIGHT", "4/6 — Hauteur",
                "La POSITION VERTICALE de l'image a-t-elle bougé (monte/descend) pendant le test ?") {
                ramp("SET_HUD_HEIGHT(0x4C10E010)", intArrayOf(6, 11, 15, 11)) {
                    setInt(CanWriteVerbs.SET_HUD_HEIGHT, it)
                }
            },
            Step("HUD_ANGLE", "5/6 — Angle",
                "L'ANGLE / l'inclinaison de l'image a-t-il changé pendant le test ?") {
                rampD("SET_HUD_ANGLE(0x4C10E02C, double)", doubleArrayOf(-3.0, 0.0, 3.0, 0.0)) {
                    setDouble(CanWriteVerbs.SET_HUD_ANGLE, it)
                }
            },
            Step("HUD_OFF", "6/6 — Éteindre le HUD",
                "Le HUD s'est-il ÉTEINT ?") {
                "SET_HUD_SWITCH(0x4C10E023)=2  rc=${setInt(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_OFF)}"
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "HUD bench (DL3)"

        // Warm up the daemon so the first write is instant (and, after an app update, forces a
        // fresh daemon that speaks protocol v17 = the new double/pull verbs). Guarded, off-thread.
        Thread { try { ProxyClient.connect(this) } catch (_: Throwable) {} }.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "DiLink 3 HUD bench. Park first. HUD control is proven — these tools confirm it " +
                    "and decode the turn-by-turn guidance codes."
            textSize = 13f
        })
        root.addView(TextView(this).apply {
            text = "Firmware HUD (inswver): ${firmwareLabel()}"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })

        // ── TOOL 1 — confirm the 5 discoveries ──────────────────────────────
        root.addView(sectionHeader("① Confirmer les découvertes HUD"))
        root.addView(hint("On envoie chaque commande HUD (allumage, ADAS, luminosité, hauteur, angle, " +
                "extinction). Après CHAQUE commande, réponds OUI/NON à la question. À la fin ça envoie un ZIP."))
        confirmBtn = Button(this).apply {
            text = "▶  Lancer la confirmation (6 commandes)"
            isAllCaps = false
            setOnClickListener { startConfirmation() }
        }
        root.addView(confirmBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(bar, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })

        // ── TOOL 2 — raw logcat recorder (arrows) ───────────────────────────
        root.addView(sectionHeader("② Enregistreur logcat brut (flèches, en roulant)"))
        root.addView(hint("Capture un logcat NON filtré (tout, horodaté). Un passager tape la flèche " +
                "affichée sur le HUD à chaque changement → on décode les codes de guidage. Passager uniquement."))
        root.addView(Button(this).apply {
            text = "▶  Ouvrir l'enregistreur logcat brut"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@HudDiagActivity, HudRawCaptureActivity::class.java)) }
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })

        // ── TOOL 3 — CAN → HUD bench (does the HUD MCU consume our nav CAN frames?) ──
        root.addView(sectionHeader("③ Bench CAN → HUD (firmware à flèches)"))
        root.addView(hint("⚠️ COUPE d'abord la navigation de la voiture. On envoie NOUS-MÊMES un guidage " +
                "sur le CAN (flèche + distance + route) : tout droit, gauche, droite (~6 s chacun). Regarde le " +
                "PARE-BRISE : si une flèche apparaît, le HUD est pilotable par nous. Puis réponds OUI/NON → ZIP."))
        benchBtn = Button(this).apply {
            text = "▶  Émettre un guidage CAN → regarde le HUD"
            isAllCaps = false
            setOnClickListener { startCanHudBench() }
        }
        root.addView(benchBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })

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
        log("Ready.")
    }

    // ── TOOL 1 — confirmation sequence ──────────────────────────────────────
    private fun startConfirmation() {
        confirmBtn.isEnabled = false
        bar.visibility = View.VISIBLE
        report.setLength(0)
        report.append("=== DL3 HUD CONTROL CONFIRMATION ===\n")
            .append("${com.byd.dashcast.BuildConfig.VERSION_NAME} (${com.byd.dashcast.BuildConfig.VERSION_CODE}) — ")
            .append("${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n")
            .append("HUD firmware (inswver): ${Platform.hudFirmwareVersion()}\n")
            .append("Feature ids proven from OEM com.byd.carsettings HalSetter (log.docx), ECU 0x4C1/0xE.\n")
            .append("rc=0 → SDK accepted the write.\n\n")
        stepIdx = 0
        log("──── confirmation started ────")
        runNextStep()
    }

    private fun runNextStep() {
        if (stepIdx >= steps.size) { finishConfirmation(); return }
        val step = steps[stepIdx]
        log("▶ ${step.title} — regarde le HUD…")
        bg {
            val rc = try { step.run() }
                     catch (t: Throwable) { "EXCEPTION ${t.javaClass.simpleName}: ${t.message}" }
            runOnUiThread { askStep(step, rc) }
        }
    }

    /** Popup after each command: says what was sent + asks whether the expected effect happened. */
    private fun askStep(step: Step, rc: String) {
        log("   $rc")
        AlertDialog.Builder(this)
            .setTitle(step.title)
            .setMessage(step.question + "\n\n(envoyé: $rc)")
            .setCancelable(false)
            .setPositiveButton("✓ OUI") { _, _ -> recordAndNext(step, rc, "YES") }
            .setNegativeButton("✗ NON") { _, _ -> askStepNote(step, rc) }
            .setNeutralButton("Passer") { _, _ -> recordAndNext(step, rc, "SKIP") }
            .show()
    }

    /** On NON, offer an optional free-text note before recording. */
    private fun askStepNote(step: Step, rc: String) {
        val input = EditText(this).apply {
            hint = "Qu'as-tu vu à la place ? (optionnel)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(step.title + " — NON")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Envoyer") { _, _ ->
                val note = input.text.toString().trim()
                recordAndNext(step, rc, "NO" + if (note.isNotEmpty()) " — $note" else "")
            }
            .show()
    }

    private fun recordAndNext(step: Step, rc: String, answer: String) {
        report.append("[${step.id}] $rc\n         réponse: $answer\n\n")
        log("   → $answer")
        stepIdx++
        runNextStep()
    }

    private fun finishConfirmation() {
        log("──── building zip ────")
        bg {
            // Leave the HUD ON (predictable state) after the OFF confirmation step.
            val restore = try { setInt(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON) }
                          catch (t: Throwable) { "EXC ${t.message}" }
            report.append("[restore] SET_HUD_SWITCH=1 (HUD left ON)  rc=$restore\n")
            report.append("\nNOTE: brightness/height/angle were swept for the test — re-adjust the fine " +
                    "values in the car HUD settings if needed.\n")
            val work = File(cacheDir, "hud_confirm_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }
            File(work, "01_confirm.txt").writeText(report.toString())
            File(work, "02_props.txt").writeText(sh("getprop 2>/dev/null | grep -iE 'hud|fission_single_os|model|inswver'"))
            val zip = HudCaptureSupport.zipDir(work)
            log("zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 HUD control confirmation — ${Build.PRODUCT}")
            runOnUiThread {
                confirmBtn.isEnabled = true
                bar.visibility = View.GONE
            }
        }
    }

    /** Compact firmware label parsed from inswver (e.g. "SX326 (2026-02-03)"). */
    private fun firmwareLabel(): String {
        val v = try { Platform.hudFirmwareVersion() } catch (_: Throwable) { "" }
        if (v.isEmpty()) return "?"
        val sx = Regex("S[A-Z]?[0-9]+").find(v)?.value ?: "?"
        val d = Regex("20[0-9]{6}").find(v)?.value ?: ""
        val date = if (d.length == 8) "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" else "?"
        return "$sx ($date)  [$v]"
    }

    // ── TOOL 3 — CAN → HUD bench ─────────────────────────────────────────────
    // Writes nav guidance ourselves on BYDAutoInstrumentDevice (icon + distance + road + status)
    // with the OEM nav OFF, to test whether the windshield-HUD MCU consumes our CAN guidance frames
    // directly (nav-agnostic). If the HUD shows an arrow → we can drive it; if not → HUD content is
    // rendered by the OEM nav (e.g. Telenav) and not CAN-injectable. Only meaningful on arrow-capable
    // firmware (recent inswver) — the label is captured in the zip so we can correlate.
    private fun startCanHudBench() {
        benchBtn.isEnabled = false
        bar.visibility = View.VISIBLE
        log("──── CAN→HUD bench started (OEM nav must be OFF) ────")
        bg {
            val sb = StringBuilder("=== DL3 CAN → HUD BENCH (we write nav guidance; OEM nav OFF) ===\n")
            sb.append("${com.byd.dashcast.BuildConfig.VERSION_NAME} (${com.byd.dashcast.BuildConfig.VERSION_CODE}) — ")
                .append("${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n")
                .append("HUD firmware (inswver): ${Platform.hudFirmwareVersion()}\n")
                .append("Writes BYDAutoInstrumentDevice guidance (INSTRUMENT_GUIDE_INFO_SIMPLE + distance + road + status).\n\n")
            fun step(label: String, block: () -> Unit) {
                val line = try { block(); "$label ok" } catch (t: Throwable) { "$label ERR ${t.message}" }
                sb.append(line).append('\n'); log(line)
            }
            step("SET_HUD_SWITCH=1") { CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON) }
            step("setNaviActive(true)") { CanBusController.setNaviActive(true) }
            // Sweep three unambiguous maneuvers; each SUSTAINED ~6 s (counting distance down) like a real nav.
            val icons = listOf(
                Triple("TOUT DROIT", CanBusController.ICON_STRAIGHT_SOLID, "STRAIGHT"),
                Triple("GAUCHE", CanBusController.ICON_TURN_LEFT, "LEFT"),
                Triple("DROITE", CanBusController.ICON_TURN_RIGHT, "RIGHT"))
            for ((fr, icon, en) in icons) {
                log("▶▶ REGARDE LE PARE-BRISE — '$fr' (icône CAN $icon) ~6 s")
                var dist = 300
                var rc = "?"
                repeat(6) {
                    rc = try { CanBusController.sendSimpleGuidance(icon, dist); "0" } catch (t: Throwable) { "ERR ${t.message}" }
                    try { CanBusController.sendNextStreetName("TEST $fr") } catch (_: Throwable) {}
                    try { CanBusController.sendRestRoute(0, 5, 1200) } catch (_: Throwable) {}
                    dist = (dist - 40).coerceAtLeast(40); sleep(1000)
                }
                sb.append("[$en] icon=$icon sustained 6s (dist 300→) rc=$rc\n")
            }
            runOnUiThread { askBench(sb) }
        }
    }

    /** Popup: did the WINDSHIELD HUD render an arrow from our CAN guidance? Answer baked into the zip. */
    private fun askBench(sb: StringBuilder) {
        AlertDialog.Builder(this)
            .setTitle("Bench CAN → HUD")
            .setMessage("Pendant le test (nav voiture ÉTEINTE), le HUD du PARE-BRISE a-t-il affiché une " +
                    "FLÈCHE de direction (tout droit / gauche / droite) ou une info de nav ?")
            .setCancelable(false)
            .setPositiveButton("✓ OUI, flèche") { _, _ -> finishBench(sb, "YES — arrow on HUD") }
            .setNegativeButton("✗ NON, rien") { _, _ -> askBenchNote(sb) }
            .setNeutralButton("Partiel/bizarre") { _, _ -> askBenchNote(sb) }
            .show()
    }

    private fun askBenchNote(sb: StringBuilder) {
        val input = EditText(this).apply {
            hint = "Qu'as-tu vu (HUD et/ou cluster) ? (optionnel)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Bench CAN → HUD — détail")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Envoyer") { _, _ ->
                val note = input.text.toString().trim()
                finishBench(sb, "NO/PARTIAL" + if (note.isNotEmpty()) " — $note" else "")
            }
            .show()
    }

    private fun finishBench(sb: StringBuilder, answer: String) {
        log("──── bench result: $answer — building zip ────")
        bg {
            sb.append("\nRÉSULTAT (HUD arrow visible): $answer\n")
            try { CanBusController.setNaviActive(false) } catch (_: Throwable) {}  // clean up injected nav
            val work = File(cacheDir, "hud_canbench_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }
            File(work, "01_can_bench.txt").writeText(sb.toString())
            File(work, "02_props.txt").writeText(sh("getprop 2>/dev/null | grep -iE 'hud|inswver|fission_single_os|model'"))
            val zip = HudCaptureSupport.zipDir(work)
            log("zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 CAN→HUD bench [${firmwareLabel()}] — $answer")
            runOnUiThread { benchBtn.isEnabled = true; bar.visibility = View.GONE }
        }
    }

    // ── write helpers ───────────────────────────────────────────────────────
    private fun setInt(featureId: Int, value: Int): String =
        try { CanBusController.setSettingFeature(featureId, value).toString() }
        catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }

    private fun setDouble(featureId: Int, value: Double): String =
        try { ProxyClient.canSettingDouble(featureId, value).toString() }
        catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }

    /** Ramps [values] into an int feature (with a pause between each) so a change is visible. */
    private fun ramp(label: String, values: IntArray, write: (Int) -> String): String {
        val sb = StringBuilder(label).append(" ramp ")
        for (v in values) { sb.append("$v:").append(write(v)).append(' '); sleep(700) }
        return sb.toString().trim()
    }

    private fun rampD(label: String, values: DoubleArray, write: (Double) -> String): String {
        val sb = StringBuilder(label).append(" ramp ")
        for (v in values) { sb.append("$v:").append(write(v)).append(' '); sleep(700) }
        return sb.toString().trim()
    }

    private fun uploadZip(zip: File, caption: String) {
        if (!TelegramBugReporter.isConfigured()) {
            log("Telegram non configuré — zip: ${zip.absolutePath}"); return
        }
        TelegramBugReporter.send(this, zip, caption, HudCaptureSupport.HUD_TEST_THREAD,
            object : TelegramBugReporter.Callback {
                override fun onSent() { log("✓ envoyé sur Telegram (topic ${HudCaptureSupport.HUD_TEST_THREAD}). Terminé.") }
                override fun onFailed(message: String) { log("✗ échec envoi: $message — zip: ${zip.absolutePath}") }
            })
    }

    // ── tiny view + thread helpers ──────────────────────────────────────────
    private inline fun bg(crossinline work: () -> Unit) {
        Thread { try { work() } catch (t: Throwable) { log("ERR: ${t.javaClass.simpleName}: ${t.message}") } }.start()
    }

    private fun sh(cmd: String): String =
        try { ProxyClient.runShell(cmd) ?: "" }
        catch (t: Throwable) { "ERR [$cmd]: ${t.message}" }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun log(msg: String) = runOnUiThread {
        AppLogger.i("HudDiagBench", msg)
        out.append("[${stamp.format(Date())}] $msg\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 12f }
}
