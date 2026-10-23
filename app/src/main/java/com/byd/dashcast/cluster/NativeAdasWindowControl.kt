package com.byd.dashcast.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Explicit, one-shot requests. Saved recovery evidence never causes a boot/resume/reconnect write. */
internal class NativeAdasWindowControl(
    private val device: Device,
    private val recovery: Recovery,
    private val probeWorker: Executor,
    private val dispatchWorker: Executor,
) {
    interface Device {
        fun available(): Boolean
        fun originalValue(): Int
        fun send(frame: String)
    }
    interface Recovery {
        fun value(): Int?
        fun saveOriginal(): Boolean
    }
    enum class Action { HIDE, RESTORE }
    enum class Result { HIDE_SENT, RESTORE_SENT, UNAVAILABLE, ORIGINAL_UNKNOWN,
        RECOVERY_MISSING, RECOVERY_FAILED, SEND_FAILED, BUSY, CANCELLED }

    class Request internal constructor(callback: (Result) -> Unit,
        private val onCancelled: (Request) -> Unit) {
        private val phase = AtomicInteger(0)
        @Volatile private var callback: ((Result) -> Unit)? = callback
        // Cancellation wins until dispatch starts; an already queued Android broadcast cannot be recalled.
        fun cancel() {
            callback = null
            if (phase.compareAndSet(0, 2)) onCancelled(this)
        }
        internal fun cancelled(): Boolean = phase.get() == 2
        internal fun startDispatch(): Boolean = phase.compareAndSet(0, 1)
        internal fun complete(result: Result) { callback?.invoke(result); callback = null }
    }

    private val active = AtomicReference<Request?>()
    fun request(action: Action, callback: (Result) -> Unit): Request {
        if (!ProjectionAdasCoordinator.prepareManualRequest(action)) {
            return Request(callback) {}.also { it.complete(Result.BUSY) }
        }
        if (action == Action.RESTORE) active.get()?.cancel()
        val request = Request(callback) { active.compareAndSet(it, null) }
        if (!active.compareAndSet(null, request)) {
            request.complete(Result.BUSY)
            return request
        }
        if (com.byd.dashcast.cluster.display.ProjectionCommandBus.diagnosticReserved()) {
            active.compareAndSet(request, null); request.complete(Result.BUSY); return request
        }
        try {
            // A blocked SDK read occupies only the bounded probe lane. Cancelling that request
            // retires it, so a saved-value restore can still use the independent dispatch lane.
            (if (action == Action.HIDE) probeWorker else dispatchWorker).execute {
                val result = try { perform(action, request) }
                    catch (_: Exception) { Result.SEND_FAILED }
                active.compareAndSet(request, null)
                AppLogger.i("NativeAdasWindow", "action=$action result=$result")
                request.complete(result)
            }
        } catch (_: Exception) {
            active.compareAndSet(request, null)
            request.complete(Result.UNAVAILABLE)
        }
        return request
    }

    private fun perform(action: Action, request: Request): Result {
        if (request.cancelled()) return Result.CANCELLED
        if (!device.available()) return Result.UNAVAILABLE
        if (action == Action.HIDE) {
            // 0, 2 and SDK sentinel/error values are not verified original display inputs.
            val original = try { device.originalValue() } catch (e: Exception) {
                AppLogger.w("NativeAdasWindow", "stage=original_read exception=${e.javaClass.simpleName}")
                return Result.SEND_FAILED
            }
            if (original != 1) return Result.ORIGINAL_UNKNOWN
            if (request.cancelled()) return Result.CANCELLED
            if (!recovery.saveOriginal()) return Result.RECOVERY_FAILED
        } else if (recovery.value() != 1) {
            return Result.RECOVERY_MISSING
        }
        if (!request.startDispatch()) return Result.CANCELLED
        device.send(if (action == Action.HIDE) HIDE_FRAME else RESTORE_FRAME)
        // The raw downlink can leave the SDK cache unchanged. Always emit a requested restore,
        // even if that cache still reads 1, and retain recovery for another manual attempt.
        return if (action == Action.HIDE) Result.HIDE_SENT else Result.RESTORE_SENT
    }

    companion object {
        const val PROPERTY = 0x23500008
        const val HIDE_FRAME = "23,50,00,08,01,00"
        const val RESTORE_FRAME = "23,50,00,08,01,01"
        val RECEIVER = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
        @Volatile private var instance: NativeAdasWindowControl? = null
        fun isBusy(): Boolean = instance?.active?.get() != null

        fun cancelPending(): Boolean {
            val owner = instance ?: return true
            owner.active.get()?.cancel()
            return owner.active.get() == null
        }

        @Synchronized fun get(context: Context): NativeAdasWindowControl {
            instance?.let { return it }
            val app = context.applicationContext
            return NativeAdasWindowControl(AndroidDevice(app), object : Recovery {
                override fun value() = ClusterPrefs.nativeAdasRestoreValue(app)
                override fun saveOriginal() = ClusterPrefs.saveNativeAdasRestoreValue(app)
            }, executor("adas-probe"), executor("adas-restore")).also { instance = it }
        }
        private fun executor(name: String) = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            SynchronousQueue(), { runnable -> Thread(runnable, "dashcast-$name").apply { isDaemon = true } })
    }
}

/** Manifest receiver delivery only: Android provides no SDK return value or rendered-state ACK. */
internal class AndroidDevice(
    private val context: Context,
    private val platformSupported: () -> Boolean = {
        supportsFirmware(Build.VERSION.SDK_INT, Platform.get().isAutoDetectedDiLink3(), Build.FINGERPRINT)
    },
) : NativeAdasWindowControl.Device {
    override fun available(): Boolean {
        if (com.byd.dashcast.clusterlive.ClusterNativeProjectionDebt.blocked(context.applicationContext) ||
            com.byd.dashcast.cluster.display.ProjectionCommandBus.diagnosticReserved()) return false
        if (!platformSupported()) return false
        return try {
            val receiver = context.packageManager.getReceiverInfo(NativeAdasWindowControl.RECEIVER, 0)
            receiver.enabled && receiver.applicationInfo.enabled && receiver.exported &&
                receiver.permission.isNullOrEmpty()
        } catch (_: Exception) { false }
    }
    override fun originalValue(): Int {
        val binder = ProxyClient.getProxyDaemonBinder() ?: error("No connected instrument reader")
        return ProxyClient.canInstrumentGetOnCurrentBinder(binder, NativeAdasWindowControl.PROPERTY)
    }
    override fun send(frame: String) {
        require(frame == NativeAdasWindowControl.HIDE_FRAME || frame == NativeAdasWindowControl.RESTORE_FRAME)
        context.sendBroadcast(Intent("com.byd.cluster.spi")
            .setComponent(NativeAdasWindowControl.RECEIVER)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("wholeFrame", frame))
    }
    companion object {
        internal const val KNOWN_FINGERPRINT = "BYD-AUTO/DiLink3.0/DiLink3.0:10/QKQ1.210910.001/eng.build.20260610.082733:user/release-keys"
        internal fun supportsFirmware(api: Int, positivelyDetectedDiLink3: Boolean, fingerprint: String): Boolean =
            api == 29 && positivelyDetectedDiLink3 && fingerprint == KNOWN_FINGERPRINT
    }
}
