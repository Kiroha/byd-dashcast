package com.byd.dashcast.proxy.daemon

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * ProxyShell — shell execution utility for the daemon process.
 *
 * Provides a single [exec] entry point that forks a `sh -c` subprocess, captures combined
 * stdout+stderr, and enforces a 30-second timeout. Daemon-only.
 *
 * Kotlin port note: every `$` inside the shell scripts below is escaped as `${'$'}`. They are
 * shell parameter references, not Kotlin templates — and `$child` in particular IS a valid
 * Kotlin identifier, so an unescaped version would either fail to compile or, worse, silently
 * interpolate a local. Raw `"""` strings are deliberately NOT used here: the migration keeps a
 * three-file allowlist for those, and a shell command must never become one.
 */
internal object ProxyShell {

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    private const val TRUNCATION_MARKER = "[output truncated]"

    /** Result of an [exec] call. `exit` and `output` are read as bare fields from
     *  ProxyDaemonMain.java, so both carry @JvmField. */
    class Result(@JvmField val exit: Int, @JvmField val output: String)

    /**
     * Execute [cmd] via `sh -c`, capturing combined stdout + stderr. Strips trailing newlines to
     * preserve the exact semantics of the original line-by-line collector. Bounded by a
     * 30-second timeout.
     *
     * Build 195 / P4: reads the stream into a single [ByteArrayOutputStream] then decodes once —
     * replaces per-line `BufferedReader + append('\n')` which on a 300-line `dumpsys` ate ~1000
     * allocations.
     */
    @JvmStatic
    fun exec(cmd: String?): Result = exec(cmd, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_OUTPUT_BYTES)

