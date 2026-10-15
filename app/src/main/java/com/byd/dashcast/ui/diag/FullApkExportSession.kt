package com.byd.dashcast.ui.diag

import android.content.Context
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

    fun start(context: Context, kind: ExportKind = ExportKind.APK, finished: () -> Unit) {
        val app = context.applicationContext
        cancellation.set(false)
        snapshot = FullApkExport.Progress("Preparing local ${kind.label} export", kind = kind)
        try {
            Thread({
                var marker: File? = null
                try {
                    val base = exportRoot(app, kind)
                    val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                    val directory = File(base, "${kind.prefix}$id")
                    check(directory.mkdirs()) { "Cannot create ${directory.absolutePath}" }
                    marker = File(directory, "RUNNING.txt")
                    marker!!.writeText("${kind.label} export started. If this file remains after DashCast restarts, the export was interrupted. " +
                        "Only .zip volumes are finished; .part files are incomplete.\n")
                    val device = if (kind == ExportKind.APK) FullApkExportDevice(app) else SystemExportDevice(app)
                    val result = FullApkExport(device, directory, cancellation::get,
                        { snapshot = it }, kind = kind).run()
                    // Keep a concise outcome even if the JSON manifest could not be saved (disk full).
                    runCatching { File(directory, "RESULT.txt").writeText(
                        "${kind.label} export: ${result.outcome}\n${result.stage}\nCoverage of the whole vehicle is unverified.\n" +
                            "Finished ZIP volumes: ${result.archives.size}\nIncomplete .part volumes must not be used as successful exports.\n") }
                    marker!!.delete()
                } catch (e: Exception) {
                    val previous = snapshot ?: FullApkExport.Progress("${kind.label} export failed", kind = kind)
                    snapshot = previous.copy(stage = "Export failed: ${e.message}", outcome = "failed", running = false)
                } finally {
                    finished()
                }
            }, "${kind.label.lowercase(Locale.US)}-export").start()
        } catch (e: Exception) {
            snapshot = snapshot!!.copy(stage = "Cannot start export: ${e.message}", outcome = "failed", running = false)
            finished()
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
                val (kind, latest) = ExportKind.latest(ExportKind.entries.map { it to exportRoot(app, it) })
                    ?: return@Thread
                val files = latest.listFiles().orEmpty()
                val archives = files.filter { it.isFile && it.name.endsWith(".zip") }.sortedBy { it.name }
                val interrupted = files.any { it.name == "RUNNING.txt" || it.name.endsWith(".part") }
                val result = files.firstOrNull { it.name == "RESULT.txt" }?.let { runCatching { it.bufferedReader().use { reader ->
                    ExportText.read(reader, 1600).first } }.getOrNull() }
                synchronized(this) {
                    if (snapshot == null) snapshot = FullApkExport.Progress(
                        stage = if (interrupted) "Previous ${kind.label} export interrupted or incomplete. Finished ZIP volumes retained; inspect manifest.json."
                                else result ?: "Previous local ${kind.label} export found. Inspect manifest.json for results and coverage.",
                        running = false, outcome = if (interrupted) "interrupted/partial" else "previous",
                        archives = archives, directory = latest, countsKnown = false, kind = kind)
                }
            } finally { recovering = false }
        }, "local-export-recovery").start()
    }

    private fun exportRoot(context: Context, kind: ExportKind): File {
        val base = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
            ?: File("/storage/emulated/0/Android/data/${context.packageName}/files")
        return File(base, kind.rootName)
    }
}
