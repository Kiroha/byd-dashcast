package com.byd.dashcast.ui.diag

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A small source collector, independent of the global firmware/mount inventory. Raw init
 * bytes are streamed to app-owned files and a local ZIP; none is parsed or executed here. */
internal class OemInitSourcesExport(
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
        fun page(root: Int, offset: Int): OemInitSourcesPolicy.Page
        fun chunk(entry: OemInitSourcesPolicy.Entry, offset: Long, length: Int): ByteArray
    }
    private data class Record(val entry: OemInitSourcesPolicy.Entry, var status: String = entry.status,
        var bytes: Long = 0, var hash: String = "", var local: String = "", var error: String = "")
    private val started = clock()
    private var state = FullApkExport.Progress("Préparation des sources init", kind = ExportKind.OEM_INIT_SOURCES, directory = directory)
    private val records = mutableListOf<Record>()
    private val issues = mutableListOf<String>()
    private val roots = mutableListOf<Map<String, Any?>>()
    private val pages = mutableListOf<Map<String, Any?>>()
    private var identity = emptyMap<String, String>()
    private var wireBytes = 0
    private var pathBytes = 0
    private var sourceBytes = 0L
    private var readBytes = 0L
    private var outcome = "running"
    private var message = ""
    private var archive: File? = null
    private var owned = false
    private var checkpointCount = 0
    private var checkpointTime = started
    private fun issue(text: String) { if (issues.size < 128) issues.add(text.take(256)) }
    private fun sourceCheck() {
        if (cancelled()) throw CancellationException("Source collection cancelled; completed local files retained")
        if (clock() - started >= OemInitSourcesPolicy.COLLECT_MS) throw IOException("18-minute acquisition budget reached; local finalization reserved")
        if (device.terminated) throw IOException("Physical source transport terminated; no subsequent call")
    }
    private fun finalCheck() {
        if (clock() - started >= OemInitSourcesPolicy.SESSION_MS) throw IOException("20-minute session budget reached; completed staged sources retained")
    }
    private fun space(need: Long = 0) {
        val free = freeBytes(directory)
        if (free < OemInitSourcesPolicy.RESERVE + need) throw IOException("Local storage reserve unavailable (128 MiB plus pending write)")
    }
    private fun publish(label: String, current: String = "") {
        state = state.copy(stage = label, current = current, discovered = records.size,
            completed = records.count { it.status == "exported" }, failed = records.count { it.status !in listOf("ok", "exported") },
            bytes = sourceBytes, totalBytes = null, lastProgressMs = clock())
        progress(state)
    }
    fun run(): FullApkExport.Progress {
        try {
            check(directory.mkdirs() || directory.isDirectory)
            check(directory.listFiles()?.all { it.name == "RUNNING.txt" && it.isFile } == true) { "Existing source export retained; destination is not empty" }
            owned = true
            space()
            save("plan.json", mapOf("schema" to OemInitSourcesPolicy.PROFILE, "kind" to ExportKind.OEM_INIT_SOURCES.name,
                "roots" to OemInitSourcesPolicy.roots, "scope" to SCOPE, "limits" to LIMITS, "transport" to TRANSPORT))
            sourceCheck(); identity = device.negotiate(::sourceCheck)
            try { inventory(false) } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                issue("Initial inventory incomplete: ${e.message}")
                if (device.terminated) throw e
            }
            saveResults()
            for ((index, record) in records.withIndex()) {
                sourceCheck()
                if (record.status != "ok") continue
                val entry = record.entry
                val expected = entry.stamp ?: continue
                if (expected.size > OemInitSourcesPolicy.MAX_FILE_BYTES) { record.status = "file_byte_limit"; checkpoint(); continue }
                if (expected.size > OemInitSourcesPolicy.MAX_SOURCE_BYTES - readBytes) { record.status = "total_source_read_byte_limit"; checkpoint(); continue }
                publish("Copie source ${index + 1}/${records.size}", entry.path.orEmpty())
                val name = "source-${index.toString().padStart(4, '0')}.rc"
                val part = File(directory, "$name.part")
                val target = File(directory, name)
                try {
                    space(expected.size)
                    check(!target.exists() && part.createNewFile())
                    val digest = MessageDigest.getInstance("SHA-256")
                    part.outputStream().buffered(OemInitSourcesPolicy.CHUNK).use { output ->
                        var offset = 0L
                        // Empty files still open/fstat on the daemon; an empty reply is not assumed readable.
                        do {
                            sourceCheck()
                            val count = minOf(OemInitSourcesPolicy.CHUNK.toLong(), expected.size - offset).toInt()
                            if (count > OemInitSourcesPolicy.MAX_SOURCE_BYTES - readBytes) throw IOException("32 MiB aggregate source-read budget reached")
                            // Reserve before the physical operation: pread may succeed and then
                            // fail its post-stat, returning no bytes to this client.
                            readBytes += count
                            val bytes = device.chunk(entry, offset, maxOf(count, 1))
                            sourceCheck()
                            if (bytes.size != count) throw IOException("Strict source byte count mismatch")
                            space(bytes.size.toLong())
                            output.write(bytes); digest.update(bytes); offset += bytes.size
                            record.bytes = offset
                            publish("Copie source ${index + 1}/${records.size}", entry.path.orEmpty())
                        } while (offset < expected.size)
                    }
                    check(part.length() == expected.size && !target.exists() && part.renameTo(target)) { "Cannot finalize source staging file" }
                    record.local = name
                    record.hash = digest.digest().hex()
                    record.status = "exported"
                    sourceBytes += record.bytes
                } catch (e: CancellationException) {
                    record.status = "cancelled_incomplete"; record.error = e.message.orEmpty().take(256); throw e
                } catch (e: Exception) {
                    record.status = "read_failed"; record.error = "${e.javaClass.simpleName}: ${e.message}".take(256)
                    // Failed fragments remain explicitly .part, outside the ZIP. Do not spend
                    // another 32 MiB on failures; only this app-owned incomplete fragment is removed.
                    if (part.exists() && !part.delete()) throw IOException("Cannot remove incomplete local fragment")
                    if (device.terminated) throw e
                }
                checkpoint()
            }
            inventory(true)
            outcome = if (issues.isEmpty() && records.isNotEmpty() && records.all { it.status == "exported" }) "finished" else "partial"
        } catch (e: CancellationException) {
            outcome = "cancelled"; issue(e.message.orEmpty())
        } catch (e: Exception) {
            outcome = if (owned) "partial" else "failed"; issue("${e.javaClass.simpleName}: ${e.message}")
        }
        records.filter { it.status == "ok" }.forEach { it.status = "not_attempted" }
        message = "${records.count { it.status == "exported" }}/${records.size} sources conservées. Consulter manifest.json ; couverture limitée et non atomique."
        if (owned) try {
            saveResults()
            save("RESULT.txt", "$message\nProfil ${OemInitSourcesPolicy.PROFILE}; résultat $outcome.\n$SCOPE\n$LIMITS\n$TRANSPORT\n" +
                "Sources lues uniquement. DashCast écrit ses fichiers locaux et le ZIP ; partage manuel. Les .part ne sont pas des sources complètes.\n" +
                "Si le ZIP échoue, les sources déjà finalisées restent dans ce dossier. Aucun contenu init exécuté, import suivi ou appel OEM d’authentification/politique effectué.\n")
            saveManifest()
            archive()
        } catch (e: Exception) {
            outcome = "failed"; issue("Archive/local finalization: ${e.javaClass.simpleName}: ${e.message}")
            message = "Finalisation incomplète ; sources finalisées conservées dans le dossier. ${e.message}".take(512)
            runCatching { saveResults(); saveManifest(); save("ERREUR_LIVRAISON.txt", "$message\n") }
        }
        state = state.copy(stage = message, current = directory.path, running = false, outcome = outcome,
            completed = records.count { it.status == "exported" }, failed = records.count { it.status != "exported" },
            bytes = sourceBytes, discovered = records.size, archives = listOfNotNull(archive), lastProgressMs = clock())
        progress(state)
        return state
    }
    private fun inventory(recheck: Boolean) {
        val seen = linkedMapOf<String, OemInitSourcesPolicy.Entry>()
        var count = 0
        var localPathBytes = 0
        for (root in OemInitSourcesPolicy.roots.indices) {
            var offset = 0
            do {
                sourceCheck()
                publish(if (recheck) "Vérification finale de l’inventaire" else "Inventaire des sources init", "${OemInitSourcesPolicy.roots[root]} · page ${offset / OemInitSourcesPolicy.PAGE + 1} · entrées ${offset + 1}–${offset + OemInitSourcesPolicy.PAGE}")
                val page = device.page(root, offset)
                sourceCheck()
                wireBytes += page.wireBytes
                if (wireBytes > OemInitSourcesPolicy.MAX_WIRE_BYTES) throw IOException("4 MiB aggregate inventory wire budget reached")
                if (!recheck) {
                    pages.add(mapOf("root" to root, "offset" to offset, "entries" to page.entries.size, "more" to page.more))
                    if (offset == 0) roots.add(mapOf("root" to OemInitSourcesPolicy.roots[root], "status" to page.rootStatus, "stamp" to page.rootStamp))
                }
                if (page.rootStatus != "ok") issue("${if (recheck) "Final" else "Initial"} root $root: ${page.rootStatus}; unavailable is not absence")
                for (entry in page.entries) {
                    sourceCheck()
                    count++
                    if (count > OemInitSourcesPolicy.MAX_ENTRIES) throw IOException("2048-entry inventory budget reached (rejected entries included)")
                    localPathBytes += entry.encodedPath.length * 3 / 4
                    if (localPathBytes > OemInitSourcesPolicy.MAX_PATH_BYTES) throw IOException("256 KiB source-path metadata budget reached")
                    val previous = seen.putIfAbsent(entry.encodedPath, entry)
                    if (previous != null) {
                        issue("Duplicate path observed in paged inventory; ${if (previous == entry) "same" else "conflicting"} identity")
                        if (!recheck) records.firstOrNull { it.entry.encodedPath == entry.encodedPath }?.let {
                            it.status = "duplicate_or_conflicting_inventory"
                        }
                    } else if (!recheck) records.add(Record(entry))
                }
                if (!recheck) { pathBytes = localPathBytes; saveResults() }
                offset += OemInitSourcesPolicy.PAGE
                if (page.more && offset >= OemInitSourcesPolicy.MAX_ENTRIES) throw IOException("Root ordinal budget reached; remaining entries unobserved")
            } while (page.more)
        }
        if (recheck) {
            val before = records.associate { it.entry.encodedPath to it.entry }
            if (before != seen) issue("Initial/final path or strict metadata differs; collection is non-atomic")
        }
    }
    private fun recordJson(record: Record): Map<String, Any?> = mapOf("path" to record.entry.path,
        "path_bytes_base64" to record.entry.encodedPath, "root" to record.entry.root, "ordinal" to record.entry.ordinal,
        "inventory_status" to record.entry.status, "strict_identity" to record.entry.stamp?.node(),
        "status" to record.status, "bytes" to record.bytes, "sha256" to record.hash, "local_file" to record.local,
        "zip_entry" to if (record.local.isEmpty()) "" else "init-sources/${record.local}", "error" to record.error)
    private fun report() = mapOf("schema" to OemInitSourcesPolicy.PROFILE, "kind" to ExportKind.OEM_INIT_SOURCES.name,
        "outcome" to outcome, "message" to message, "identity" to identity, "app_runtime" to appRuntime, "scope" to SCOPE, "limits" to LIMITS,
        "roots" to roots, "pages" to pages, "issues" to issues, "path_metadata_bytes" to pathBytes,
        "wire_metadata_bytes" to wireBytes, "source_bytes" to sourceBytes, "source_read_bytes" to readBytes, "files" to records.map(::recordJson),
        "archive_scope" to "Collection outcome is separate from ZIP delivery; integrite.json attests the final ZIP if present.")
    private fun saveResults() = save("resultats.json", report())
    private fun checkpoint() {
        checkpointCount++
        if (checkpointCount >= 16 || clock() - checkpointTime >= 1000) {
            saveResults(); checkpointCount = 0; checkpointTime = clock()
        }
    }
    private fun saveManifest() = save("manifest.json", report())
    private fun save(name: String, value: Any) {
        val bytes = (if (value is String) value else JSONObject(value as Map<*, *>).toString()).toByteArray(Charsets.UTF_8)
        if (bytes.size > OemInitSourcesPolicy.MAX_JSON_BYTES) throw IOException("Local metadata document exceeds 4 MiB")
        space(bytes.size.toLong())
        val part = File(directory, "$name.tmp")
        part.writeBytes(bytes)
        if (!part.renameTo(File(directory, name))) throw IOException("Cannot finalize local $name")
    }
    private fun archive() {
        finalCheck(); space(sourceBytes + 8L * 1024 * 1024)
        publish("Création du ZIP des sources déjà acquises")
        val name = "oem-init-sources-001.zip"
        val part = File(directory, "$name.part")
        check(part.createNewFile())
        ZipOutputStream(part.outputStream().buffered(OemInitSourcesPolicy.CHUNK)).use { zip ->
            val payloads = records.filter { it.status == "exported" }.map { File(directory, it.local) to "init-sources/${it.local}" } +
                listOf("plan.json", "resultats.json", "manifest.json", "RESULT.txt").map { File(directory, it) to it }
            val buffer = ByteArray(OemInitSourcesPolicy.CHUNK)
            for ((file, entry) in payloads) {
                finalCheck(); space()
                zip.putNextEntry(ZipEntry(entry))
                val expected = records.firstOrNull { it.local == file.name && it.status == "exported" }
                val copiedDigest = if (expected == null) null else MessageDigest.getInstance("SHA-256")
                var copied = 0L
                file.inputStream().use { input ->
                    while (true) {
                        finalCheck()
                        val n = input.read(buffer); if (n < 0) break
                        if (n == 0) throw IOException("Local source read made no progress")
                        space(n.toLong()); zip.write(buffer, 0, n)
                        copiedDigest?.update(buffer, 0, n); copied += n
                    }
                }
                if (expected != null && (copied != expected.bytes || copiedDigest!!.digest().hex() != expected.hash))
                    throw IOException("Completed local source changed before ZIP finalization")
                zip.closeEntry()
            }
        }
        val target = File(directory, name)
        check(!target.exists() && part.renameTo(target))
        archive = target // A finalized ZIP survives a sidecar/hash failure.
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(OemInitSourcesPolicy.CHUNK)
            while (true) { finalCheck(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        save("integrite.json", mapOf("schema" to OemInitSourcesPolicy.PROFILE, "collection_outcome" to outcome,
            "archive_outcome" to "finished", "archive" to name, "bytes" to target.length(), "sha256" to digest.digest().hex(),
            "hash_scope" to "Stored original source bytes and final local ZIP; no source authenticity or atomic snapshot attested."))
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        const val SCOPE = "Only /init*.rc and direct *.rc children of five literal partition etc/init and hw roots. No recursive/global scan, links or imports followed. Canonical shell namespace paths, not physical-medium attestation; global firmware policy unchanged. Live non-atomic observations; inaccessible roots/files are unknown, never absent."
        const val LIMITS = "2048 entries including rejected names; 16 entries/page; 2 MiB/file; 32 MiB aggregate source reads including failed attempts; 64 KiB chunks; 4 MiB inventory wire across initial/final checks; 256 KiB decoded-path metadata per inventory; 4 MiB/document; 128 MiB local free reserve plus pending writes. Acquisition 18 min, total session 20 min soft monotonic budgets. POSIX glob expansion occurs before count limits. Limits/failed reads remain explicit gaps."
        const val TRANSPORT = "Already-connected pinned ProxyDaemon UID2000, protocol>=26, strict schema1; WHOAMI/capabilities and each metadata/chunk operation use the same physical-call quarantine. One operation per closure, 35s client wait; a late physical call may linger and keeps diagnostics blocked. No reconnect, retry, fallback, root, OEM policy/authentication call or source execution. Existing shell inventory transport may write its PID temporary."
    }
}
