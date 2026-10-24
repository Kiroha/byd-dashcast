package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.byd.dashcast.platform.Platform
import org.json.JSONObject
import org.json.JSONArray
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A single fixed OEM mode request; no callback, renderer patch, CAN frame or retry. */
object ClusterNativeModeHelper {
    @Suppress("DEPRECATION")
    @JvmStatic fun main(args: Array<String>) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        Thread({
            while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            Process.killProcess(Process.myPid())
        }, "native-mode-deadline").apply { isDaemon = true; start() }
        val result = JSONObject().put("state", "UNAVAILABLE").put("phase", "helper-admission")
            .put("attempted", false).put("physicalPixels", "UNKNOWN").put("qmlAck", "UNKNOWN")
        val trace = JSONArray()
        fun step(stage: String, detail: String = "") {
            val line = "epochMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} stage=$stage ${detail.take(384)}"
            if (trace.length() < 20) trace.put(line)
            println("DASHCAST_NATIVE_STEP=$line")
        }
        try {
            require(args.size in 2..3 && args[0].matches(Regex("[0-9a-f]{32}")))
            val dashboard = args.size == 3
            val command = args[1].toInt()
            require(if (dashboard) com.byd.dashcast.clusterlive.ClusterThemePolicy.ownedName(args[2]) &&
                com.byd.dashcast.clusterlive.ClusterDashboardProfile.permitsCommand(command) else command == 16 || command == 18)
            step("helper-start", "command=$command uid=${Process.myUid()} pid=${Process.myPid()} token=${args[0]}")
            val dir = File("/data/local/tmp/dashcast-native-mode/request-${args[0]}")
            val st = Os.lstat(dir.path)
            check(OsConstants.S_ISDIR(st.st_mode) && st.st_uid == Process.myUid() && st.st_mode and 511 == 448)
            HiddenApiBypass.setHiddenApiExemptions("Landroid/")
            check(android.os.Build.VERSION.SDK_INT == 29 && Platform.get().isAutoDetectedDiLink3()) {
                "Positive DiLink 3 and API 29 required; effective fallback is insufficient"
            }
            step("platform-verified", "API=29 positiveDL3=true build=${android.os.Build.DISPLAY} fingerprint=${android.os.Build.FINGERPRINT}")
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
            val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
            if (dashboard && command == 31) {
                // Read the actual current display. A cached registry or preset is
                // not evidence that the car has the required physical geometry.
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val matches = dm.displays.filter { com.byd.dashcast.cluster.display.ClusterDisplayNames.isKnownClusterName(it.name) }
                check(matches.size == 1) { "Current cluster geometry is missing or ambiguous" }
                val size = android.graphics.Point(); matches.single().getRealSize(size)
                check(matches.single().state == android.view.Display.STATE_ON &&
                    com.byd.dashcast.clusterlive.ClusterDashboardProfile.permitsGeometry(size.x, size.y, false)) {
                    "Dashboard requires an actual ON 1920x720 cluster; unknown/small geometry refused"
                }
                step("dashboard-current-geometry", "display=${matches.single().displayId} size=${size.x}x${size.y}")
            }
            result.put("phase", "java-identity")
            step("java-identity-begin", "package=${com.byd.dashcast.clusterlive.ClusterThemePolicy.JAVA_PACKAGE} expectedSHA256=${com.byd.dashcast.clusterlive.ClusterThemePolicy.JAVA_APK_SHA}")
            val identity = ClusterThemeQueryHelper.installedIdentity(context, deadline)
            step("java-identity-verified", "uid=${identity.uid} path=${identity.path} dev=${identity.device} inode=${identity.inode} size=${identity.size}; exactSHAverified=true nativeRendererIdentity=UNKNOWN")
            val service = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                .invoke(null, "AutoContainer") as? IBinder ?: error("AutoContainer unavailable")
            check(service.interfaceDescriptor == "android.os.IAutoContainer") { "Unsupported AutoContainer descriptor" }
            val died = AtomicBoolean(false)
            val death = IBinder.DeathRecipient { died.set(true) }
            service.linkToDeath(death, 0)
            step("service-pinned", "service=AutoContainer descriptor=android.os.IAutoContainer")
            try {
                check(service.isBinderAlive && !died.get() && SystemClock.elapsedRealtime() < deadline)
                val fd = Os.open(File(dir, "ready.tmp").path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                    OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
                try {
                    val bytes = JSONObject().put("token", args[0]).put("pid", Process.myPid())
                        .put("start", procStart(Process.myPid())).toString().toByteArray()
                    var offset = 0
                    while (offset < bytes.size) { val n = Os.write(fd, bytes, offset, bytes.size - offset); check(n > 0); offset += n }
                    Os.fsync(fd)
                } finally { Os.close(fd) }
                Os.rename(File(dir, "ready.tmp").path, File(dir, "ready.json").path)
                check(System.`in`.bufferedReader().readLine() == "GO") { "Parent did not admit native mode request" }
                step("GO-received")

                check(service.isBinderAlive && !died.get() && SystemClock.elapsedRealtime() < deadline) { "Pinned Binder/deadline changed before transaction" }

                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.os.IAutoContainer")
                    data.writeInt(1000); data.writeInt(command); data.writeString("")
                    result.put("phase", "native-send").put("attempted", true)
                    step("native-transaction-attempt", "sendInfo type=1000 command=$command string=empty")
                    check(service.transact(2, data, reply, 0)) { "OEM sendInfo not handled" }
                    reply.readException(); check(reply.dataAvail() == 4) { "Missing/unsupported OEM native integer" }
                    val native = reply.readInt(); result.put("native", native)
                    step("native-integer-return", "native=$native; QtDelivery/debounce/QML_ACK/pixels/g_productIndex=UNKNOWN")
                    check(identity == ClusterThemeQueryHelper.installedIdentity(context, deadline)) { "Installed Java bridge changed" }
                    check(service.isBinderAlive && !died.get() && SystemClock.elapsedRealtime() < deadline)
                    step("identity-rechecked", "pinnedBinderAlive=${service.isBinderAlive} binderDied=${died.get()}")
                    result.put("state", "RECEIVED").put("phase", "complete")
                        .put("detail", "Single sendInfo(1000,$command) returned native=$native; debounce, QML acknowledgment and physical pixels UNKNOWN.")
                } finally { reply.recycle(); data.recycle() }
            } finally { runCatching { service.unlinkToDeath(death, 0) } }
        } catch (error: Throwable) {
            step("exception", "phase=${result.optString("phase")} class=${error.javaClass.name} message=${error.message}")
            result.put("detail", "${error.javaClass.simpleName}: ${error.message}".take(768))
        }
        result.put("trace", trace)
        println("DASHCAST_NATIVE_MODE_RESULT=$result"); System.out.flush()
        kotlin.system.exitProcess(0)
    }

    internal fun procStart(pid: Int): String = File("/proc/$pid/stat").readText()
        .substringAfterLast(')').trim().split(Regex("\\s+"))[19]
}
