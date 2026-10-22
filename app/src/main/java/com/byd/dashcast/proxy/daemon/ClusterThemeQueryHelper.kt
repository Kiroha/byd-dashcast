package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeReadOnlyQuery
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Separate short-lived read-only process. The mutating helper and its authentication stay strict. */
object ClusterThemeQueryHelper {
    private const val DESCRIPTOR = "android.os.IAutoContainer"
    private const val CALLBACK = "android.os.IContainerCallback"
    @Suppress("DEPRECATION")
    @JvmStatic fun main(args: Array<String>) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        Thread({
            while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            Process.killProcess(Process.myPid())
        }, "cluster-query-self-deadline").apply { isDaemon = true; start() }
        var result = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-admission")
        try {
            require(args.size == 1 && args[0].matches(Regex("[0-9a-f]{32}")))
            val dir = File("/data/local/tmp/dashcast-cluster-query/query-${args[0]}")
            val st = Os.lstat(dir.path)
            check(OsConstants.S_ISDIR(st.st_mode) && st.st_uid == Process.myUid() && st.st_mode and 511 == 448)
            val temporary = File(dir, "ready.tmp")
            val fd = Os.open(temporary.path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            try {
                val bytes = JSONObject().put("token", args[0]).toString().toByteArray()
                var offset = 0
                while (offset < bytes.size) { val n = Os.write(fd, bytes, offset, bytes.size - offset); check(n > 0); offset += n }
                Os.fsync(fd)
            } finally { Os.close(fd) }
            Os.rename(temporary.path, File(dir, "ready.json").path)
            check(System.`in`.bufferedReader().readLine() == "GO") { "Parent did not admit read-only query" }
            HiddenApiBypass.setHiddenApiExemptions("Landroid/")
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            val transport = QueryTransport(context, deadline)
            val receipt = ClusterThemeReadOnlyQuery.execute(transport, deadline, SystemClock::elapsedRealtime) { Thread.sleep(50) }
            result = JSONObject().put("state", receipt.state).put("phase", receipt.phase).put("detail", receipt.detail)
                .put("registration", receipt.registration).put("native", receipt.native)
                .put("rejected", receipt.rejected).put("malformed", receipt.malformed)
                .put("selection", receipt.response?.summary()?.take(6144).orEmpty())
                .put("expectedJavaApkSha256", ClusterThemePolicy.JAVA_APK_SHA).put("nativeIdentity", "UNKNOWN")
        } catch (error: Throwable) {
            result.put("detail", "${error.javaClass.simpleName}: ${error.message}".take(768))
        }
        println("DASHCAST_QUERY_RESULT=$result"); System.out.flush()
        // The OEM's per-Binder death recipient removes only this helper's callback.
        kotlin.system.exitProcess(0)
    }

    private class QueryTransport(private val context: Context, private val deadline: Long) : ClusterThemeReadOnlyQuery.Transport {
        private var snapshot: InstallIdentity? = null
        private var service: IBinder? = null
        private val died = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val death = IBinder.DeathRecipient { died.set(true) }
        private var callback: Binder? = null

