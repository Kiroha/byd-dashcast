package com.byd.dashcast.ui.diag

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Metadata through bounded EXEC; regular source bytes through strict FD reads. No captured
 * text, property or proc link is evaluated, executed or promoted to an additional path. */
internal class OemBootstrapExport(
    private val device: Device,
    private val directory: File,
    private val cancelled: () -> Boolean,
    private val progress: (FullApkExport.Progress) -> Unit,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val clock: () -> Long = FullApkExport::monotonicMs,
    private val appRuntime: Map<String, String> = emptyMap(),
) {
    interface Device {
        val terminated: Boolean get() = false
        fun negotiate(check: () -> Unit): Map<String, String>
        fun metadata(label: String, pid: Int? = null): List<OemBootstrapPolicy.Blob>
        fun chunk(entry: OemBootstrapPolicy.Source, offset: Long, length: Int): ByteArray
    }
    private data class Record(var entry: OemBootstrapPolicy.Source, var status: String = "not_attempted",
        var bytes: Long = 0, var hash: String = "", var local: String = "", var error: String = "")
    private val started = clock()
    private var state = FullApkExport.Progress("Préparation du relevé de démarrage", kind = ExportKind.OEM_BOOTSTRAP, directory = directory)
    private val records = OemBootstrapPolicy.sources.mapIndexed { i, path -> Record(OemBootstrapPolicy.Source(i, path, "not_attempted", null, emptyList())) }
    private val issues = mutableListOf<String>()
    private var inventory = emptyList<OemBootstrapPolicy.Blob>()
    private val processes = mutableListOf<Map<String, Any?>>()
    private var selectedPids = emptyList<OemBootstrapPolicy.Pid>()
    private var identity = emptyMap<String, String>()
    private var metadataBytes = 0L
    private var sourceBytes = 0L
    private var readBytes = 0L
    private var metadataOk = 0
    private var metadataFailed = 0
    private var metadataPlanned = OemBootstrapPolicy.inventoryLimits.size
    private var outcome = "running"
    private var message = ""
    private var archive: File? = null
    private var owned = false
    private fun issue(text: String) { if (issues.size < 128) issues.add(text.take(256)) }
    private fun sourceCheck() {
        if (cancelled()) throw CancellationException("Lectures arrêtées ; données locales complètes conservées")
        if (clock() - started >= OemBootstrapPolicy.COLLECT_MS) throw IOException("18-minute acquisition budget reached; local finalization reserved")
        if (device.terminated) throw IOException("Physical transport terminated; no subsequent call")
    }
    private fun finalCheck() {
        if (clock() - started >= OemBootstrapPolicy.SESSION_MS) throw IOException("20-minute soft session budget reached; staged evidence retained")
    }
    private fun space(need: Long = 0) {
        if (freeBytes(directory) < OemBootstrapPolicy.RESERVE + need) throw IOException("128 MiB local storage reserve plus pending write unavailable")
    }
    private fun publish(label: String, current: String = "") {
        state = state.copy(stage = label, current = current, discovered = records.size + metadataPlanned,
            completed = records.count { it.status == "exported" } + metadataOk,
            failed = records.count { it.status !in listOf("ok", "exported", "not_attempted") } + metadataFailed,
            bytes = sourceBytes, totalBytes = null, lastProgressMs = clock())
        progress(state)
    }
    private fun metadata(label: String, limits: Map<String, Int>, pid: Int? = null): List<OemBootstrapPolicy.Blob> {
        sourceCheck()
        // Precharge a conservative complete wire bound. A failed/late call keeps its charge;
        // a completed typed reply refunds unused space, never exceeding the aggregate quota.
        val reserve = limits.entries.sumOf { (id, limit) -> ((limit + 130L) / 3 + 1) * 4 + id.length + 128 } + 256
        if (reserve > OemBootstrapPolicy.MAX_METADATA_BYTES - metadataBytes) throw IOException("1 MiB aggregate metadata budget reached")
        metadataBytes += reserve
        val blobs = device.metadata(label, pid)
        sourceCheck()
        if (blobs.map { it.id } != limits.keys.toList() || blobs.any { it.limit != limits[it.id] || it.bytes.size > it.limit })
            throw IOException("Typed metadata scope/size mismatch")
        val actual = blobs.sumOf { ((it.bytes.size + 130L) / 3 + 1) * 4 + it.id.length + 128 } + 256
        check(actual <= reserve)
        metadataBytes -= reserve - actual
        metadataOk += blobs.count { it.status == "ok" }
        metadataFailed += blobs.count { it.status != "ok" }
        blobs.filter { it.status != "ok" }.forEach { issue("$label/${it.id}: ${it.status}; unavailable is not global absence") }
        return blobs
    }
    fun run(): FullApkExport.Progress {
        try {
            check(directory.mkdirs() || directory.isDirectory)
            check(directory.listFiles()?.all { it.name == "RUNNING.txt" && it.isFile } == true) { "Existing export retained; destination is not empty" }
            owned = true; space()
            save("plan.json", mapOf("schema" to OemBootstrapPolicy.PROFILE, "kind" to ExportKind.OEM_BOOTSTRAP.name,
                "fixed_source_paths" to OemBootstrapPolicy.sources, "properties" to OemBootstrapPolicy.properties,
                "process_blob_limits" to OemBootstrapPolicy.processLimits, "scope" to SCOPE, "limits" to LIMITS, "transport" to TRANSPORT))
            sourceCheck(); identity = device.negotiate(::sourceCheck)
            publish("Propriétés et sélection des PID", "Daemon déjà connecté ; noms exacts init / strategyservice")
            inventory = metadata("inventory", OemBootstrapPolicy.inventoryLimits)
            val daemonPid = identity["daemon_pid"]?.toIntOrNull()?.takeIf(OemBootstrapPolicy::validPid)
                ?: throw IOException("Negotiated daemon PID unavailable")
            val selection = OemBootstrapPolicy.select(inventory.first { it.id == "ps" }, daemonPid)
            selection.issues.forEach(::issue)
            selectedPids = selection.pids
            metadataPlanned += selection.pids.size * OemBootstrapPolicy.processLimits.size
            saveResults()
            for ((index, pid) in selection.pids.withIndex()) {
                publish("Identité des processus ${index + 1}/${selection.pids.size}", "PID ${pid.pid} · ${pid.roles.joinToString()}")
                val blobs = metadata("pid_${pid.pid}", OemBootstrapPolicy.processLimits, pid.pid)
                val process = OemBootstrapPolicy.processIdentity(pid, blobs)
                processes.add(process)
                if (process["identity_status"] != "starttime_bracket_consistent") issue("PID ${pid.pid}: identity unstable/unobserved; no observation attribution")
                saveResults()
            }
            for (offset in OemBootstrapPolicy.sources.indices step OemBootstrapPolicy.SOURCE_BATCH) {
                sourceCheck()
                publish("Métadonnées des fichiers ciblés", "Chemins ${offset + 1}–${minOf(offset + OemBootstrapPolicy.SOURCE_BATCH, records.size)}/${records.size}")
                val beforeOk = metadataOk; val beforeFailed = metadataFailed
                val blobs = metadata("sources_$offset", OemBootstrapPolicy.sourceLimits(offset))
                metadataOk = beforeOk; metadataFailed = beforeFailed
                val sourceMetadata = OemBootstrapPolicy.sourceMetadata(blobs, offset)
                sourceMetadata.forEach { source -> records[source.index].apply { entry = source; status = source.status } }
                saveResults()
                for (index in OemBootstrapPolicy.sourceIndices(offset)) {
                    sourceCheck()
                    val record = records[index]
                    if (record.status != "ok") continue
                    val expected = record.entry.stamp ?: continue
                    if (expected.size > OemBootstrapPolicy.MAX_FILE_BYTES) { record.status = "file_byte_limit"; saveResults(); continue }
                    if (expected.size > OemBootstrapPolicy.MAX_SOURCE_BYTES - readBytes) { record.status = "total_source_read_byte_limit"; saveResults(); continue }
                    publish("Copie des fichiers ${index + 1}/${records.size}", record.entry.path)
                    copy(record)
                    saveResults()
                }
            }
            outcome = if (issues.isEmpty() && records.all { it.status == "exported" }) "finished" else "partial"
        } catch (e: CancellationException) { outcome = "cancelled"; issue(e.message.orEmpty()) }
        catch (e: Exception) { outcome = if (owned) "partial" else "failed"; issue("${e.javaClass.simpleName}: ${e.message}") }
        records.filter { it.status == "ok" }.forEach { it.status = "not_attempted" }
        message = "${records.count { it.status == "exported" }}/${records.size} fichiers et ${processes.size} relevés PID conservés ; consulter manifest.json."
        if (owned) try {
            saveResults(); save("manifest.json", report())
            save("RESULT.txt", "$message\nProfil ${OemBootstrapPolicy.PROFILE}; résultat $outcome.\n$SCOPE\n$LIMITS\n$TRANSPORT\n" +
                "Sources lues uniquement ; DashCast écrit ses copies et son ZIP. Partage manuel. Les fichiers finalisés sont conservés si le ZIP échoue. Les .part restent incomplets.\n")
            archive()
        } catch (e: Exception) {
            outcome = "failed"; issue("Archive/local finalization: ${e.javaClass.simpleName}: ${e.message}")
            message = "Finalisation incomplète ; données finalisées conservées. ${e.message}".take(512)
            runCatching { saveResults(); save("manifest.json", report()); save("ERREUR_LIVRAISON.txt", "$message\n") }
        }
        state = state.copy(stage = message, current = directory.path, running = false, outcome = outcome,
            completed = records.count { it.status == "exported" } + metadataOk,
            failed = records.count { it.status != "exported" } + metadataFailed,
            bytes = sourceBytes, discovered = records.size + metadataPlanned, archives = listOfNotNull(archive), lastProgressMs = clock())
        progress(state)
        return state
    }
    private fun copy(record: Record) {
        val entry = record.entry; val expected = entry.stamp ?: throw IOException("Source stamp missing")
        val name = "source-${entry.index.toString().padStart(3, '0')}.bin"
        val part = File(directory, "$name.part"); val target = File(directory, name)
        try {
            space(expected.size); check(!target.exists() && part.createNewFile())
            val digest = MessageDigest.getInstance("SHA-256")
            part.outputStream().buffered(OemBootstrapPolicy.CHUNK).use { output ->
                var offset = 0L
                do {
                    sourceCheck()
                    val count = minOf(OemBootstrapPolicy.CHUNK.toLong(), expected.size - offset).toInt()
                    if (count > OemBootstrapPolicy.MAX_SOURCE_BYTES - readBytes) throw IOException("32 MiB aggregate source-read budget reached")
                    // Include failed pread/post-stat attempts in the source quota.
                    readBytes += count
                    val bytes = device.chunk(entry, offset, maxOf(count, 1))
                    sourceCheck()
                    if (bytes.size != count) throw IOException("Strict source byte count mismatch")
                    space(bytes.size.toLong()); output.write(bytes); digest.update(bytes); offset += bytes.size
                    record.bytes = offset
                    publish("Copie des fichiers ciblés", "${entry.path} · $offset/${expected.size} octets")
                } while (offset < expected.size)
            }
            check(part.length() == expected.size && !target.exists() && part.renameTo(target)) { "Cannot finalize local source" }
            record.local = name; record.hash = digest.digest().hex(); record.status = "exported"; sourceBytes += record.bytes
        } catch (e: CancellationException) {
            record.status = "cancelled_incomplete"; record.error = e.message.orEmpty().take(256); throw e
        } catch (e: Exception) {
            record.status = "read_failed"; record.error = "${e.javaClass.simpleName}: ${e.message}".take(256)
            if (part.exists() && !part.delete()) throw IOException("Cannot remove incomplete local fragment")
            if (device.terminated) throw e
        }
    }
    private fun report(): Map<String, Any?> = mapOf("schema" to OemBootstrapPolicy.PROFILE, "kind" to ExportKind.OEM_BOOTSTRAP.name,
        "outcome" to outcome, "message" to message, "identity" to identity, "app_runtime" to appRuntime,
        "scope" to SCOPE, "limits" to LIMITS, "issues" to issues, "metadata_bytes_charged" to metadataBytes,
        "selected_processes" to selectedPids.map { mapOf("pid" to it.pid, "roles" to it.roles) },
        "source_bytes" to sourceBytes, "source_read_bytes" to readBytes, "inventory" to inventory.map { it.node() }, "processes" to processes,
        "files" to records.map { r -> mapOf("path" to r.entry.path, "index" to r.entry.index, "metadata_status" to r.entry.status,
            "strict_identity" to r.entry.stamp?.node(), "metadata" to r.entry.metadata.map { it.node() }, "status" to r.status,
            "bytes" to r.bytes, "sha256" to r.hash, "local_file" to r.local, "zip_entry" to if (r.local.isEmpty()) "" else "bootstrap/${r.local}", "error" to r.error) },
        "archive_scope" to "Collection result differs from ZIP delivery; integrite.json hashes the final ZIP if present.")
    private fun saveResults() = save("resultats.json", report())
    private fun save(name: String, value: Any) {
        val bytes = (if (value is String) value else JSONObject(value as Map<*, *>).toString()).toByteArray(Charsets.UTF_8)
        if (bytes.size > OemBootstrapPolicy.MAX_JSON_BYTES) throw IOException("Local metadata exceeds 4 MiB")
        space(bytes.size.toLong()); val part = File(directory, "$name.tmp"); part.writeBytes(bytes)
        if (!part.renameTo(File(directory, name))) throw IOException("Cannot finalize local $name")
    }
    private fun archive() {
        finalCheck(); space(sourceBytes + 8L * 1024 * 1024)
        publish(FINALIZING)
        val name = "oem-bootstrap-001.zip"; val part = File(directory, "$name.part")
        check(part.createNewFile())
        ZipOutputStream(part.outputStream().buffered(OemBootstrapPolicy.CHUNK)).use { zip ->
            val payloads = records.filter { it.status == "exported" }.map { File(directory, it.local) to "bootstrap/${it.local}" } +
                listOf("plan.json", "resultats.json", "manifest.json", "RESULT.txt").map { File(directory, it) to it }
            val buffer = ByteArray(OemBootstrapPolicy.CHUNK)
            for ((file, entry) in payloads) {
                finalCheck(); space(); zip.putNextEntry(ZipEntry(entry))
                val expected = records.firstOrNull { it.local == file.name && it.status == "exported" }
                val digest = if (expected == null) null else MessageDigest.getInstance("SHA-256")
                var count = 0L
                file.inputStream().use { input ->
                    while (true) {
                        finalCheck(); val n = input.read(buffer); if (n < 0) break
                        if (n == 0) throw IOException("Local source made no progress")
                        space(n.toLong()); zip.write(buffer, 0, n); digest?.update(buffer, 0, n); count += n
                    }
                }
                if (expected != null && (count != expected.bytes || digest!!.digest().hex() != expected.hash))
                    throw IOException("Finalized source changed before ZIP")
                zip.closeEntry()
            }
        }
        val target = File(directory, name); check(!target.exists() && part.renameTo(target)); archive = target
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(OemBootstrapPolicy.CHUNK)
            while (true) { finalCheck(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        save("integrite.json", mapOf("schema" to OemBootstrapPolicy.PROFILE, "collection_outcome" to outcome,
            "archive_outcome" to "finished", "archive" to name, "bytes" to target.length(), "sha256" to digest.digest().hex(),
            "hash_scope" to "Stored raw source bytes and final local ZIP; no authenticity, boot selection or atomic snapshot attested."))
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        const val FINALIZING = "Finalisation locale du ZIP de démarrage"
        const val SCOPE = "Fixed bootstrap/init/strategyservice and four startup-wrapper candidates only. Conditional cell2 paths come from static Fission evidence; presence/readability/active cell unproven. No recursion, proc root/exe following, property-derived paths, imported source execution, OEM RPC, settings/auth/policy changes or backend traffic. Read denials and missing paths are separate observations, not global absence. Proc is size-zero-capable bounded metadata, not strict regular-file input. /proc/self labels command utilities, not the pinned daemon. Per-PID stat starttime brackets detect reuse/disappearance; no Binder-to-PID publisher attribution or host namespace proof."
        const val LIMITS = "At most10 deduplicated PIDs: PID1, negotiated daemon PID, first4 numeric exact init and first4 exact strategyservice names. Proc per PID: stat/cmdline4KiB, status8KiB, mountinfo16KiB, links1KiB each;40KiB raw and under64KiB encoded/framed per PID, bounded before Base64. Metadata1MiB aggregate conservative wire charge including failed calls. Literal source list <=40, metadata batches <=4 paths with copy/checkpoint between batches; 8MiB/file;32MiB aggregate requested strict bytes including failed attempts;64KiB chunks. Local documents4MiB, free reserve128MiB plus pending write and ZIP allowance. Acquisition18min/session20min soft monotonic budgets; a physical call may linger beyond client timeout."
        const val TRANSPORT = "Already-connected pinned ProxyDaemon UID2000, protocol>=26, schema1 strict capability. WHOAMI/capabilities and every EXEC/chunk use shared physical-call quarantine, one transaction per closure,35s client wait. Timeout/malformed transport is terminal; late closure only fills private holders. No reconnect, retry, root/SELinux workaround, chroot/setns/ptrace or daemon/protocol/permission change. Existing EXEC transport may create its PID temporary. App-owned staging and local ZIP writes only; sharing manual."
    }
}
