package com.byd.dashcast.ui.diag

import android.content.Context
import android.os.Build
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.ProxyClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the "BYD APK Extraction" bundle: an inventory, the runtime context a reader needs, and
 * the OEM cluster APKs themselves — for interoperability analysis of how the cluster is fed.
 *
 * Interop target: the `AutoContainer` activation call returns -1 on every trinket/DL5.1 capture
 * while returning 0/1 on the models where projection works. The code behind that call is in the
 * OEM's `com.xdja.containerservice`; this bundle carries it plus the evidence to correlate the
 * dex with the observed -1.
 *
 * Selection is delegated to [ApkExtractionPolicy]: the OEM cluster stack lives entirely on the
 * read-only firmware partitions, so the target set is scoped there, named targets before a narrow
 * pattern sweep, and a byte budget under Telegram's 50 MB document ceiling. Every rejection is
 * written to the manifest so a reader can tell "excluded" from "absent on this ROM".
 *
 * Two functions only because the copy is the slow part: [plan] does the read-only gather (inventory,
 * context) and decides the APK set without copying; [materialize] then copies the selected APKs and
 * zips. The caller runs them back to back.
 */
object BydApkExtractionBundle {

    /** One planned APK copy: decided in [plan], executed in [materialize]. */
    data class PlannedApk(val pkg: String, val apkPath: String, val sizeBytes: Long, val tier: ApkExtractionPolicy.Tier)

    /** One planned native-binary pull (through the daemon): decided in [plan], executed in [materialize]. */
    data class PlannedNative(val name: String, val path: String, val sizeBytes: Long, val tier: ApkExtractionPolicy.Tier)

    /**
     * The read-only result of [plan]. [workDir] already holds the text artefacts (inventory +
     * context); [accepted] / [acceptedNative] are what [materialize] will copy; [manifestSkips]
     * records every rejection with its reason. [payloadBytes] is the total (APKs + native).
     */
    data class Plan(
        val workDir: File,
        val accepted: List<PlannedApk>,
        val acceptedNative: List<PlannedNative>,
        val manifestSkips: List<String>,
        val payloadBytes: Long
    )

    private fun sh(cmd: String): String =
        try { ProxyClient.runShell(cmd) ?: "" }
        catch (t: Throwable) { "ERR running [$cmd]: ${t.javaClass.simpleName}: ${t.message}" }

    private fun write(dir: File, name: String, content: String) {
        try { File(dir, name).writeText(content) } catch (_: Throwable) {}
    }

    fun header(ctx: Context): String =
        "DashCast ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
        "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}"

    // ── Phase 1: read-only planning ─────────────────────────────────────────────

