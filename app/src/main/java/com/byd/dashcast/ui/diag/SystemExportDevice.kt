package com.byd.dashcast.ui.diag

import android.content.Context
import android.os.Build
import com.byd.dashcast.proxy.ProxyClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CancellationException

/** Shell script builders are independently exercised on local fixtures, never on a vehicle. */
internal object SystemInventoryCommands {
    fun scan(root: String, mode: String, apexNames: List<String> = emptyList()): String {
        val qroot = ApkInventoryShell.quote(root)
        val predicate = when (mode) {
            "framework" -> "\\( -name '*.jar' -o -name '*.art' -o -name '*.oat' -o -name '*.odex' -o -name '*.vdex' \\)"
            "xml" -> "-name '*.xml'"
            "init" -> "-name '*.rc'"
            "native" -> SystemExportPolicy.nativeNames.joinToString(" -o ", "\\( ", " \\)") { "-name ${ApkInventoryShell.quote(it)}" }
            "apex" -> apexNames.joinToString(" -o ", "\\( ", " \\)") { "-name ${ApkInventoryShell.quote(it)}" }
            "exact" -> ""
            else -> error("Unknown fixed inventory mode")
        }
        val inspect = inspectFiles(mode)
        if (mode == "exact") return "if [ -f $qroot ]; then sh -c ${ApkInventoryShell.quote(inspect)} _ $qroot; " +
            "else printf 'ROOT_UNAVAILABLE|%s\\n' $qroot; fi"
        val roots = when (mode) {
            "framework" -> SystemExportPolicy.frameworkRoots
            "xml" -> SystemExportPolicy.xmlRoots
            "init" -> SystemExportPolicy.initRoots
            "native" -> SystemExportPolicy.nativeRoots
            "apex" -> listOf("runtime", "art", "conscrypt", "i18n").flatMap {
                listOf("/apex/com.android.$it/javalib", "/apex/com.android.$it@*/javalib") }
            else -> error("Unknown root mode")
        }
        val rootPatterns = (roots + roots.filter { it.startsWith("/product/") || it.startsWith("/system_ext/") || it.startsWith("/vendor/") }.map { "/system$it" }).joinToString("|")
        val rootGuard = "dc_root=\$(readlink -f $qroot); case \"\$dc_root\" in $rootPatterns) ;; " +
            "*) printf 'ROOT_POLICY_DENIED|%s\\n' $qroot; exit 0 ;; esac; " +
            (if (mode == "apex") "dc_module=\${dc_root#/apex/}; dc_module=\${dc_module%%/*}; " +
                "case \"\$dc_module\" in *@*) dc_version=\${dc_module#*@}; case \"\$dc_version\" in ''|*[!0-9]*) " +
                "printf 'ROOT_POLICY_DENIED|%s\\n' $qroot; exit 0 ;; esac ;; esac; " else "")
        return "if [ ! -d $qroot ]; then printf 'ROOT_UNAVAILABLE|%s\\n' $qroot; " +
            "elif [ ! -r $qroot ] || [ ! -x $qroot ]; then printf 'ROOT_DENIED|%s\\n' $qroot; " +
            "else $rootGuard find -H \"\$dc_root\" ${if (mode == "native") "-maxdepth 1 " else ""}" +
            "\\( -type f -o -type l \\) $predicate -exec sh -c ${ApkInventoryShell.quote(inspect)} _ {} +; fi"

    }
    fun inspectFiles(mode: String): String {
        fun aliases(roots: List<String>) = roots + roots.filterNot { it.startsWith("/system/") }.map { "/system$it" }
        val canonicalPatterns = when (mode) {
            "framework" -> aliases(SystemExportPolicy.frameworkRoots).flatMap { rootPath ->
                listOf("jar", "art", "oat", "odex", "vdex").map { "$rootPath/*.$it" } }
            "xml" -> aliases(SystemExportPolicy.xmlRoots).map { "$it/*.xml" }
            "init" -> aliases(SystemExportPolicy.initRoots).map { "$it/*.rc" }
            "native" -> aliases(SystemExportPolicy.nativeRoots).flatMap { rootPath -> SystemExportPolicy.nativeNames.map { "$rootPath/$it" } }
            "apex" -> listOf("runtime", "art", "conscrypt", "i18n").flatMap { module ->
                listOf("jar", "art", "oat", "odex", "vdex").flatMap { listOf("/apex/com.android.$module/javalib/*.$it", "/apex/com.android.$module@*/javalib/*.$it") } }
            else -> listOf(SystemExportPolicy.CONTAINER_CONFIG)
        }.joinToString("|")
        return "for dc_file do " +
            "dc_real=\$(readlink -f \"\$dc_file\") || { printf 'ISSUE|canonical resolution failed|%s\\n' \"\$dc_file\"; continue; }; " +
            "case \"\$dc_real\" in $canonicalPatterns) ;; *) printf 'ISSUE|canonical path outside firmware policy|%s\\n' \"\$dc_file\"; continue ;; esac; " +
            (if (mode == "apex") "dc_module=\${dc_real#/apex/}; dc_module=\${dc_module%%/*}; " +
                "case \"\$dc_module\" in *@*) dc_version=\${dc_module#*@}; case \"\$dc_version\" in ''|*[!0-9]*) " +
                "printf 'ISSUE|unsupported APEX version|%s\\n' \"\$dc_file\"; continue ;; esac ;; esac; " else "") +
            (if (mode == "init") "grep -Eiq ${ApkInventoryShell.quote(SystemExportPolicy.INIT_PATTERN)} \"\$dc_real\"; dc_grep=\$?; " +
                "if [ \"\$dc_grep\" = 1 ]; then continue; fi; if [ \"\$dc_grep\" != 0 ]; then printf 'ISSUE|init read denied/tool failure|%s\\n' \"\$dc_file\"; continue; fi; " else "") +
            "dc_size=\$(stat -c %s \"\$dc_real\") && " +
            "[ -f \"\$dc_real\" ] && printf 'SYS|%s|%s|%s\\n' \"\$dc_size\" \"\$dc_real\" \"\$dc_file\" || " +
            "printf 'ISSUE|canonical/stat unavailable|%s\\n' \"\$dc_file\"; done"
    }

