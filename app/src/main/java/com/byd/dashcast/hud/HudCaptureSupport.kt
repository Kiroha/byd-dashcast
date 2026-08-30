package com.byd.dashcast.hud

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.report.ReportStore
import com.byd.dashcast.R
import android.content.Context
import android.app.Activity
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.report.Redactor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared helpers for the DL3 HUD diagnostic screens (confirmation bench + raw logcat recorder).
 *
 * Kept tiny and dependency-free so both [HudDiagActivity] and [HudRawCaptureActivity] can zip a
 * working directory and pull a device-side capture file through the daemon.
 */
object HudCaptureSupport {

    /**
     * Telegram topic (`message_thread_id`) for HUD diagnostics, from `local.properties`.
     *
     * Not a literal any more: a topic id hardcoded in a tracked file ships with the public
     * repository, which is how the previous supergroup id leaked (audit AUD-008). Empty when the
     * key is absent — [TelegramBugReporter] then omits the field and the upload lands in the
     * group's General topic instead of failing.
     */
    @JvmStatic
    /**
     * The HUD topic.
     *
     * A numeric `message_thread_id` while a device is provisioned directly; the relay's symbolic
     * name once it is not, because with a relay the thread ids live server-side and the car has
     * none to quote. Both forms are understood by TelegramBugReporter.
     */
    val HUD_TEST_THREAD: String get() {
        val id = com.byd.dashcast.report.ReportChannel.hudThreadId()
        return if (id.isNotEmpty()) id else com.byd.dashcast.report.RelayUploader.TOPIC_HUD
    }

    /**
     * Zips [work] into the shareable report store and drops the staging directory.
     *
     * The plain [zipDir] writes beside the work directory, which for a work directory in `cacheDir`
     * leaves the archive somewhere `FileProvider` cannot reach and the tester cannot open. Every HUD
     * capture goes through here instead, so the archive is always shareable and the staging copy
     * never survives its own bundle.
     */
    @JvmStatic
    fun zipDirToStore(ctx: Context, work: File): File {
        ReportStore.prune(ctx)
        val zip = zipDir(work, File(ReportStore.dir(ctx), work.name + ".zip"))
        try { work.deleteRecursively() } catch (_: Throwable) { /* best-effort */ }
        return zip
    }

    /**
     * Last-resort exit for a capture that could not be uploaded.
     *
     * Replaces the previous dead end — a log line naming a `/data/data/<pkg>/cache` path that
     * neither a file manager nor the uid-2000 shell can open. The archive now lives under the
     * app's external files dir, so it can be pulled; the system chooser is offered on top, as a
     * convenience rather than as the plan, because a client able to receive it is not guaranteed
     * to be installed on these head units.
     */
    @JvmStatic
    fun offerFallback(activity: Activity, zip: File, log: (String) -> Unit) {
        log("kept locally: ${zip.absolutePath}")
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                AppLogger.shareFile(activity, zip,
                    activity.getString(R.string.bug_share_subject),
                    activity.getString(R.string.bug_share_chooser))
            } catch (t: Throwable) {
                log("share unavailable (${t.javaClass.simpleName}) — pull the file above")
            }
        }
    }

    /** Zips every file under [work] into a sibling {@code <name>.zip} and returns it. */
    fun zipDir(work: File): File = zipDir(work, File(work.parentFile, work.name + ".zip"))

    /**
     * Zips [work] into an explicit [dest].
     *
     * The no-destination overload writes beside the work directory, which for a work directory in
     * `cacheDir` puts the archive in `cacheDir` too — where `FileProvider` cannot reach it, because
     * `file_paths.xml` declares no `cache-path`. Callers that need the archive to be shareable pass
     * a destination under [com.byd.dashcast.report.ReportStore].
     */
    @JvmStatic
    fun zipDir(work: File, dest: File): File {
        val zip = dest
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            work.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(work).path
                zos.putNextEntry(ZipEntry(rel))
                if (isRedactableText(f)) {
                    writeRedactedText(f, zos)
                } else {
                    FileInputStream(f).use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
        return zip
    }

    /**
     * The bundles' redaction point, and the reason it exists here.
     *
     * The consent notice tells a driver, in thirteen languages, that the vehicle serial number,
     * Wi-Fi network names, hardware addresses and positions are removed from **every** report. That
     * was true of the bug report and false of the three bundles that come through this function —
     * the HUD bench, the raw HUD capture and the AAOS diagnostic — which carry the same
     * device-wide logcat and the same getprop sweep. A notice that promises more than the code
     * does is worse than no notice, so the promise is made true here rather than narrowed there.
     *
     * Only text is filtered. An image cannot be redacted the way text can, which the notice already
     * says, and a binary run through a regex would be corrupted rather than cleaned.
     */
    private val REDACTABLE = setOf("txt", "log", "json", "xml", "properties", "csv", "md", "")

    private fun isRedactableText(f: File): Boolean = f.extension.lowercase() in REDACTABLE

    /** Smaller files retain whole-document redaction; larger ones are processed incrementally. */
    private const val MAX_IN_MEMORY_REDACTION_BYTES = 8L * 1024 * 1024

    /**
     * Redacts text without a size bypass. Whole-document mode preserves reporter cross-line
     * correlation for ordinary files; oversized captures use one stable salt and one line at a
     * time so memory is bounded by their longest line. A failed rule omits the affected content
     * instead of copying plaintext into an archive whose consent notice promises redaction.
     */
    private fun writeRedactedText(f: File, output: ZipOutputStream) {
        if (f.length() <= MAX_IN_MEMORY_REDACTION_BYTES) {
            val bytes = try {
                val result = Redactor.redact(f.readText())
                if (result.failed.isEmpty()) result.text.toByteArray()
                else REDACTION_FAILURE_MARKER.toByteArray()
            } catch (_: Throwable) {
                REDACTION_FAILURE_MARKER.toByteArray()
            }
            output.write(bytes)
            return
        }

        val salt = UUID.randomUUID().toString()
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        try {
            f.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val result = Redactor.redact(line, salt)
                    writer.write(if (result.failed.isEmpty()) result.text else REDACTION_FAILURE_MARKER)
                    writer.write('\n'.code)
                }
            }
            writer.flush()
        } catch (_: Throwable) {
            writer.write(REDACTION_FAILURE_MARKER)
            writer.write('\n'.code)
            writer.flush()
        }
    }

    private const val REDACTION_FAILURE_MARKER = "[text omitted: redaction failed]"

    /**
     * Pulls a device-side file (e.g. a raw logcat under {@code /data/local/tmp}) through the daemon
     * into [dest], chunk by chunk. The daemon (uid 2000 = shell) can read paths SELinux hides from
     * the app uid. Returns the number of bytes written.
     */
    fun pullRemoteFile(remotePath: String, dest: File): Long {
        val chunkSize = 256 * 1024
        var offset = 0L
        FileOutputStream(dest).use { fos ->
            while (true) {
                val chunk = ProxyClient.readFileChunk(remotePath, offset, chunkSize)
                if (chunk.isEmpty()) break
                fos.write(chunk)
                offset += chunk.size
                if (chunk.size < chunkSize) break // short read → EOF
            }
        }
        return offset
    }
}
