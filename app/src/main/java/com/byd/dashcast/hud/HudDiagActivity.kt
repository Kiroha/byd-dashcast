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
 * Two tools only:
 *  1. **Confirm the discoveries** — we send each HUD command ourselves and, after every command,
 *     ask the tester whether the expected effect happened (OK / KO). The answers + SDK result
 *     codes are zipped and uploaded to Telegram.
 *  2. **Raw logcat recorder** — opens [HudRawCaptureActivity]: an unfiltered logcat capture with
 *     on-screen arrow buttons whose taps are injected into the log, to decode the turn-by-turn
 *     guidance codes while driving.
 *
 * Dev-only screen, built programmatically (no layout/strings → no i18n burden).
 */
@android.annotation.SuppressLint("SetTextI18n")
class HudDiagActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private lateinit var confirmBtn: Button
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
