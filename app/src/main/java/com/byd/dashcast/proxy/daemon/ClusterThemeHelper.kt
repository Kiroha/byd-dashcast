package com.byd.dashcast.proxy.daemon

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.os.Looper
import android.util.Base64
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import android.system.Os
import android.system.OsConstants
import android.os.Process

/** Owned short-lived child: one mutation at most, same UID/namespace, no reconnect/replay.
 * Killing this process bounds OUR Binder wait, never cancels an admitted OEM request.
 */
object ClusterThemeHelper {
    private const val DESCRIPTOR = "android.os.IAutoContainer"
    private const val CALLBACK = "android.os.IContainerCallback"
    @JvmStatic fun main(args: Array<String>) {
        // Independent of daemon/app lifetime, including a blocked Binder call. This only kills us;
        // any request already admitted by OEM remains UNKNOWN.
        val selfDeadline = SystemClock.elapsedRealtime() + 8000
        Thread({
            while (SystemClock.elapsedRealtime() < selfDeadline) Thread.sleep(50)
            Process.killProcess(Process.myPid())
        }, "cluster-helper-self-deadline").apply { isDaemon = true; start() }
        val result = JSONObject().put("schema", 1)
        try {
            require(args.size == 3 && args[0] in setOf("query", "load", "restore"))
            require(ClusterThemePolicy.ownedName(args[1]) && args[2].matches(Regex("[0-9a-f]{32}")))
            val dir = File("/data/local/tmp/dashcast-cluster-live")
            check(Os.lstat(dir.path).st_uid == Process.myUid())
            val record = File(dir, "helper-${args[2]}.json")
            val start = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19]
            val fd = Os.open(record.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            FileOutputStream(fd).use { it.write(JSONObject().put("token", args[2]).put("filename", args[1]).put("pid", Process.myPid()).put("start", start).toString().toByteArray()); it.fd.sync() }
            // Parent must durably record our identity before GO. Parent death closes this pipe.
            check(System.`in`.bufferedReader().readLine() == "GO") { "Parent did not authorize admission" }
            HiddenApiBypass.setHiddenApiExemptions("Landroid/")
            val javaIdentity = verifyJavaBridge(selfDeadline)
            result.put("javaBridge", javaIdentity.toString())
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String::class.java).invoke(null, "AutoContainer") as? IBinder
                ?: error("AutoContainer unavailable")
            check(service.interfaceDescriptor == DESCRIPTOR) { "Unsupported AutoContainer descriptor" }
            val serviceDied = AtomicBoolean(false)
            service.linkToDeath({ serviceDied.set(true) }, 0)
            val events = ArrayList<ByteArray>()
            val cb = object : Binder() {
                init { attachInterface(null, CALLBACK) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                    if (getCallingUid() != 1000 || (getCallingPid() != javaIdentity.pid && !(getCallingPid() == 0 && flags and IBinder.FLAG_ONEWAY != 0))) throw SecurityException("Callback sender is not the pinned Java bridge process")
                    check(javaIdentity.stillCurrent()) { "Java bridge process changed" }
                    data.enforceInterface(CALLBACK)
                    if (code == 4 && data.dataAvail() <= 131200) {
                        val type = data.readInt()
                        val payload = data.createByteArray()
                        if (type == 8 && payload != null && payload.size in 8..65536) synchronized(events) {
                            if (events.size < 32) events.add(payload)
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
            }
            fun transact(code: Int, write: (Parcel) -> Unit): Int {
                val d = Parcel.obtain(); val r = Parcel.obtain()
                try {
                    check(!serviceDied.get() && service.isBinderAlive && javaIdentity.stillCurrent()) { "Pinned Java bridge/session changed" }
                    d.writeInterfaceToken(DESCRIPTOR); write(d)
                    check(service.transact(code, d, r, 0)) { "OEM transaction not handled" }
                    r.readException()
                    check(r.dataAvail() == 4) { "Missing/unsupported native result" }
                    return r.readInt()
                } finally { r.recycle(); d.recycle() }
            }
            val registration = transact(4) { it.writeStrongBinder(cb) }
            result.put("registration", registration)
            check(registration == 0) { "Callback registration refused: $registration" }
            fun send(bytes: ByteArray): Int = transact(3) { it.writeInt(8); it.writeByteArray(bytes) }
            val bytes = when (args[0]) {
                "query" -> ClusterThemeProtocol.query()
                "restore" -> ClusterThemeProtocol.restoreDefault()
                else -> ClusterThemeProtocol.load(args[1])
            }
            // Record this boundary even if the synchronous remote transaction never returns.
            println("DASHCAST_TRANSMITTING=${args[0]}"); System.out.flush()
            result.put("native", send(bytes))
            val until = SystemClock.elapsedRealtime() + 4500
            var nextQuery = SystemClock.elapsedRealtime() + 300
            var queryFloor = 0
            while (SystemClock.elapsedRealtime() < until) {
                val responses = synchronized(events) { events.toList() }
                val query = responses.mapNotNull { runCatching { ClusterThemeProtocol.decode(it) }.getOrNull() }
                    .lastOrNull { it.cmd == 1001 && it.sub == 4 }
                if (args[0] == "query" && query != null) break
                if (args[0] != "query" && SystemClock.elapsedRealtime() >= nextQuery) {
                    // Read-only selection queries; the load/restore itself is never retried.
                    queryFloor = synchronized(events) { events.size }
                    result.put("queryNative", send(ClusterThemeProtocol.query()))
                    nextQuery = SystemClock.elapsedRealtime() + 1500
                }
                Thread.sleep(50)
            }
            val raw = JSONArray()
            synchronized(events) { events.forEach { raw.put(Base64.encodeToString(it, Base64.NO_WRAP)) } }
            result.put("raw", raw)
            val post = JSONArray()
            synchronized(events) { events.drop(queryFloor).forEach { post.put(Base64.encodeToString(it, Base64.NO_WRAP)) } }
            result.put("postQueryRaw", post)
        } catch (t: Throwable) {
            result.put("error", "${t.javaClass.simpleName}: ${t.message}".take(1024))
        }
        println("DASHCAST_RESULT=$result"); System.out.flush()
        // Releases only this helper's callback; the daemon remains alive for restoration.
        kotlin.system.exitProcess(0)
    }
    private data class JavaIdentity(val pid: Int, val start: String, val mount: String) {
        fun stillCurrent(): Boolean = runCatching {
            start == processStart(pid) && mount == Os.readlink("/proc/$pid/ns/mnt") && Os.stat("/proc/$pid").st_uid == 1000
        }.getOrDefault(false)
    }
    private fun processStart(pid: Int): String = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19]
    @Suppress("DEPRECATION")
    private fun verifyJavaBridge(deadline: Long): JavaIdentity {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val app = context.packageManager.getApplicationInfo(ClusterThemePolicy.JAVA_PACKAGE, 0)
        check(app.uid == 1000 && app.sourceDir.startsWith("/")) { "Installed Java bridge UID/path differs" }
        val candidates = File("/proc").listFiles().orEmpty().filter { it.name.matches(Regex("[1-9][0-9]{0,8}")) }
            .filter { runCatching { File(it, "cmdline").readText().split('\u0000').first() == ClusterThemePolicy.JAVA_PACKAGE }.getOrDefault(false) }
        check(candidates.size == 1) { "Exactly one readable Java bridge process required" }
        val pid = candidates.single().name.toInt()
        val identity = JavaIdentity(pid, processStart(pid), Os.readlink("/proc/$pid/ns/mnt"))
        check(identity.stillCurrent()) { "Java bridge process UID/identity unavailable" }
        val path = "/proc/$pid/root" + app.sourceDir
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        FileInputStream(fd).use { input ->
            val st = Os.fstat(input.fd)
            check(OsConstants.S_ISREG(st.st_mode) && st.st_size in 1..(64L * 1024 * 1024)) { "Java bridge APK type/size unsupported" }
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
            while (true) { check(SystemClock.elapsedRealtime() < deadline); val n = input.read(buffer); if (n < 0) break
                size += n; check(size <= 64L * 1024 * 1024); digest.update(buffer, 0, n) }
            val after = Os.fstat(input.fd)
            check(size == st.st_size && after.st_size == st.st_size && after.st_mtime == st.st_mtime && after.st_ino == st.st_ino)
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == ClusterThemePolicy.JAVA_APK_SHA) { "Installed Java bridge APK differs from researched artifact" }
            val maps = File("/proc/$pid/maps").readLines().map { it.trim().split(Regex("\\s+"), limit = 6) }
            val rows = maps.filter { it.size == 6 && it[5] == app.sourceDir }
            check(rows.isNotEmpty()) { "Installed APK is not verifiably mapped by the Java bridge process" }
            for (row in rows) {
                val dev = row[3].split(':'); check(dev.size == 2)
                val major = (st.st_dev ushr 8 and 0xfff) or (st.st_dev ushr 32 and 0xfffff000L)
                val minor = (st.st_dev and 0xff) or (st.st_dev ushr 12 and 0xffffff00L)
                check(row[4].toLong() == st.st_ino && dev[0].toLong(16) == major && dev[1].toLong(16) == minor) { "Mapped Java APK identity differs" }
            }
        }
        val again = context.packageManager.getApplicationInfo(ClusterThemePolicy.JAVA_PACKAGE, 0)
        check(again.uid == 1000 && again.sourceDir == app.sourceDir && identity.stillCurrent()) { "Java bridge changed during verification" }
        return identity
    }

}