    fun snapshot(command: String, temporary: String): String = "umask 077; ( ulimit -f 8192 || exit 125; $command ) > " +
        ApkInventoryShell.quote(temporary) + " 2>&1; dc_rc=\$?; printf '__DC_SYSTEM_SCAN__:%s\\n' \"\$dc_rc\""
}

/** Reads original firmware files through existing app/shell rights; no service/vehicle calls. */
internal class SystemExportDevice(private val context: Context) : FullApkExport.Device {
    private val sources = linkedMapOf<String, FullApkExport.Source>()
    private val issues = mutableListOf<String>()
    private val coverage = mutableListOf(
        "SYSTEM targeted firmware collection; not a whole-vehicle dump. No APK or private application-data scan.",
        "Original system files are read only. ZIPs/metadata, small inventory snapshots and normal existing daemon bootstrap/PID files are written.",
        "Live non-atomic collection: equal before/after fingerprints do not prove unchanged individual files or hardware compatibility.",
        "Existing app and ProxyDaemon identities only; no root, permission change, SurfaceDaemon or OEM-service invocation.",
        "Native dependencies are limited to five named JNI/service libraries in both ABIs; DT_NEEDED closure requires subsequent offline analysis and targeted collection.",
        "Boot/classpath companions are selected where exposed. Protected ART/OAT/VDEX or absent code remains unavailable; APK export is a separate action."
    )
    private var shellReady = false
    private var before = emptyMap<String, String>()
    private val inventoryBudget = SystemInventoryBudget()
    private var issueOverflow = false
    private fun issue(value: String) {
        if (issues.size < SystemExportPolicy.MAX_ISSUES) issues.add(value.take(800)) else issueOverflow = true
    }
    private fun add(path: String, canonical: String, size: Long?, origin: String) {
        if (!SystemExportPolicy.accepted(path, canonical)) { issue("Rejected path or symlink escaping firmware allowlist: $path -> $canonical"); return }
        if (size == null || size < 0) { issue("Unknown/invalid size: $path"); return }
        if (sources.size >= SystemExportPolicy.MAX_SOURCES && canonical !in sources) {
            issue("Inventory source cap ${SystemExportPolicy.MAX_SOURCES} reached; remaining entries omitted"); return
        }
        val existing = sources[canonical]
        if (existing == null) sources[canonical] = FullApkExport.Source(path, size, linkedSetOf(origin), canonical)
        else existing.origins.add("$origin; alias=$path")
    }

