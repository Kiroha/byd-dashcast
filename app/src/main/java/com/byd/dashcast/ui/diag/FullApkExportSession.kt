package com.byd.dashcast.ui.diag

import android.content.Context
import android.os.Build
import android.os.Process
import com.byd.dashcast.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped state contains no Activity reference. Rotation/reentry observes the same job.
 * A process death stops the job; its running marker and .part files remain visibly interrupted. */
internal object FullApkExportSession {
    @Volatile var snapshot: FullApkExport.Progress? = null
        private set
    private val cancellation = AtomicBoolean(false)
    @Volatile private var recovering = false

    fun start(context: Context, kind: ExportKind = ExportKind.APK,
              launch: (Runnable, String) -> Unit = { task, name -> Thread(task, name).start() },
              resumeDirectory: File? = null,
              firmwareFactory: (Context, () -> Boolean) -> FirmwareExport.Device = { app, stop -> FirmwareDevice(app, stop) },
              root: (Context, ExportKind) -> File = { app, type -> exportRoot(app, type) },
              finished: () -> Unit) {
        try {
            val app = context.applicationContext
            if (DiLinkContextDevice.isTransportPending()) {
                // A previous non-cancellable Binder call keeps the diagnostic gate occupied.
                // Do not start any export or release that gate before the physical call returns.
                DiLinkContextDevice.whenTransportIdle(finished)
                return
            }
            cancellation.set(false)
            snapshot = FullApkExport.Progress("Preparing local ${kind.label} export", kind = kind)
            launch(Runnable {
                var marker: File? = null
                var firmwareDevice:FirmwareExport.Device?=null
                var firmwareWrites:FirmwareWriteGuard?=null
                try {
                    val base = root(app, kind)
                    val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                    val directory = resumeDirectory ?: File(base, "${kind.prefix}$id")
                    require(resumeDirectory == null || (kind == ExportKind.FIRMWARE_LARGE && directory.parentFile?.canonicalFile == base.canonicalFile && directory.name.startsWith(kind.prefix))) { "Invalid resume directory" }
                    if(kind==ExportKind.FIRMWARE_LARGE) {
                        FirmwareDiagnostics.begin()
                        FirmwareDiagnostics.phase("Compatibilité de session",directory.path)
                        if(resumeDirectory!=null)FirmwareLedger.requireCompatibleResume(resumeDirectory)
                        firmwareDevice=firmwareFactory(app,cancellation::get)
                        firmwareWrites=firmwareDevice!!.outputGuard ?: FirmwareWriteGuard(firmwareDevice!!::validateDestination)
                        firmwareWrites!!.attest { firmwareDevice!!.prepareDestination(directory.path) }
                        firmwareWrites!!.validate()
                    }
                    firmwareWrites?.check()
                    check(directory.mkdirs() || (resumeDirectory != null && directory.isDirectory)) { "Cannot create ${directory.absolutePath}" }
                    firmwareWrites?.validate()
                    marker = File(directory, "RUNNING.txt")
                    marker!!.writeText("${kind.label} export started. If this file remains after DashCast restarts, the export was interrupted. " +
                        "Only .zip volumes are finished; .part files are incomplete.\n")
                    val result = if (kind == ExportKind.FIRMWARE_LARGE) {
                        FirmwareLedger(directory,writes=firmwareWrites).use { ledger -> FirmwareExport(firmwareDevice!!,directory,ledger,cancellation::get,{ snapshot = it },writes=firmwareWrites!!).run() }
                    } else if (kind == ExportKind.CONTEXT) {
                        DiLinkContextExport(DiLinkContextDevice(), directory, cancellation::get,
                            { snapshot = it }, appRuntime = mapOf("package" to app.packageName,
                                "app_uid" to Process.myUid(), "android_api" to Build.VERSION.SDK_INT,
                                "app_version_name" to BuildConfig.VERSION_NAME, "app_version_code" to BuildConfig.VERSION_CODE,
                                "build_fingerprint" to Build.FINGERPRINT,
                                "scope" to "App-local runtime values; distinct from the transport UID observed by id.")).run()
                    } else {
                        val device = if (kind == ExportKind.APK) FullApkExportDevice(app) else SystemExportDevice(app, kind.systemProfile)
                        FullApkExport(device, directory, cancellation::get,
                            { snapshot = it }, kind = kind).run()
                    }
                    // Keep a concise outcome even if the JSON manifest could not be saved (disk full).
                    if (kind != ExportKind.CONTEXT && kind != ExportKind.FIRMWARE_LARGE) runCatching { File(directory, "RESULT.txt").writeText(
                        "${kind.label} export: ${result.outcome}\n${result.stage}\nCoverage of the whole vehicle is unverified.\n" +
                            "Finished ZIP volumes: ${result.archives.size}\nIncomplete .part volumes must not be used as successful exports.\n") }
                    firmwareWrites?.validate()
                    marker!!.delete()
                } catch (e: Exception) {
                    val previous = snapshot ?: FullApkExport.Progress("${kind.label} export failed", kind = kind)
                    if(kind==ExportKind.FIRMWARE_LARGE) {
                        FirmwareDiagnostics.failure(e)
                        com.byd.dashcast.util.AppLogger.e("FirmwareExport",FirmwareDiagnostics.failureSummary().orEmpty())
                    }
                    snapshot = previous.copy(stage = if(kind==ExportKind.FIRMWARE_LARGE)FirmwareDiagnostics.failureSummary().orEmpty() else "Export failed: ${e.message}", outcome = "failed", running = false)
                } finally {
                    runCatching {firmwareDevice?.close()}
                    DiLinkContextDevice.whenTransportIdle(finished)
                }
            }, "${kind.label.lowercase(Locale.US)}-export")
        } catch (failure: Throwable) {
            try {
                if (failure is Exception) snapshot = (snapshot ?: FullApkExport.Progress("Export failed", kind = kind))
                    .copy(stage = "Cannot start export: ${failure.message}", outcome = "failed", running = false)
            } finally { DiLinkContextDevice.whenTransportIdle(finished) }
            if (failure !is Exception) throw failure
        }
    }

