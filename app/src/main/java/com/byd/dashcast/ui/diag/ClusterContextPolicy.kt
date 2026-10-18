package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.daemon.StrictFileRead
import java.io.IOException

/** Closed cluster evidence scope. Observed text is never a command or another source path. */
internal object ClusterContextPolicy {
    const val PROFILE = "CLUSTER_CONTEXT_V1"
    const val PROVIDER = "/system/lib64/libwindowsurface_android.so"
    const val MAP_BYTES = 64 * 1024
    const val MAX_PIDS = 6
    const val COLLECT_MS = 3 * 60_000L
    const val SESSION_MS = 4 * 60_000L
    val properties = listOf("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.model",
        "ro.product.device", "ro.product.board", "apps.setting.product.inswver",
        "ro.build.system.fission_single_os")
    val paths = listOf(PROVIDER, "/system/lib64/libutils.so", "/system/lib64/libc.so",
        "/apex/com.android.runtime/lib64/bionic/libc.so", "/system/bin/qtandroidnative")
    val names = listOf("qtandroidnative", "com.xdja.containerservice", "surfaceflinger")
    val propertyLimits = linkedMapOf<String, Int>().apply {
        properties.indices.forEach { put("prop_$it", 1024) }
    }
    val inventoryLimits = linkedMapOf("ps" to 32768).apply { putAll(propertyLimits) }
    val sourceLimits = linkedMapOf<String, Int>().apply {
        paths.indices.forEach { i -> put("stat_$i", 1024); put("canonical_$i", 4096); put("sha_$i", 1024) }
    }
    val processLimits = linkedMapOf("stat_before" to 4096, "cmdline_argv0" to 1024,
        "status" to 8192, "maps" to MAP_BYTES, "exe_link" to 1024,
        "root_link" to 1024, "mnt_link" to 1024, "pid_link" to 1024, "stat_after" to 4096)
    data class Pid(val pid: Int, val name: String)
    data class Selection(val pids: List<Pid>, val issues: List<String>)
    private fun quote(value: String) = ApkInventoryShell.quote(value)

    fun script(label: String, pid: Int? = null): Pair<String, Map<String, Int>> {
        val limits = when (label) {
            "inventory_before" -> inventoryLimits
            "properties_after" -> propertyLimits
            "sources_before", "sources_after" -> sourceLimits
            else -> {
                require(pid != null && OemBootstrapPolicy.validPid(pid) && label == "pid_$pid")
                processLimits
            }
        }
        val script = buildString {
            appendLine(OemBootstrapPolicy.helpers)
            appendLine("printf 'BEGIN|$PROFILE|$label\\n'")
            when (label) {
                "inventory_before", "properties_after" -> {
                    if (label == "inventory_before") appendLine("emit ps 32768 ps -A -o PID,NAME")
                    properties.forEachIndexed { i, key -> appendLine("emit prop_$i 1024 getprop ${quote(key)}") }
                }
                "sources_before", "sources_after" -> paths.forEachIndexed { i, path ->
                    appendLine("emit stat_$i 1024 stat -c '%f:%s:%Y:%Z:%i:%d' ${quote(path)}")
                    appendLine("emit canonical_$i 4096 readlink -f ${quote(path)}")
                    appendLine("emit sha_$i 1024 sha256sum ${quote(path)}")
                }
                else -> {
                    // read -d '' stops at the first NUL. A bounded prefix is fed to it;
                    // unsupported shell behavior or a missing terminator is a read failure.
                    // No argument after argv[0] is emitted, even when cmdline contains secrets.
                    appendLine("""
argv0() {
    head -c 1025 "${'$'}1" | {
        IFS= read -r -d '' first
        rc=${'$'}?
        [ "${'$'}rc" -eq 0 ] || return "${'$'}rc"
        printf '%s\000' "${'$'}first"
    }
}
""".trimIndent())
                    processLimits.forEach { (key, cap) ->
                        val path = when (key) {
                            "stat_before", "stat_after" -> "/proc/$pid/stat"
                            "cmdline_argv0" -> "/proc/$pid/cmdline"
                            "exe_link" -> "/proc/$pid/exe"
                            "root_link" -> "/proc/$pid/root"
                            "mnt_link" -> "/proc/$pid/ns/mnt"
                            "pid_link" -> "/proc/$pid/ns/pid"
                            else -> "/proc/$pid/$key"
                        }
                        when {
                            key == "cmdline_argv0" -> appendLine("emit $key $cap argv0 ${quote(path)}")
                            key.endsWith("_link") -> appendLine("emit $key $cap readlink ${quote(path)}")
                            else -> appendLine("emit $key $cap head -c ${cap + 1} ${quote(path)}")
                        }
                    }
                }
            }
            appendLine("printf 'END|$PROFILE|$label\\n'")
        }
        return script to limits
    }

