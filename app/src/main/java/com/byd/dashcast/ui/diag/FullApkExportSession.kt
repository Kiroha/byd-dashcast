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

    fun start(context: Context, finished: () -> Unit) {
        val app = context.applicationContext
        cancellation.set(false)
        snapshot = FullApkExport.Progress("Preparing local export")
        try {
            Thread({
                var marker: File? = null
                try {
                    val base = exportRoot(app)
                    val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                    val directory = File(base, "apk-export-$id")
                    check(directory.mkdirs()) { "Cannot create ${directory.absolutePath}" }
                    marker = File(directory, "RUNNING.txt")
                    marker!!.writeText("Export started. If this file remains after DashCast restarts, the export was interrupted. " +
                        "Only .zip volumes are finished; .part files are incomplete.\n")
                    val result = FullApkExport(FullApkExportDevice(app), directory, cancellation::get,
                        { snapshot = it }).run()
                    // Keep a concise outcome even if the JSON manifest could not be saved (disk full).
                    runCatching { File(directory, "RESULT.txt").writeText(
                        "${result.outcome}\n${result.stage}\nCoverage of the whole vehicle is unverified.\n" +
                            "Finished ZIP volumes: ${result.archives.size}\nIncomplete .part volumes must not be used as successful exports.\n") }
                    marker!!.delete()
                } catch (e: Exception) {
                    val previous = snapshot ?: FullApkExport.Progress("Export failed")
                    snapshot = previous.copy(stage = "Export failed: ${e.message}", outcome = "failed", running = false)
                } finally {
                    finished()
                }
            }, "full-apk-export").start()
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
                val latest = exportRoot(app).listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                    ?: return@Thread
                val files = latest.listFiles().orEmpty()
                val archives = files.filter { it.isFile && it.name.endsWith(".zip") }.sortedBy { it.name }
                val interrupted = files.any { it.name == "RUNNING.txt" || it.name.endsWith(".part") }
                val result = files.firstOrNull { it.name == "RESULT.txt" }?.let { runCatching { it.readText().take(1600) }.getOrNull() }
                synchronized(this) {
                    if (snapshot == null) snapshot = FullApkExport.Progress(
                        stage = if (interrupted) "Previous export interrupted or incomplete. Finished ZIP volumes retained; inspect manifest.json."
                                else result ?: "Previous local export found. Inspect manifest.json for results and coverage.",
                        running = false, outcome = if (interrupted) "interrupted/partial" else "previous",
                        archives = archives, directory = latest, countsKnown = false)
                }
            } finally { recovering = false }
        }, "apk-export-recovery").start()
    }

    private fun exportRoot(context: Context): File {
        val base = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
            ?: File("/storage/emulated/0/Android/data/${context.packageName}/files")
        return File(base, "apk-exports")
    }
}