    /**
     * Collects the text artefacts and decides the APK set. Copies NO binary. Safe to run and
     * discard. Each step is guarded: a failure writes a line into the bundle instead of throwing,
     * so the button never crashes the app when the daemon, shell or a dump is unavailable.
     */
    fun plan(ctx: Context, progress: (String) -> Unit): Plan {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val work = File(ctx.cacheDir, "byd_apk_$stamp").apply { mkdirs() }
        var step = 0
        val totalSteps = 6
        fun s(label: String, block: () -> Unit) {
            step++
            progress("[$step/$totalSteps] $label…")
            try { block(); progress("    ✓ $label") }
            catch (t: Throwable) { progress("    ✗ $label: ${t.javaClass.simpleName}: ${t.message}") }
        }

        s("connect daemon") { try { ProxyClient.connect(ctx) } catch (_: Throwable) {} }

        write(work, "00_header.txt", header(ctx) + "\n")

        val pmOut = sh("pm list packages -f 2>/dev/null")
        s("inventory: every package, path, version, signer") {
            write(work, "01_inventory.txt", buildInventory(pmOut))
        }

        s("context: displays / SurfaceFlinger / AutoContainer service") {
            val sb = StringBuilder()
            sb.append("=== dumpsys display ===\n")
              .append(sh("dumpsys display 2>/dev/null | head -c 250000")).append("\n\n")
            sb.append("=== SurfaceFlinger (cluster / virtual displays) ===\n")
              .append(sh("dumpsys SurfaceFlinger 2>/dev/null | " +
                  "grep -iE 'display|layerStack|fission|cluster|virtual|xdja' | head -c 120000")).append("\n\n")
            sb.append("=== service list (AutoContainer registration) ===\n")
              .append(sh("service list 2>/dev/null | head -c 60000")).append("\n\n")
            sb.append("=== AutoContainer service dump (if any) ===\n")
              .append(sh("dumpsys AutoContainer 2>/dev/null | head -c 40000")).append("\n")
            write(work, "02_context.txt", sb.toString())
        }

        s("native backend: fission/container/cluster binaries + init services") {
            val sb = StringBuilder()
            sb.append("=== /system/bin, /vendor/bin, /system_ext/bin matches ===\n")
              .append(sh("ls -l /system/bin /vendor/bin /system_ext/bin 2>/dev/null | " +
                  "grep -iE 'fission|container|cluster|xdja|instrument|autocontainer' | head -c 20000")).append("\n\n")
            // The projection backend is partly native (AutoContainerNative): dex alone may not be
            // enough, so record which native services exist and their state.
            sb.append("=== init services (fission/container/cluster/xdja) ===\n")
              .append(sh("getprop 2>/dev/null | grep -iE 'init.svc.*(fission|container|cluster|xdja)' | head -c 20000")).append("\n\n")
            sb.append("=== running processes ===\n")
              .append(sh("ps -A 2>/dev/null | grep -iE 'fission|container|cluster|xdja|automap' | head -c 20000")).append("\n")
            write(work, "03_native_backend.txt", sb.toString())
        }

        val accepted = ArrayList<PlannedApk>()
        val skips = ArrayList<String>()
        s("select OEM cluster APKs") {
            planApks(pmOut, accepted, skips)
        }

        val apkBytes = accepted.sumOf { it.sizeBytes }
        val acceptedNative = ArrayList<PlannedNative>()
        s("select native cluster/projection binaries") {
            planNative(apkBytes, acceptedNative, skips)
        }

        val payload = apkBytes + acceptedNative.sumOf { it.sizeBytes }
        return Plan(work, accepted, acceptedNative, skips, payload)
    }

    /**
     * Enumerates the native cluster/projection executables and decides which to pull, drawing from
     * the budget already spent by the APKs so the whole bundle stays under Telegram's ceiling.
     *
     * Enumeration is done by the daemon shell (uid 2000), which can `stat` firmware binaries the app
     * process cannot. A binary the shell cannot stat (SELinux — it shows as `-????` in a listing)
     * yields no size line and is recorded as unreadable; nothing is copied here.
     */
    private fun planNative(apkBytes: Long, accepted: MutableList<PlannedNative>, skips: MutableList<String>) {
        val dirs = ApkExtractionPolicy.NATIVE_BIN_DIRS.joinToString(" ")
        val pat = "fission|cluster|container|xdja|autocontainer|instrument|meter"
        // Emit "path|size" per matching, stat-able file. `stat` failing (unreadable) drops the line.
        val listing = sh(
            "for d in $dirs; do " +
            "for f in \$(ls \"\$d\" 2>/dev/null | grep -iE '$pat'); do " +
            "p=\"\$d/\$f\"; sz=\$(stat -c %s \"\$p\" 2>/dev/null) && echo \"\$p|\$sz\"; " +
            "done; done"
        )

        data class Cand(val name: String, val path: String, val size: Long, val tier: ApkExtractionPolicy.Tier)
        val cands = ArrayList<Cand>()
        val seen = HashSet<String>()
        for (raw in listing.lineSequence()) {
            val line = raw.trim()
            val bar = line.lastIndexOf('|')
            if (bar <= 0) continue
            val path = line.substring(0, bar)
            val size = line.substring(bar + 1).toLongOrNull() ?: continue
            val name = path.substringAfterLast('/')
            if (!seen.add(name)) continue // same binary symlinked/duplicated across dirs
            val tier = ApkExtractionPolicy.classifyNative(name)
            if (tier == ApkExtractionPolicy.Tier.EXCLUDED) continue
            cands.add(Cand(name, path, size, tier))
        }
        cands.sortBy { ApkExtractionPolicy.order(it.tier) }

        var bundleBytes = apkBytes
        for (c in cands) {
            if (c.size <= 0) { skips.add("SKIP native ${c.name}: unreadable/zero"); continue }
            when (ApkExtractionPolicy.admitNative(c.size, bundleBytes, accepted.size)) {
                ApkExtractionPolicy.Skip.NONE -> {
                    accepted.add(PlannedNative(c.name, c.path, c.size, c.tier))
                    bundleBytes += c.size
                }
                ApkExtractionPolicy.Skip.TOO_BIG ->
                    skips.add("SKIP native ${c.name}: too big (${c.size / 1024 / 1024} MB > per-file cap)")
                ApkExtractionPolicy.Skip.OVER_BUDGET ->
                    skips.add("SKIP native ${c.name}: over bundle budget (${c.size / 1024} KB)")
                ApkExtractionPolicy.Skip.MAX_COUNT ->
                    skips.add("SKIP native ${c.name}: max native count reached")
            }
        }
    }

