package com.byd.dashcast.proxy.daemon

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.byd.dashcast.proxy.AdasLogProbePolicy as Policy
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/** Fixed existing files only. No shell, logging activation, subscriptions or device reads. */
internal object AdasLogReader {
    data class Stamp(val device: Long, val inode: Long, val mode: Int, val size: Long, val modified: Long) {
        val regular get() = mode and 0xf000 == 0x8000
        val directory get() = mode and 0xf000 == 0x4000
        fun sameFile(other: Stamp) = device == other.device && inode == other.inode && mode == other.mode
    }
    interface Access<F> {
        fun stat(path: String): Stamp
        fun children(path: String, visit: (String) -> Boolean)
        fun open(path: String): F
        fun statFd(fd: F): Stamp
        fun read(fd: F, bytes: ByteArray, offset: Int, count: Int, position: Long): Int
        fun close(fd: F)
    }
    private val system = object : Access<FileDescriptor> {
        private fun stamp(s: android.system.StructStat) = Stamp(s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime)
        override fun stat(path: String) = stamp(Os.lstat(path))
        override fun children(path: String, visit: (String) -> Boolean) {
            Files.newDirectoryStream(Paths.get(path)).use { entries ->
                for (entry in entries) if (!visit(entry.fileName.toString())) break
            }
        }
        override fun open(path: String): FileDescriptor = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        override fun statFd(fd: FileDescriptor) = stamp(Os.fstat(fd))
        override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int, position: Long) = Os.pread(fd, bytes, offset, count, position)
        override fun close(fd: FileDescriptor) = Os.close(fd)
    }
    private class ProbeFailure(val status: String) : IOException(status)
    fun collect(): String = collect(system)

    internal fun <F> collect(access: Access<F>, clock: () -> Long = { System.nanoTime() / 1_000_000 }): String {
        val started = clock()
        val out = StringBuilder("schema=1; historical existing OEM log probe; sensor freshness UNKNOWN\n")
        var matches = 0
        var bytesRead = 0
        fun budget() { if (clock() - started >= Policy.IO_BUDGET_MS) throw ProbeFailure("TIME_BUDGET") }
        fun append(value: String) { if (out.length + value.length + 512 > Policy.MAX_RESPONSE) throw ProbeFailure("RESULT_LIMIT"); out.append(value).append('\n') }
        fun ancestors(path: String): List<Pair<String, Stamp>> {
            val parts = path.split('/').filter { it.isNotEmpty() }
            return parts.indices.map { index ->
                budget()
                val parent = "/" + parts.take(index + 1).joinToString("/")
                val stamp = access.stat(parent)
                if (!stamp.directory) throw ProbeFailure("REJECTED_PARENT_TYPE_OR_SYMLINK")
                parent to stamp
            }
        }
        fun unchanged(parents: List<Pair<String, Stamp>>) {
            parents.forEach { (path, stamp) ->
                budget()
                if (!stamp.sameFile(access.stat(path))) throw ProbeFailure("PARENT_CHANGED")
            }
        }
        for ((rootIndex, root) in Policy.roots.withIndex()) {
            try {
                budget()
                val parents = ancestors(root)
                val rootStamp = parents.last().second
                val identity = "device=${rootStamp.device} inode=${rootStamp.inode} mode=${rootStamp.mode.toString(16)}"
                if (rootIndex != 0) {
                    append("root=$root status=DIRECTORY_METADATA_ONLY $identity; filename contract unknown; no contents read")
                    continue
                }
                val candidates = mutableListOf<Pair<String, Stamp>>()
                var visited = 0
                var entryLimit = false
                access.children(root) { name ->
                    budget()
                    if (++visited > Policy.MAX_ENTRIES) { entryLimit = true; false }
                    else {
                        if (Policy.allowedName(name)) {
                            val path = "$root/$name"
                            try {
                                val stat = access.stat(path)
                                if (stat.regular && stat.size >= 0) candidates += path to stat
                                else append("file=$path status=REJECTED_NONREGULAR; no open/read")
                            } catch (error: Exception) { append("file=$path status=${status(error)}; metadata failed") }
                        }
                        true
                    }
                }
                unchanged(parents)
                append("root=$root status=LISTED $identity entries=${minOf(visited, Policy.MAX_ENTRIES)} candidates=${candidates.size} entryLimit=$entryLimit fileLimit=${candidates.size > Policy.MAX_FILES}")
                for ((path, expected) in candidates.sortedWith(compareByDescending<Pair<String, Stamp>> { it.second.modified }.thenBy { it.first }).take(Policy.MAX_FILES)) {
                    try {
                        budget(); unchanged(parents)
                        val fd = access.open(path)
                        val lines: List<String>
                        val count: Int
                        val start: Long
                        var grew = false
                        try {
                            val before = access.statFd(fd)
                            if (!expected.sameFile(before) || !before.regular || before.size < 0) throw ProbeFailure("FILE_CHANGED_BEFORE_READ")
                            start = maxOf(0, before.size - Policy.TAIL_BYTES)
                            val bytes = ByteArray((before.size - start).toInt())
                            var offset = 0
                            while (offset < bytes.size) {
                                budget()
                                val n = access.read(fd, bytes, offset, minOf(8192, bytes.size - offset), start + offset)
                                if (n <= 0 || n > bytes.size - offset) throw ProbeFailure("SHORT_OR_TRUNCATED_READ")
                                offset += n; bytesRead += n
                            }
                            val after = access.statFd(fd)
                            if (!before.sameFile(after) || !after.regular || after.size < before.size) throw ProbeFailure("FILE_CHANGED_DURING_READ")
                            if (!after.sameFile(access.stat(path))) throw ProbeFailure("FILE_ROTATED_DURING_READ")
                            unchanged(parents)
                            grew = after.size > before.size
                            count = bytes.size
                            val text = bytes.toString(Charsets.UTF_8)
                            // Tail fragments and the producer's unfinished last line cannot attest a complete record.
                            val complete = (if (start > 0) text.substringAfter('\n', "") else text).substringBeforeLast('\n', "")
                            lines = complete.lineSequence().mapNotNull(Policy::targetLine).take(Policy.MAX_MATCHES + 1).toList()
                        } finally { access.close(fd) }
                        append("file=$path status=${if (count == 0) "EMPTY_READABLE" else "TAIL_READ"} bytes=$count offset=$start observedSize=${start + count} tailLimit=${start > 0} appended=$grew; non-atomic, source timestamps unknown")
                        for (line in lines) {
                            if (matches >= Policy.MAX_MATCHES) break
                            append("record file=$path $line"); matches++
                        }
                        if (matches >= Policy.MAX_MATCHES) append("matchLimit=true; additional records omitted")
                    } catch (error: Exception) { append("file=$path status=${status(error)}; no records retained for this failed file") }
                }
            } catch (error: Exception) {
                // Reserve space for a terminal failure even if the response budget was reached.
                out.append("root=$root status=${status(error)}\n")
            }
        }
        out.append("finished durationMs=${clock() - started} bytesRead=$bytesRead matches=$matches; bounded tails only; no matches does not prove absence; callbacks are decoded changes, not a heartbeat or atomic target set\n")
        return out.toString().take(Policy.MAX_RESPONSE)
    }

    private fun status(error: Exception): String {
        val cause = generateSequence<Throwable>(error) { it.cause }.take(16).last()
        return when (cause) {
        is ProbeFailure -> cause.status
        is ErrnoException -> when (cause.errno) {
            OsConstants.ENOENT, OsConstants.ENOTDIR -> "MISSING"
            OsConstants.EACCES, OsConstants.EPERM -> "DENIED"
            OsConstants.ELOOP -> "REJECTED_SYMLINK"
            else -> "IO_ERROR_errno_${cause.errno}"
        }
        is java.nio.file.AccessDeniedException, is SecurityException -> "DENIED"
        is java.nio.file.NoSuchFileException -> "MISSING"
        is UnsupportedOperationException -> "UNSUPPORTED"
        else -> "IO_ERROR_${cause.javaClass.simpleName.take(64)}"
        }
    }
}
