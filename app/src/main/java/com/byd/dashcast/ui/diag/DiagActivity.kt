package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.hud.HudDiagActivity
import com.byd.dashcast.hud.HudCaptureSupport
import com.byd.dashcast.hud.MapNotificationListenerService
import com.byd.dashcast.hud.NavigationIconCapture
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
import java.lang.ref.WeakReference

/**
 * Diagnostics host — rebuilt in Kotlin, English-only by project rule (SetTextI18n exempt).
 *
 * Hosts several tools, including:
 *  1. **Export complet accessible** — one visible export action for broad firmware files and
 *     positively identified installed APKs. APP/SHELL discovery, standalone ZIPs, disk-backed
 *     inventory and verified resume run on a worker; sharing is manual and no payload executes.
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
    private lateinit var navIconCaptureBtn: Button
    private lateinit var hudBenchBtn: Button
    private lateinit var reportingPairBtn: Button
    private var reportingPairInFlight = false
    private lateinit var fullExportBtn: Button
    private lateinit var fullExportStatus: TextView
    private lateinit var fullExportCurrent: TextView
    private lateinit var cancelFullExportBtn: Button
    private lateinit var shareFullExportBtn: Button
    private val exportUiHandler = Handler(Looper.getMainLooper())
    private var lastExportDirectory: File? = null
    private val exportUiTick = object : Runnable {
        override fun run() {
            renderFullExport()
            exportUiHandler.postDelayed(this, 1000)
        }
    }

    /** Shared across every probe on this screen — they all write into the same [logView], and
     *  running two at once (e.g. a multi-minute APK extraction plus a 60s trace) would interleave
     *  their output with no attribution of which button produced which line. Single-shot manual
     *  diagnostics, so the correct behaviour on contention is "tell the tester to wait", not queue. */
    private fun allDiagButtons() = listOf(runBtn, probeBtn, fissionRegistryBtn, armCallbackBtn, traceBtn, navIconCaptureBtn, fullExportBtn, hudBenchBtn, reportingPairBtn)

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
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                allDiagButtons().forEach { it.isEnabled = true }
                reportingPairBtn.isEnabled = !reportingPairInFlight
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Diagnostics"; textSize = 20f })
        root.addView(TextView(this).apply {
            text = "Local exports: use while parked. Keep DashCast open until they finish."
            textSize = 14f
        })
        fullExportStatus = TextView(this).apply { textSize = 16f }
        root.addView(fullExportStatus)
        fullExportCurrent = TextView(this).apply {
            textSize = 16f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        root.addView(fullExportCurrent)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            cancelFullExportBtn = Button(this@DiagActivity).apply {
                text = "Cancel local export"
                setOnClickListener { FullApkExportSession.cancel(); renderFullExport() }
            }
            shareFullExportBtn = Button(this@DiagActivity).apply {
                text = "Local export files"
                setOnClickListener { showFullExportFiles() }
            }
            addView(cancelFullExportBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareFullExportBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        // Controls scroll independently; the progress/stall indicator above stays visible even
        // on a short landscape display, instead of being pushed below the diagnostic buttons.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fullExportBtn = Button(this).apply {
            text = "Export complet accessible — firmware + APK"
            setOnClickListener { chooseSystemExport() }
        }
        controls.addView(fullExportBtn)
        controls.addView(TextView(this).apply {
            text = "Véhicule stationné. Garder DIAG visible. ZIP locaux, partage manuel ; fichiers protégés et données privées exclus."
            textSize = 14f
        })
        runBtn = Button(this).apply {
            text = "BYD APK Extraction"
            // Projection may already be mined, but HUD receiver evidence is still needed.
            setOnClickListener { if (reComplete) showReCompleteMessage() else start() }
        }

        hudBenchBtn = Button(this).apply {
            text = "HUD bench (DL3) — sendInfo2 NaviInfo test"
            setOnClickListener { if (!sBusy.get()) startActivity(Intent(this@DiagActivity, HudDiagActivity::class.java)) }
        }
        controls.addView(hudBenchBtn)

        navIconCaptureBtn = Button(this).apply {
            setText(R.string.diag_nav_icons_title)
            setOnClickListener {
                AlertDialog.Builder(this@DiagActivity)
                    .setTitle(R.string.diag_nav_icons_title)
                    .setMessage(R.string.diag_nav_icons_consent)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.diag_nav_icons_capture) { _, _ -> captureNavigationIcons() }
                    .show()
            }
        }
        controls.addView(navIconCaptureBtn)

        probeBtn = Button(this).apply {
            text = "OEM cluster probes (read-only)"
            setOnClickListener { runOemProbes() }
        }
        controls.addView(probeBtn)

        fissionRegistryBtn = Button(this).apply {
            text = "OEM display registry (read-only) — DL3"
            setOnClickListener { runFissionRegistryProbe() }
        }
        controls.addView(fissionRegistryBtn)

        armCallbackBtn = Button(this).apply {
            text = "Arm container callback (diagnostic)"
            setOnClickListener { runArmContainerCallback() }
        }
        controls.addView(armCallbackBtn)

        traceBtn = Button(this).apply {
            text = "Trace projection state (60s) — DL3"
            setOnClickListener { runProjectionTrace() }
        }
        controls.addView(traceBtn)

        // Opt-in raw nav-notification capture (to calibrate the Waze/Maps text parser). OFF by
        // default: it logs the ACTUAL notification text — destination / current road / ETA, i.e.
        // location PII — into the journal that bug reports carry. Turn ON, drive a route, send a
        // bug report, then turn OFF.
        controls.addView(CheckBox(this).apply {
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

        // AUD-PERF-P3 — opt-in post-launch cluster state dump. OFF by default because it runs
        // `dumpsys SurfaceFlinger` on EVERY projection start, which serialises SF's whole layer
        // tree under SF's global lock while the projected app is still cold-starting. Same
        // placement rationale as the capture toggle above: a technical action on the English-only
        // screen, so it costs no translated string.
        controls.addView(CheckBox(this).apply {
            text = "Dump cluster state after each launch (placement diagnostic)"
            isChecked = ClusterPrefs.isLaunchDiagnosticsEnabled(this@DiagActivity)
            setOnCheckedChangeListener { _, checked ->
                ClusterPrefs.setLaunchDiagnosticsEnabled(this@DiagActivity, checked)
                log(if (checked)
                    "Launch diagnostics ON — each cluster launch now dumps WM tasks + SurfaceFlinger " +
                        "layers to the journal 1.5s after starting. Costs a SurfaceFlinger global-lock " +
                        "hold per launch; turn it OFF once you have the report."
                else "Launch diagnostics OFF (default).")
            }
        })

        // Provisioning entry point. It lives here rather than in Settings on purpose: it is a
        // technical action, and DiagActivity is the screen app/lint.xml documents as the
        // English-only exception, so it costs no translated string. A new Settings row would have
        // cost 13.
        reportingPairBtn = Button(this).apply {
            text = "Pair reporting channel — put ${ReportChannel.IMPORT_NAME} in Download"
            setOnClickListener {
                val pairingButton = this
                reportingPairInFlight = true
                pairingButton.isEnabled = false
                log("pairing…")
                ReportChannel.inspectFromDevice(this@DiagActivity) { candidate, outcome ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (candidate == null) {
                            log(outcome)
                            reportingPairInFlight = false; pairingButton.isEnabled = !sBusy.get()
                            return@runOnUiThread
                        }
                        AlertDialog.Builder(this@DiagActivity)
                            .setTitle("Confirm reporting channel")
                            .setMessage(candidate.confirmationSummary())
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                log("pairing cancelled — nothing stored")
                                reportingPairInFlight = false; pairingButton.isEnabled = !sBusy.get()
                            }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                ReportChannel.applyCandidate(
                                    this@DiagActivity, candidate
                                ) { result ->
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) {
                                            log(result)
                                            reportingPairInFlight = false; pairingButton.isEnabled = !sBusy.get()
                                        }
                                    }
                                }
                            }
                            .setOnCancelListener { reportingPairInFlight = false; pairingButton.isEnabled = !sBusy.get() }
                            .show()
                    }
                }
            }
        }
        controls.addView(reportingPairBtn)

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
        log("Ready. Choose a diagnostic or Export complet accessible.")
        FullApkExportSession.inspectPrevious(applicationContext)
        renderFullExport()
    }

    override fun onStart() {
        super.onStart()
        exportUiHandler.post(exportUiTick)
    }

    override fun onStop() {
        exportUiHandler.removeCallbacks(exportUiTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun chooseSystemExport() {
        if (sBusy.get()) { log("Un diagnostic est déjà en cours. Attendre sa fin."); return }
        AlertDialog.Builder(this).setTitle("Export complet accessible — véhicule stationné")
            .setItems(buildList {
                add("Nouvelle collecte complète accessible")
                if (FullApkExportSession.snapshot?.kind == ExportKind.FIRMWARE_LARGE && FullApkExportSession.snapshot?.directory != null)
                    add("Reprendre la collecte précédente — vérifier les ZIP et continuer")
            }.toTypedArray()) { _, index ->
                startFullApkExport(ExportKind.FIRMWARE_LARGE, if(index == 1) FullApkExportSession.snapshot?.directory else null)
            }.setNegativeButton("Annuler", null).show()
    }

    private fun startFullApkExport(kind: ExportKind = ExportKind.APK, resumeDirectory: File? = null) {
        if (!claimBusyOrWarn()) return
        allDiagButtons().forEach { it.isEnabled = false }
        log(if (kind == ExportKind.CONTEXT)
            "Relevé DiLink lancé : 14 lectures fixes via la connexion existante. Aucun envoi automatique ; résultats et ZIP conservés localement."
            else if (kind == ExportKind.FIRMWARE_LARGE)
                "Collecte large : fichiers réguliers accessibles des partitions firmware et APEX montés, plus APK installés positivement identifiés. APP et SHELL inventoriés séparément ; pas de données privées ni image physique complète. Garder DIAG visible. Reprise dans la même session après vérification des ZIP. Aucun contenu collecté exécuté."
            else if (kind == ExportKind.SYSTEM_P15)
                "Complément DiLink P15 : copie de ${SystemExportProfile.DILINK_P15.requestedPaths.size} chemins fixes sous les droits existants. Aucun binaire collecté exécuté. Les fichiers refusés restent signalés ; récupération non garantie. ZIP et manifest.json conservés localement."
            else "Local ${kind.label} export started. No automatic upload. Finished ZIPs are kept outside report cleanup.")
        // Static completion releases the shared gate even if this Activity has been destroyed.
        FullApkExportSession.start(applicationContext, kind, resumeDirectory = resumeDirectory) { sBusy.set(false) }
        renderFullExport()
    }

    @SuppressLint("SetTextI18n")
    private fun renderFullExport() {
        if (!::fullExportStatus.isInitialized) return
        val state = FullApkExportSession.snapshot
        if (state?.running == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        allDiagButtons().forEach { it.isEnabled = !sBusy.get() }
        reportingPairBtn.isEnabled = !sBusy.get() && !reportingPairInFlight
        cancelFullExportBtn.isEnabled = state?.running == true && !FullApkExportSession.isCancelling()
        shareFullExportBtn.isEnabled = state?.directory != null && (state.kind != ExportKind.FIRMWARE_LARGE || !state.running)
        cancelFullExportBtn.text = "Cancel ${state?.kind?.label ?: "local"} export"
        shareFullExportBtn.text = "${state?.kind?.label ?: "Local"} export files"
        if (state == null) {
            fullExportStatus.text = "Export complet accessible prêt — firmware et APK visibles."
            fullExportCurrent.text = "Nouvelle session ou reprise via Export complet accessible."
            return
        }
        val now = FullApkExport.monotonicMs()
        val elapsed = ((now - state.startedMs) / 1000).coerceAtLeast(0)
        val idle = ((now - state.lastProgressMs) / 1000).coerceAtLeast(0)
        if (state.kind == ExportKind.CONTEXT) {
            cancelFullExportBtn.text = if (FullApkExportSession.isCancelling()) "Annulation en attente…" else "Annuler le relevé"
            shareFullExportBtn.text = "Fichiers du relevé"
            shareFullExportBtn.isEnabled = state.directory != null && !state.running
            val pending = !state.running && DiLinkContextDevice.isTransportPending()
            fullExportStatus.text = if (state.running) {
                (if (FullApkExportSession.isCancelling()) "Annulation après la commande en cours…" else state.stage) +
                    "\n${state.completed + state.failed}/14 lectures traitées · ${state.completed} reconnues · ${state.failed} incomplètes" +
                    "\nDurée ${elapsed}s · commande/stade ${idle}s · limite commande 30s, transport 35s"
            } else {
                "Relevé DiLink : ${contextOutcomeLabel(state.outcome)} · ${state.archives.size} ZIP finalisé(s)" +
                    (if (state.countsKnown) "\n${state.completed + state.failed}/14 lectures traitées · ${state.failed} incomplètes"
                     else "\nRelevé précédent : consulter resultats.json") +
                    (if (pending) "\nCommande toujours en attente : diagnostics bloqués jusqu’au retour du transport." else "\n${state.stage.take(190)}")
            }
            fullExportCurrent.text = if (state.running) state.current else state.directory?.absolutePath ?: "Aucun dossier disponible"
            if (!state.running && state.directory != null && lastExportDirectory != state.directory) {
                lastExportDirectory = state.directory
                log("Relevé DiLink ${state.outcome} : ${state.directory.absolutePath}\n${state.stage}\n" +
                    "Ouvrir Fichiers du relevé pour partager le ZIP manuellement. Les refus/absences sont conservés dans resultats.json.")
            }
            return
        }
        val total = state.totalBytes?.let { " / ${it / 1048576} MiB known" } ?: " (total unknown)"
        if (state.running) {
            fullExportStatus.text = "${state.kind.label} · " + (if (FullApkExportSession.isCancelling()) "Cancelling after the current read/command returns…" else state.stage) +
                "\n${state.discovered} found · ${state.completed} copied · ${state.failed} failed · ${state.bytes / 1048576} MiB$total" +
                "\nDurée ${elapsed}s · aucun progrès publié depuis ${idle}s" +
                (if (state.kind == ExportKind.FIRMWARE_LARGE && idle >= 35) " — lecture en attente ; annulation après retour de la commande" else if (idle >= 15) " — waiting; possible stall" else "")
            fullExportCurrent.text = state.current
        } else {
            fullExportStatus.text = "Local ${state.kind.label} export: ${state.outcome} · ${state.archives.size} finished ZIP(s)" +
                (if (state.countsKnown) "\n${state.completed} exported · ${state.failed} incomplete/failed · whole-vehicle coverage unverified."
                 else "\nPrevious counts unavailable — see manifest.json. Whole-vehicle coverage unverified.") +
                "\n${state.stage.take(190)}"
            fullExportCurrent.text = state.directory?.absolutePath ?: "No export directory available"
        }
        if (!state.running && state.directory != null && lastExportDirectory != state.directory) {
            lastExportDirectory = state.directory
            log("Local ${state.kind.label} export ${state.outcome}: ${state.directory.absolutePath}\n${state.stage}\n" +
                "Use Local export files to view paths/share individual ZIPs. .part files are incomplete. See manifest.json for coverage and failures.")
        }
    }

    private fun contextOutcomeLabel(outcome: String): String = when (outcome) {
        "finished" -> "terminé"
        "partial" -> "partiel"
        "cancelled" -> "annulé"
        "failed" -> "échec"
        "interrupted/partial" -> "interrompu / partiel"
        "previous" -> "précédent"
        else -> outcome
    }

    private fun showFullExportFiles() {
        val state = FullApkExportSession.snapshot ?: return
        val directory = state.directory ?: return
        val metadata = if (state.kind == ExportKind.CONTEXT) listOf("manifest.json", "resultats.json", "integrite.json", "RESULT.txt", "ERREUR_LIVRAISON.txt")
            else listOf("manifest.json")
        val files = state.archives + metadata.map { File(directory, it) }.filter { it.isFile }
        val labels = files.map { "${it.name} (${if(it.length()<1048576) "${it.length()/1024} KiB" else "${it.length()/1048576} MiB"})" }.toTypedArray()
        val context = state.kind == ExportKind.CONTEXT
        AlertDialog.Builder(this).setTitle(if (context) "Fichiers du relevé DiLink" else "Local ${state.kind.label} export files")
            .setMessage(if (context) "${directory.absolutePath}\n\nLe ZIP contient les résultats reçus et les statuts des 14 lectures. " +
                "Un relevé partiel ou annulé reste lisible. resultats.json décrit les limites ; integrite.json contient le SHA-256 du ZIP. Partage manuel uniquement."
                else "${directory.absolutePath}\n\nTransférer TOUS les ZIP finalisés et manifest.json du même dossier. Chaque ZIP est indépendant. " +
                    "Les .part sont incomplets. Le manifeste extérieur décrit chemins, SHA-256 et limites de couverture. Aucun envoi automatique.")
            .setPositiveButton(if (context) "Lister / partager" else "List / share files") { _, _ ->
                AlertDialog.Builder(this).setTitle(if (context) "Partager un fichier du relevé" else "Share ${state.kind.label} export file manually")
                    .setItems(labels) { _, index ->
                        try {
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", files[index])
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = when (files[index].extension) { "zip" -> "application/zip"; "txt" -> "text/plain"; else -> "application/json" }
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("${state.kind.label} export", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Share local ${state.kind.label} export"))
                        } catch (e: Exception) { log("Share unavailable: ${e.message}. Local file: ${files[index].absolutePath}") }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Collect the HUD receiver even when this platform's projection has already been mined. */
    private val reComplete: Boolean by lazy {
        try {
            val p = Platform.get()
            ApkExtractionPolicy.isPlatformFullyMined(
                p.isDiLink3(this), p.isDiLink5(this), Build.VERSION.SDK_INT,
                collectHudReceiverEvidence = true)
        } catch (t: Throwable) {
            // Fail OPEN: if platform detection throws, keep extraction available rather than
            // silently blocking it on a platform we might still need.
            false
        }
    }

    private fun showReCompleteMessage() {
        runOnUiThread { logView.text = "" }
        log("Targeted projection APK analysis is complete for this platform (DiLink 3 / DiLink 5.0).")
        log("The older targeted extraction is unavailable here. The full local APK export")
        log("remains available above, including APKs outside the targeted analysis.")
    }

    private fun start() {
        // Defensive: the button should not reach here on a fully-mined platform, but never run
        // (or send) if it somehow does.
        if (reComplete) { showReCompleteMessage(); return }
        if (!claimBusyOrWarn()) return
        runOnUiThread { allDiagButtons().forEach { it.isEnabled = false } }
        runOnUiThread { logView.text = "" }
        log("Collecting…")
        val app = applicationContext
        val owner = WeakReference(this)
        val progress: (String) -> Unit = { line -> logExtraction(owner, line) }
        Thread({
            var workDir: File? = null
            // With an Azure container configured the 50 MB messaging ceiling no longer applies, so
            // the planner may pull the big OEM artefacts (cluster Qt themes, the renderer .so) that
            // were previously skipped for size. Set before planning — it changes every budget.
            ApkExtractionPolicy.largeSink = AzureBlobUploader.isConfigured()
            if (ApkExtractionPolicy.largeSink) progress("Azure container configured — large pull enabled.")
            val zip: File = try {
                val plan = BydApkExtractionBundle.plan(app, progress)
                workDir = plan.workDir
                progress("Selected ${plan.accepted.size} OEM APK(s) + ${plan.acceptedNative.size} native, " +
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
                val store = ReportStore.dir(app)
                val short = when {
                    !ReportStore.hasRoomFor(app.cacheDir, need) -> app.cacheDir
                    !ReportStore.hasRoomFor(store, need) -> store
                    else -> null
                }
                if (short != null) {
                    val freeMb = ReportStore.usableBytes(short) / (1024 * 1024)
                    progress("aborted: needs ~${need / (1024 * 1024)} MB free on ${short.absolutePath},")
                    progress("only $freeMb MB available. Free some space and run this again.")
                    BydApkExtractionBundle.cleanup(workDir); workDir = null
                    completeExtraction(owner)
                    return@Thread
                }
                ReportStore.prune(app)
                val archive = BydApkExtractionBundle.materialize(
                    plan, File(store, plan.workDir.name + ".zip"), progress)
                BydApkExtractionBundle.cleanup(workDir)
                workDir = null
                archive
            } catch (t: Throwable) {
                BydApkExtractionBundle.cleanup(workDir)
                workDir = null
                progress("failed: ${t.javaClass.simpleName}: ${t.message}")
                completeExtraction(owner)
                return@Thread
            }
            progress("zip ready: ${zip.name} (${zip.length() / 1024} KB)")

            // Azure first when available: it has no practical size limit, and it is the only sink
            // that can take a pull containing the 100 MB+ OEM artefacts.
            if (AzureBlobUploader.isConfigured()) {
                progress("uploading to Azure…")
                AzureBlobUploader.upload(zip, "dilink/${zip.name}", progress,
                    object : AzureBlobUploader.Callback {
                        override fun onUploaded(url: String) {
                            progress("✓ uploaded to Azure:\n$url")
                            progress("Done — tell the maintainer the file name above.")
                            BydApkExtractionBundle.cleanup(null, zip)
                            completeExtraction(owner)
                        }
                        override fun onFailed(message: String) {
                            // Fall back rather than losing the pull: a bundle that still fits under
                            // the messaging ceiling can go out the old way.
                            progress("✗ Azure upload failed: $message")
                            if (TelegramBugReporter.isConfigured()
                                    && zip.length() < 45L * 1024 * 1024) {
                                progress("falling back to Telegram…")
                                sendExtractionViaTelegram(app, owner, zip, progress)
                            } else {
                                keepExtractionLocally(owner, zip, progress)
                            }
                        }
                    })
                return@Thread
            }

            if (!TelegramBugReporter.isConfigured()) {
                progress("cannot upload: ${com.byd.dashcast.report.ReportConsent.transportBlockReason()}")
                keepExtractionLocally(owner, zip, progress)
                return@Thread
            }
            sendExtractionViaTelegram(app, owner, zip, progress)
        }, "byd-apk-extract").start()
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

    private fun captureNavigationIcons() {
        if (!claimBusyOrWarn()) return
        allDiagButtons().forEach { it.isEnabled = false }
        val app = applicationContext
        val owner = WeakReference(this)
        Thread({
            try {
                val active = MapNotificationListenerService.activeMapsNotificationsForCapture()
                if (active == null) {
                    logExtraction(owner, app.getString(R.string.diag_nav_icons_no_listener))
                    return@Thread
                }
                if (active.isEmpty()) {
                    logExtraction(owner, app.getString(R.string.diag_nav_icons_none))
                    return@Thread
                }
                val zip = NavigationIconCapture.capture(app, active)
                logExtraction(owner, "Maps icon snapshot kept locally: ${zip.absolutePath}")
                val activity = owner.get() ?: return@Thread
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    activity.showNavigationIconExport(zip)
                }
            } catch (e: Exception) {
                logExtraction(owner, app.getString(R.string.diag_nav_icons_failed) + " (${e.javaClass.simpleName})")
            } finally {
                completeExtraction(owner)
            }
        }, "nav-icon-capture").start()
    }

    private fun showNavigationIconExport(zip: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_nav_icons_title)
            .setMessage(getString(R.string.diag_nav_icons_ready, zip.absolutePath))
            .setNegativeButton(R.string.diag_nav_icons_keep, null)
            .setNeutralButton(R.string.bug_share_chooser) { _, _ ->
                AppLogger.shareFile(this, zip, getString(R.string.diag_nav_icons_title),
                    getString(R.string.bug_share_chooser))
            }
            .setPositiveButton(R.string.bug_send) { _, _ ->
                com.byd.dashcast.report.ReportConsent.askThen(this) {
                    val app = applicationContext
                    val owner = WeakReference(this)
                    Thread({
                        TelegramBugReporter.send(app, zip,
                            "Maps notification icon snapshot — DashCast ${com.byd.dashcast.BuildConfig.VERSION_NAME}",
                            HudCaptureSupport.HUD_TEST_THREAD,
                            object : TelegramBugReporter.Callback {
                                override fun onSent() { logExtraction(owner, "Maps icon snapshot sent.") }
                                override fun onFailed(message: String) {
                                    logExtraction(owner, "Upload failed; ZIP kept locally: ${zip.absolutePath}")
                                    owner.get()?.let { activity ->
                                        HudCaptureSupport.offerFallback(activity, zip) { logExtraction(owner, it) }
                                    }
                                }
                                override fun onAmbiguous(message: String) {
                                    logExtraction(owner, "Upload outcome unknown; ZIP kept locally: ${zip.absolutePath}")
                                }
                            })
                    }, "nav-icon-upload").start()
                }
            }
            .show()
    }

    private fun log(line: String) {
        AppLogger.i(TAG, line)
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::logView.isInitialized) {
                logView.append(line + "\n")
            }
        }
    }

    private fun resetButton() = releaseBusy()

    private companion object {
        const val TAG = "DiagActivity"
        val sBusy = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun logExtraction(owner: WeakReference<DiagActivity>, line: String) {
            AppLogger.i(TAG, line)
            val activity = owner.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && activity::logView.isInitialized) {
                    activity.logView.append(line + "\n")
                }
            }
        }

        private fun completeExtraction(owner: WeakReference<DiagActivity>) {
            sBusy.set(false)
            val activity = owner.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.allDiagButtons().forEach { it.isEnabled = true }
                }
            }
        }

        private fun sendExtractionViaTelegram(
            app: android.content.Context,
            owner: WeakReference<DiagActivity>,
            zip: File,
            progress: (String) -> Unit,
        ) {
            progress("uploading…")
            TelegramBugReporter.send(app, zip,
                "BYD APK extraction — ${BydApkExtractionBundle.header(app)}",
                object : TelegramBugReporter.Callback {
                    override fun onSent() {
                        progress("✓ sent. Done — you can leave this screen.")
                        BydApkExtractionBundle.cleanup(null, zip)
                        completeExtraction(owner)
                    }

                    override fun onFailed(message: String) {
                        progress("✗ upload failed: $message")
                        keepExtractionLocally(owner, zip, progress)
                    }

                    override fun onAmbiguous(message: String) {
                        progress("? upload outcome unknown: $message\nkept at ${zip.absolutePath}")
                        completeExtraction(owner)
                    }
                })
        }

        private fun keepExtractionLocally(
            owner: WeakReference<DiagActivity>,
            zip: File,
            progress: (String) -> Unit,
        ) {
            progress("kept locally at:\n${zip.absolutePath}")
            if (zip.length() < 45L * 1024 * 1024) {
                owner.get()?.runOnUiThread {
                    val activity = owner.get() ?: return@runOnUiThread
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    try {
                        AppLogger.shareFile(activity, zip,
                            activity.getString(R.string.bug_share_subject),
                            activity.getString(R.string.bug_share_chooser))
                    } catch (t: Throwable) {
                        progress("share unavailable (${t.javaClass.simpleName}) — pull the file above")
                    }
                }
            } else {
                progress("too large to share from the car — pull it over adb.")
            }
            completeExtraction(owner)
        }
    }
}