        override fun verifyIdentity() {
            val current = installedIdentity(context, deadline)
            val original = snapshot
            if (original == null) {
                snapshot = current
                val binder = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                    .invoke(null, "AutoContainer") as? IBinder ?: error("AutoContainer unavailable")
                check(binder.interfaceDescriptor == DESCRIPTOR) { "Unsupported AutoContainer descriptor" }
                binder.linkToDeath(death, 0)
                service = binder
            } else check(original == current) { "Installed Java bridge changed; no reconnect/replay" }
            check(alive()) { "Pinned AutoContainer Binder died" }
        }
        override fun alive() = !closed.get() && !died.get() && service?.isBinderAlive == true
        override fun register(receiver: (Int, ByteArray) -> Unit): Int {
            val cb = object : Binder() {
                init { attachInterface(null, CALLBACK) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                    check(!closed.get() && !died.get() && SystemClock.elapsedRealtime() < deadline) { "Query callback lease expired" }
                    if (getCallingUid() != 1000) throw SecurityException("Query callback UID differs from system bridge")
                    data.enforceInterface(CALLBACK)
                    if (code == 4) {
                        check(data.dataAvail() in 8..131200) { "Unsupported callback size" }
                        val type = data.readInt()
                        val bytes = data.createByteArray()
                        if (type == 8 && bytes != null && bytes.size in 8..65536 && data.dataAvail() == 0)
                            receiver(getCallingUid(), bytes)
                    }
                    reply?.writeNoException()
                    return true
                }
            }
            callback = cb
            return transact(4) { it.writeStrongBinder(cb) }
        }
        override fun sendQuery(bytes: ByteArray): Int {
            check(bytes.contentEquals(com.byd.dashcast.clusterlive.ClusterThemeProtocol.query())) { "Only selection query is permitted" }
            return transact(3) { it.writeInt(8); it.writeByteArray(bytes) }
        }
        private fun transact(code: Int, write: (Parcel) -> Unit): Int {
            check(alive() && SystemClock.elapsedRealtime() < deadline) { "Query Binder/deadline changed" }
            val d = Parcel.obtain(); val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(DESCRIPTOR); write(d)
                check(checkNotNull(service).transact(code, d, r, 0)) { "OEM transaction not handled" }
                r.readException()
                check(r.dataAvail() == 4) { "Missing/unsupported OEM native result" }
                return r.readInt()
            } finally { r.recycle(); d.recycle() }
        }
        override fun close() {
            closed.set(true)
            runCatching { service?.unlinkToDeath(death, 0) }
        }
    }

    private data class InstallIdentity(val path: String, val uid: Int, val updated: Long,
        val device: Long, val inode: Long, val size: Long, val modified: Long)

    @Suppress("DEPRECATION")
    private fun installedIdentity(context: Context, deadline: Long): InstallIdentity {
        val info = context.packageManager.getPackageInfo(ClusterThemePolicy.JAVA_PACKAGE, 0)
        val app = checkNotNull(info.applicationInfo)
        check(app.uid == 1000 && app.sourceDir.startsWith("/") && app.splitSourceDirs.isNullOrEmpty() &&
            app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            "Unsupported installed Java bridge UID/source/splits"
        }
        val fd = Os.open(app.sourceDir, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        try {
            val before = Os.fstat(fd)
            check(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..(64L * 1024 * 1024)) { "Unsupported installed APK type/size" }
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "Installed APK hash deadline exceeded" }
                val n = Os.read(fd, buffer, 0, buffer.size); if (n == 0) break
                size += n; check(size <= 64L * 1024 * 1024); digest.update(buffer, 0, n)
            }
            val after = Os.fstat(fd); val pathNow = Os.lstat(app.sourceDir)
            check(size == before.st_size && after.st_size == before.st_size && after.st_mtime == before.st_mtime &&
                after.st_ino == before.st_ino && after.st_dev == before.st_dev && pathNow.st_dev == before.st_dev && pathNow.st_ino == before.st_ino) {
                "Installed Java APK changed while hashing"
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == ClusterThemePolicy.JAVA_APK_SHA) {
                "Installed Java bridge APK differs from researched artifact"
            }
            val again = context.packageManager.getPackageInfo(ClusterThemePolicy.JAVA_PACKAGE, 0)
            check(again.applicationInfo?.uid == app.uid && again.applicationInfo?.sourceDir == app.sourceDir &&
                again.applicationInfo?.splitSourceDirs.isNullOrEmpty() && again.lastUpdateTime == info.lastUpdateTime) {
                "Installed Java bridge changed during verification"
            }
            return InstallIdentity(app.sourceDir, app.uid, info.lastUpdateTime, before.st_dev, before.st_ino, before.st_size, before.st_mtime)
        } finally { Os.close(fd) }
    }
}
