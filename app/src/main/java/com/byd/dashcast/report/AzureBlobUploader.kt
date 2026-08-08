package com.byd.dashcast.report

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.util.AppLogger

import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Uploads large diagnostic bundles straight to Azure Blob Storage, so reverse-engineering pulls are
 * not capped by the messaging channel (Telegram tops out at 50 MB, and the extraction budget was
 * sized to fit under it). The OEM artefacts we actually want are far bigger — the cluster Qt theme
 * bundles alone are ~126 MB and ~118 MB.
 *
 * <p><b>Credentials.</b> The container URL and its SAS token come from `local.properties` via
 * [BuildConfig] (the same mechanism as the bug-report bot), so they never enter git. The SAS is
 * expected to be **create/write only, no read, no list** and time-limited: a token shipped inside an
 * APK must be assumed public, so it must not be able to read back what other cars uploaded.
 *
 * <p><b>Why block upload.</b> A car uploads over a mobile link that drops. Each 4 MB block is a
 * separate request with its own retries, and only the final commit makes the blob visible — a failed
 * block costs 4 MB of retry, not the whole transfer.
 */
object AzureBlobUploader {

    private const val TAG = "AzureBlobUploader"

    /** Azure allows far larger blocks, but small ones keep a retry cheap on a flaky car link. */
    private const val BLOCK_SIZE = 4 * 1024 * 1024

    private const val ATTEMPTS_PER_BLOCK = 3
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000

    /** Blob API version that supports the block operations used here. */
    private const val API_VERSION = "2021-08-06"

    interface Callback {
        /** @param url the blob URL WITHOUT the SAS — safe to log and to post in a message. */
        fun onUploaded(url: String)
        fun onFailed(message: String)
    }

    /** True when a container URL and a SAS were supplied at build time. */
    @JvmStatic
    fun isConfigured(): Boolean =
        BuildConfig.AZURE_BLOB_URL.isNotEmpty() && BuildConfig.AZURE_BLOB_SAS.isNotEmpty()

    /**
     * Uploads [file] as [blobName]. Blocking — call it off the main thread (the extraction flow
     * already runs on its own thread). [progress] receives a human-readable line per block.
     */
    @JvmStatic
    fun upload(file: File, blobName: String, progress: (String) -> Unit, cb: Callback) {
        if (!isConfigured()) { cb.onFailed("Azure not configured"); return }
        val base = BuildConfig.AZURE_BLOB_URL.trimEnd('/')
        val sas = BuildConfig.AZURE_BLOB_SAS.trimStart('?')
        val blobUrl = "$base/${sanitise(blobName)}"
        val total = file.length()
        try {
            val blockIds = ArrayList<String>()
            FileInputStream(file).use { input ->
                val buf = ByteArray(BLOCK_SIZE)
                var index = 0
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    // Block ids must all be the same length before base64 — Azure rejects a
                    // mixed-width block list.
                    val id = base64(String.format(Locale.US, "%08d", index).toByteArray())
                    putBlock("$blobUrl?comp=block&blockid=${urlEncode(id)}&$sas", buf, n)
                    blockIds.add(id)
                    sent += n
                    index++
                    progress("uploaded ${sent / 1024 / 1024} / ${total / 1024 / 1024} MB")
                }
            }
            if (blockIds.isEmpty()) { cb.onFailed("empty file"); return }
            commit("$blobUrl?comp=blocklist&$sas", blockIds)
            AppLogger.i(TAG, "uploaded ${file.name} (${total / 1024} KB) in ${blockIds.size} block(s)")
            cb.onUploaded(blobUrl)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "upload failed: ${t.javaClass.simpleName}: ${t.message}")
            cb.onFailed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun putBlock(url: String, buf: ByteArray, len: Int) {
        var lastError: Throwable? = null
        for (attempt in 1..ATTEMPTS_PER_BLOCK) {
            try {
                val c = open(url, "PUT")
                c.setFixedLengthStreamingMode(len)
                c.doOutput = true
                c.outputStream.use { it.write(buf, 0, len) }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..299) return
                lastError = IllegalStateException("HTTP $code")
            } catch (t: Throwable) {
                lastError = t
            }
            // Linear back-off; a dropped mobile link usually recovers within a few seconds.
            try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
        }
        throw lastError ?: IllegalStateException("block upload failed")
    }

    /** Commits the uploaded blocks; until this succeeds the blob does not exist. */
    private fun commit(url: String, blockIds: List<String>) {
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><BlockList>")
        for (id in blockIds) xml.append("<Latest>").append(id).append("</Latest>")
        xml.append("</BlockList>")
        val body = xml.toString().toByteArray(Charsets.UTF_8)
        val c = open(url, "PUT")
        c.setRequestProperty("Content-Type", "application/xml")
        c.setRequestProperty("x-ms-blob-content-type", "application/zip")
        c.setFixedLengthStreamingMode(body.size)
        c.doOutput = true
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("commit failed: HTTP $code")
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("x-ms-version", API_VERSION)
            setRequestProperty("x-ms-blob-type", "BlockBlob")
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun base64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** Keeps the blob name to characters that need no escaping in a URL path. */
    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
