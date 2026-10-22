package com.byd.dashcast.clusterlive

import java.io.ByteArrayOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/** Observes only this process's visible /proc view. Names never replace live identity checks. */
internal object ClusterRendererDiscovery {
    const val MAX_ENTRIES = 8192
    const val MAX_PIDS = 4096
    const val MAX_ARGV0_BYTES = 1024
    const val SCAN_MS = 5000L
    private val pidName = Regex("[1-9][0-9]{0,8}")

    enum class FailureKind { DENIED, VANISHED, IO }
    data class Failure(val kind: FailureKind, val label: String)
    interface Access<F> {
        fun open(pid: String): F
        fun read(fd: F, buffer: ByteArray, count: Int): Int
        fun close(fd: F)
    }
    data class Result(
        val candidates: List<String>, val listStatus: String, val stop: String,
        val entries: Int, val pids: Int, val readable: Int, val denied: Int,
        val vanished: Int, val empty: Int, val noNul: Int, val tooLong: Int,
        val errors: Int, val firstIssue: String,
    ) {
        fun summary(): String = "Renderer discovery: matches=${candidates.size}; list=$listStatus; stop=$stop; " +
            "entries=$entries; pids=$pids; argv0=$readable; denied=$denied; vanished=$vanished; " +
            "empty=$empty; noNul=$noNul; tooLong=$tooLong; errors=$errors; first=$firstIssue. " +
            "Visible /proc view only; unavailable is not absent. No UID/namespace fallback. " +
            "Collect Diag > Exports & OEM > Releves locaux OEM > Contexte cluster."

        fun requireSingle(): String {
            check(listStatus == "COMPLETE" && stop == "NONE" && candidates.size == 1) { summary() }
            return candidates.single()
        }
    }

    private class Stop(val reason: String) : RuntimeException()

    fun failure(error: Exception): Failure {
        val cause = generateSequence<Throwable>(error) { it.cause }.take(16).last()
        return when (cause) {
            is AccessDeniedException, is SecurityException -> Failure(FailureKind.DENIED, "DENIED")
            is NoSuchFileException -> Failure(FailureKind.VANISHED, "MISSING")
            else -> Failure(FailureKind.IO, cause.javaClass.simpleName.take(32))
        }
    }

    fun <F> scan(
        entries: ((String) -> Unit) -> Unit,
        access: Access<F>,
        classify: (Exception) -> Failure = ::failure,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Result {
        val started = clock()
        var visited = 0; var pids = 0; var readable = 0; var denied = 0
        var vanished = 0; var empty = 0; var noNul = 0; var tooLong = 0; var errors = 0
        var listStatus = "INCOMPLETE"; var stop = "NONE"; var firstIssue = "NONE"
        val candidates = ArrayList<String>()
        fun budget() { if (clock() - started >= SCAN_MS) throw Stop("TIME_LIMIT") }
        fun issue(value: String) { if (firstIssue == "NONE") firstIssue = value }
        try {
            entries { name ->
                budget()
                if (visited >= MAX_ENTRIES) throw Stop("ENTRY_LIMIT")
                visited++
                if (pidName.matches(name)) {
                    if (pids >= MAX_PIDS) throw Stop("PID_LIMIT")
                    pids++
                    try {
                        val fd = access.open(name)
                        val first = try {
                            val out = ByteArrayOutputStream()
                            val bytes = ByteArray(256)
                            var complete = false
                            while (out.size() <= MAX_ARGV0_BYTES) {
                                budget()
                                val n = access.read(fd, bytes, minOf(bytes.size, MAX_ARGV0_BYTES + 1 - out.size()))
                                if (n <= 0) break
                                val end = (0 until n).firstOrNull { bytes[it] == 0.toByte() }
                                out.write(bytes, 0, end ?: n)
                                if (end != null) { complete = true; break }
                            }
                            when {
                                out.size() > MAX_ARGV0_BYTES -> { tooLong++; issue("cmdline:TOO_LONG"); null }
                                out.size() == 0 -> { empty++; null }
                                !complete -> { noNul++; issue("cmdline:NO_NUL"); null }
                                else -> out.toString("UTF-8")
                            }
                        } finally { access.close(fd) }
                        if (first != null) {
                            readable++
                            // Keep the existing basename contract; executable/maps/hashes are checked later.
                            if (first.substringAfterLast('/') == "qtandroidnative") candidates += name
                        }
                    } catch (limit: Stop) { throw limit }
                    catch (error: Exception) {
                        val failure = classify(error)
                        when (failure.kind) {
                            FailureKind.DENIED -> denied++
                            FailureKind.VANISHED -> vanished++
                            FailureKind.IO -> errors++
                        }
                        issue("cmdline:${failure.label}")
                    }
                }
            }
            budget()
            listStatus = "COMPLETE"
        } catch (limit: Stop) { stop = limit.reason }
        catch (error: Exception) {
            val failure = classify(error)
            listStatus = "${failure.kind}:${failure.label}"
            issue("list:${failure.label}")
        }
        return Result(candidates.toList(), listStatus, stop, visited, pids, readable,
            denied, vanished, empty, noNul, tooLong, errors, firstIssue)
    }
}