    fun cancel() { cancellation.set(true) }
    fun isCancelling() = cancellation.get() && snapshot?.running == true

    /** Metadata-only recovery; never delete or automatically upload earlier exports. */
    @Synchronized fun inspectPrevious(context: Context) {
        if (snapshot != null || recovering) return
        recovering = true
        val app = context.applicationContext
        Thread({
            try {
                val (kind, latest) = ExportKind.latest(ExportKind.entries.map { it to exportRoot(app, it, existingOnly=true) })
                    ?: return@Thread
                val recovered = recoverPrevious(kind, latest)
                synchronized(this) {
                    if (snapshot == null) snapshot = recovered
                }
            } finally { recovering = false }
        }, "local-export-recovery").start()
    }

    /** Worker-only bounded metadata recovery; no daemon, source reads, deletion or upload. */
    internal fun recoverPrevious(kind: ExportKind, latest: File): FullApkExport.Progress {
        val files = latest.listFiles().orEmpty()
        val archives = files.filter { it.isFile && it.name.endsWith(".zip") }.sortedBy { it.name }
        val interrupted = files.any { it.name == "RUNNING.txt" || it.name.endsWith(".part") }
        val integrityFailure = kind == ExportKind.CONTEXT && files.any { it.isFile && it.name == "ERREUR_LIVRAISON.txt" }
        val result = files.firstOrNull { it.name == "RESULT.txt" }?.let { runCatching { it.bufferedReader().use { reader ->
            ExportText.read(reader, 1600).first } }.getOrNull() }
        return FullApkExport.Progress(
            stage = if (integrityFailure) "Échec d’intégrité du relevé précédent. ZIP finalisé conservé ; consulter ERREUR_LIVRAISON.txt."
                    else if (kind == ExportKind.FIRMWARE_LARGE) "Collecte précédente conservée. Seul le profil stockage interne actuel peut reprendre après vérification des ZIP. Une ancienne collecte avec SD/USB exige une nouvelle collecte du stockage interne."
                    else if (interrupted) "Previous ${kind.label} export interrupted or incomplete. Finished ZIP volumes retained; inspect manifest.json."
                    else result ?: "Previous local ${kind.label} export found. Inspect manifest.json for results and coverage.",
            running = false, outcome = if (integrityFailure) "failed" else if (interrupted) "interrupted/partial" else "previous",
            archives = archives, directory = latest, countsKnown = false, kind = kind)
    }

    private fun exportRoot(context: Context, kind: ExportKind, existingOnly:Boolean=false): File {
        if(existingOnly || kind==ExportKind.FIRMWARE_LARGE)return File("/storage/emulated/${Process.myUid()/100000}/Android/data/${context.packageName}/files",kind.rootName)
        val base = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
            ?: File("/storage/emulated/0/Android/data/${context.packageName}/files")
        return File(base, kind.rootName)
    }
}
