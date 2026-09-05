package com.byd.dashcast.proxy.daemon

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.Locale

/**
 * FissionHostSvcVerbs — read-only probe of the native `FissionHostSvc` Binder service
 * (from `libfission_services.so`, DL3 only — never observed on DL4/DL5.0/DL5.1/AAOS).
 *
 * Reverse-engineered from a real DL3 pull (disassembly of `BnFissionHostService`, confirmed by a
 * second, independent binary: the OEM's own `AutoContainerNative::getQtProjectionDispInfoNative`
 * calls this exact service with this exact transaction code to feed the Qt cluster renderer). Its
 * `getAutoCarDisplay` (transaction 101) is a pure getter — copies 5 in-memory fields (type, name,
 * width, height, a producer Binder) into the reply with no side effect — reading it can never
 * disturb whatever currently owns the registry.
 *
 * **What this does NOT do**: no interface descriptor for this native service has been confirmed
 * (unlike `AutoContainer`, a hidden Java AIDL interface whose descriptor can be queried live via
 * `INTERFACE_TRANSACTION` the same way [Phase4ProcessVerbs] already does — that trick does not
 * apply here because the RE evidence is ambiguous about whether this hand-rolled C++ service even
 * validates an interface token). The reply layout (int, String16, int, int, StrongBinder) is a
 * disassembly READING, not a spec — every call therefore returns the raw reply bytes as hex
 * FIRST, unconditionally, so a wrong decode never loses the evidence.
 */
internal object FissionHostSvcVerbs {

    private const val SVC_NAME = "FissionHostSvc"
    private const val TXN_GET_AUTOCAR_DISPLAY = 101

    @Volatile
    private var sBinder: IBinder? = null

    private val sDeath = IBinder.DeathRecipient { sBinder = null }

    /** @return the live binder, or `null` if the service is not registered on this ROM. */
    private fun resolve(): IBinder? {
        var b = sBinder
        if (b != null && b.isBinderAlive) return b
        synchronized(FissionHostSvcVerbs::class.java) {
            b = sBinder
            val cached = b
            if (cached != null && cached.isBinderAlive) return cached
            b = try {
                val sm = Class.forName("android.os.ServiceManager")
                sm.getMethod("getService", String::class.java).invoke(null, SVC_NAME) as IBinder?
            } catch (ignore: Throwable) {
                return null
            }
            val resolved = b ?: return null
            try { resolved.linkToDeath(sDeath, 0) } catch (ignore: Throwable) { /* still usable */ }
            sBinder = resolved
            return resolved
        }
    }

