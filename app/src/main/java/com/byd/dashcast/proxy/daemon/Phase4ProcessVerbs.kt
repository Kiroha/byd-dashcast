package com.byd.dashcast.proxy.daemon

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel

import java.io.File
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale

/**
 * Phase4ProcessVerbs — process-management verbs that run inside the daemon
 * process (uid 2000).
 *
 * Covers three concern groups:
 *  1. **`/proc` scan** — pure-Java `pidof` replacement ([getPidsByPackage]).
 *  2. **AutoContainer** — typed Binder transactions to the BYD `AutoContainer` service
 *     ([autoContainerSendInfo], [autoContainerSendInfo2], [autoContainerRegisterCallback]).
 *  3. **IActivityManager** — `forceStopPackage` via reflection.
 *
 * Kotlin port note: every `synchronized` block below deliberately locks on
 * `Phase4ProcessVerbs::class.java`, NOT on the object instance. Five methods and the
 * DeathRecipient share that one monitor, and `@Synchronized` on an object's members would
 * have silently moved them to a different lock — the caches would still look correct and
 * would race only under a binder death.
 *
 * @see Phase4DisplayVerbs
 * @see Phase4TaskVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
object Phase4ProcessVerbs {

    // ─── AutoContainer cache ──────────────────────────────────────────────

    private const val AUTOCONTAINER_SVC = "AutoContainer"
    private const val TXN_SEND_INFO = 2
    private const val TXN_SEND_INFO2 = 3
    private const val TXN_REGISTER_CALLBACK = 4

    /** The monitor every cache in this file shares — the Java `synchronized (Phase4ProcessVerbs.class)`. */
    private val LOCK = Phase4ProcessVerbs::class.java

    /**
     * The binder and the descriptor it advertised, together.
     *
     * They used to be two independent static fields, read at two different moments, and that cost
     * correctness twice. The `catch { return b; }` on linkToDeath below returned a usable binder
     * while never assigning the descriptor, so on a ROM where linkToDeath fails every subsequent
     * transaction wrote a NULL interface token. And even on the happy path, binderDied() could null
     * the descriptor between a caller obtaining the binder and reading the descriptor for it.
     * One immutable pair, resolved once, closes both.
     */
    private class Container(val binder: IBinder, val descriptor: String)

    @Volatile private var sAutoContainer: Container? = null

    private val sAutoContainerDeath = IBinder.DeathRecipient {
        synchronized(LOCK) {
            sAutoContainer = null
        }
    }

    /**
     * Resolve (and cache) the live IBinder for the `AutoContainer` service.
     * Descriptor is read at runtime so OEM rebrands still work.
     * Cache is invalidated via a DeathRecipient when the host process dies.
     */
    @Throws(Throwable::class)
    private fun autoContainer(): Container {
        var c = sAutoContainer
        if (c != null && c.binder.isBinderAlive) return c
        synchronized(LOCK) {
            c = sAutoContainer
            val cached = c
            if (cached != null && cached.binder.isBinderAlive) return cached
            val sm = Class.forName("android.os.ServiceManager")
            val b = sm.getMethod("getService", String::class.java)
                    .invoke(null, AUTOCONTAINER_SVC) as IBinder?
                    ?: throw IllegalStateException("no '$AUTOCONTAINER_SVC' service")
            val descr: String?
            val d0 = Parcel.obtain()
            val r0 = Parcel.obtain()
            try {
                b.transact(IBinder.INTERFACE_TRANSACTION, d0, r0, 0)
                descr = r0.readString()
            } finally {
                r0.recycle()
                d0.recycle()
            }
            if (descr == null || descr.isEmpty()) {
                throw IllegalStateException("$AUTOCONTAINER_SVC advertised empty descriptor")
            }
            val fresh = Container(b, descr)
            // linkToDeath is best-effort: it throws when the binder died between getService and
            // here. The pair is still complete and usable, so hand it back — it is simply not
            // cached, and the isBinderAlive() check above re-resolves on the next call. What must
            // NOT happen, and used to, is returning a binder with no descriptor to go with it.
            try {
                b.linkToDeath(sAutoContainerDeath, 0)
            } catch (t: Throwable) {
                return fresh
            }
            sAutoContainer = fresh
            return fresh
        }
    }

    // ─── IActivityManager cache ───────────────────────────────────────────

    @Volatile private var sActivityManager: Any? = null
    @Volatile private var sForceStopPackage: Method? = null

    @Throws(Throwable::class)
    private fun activityManager(): Any {
        var am = sActivityManager
        if (am != null) return am
        synchronized(LOCK) {
            am = sActivityManager
            val cached = am
            if (cached != null) return cached
            am = try {
                val c = Class.forName("android.app.ActivityManager")
                c.getMethod("getService").invoke(null)
            } catch (ignore: Throwable) {
                val c = Class.forName("android.app.ActivityManagerNative")
                c.getMethod("getDefault").invoke(null)
            }
            val resolved = am ?: throw IllegalStateException("no IActivityManager")
            sActivityManager = resolved
            return resolved
        }
    }

    @Throws(Throwable::class)
    private fun forceStopMethod(am: Any): Method {
        var m = sForceStopPackage
        if (m != null) return m
        synchronized(LOCK) {
            m = sForceStopPackage
            val cached = m
            if (cached != null) return cached
            var found: Method? = null
            for (cand in am.javaClass.methods) {
                if ("forceStopPackage" != cand.name) continue
                val pt = cand.parameterTypes
                if (pt.size == 2 && pt[0] == String::class.java && pt[1] == Int::class.javaPrimitiveType) {
                    found = cand; break
                }
            }
            val resolved = found ?: throw NoSuchMethodException("no forceStopPackage(String,int)")
            sForceStopPackage = resolved
            return resolved
        }
    }

    // ─── /proc scan buffer ───────────────────────────────────────────────

    private val sCmdlineBuf: ThreadLocal<ByteArray> = ThreadLocal.withInitial { ByteArray(256) }

    // ─── Verbs ────────────────────────────────────────────────────────────

    /**
     * Equivalent of `pidof <packageName>` — pure-Java scan of `/proc/<pid>/cmdline`.
     *
     * Probe P8 (build 173): returns in < 1 ms with 241 live processes on BYD Seal EU.
     * Replaces a `sh -c "pidof …"` fork (48–181 ms).
     *
     * @return space-separated PIDs, or `""` if none match.
     */
    @JvmStatic
    fun getPidsByPackage(packageName: String?): String {
        if (packageName == null || packageName.isEmpty()) return ""
        val dirs = File("/proc").listFiles() ?: return ""
        // ThreadLocal.get() is @Nullable in Kotlin's view of the JDK (a ThreadLocal with no
        // initialValue does return null); withInitial above guarantees a buffer, so this
        // cannot be null and the assertion throws the same NPE the Java would have.
        val buf = sCmdlineBuf.get()!!
        var pids: StringBuilder? = null
        for (d in dirs) {
            val name = d.name
            if (!d.isDirectory) continue
            var numeric = true
            var i = 0
            val n = name.length
            while (i < n) {
                val c = name[i]
                if (c < '0' || c > '9') { numeric = false; break }
                i++
            }
            if (!numeric) continue
            val cmd = File(d, "cmdline")
            if (!cmd.canRead()) continue
            val read: Int
            try {
                read = FileInputStream(cmd).use { fis -> fis.read(buf) }
            } catch (ignore: Throwable) { continue }
            if (read <= 0) continue
            var end = 0
            while (end < read && buf[end].toInt() != 0) end++
            if (end == 0) continue
            // Java's new String(byte[],int,int) uses Charset.defaultCharset(); on Android that is
            // always UTF-8, so naming it explicitly is the same decode and not a platform-dependent
            // one. Kotlin has no charset-less ByteArray constructor, so it has to be named.
            val argv0 = String(buf, 0, end, Charsets.UTF_8)
            if (argv0 == packageName
                    || (argv0.length > packageName.length
                        && argv0.startsWith(packageName)
                        && argv0[packageName.length] == ':')) {
                if (pids == null) pids = StringBuilder(name.length)
                else pids.append(' ')
                pids.append(name)
            }
        }
        return pids?.toString() ?: ""
    }

    /**
     * Equivalent of `service call AutoContainer 2 i32 type i32 info s16 str`.
     *
     * Probe P13 (build 176): `transact(2, …)` accepted from uid 2000 on BYD Seal EU with
     * descriptor `android.os.IAutoContainer`.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun autoContainerSendInfo(type: Int, info: Int, str: String?) {
        transactAutoContainerSendInfo(type, info, str, false)
    }

    /** Same transaction as [autoContainerSendInfo], preserving the native result code. */
    @JvmStatic
    @Throws(Throwable::class)
    fun autoContainerSendInfoResult(type: Int, info: Int, str: String?): Int =
            transactAutoContainerSendInfo(type, info, str, true)

    @Throws(Throwable::class)
    private fun transactAutoContainerSendInfo(type: Int, info: Int, str: String?,
                                              readResult: Boolean): Int {
        val c = autoContainer()
        val b = c.binder
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(c.descriptor)
            data.writeInt(type)
            data.writeInt(info)
            data.writeString(str ?: "")
            if (!b.transact(TXN_SEND_INFO, data, reply, 0)) {
                throw IllegalStateException("AutoContainer sendInfo transaction not handled")
            }
            reply.readException()
            if (!readResult) return 0
            if (reply.dataAvail() < Int.SIZE_BYTES) {
                throw IllegalStateException("AutoContainer sendInfo returned no result code")
            }
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Equivalent of `AutoContainer.sendInfo2(type, data)` (AIDL transaction 3) — same binder
     * the OEM's own nav app uses to push a serialized `NaviInfo` FlatBuffer (type=4) to the
     * HUD. Reuses the cached binder resolved by [autoContainer].
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun autoContainerSendInfo2(type: Int, data: ByteArray?) {
        val c = autoContainer()
        val b = c.binder
        val dataParcel = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            dataParcel.writeInterfaceToken(c.descriptor)
            dataParcel.writeInt(type)
            dataParcel.writeByteArray(data)
            if (!b.transact(TXN_SEND_INFO2, dataParcel, reply, 0)) {
                throw IllegalStateException("AutoContainer sendInfo2 transaction not handled")
            }
            reply.readException()
        } finally {
            reply.recycle()
            dataParcel.recycle()
        }
    }

    /**
     * Force-stop a package via IActivityManager reflection.
     *
     * Retries once on a dead proxy. The AutoContainer cache a few lines up invalidates itself
     * through a DeathRecipient; this one never did, so after a system_server restart — which this
     * daemon outlives, being a separate shell-started process — the cached proxy stayed dead and
     * every force-stop failed silently until the daemon itself respawned. Force-stopping a package
     * is idempotent, so a single retry costs nothing when the first attempt genuinely landed.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun forceStopPackage(packageName: String?, userId: Int) {
        if (packageName == null || packageName.isEmpty()) {
            throw IllegalArgumentException("packageName empty")
        }
        try {
            invokeForceStop(packageName, userId)
        } catch (t: Throwable) {
            if (!isDeadObject(t)) throw t
            synchronized(LOCK) {
                sActivityManager = null
                sForceStopPackage = null   // the Method belongs to the dead proxy's class
            }
            invokeForceStop(packageName, userId)
        }
    }

    @Throws(Throwable::class)
    private fun invokeForceStop(packageName: String, userId: Int) {
        val am = activityManager()
        val m = forceStopMethod(am)
        try {
            m.invoke(am, packageName, userId)
        } catch (ite: InvocationTargetException) {
            // Unwrap so the caller — and isDeadObject above — sees the real failure, not the
            // reflection wrapper.
            throw ite.cause ?: ite
        }
    }

    /** True for a binder that died under us, at any depth of the cause chain. */
    private fun isDeadObject(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is DeadObjectException) return true
            if (c.javaClass.name.endsWith("DeadSystemException")) return true
            if (c === c.cause) break
            c = c.cause
        }
        return false
    }

    // ─── AutoContainer callback (diagnostic, never called before this release) ────────────

    /** Kept alive for the daemon process lifetime — a dropped local reference would not by
     *  itself unregister the far side, but there is no reason to rely on that; a static field
     *  costs nothing and removes the question. Re-armed on every daemon respawn (a fresh
     *  process has no memory of a prior registration — the OEM service does not persist it
     *  across our process death either, since it is keyed by this binder's own identity). */
    @Volatile private var sRegisteredCallback: ContainerCallbackBinder? = null

    /** Returned by [autoContainerRegisterCallback] when the native reply carries no result int at
     *  all — distinct from any real result code (including 0, which is meaningful elsewhere in this
     *  file: [autoContainerSendInfoResult] uses it for "accepted"). The reply shape for this
     *  specific call was never confirmed on-car; silently defaulting to 0 would make "genuinely
     *  accepted" and "reply layout guess is wrong" indistinguishable. */
    const val REGISTER_CALLBACK_NO_RESULT_FIELD = Int.MIN_VALUE

    /**
     * Registers this daemon's own callback with `AutoContainer.registerCallback`
     * (AIDL transaction 4 — documented in `ClusterManager.kt` since the DL3 RE pass that
     * found it, never called until now). Every push the native service makes afterward
     * (`serviceDied`/`receivedJson/Info/Info2`) is logged into the daemon's own transcript for
     * as long as this process lives — diagnostic only, does not feed any production code path.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun autoContainerRegisterCallback(): Int {
        var cb = sRegisteredCallback
        if (cb == null) {
            synchronized(LOCK) {
                cb = sRegisteredCallback
                if (cb == null) {
                    cb = ContainerCallbackBinder()
                    sRegisteredCallback = cb
                }
            }
        }
        val c = autoContainer()
        val b = c.binder
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(c.descriptor)
            data.writeStrongBinder(cb)
            if (!b.transact(TXN_REGISTER_CALLBACK, data, reply, 0)) {
                throw IllegalStateException("AutoContainer registerCallback transaction not handled")
            }
            reply.readException()
            return if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt()
                   else REGISTER_CALLBACK_NO_RESULT_FIELD
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Raw Binder (not the hidden `IContainerCallback$Stub`) receiving pushes from the OEM's
     * AIDL-generated proxy. Transaction codes below (1=serviceDied, 2=receivedJson,
     * 3=receivedInfo, 4=receivedInfo2) are inferred from the callback implementation's method
     * order in the OEM's own decompiled bytecode, NOT independently confirmed against the AIDL
     * compiler's actual numbering — every call therefore logs its raw code and a hex dump of the
     * untouched data first, so a wrong guess here can still be decoded later from a bug report.
     * Rate-limited: a dying/reconnecting native service could otherwise flood the one log section
     * that is captured in full (unlike logcat, which the project has already lost evidence to
     * once from an unrelated flood — see Phase 0 / INC-20260804-171617).
     */
    private class ContainerCallbackBinder : Binder() {
        private var mLastLogAt: Long = 0
        private var mSuppressedSinceLog = 0

        @Synchronized
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // marshall() is ILLEGAL on a parcel carrying binder objects, and it sat OUTSIDE the
            // try below — so a push that happened to carry one threw out of onTransact, losing the
            // log line this callback exists for and answering the OEM with an exception instead of
            // writeNoException(). Exactly the defect AUD-225 fixed in FissionHostSvcVerbs; it was
            // corrected there and left here. Best-effort now, and the throw is itself evidence: it
            // can only happen when a Binder is present.
            val rawHex: String = try {
                val raw = data.marshall()
                "raw(" + raw.size + "B)=" + toHex(raw)
            } catch (marshallFail: Throwable) {
                "raw=<unavailable: " + marshallFail.javaClass.simpleName +
                        " — the push carries binder objects>"
            }
            data.setDataPosition(0)
            val now = System.currentTimeMillis()
            val logIt = (now - mLastLogAt) >= MIN_LOG_INTERVAL_MS
            if (logIt) {
                if (mSuppressedSinceLog > 0) {
                    ProxyDaemonMain.log("AutoContainer callback: (" + mSuppressedSinceLog +
                            " earlier push(es) suppressed, min " + MIN_LOG_INTERVAL_MS + "ms apart)")
                }
                mLastLogAt = now
                mSuppressedSinceLog = 0
                val sb = StringBuilder("AutoContainer callback: code=").append(code)
                        .append(' ').append(rawHex)
                try {
                    // Skip whatever the caller wrote as an interface token — not validated against
                    // any expected descriptor, just consumed so the following best-effort reads
                    // line up. writeInterfaceToken()'s real wire format is TWO fields, not one: a
                    // leading int32 (IPCThreadState strict-mode policy word) THEN the String16
                    // descriptor — a lone readString() misreads that policy int as the string's
                    // length prefix and desyncs every field that follows (caught by review).
                    data.readInt()
                    data.readString()
                    when (code) {
                        1 -> sb.append(" [serviceDied()]")
                        2 -> sb.append(" [receivedJson type=").append(data.readInt())
                               .append(" json=").append(data.readString()).append(']')
                        3 -> sb.append(" [receivedInfo type=").append(data.readInt())
                               .append(" infoInt=").append(data.readInt())
                               .append(" infoStr=").append(data.readString()).append(']')
                        4 -> {
                            val t = data.readInt()
                            val payload = data.createByteArray()
                            sb.append(" [receivedInfo2 type=").append(t)
                              .append(" dataLen=").append(if (payload == null) -1 else payload.size)
                              .append(']')
                        }
                        else -> sb.append(" [unknown code — see raw hex above]")
                    }
                } catch (decodeFail: Throwable) {
                    sb.append(" (decode failed: ").append(decodeFail.javaClass.simpleName).append(')')
                }
                ProxyDaemonMain.log(sb.toString())
            } else {
                mSuppressedSinceLog++
            }
            if ((flags and IBinder.FLAG_ONEWAY) == 0 && reply != null) {
                reply.writeNoException()
            }
            return true
        }

        companion object {
            private const val MIN_LOG_INTERVAL_MS = 5_000L

            // x stays a Byte on purpose: java.util.Formatter renders a negative Byte for %x as
            // value + 2^8, i.e. the unsigned byte. Widening it to Int would print ffffffff.
            private fun toHex(b: ByteArray): String {
                val sb = StringBuilder(b.size * 2)
                for (x in b) sb.append(String.format(Locale.ROOT, "%02x", x))
                return sb.toString()
            }
        }
    }
}