    fun select(blob: OemBootstrapPolicy.Blob): Selection {
        if (blob.status != "ok") return Selection(emptyList(), listOf("Process list ${blob.status}; no dynamic PIDs selected"))
        val rows = blob.bytes.toString(Charsets.ISO_8859_1).trimEnd('\r', '\n').lines()
        if (rows.firstOrNull()?.trim()?.split(Regex("\\s+")) != listOf("PID", "NAME"))
            return Selection(emptyList(), listOf("Process list header unrecognized; no dynamic PIDs selected"))
        val seen = linkedMapOf<Int, String>()
        for (row in rows.drop(1)) {
            val fields = row.trim().split(Regex("\\s+"))
            val pid = fields.firstOrNull()?.toIntOrNull()
            if (fields.size != 2 || pid == null || !OemBootstrapPolicy.validPid(pid) || seen.put(pid, fields[1]) != null)
                return Selection(emptyList(), listOf("Process list malformed/duplicate PID; no dynamic PIDs selected"))
        }
        val selected = mutableListOf<Pid>()
        val issues = mutableListOf<String>()
        for (name in names) {
            val matches = seen.filterValues { it == name }.keys.sorted()
            matches.take(2).forEach { selected += Pid(it, name) }
            if (matches.isEmpty()) issues += "No exact $name name observed in bounded process list; no global absence inferred"
            if (matches.size > 2) issues += "Exact $name name cap reached: ${matches.size - 2} additional PIDs omitted"
        }
        check(selected.size <= MAX_PIDS)
        return Selection(selected, issues)
    }

    fun identity(pid: Pid, blobs: List<OemBootstrapPolicy.Blob>): Map<String, Any?> {
        val before = OemBootstrapPolicy.stat(blobs.first { it.id == "stat_before" }, pid.pid)
        val after = OemBootstrapPolicy.stat(blobs.first { it.id == "stat_after" }, pid.pid)
        val arg = blobs.first { it.id == "cmdline_argv0" }
        val bytes = arg.bytes
        val first = if (arg.status == "ok" && bytes.isNotEmpty() && bytes.last() == 0.toByte() &&
            bytes.dropLast(1).none { it == 0.toByte() }) bytes.dropLast(1).toByteArray().toString(Charsets.ISO_8859_1) else null
        val accepted = when (pid.name) {
            "qtandroidnative", "surfaceflinger" -> setOf(pid.name, "/system/bin/${pid.name}")
            "com.xdja.containerservice" -> setOf(pid.name)
            else -> emptySet()
        }
        val stable = before != null && before == after
        val matches = first != null && first in accepted
        return mapOf("pid" to pid.pid, "selected_exact_name" to pid.name,
            "identity_status" to if (stable && matches) "starttime_bracket_consistent_name_verified" else "unstable_or_unobserved",
            "starttime_before" to before?.starttime, "starttime_after" to after?.starttime,
            "comm_before" to before?.comm, "comm_after" to after?.comm, "argv0_matches" to matches,
            "scope" to "Per-PID non-atomic observations. stat comm can truncate to 15 bytes; full argv0 independently verifies the selected name. No loaded-provider identity, Binder publisher, namespace or pixel-delivery proof.",
            "maps_offset" to 0, "maps_byte_limit" to MAP_BYTES,
            "observations" to blobs.map { it.node() })
    }

    /** Only this exact canonical regular file is ever eligible for a strict byte read. */
    fun provider(blobs: List<OemBootstrapPolicy.Blob>): OemBootstrapPolicy.Source {
        val stat = blobs.first { it.id == "stat_0" }
        val canonical = blobs.first { it.id == "canonical_0" }
        var expected: StrictFileRead.Expected? = null
        val status = if (stat.status != "ok") stat.status else {
            expected = try { StrictFileRead.Expected.shell(stat.bytes.toString(Charsets.ISO_8859_1).trimEnd('\n')) }
            catch (_: Exception) { throw IOException("Invalid provider stat identity") }
            when {
                expected.mode and 0xf000L == 0xa000L -> "symlink_refused"
                !expected.matches(expected) -> "nonregular_refused"
                canonical.status != "ok" -> "canonical_${canonical.status}"
                canonical.bytes.toString(Charsets.ISO_8859_1) != "$PROVIDER\n" -> "noncanonical_refused"
                else -> "ok"
            }
        }
        return OemBootstrapPolicy.Source(0, PROVIDER, status, expected, listOf(stat, canonical))
    }

    fun sha(blobs: List<OemBootstrapPolicy.Blob>, index: Int): String? {
        require(index in paths.indices)
        val blob = blobs.first { it.id == "sha_$index" }
        if (blob.status != "ok") return null
        val match = Regex("([0-9a-fA-F]{64}) [ *]" + Regex.escape(paths[index]) + "\\n?")
            .matchEntire(blob.bytes.toString(Charsets.ISO_8859_1)) ?: return null
        return match.groupValues[1].lowercase()
    }
}
