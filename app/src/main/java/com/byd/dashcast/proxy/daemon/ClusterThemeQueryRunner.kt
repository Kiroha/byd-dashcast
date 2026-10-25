package com.byd.dashcast.proxy.daemon

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only a query child and a private temporary lease; never reads/writes the live-test journal. */
internal object ClusterThemeQueryRunner {
    data class Result(val receipt: JSONObject, val reaped: Boolean)
    @Volatile private var outstandingDisplayProbe: java.lang.Process? = null
    fun run(admit: () -> Unit = {}): Result = runHelper(null, admit, { _, _, _ -> })
    fun nativeMode(command: Int, admit: () -> Unit, ready: (Int, String, String) -> Unit): Result {
        require(command == 16 || command == 18)
        return runHelper(command, admit, ready)
    }
    fun dashboardMode(command: Int, filename: String, admit: () -> Unit, ready: (Int, String, String) -> Unit): Result {
        require(com.byd.dashcast.clusterlive.ClusterDashboardProfile.permitsCommand(command))
        require(com.byd.dashcast.clusterlive.ClusterThemePolicy.ownedName(filename))
        return runHelper(command, admit, ready, filename)
    }
    @Synchronized fun dashboardDisplay(filename: String, budgetMs: Long, valid: () -> Boolean, admit: () -> Unit): Result {
        require(com.byd.dashcast.clusterlive.ClusterThemePolicy.ownedName(filename))
        require(budgetMs in 1..15000)
        outstandingDisplayProbe?.let { previous ->
            if (previous.isAlive || !previous.waitFor(1, TimeUnit.MILLISECONDS))
                return Result(JSONObject().put("state", "UNAVAILABLE").put("detail", "Previous owned display reader remains unreaped; no new probe"), false)
            outstandingDisplayProbe = null
        }
        return runHelper(-1, admit, { _, _, _ -> }, filename, budgetMs, valid)
    }
    private fun runHelper(command: Int?, admit: () -> Unit, recordReady: (Int, String, String) -> Unit,
        dashboardFilename: String? = null, probeBudgetMs: Long? = null, probeValid: (() -> Boolean)? = null): Result {
        val probeDeadline = probeBudgetMs?.let { SystemClock.elapsedRealtime() + it }
        val root = File(if (command == null) "/data/local/tmp/dashcast-cluster-query" else "/data/local/tmp/dashcast-native-mode")
        if (!root.exists()) check(root.mkdir()) { "Cannot create private query directory" }
        val rootStat = Os.lstat(root.path)
        check(OsConstants.S_ISDIR(rootStat.st_mode) && rootStat.st_uid == Process.myUid()) { "Query directory owner/type differs" }
        Os.chmod(root.path, 448)
        val token = UUID.randomUUID().toString().replace("-", "")
        val lease = File(root, (if (command == null) "query-" else "request-") + token)
        check(lease.mkdir()) { "Query lease already exists" }
        Os.chmod(lease.path, 448)
        val ready = File(lease, "ready.json")
        var reaped = true
        var child: java.lang.Process? = null
        var reader: Thread? = null
        val output = ByteArrayOutputStream()
        var result = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-start")
            .put("nativeIdentity", "UNKNOWN")
        try {
            val apk = System.getenv("CLASSPATH") ?: error("Daemon APK classpath unavailable")
            check(apk.startsWith("/") && File(apk).isFile) { "Unsupported daemon classpath" }
            val arguments = mutableListOf("/system/bin/app_process64", "-Xnoimage-dex2oat", "/system/bin",
                if (command == null) "com.byd.dashcast.proxy.daemon.ClusterThemeQueryHelper" else "com.byd.dashcast.proxy.daemon.ClusterNativeModeHelper", token)
            if (command != null) arguments.add(command.toString())
            dashboardFilename?.let(arguments::add)
            val process = ProcessBuilder(arguments).redirectErrorStream(true)
                .apply { environment()["CLASSPATH"] = apk }.start()
            child = process; reaped = false
            val overflow = AtomicBoolean(false)
            reader = Thread({
                runCatching { process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        synchronized(output) { if (output.size() + n <= 32768) output.write(buffer, 0, n) else overflow.set(true) }
                    }
                } }
            }, "cluster-query-result").apply { isDaemon = true; start() }
            val readyUntil = minOf(SystemClock.elapsedRealtime() + 4000, probeDeadline ?: Long.MAX_VALUE)
            while (!ready.exists() && process.isAlive && SystemClock.elapsedRealtime() < readyUntil) {
                check(probeValid == null || probeValid()) { "Display snapshot cancelled before readiness" }
                Thread.sleep(25)
            }
            check(probeValid == null || probeValid()) { "Display snapshot cancelled before admission" }
            val fd = Os.open(ready.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                val st = Os.fstat(fd)
                check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() && st.st_size in 1..1024)
                val bytes = ByteArray(st.st_size.toInt())
                var count = 0
                while (count < bytes.size) { val n = Os.read(fd, bytes, count, bytes.size - count); check(n > 0); count += n }
                val admission = JSONObject(bytes.toString(Charsets.UTF_8))
                check(Os.read(fd, ByteArray(1), 0, 1) == 0 && admission.getString("token") == token)
                if (command != null) recordReady(admission.getInt("pid"), admission.getString("start"), token)
            } finally { Os.close(fd) }
            check(process.isAlive) { "Read-only helper died before admission" }
            admit()
            check(probeDeadline == null || SystemClock.elapsedRealtime() < probeDeadline) { "Display snapshot budget expired before GO" }
            process.outputStream.write("GO\n".toByteArray()); process.outputStream.flush()
            val waitMs = minOf(9000L, probeDeadline?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(1) } ?: 9000L)
            if (probeValid == null) check(process.waitFor(waitMs, TimeUnit.MILLISECONDS)) { "Read-only helper deadline; no replay" }
            else awaitDisplayProbe(process, SystemClock.elapsedRealtime() + waitMs, probeValid)
            reaped = true
            reader.join(500)
            check(!reader.isAlive && !overflow.get()) { "Incomplete/oversized query result" }
            val text = synchronized(output) { output.toString("UTF-8") }
            val prefix = if (command == null) "DASHCAST_QUERY_RESULT=" else "DASHCAST_NATIVE_MODE_RESULT="
            val line = text.lineSequence().lastOrNull { it.startsWith(prefix) }
                ?: error("Missing read-only helper result")
            val receipt = JSONObject(line.removePrefix(prefix))
            check(receipt.getString("state") in setOf("RECEIVED", "UNAVAILABLE"))
            result = receipt.put("nativeIdentity", "UNKNOWN")
        } catch (error: Throwable) {
            result.put("detail", "${error.javaClass.simpleName}: ${error.message}".take(768))
        } finally {
            child?.let { process ->
                if (!reaped) {
                    runCatching { process.destroyForcibly() }
                    reaped = runCatching { process.waitFor(1000, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                }
                runCatching { process.inputStream.close() }; runCatching { process.outputStream.close() }
            }
            runCatching { reader?.join(200) }
            if (reaped) {
                // Only the freshly created private lease and its fixed ready record are eligible.
                runCatching {
                    for (record in listOf(ready, File(lease, "ready.tmp"))) if (record.exists()) {
                        val st = Os.lstat(record.path)
                        check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid())
                        check(record.delete())
                    }
                    check(lease.delete())
                }
            }
        }
        if (command != null && !result.has("trace")) {
            val partial = synchronized(output) { output.toString("UTF-8") }.lineSequence()
                .filter { it.startsWith("DASHCAST_NATIVE_STEP=") }.map { it.removePrefix("DASHCAST_NATIVE_STEP=").take(512) }.take(20).toList()
            result.put("trace", org.json.JSONArray(partial))
        }
        if (command == -1 && !reaped) outstandingDisplayProbe = child
        if (!reaped) result.put("detail", if (command == -1)
            "Owned display reader not reaped; further display probes blocked. Native/RCC Restore remains available."
            else "Owned query helper not reaped; further queries/tests blocked. Native renderer identity UNKNOWN.")
        return Result(result.put("receivedEpochMs", System.currentTimeMillis()), reaped)
    }

    internal fun awaitDisplayProbe(process: java.lang.Process, deadline: Long, valid: () -> Boolean) {
        while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
            if (!valid()) {
                process.destroyForcibly()
                error("Display snapshot cancelled; owned read-only child stopped")
            }
            if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check(valid()) { "Display snapshot completed for a retired opening" }
                return
            }
        }
        check(!process.isAlive) { "Display snapshot deadline; no replay" }
        check(valid()) { "Display snapshot completed for a retired opening" }
    }
}
