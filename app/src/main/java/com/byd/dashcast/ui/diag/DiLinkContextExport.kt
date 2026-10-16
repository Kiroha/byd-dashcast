package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixed, bounded context reads. Only app-owned export files are written by this collector. */
@SuppressLint("UsableSpace") // Reserve actual writable space; do not count or evict unrelated caches.
internal class DiLinkContextExport(
    private val device: Device,
    private val directory: File,
    private val cancelled: () -> Boolean,
    private val progress: (FullApkExport.Progress) -> Unit,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val appRuntime: Map<String, Any?> = emptyMap(),
) {
    fun interface Device { fun read(command: DiLinkContextPlan.Command): DiLinkContextTransport.Reply }
    private var state = FullApkExport.Progress("Préparation du relevé DiLink", kind = ExportKind.CONTEXT,
        discovered = DiLinkContextPlan.commands.size, directory = directory)
    private val rows = mutableListOf<Map<String, Any?>>()
    private val outputFiles = mutableListOf<File>()
    private var outcome = "running"
    private var message = ""
    private val startedUtc = utcNow()
    private var ownsDestination = false

    fun run(): FullApkExport.Progress {
        var archive: File? = null
        var deliveryFailed = false
        try {
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create context directory" }
            check(directory.listFiles()?.all { it.name == "RUNNING.txt" && it.isFile } == true) {
                "Context destination already contains an export; existing files retained"
            }
            ownsDestination = true
            ensureSpace()
            save("plan.json", json(mapOf("schema" to SCHEMA, "commands" to DiLinkContextPlan.commands.map(::commandJson),
                "not_applicable" to listOf("adb version (PC-only)", "adb devices (PC-only)"),
                "transport" to TRANSPORT, "app_runtime" to appRuntime, "output_format" to DiLinkContextPlan.OUTPUT_DESCRIPTION)))
            writeResults()
            for ((index, command) in DiLinkContextPlan.commands.withIndex()) {
                if (cancelled()) { outcome = "cancelled"; break }
                ensureSpace()
                publish(state.copy(stage = "Lecture ${index + 1}/${DiLinkContextPlan.commands.size} · ${command.label}",
                    current = command.shell, lastProgressMs = FullApkExport.monotonicMs()))
                val beforeUtc = utcNow()
                val before = FullApkExport.monotonicMs()
                val reply = try { device.read(command) } catch (e: Exception) {
                    DiLinkContextTransport.Reply(null, "", "${e.javaClass.simpleName}: ${e.message}", true)
                }
                // Defence in depth for an old/malformed transport. Never archive unlimited text.
                val received = reply.combinedOutput.toByteArray(Charsets.UTF_8)
                val clipped = received.size > MAX_ARCHIVED_OUTPUT
                val text = if (clipped) String(received, 0, MAX_ARCHIVED_OUTPUT, Charsets.UTF_8) else reply.combinedOutput
                val interpreted = if (clipped) DiLinkContextPlan.Interpretation("truncated", "Collector output limit reached")
                    else DiLinkContextPlan.interpret(command, reply.exitCode, text, reply.error.take(2048))
                val outputName = "${(index + 1).toString().padStart(2, '0')}-${command.key}.combined.txt"
                val output = File(directory, outputName)
                output.writeText(text, Charsets.UTF_8)
                outputFiles.add(output)
                rows.add(commandJson(command) + mapOf("status" to interpreted.status, "reason" to interpreted.reason,
                    "started_utc_from_device_clock" to beforeUtc, "ended_utc_from_device_clock" to utcNow(),
                    "elapsed_ms" to (FullApkExport.monotonicMs() - before), "exit_code" to reply.exitCode,
                    "transport_error" to reply.error.take(2048), "transport_fatal" to reply.fatal,
                    "collector_clipped" to clipped, "combined_output" to fileJson(output), "observed" to interpreted.values))
                publish(state.copy(completed = rows.count { it["status"] == "ok" },
                    failed = rows.count { it["status"] != "ok" }, bytes = outputFiles.sumOf { it.length() },
                    lastProgressMs = FullApkExport.monotonicMs()))
                writeResults()
                if (reply.fatal) { message = reply.error.take(2048); outcome = "partial"; break }
            }
            if (outcome == "running") outcome = if (cancelled()) "cancelled"
                else if (rows.size == DiLinkContextPlan.commands.size && rows.all { it["status"] == "ok" }) "finished" else "partial"
            message = when (outcome) {
                "finished" -> "Les 14 lectures du plan ont fourni des résultats reconnus. Validation matérielle et droits OEM non établis."
                "cancelled" -> "Relevé annulé entre deux commandes ; les résultats déjà reçus sont conservés."
                else -> message.ifEmpty { "Des lectures sont indisponibles ou incomplètes ; consulter resultats.json." }
            }
            val payload = writeFinalMetadata()
            publish(state.copy(stage = "Création du ZIP local", current = "context-001.zip", lastProgressMs = FullApkExport.monotonicMs()))
            ensureSpace()
            val part = File(directory, "context-001.zip.part")
            check(part.createNewFile()) { "Archive staging file already exists" }
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                for (file in payload + File(directory, "manifest.json")) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val target = File(directory, "context-001.zip")
            check(!target.exists() && part.renameTo(target)) { "Cannot finalize context ZIP; .part is incomplete" }
            archive = target
            // Outside the ZIP to avoid a self-referential archive digest.
            save("integrite.json", json(mapOf("schema" to SCHEMA, "collection_outcome" to outcome,
                "archive_outcome" to "finished", "archive" to fileJson(target),
                "manifest" to fileJson(File(directory, "manifest.json")), "hash_scope" to "Stored exported bytes; not original separate stdout/stderr streams")))
        } catch (e: Exception) {
            deliveryFailed = true
            message = "${e.javaClass.simpleName}: ${e.message}".take(2048)
            if (archive == null) {
                outcome = "failed"
                // Align any already-written metadata after a failed ZIP/space/rename operation.
                if (ownsDestination) runCatching { writeFinalMetadata() }
            } else {
                // A successfully closed ZIP stays immutable when its hash sidecar fails.
                // Its outcome describes collection, explicitly not final delivery integrity.
                runCatching { save("ERREUR_LIVRAISON.txt", "ZIP finalisé conservé. Échec du fichier d'intégrité : $message\n") }
            }
        }
        val archives = listOfNotNull(archive)
        publish(state.copy(stage = message, current = directory.absolutePath, running = false,
            outcome = if (deliveryFailed) "failed" else outcome,
            completed = rows.count { it["status"] == "ok" }, failed = rows.count { it["status"] != "ok" },
            archives = archives, lastProgressMs = FullApkExport.monotonicMs()))
        return state
    }

    private fun writeFinalMetadata(): List<File> {
        writeResults()
        save("RESULT.txt", "Relevé DiLink (résultat des lectures) : $outcome\n$message\n${rows.size}/14 lectures traitées.\n" +
            "La finalisation du ZIP et son SHA-256 sont attestés séparément par integrite.json.\n" +
            "Données système lues ; réglages véhicule inchangés par ces commandes. Export local uniquement.\n" +
            "Le transport existant peut créer son fichier PID temporaire. Aucune activité OEM n'est ouverte.\n" +
            "Résultats non atomiques ; horloge observée sur l'appareil, aucune comparaison PC fiable.\n")
        val payload = listOf(File(directory, "plan.json"), File(directory, "resultats.json"), File(directory, "RESULT.txt")) + outputFiles
        save("manifest.json", json(mapOf("schema" to SCHEMA, "kind" to ExportKind.CONTEXT.name,
            "outcome" to outcome, "outcome_scope" to "Collection only; ZIP finalization and archive digest are attested separately by integrite.json.",
            "message" to message, "planned" to 14, "attempted" to rows.size,
            "files" to payload.map(::fileJson), "coverage" to "Fixed P12 metadata plan only; complete DiLink platform coverage is not established.",
            "app_runtime" to appRuntime, "output_format" to DiLinkContextPlan.OUTPUT_DESCRIPTION)))
        return payload
    }

    private fun writeResults() {
        val completedKeys = rows.map { it["key"] }.toSet()
        save("resultats.json", json(mapOf("schema" to SCHEMA, "kind" to ExportKind.CONTEXT.name,
            "outcome" to outcome, "message" to message, "started_utc_from_device_clock" to startedUtc,
            "outcome_scope" to "Collection only; ZIP finalization and archive digest are attested separately by integrite.json.",
            "updated_utc_from_device_clock" to utcNow(), "planned" to 14, "attempted" to rows.size,
            "transport" to TRANSPORT, "app_runtime" to appRuntime, "output_format" to DiLinkContextPlan.OUTPUT_DESCRIPTION,
            "commands" to (rows + DiLinkContextPlan.commands.filter { it.key !in completedKeys }.map {
                commandJson(it) + mapOf("status" to if (outcome == "running") "pending" else "not_attempted")
            }), "not_applicable" to listOf("adb version (PC-only)", "adb devices (PC-only)"),
            "limitations" to listOf("No APK, activity or OEM operation is launched.",
                "No VIN, IMEI or account is requested; package/service metadata can still identify the firmware.",
                "No independent PC clock comparison. Empty properties remain unknown.",
                "Service list and package metadata do not prove access rights or local authorization.",
                "No connection, bootstrap, retry, root, settings, certificate or DNS operation by this feature."))))
    }
    private fun commandJson(command: DiLinkContextPlan.Command): Map<String, Any?> = mapOf(
        "key" to command.key, "label" to command.label, "argv" to command.argv, "shell" to command.shell,
        "kind" to command.kind.name, "subject" to command.subject,
        "command_timeout_ms" to DiLinkContextPlan.COMMAND_TIMEOUT_MS, "transport_timeout_ms" to DiLinkContextPlan.TRANSPORT_TIMEOUT_MS)
    private fun publish(next: FullApkExport.Progress) { state = next; progress(next) }
    private fun ensureSpace() { if (freeBytes(directory) < MIN_FREE_BYTES) throw IOException("Insufficient local export space (16 MiB reserve)") }
    private fun save(name: String, value: String) {
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(File(directory, name))) throw IOException("Cannot finalize $name")
    }
    private fun fileJson(file: File): Map<String, Any?> = mapOf("name" to file.name, "bytes" to file.length(),
        "sha256" to file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(8192)
            while (true) { val n = input.read(bytes); if (n < 0) break; if (n > 0) digest.update(bytes, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        })
    companion object {
        const val SCHEMA = "dilink-in-app-fixed-context-v1"
        private const val TRANSPORT = "Already-connected pinned PROXY daemon Binder TXN_EXEC; no reconnect or fallback. UID observed by id, not assumed."
        private const val MAX_ARCHIVED_OUTPUT = DiLinkContextPlan.MAX_OUTPUT_BYTES + 64
        private const val MIN_FREE_BYTES = 16L * 1024 * 1024
        private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        private fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> FullApkExport.json(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { FullApkExport.json(it.key.toString()) + ":" + json(it.value) }
            is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
            else -> error("Unsupported JSON value")
        }
    }
}
