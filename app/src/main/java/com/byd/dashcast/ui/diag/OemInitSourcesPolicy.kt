package com.byd.dashcast.ui.diag

import com.byd.dashcast.proxy.daemon.StrictFileRead
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Closed direct-child source scope. Paths are data; no init text is interpreted on the car. */
internal object OemInitSourcesPolicy {
    const val PROFILE = "OEM_INIT_SOURCES_V1"
    const val PAGE = 16
    const val MAX_ENTRIES = 2048
    const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val CHUNK = 64 * 1024
    const val RESERVE = 128L * 1024 * 1024
    const val MAX_WIRE_BYTES = 4 * 1024 * 1024
    const val MAX_PATH_BYTES = 256 * 1024
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val COLLECT_MS = 18L * 60 * 1000
    const val SESSION_MS = 20L * 60 * 1000
    val roots = listOf("/") + listOf("system", "vendor", "odm", "product", "system_ext")
        .flatMap { listOf("/$it/etc/init", "/$it/etc/init/hw") }
    data class Entry(val root: Int, val ordinal: Int, val encodedPath: String, val path: String?,
                     val status: String, val stamp: StrictFileRead.Expected?)
    data class Page(val root: Int, val offset: Int, val rootStatus: String, val rootStamp: String,
                    val entries: List<Entry>, val more: Boolean, val wireBytes: Int)
    fun accepted(path: String, root: Int): Boolean {
        if (root !in roots.indices || path.toByteArray(Charsets.UTF_8).size > 4096 || '\u0000' in path) return false
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        return parent == roots[root] && name.isNotEmpty() && name != "." && name != ".." &&
            name.endsWith(".rc") && (root != 0 || name.startsWith("init"))
    }
    private fun quote(value: String) = ApkInventoryShell.quote(value)
    fun command(root: Int, offset: Int): String = "sh -c " + quote(script(root, offset))
    fun script(root: Int, offset: Int): String {
        require(root in roots.indices && offset in 0..MAX_ENTRIES && offset % PAGE == 0)
        val path = roots[root]
        val glob = if (root == 0) "/init*.rc" else "\"\$d\"/*.rc"
        return """
LC_ALL=C
export LC_ALL
d=${quote(path)}
printf 'BEGIN|OEM_INIT_SOURCES_V1|$root|$offset\n'
enc() { printf '%s' "${'$'}1" | base64 | tr -d '\n'; }
root_end() { printf 'ROOT|%s|-\nEND|$root|$offset|0|0\n' "${'$'}1"; exit 0; }
for tool in stat readlink base64 tr; do command -v "${'$'}tool" >/dev/null 2>&1 || root_end utility_unavailable; done
[ ! -L "${'$'}d" ] || root_end symlink_root
[ -d "${'$'}d" ] && [ -r "${'$'}d" ] && [ -x "${'$'}d" ] || root_end root_unavailable
real=${'$'}(readlink -f "${'$'}d" 2>/dev/null; r=${'$'}?; printf '.DC.%s' "${'$'}r")
case "${'$'}real" in *.DC.0) real=${'$'}{real%.DC.0}; real=${'$'}{real%?};; *) root_end canonical_unavailable;; esac
[ "${'$'}real" = "${'$'}d" ] || root_end noncanonical_root
rs=${'$'}(stat -c '%f:%s:%Y:%Z:%i:%d' "${'$'}d" 2>/dev/null) || root_end root_stat_unavailable
printf 'ROOT|ok|%s\n' "${'$'}rs"
n=0
selected=0
more=0
for p in $glob; do
    [ -e "${'$'}p" ] || [ -L "${'$'}p" ] || continue
    n=${'$'}((n+1))
    [ "${'$'}n" -gt $offset ] || continue
    if [ "${'$'}selected" -ge $PAGE ]; then more=1; break; fi
    selected=${'$'}((selected+1))
    s=ok
    stamp=-
    if [ "${'$'}{#p}" -gt 4096 ]; then s=path_byte_limit
    elif [ -L "${'$'}p" ]; then s=symlink_file
    else
        real=${'$'}(readlink -f "${'$'}p" 2>/dev/null; r=${'$'}?; printf '.DC.%s' "${'$'}r")
        case "${'$'}real" in *.DC.0) real=${'$'}{real%.DC.0}; real=${'$'}{real%?};; *) s=canonical_unavailable;; esac
        if [ "${'$'}s" = ok ] && [ "${'$'}real" != "${'$'}p" ]; then s=noncanonical_file; fi
        if [ "${'$'}s" = ok ]; then
            stamp=${'$'}(stat -c '%f:%s:%Y:%Z:%i:%d' "${'$'}p" 2>/dev/null) || s=stat_unavailable
            case "${'$'}stamp" in 8[0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]:*) ;; *) s=nonregular_file; stamp=-;; esac
        fi
    fi
    printf 'ENTRY|%s|' "${'$'}n"; enc "${'$'}p"; printf '|%s|%s\n' "${'$'}s" "${'$'}stamp"
done
printf 'END|$root|$offset|%s|%s\n' "${'$'}selected" "${'$'}more"
""".trimIndent()
    }
    fun parse(root: Int, offset: Int, output: String): Page {
        require(root in roots.indices && offset in 0..MAX_ENTRIES && offset % PAGE == 0)
        val size = output.toByteArray(Charsets.UTF_8).size
        if (size > 192 * 1024) throw IOException("Inventory page exceeds 192 KiB")
        val rows = output.trimEnd('\r', '\n').split('\n')
        if (rows.size !in 3..PAGE + 3 || rows.first() != "BEGIN|$PROFILE|$root|$offset")
            throw IOException("Inventory page framing missing")
        val rootFields = rows[1].split('|')
        if (rootFields.size != 3 || rootFields[0] != "ROOT" ||
            rootFields[1] !in setOf("ok", "utility_unavailable", "symlink_root", "root_unavailable", "canonical_unavailable", "noncanonical_root", "root_stat_unavailable"))
            throw IOException("Inventory root framing invalid")
        if (rootFields[1] == "ok") try {
            val stamp = StrictFileRead.Expected.shell(rootFields[2])
            if (stamp.mode and 0xf000L != 0x4000L || stamp.size < 0) throw IOException("Root is not an ordinary directory")
        } catch (_: Exception) { throw IOException("Invalid root identity metadata") }
        val entries = rows.drop(2).dropLast(1).mapIndexed { index, row ->
            val f = row.split('|')
            if (f.size != 5 || f[0] != "ENTRY" || f[1].toIntOrNull() != offset + index + 1 || f[2].length > 5464)
                throw IOException("Inventory entry framing invalid")
            val bytes = try { Base64.getDecoder().decode(f[2]) } catch (_: Exception) { throw IOException("Invalid encoded source path") }
            if (bytes.isEmpty() || bytes.size > 4096 || Base64.getEncoder().encodeToString(bytes) != f[2])
                throw IOException("Source path framing invalid")
            val path = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { null }
            if (path != null && !accepted(path, root)) throw IOException("Source outside direct-child init scope")
            if (f[3] !in setOf("ok", "path_byte_limit", "symlink_file", "canonical_unavailable", "noncanonical_file", "stat_unavailable", "nonregular_file"))
                throw IOException("Unknown inventory entry state")
            val stamp = if (f[3] == "ok") try { StrictFileRead.Expected.shell(f[4]).also {
                if (!it.matches(it)) throw IOException("Nonregular source metadata")
            } } catch (_: Exception) { throw IOException("Invalid strict source metadata") } else null
            Entry(root, offset + index + 1, f[2], path, if (path == null) "invalid_utf8_path" else f[3], stamp)
        }
        val end = rows.last().split('|')
        if (end.size != 5 || end.take(3) != listOf("END", root.toString(), offset.toString()) ||
            end[3].toIntOrNull() != entries.size || end[4] !in listOf("0", "1") ||
            (end[4] == "1" && entries.size != PAGE) || (rootFields[1] != "ok" && entries.isNotEmpty()))
            throw IOException("Inventory completion marker invalid")
        return Page(root, offset, rootFields[1], rootFields[2], entries, end[4] == "1", size)
    }
}
