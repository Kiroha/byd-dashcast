package com.byd.dashcast.ui.diag

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Device-independent streaming exporter. Source files are never copied to a staging directory. */
internal class FullApkExport(
    private val device: Device,
    private val directory: File,
    private val cancelled: () -> Boolean,
    private val progress: (Progress) -> Unit,
    private val volumeBytes: Long = 1024L * 1024 * 1024,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val kind: ExportKind = ExportKind.APK,
) {
    data class Source(val path: String, var size: Long?, val origins: MutableSet<String> = linkedSetOf(),
                      val canonicalPath: String? = null)
    data class Inventory(val sources: List<Source>, val coverage: List<String>, val firmware: Map<String, String>,
                         val discoveryIssues: List<String> = emptyList())
    interface Device {
        fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit): Inventory
        fun open(source: Source): InputStream
        fun finish(): ContextCheck = ContextCheck()
        fun finish(cancelled: Boolean): ContextCheck = finish()
    }
    data class ContextCheck(val firmware: Map<String, String> = emptyMap(), val issues: List<String> = emptyList())
    data class Progress(
        val stage: String, val current: String = "", val discovered: Int = 0,
        val completed: Int = 0, val failed: Int = 0, val bytes: Long = 0,
        val totalBytes: Long? = null, val archives: List<File> = emptyList(),
        val running: Boolean = true, val outcome: String = "",
        val startedMs: Long = monotonicMs(), val lastProgressMs: Long = startedMs,
        val directory: File? = null,
        val countsKnown: Boolean = true,
        val kind: ExportKind = ExportKind.APK,
    )
    private data class Record(val source: Source, var status: String = "pending", var archive: String = "",
                              var entry: String = "", var bytes: Long = 0, var hash: String = "", var error: String = "")
    private var state = Progress("Preparing ${kind.label} export", directory = directory, kind = kind)
    private val archives = mutableListOf<File>()
    private val records = mutableListOf<Record>()
    private var inventory = Inventory(emptyList(), emptyList(), emptyMap())
    private var zip: ZipOutputStream? = null
    private var part: File? = null
    private var volumePayload = 0L
    private var partNumber = 0
    private val volumeRecords = mutableListOf<Record>()
    private var lastPublish = 0L
    private var finalContext = ContextCheck()
    private var contextChecked = false

    fun run(): Progress {
        var outcome = "failed"
        var message = ""
        try {
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create export directory" }
            inventory = device.discover(::checkCancelled) { label, count ->
                publish(state.copy(stage = "Discovering ${kind.noun} (total unknown)", current = label,
                    discovered = count, lastProgressMs = monotonicMs()))
            }
            records.addAll(inventory.sources.map { Record(it) })
            val total = if (records.all { it.source.size != null }) records.sumOf { it.source.size!! } else null
            publish(state.copy(stage = "Writing standalone ZIP volumes", discovered = records.size, totalBytes = total))
            writeManifest("running", "")
            for ((index, record) in records.withIndex()) {
                checkCancelled()
                val source = record.source
                publish(state.copy(current = source.path, lastProgressMs = monotonicMs()))
                try {
                    // Probe/open before starting an entry, so inaccessible files do not spoil a volume.
                    device.open(source).use { input ->
                        if (zip != null && volumePayload > 0 &&
                            (source.size == null || volumePayload + source.size!! > volumeBytes)) finishVolume()
                        ensureSpace(source.size ?: BUFFER_BYTES.toLong())
                        if (zip == null) startVolume()
                        record.entry = "${kind.entryRoot}/${index.toString().padStart(6, '0')}/${safeName(source.path.substringAfterLast('/'))}"
                        record.archive = part!!.name.removeSuffix(".part")
                        volumeRecords.add(record)
                        zip!!.putNextEntry(ZipEntry(record.entry))
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(BUFFER_BYTES)
                        var nextSpaceCheck = 0L
                        while (true) {
                            checkCancelled()
                            val n = try { input.read(buffer) }
                                catch (e: IOException) { throw SourceException("Source read failed: ${e.message}", e) }
                            if (n < 0) break
                            if (n == 0) throw SourceException("Source returned no bytes without EOF")
                            if (record.bytes >= nextSpaceCheck) {
                                ensureSpace(BUFFER_BYTES.toLong())
                                nextSpaceCheck = record.bytes + 4L * 1024 * 1024
                            }
                            zip!!.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            record.bytes += n
                            volumePayload += n
                            publish(state.copy(bytes = state.bytes + n, lastProgressMs = monotonicMs()), throttle = true)
                        }
                        if (record.bytes == 0L || (source.size != null && record.bytes != source.size)) {
                            throw SourceException("Source size mismatch: expected ${source.size}, read ${record.bytes}; unreadable or changed")
                        }
                        zip!!.closeEntry()
                        record.hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        record.status = "exported"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SpaceException) {
                    record.error = e.message.orEmpty()
                    throw e
                } catch (e: SourceException) {
                    // A source failure does not damage the ZIP stream. Keep earlier healthy APKs
                    // in this volume and explicitly describe the truncated entry as failed.
                    zip!!.closeEntry()
                    record.status = "failed"
                    record.error = e.message.orEmpty() + if (kind == ExportKind.APK) "; entry is incomplete, do not use as an APK" else "; entry is incomplete, do not use as a system file"
                } catch (e: IOException) {
                    record.status = "failed"
                    record.error = e.message.orEmpty()
                    // ZIP/destination failure after putNextEntry is fatal. An open failure before
                    // any entry only affects this source and leaves the current volume usable.
                    if (record in volumeRecords) throw e
                }
                publishCounts()
                // Close oversized APKs immediately. Every volume is independently usable.
                if (volumePayload >= volumeBytes) finishVolume()
            }
            finishVolume()
            checkFinalContext()
            outcome = if (finalContext.issues.isNotEmpty() || records.any { it.status != "exported" } || inventory.discoveryIssues.isNotEmpty() || records.isEmpty()) "partial" else "finished"
            message = if (outcome == "finished") "All selected readable ${kind.noun} exported; whole-vehicle coverage remains unverified."
                      else "Discovery/context gaps or ${kind.label} export failures recorded. See manifest.json."
        } catch (e: CancellationException) {
            outcome = "cancelled"
            message = "Cancelled. Finished ZIP volumes retained; .part files are incomplete."
        } catch (e: Exception) {
            message = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            if (zip != null) abandonVolume(message.ifEmpty { outcome })
            checkFinalContext()
            records.filter { it.status == "pending" }.forEach { it.status = "not_attempted" }
            try { writeManifest(outcome, message) } catch (e: Exception) {
                message += " Manifest write failed: ${e.message}"
                if (outcome != "cancelled") outcome = "failed"
            }
            publishCounts()
            publish(state.copy(stage = message, running = false, outcome = outcome, current = "",
                archives = archives.toList(), lastProgressMs = monotonicMs()))
        }
        return state
    }

    private fun checkFinalContext() {
        if (contextChecked) return
        contextChecked = true
        if (kind == ExportKind.SYSTEM) publish(state.copy(stage = if (cancelled()) "Finalizing cancelled SYSTEM export" else "Checking final SYSTEM build context", current = "Selected build properties", lastProgressMs = monotonicMs()))
        finalContext = try { device.finish(cancelled()) } catch (e: Exception) {
            ContextCheck(issues = listOf("Final build context unavailable: ${e.message}"))
        }
    }
    private fun checkCancelled() { if (cancelled()) throw CancellationException("Export cancelled") }
    private class SpaceException(message: String) : IOException(message)
    private class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause)
    private fun ensureSpace(need: Long) {
        val free = freeBytes(directory)
        if (free <= 0) throw SpaceException("Cannot establish free storage space")
        if (free < need + FREE_MARGIN) throw SpaceException("Insufficient storage: ${free / 1048576} MiB free; need ${(need + FREE_MARGIN) / 1048576} MiB")
    }
    private fun startVolume() {
        checkCancelled()
        partNumber++
        part = File(directory, "${kind.archivePrefix}-${partNumber.toString().padStart(3, '0')}.zip.part")
        zip = ZipOutputStream(part!!.outputStream().buffered(BUFFER_BYTES)).apply { setLevel(0) }
        volumePayload = 0
        volumeRecords.clear()
    }
    private fun finishVolume() {
        val output = zip ?: return
        publish(state.copy(stage = "Closing ZIP volume", lastProgressMs = monotonicMs()))
        val manifest = manifest("volume_complete", "This manifest describes this volume; consult the session manifest for final coverage.", volumeRecords)
        output.putNextEntry(ZipEntry(if (kind == ExportKind.SYSTEM) "manifest-volume.json" else "manifest.json"))
        output.write(manifest.toByteArray(Charsets.UTF_8))
        output.closeEntry()
        output.close()
        val closed = part!!
        val final = File(directory, closed.name.removeSuffix(".part"))
        if (!closed.renameTo(final)) throw IOException("Cannot finalize ${closed.name}")
        zip = null
        archives.add(final)
        volumeRecords.clear()
        volumePayload = 0
        writeManifest("running", "")
        publish(state.copy(stage = "Writing standalone ZIP volumes", archives = archives.toList(), lastProgressMs = monotonicMs()))
    }
    private fun abandonVolume(reason: String) {
        try { zip?.close() } catch (_: Exception) { }
        zip = null
        volumeRecords.forEach {
            it.status = "archive_incomplete"
            it.error = listOf(it.error, reason, "Only present in incomplete ${part?.name}").filter { s -> s.isNotEmpty() }.joinToString("; ")
        }
        volumeRecords.clear()
        volumePayload = 0
    }
    private fun publishCounts() = publish(state.copy(
        completed = records.count { it.status == "exported" },
        failed = records.count { it.status != "exported" && it.status != "pending" }, archives = archives.toList()))
    private fun publish(next: Progress, throttle: Boolean = false) {
        state = next
        val now = monotonicMs()
        if (!throttle || now - lastPublish >= 250) { lastPublish = now; progress(state) }
    }
    private fun writeManifest(outcome: String, message: String) {
        if (!directory.isDirectory) return
        val temporary = File(directory, "manifest.json.tmp")
        temporary.writeText(manifest(outcome, message, records))
        if (!temporary.renameTo(File(directory, "manifest.json"))) throw IOException("Cannot finalize manifest.json")
    }
    private fun manifest(outcome: String, message: String, rows: List<Record>): String = buildString {
        append("{\n\"schema\":1,\"outcome\":").append(json(outcome))
        append(",\"kind\":").append(json(kind.name))
        append(",\"contextCheckComplete\":").append(contextChecked)
        append(",\"message\":").append(json(message))
        append(",\"wholeVehicleCoverage\":\"unverified\",\"firmware\":{")
        append(inventory.firmware.entries.joinToString(",") { json(it.key) + ":" + json(it.value) })
        append("},\"firmwareAfter\":{")
        append(finalContext.firmware.entries.joinToString(",") { json(it.key) + ":" + json(it.value) })
        append("},\"contextIssues\":[").append(finalContext.issues.joinToString(",") { json(it) }).append("]")
        append(",\"coverage\":[").append(inventory.coverage.joinToString(",") { json(it) }).append("]")
        append(",\"discoveryIssues\":[").append(inventory.discoveryIssues.joinToString(",") { json(it) }).append("]")
        append(",\"entryUseRule\":").append(json("Only entries whose status is exported contain a complete " +
            (if (kind == ExportKind.APK) "APK" else "system file") + "; failed entries may contain truncated bytes."))
        append(",\"finishedArchives\":[").append(archives.joinToString(",") { json(it.name) }).append("]")
        append(",\"files\":[\n")
        append(rows.joinToString(",\n") { r ->
            "{\"path\":${json(r.source.path)},\"canonicalPath\":${r.source.canonicalPath?.let { json(it) } ?: "null"},\"expectedBytes\":${r.source.size}," +
                "\"origins\":[${r.source.origins.joinToString(",") { json(it) }}]," +
                "\"status\":${json(r.status)},\"archive\":${json(r.archive)},\"entry\":${json(r.entry)}," +
                "\"bytes\":${r.bytes},\"sha256\":${json(r.hash)},\"error\":${json(r.error)}}"
        })
        append("\n]}\n")
    }
    companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val FREE_MARGIN = 128L * 1024 * 1024
        fun monotonicMs() = System.nanoTime() / 1_000_000
        private fun safeName(value: String) = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "source.apk" }
        internal fun json(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
    }
}