    private fun buildInventory(pmOut: String): String {
        val sb = StringBuilder("BYD extraction inventory — every installed package\n")
        sb.append("pkg | apkPath | sizeKB | versionCode | versionName | signer\n")
        for (raw in pmOut.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val apk = body.substring(0, eq)
            val pkg = body.substring(eq + 1)
            val sizeKb = try { File(apk).length() / 1024 } catch (_: Throwable) { -1L }
            val vc = firstMatch(sh("dumpsys package $pkg 2>/dev/null | grep -m1 versionCode"), "versionCode=([0-9]+)")
            val vn = firstMatch(sh("dumpsys package $pkg 2>/dev/null | grep -m1 versionName"), "versionName=(\\S+)")
            sb.append("$pkg | $apk | $sizeKb | ${vc ?: "?"} | ${vn ?: "?"} | ${signerOf(pkg)}\n")
        }
        return sb.toString()
    }

    private fun signerOf(pkg: String): String {
        val out = sh("dumpsys package $pkg 2>/dev/null | grep -m1 -iE 'signatures|signerId'")
        return firstMatch(out, "\\[([0-9a-fA-F]{6,})") ?: "?"
    }

    private fun firstMatch(text: String, regex: String): String? =
        Regex(regex).find(text)?.groupValues?.getOrNull(1)

    private fun planApks(pmOut: String, accepted: MutableList<PlannedApk>, skips: MutableList<String>) {
        data class Cand(val pkg: String, val apk: String, val tier: ApkExtractionPolicy.Tier)
        val cands = ArrayList<Cand>()
        for (raw in pmOut.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val apk = body.substring(0, eq)
            val pkg = body.substring(eq + 1)
            val tier = ApkExtractionPolicy.classify(pkg, apk)
            if (tier == ApkExtractionPolicy.Tier.EXCLUDED) continue
            cands.add(Cand(pkg, apk, tier))
        }
        cands.sortBy { ApkExtractionPolicy.order(it.tier) }

        var acceptedBytes = 0L
        for (c in cands) {
            val size = try { File(c.apk).length() } catch (_: Throwable) { -1L }
            if (size <= 0) { skips.add("SKIP ${c.pkg}: unreadable/zero (${c.apk})"); continue }
            when (ApkExtractionPolicy.admit(size, acceptedBytes, accepted.size)) {
                ApkExtractionPolicy.Skip.NONE -> {
                    accepted.add(PlannedApk(c.pkg, c.apk, size, c.tier))
                    acceptedBytes += size
                }
                ApkExtractionPolicy.Skip.TOO_BIG ->
                    skips.add("SKIP ${c.pkg}: too big (${size / 1024 / 1024} MB > per-file cap)")
                ApkExtractionPolicy.Skip.OVER_BUDGET ->
                    skips.add("SKIP ${c.pkg}: over ${ApkExtractionPolicy.BUDGET_TOTAL / 1024 / 1024} MB total budget (${size / 1024} KB)")
                ApkExtractionPolicy.Skip.MAX_COUNT ->
                    skips.add("SKIP ${c.pkg}: max count reached")
            }
        }
    }

    // ── Phase 2: copy + zip (only after the tester confirms) ────────────────────

