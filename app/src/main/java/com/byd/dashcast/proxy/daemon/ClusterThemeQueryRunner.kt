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
    fun run(): Result {
        val root = File("/data/local/tmp/dashcast-cluster-query")
        if (!root.exists()) check(root.mkdir()) { "Cannot create private query directory" }
        val rootStat = Os.lstat(root.path)
        check(OsConstants.S_ISDIR(rootStat.st_mode) && rootStat.st_uid == Process.myUid()) { "Query directory owner/type differs" }
        Os.chmod(root.path, 448)
        val token = UUID.randomUUID().toString().replace("-", "")
        val lease = File(root, "query-$token")
        check(lease.mkdir()) { "Query lease already exists" }
        Os.chmod(lease.path, 448)
        val ready = File(lease, "ready.json")
        var reaped = true
        var child: java.lang.Process? = null
        var reader: Thread? = null
        var result = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-start")
            .put("nativeIdentity", "UNKNOWN")
        try {
            val apk = System.getenv("CLASSPATH") ?: error("Daemon APK classpath unavailable")
            check(apk.startsWith("/") && File(apk).isFile) { "Unsupported daemon classpath" }
            val process = ProcessBuilder("/system/bin/app_process64", "-Xnoimage-dex2oat", "/system/bin",
                "com.byd.dashcast.proxy.daemon.ClusterThemeQueryHelper", token).redirectErrorStream(true)
                .apply { environment()["CLASSPATH"] = apk }.start()
            child = process; reaped = false
            val output = ByteArrayOutputStream()
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
            val readyUntil = SystemClock.elapsedRealtime() + 4000
            while (!ready.exists() && process.isAlive && SystemClock.elapsedRealtime() < readyUntil) Thread.sleep(25)
            val fd = Os.open(ready.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                val st = Os.fstat(fd)
                check(OsConstants.S_ISREG(st.st_mode) && st.st_uid == Process.myUid() && st.st_size in 1..1024)
                val bytes = ByteArray(st.st_size.toInt())
                var count = 0
                while (count < bytes.size) { val n = Os.read(fd, bytes, count, bytes.size - count); check(n > 0); count += n }
                check(Os.read(fd, ByteArray(1), 0, 1) == 0 && JSONObject(bytes.toString(Charsets.UTF_8)).getString("token") == token)
            } finally { Os.close(fd) }
            check(process.isAlive) { "Read-only helper died before admission" }
            process.outputStream.write("GO\n".toByteArray()); process.outputStream.flush()
            check(process.waitFor(9000, TimeUnit.MILLISECONDS)) { "Read-only helper deadline; no replay" }
            reaped = true
            reader.join(500)
            check(!reader.isAlive && !overflow.get()) { "Incomplete/oversized query result" }
            val text = synchronized(output) { output.toString("UTF-8") }
            val line = text.lineSequence().lastOrNull { it.startsWith("DASHCAST_QUERY_RESULT=") }
                ?: error("Missing read-only helper result")
            val receipt = JSONObject(line.removePrefix("DASHCAST_QUERY_RESULT="))
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
        if (!reaped) result.put("detail", "Owned query helper not reaped; further queries/tests blocked. Native renderer identity UNKNOWN.")
        return Result(result.put("receivedEpochMs", System.currentTimeMillis()), reaped)
    }
}
