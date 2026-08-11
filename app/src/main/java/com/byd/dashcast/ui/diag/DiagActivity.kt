package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.hud.HudDiagActivity
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.R
import com.byd.dashcast.proxy.daemon.Phase4ProcessVerbs
import com.byd.dashcast.report.AzureBlobUploader
import com.byd.dashcast.report.ReportChannel
import com.byd.dashcast.report.ReportStore
import com.byd.dashcast.report.TelegramBugReporter
import com.byd.dashcast.util.AppLogger
import java.io.File

/**
 * Diagnostics host — rebuilt in Kotlin, English-only by project rule (SetTextI18n exempt).
 *
 * Hosts several tools, including:
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
 *  3. **OEM display registry** ([runFissionRegistryProbe]) and **projection trace**
 *     ([runProjectionTrace]) — read-only probes of the native `FissionHostSvc` display registry
 *     found on DiLink 3, never called before this release.
 *  4. **Arm container callback** ([runArmContainerCallback]) — registers the daemon's own listener
 *     on `AutoContainer.registerCallback`, a documented-but-never-called AIDL method, so a future
 *     bug report can capture the native service's own lifecycle pushes.
 *
 * Every long-running probe on this screen shares [sBusy] so at most one runs at a time — they all
 * write into the same [logView].
 */
class DiagActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var runBtn: Button
    private lateinit var probeBtn: Button
    private lateinit var fissionRegistryBtn: Button
    private lateinit var armCallbackBtn: Button
    private lateinit var traceBtn: Button
    @Volatile private var lastWork: File? = null

    /** Shared across every probe on this screen — they all write into the same [logView], and
     *  running two at once (e.g. a multi-minute APK extraction plus a 60s trace) would interleave
     *  their output with no attribution of which button produced which line. Single-shot manual
     *  diagnostics, so the correct behaviour on contention is "tell the tester to wait", not queue. */
    private val sBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun allDiagButtons() = listOf(runBtn, probeBtn, fissionRegistryBtn, armCallbackBtn, traceBtn)

    /** True (and claims [sBusy]) iff nothing else on this screen is running; otherwise logs and
     *  returns false without touching any button state. */
    private fun claimBusyOrWarn(): Boolean {
        if (sBusy.compareAndSet(false, true)) return true
        log("Another diagnostic is already running on this screen — wait for it to finish first.")
        return false
    }

    /** Releases [sBusy] and re-enables every button — the single completion path for [start] and
     *  [runOemProbes], whose async callback chains do not fit the simple "spawn thread, finally
     *  re-enable" shape the three newer probes use directly. */
    private fun releaseBusy() {
        sBusy.set(false)
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = true } }
    }

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

        fissionRegistryBtn = Button(this).apply {
            text = "OEM display registry (read-only) — DL3"
            setOnClickListener { runFissionRegistryProbe() }
        }
        root.addView(fissionRegistryBtn)

        armCallbackBtn = Button(this).apply {
            text = "Arm container callback (diagnostic)"
            setOnClickListener { runArmContainerCallback() }
        }
        root.addView(armCallbackBtn)

        traceBtn = Button(this).apply {
            text = "Trace projection state (60s) — DL3"
            setOnClickListener { runProjectionTrace() }
        }
        root.addView(traceBtn)

        // Opt-in raw nav-notification capture (to calibrate the Waze/Maps text parser). OFF by
        // default: it logs the ACTUAL notification text — destination / current road / ETA, i.e.
        // location PII — into the journal that bug reports carry. Turn ON, drive a route, send a
        // bug report, then turn OFF.
        root.addView(CheckBox(this).apply {
            text = "Capture raw nav-notification text (Waze diagnostic)"
            isChecked = ClusterPrefs.isNavRawCaptureEnabled(this@DiagActivity)
            setOnCheckedChangeListener { _, checked ->
                ClusterPrefs.setNavRawCaptureEnabled(this@DiagActivity, checked)
                log(if (checked)
                    "Raw nav-text capture ON — start a nav route (Waze/Maps), let a few maneuvers " +
                        "pass, then send a bug report. This logs destination/road/ETA text; turn it OFF after."
                else "Raw nav-text capture OFF.")
            }
        })

        // Provisioning entry point. It lives here rather than in Settings on purpose: it is a
        // technical action, and DiagActivity is the screen app/lint.xml documents as the
        // English-only exception, so it costs no translated string. A new Settings row would have
        // cost 13.
        root.addView(Button(this).apply {
            text = "Pair reporting channel — put ${ReportChannel.IMPORT_NAME} in Download"
            setOnClickListener {
                isEnabled = false
                log("pairing…")
                ReportChannel.importFromDevice(this@DiagActivity) { outcome ->
                    runOnUiThread { log(outcome); isEnabled = true }
                }
            }
        })

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
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
        runOnUiThread { logView.text = "" }
        log("Collecting…")
        Thread({
            // With an Azure container configured the 50 MB messaging ceiling no longer applies, so
            // the planner may pull the big OEM artefacts (cluster Qt themes, the renderer .so) that
            // were previously skipped for size. Set before planning — it changes every budget.
            ApkExtractionPolicy.largeSink = AzureBlobUploader.isConfigured()
            if (ApkExtractionPolicy.largeSink) log("Azure container configured — large pull enabled.")
            val zip: File = try {
                val plan = BydApkExtractionBundle.plan(this) { line -> log(line) }
                lastWork = plan.workDir
                log("Selected ${plan.accepted.size} OEM APK(s) + ${plan.acceptedNative.size} native, " +
                    "${plan.payloadBytes / 1024} KB" +
                    (if (plan.manifestSkips.isEmpty()) "" else " (${plan.manifestSkips.size} skipped — see manifest)"))

                // Free-space guard. Nothing checked it before, and with the Azure sink the budget
                // went from 42 MB to 2 GB: the pull copies the payload into the work directory and
                // then writes a zip beside it, so the peak is roughly twice the payload on the same
                // volume. The planned size is used rather than the ceiling — refusing on the
                // theoretical 2 GB would block units that can perfectly well take the real pull.
                // The payload is copied into the work directory on the cache volume, then zipped
                // into the report store on external storage. These are two different volumes on
                // these units, so each is checked for one payload rather than one volume for two.
                val need = plan.payloadBytes
                val store = ReportStore.dir(this)
                val short = when {
                    !ReportStore.hasRoomFor(cacheDir, need) -> cacheDir
                    !ReportStore.hasRoomFor(store, need) -> store
                    else -> null
                }
                if (short != null) {
                    val freeMb = ReportStore.usableBytes(short) / (1024 * 1024)
                    log("aborted: needs ~${need / (1024 * 1024)} MB free on ${short.absolutePath},")
                    log("only $freeMb MB available. Free some space and run this again.")
                    BydApkExtractionBundle.cleanup(lastWork); lastWork = null
                    resetButton()
                    return@Thread
                }
                ReportStore.prune(this)
                BydApkExtractionBundle.materialize(
                    plan, File(store, plan.workDir.name + ".zip")) { line -> log(line) }
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
                resetButton()
                return@Thread
            }
            log("zip ready: ${zip.name} (${zip.length() / 1024} KB)")

            // Azure first when available: it has no practical size limit, and it is the only sink
            // that can take a pull containing the 100 MB+ OEM artefacts.
            if (AzureBlobUploader.isConfigured()) {
                log("uploading to Azure…")
                AzureBlobUploader.upload(zip, "dilink/${zip.name}", { line: String -> log(line) },
                    object : AzureBlobUploader.Callback {
                        override fun onUploaded(url: String) {
                            log("✓ uploaded to Azure:\n$url")
                            log("Done — tell the maintainer the file name above.")
                            BydApkExtractionBundle.cleanup(lastWork); lastWork = null
                            resetButton()
                        }
                        override fun onFailed(message: String) {
                            // Fall back rather than losing the pull: a bundle that still fits under
                            // the messaging ceiling can go out the old way.
                            log("✗ Azure upload failed: $message")
                            if (TelegramBugReporter.isConfigured()
                                    && zip.length() < 45L * 1024 * 1024) {
                                log("falling back to Telegram…")
                                sendViaTelegram(zip)
                            } else {
                                keepLocally(zip)
                            }
                        }
                    })
                return@Thread
            }

            if (!TelegramBugReporter.isConfigured()) {
                log("Telegram not configured.")
                keepLocally(zip)
                return@Thread
            }
            sendViaTelegram(zip)
        }, "byd-apk-extract").start()
    }

    /** Uploads the bundle through the report bot — the pre-Azure path, also the fallback. */
    private fun sendViaTelegram(zip: File) {
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
                        log("✗ upload failed: $message")
                        keepLocally(zip)
                    }
                })
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
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
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
                // per-probe catch would otherwise leave every button dead until the screen is recreated.
                releaseBusy()
            }
        }, "oem-cluster-probes").start()
    }

    /**
     * Read-only probe of the native `FissionHostSvc` display registry (RE'd from a real DL3
     * firmware pull, [docs/CLUSTER_DL5_PROJECTION_HANDOFF.md] context — a pure getter, no
     * side effect on whatever currently owns the registry). "SERVICE NOT FOUND" is the expected,
     * useful answer on every platform but DL3, not a failure.
     */
    private fun runFissionRegistryProbe() {
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
        runOnUiThread { logView.text = "" }
        log("Reading FissionHostSvc.getAutoCarDisplay()…")
        Thread({
            try {
                val result = try {
                    ProxyClient.fissionGetAutoCarDisplay()
                } catch (t: Throwable) {
                    "ERR ${t.javaClass.simpleName}: ${t.message}"
                }
                log(result ?: "(null)")
                log("")
                log("Send a bug report now so this result reaches us.")
            } finally {
                releaseBusy()
            }
        }, "fission-registry-probe").start()
    }

    /**
     * Arms `AutoContainer.registerCallback` (AIDL transaction 4) in the daemon. The registration
     * only confirms here; its payoff — a `serviceDied()`/`receivedX()` push — is asynchronous and
     * lands in the daemon's own transcript (`--- PROXYDAEMON LOG ---`) whenever it happens, which
     * may be a future bug report entirely. Must be re-armed after every daemon respawn (app
     * update, `versionCode` bump) since the registration lives only for that process's lifetime.
     */
    private fun runArmContainerCallback() {
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
        runOnUiThread { logView.text = "" }
        log("Registering AutoContainer callback in the daemon…")
        Thread({
            try {
                val result = try {
                    val rc = ProxyClient.autoContainerRegisterCallback()
                    if (rc == Phase4ProcessVerbs.REGISTER_CALLBACK_NO_RESULT_FIELD) {
                        "registerCallback ACCEPTED — no result field in the reply (layout unconfirmed)"
                    } else {
                        "registerCallback ACCEPTED — result=$rc"
                    }
                } catch (t: Throwable) {
                    "ERR ${t.javaClass.simpleName}: ${t.message}"
                }
                log(result)
                log("")
                log("Armed for as long as the daemon stays up. Any native push is now logged into")
                log("the daemon transcript — it will show up in a LATER bug report, not this one.")
            } finally {
                releaseBusy()
            }
        }, "arm-container-callback").start()
    }

    /**
     * Samples the FissionHostSvc registry every ~2s for 60s (server-side hard cap 90s) while the
     * tester runs a normal projection start/stop cycle, to see whether the registry moves when
     * projection activates — the open question behind whether `setAutoCarDisplay` would be safe to
     * ever call. Read-only throughout; nothing is written to the registry.
     */
    private fun runProjectionTrace() {
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
        runOnUiThread { logView.text = "" }
        log("Tracing started — do your NORMAL projection start/stop cycle now (e.g. launch Waze")
        log("on the cluster, then stop projection). Results in 60s…")
        Thread({
            try {
                try {
                    ProxyClient.projectionTraceStart()
                } catch (t: Throwable) {
                    log("ERR could not arm tracer: ${t.javaClass.simpleName}: ${t.message}")
                    return@Thread
                }
                try {
                    Thread.sleep(60_000L)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val result = try {
                    ProxyClient.projectionTraceDrain()
                } catch (t: Throwable) {
                    "ERR ${t.javaClass.simpleName}: ${t.message}"
                }
                log("")
                log("── trace (one line per change) ──")
                log(result ?: "(null)")
                log("")
                log("Send a bug report now so this result reaches us.")
            } finally {
                releaseBusy()
            }
        }, "projection-trace").start()
    }

    @SuppressLint("SetTextI18n")
    /**
     * Last-resort exit for an extraction that could not be uploaded.
     *
     * The archive already lives in the report store, so the path printed here is one a file manager
     * or an adb pull can actually reach — the previous message named a cacheDir path that nobody
     * could open. The system chooser is only offered for archives small enough for it to mean
     * something: these pulls routinely exceed a hundred megabytes, and handing such a file to a
     * chooser wastes the tester's time rather than helping them.
     */
    private fun keepLocally(zip: File) {
        log("kept locally at:\n${zip.absolutePath}")
        if (zip.length() < 45L * 1024 * 1024) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    AppLogger.shareFile(this, zip,
                        getString(R.string.bug_share_subject), getString(R.string.bug_share_chooser))
                } catch (t: Throwable) {
                    log("share unavailable (${t.javaClass.simpleName}) — pull the file above")
                }
            }
        } else {
            log("too large to share from the car — pull it over adb.")
        }
        resetButton()
    }

    private fun log(line: String) = runOnUiThread {
        AppLogger.i(TAG, line)
        logView.append(line + "\n")
    }

    private fun resetButton() = releaseBusy()

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