    /**
     * Copies the [Plan.accepted] APKs into the working dir, writes the manifest, and zips.
     * A copy that fails or an APK that changed size mid-copy is recorded, not fatal.
     */
    fun materialize(plan: Plan, progress: (String) -> Unit): File {
        val apkDir = File(plan.workDir, "apks").apply { mkdirs() }
        val manifest = StringBuilder("BYD APK pull manifest (budget ")
            .append(ApkExtractionPolicy.BUDGET_TOTAL / 1024 / 1024).append(" MB)\n")
        var copied = 0
        var bytes = 0L
        for (a in plan.accepted) {
            val src = File(a.apkPath)
            try {
                if (!src.canRead()) { manifest.append("SKIP ${a.pkg}: became unreadable\n"); continue }
                val dst = File(apkDir, a.pkg + ".apk")
                src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
                copied++
                bytes += dst.length()
                val where = ApkExtractionPolicy.partitionLabel(a.apkPath)
                progress("    + ${a.pkg} (${dst.length() / 1024} KB, $where)")
                manifest.append("OK   ${a.pkg} [${a.tier}/$where] (${dst.length() / 1024} KB) ${a.apkPath}\n")
            } catch (t: Throwable) {
                manifest.append("FAIL ${a.pkg}: ${t.javaClass.simpleName}: ${t.message}\n")
            }
        }
        // Native binaries — pulled through the daemon into native/, since the app process cannot
        // read system_file. A pull that yields no bytes (SELinux denied the daemon too) is recorded.
        var nativeCopied = 0
        if (plan.acceptedNative.isNotEmpty()) {
            val nativeDir = File(plan.workDir, "native").apply { mkdirs() }
            for (n in plan.acceptedNative) {
                try {
                    val got = pullViaDaemon(n.path, File(nativeDir, n.name), n.sizeBytes)
                    if (got > 0) {
                        nativeCopied++
                        bytes += got
                        progress("    + native ${n.name} (${got / 1024} KB)")
                        manifest.append("OK   native ${n.name} [${n.tier}] (${got / 1024} KB) ${n.path}\n")
                    } else {
                        manifest.append("FAIL native ${n.name}: daemon read returned no bytes (SELinux?) ${n.path}\n")
                    }
                } catch (t: Throwable) {
                    manifest.append("FAIL native ${n.name}: ${t.javaClass.simpleName}: ${t.message}\n")
                }
            }
        }

        manifest.append("\n")
        for (line in plan.manifestSkips) manifest.append(line).append("\n")
        manifest.append("\nCopied: $copied APK(s) + $nativeCopied native, ${bytes / 1024 / 1024} MB\n")
        write(plan.workDir, "04_apk_manifest.txt", manifest.toString())

        progress("zipping…")
        return zipDir(plan.workDir)
    }

    /** Chunk size per daemon read — well under the ~1 MB binder transaction limit. */
    private const val PULL_CHUNK = 512 * 1024

    /**
     * Streams [path] out of the device through the uid-2000 daemon into [dst], chunk by chunk.
     * Returns the number of bytes written (0 if the daemon could read nothing). [expected] bounds
     * the loop so a misbehaving reader cannot spin forever.
     */
    private fun pullViaDaemon(path: String, dst: File, expected: Long): Long {
        var offset = 0L
        FileOutputStream(dst).use { out ->
            while (offset < expected + PULL_CHUNK) {
                val chunk = ProxyClient.readFileChunk(path, offset, PULL_CHUNK)
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
                if (chunk.size < PULL_CHUNK) break // short read = EOF
            }
        }
        val written = dst.length()
        if (written <= 0) { try { dst.delete() } catch (_: Throwable) {} }
        return written
    }

    private fun zipDir(work: File): File {
        val zip = File(work.parentFile, work.name + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            work.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(work).path))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

    /** Best-effort cleanup of a working dir + its zip, to bound cache growth across runs. */
    fun cleanup(work: File?) {
        if (work == null) return
        try { File(work.parentFile, work.name + ".zip").delete() } catch (_: Throwable) {}
        try { work.deleteRecursively() } catch (_: Throwable) {}
    }
}