    @JvmStatic
    fun exec(cmd: String?, timeoutMs: Long, maxOutputBytes: Int): Result {
        if (cmd == null) return Result(-1, "ERR null command")
        if (timeoutMs <= 0L) return Result(-1, "ERR invalid timeout")
        if (maxOutputBytes <= 0) return Result(-1, "ERR invalid output limit")
        var execution: ProcessExecution? = null
        var process: Process? = null
        var processOutput: InputStream? = null
        var reader: Thread? = null
        try {
            execution = startCommand(cmd)
            process = execution.process
            try { process.outputStream.close() } catch (ignored: Throwable) {}

            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            processOutput = process.inputStream
            val capture = OutputCapture(processOutput, maxOutputBytes)
            reader = Thread(capture, "proxy-shell-reader")
            reader.isDaemon = true
            reader.start()

            if (!process.waitFor(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                terminate(execution, processOutput, reader)
                return Result(-1, timeoutMessage(timeoutMs))
            }

            reader.join(remainingMillis(deadlineNanos))
            if (reader.isAlive) {
                terminate(execution, processOutput, reader)
                return Result(-1, timeoutMessage(timeoutMs))
            }
            return Result(process.exitValue(), capture.result())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(-1, "ERR interrupted")
        } catch (t: Throwable) {
            val msg = t.message
            return Result(-1, "ERR " + (msg ?: t.javaClass.simpleName))
        } finally {
            if (processOutput != null) try { processOutput.close() } catch (ignored: Throwable) {}
            if (process != null) try { process.destroy() } catch (ignored: Throwable) {}
            execution?.pidFile?.delete()
        }
    }

    @Throws(IOException::class)
    private fun startCommand(cmd: String): ProcessExecution {
        var tempDir = File("/data/local/tmp")
        if (!tempDir.isDirectory || !tempDir.canWrite()) {
            tempDir = File(System.getProperty("java.io.tmpdir", ".") ?: ".")
        }
        val pidFile = File.createTempFile("dashcast_proxy_shell_", ".pid", tempDir)
        // Pass cmd as $1 rather than interpolating it into the wrapper. The child shell receives
        // exactly the original command string; the wrapper owns only process-group lifecycle.
        // \$ is a Kotlin string escape, so this stays ONE compile-time constant and lands in
        // the constant pool verbatim - which is what makes it diffable against the Java original.
        val wrapper = "if command -v setsid >/dev/null 2>&1; then " +
                "setsid sh -c \"\$1\" & child=\$!; echo G:\$child > \"\$2\"; " +
                "else sh -c \"\$1\" & child=\$!; echo P:\$child > \"\$2\"; fi; wait \$child"
        try {
            val process = ProcessBuilder(
                    "sh", "-c", wrapper, "dashcast-proxy-shell", cmd, pidFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            return ProcessExecution(process, pidFile)
        } catch (startError: IOException) {
            pidFile.delete()
            throw startError
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return 1L
        return max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
    }

    private fun timeoutMessage(timeoutMs: Long): String =
            if (timeoutMs % 1000L == 0L) "ERR timeout " + (timeoutMs / 1000L) + "s"
            else "ERR timeout " + timeoutMs + "ms"

    private fun terminate(execution: ProcessExecution, output: InputStream, reader: Thread) {
        val process = execution.process
        var child = readChildIdentity(execution.pidFile)
        try { process.destroy() } catch (ignored: Throwable) {}
        if (child == null) child = readChildIdentity(execution.pidFile)
        if (child != null) killChild(child)
        try {
            if (!process.waitFor(100L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (ignored: Throwable) {
            try { process.destroyForcibly() } catch (ignoredAgain: Throwable) {}
        }
        try { output.close() } catch (ignored: Throwable) {}
        try { reader.join(500L) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun readChildIdentity(pidFile: File): ChildIdentity? {
        try {
            BufferedReader(FileReader(pidFile)).use { reader ->
                val line = reader.readLine()
                if (line == null || line.length < 3 || line[1] != ':') return null
                val pid = line.substring(2).trim().toLong()
                return if (pid > 0) ChildIdentity(pid, line[0] == 'G') else null
            }
        } catch (ignored: Throwable) {
            return null
        }
    }

    private fun killChild(child: ChildIdentity) {
        val target = if (child.processGroup) "-" + child.pid else child.pid.toString()
        var killer: Process? = null
        try {
            val script = "kill -9 -- \"\$1\" 2>/dev/null || kill -9 \"\$1\" 2>/dev/null || true"
            killer = ProcessBuilder("sh", "-c", script, "dashcast-proxy-kill", target).start()
            killer.waitFor(500L, TimeUnit.MILLISECONDS)
        } catch (ignored: Throwable) {
            // The direct Process.destroy path below still handles the supervisor/legacy child.
        } finally {
            if (killer != null) try { killer.destroy() } catch (ignored: Throwable) {}
        }
    }

    private class ProcessExecution(@JvmField val process: Process, @JvmField val pidFile: File)

    private class ChildIdentity(@JvmField val pid: Long, @JvmField val processGroup: Boolean)

    private class OutputCapture(
            private val input: InputStream,
            private val limit: Int
    ) : Runnable {
        private val output = ByteArrayOutputStream(min(limit, 2048))
        // Plain field, exactly as the Java had it. The reader thread is join()ed before result()
        // is called, so Thread.join's happens-before makes the write visible without @Volatile.
        private var truncated = false

        override fun run() {
            val chunk = ByteArray(4096)
            try {
                var count: Int
                while (input.read(chunk).also { count = it } > 0) {
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(chunk, 0, min(count, remaining))
                    if (count > remaining) truncated = true
                }
            } catch (ignored: Throwable) {
                // Closing the stream is how the timeout path unblocks this reader.
            }
        }

        fun result(): String {
            var value = String(output.toByteArray(), StandardCharsets.UTF_8)
            var end = value.length
            while (end > 0) {
                val c = value[end - 1]
                if (c != '\n' && c != '\r') break
                end--
            }
            value = if (end == value.length) value else value.substring(0, end)
            if (!truncated) return value
            return if (value.isEmpty()) TRUNCATION_MARKER else value + "\n" + TRUNCATION_MARKER
        }
    }
}
