package com.byd.dashcast.hud

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

    /** Telegram topic (message_thread_id) for HUD diagnostics — t.me/c/3712642112/2701. */
    const val HUD_TEST_THREAD = "2701"

    /** Zips every file under [work] into a sibling {@code <name>.zip} and returns it. */
    fun zipDir(work: File): File {
        val zip = File(work.parentFile, work.name + ".zip")
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
