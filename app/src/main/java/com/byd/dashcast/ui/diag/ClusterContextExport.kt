package com.byd.dashcast.ui.diag

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One exact provider file and bounded passive runtime witnesses; no global discovery. */
internal class ClusterContextExport(
    private val device: OemBootstrapExport.Device,
    private val directory: File,
    private val cancelled: () -> Boolean,
    private val progress: (FullApkExport.Progress) -> Unit,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val clock: () -> Long = FullApkExport::monotonicMs,
    private val appRuntime: Map<String, String> = emptyMap(),
) {
    private val started = clock()
    private var state = FullApkExport.Progress("Préparation du contexte cluster", kind = ExportKind.CLUSTER_CONTEXT, directory = directory)
    private var identity = emptyMap<String, String>()
    private var inventory = emptyList<OemBootstrapPolicy.Blob>()
    private var propertiesAfter = emptyList<OemBootstrapPolicy.Blob>()
    private var sourcesBefore = emptyList<OemBootstrapPolicy.Blob>()
    private var sourcesAfter = emptyList<OemBootstrapPolicy.Blob>()
    private var selected = emptyList<ClusterContextPolicy.Pid>()
    private val processes = mutableListOf<Map<String, Any?>>()
    private val issues = mutableListOf<String>()
    private var provider: OemBootstrapPolicy.Source? = null
    private var sourceStatus = "not_attempted"
    private var sourceError = ""
    private var copied = 0L
    private var readBytes = 0L
    private var sourceHash = ""
    private var metadataBytes = 0L
    private var metadataOk = 0
    private var metadataFailed = 0
    private var metadataPlanned = ClusterContextPolicy.inventoryLimits.size +
        ClusterContextPolicy.propertyLimits.size + 2 * ClusterContextPolicy.sourceLimits.size
    private var outcome = "running"
    private var archive: File? = null
    private var ownsDestination = false
    private val part get() = File(directory, "$PROVIDER_NAME.part")
    private val target get() = File(directory, PROVIDER_NAME)

    private fun checkSource() {
        if (cancelled()) throw CancellationException("Collection cancelled between reads")
        if (clock() - started >= ClusterContextPolicy.COLLECT_MS) throw IOException("3-minute soft acquisition budget reached")
        if (device.terminated) throw IOException("Physical transport terminated; no subsequent operation")
    }
    private fun checkFinal() {
        if (clock() - started >= ClusterContextPolicy.SESSION_MS) throw IOException("4-minute soft session budget reached; local evidence retained")
    }
    private fun space(extra: Long = 0) {
        if (freeBytes(directory) < OemBootstrapPolicy.RESERVE + extra) throw IOException("128 MiB local reserve plus pending write unavailable")
    }
    private fun issue(value: String) { if (issues.size < 128) issues += value.take(256) }
    private fun publish(label: String, current: String = "") {
        state = state.copy(stage = label, current = current, discovered = metadataPlanned + 1,
            completed = metadataOk + if (sourceStatus == "exported") 1 else 0,
            failed = metadataFailed + if (sourceStatus !in listOf("not_attempted", "copy_pending_postcheck", "exported")) 1 else 0,
            bytes = copied, lastProgressMs = clock())
        progress(state)
    }
    private fun metadata(label: String, limits: Map<String, Int>, pid: Int? = null): List<OemBootstrapPolicy.Blob> {
        checkSource()
        val reserve = limits.entries.sumOf { (id, cap) -> ((cap + 130L) / 3 + 1) * 4 + id.length + 128 } + 256
        if (reserve > OemBootstrapPolicy.MAX_METADATA_BYTES - metadataBytes) throw IOException("1 MiB aggregate metadata wire budget reached")
        metadataBytes += reserve
        val blobs = device.metadata(label, pid)
        checkSource()
        if (blobs.map { it.id } != limits.keys.toList() || blobs.any { it.limit != limits[it.id] || it.bytes.size > it.limit })
            throw IOException("Cluster metadata scope/size mismatch")
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
            check(directory.listFiles()?.all { it.name == "RUNNING.txt" && it.isFile } == true) { "Existing export retained" }
            ownsDestination = true
            space()
            save("plan.json", mapOf("schema" to ClusterContextPolicy.PROFILE, "kind" to ExportKind.CLUSTER_CONTEXT.name,
                "copied_literal_path" to ClusterContextPolicy.PROVIDER,
                "metadata_sha_only_paths" to ClusterContextPolicy.paths.drop(1), "properties_before_after" to ClusterContextPolicy.properties,
                "exact_process_names" to ClusterContextPolicy.names, "process_limits" to ClusterContextPolicy.processLimits,
                "scope" to SCOPE, "limits" to LIMITS, "transport" to OemBootstrapExport.TRANSPORT))
            checkSource()
            identity = device.negotiate(::checkSource)
            publish("Identité et contexte système", "Daemon déjà connecté ; aucune nouvelle connexion")
            inventory = metadata("inventory_before", ClusterContextPolicy.inventoryLimits)
            val selection = ClusterContextPolicy.select(inventory.first { it.id == "ps" })
            selected = selection.pids
            selection.issues.forEach(::issue)
            metadataPlanned += selected.size * ClusterContextPolicy.processLimits.size
            saveResults()

            publish("Identité des cinq chemins fixes", ClusterContextPolicy.PROVIDER)
            sourcesBefore = metadata("sources_before", ClusterContextPolicy.sourceLimits)
            provider = ClusterContextPolicy.provider(sourcesBefore)
            sourceStatus = provider!!.status
            saveResults()
            if (sourceStatus == "ok") copyProvider()

            for ((index, pid) in selected.withIndex()) {
                publish("Contexte des processus ${index + 1}/${selected.size}", "${pid.name} · PID ${pid.pid}")
                val blobs = metadata("pid_${pid.pid}", ClusterContextPolicy.processLimits, pid.pid)
                val observation = ClusterContextPolicy.identity(pid, blobs)
                processes += observation
                if (observation["identity_status"] != "starttime_bracket_consistent_name_verified")
                    issue("PID ${pid.pid}: starttime/name unverified; observations not attributed to selected process")
                saveResults()
            }
            publish("Vérification après collecte", "Métadonnées et SHA-256 des chemins fixes")
            sourcesAfter = metadata("sources_after", ClusterContextPolicy.sourceLimits)
            verifyProvider()
            propertiesAfter = metadata("properties_after", ClusterContextPolicy.propertyLimits)
            if (!propertiesConsistent()) issue("Selected properties missing/empty/changed; no consistent context bracket established")
            outcome = if (issues.isEmpty() && sourceStatus == "exported") "finished" else "partial"
        } catch (e: CancellationException) {
            outcome = "cancelled"; issue(e.message.orEmpty())
        } catch (e: Exception) {
            outcome = if (ownsDestination) "partial" else "failed"
            issue("${e.javaClass.simpleName}: ${e.message}")
        }
        if (sourceStatus == "copy_pending_postcheck") {
            sourceStatus = "postcheck_unavailable"
            sourceError = "No complete matching post-stat and source SHA bracket"
        }
        if (ownsDestination && part.exists() && !part.delete()) issue("Incomplete local provider fragment could not be removed")
        val message = "${if (sourceStatus == "exported") 1 else 0}/1 bibliothèque et ${processes.size}/${selected.size} relevés PID conservés ; consulter manifest.json."
        if (ownsDestination) try {
            saveResults(); save("manifest.json", report())
            save("RESULT.txt", "$message\nProfil ${ClusterContextPolicy.PROFILE} ; résultat $outcome.\n$SCOPE\n$LIMITS\n" +
                "Sources lues uniquement ; copies et ZIP locaux sur le stockage principal, partage manuel.\n" +
                "Instantanés non atomiques. Un résultat partiel ne prouve aucune absence ni compatibilité matérielle.\n")
            archive()
        } catch (e: Exception) {
            outcome = "failed"; issue("Local finalization: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { saveResults(); save("manifest.json", report()); save("ERREUR_LIVRAISON.txt", "Local finalization failed; finalized evidence retained. ${e.message}\n") }
        }
        publish(message, directory.path)
        state = state.copy(running = false, outcome = outcome, archives = listOfNotNull(archive))
        progress(state)
        return state
    }

    private fun copyProvider() {
        val source = provider ?: throw IOException("Provider metadata missing")
        val expected = source.stamp ?: throw IOException("Provider stamp missing")
        if (expected.size > OemBootstrapPolicy.MAX_FILE_BYTES) { sourceStatus = "file_byte_limit"; return }
        if (ClusterContextPolicy.sha(sourcesBefore, 0) == null) { sourceStatus = "source_sha_before_unavailable"; return }
        try {
            space(expected.size)
            check(!target.exists() && part.createNewFile())
            val digest = MessageDigest.getInstance("SHA-256")
            part.outputStream().buffered(OemBootstrapPolicy.CHUNK).use { output ->
                var offset = 0L
                do {
                    checkSource()
                    val count = minOf(OemBootstrapPolicy.CHUNK.toLong(), expected.size - offset).toInt()
                    if (count > OemBootstrapPolicy.MAX_FILE_BYTES - readBytes) throw IOException("8 MiB requested strict-byte budget reached")
                    readBytes += count
                    val bytes = device.chunk(source, offset, maxOf(count, 1))
                    checkSource()
                    if (bytes.size != count) throw IOException("Provider strict read byte count mismatch")
                    space(count.toLong()); output.write(bytes); digest.update(bytes); offset += count
                    copied = offset
                    publish("Copie de la bibliothèque cluster", "$offset/${expected.size} octets")
                } while (offset < expected.size)
            }
            sourceHash = digest.digest().hex()
            sourceStatus = "copy_pending_postcheck"
        } catch (e: CancellationException) {
            sourceStatus = "cancelled_incomplete"; throw e
        } catch (e: Exception) {
            sourceStatus = "read_failed"; sourceError = "${e.javaClass.simpleName}: ${e.message}".take(256)
            issue("Provider copy: $sourceError")
            if (device.terminated) throw e
        }
        saveResults()
    }

    private fun verifyProvider() {
        if (sourceStatus != "copy_pending_postcheck") return
        val before = provider ?: throw IOException("Provider identity missing")
        val after = ClusterContextPolicy.provider(sourcesAfter)
        val shaBefore = ClusterContextPolicy.sha(sourcesBefore, 0)
        val shaAfter = ClusterContextPolicy.sha(sourcesAfter, 0)
        if (after.status != "ok" || before.stamp != after.stamp || shaBefore == null ||
            shaAfter != shaBefore || sourceHash != shaBefore || part.length() != before.stamp?.size) {
            sourceStatus = "source_changed_or_postcheck_unverified"
            sourceError = "Provider pre/post stat, remote SHA and copied SHA do not all agree"
            issue(sourceError)
            return
        }
        check(!target.exists() && part.renameTo(target)) { "Cannot finalize verified provider" }
        sourceStatus = "exported"
    }

    private fun propertiesConsistent(): Boolean = ClusterContextPolicy.propertyLimits.keys.all { key ->
        val before = inventory.firstOrNull { it.id == key }
        val after = propertiesAfter.firstOrNull { it.id == key }
        before?.status == "ok" && after?.status == "ok" && before.bytes.isNotEmpty() &&
            before.bytes.toString(Charsets.ISO_8859_1).trim().isNotEmpty() && before.bytes.contentEquals(after.bytes)
    }

    private fun report(): Map<String, Any?> = mapOf("schema" to ClusterContextPolicy.PROFILE,
        "kind" to ExportKind.CLUSTER_CONTEXT.name, "outcome" to outcome, "identity" to identity, "app_runtime" to appRuntime,
        "scope" to SCOPE, "limits" to LIMITS, "transport" to OemBootstrapExport.TRANSPORT, "issues" to issues,
        "metadata_bytes_charged" to metadataBytes, "source_read_bytes" to readBytes,
        "selected_processes" to selected.map { mapOf("pid" to it.pid, "exact_name" to it.name) },
        "inventory_before" to inventory.map { it.node() }, "properties_after" to propertiesAfter.map { it.node() },
        "properties_bracket_consistent" to propertiesConsistent(), "sources_before" to sourcesBefore.map { it.node() },
        "sources_after" to sourcesAfter.map { it.node() }, "processes" to processes,
        "files" to listOf(mapOf("path" to ClusterContextPolicy.PROVIDER, "status" to sourceStatus,
            "strict_identity" to provider?.stamp?.node(), "bytes" to copied, "sha256" to sourceHash,
            "local_file" to if (sourceStatus == "exported") PROVIDER_NAME else "",
            "zip_entry" to if (sourceStatus == "exported") "cluster/$PROVIDER_NAME" else "", "error" to sourceError)),
        "archive_scope" to "Collection result differs from ZIP delivery; integrite.json hashes the final local ZIP if present.")
    private fun saveResults() = save("resultats.json", report())
    private fun save(name: String, value: Any) {
        val bytes = (if (value is String) value else JSONObject(value as Map<*, *>).toString()).toByteArray(Charsets.UTF_8)
        if (bytes.size > OemBootstrapPolicy.MAX_JSON_BYTES) throw IOException("Local metadata exceeds 4 MiB")
        space(bytes.size.toLong())
        val temporary = File(directory, "$name.tmp"); temporary.writeBytes(bytes)
        check(temporary.renameTo(File(directory, name))) { "Cannot finalize $name" }
    }
    private fun archive() {
        checkFinal(); space((if (sourceStatus == "exported") copied else 0) + 8L * 1024 * 1024)
        publish(FINALIZING)
        val name = "cluster-context-001.zip"
        val staging = File(directory, "$name.part"); check(staging.createNewFile())
        ZipOutputStream(staging.outputStream().buffered(OemBootstrapPolicy.CHUNK)).use { zip ->
            val payloads = listOf("plan.json", "resultats.json", "manifest.json", "RESULT.txt").map { File(directory, it) to it } +
                if (sourceStatus == "exported") listOf(target to "cluster/$PROVIDER_NAME") else emptyList()
            val buffer = ByteArray(OemBootstrapPolicy.CHUNK)
            for ((file, entry) in payloads) {
                checkFinal(); space(); zip.putNextEntry(ZipEntry(entry))
                val digest = if (file == target) MessageDigest.getInstance("SHA-256") else null
                var count = 0L
                file.inputStream().use { input ->
                    while (true) {
                        checkFinal()
                        val n = input.read(buffer); if (n < 0) break
                        if (n == 0) throw IOException("Local payload made no progress")
                        zip.write(buffer, 0, n); digest?.update(buffer, 0, n); count += n
                    }
                }
                if (digest != null && (count != copied || digest.digest().hex() != sourceHash))
                    throw IOException("Verified local provider changed before ZIP")
                zip.closeEntry()
            }
        }
        val final = File(directory, name); check(!final.exists() && staging.renameTo(final)); archive = final
        val digest = MessageDigest.getInstance("SHA-256")
        final.inputStream().use { input ->
            val buffer = ByteArray(OemBootstrapPolicy.CHUNK)
            while (true) { checkFinal(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        save("integrite.json", mapOf("schema" to ClusterContextPolicy.PROFILE, "collection_outcome" to outcome,
            "archive_outcome" to "finished", "archive" to name, "bytes" to final.length(), "sha256" to digest.digest().hex(),
            "hash_scope" to "Stored copied bytes and final local ZIP; source SHA agreement is not authenticity, loaded-provider identity or an atomic snapshot."))
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val PROVIDER_NAME = "libwindowsurface_android.so"
        const val FINALIZING = "Finalisation locale du contexte cluster"
        const val SCOPE = "Copy only literal /system/lib64/libwindowsurface_android.so after canonical regular-file checks. Four other literal paths get metadata/SHA only. Fingerprint/API/product/fission properties before/after. Exact process names qtandroidnative, com.xdja.containerservice and surfaceflinger; no broad proc scan. stat starttime before/after plus first-NUL full argv0, bounded status/maps and exe/root/mnt/pid link text. No argument after argv0 exported. No link/maps/property-derived paths read or executed, no proc root traversal, library loading, OEM Binder probing, permissions, restarts, display activation or vehicle setting changes. Partial/truncated/denied observations establish no absence or complete loaded-provider identity. No atomic snapshot or pixel freshness proof."
        const val LIMITS = "At most2 numeric PIDs per exact name,6 total; maps offset0 <=64KiB/PID (<=384KiB raw total), stat4KiB each, argv0 1KiB, status8KiB and four links1KiB each. Missing first-NUL/unsupported read syntax is unverified. Shared framing is bounded before Base64 and below unchanged192KiB EXEC character cap; aggregate metadata1MiB conservative encoded charge, including failed calls. One provider <=8MiB, strict chunks64KiB, no retry, pre/post stat and remote/local SHA agreement required for finalized copy. Local documents4MiB; free reserve128MiB plus pending writes. Acquisition3min and total4min soft monotonic budgets reserve up to1min local finalization;35s client transport timeout does not bound a lingering physical call. Truncation/PID reuse/refusal remains explicit; snapshot is non-atomic."
    }
}
