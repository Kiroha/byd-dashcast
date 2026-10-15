package com.byd.dashcast.ui.diag

import android.content.Context
import android.content.pm.PackageManager
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
import java.util.concurrent.CancellationException
import java.util.UUID

/** Paged stdout keeps inventory commands below ProxyShell's 256 KiB reply ceiling.
 * Every page requires both completion markers; timeout, truncation and exit errors are retained.
 * The command is rerun for each page, so this is explicitly not an atomic filesystem snapshot. */
internal class ApkInventoryShell(private val run: (String) -> String) {
    data class Result(val lines: List<String>, val issues: List<String>)
    fun lines(command: String, checkCancelled: () -> Unit, pageProgress: () -> Unit): Result {
        val result = mutableListOf<String>()
        val issues = mutableListOf<String>()
        var offset = 0
        var expectedTotal: Int? = null
        while (true) {
            checkCancelled()
            val end = offset + 64
            val script = "( $command; dc_rc=\$?; printf '\\n__DC_EXIT__:%s\\n' \"\$dc_rc\" ) 2>&1 | " +
                "awk 'BEGIN { n=0 } /^__DC_EXIT__:/ { print; next } " +
                "{ if (length(\$0)>0) { n++; if(n>$offset && n<=$end) print } } " +
                "END { print \"__DC_LINES__:\" n }'"
            val output = try { run(script) } catch (e: Exception) {
                issues.add("Transport failure: ${e.javaClass.simpleName}: ${e.message}"); break
            }
            checkCancelled()
            val rows = output.lineSequence().filter { it.isNotEmpty() }.toList()
            val rc = rows.firstOrNull { it.startsWith("__DC_EXIT__:") }?.substringAfter(':')?.toIntOrNull()
            val count = rows.lastOrNull { it.startsWith("__DC_LINES__:") }?.substringAfter(':')?.toIntOrNull()
            result.addAll(rows.filter { !it.startsWith("__DC_EXIT__:") && !it.startsWith("__DC_LINES__:") && it != "[output truncated]" })
            if (rc == null || count == null || output.contains("[output truncated]")) {
                issues.add("Missing completion marker, timeout or truncated inventory reply"); break
            }
            if (rc != 0) issues.add("Command exited $rc (permission, missing tool or traversal failure possible)")
            if (expectedTotal != null && expectedTotal != count) issues.add("Inventory changed between pages ($expectedTotal -> $count)")
            expectedTotal = count
            pageProgress()
            if (count <= end) break
            offset = end
        }
        return Result(result.distinct(), issues.distinct())
    }
    companion object {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}

internal class FullApkExportDevice(private val context: Context) : FullApkExport.Device {
    override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit): FullApkExport.Inventory {
        val sources = linkedMapOf<String, FullApkExport.Source>()
        val discoveryIssues = mutableListOf<String>()
        val coverage = mutableListOf(
            "Whole-vehicle coverage is UNVERIFIED: app and existing ProxyDaemon shell permissions only; no root or restriction bypass.",
            "Inaccessible, encrypted, offline/unmounted, other mount namespaces and unlisted partitions may contain additional APKs.",
            "Live inventory is not atomic; packages/files may change while paged commands are rerun. Newline-containing filesystem paths are unsupported and may be missed.",
            "APK files only; APEX images, JARs, native libraries, application data and firmware partition images are not extracted."
        )
        fun add(path: String?, size: Long?, origin: String) {
            if (path == null || !path.startsWith('/') || !path.endsWith(".apk", true) || path.contains('\n')) return
            val source = sources.getOrPut(path) { FullApkExport.Source(path, size?.takeIf { it > 0 }) }
            if (source.size == null && size != null && size > 0) source.size = size
            source.origins.add(origin)
        }
        update("PackageManager: base APKs and splits", 0)
        try {
            @Suppress("DEPRECATION")
            val apps = context.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
            for (app in apps) {
                checkCancelled()
                add(app.sourceDir, app.sourceDir?.let { File(it).length() }, "PackageManager:${app.packageName}:base")
                app.splitSourceDirs?.forEach { add(it, File(it).length(), "PackageManager:${app.packageName}:split") }
            }
            coverage.add("PackageManager current-user enumeration completed (${apps.size} applications); visibility/permission limits may apply.")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { discoveryIssues.add("PackageManager enumeration failed: ${e.message}") }

        update("Connecting existing diagnostic ProxyDaemon", sources.size)
        val shellReady = try { ProxyClient.connect(context) } catch (e: Exception) {
            discoveryIssues.add("ProxyDaemon connection failed: ${e.message}"); false
        }
        checkCancelled()
        coverage.add("ProxyDaemon connect=$shellReady; effective uid=${ProxyClient.getCallerUid()}; protocol=${ProxyClient.getProtocolVersion() ?: "unknown"}")
        if (!shellReady) discoveryIssues.add("ProxyDaemon unavailable: shell multiuser and filesystem discovery incomplete; app access only.")
        val shell = ApkInventoryShell { ProxyClient.runShell(it) }
        fun command(label: String, cmd: String): List<String> {
            checkCancelled()
            update(label, sources.size)
            if (!shellReady) { coverage.add("$label: skipped because ProxyDaemon unavailable"); return emptyList() }
            val result = shell.lines(cmd, checkCancelled) { update(label, sources.size) }
            coverage.add("$label: " + if (result.issues.isEmpty()) "command completed (${result.lines.size} lines)" else result.issues.joinToString("; "))
            discoveryIssues.addAll(result.issues.map { "$label: $it" })
            return result.lines
        }
        val users = command("Shell users", "pm list users")
            .mapNotNull { Regex("UserInfo\\{(\\d+):").find(it)?.groupValues?.get(1)?.toIntOrNull() }.toMutableSet()
        if (users.isEmpty()) discoveryIssues.add("No shell users discovered: multiuser coverage incomplete; trying user 0.")
        users.add(0)
        for (user in users.sorted()) {
            checkCancelled()
            val rows = command("User $user packages (including disabled/retained)", "pm list packages -f -u --user $user")
            val packages = linkedSetOf<String>()
            for (row in rows) {
                if (!row.startsWith("package:")) { discoveryIssues.add("User $user package inventory message: ${row.take(500)}"); continue }
                val body = row.removePrefix("package:")
                val pkg = body.substringAfterLast('=')
                if (pkg.matches(Regex("[A-Za-z0-9_.]+"))) packages.add(pkg)
                if (body.contains('=')) add(body.substringBeforeLast('='), null, "pm:user=$user:$pkg:base")
            }
            if (packages.isEmpty()) discoveryIssues.add("User $user has no package rows; package coverage unconfirmed.")
            // Batches bound shell command length and give responsive progress during many packages.
            for (batch in packages.toList().chunked(8)) {
                val script = buildString {
                    append("dc_path_fail=0; ")
                    for (pkg in batch) {
                        append("printf 'PKG:%s\\n' ").append(ApkInventoryShell.quote(pkg)).append("; ")
                        append("pm path --user ").append(user).append(' ').append(ApkInventoryShell.quote(pkg))
                            .append(" || dc_path_fail=1; ")
                    }
                    append("test \"\$dc_path_fail\" = 0")
                }
                var pkg = "unknown"
                for (row in command("User $user APK paths (${batch.first()}…)", script)) {
                    if (row.startsWith("PKG:")) pkg = row.removePrefix("PKG:")
                    else if (row.startsWith("package:")) add(row.removePrefix("package:"), null, "pm:user=$user:$pkg:base-or-split")
                    else discoveryIssues.add("User $user pm path message: ${row.take(500)}")
                }
            }
        }

        val roots = linkedSetOf("/system", "/system_ext", "/product", "/vendor", "/odm", "/oem",
            "/data/app", "/data/app-private", "/data/preloads", "/data/preinstall", "/data/local",
            "/cache", "/preload", "/preloads", "/preinstall", "/mnt/expand", "/storage/emulated/0")
        val mounts = command("Mounted filesystem inventory", "cat /proc/mounts")
        // Scan actual OEM and read-only image mounts too; avoid pseudo-filesystems and broad /data.
        for (row in mounts) {
            val fields = row.split(' ')
            if (fields.size < 3) continue
            val point = fields[1].replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\")
            if (point.startsWith('/') && point != "/" && point != "/data" &&
                fields[2] in setOf("ext4", "erofs", "squashfs", "f2fs", "vfat", "exfat") &&
                !point.startsWith("/mnt/runtime/") &&
                !point.startsWith("/mnt/user/") && !point.startsWith("/mnt/pass_through/")) roots.add(point)
        }
        coverage.add("Filesystem scan roots: ${roots.joinToString(", ")}; /data private app data is not recursively scanned.")
        val tempDir = "/data/local/tmp/dashcast-apk-inventory-${UUID.randomUUID()}"
        val tempReady = shellReady && try {
            ProxyClient.runShell("umask 077; mkdir ${ApkInventoryShell.quote(tempDir)} && printf '__DC_READY__'").trim() == "__DC_READY__"
        } catch (_: Exception) { false }
        if (shellReady && !tempReady) discoveryIssues.add("Cannot create small shell inventory snapshot; falling back to app filesystem access.")
        try { for ((rootIndex, root) in roots.withIndex()) {
            checkCancelled()
            update("Filesystem APK scan $root", sources.size)
            if (!tempReady) {
                // NIO's depth-first walker keeps directory streams, rather than arrays/queues
                // containing every child of a broad shared-storage directory. No symlink follow.
                var visited = 0
                try {
                    Files.walkFileTree(File(root).toPath(), object : SimpleFileVisitor<Path>() {
                        fun tick() {
                            checkCancelled()
                            if (++visited % 64 == 0) update("App filesystem scan $root", sources.size)
                        }
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            tick(); return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            tick()
                            if (attrs.isRegularFile) add(file.toString(), attrs.size(), "app-filesystem:$root")
                            if (attrs.isSymbolicLink) coverage.add("App scan skipped file/directory alias: $file")
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            tick(); discoveryIssues.add("App cannot enumerate $file: ${exc.message}")
                            return FileVisitResult.CONTINUE
                        }
                    })
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { discoveryIssues.add("App filesystem scan $root failed: ${e.message}")
                }
                coverage.add("App filesystem scan attempted $root (no shell snapshot).")
                continue
            }
            val quoted = ApkInventoryShell.quote(root)
            val temp = "$tempDir/$rootIndex.txt"
            val qtemp = ApkInventoryShell.quote(temp)
            val cmd = "if [ -d $quoted ]; then find -H $quoted -type f -iname '*.apk' -exec stat -c 'APK|%s|%n' {} \\;; " +
                "else printf 'ROOT_UNAVAILABLE:%s\\n' $quoted; fi"
            // A small *inventory text* snapshot is allowed; APK payloads are always streamed.
            // Scan each root once. Even a 30s timeout leaves partial inventory to recover below.
            val completion = try { ProxyClient.runShell("umask 077; ( ulimit -f 32768 || exit 125; $cmd ) > $qtemp 2>&1; dc_rc=\$?; printf '__DC_SCAN_EXIT__:%s\\n' \"\$dc_rc\"") }
                catch (e: Exception) { "Transport error: ${e.message}" }
            if (completion.trim() != "__DC_SCAN_EXIT__:0") discoveryIssues.add("Filesystem $root scan incomplete: ${completion.take(500)}")
            coverage.add("Filesystem $root temporary inventory bounded by child-shell file limit (32768 units); timeout/limit failures mark discovery partial.")
            checkCancelled()
            val snapshotBytes = runCatching { ProxyClient.runShell("stat -c %s $qtemp").trim().toLongOrNull() }.getOrNull()
            if (snapshotBytes == null || snapshotBytes < 0) discoveryIssues.add("Filesystem $root snapshot size unknown; read completeness unconfirmed")
            try { proxyInput(temp, snapshotBytes).buffered(FullApkExport.BUFFER_BYTES).bufferedReader().use { reader ->
                var rowCount = 0
                while (true) {
                    checkCancelled()
                    val row = reader.readLine() ?: break
                    if (row.startsWith("APK|")) {
                        val fields = row.split('|', limit = 3)
                        if (fields.size == 3) add(fields[2], fields[1].toLongOrNull(), "filesystem:$root")
                        else discoveryIssues.add("Unparsed filesystem entry: ${row.take(500)}")
                    } else if (row.startsWith("ROOT_UNAVAILABLE:")) coverage.add(row)
                    else if (row.isNotEmpty()) discoveryIssues.add("Filesystem $root message: ${row.take(500)}")
                    rowCount++
                    if (rowCount % 64 == 0) update("Reading filesystem inventory $root", sources.size)
                }
                coverage.add("Filesystem $root snapshot read ($rowCount lines); ${completion.take(120)}")
            } } catch (e: CancellationException) { throw e
            } catch (e: Exception) { discoveryIssues.add("Filesystem $root snapshot unreadable: ${e.message}")
            } finally { runCatching { ProxyClient.runShell("rm -f $qtemp") } }
        } } finally {
            if (tempReady) runCatching { ProxyClient.runShell("rm -rf ${ApkInventoryShell.quote(tempDir)}") }
        }
        update("Discovery complete; ${sources.size} unique source paths", sources.size)
        return FullApkExport.Inventory(sources.values.toList(), coverage, linkedMapOf(
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE,
            "product" to Build.PRODUCT, "display" to Build.DISPLAY, "fingerprint" to Build.FINGERPRINT,
            "androidRelease" to Build.VERSION.RELEASE, "api" to Build.VERSION.SDK_INT.toString(),
            "appVersion" to runCatching { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("unknown"),
            "daemonUid" to ProxyClient.getCallerUid().toString(), "daemonProtocol" to (ProxyClient.getProtocolVersion() ?: "unknown"),
            "vehicleRomFirmwareIdentification" to "Unverified; Android build identifiers recorded above"
        ), discoveryIssues.distinct())
    }

    override fun open(source: FullApkExport.Source): InputStream {
        try {
            val file = File(source.path)
            val input = file.inputStream()
            val size = file.length()
            if (source.size == null && size > 0) source.size = size
            return input
        } catch (_: IOException) { /* Retry with the existing shell identity, without privilege changes. */
        } catch (_: SecurityException) { }
        if (source.size == null) {
            val reply = try { ProxyClient.runShell("stat -c %s " + ApkInventoryShell.quote(source.path)) }
                catch (e: Exception) { throw IOException("Cannot establish source size: ${e.message}", e) }
            source.size = reply.trim().toLongOrNull()?.takeIf { it > 0 }
            if (source.size == null) throw IOException("Cannot establish source size; unreadable or unsupported stat: ${reply.take(240)}")
        }
        // Size zero from daemon can also mean an unreadable file. The engine rejects empty APKs
        // and requires exact size when inventory knows it, so EOF can never silently succeed.
        return proxyInput(source.path, source.size)
    }

    private fun proxyInput(path: String, expectedSize: Long? = null): InputStream = object : InputStream() {
            private var offset = 0L
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val bytes = try { ProxyClient.readFileChunk(path, offset, minOf(len, FullApkExport.BUFFER_BYTES)) }
                catch (e: Exception) { throw IOException("ProxyDaemon read failed: ${e.message}", e) }
                if (bytes.isEmpty()) {
                    if (expectedSize != null && offset != expectedSize) throw IOException("ProxyDaemon short read: expected $expectedSize bytes, received $offset")
                    return -1
                }
                if (bytes.size > len) throw IOException("ProxyDaemon returned an oversized chunk")
                bytes.copyInto(buffer, off)
                offset += bytes.size
                return bytes.size
            }
    }
}
