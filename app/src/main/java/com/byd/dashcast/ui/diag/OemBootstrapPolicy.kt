package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.daemon.StrictFileRead
import java.io.IOException
import java.util.Base64

/** Literal passive scope. Properties and proc links are evidence, never new source paths. */
internal object OemBootstrapPolicy {
    const val PROFILE = "OEM_BOOTSTRAP_V1"
    const val CHUNK = 64 * 1024
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_METADATA_BYTES = 1024 * 1024
    const val MAX_REPLY_CHARS = 192 * 1024
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_PIDS = 10
    const val SOURCE_BATCH = 4
    const val RESERVE = 128L * 1024 * 1024
    const val COLLECT_MS = 18L * 60 * 1000
    const val SESSION_MS = 20L * 60 * 1000
    val properties = listOf("ro.hardware", "ro.boot.init_rc", "ro.boot.hardware", "ro.bootmode",
        "ro.build.fingerprint", "ro.build.version.sdk", "ro.product.device", "ro.board.platform",
        "ro.product.board", "ro.boot.slot_suffix", "init.svc.strategyservice")
    val sources = (listOf("/init", "/system/bin/init") +
        listOf("system", "vendor", "odm", "product", "system_ext").map { "/$it/bin/strategyservice" } +
        listOf("/init.rc", "/init.qcom.rc", "/init.fission_host.rc", "/init.fission_cells.rc",
            "/init.environ.rc", "/init.qcom.fission_host.rc", "/init.qcom.fission_cells.rc",
            "/system/etc/init/hw/init.rc", "/system/etc/init/hw/init.qcom.rc",
            "/system/etc/init/hw/init.fission_host.rc", "/system/etc/init/hw/init.fission_cells.rc",
            "/vendor/etc/init/hw/init.qcom.test.rc", "/vendor/etc/init/hw/init.target.rc",
            "/vendor/bin/init.qcom.class_core.sh", "/vendor/bin/init.class_main.sh",
            "/vendor/bin/init.qcom.sh", "/vendor/bin/init.qcom.post_boot.sh",
            "/dev/cells/cell2/init", "/dev/cells/cell2/init.rc",
            "/dev/cells/.cellconf/cell2.conf", "/data/cells/.cellconf/cell2.conf")).also {
        check(it.size == it.distinct().size && it.size <= 40)
    }
    data class Blob(val id: String, val limit: Int, val status: String, val exit: Int?, val bytes: ByteArray) {
        fun node(): Map<String, Any?> = mapOf("id" to id, "limit" to limit, "status" to status,
            "exit_code" to exit, "bytes" to bytes.size, "data_base64" to Base64.getEncoder().encodeToString(bytes),
            "data_scope" to if (status in listOf("access_denied", "not_found", "read_failed")) "diagnostic output; not source bytes" else "bounded raw output; may be truncated")
    }
    data class Pid(val pid: Int, val roles: List<String>)
    data class Selection(val pids: List<Pid>, val issues: List<String>)
    data class ProcessStat(val pid: Int, val comm: String, val starttime: String)
    data class Source(val index: Int, val path: String, val status: String, val stamp: StrictFileRead.Expected?, val metadata: List<Blob>)
    private val pidRegex = Regex("[1-9][0-9]{0,8}")
    fun validPid(pid: Int) = pid > 0 && pidRegex.matches(pid.toString())
    private fun quote(s: String) = ApkInventoryShell.quote(s)
    private const val FOOTER = "\n.DC_BOOT_RC."
    /** A bounded streaming prefix precedes Base64, including for size-zero proc files. The
     * appended exit marker is absent only when the prefix itself reached its explicit cap. */
    internal val helpers = """
LC_ALL=C
export LC_ALL
emit() {
    id=${'$'}1; cap=${'$'}2; shift 2
    printf 'BLOB|%s|%s|' "${'$'}id" "${'$'}cap"
    { "${'$'}@" 2>&1; r=${'$'}?; printf '\n.DC_BOOT_RC.%s\n' "${'$'}r"; } | head -c "${'$'}((cap+128))" | base64 | tr -d '\n'
    printf '\n'
}
""".trimIndent()
    fun command(script: String) = "sh -c " + quote(script)
    fun inventoryScript() = buildString {
        appendLine(helpers); appendLine("printf 'BEGIN|$PROFILE|inventory\\n'")
        appendLine("emit ps 32768 ps -A -o PID,NAME")
        properties.forEachIndexed { n, key -> appendLine("emit prop_$n 1024 getprop ${quote(key)}") }
        // /proc/self belongs to each command utility. It is never the pinned Binder daemon.
        appendLine("emit command_self_root 1024 readlink /proc/self/root")
        appendLine("emit command_self_mnt 1024 readlink /proc/self/ns/mnt")
        appendLine("emit command_self_pid 1024 readlink /proc/self/ns/pid")
        appendLine("printf 'END|$PROFILE|inventory\\n'")
    }
    val inventoryLimits = linkedMapOf("ps" to 32768).apply {
        properties.indices.forEach { put("prop_$it", 1024) }
        listOf("command_self_root", "command_self_mnt", "command_self_pid").forEach { put(it, 1024) }
    }
    val processLimits = linkedMapOf("stat_before" to 4096, "cmdline" to 4096, "status" to 8192,
        "mountinfo" to 16384, "root_link" to 1024, "exe_link" to 1024, "mnt_link" to 1024,
        "pid_link" to 1024, "stat_after" to 4096)
    fun processScript(pid: Int): String {
        require(validPid(pid))
        return buildString {
            appendLine(helpers); appendLine("printf 'BEGIN|$PROFILE|pid_$pid\\n'")
            processLimits.forEach { (key, limit) ->
                val path = when (key) {
                    "stat_before", "stat_after" -> "/proc/$pid/stat"
                    "root_link" -> "/proc/$pid/root"; "exe_link" -> "/proc/$pid/exe"
                    "mnt_link" -> "/proc/$pid/ns/mnt"; "pid_link" -> "/proc/$pid/ns/pid"
                    else -> "/proc/$pid/$key"
                }
                if (key.endsWith("_link")) appendLine("emit $key $limit readlink ${quote(path)}")
                else appendLine("emit $key $limit head -c ${limit + 1} ${quote(path)}")
            }
            appendLine("printf 'END|$PROFILE|pid_$pid\\n'")
        }
    }
    fun sourceIndices(offset: Int): IntRange {
        require(offset in sources.indices && offset % SOURCE_BATCH == 0)
        return offset until minOf(offset + SOURCE_BATCH, sources.size)
    }
    fun sourceScript(offset: Int) = buildString {
        appendLine(helpers); appendLine("printf 'BEGIN|$PROFILE|sources_$offset\\n'")
        sourceIndices(offset).forEach { i ->
            val path = sources[i]
            appendLine("emit stat_$i 1024 stat -c '%f:%s:%Y:%Z:%i:%d' ${quote(path)}")
            appendLine("emit canonical_$i 4096 readlink -f ${quote(path)}")
        }
        appendLine("printf 'END|$PROFILE|sources_$offset\\n'")
    }
    fun sourceLimits(offset: Int) = linkedMapOf<String, Int>().apply {
        sourceIndices(offset).forEach { put("stat_$it", 1024); put("canonical_$it", 4096) }
    }
    fun parse(label: String, output: String, limits: Map<String, Int>, profile: String = PROFILE): List<Blob> {
        if (output.length > MAX_REPLY_CHARS || output.toByteArray(Charsets.UTF_8).size > MAX_REPLY_CHARS)
            throw IOException("Bootstrap metadata response exceeds bound")
        val lines = output.trimEnd('\r', '\n').split('\n')
        if (lines.size != limits.size + 2 || lines.first() != "BEGIN|$profile|$label" || lines.last() != "END|$profile|$label")
            throw IOException("Bootstrap metadata framing missing")
        return limits.entries.mapIndexed { index, (id, cap) ->
            val f = lines[index + 1].split('|')
            if (f.size != 4 || f[0] != "BLOB" || f[1] != id || f[2].toIntOrNull() != cap || f[3].length > ((cap + 130) / 3 + 1) * 4)
                throw IOException("Bootstrap blob framing invalid")
            val raw = try { Base64.getDecoder().decode(f[3]) } catch (_: Exception) { throw IOException("Bootstrap Base64 invalid") }
            if (raw.size > cap + 128 || Base64.getEncoder().encodeToString(raw) != f[3]) throw IOException("Bootstrap blob size/encoding invalid")
            // ISO-8859-1 gives a one-to-one byte mapping; no cmdline NUL or non-UTF8 is lost.
            val text = raw.toString(Charsets.ISO_8859_1)
            val end = Regex("\\n\\.DC_BOOT_RC\\.([0-9]{1,3})\\n$").find(text)
            if (end == null) {
                if (raw.size != cap + 128) throw IOException("Bootstrap exit marker missing")
                Blob(id, cap, "truncated_exit_unobserved", null, raw.copyOf(cap))
            } else {
                val exit = end.groupValues[1].toInt()
                if (exit !in 0..255) throw IOException("Bootstrap exit code invalid")
                val payload = raw.copyOf(end.range.first)
                val status = when {
                    payload.size > cap -> "truncated"
                    exit == 0 -> "ok"
                    "Permission denied" in payload.toString(Charsets.ISO_8859_1) -> "access_denied"
                    "No such file or directory" in payload.toString(Charsets.ISO_8859_1) -> "not_found"
                    else -> "read_failed"
                }
                Blob(id, cap, status, exit, payload.copyOf(minOf(payload.size, cap)))
            }
        }
    }
    fun select(ps: Blob, daemonPid: Int): Selection {
        require(validPid(daemonPid))
        val issues = mutableListOf<String>()
        val selected = linkedMapOf(1 to mutableListOf("namespace_pid1"))
        selected.getOrPut(daemonPid) { mutableListOf() }.add("pinned_daemon")
        if (ps.status != "ok") issues.add("Process list ${ps.status}; only fixed PID1 and negotiated daemon selected")
        else {
            val text = ps.bytes.toString(Charsets.ISO_8859_1)
            val lines = text.trimEnd('\r', '\n').split('\n')
            val seen = linkedMapOf<Int, String>()
            var valid = lines.firstOrNull()?.trim()?.split(Regex("\\s+")) == listOf("PID", "NAME")
            for (line in lines.drop(1)) {
                val fields = line.trim().split(Regex("\\s+"))
                val pid = fields.firstOrNull()?.toIntOrNull()
                if (fields.size != 2 || pid == null || !validPid(pid) || seen.put(pid, fields[1]) != null) { valid = false; break }
            }
            if (!valid) issues.add("Process list shape/duplicate PID invalid; dynamic selection unavailable")
            else for (name in listOf("init", "strategyservice")) {
                val candidates = seen.filterValues { it == name }.keys.sorted()
                if (candidates.size > 4) issues.add("Exact $name process cap reached; ${candidates.size - 4} additional names unobserved")
                for (pid in candidates.take(4)) selected.getOrPut(pid) { mutableListOf() }.add("ps_exact_$name")
            }
        }
        check(selected.size <= MAX_PIDS)
        return Selection(selected.map { Pid(it.key, it.value.distinct()) }, issues)
    }
    fun stat(blob: Blob, pid: Int): ProcessStat? {
        if (blob.status != "ok") return null
        val text = blob.bytes.toString(Charsets.ISO_8859_1).trimEnd('\n')
        if (!text.startsWith("$pid (")) return null
        val end = text.lastIndexOf(") ")
        if (end < "$pid (".length) return null
        val fields = text.substring(end + 2).split(' ')
        if (fields.size < 20 || !fields[0].matches(Regex("[A-Za-z]")) || !fields[19].matches(Regex("[0-9]{1,20}"))) return null
        return ProcessStat(pid, text.substring("$pid (".length, end), fields[19])
    }
    fun processIdentity(pid: Pid, blobs: List<Blob>): Map<String, Any?> {
        val before = stat(blobs.first { it.id == "stat_before" }, pid.pid)
        val after = stat(blobs.first { it.id == "stat_after" }, pid.pid)
        val stable = before != null && before == after
        val nameMatches = stable && pid.roles.all { role -> when (role) {
            "ps_exact_init" -> before!!.comm == "init"; "ps_exact_strategyservice" -> before!!.comm == "strategyservice"; else -> true
        } }
        return mapOf("pid" to pid.pid, "selection_roles" to pid.roles,
            "identity_status" to if (stable && nameMatches) "starttime_bracket_consistent" else "unstable_or_unobserved",
            "starttime_before" to before?.starttime, "starttime_after" to after?.starttime,
            "comm_before" to before?.comm, "comm_after" to after?.comm,
            "scope" to "Per-PID non-atomic observations; consistent starttime is not Binder publisher attribution or host namespace proof",
            "observations" to blobs.map { it.node() })
    }
    fun sourceMetadata(blobs: List<Blob>, offset: Int): List<Source> = sourceIndices(offset).map { i ->
        val path = sources[i]
        val stat = blobs.first { it.id == "stat_$i" }; val canonical = blobs.first { it.id == "canonical_$i" }
        var expected: StrictFileRead.Expected? = null
        val status = when {
            stat.status != "ok" -> stat.status
            else -> {
                expected = try { StrictFileRead.Expected.shell(stat.bytes.toString(Charsets.ISO_8859_1).trimEnd('\n')) }
                catch (_: Exception) { throw IOException("Invalid fixed-source stat identity") }
                when {
                    expected!!.mode and 0xf000L == 0xa000L -> "symlink_refused"
                    !expected!!.matches(expected!!) -> "nonregular_refused"
                    canonical.status != "ok" -> "canonical_${canonical.status}"
                    canonical.bytes.toString(Charsets.ISO_8859_1) != "$path\n" -> "noncanonical_refused"
                    else -> "ok"
                }
            }
        }
        Source(i, path, status, expected, listOf(stat, canonical))
    }
}
