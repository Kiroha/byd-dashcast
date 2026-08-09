package com.byd.dashcast.hud

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.ProxyClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    val HUD_TEST_THREAD: String get() = BuildConfig.BUG_REPORT_HUD_THREAD_ID

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
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

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
