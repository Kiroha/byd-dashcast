package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.byd.dashcast.clusterlive.ClusterThemeIviExchange
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.platform.Platform
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Exact installed Java bridge, fixed RCCs and one mutation. No host-process/UID/namespace fallback. */
object ClusterThemeIviHelper {
    private const val DESCRIPTOR = "android.os.IAutoContainer"
    private const val CALLBACK = "android.os.IContainerCallback"
    @JvmStatic fun main(args: Array<String>) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        Thread({
            while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            Process.killProcess(Process.myPid())
        }, "cluster-ivi-self-deadline").apply { isDaemon = true; start() }
        val result = JSONObject().put("schema", 1).put("transport", "android-ivi").put("nativeIdentity", "UNKNOWN")
        try {
            require(args.size == 7 && args[0] in setOf("query", "load", "restore") &&
                ClusterThemePolicy.ownedName(args[1]) && args[2].matches(Regex("[0-9a-f]{32}")))
            val variant = ClusterThemeVariant.values().single { it.id == args[3] }
            val dir = File("/data/local/tmp/dashcast-cluster-live")
            val st = Os.lstat(dir.path)
            check(OsConstants.S_ISDIR(st.st_mode) && st.st_uid == Process.myUid() && st.st_mode and 511 == 448)
            val record = File(dir, "helper-${args[2]}.json")
            val fd = Os.open(record.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
            FileOutputStream(fd).use {
                it.write(JSONObject().put("token", args[2]).put("filename", args[1]).put("pid", Process.myPid())
                    .put("start", ClusterNativeModeHelper.procStart(Process.myPid())).toString().toByteArray())
                it.fd.sync()
            }
            check(System.`in`.bufferedReader().readLine() == "GO") { "Parent did not authorize admission" }
            HiddenApiBypass.setHiddenApiExemptions("Landroid/")
            check(android.os.Build.VERSION.SDK_INT == 29 && Platform.get().isAutoDetectedDiLink3()) {
                "Positive DiLink 3 and API 29 required; fallback/override insufficient"
            }
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            val transport = Transport(context, deadline, args[1], variant, args[4].toLong(), args[5].toLong(), args[6])
            val receipt = ClusterThemeIviExchange.execute(args[0], args[1], transport, deadline,
                SystemClock::elapsedRealtime, { Thread.sleep(25) }) {
                println("DASHCAST_IVI_STEP=epochMs=${System.currentTimeMillis()} $it"); System.out.flush()
            }
            result.put("native", receipt.native).put("queryNative", receipt.queryNative).put("attempted", receipt.attempted)
                .put("retirementEligible", receipt.retirementEligible)
                .put("raw", JSONArray(receipt.responses.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
                .put("postQueryRaw", JSONArray(receipt.postQueryResponses.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
                .put("trace", JSONArray(receipt.trace)).put("callbackCorrelation", "UNAVAILABLE")
            if (receipt.error != null) result.put("error", receipt.error)
        } catch (error: Throwable) {
            result.put("error", "${error.javaClass.simpleName}: ${error.message}".take(1024))
        }
        println("DASHCAST_RESULT=$result"); System.out.flush()
        kotlin.system.exitProcess(0)
    }

    private class Transport(private val context: Context, private val deadline: Long, private val name: String,
        private val variant: ClusterThemeVariant, private val expectedDevice: Long, private val expectedInode: Long, private val expectedParents: String) : ClusterThemeIviExchange.Transport {
        private var identity: ClusterThemeQueryHelper.InstallIdentity? = null
        private var service: IBinder? = null
        private var callback: Binder? = null
        private val died = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val death = IBinder.DeathRecipient { died.set(true) }
        override fun verifyIdentity() {
            val current = ClusterThemeQueryHelper.installedIdentity(context, deadline)
            if (identity == null) {
                identity = current
                val binder = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                    .invoke(null, "AutoContainer") as? IBinder ?: error("AutoContainer unavailable")
                check(binder.interfaceDescriptor == DESCRIPTOR) { "Unsupported AutoContainer descriptor" }
                binder.linkToDeath(death, 0); service = binder
                println("DASHCAST_IVI_STEP=javaIdentityVerified=true uid=${current.uid} path=${current.path} SHA256=${ClusterThemePolicy.JAVA_APK_SHA} nativeIdentity=UNKNOWN")
                System.out.flush()
            } else check(identity == current) { "Installed Java APK changed; no reconnect/replay" }
            check(alive()) { "Pinned AutoContainer died" }
        }
        override fun alive() = !closed.get() && !died.get() && service?.isBinderAlive == true
        override fun register(receiver: (Int, ByteArray) -> Unit): Int {
            val cb = object : Binder() {
                init { attachInterface(null, CALLBACK) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                    check(alive() && SystemClock.elapsedRealtime() < deadline) { "Callback lease expired" }
                    val uid = getCallingUid()
                    if (uid != 1000) { receiver(uid, ByteArray(0)); throw SecurityException("Unexpected callback UID") }
                    data.enforceInterface(CALLBACK)
                    if (code == 4) {
                        if (data.dataAvail() !in 8..8200) receiver(uid, ByteArray(0))
                        else try {
                            val type = data.readInt(); val bytes = data.createByteArray()
                            if (type == 8) receiver(uid, if (data.dataAvail() == 0 && bytes != null) bytes else ByteArray(0))
                        } catch (error: Exception) { receiver(uid, ByteArray(0)); throw error }
                    }
                    reply?.writeNoException(); return true
                }
            }
            callback = cb
            return transact(4) { it.writeStrongBinder(cb) }
        }
        override fun send(bytes: ByteArray): Int = transact(3) { it.writeInt(8); it.writeByteArray(bytes) }
        private fun transact(code: Int, write: (Parcel) -> Unit): Int {
            check(alive() && SystemClock.elapsedRealtime() < deadline)
            val d = Parcel.obtain(); val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(DESCRIPTOR); write(d)
                check(checkNotNull(service).transact(code, d, r, 0)) { "OEM transaction not handled" }
                r.readException(); check(r.dataAvail() == 4) { "Missing/unsupported OEM native result" }
                return r.readInt()
            } finally { r.recycle(); d.recycle() }
        }
        override fun verifyCarrier() {
            ClusterThemeAndroidStorage.withDirectory { anchor, directory ->
            check(directory.identity == expectedParents) { "Carrier parent differs from staged directory generation" }
            val file = File(anchor, name)
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            FileInputStream(fd).use { input ->
                val before = Os.fstat(input.fd)
                check(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..ClusterThemePolicy.MAX_CARRIER &&
                    expectedDevice >= 0 && expectedInode >= 0 && before.st_dev == expectedDevice && before.st_ino == expectedInode) {
                    "Carrier generation differs from the daemon's exclusively staged object"
                }
                val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline)
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= ClusterThemePolicy.MAX_CARRIER); md.update(buffer, 0, n)
                }
                val after = Os.fstat(input.fd); val named = Os.lstat(file.path)
                check(total == before.st_size && after.st_dev == before.st_dev && after.st_ino == before.st_ino &&
                    after.st_size == before.st_size && after.st_mtime == before.st_mtime &&
                    named.st_dev == before.st_dev && named.st_ino == before.st_ino &&
                    directory == ClusterThemeAndroidStorage.directory(false)) { "Carrier/directory changed" }
                check(md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == variant.carrierSha256) {
                    "Carrier differs from closed compiled profile"
                }
            }
            }
        }
        override fun close() { closed.set(true); runCatching { service?.unlinkToDeath(death, 0) } }
    }
}
