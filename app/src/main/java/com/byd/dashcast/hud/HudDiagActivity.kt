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
import com.byd.dashcast.R
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
 * Dev-only screen, built programmatically. The UI scaffolding stays hardcoded (EN/FR), but the
 * tester-facing RESULT choices + buttons ARE translated (R.string.hud_bench_* / hud_confirm_*) so
 * international testers report accurately; each choice records a canonical English tag into the zip
 * (keeps the analyzer's YES / NO-PARTIAL parsing + adds a machine-readable sub-reason).
 */
@android.annotation.SuppressLint("SetTextI18n")
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var confirmBtn: Button
    private lateinit var benchBtn: Button
    private lateinit var sendInfo2Btn: Button
    private lateinit var bar: ProgressBar

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val mp get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wc get() = LinearLayout.LayoutParams.WRAP_CONTENT

    // ── confirmation-sequence state ─────────────────────────────────────────
    private val report = StringBuilder()
    private var stepIdx = 0

    /** Bench button to re-enable when [finishBench] completes — shared between Tool 3 (CAN) and
     *  Tool 4 (sendInfo2), which reuse the same [askBench] / [finishBench] result-picker + upload flow. */
    private var activeBenchButton: Button? = null

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
        // fresh daemon that speaks protocol v17 = the new double/pull verbs). Then register the
        // BYDAuto push-feedback listener EARLY so the HUD's actual switch + display-mode (push-only,
        // get() returns 0) is already captured by the time a bench/confirm result is zipped. Guarded.
        Thread {
            try { ProxyClient.connect(this) } catch (_: Throwable) {}
            try { ProxyClient.canListenStart() } catch (_: Throwable) {}
        }.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Production-steering banner (translated, 13 locales): the HUD nav feature is LIVE, so
        // testers should DRIVE with Maps/Waze + report via the 🐞 button — not run these R&D tools.
        root.addView(TextView(this).apply {
            text = getString(R.string.hud_prod_banner)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(android.graphics.Color.argb(40, 33, 150, 243))
        })

        root.addView(TextView(this).apply {
            text = "DiLink 3 HUD bench. Park first. HUD control is proven — these tools confirm it " +
                    "and decode the turn-by-turn guidance codes."
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
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

        // ── TOOL 4 — sendInfo2 NaviInfo injection (native OEM channel, experimental) ──
        // AutoContainer.sendInfo2(4, NaviInfo-flatbuffer) is the SAME binder call the OEM's own
        // nav app (AmapService) uses to drive the HUD — not the CAN bus. Unlike Tool 3 this bypasses
        // BYDAutoInstrumentDevice entirely, going straight through the container service.
        root.addView(sectionHeader("④ Injection NaviInfo via sendInfo2 (canal natif OEM, expérimental)"))
        root.addView(hint("⚠️ COUPE d'abord la navigation de la voiture. On envoie un guidage de test " +
                "encodé dans le MÊME format FlatBuffer que l'appli de nav OEM, via sendInfo2(4, …) — pas le CAN. " +
                "Regarde le PARE-BRISE : si un guidage apparaît, ce canal est pilotable directement depuis DashCast."))
        sendInfo2Btn = Button(this).apply {
            text = "▶  Envoyer un NaviInfo test via sendInfo2(4) → regarde le HUD"
            isAllCaps = false
            setOnClickListener { startSendInfo2Bench() }
        }
        root.addView(sendInfo2Btn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(6) })

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
            .setPositiveButton(getString(R.string.hud_confirm_yes)) { _, _ -> recordAndNext(step, rc, "YES") }
            .setNegativeButton(getString(R.string.hud_confirm_no)) { _, _ -> askStepNote(step, rc) }
            .setNeutralButton(getString(R.string.hud_confirm_skip)) { _, _ -> recordAndNext(step, rc, "SKIP") }
            .show()
    }

    /** On NON, offer an optional free-text note before recording. */
    private fun askStepNote(step: Step, rc: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.hud_bench_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(step.title + " — " + getString(R.string.hud_confirm_no))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.hud_report_send)) { _, _ ->
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
            try {
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
            writeDiagLogs(work)
            val zip = HudCaptureSupport.zipDir(work)
            log("zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, "DL3 HUD control confirmation — ${Build.PRODUCT}")
            } finally {
                // Always restore the UI, even if the zip/upload/diag work threw (bg's outer
                // catch only logs) — otherwise the button stays disabled + spinner visible.
                runOnUiThread {
                    confirmBtn.isEnabled = true
                    bar.visibility = View.GONE
                    showProdNudge()
                }
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
        activeBenchButton = benchBtn
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

    // ── TOOL 4 — sendInfo2 NaviInfo injection ───────────────────────────────
    // Sends a test byd.fbs.naviInfo.NaviInfo FlatBuffer via AutoContainer.sendInfo2(4, bytes) — the
    // exact binder call the OEM's own nav app (AmapService) uses to drive the HUD. Unlike Tool 3,
    // this never touches BYDAutoInstrumentDevice/CAN: it goes straight through the container
    // service, using the same uid-2000 checkSignatures fast-path already proven for sendInfo(1000,…).
    private fun startSendInfo2Bench() {
        activeBenchButton = sendInfo2Btn
        sendInfo2Btn.isEnabled = false
        bar.visibility = View.VISIBLE
        log("──── sendInfo2 NaviInfo bench started (OEM nav must be OFF) ────")
        bg {
            val sb = StringBuilder("=== DL3 sendInfo2(4, NaviInfo) BENCH — native OEM channel (uid 2000) ===\n")
            sb.append("${com.byd.dashcast.BuildConfig.VERSION_NAME} (${com.byd.dashcast.BuildConfig.VERSION_CODE}) — ")
                .append("${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n")
                .append("HUD firmware (inswver): ${Platform.hudFirmwareVersion()}\n")
                .append("Sends byd.fbs.naviInfo.NaviInfo via AutoContainer.sendInfo2(4, bytes) — same channel as AmapService.\n\n")
            fun step(label: String, block: () -> Unit) {
                val line = try { block(); "$label ok" } catch (t: Throwable) { "$label ERR ${t.message}" }
                sb.append(line).append('\n'); log(line)
            }
            step("SET_HUD_SWITCH=1") { CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON) }
            val maneuvers = listOf(
                Triple("TOUT DROIT", 1, "STRAIGHT"),
                Triple("GAUCHE", 2, "LEFT"),
                Triple("DROITE", 3, "RIGHT"))
            for ((fr, icon, en) in maneuvers) {
                log("▶▶ REGARDE LE PARE-BRISE — '$fr' (nextTurnIcon=$icon, sendInfo2) ~6 s")
                var dist = 300
                repeat(6) {
                    val payload = NaviInfoPayloadBuilder.build(
                        naviState = 1,
                        nextRouteName = "DashCast test $fr",
                        curToSegmentDist = dist,
                        nextTurnIcon = icon,
                        routeRemainTime = 300,
                        routeRemainDist = 1200)
                    step("sendInfo2(4, ${payload.size}B, icon=$icon, dist=$dist)") {
                        ProxyClient.autoContainerSendInfo2(4, payload)
                    }
                    dist = (dist - 40).coerceAtLeast(40); sleep(1000)
                }
                sb.append("[$en] nextTurnIcon=$icon sustained 6s (dist 300→)\n")
            }
            runOnUiThread { askBench(sb) }
        }
    }

    /**
     * Structured, TRANSLATED result picker: the tester sees the outcomes in their own language,
     * but a canonical ENGLISH tag is recorded into the zip (analyzer counts YES vs NO/PARTIAL and
     * gets a machine-readable sub-reason). The HUD's real switch/mode is captured separately in
     * 04_hud_state.txt, so options describe only what the tester SAW.
     */
    private fun askBench(sb: StringBuilder) {
        val options = listOf(
            getString(R.string.hud_bench_opt_ok)       to "YES — arrow on HUD",
            getString(R.string.hud_bench_opt_wrongdir) to "NO/PARTIAL — arrow wrong direction",
            getString(R.string.hud_bench_opt_partial)  to "NO/PARTIAL — distance/text but no clear arrow",
            getString(R.string.hud_bench_opt_nothing)  to "NO/PARTIAL — nothing on HUD",
            getString(R.string.hud_bench_opt_other)    to null,   // → optional free-text note
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hud_bench_result_title))
            .setCancelable(false)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                val tag = options[which].second
                if (tag == null) askBenchNote(sb) else finishBench(sb, tag)
            }
            .show()
    }

    private fun askBenchNote(sb: StringBuilder) {
        val input = EditText(this).apply {
            hint = getString(R.string.hud_bench_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hud_bench_note_title))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.hud_report_send)) { _, _ ->
                val note = input.text.toString().trim()
                finishBench(sb, "NO/PARTIAL — other" + if (note.isNotEmpty()) " — $note" else "")
            }
            .show()
    }

    private fun finishBench(sb: StringBuilder, answer: String) {
        log("──── bench result: $answer — building zip ────")
        val viaSendInfo2 = activeBenchButton === sendInfo2Btn
        val zipPrefix = if (viaSendInfo2) "hud_sendinfo2bench_" else "hud_canbench_"
        val caption = if (viaSendInfo2) "DL3 sendInfo2→HUD bench [${firmwareLabel()}] — $answer"
                      else "DL3 CAN→HUD bench [${firmwareLabel()}] — $answer"
        bg {
            try {
            sb.append("\nRÉSULTAT (HUD arrow visible): $answer\n")
            try { CanBusController.setNaviActive(false) } catch (_: Throwable) {}  // clean up injected nav
            val work = File(cacheDir, zipPrefix +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())).apply { mkdirs() }
            File(work, "01_can_bench.txt").writeText(sb.toString())
            File(work, "02_props.txt").writeText(sh("getprop 2>/dev/null | grep -iE 'hud|inswver|fission_single_os|model'"))
            writeDiagLogs(work)
            val zip = HudCaptureSupport.zipDir(work)
            log("zip: ${zip.name} (${zip.length() / 1024} KB)")
            uploadZip(zip, caption)
            } finally {
                // Always restore the UI even if the zip/upload/diag work threw (bg's outer catch
                // only logs) — otherwise the button stays disabled + spinner visible until recreate.
                runOnUiThread { activeBenchButton?.isEnabled = true; bar.visibility = View.GONE; showProdNudge() }
            }
        }
    }

    /** After a bench/confirm upload, nudge the tester toward the LIVE feature (drive Maps/Waze +
     *  report via 🐞) instead of these R&D tools. Translated (R.string.hud_prod_nudge). */
    private fun showProdNudge() {
        try {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.hud_prod_nudge))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) { /* activity finishing — ignore */ }
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

    /**
     * Extra diagnostic logs added to every HUD bug-report zip so a NO can be ANALYSED (not just
     * counted): recent unfiltered logcat, the HUD's ACTUAL switch + display-mode (from the BYDAuto
     * push-feedback listener — get() is push-only, so drain() is the only truth), and the
     * foreground / nav app + display state. English/technical (the diagnostic-bundle i18n
     * exception). Runs off the main thread (called from finishBench / finishConfirmation's bg{}).
     */
    private fun writeDiagLogs(work: File) {
        // Recent unfiltered logcat, bounded for the ~1 MB binder reply parcel.
        File(work, "03_logcat.txt").writeText(
            sh("logcat -d -v threadtime -t 2000 2>/dev/null | head -c 400000"))
        // HUD's real state: 0x38B0001C = switch (1=on/2=off), 0x38B0000D / 0x42E00008 = mode (1..6).
        val hud = try { ProxyClient.canListenDrain() ?: "" } catch (t: Throwable) { "ERR ${t.message}" }
        File(work, "04_hud_state.txt").writeText(
            "=== HUD push-feedback snapshot (canListenDrain; get() is push-only) ===\n" +
            "0x38B0001C = HUD switch (1=on, 2=off) | 0x38B0000D / 0x42E00008 = display mode (1..6)\n\n" + hud)
        // Foreground app + activities per display + displays + recent SELinux denials.
        File(work, "05_device_state.txt").writeText(
            "== focused window ==\n" +
            sh("dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp' | head -c 4000") +
            "\n== activities ==\n" +
            sh("dumpsys activity activities 2>/dev/null | grep -iE 'Display #|Task id=|realActivity|mResumed=true|topResumedActivity' | head -c 12000") +
            "\n== displays ==\n" +
            sh("dumpsys display 2>/dev/null | grep -iE 'Display id|uniqueId|mBaseDisplayInfo|FLAG_PRESENTATION' | head -c 8000") +
            "\n== recent avc denials ==\n" +
            sh("logcat -d -t 600 2>/dev/null | grep -iE 'avc: .*denied' | head -c 4000"))
    }

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