    /**
     * Reads the registry once. Never throws for "service absent" — that IS the diagnostic answer
     * on a non-DL3 car — only for a genuine transport failure.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun getAutoCarDisplay(): String {
        val b = resolve()
            ?: return "SERVICE NOT FOUND: '" + SVC_NAME + "' — not registered on this ROM" +
                    " (expected on DL4/DL5.0/DL5.1/AAOS; expected PRESENT on DL3)"
        return sampleOnce(b)
    }

    /** One read + decode, shared by [getAutoCarDisplay] and the tracer. */
    @Throws(Throwable::class)
    private fun sampleOnce(b: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // No confirmed descriptor for this native service (see class doc) — the token is
            // written defensively, matching the RE author's own advice, and costs nothing since
            // there are no further args to misalign if the server does not consume it.
            data.writeInterfaceToken("android.IFissionHostService")
            val handled = b.transact(TXN_GET_AUTOCAR_DISPLAY, data, reply, 0)
            if (!handled) return "NOT HANDLED (transact returned false — wrong code or dead service)"
            val out = StringBuilder()
            // marshall() is ILLEGAL on a parcel that carries binder objects, and the layout this
            // probe was written for ends in a producer Binder. So on the one outcome that matters
            // — a populated registry — this line threw and took the whole probe with it, leaving
            // "SERVICE NOT FOUND"-shaped noise instead of the evidence the class doc promises
            // ("the raw reply bytes FIRST, unconditionally, so a wrong decode never loses the
            // evidence"). Best-effort now, and the throw is itself a finding: it can only happen
            // when a Binder is present, i.e. when something IS registered.
            try {
                val raw = reply.marshall()
                out.append("raw(").append(raw.size).append("B)=").append(toHex(raw))
            } catch (marshallFail: Throwable) {
                out.append("raw=<unavailable: ").append(marshallFail.javaClass.simpleName)
                   .append(" — the reply carries binder objects, so a producer IS present>")
            }
            out.append(" sz=").append(reply.dataSize())
            reply.setDataPosition(0)
            // Best-effort decode, per the disassembly-read layout. Any failure here is reported
            // alongside the raw hex above, never in place of it.
            try {
                val type = reply.readInt()
                val name = reply.readString()
                val w = reply.readInt()
                val h = reply.readInt()
                val producer = reply.readStrongBinder()
                out.append(" | decoded: type=").append(type)
                   .append(" name=").append(if (name == null) "<null>" else "\"" + name + "\"")
                   .append(" w=").append(w).append(" h=").append(h)
                   .append(" producer=").append(if (producer == null) "absent" else "present")
                if (type <= 0 && (name == null || name.isEmpty())) {
                    out.append(" [registry looks EMPTY — nothing currently registered]")
                }
            } catch (decodeFail: Throwable) {
                out.append(" | decode FAILED (layout guess may be wrong): ")
                   .append(decodeFail.javaClass.simpleName)
            }
            return out.toString()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(Locale.ROOT, "%02x", x))
        return sb.toString()
    }

    // ─── Tracer: sample the registry across a normal projection cycle ─────

    private const val TRACE_INTERVAL_MS = 2_000L
    private const val TRACE_HARD_CAP_MS = 90_000L

    @Volatile
    private var sTraceThread: Thread? = null
    private val sTraceLog = StringBuilder()
    private val sTraceLock = Any()

    /** Arms the sampler. Idempotent — a second call while one is already running is a no-op. */
    @JvmStatic
    fun startTrace() {
        synchronized(sTraceLock) {
            val running = sTraceThread
            if (running != null && running.isAlive) return
            sTraceLog.setLength(0)
            val t = Thread({ runTrace() }, "fission-autocar-trace")
            t.isDaemon = true
            sTraceThread = t
            t.start()
        }
    }

    /** Stops the sampler (if still running) and returns everything recorded. */
    @JvmStatic
    fun drainTrace(): String {
        val t = sTraceThread
        t?.interrupt()
        synchronized(sTraceLock) {
            return if (sTraceLog.isEmpty()) "(no changes recorded)" else sTraceLog.toString()
        }
    }

    /**
     * The [TRACE_HARD_CAP_MS] check only runs between samples — `transact()` has no per-call
     * timeout, so a misbehaving or wrong service registered under this name could block this
     * thread past the cap, for the rest of the daemon's life. Accepted: this is a dedicated
     * background thread (not one of the daemon's own Binder-pool workers, so it cannot starve
     * client requests), the scenario requires a service actively misusing the "FissionHostSvc"
     * name, and `interrupt()` cannot unblock a thread parked in a native Binder call anyway.
     */
    private fun runTrace() {
        val startedAt = SystemClock.elapsedRealtime()
        val b = resolve()
        if (b == null) {
            append("t=0s SERVICE NOT FOUND — aborting trace (not DL3, or SELinux blocks lookup)")
            return
        }
        var last: String? = null
        while (SystemClock.elapsedRealtime() - startedAt < TRACE_HARD_CAP_MS) {
            if (Thread.currentThread().isInterrupted) break
            val sample: String = try {
                sampleOnce(b)
            } catch (t: Throwable) {
                "PROBE ERROR: " + t.javaClass.simpleName + ": " + t.message
            }
            if (sample != last) {
                val tSec = (SystemClock.elapsedRealtime() - startedAt) / 1000
                append("t=" + tSec + "s " + sample)
                last = sample
            }
            try { Thread.sleep(TRACE_INTERVAL_MS) }
            catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
    }

    private fun append(line: String) {
        synchronized(sTraceLock) {
            if (sTraceLog.isNotEmpty()) sTraceLog.append('\n')
            sTraceLog.append(line)
        }
    }
}