    override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit): FullApkExport.Inventory {
        update("SYSTEM: connecting existing diagnostic ProxyDaemon", 0)
        shellReady = try { ProxyClient.connect(context) } catch (e: Exception) { issue("ProxyDaemon connection failed: ${e.message}"); false }
        checkCancelled()
        before = captureContext(runtime = true)
        coverage.add("Context captured using ${if (shellReady) "daemon shell" else "app Build fields (fallback)"}; no positive model/ROM identification claimed.")
        val roots = SystemExportPolicy.frameworkRoots.map { it to "framework" } +
            SystemExportPolicy.xmlRoots.map { it to "xml" } + SystemExportPolicy.initRoots.map { it to "init" } +
            SystemExportPolicy.nativeRoots.map { it to "native" } + listOf(SystemExportPolicy.CONTAINER_CONFIG to "exact")
        if (!shellReady) {
            issue("ProxyDaemon unavailable: shell context/classpaths/libraries not captured; app filesystem access only.")
            roots.forEach { (root, mode) -> scanApp(root, mode, checkCancelled, update) }
        } else {
            val tempDir = "/data/local/tmp/dashcast-system-inventory-${UUID.randomUUID()}"
            val ready = runCatching { ProxyClient.runShell("umask 077; mkdir ${ApkInventoryShell.quote(tempDir)} && printf '__DC_SYSTEM_READY__'").trim() == "__DC_SYSTEM_READY__" }.getOrDefault(false)
            if (!ready) {
                issue("Small shell inventory snapshot unavailable; falling back to app reads with incomplete shell coverage.")
                roots.forEach { (root, mode) -> scanApp(root, mode, checkCancelled, update) }
            } else try {
                for ((index, spec) in roots.withIndex()) {
                    checkCancelled()
                    scanShell(spec.first, spec.second, "$tempDir/$index.txt", emptyList(), checkCancelled, update)
                }
                // Runtime environment values are data, never commands. Unrecognized paths are excluded.
                val classpaths = listOf("BOOTCLASSPATH", "SYSTEMSERVERCLASSPATH").flatMap { name ->
                    val (accepted, rejected) = SystemExportPolicy.classpath(before[name].orEmpty())
                    rejected.forEach { issue("$name path outside selected firmware policy: $it") }
                    if (accepted.isEmpty()) issue("$name missing or contains no allowed paths; classpath coverage unknown")
                    accepted
                }.distinct()
                for ((index, entry) in classpaths.withIndex()) {
                    checkCancelled()
                    update("SYSTEM runtime classpath: $entry", sources.size)
                    if (!SystemExportPolicy.isApexClasspath(entry)) {
                        if (!discoveredClasspath(entry)) issue("Runtime framework classpath file unavailable: $entry")
                        continue
                    }
                    val stem = entry.substringAfterLast('/').substringBeforeLast('.')
                    val names = listOf("jar", "art", "oat", "odex", "vdex").flatMap { listOf("$stem.$it", "boot-$stem.$it") }
                    scanShell(entry.substringBefore("/javalib/") + "/javalib", "apex", "$tempDir/apex-$index.txt", names, checkCancelled, update)
                    if (!discoveredClasspath(entry)) issue("Runtime classpath file unavailable: $entry")
                }
            } finally {
                // Only this generated inventory directory is removed; firmware is never written.
                runCatching { ProxyClient.runShell("rm -rf ${ApkInventoryShell.quote(tempDir)}") }
            }
        }
        for (name in SystemExportPolicy.nativeNames + listOf("framework.jar", "services.jar", "ext.jar")) {
            if (sources.values.none { it.path.substringAfterLast('/') == name }) issue("Expected firmware candidate missing/inaccessible in selected roots: $name")
        }
        if (issueOverflow) issues.add("Additional discovery issues omitted after ${SystemExportPolicy.MAX_ISSUES} entries; collection partial.")
        coverage.add("Inventory bounded per root (${SystemExportPolicy.MAX_SNAPSHOT_BYTES} bytes), aggregate (${SystemExportPolicy.MAX_TOTAL_INVENTORY_BYTES} bytes), sources (${SystemExportPolicy.MAX_SOURCES}) and issue count; no payload staging.")
        update("SYSTEM: ${sources.size} selected firmware files; missing/protected locations recorded", sources.size)
        return FullApkExport.Inventory(sources.values.toList(), coverage, before, issues.distinct())
    }

    private fun discoveredClasspath(path: String): Boolean {
        // Resolve the concrete runtime alias, including versioned APEX mounts, before lookup.
        val canonical = runCatching { ProxyClient.runShell("readlink -f ${ApkInventoryShell.quote(path)}").trim() }.getOrNull()
            ?: return false
        return SystemExportPolicy.accepted(path, canonical) && canonical in sources
    }

    private fun scanShell(root: String, mode: String, temporary: String, names: List<String>,
                          checkCancelled: () -> Unit, update: (String, Int) -> Unit) {
        if (inventoryBudget.stopped || inventoryBudget.bytes >= SystemExportPolicy.MAX_TOTAL_INVENTORY_BYTES) { issue("Aggregate inventory limit reached; root not scanned: $root"); return }
        update("SYSTEM inventory: $root", sources.size)
        try {
            val reply = runCatching { ProxyClient.runShell(SystemInventoryCommands.snapshot(SystemInventoryCommands.scan(root, mode, names), temporary)) }
                .getOrElse { "Transport error: ${it.message}" }
            SystemExportPolicy.completionIssue(reply, "__DC_SYSTEM_SCAN__")?.let { issue("$root: $it") }
            checkCancelled()
            val size = runCatching { ProxyClient.runShell("stat -c %s ${ApkInventoryShell.quote(temporary)}").trim().toLongOrNull() }.getOrNull()
            if (!inventoryBudget.accept(size)) {
                issue("$root inventory length unknown or exceeds bounded budget: $size; no further inventory snapshots will be created"); return
            }
            try {
                proxyInput(temporary, size).bufferedReader().use { reader ->
                    var rows = 0
                    while (true) {
                        checkCancelled()
                        val line = reader.readLine() ?: break
                        if (line.length > 8192) { issue("Overlong inventory row omitted: $root"); continue }
                        if (line.startsWith("SYS|")) {
                            val fields = line.split('|', limit = 4)
                            if (fields.size == 4) add(fields[3], fields[2], fields[1].toLongOrNull(), "$mode:$root")
                            else issue("Malformed inventory row: $line")
                        } else if (line.isNotBlank()) issue("$root: $line")
                        if (++rows % 64 == 0) update("SYSTEM inventory: $root ($rows rows)", sources.size)
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { issue("$root inventory read incomplete: ${e.message}") }
            coverage.add("Attempted $mode root $root; temporary snapshot $size bytes; ${reply.take(120)}")
        } finally {
            runCatching { ProxyClient.runShell("rm -f ${ApkInventoryShell.quote(temporary)}") }
        }
    }

    private fun scanApp(root: String, mode: String, checkCancelled: () -> Unit, update: (String, Int) -> Unit) {
        checkCancelled()
        update("SYSTEM app-only inventory: $root", sources.size)
        if (!File(root).exists()) { issue("Missing or inaccessible root (app identity): $root"); return }
        val canonicalRoot = runCatching { File(root).canonicalPath }.getOrNull()
        val allowedRoots = SystemExportPolicy.frameworkRoots + SystemExportPolicy.xmlRoots + SystemExportPolicy.initRoots + SystemExportPolicy.nativeRoots
        val rootsAndAliases = allowedRoots + allowedRoots.filterNot { it.startsWith("/system/") }.map { "/system$it" }
        if (canonicalRoot == null || (mode != "exact" && canonicalRoot !in rootsAndAliases) ||
            (mode == "exact" && !SystemExportPolicy.accepted(root, canonicalRoot))) {
            issue("Canonical root outside selected firmware policy: $root -> $canonicalRoot"); return
        }
        var visited = 0
        try {
            Files.walkFileTree(File(root).toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    return if (mode == "native" && dir.toString() != root) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    if (++visited % 64 == 0) update("SYSTEM app-only inventory: $root", sources.size)
                    val path = file.toString()
                    if (SystemExportPolicy.allowed(path)) {
                        val f = file.toFile()
                        val canonical = f.canonicalPath
                        if (SystemExportPolicy.accepted(path, canonical) && f.isFile) {
                            if (mode != "init" || f.bufferedReader().use { reader ->
                                    // Only matching service fragments are retained; bound inspection.
                                    val (text, truncated) = ExportText.read(reader, 65536)
                                    if (truncated) issue("Init inspection truncated: $path")
                                    text.contains(Regex(SystemExportPolicy.INIT_PATTERN, RegexOption.IGNORE_CASE)) }) {
                                add(path, canonical, f.length(), "app:$mode:$root")
                            }
                        } else issue("Skipped unreadable/non-file/escaping alias: $path -> $canonical")
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    issue("App cannot enumerate $file: ${exc.message}"); return FileVisitResult.CONTINUE
                }
            })
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { issue("App inventory failed $root: ${e.message}") }
    }

    private fun captureContext(runtime: Boolean): Map<String, String> {
        val result = linkedMapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "device" to Build.DEVICE, "product" to Build.PRODUCT, "display" to Build.DISPLAY,
            "appVersion" to runCatching { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("unknown"),
            "appUid" to android.os.Process.myUid().toString(), "daemonUid" to ProxyClient.getCallerUid().toString(),
            "daemonProtocol" to (ProxyClient.getProtocolVersion() ?: "unknown"),
            "vehicleRomFirmwareIdentification" to "Unknown; build identifiers alone are not positive platform/hardware identification")
        if (!shellReady) return result
        val cmd = buildString {
            append("dc_failed=0; ")
            for (key in SystemExportPolicy.propertyKeys) {
                append("dc_value=\$(getprop ${ApkInventoryShell.quote(key)}); dc_rc=\$?; ")
                append("printf 'PROP|%s|%s\\n' ${ApkInventoryShell.quote(key)} \"\$dc_value\"; [ \"\$dc_rc\" = 0 ] || dc_failed=1; ")
            }
            if (runtime) {
                append("printf 'PROP|BOOTCLASSPATH|%s\\nPROP|SYSTEMSERVERCLASSPATH|%s\\n' \"\$BOOTCLASSPATH\" \"\$SYSTEMSERVERCLASSPATH\"; ")
                append("pm list libraries; dc_rc=\$?; [ \"\$dc_rc\" = 0 ] || dc_failed=1; ")
            }
            append("printf '__DC_SYSTEM_CONTEXT__:%s\\n' \"\$dc_failed\"")
        }
        val reply = runCatching { ProxyClient.runShell(cmd) }.getOrElse { "Transport error: ${it.message}" }
        if (reply.length > 65536 || reply.contains("[output truncated]") || reply.lineSequence().lastOrNull() != "__DC_SYSTEM_CONTEXT__:0") {
            result["contextCaptureIssue"] = "${if (runtime) "Before" else "After"} context incomplete/timeout/truncated/unsupported: ${reply.takeLast(400)}"
            if (runtime) issue(result.getValue("contextCaptureIssue"))
        }
        val libraries = mutableListOf<String>()
        reply.take(65536).lineSequence().forEach { line ->
            if (line.startsWith("PROP|")) {
                val fields = line.split('|', limit = 3)
                if (fields.size == 3 && (fields[1] in SystemExportPolicy.propertyKeys || fields[1] in listOf("BOOTCLASSPATH", "SYSTEMSERVERCLASSPATH"))) {
                    result[fields[1]] = fields[2].take(16384)
                }
            } else if (line.startsWith("library:")) libraries.add(line.take(1000))
        }
        if (runtime) {
            result["pmListLibraries"] = libraries.joinToString("\n")
            result["classpathContext"] = "Daemon shell environment; may differ from running system-server. Not runtime availability proof."
            if (libraries.isEmpty()) issue("pm list libraries returned no library rows; availability unverified")
        }
        return result
    }

    override fun finish(cancelled: Boolean): FullApkExport.ContextCheck =
        SystemExportPolicy.finalContext(before, cancelled) { captureContext(runtime = false) }

    override fun open(source: FullApkExport.Source): InputStream {
        val canonical = source.canonicalPath ?: throw IOException("Canonical source path unavailable")
        if (!SystemExportPolicy.accepted(source.path, canonical)) throw IOException("Source outside firmware allowlist")
        // Recheck aliases before each open. Live collection remains explicitly non-atomic.
        val actual = if (shellReady) runCatching { ProxyClient.runShell("readlink -f ${ApkInventoryShell.quote(source.path)}").trim() }.getOrNull()
            else runCatching { File(source.path).canonicalPath }.getOrNull()
        if (actual != canonical || !SystemExportPolicy.allowed(actual)) throw IOException("Source canonical path changed or cannot be validated")
        return try { File(canonical).inputStream() } catch (e: IOException) {
            if (!shellReady) throw e
            proxyInput(canonical, source.size)
        } catch (e: SecurityException) {
            if (!shellReady) throw IOException("Source access denied", e)
            proxyInput(canonical, source.size)
        }
    }

    private fun proxyInput(path: String, expectedSize: Long?): InputStream = object : InputStream() {
        private var offset = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val bytes = try { ProxyClient.readFileChunk(path, offset, minOf(len, FullApkExport.BUFFER_BYTES)) }
                catch (e: Exception) { throw IOException("Existing daemon read failed: ${e.message}", e) }
            if (bytes.isEmpty()) {
                if (expectedSize != null && offset != expectedSize) throw IOException("Short read: expected $expectedSize bytes, received $offset")
                return -1
            }
            if (bytes.size > len) throw IOException("Oversized daemon chunk")
            bytes.copyInto(buffer, off); offset += bytes.size
            return bytes.size
        }
    }
}
