package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener
import android.hardware.bydauto.setting.AbsBYDAutoSettingListener
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

// java.util.ArrayDeque explicitly: kotlin.collections.ArrayDeque is auto-imported and has no
// pollFirst(), so an unqualified name here would silently resolve to the wrong deque.
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CanFeedbackListener — registers a BYD *setting* feedback listener INSIDE the daemon
 * (uid 2000, privileged context) to capture the PUSH feedback the SDK never exposes to a
 * synchronous `get()`.
 *
 * Proven push-only (1.6.71): writing SET_HUD_MODE then reading it back via the daemon
 * returned 0 every time. The HUD/nav feedback is delivered through
 * `BYDAutoSettingDevice.registerListener(AbsBYDAutoSettingListener)` callbacks instead.
 * On-car structure discovery (1.6.72) confirmed `AbsBYDAutoSettingListener` has a no-arg
 * constructor and NO abstract methods, so subclassing is safe.
 *
 * The compile stub (`app/libs/byd-auto-api-stubs.jar`) exposes
 * `onFeatureChanged(String,int)` and `onDataChanged(IBYDAutoEvent)` (it does NOT carry the
 * device's `onDataEventChanged(int,BYDAutoEventValue)` — that's overridden at runtime only if
 * needed). At runtime ART resolves `android.*` to the bootclasspath class, so these override
 * the real callbacks.
 *
 * Everything is guarded: a failure here must NEVER crash the daemon, which also drives the
 * DL3/DL5 cluster projection. Registration is opt-in (only when the HUD diag calls it) and
 * idempotent.
 *
 * Kotlin port note: every callback parameter the FRAMEWORK fills is declared nullable. A
 * non-null parameter would compile to a `checkNotNullParameter` intrinsic that throws inside a
 * BYD push callback, where nothing would surface it — the daemon would swallow it and the
 * capture would go quiet exactly like the bug this class was rewritten to stop having.
 */
object CanFeedbackListener {

    private const val CAP = 1000

    /** HUD/nav setting feature ids to subscribe to via registerListener(listener, int[]). */
    private val SUBSCRIBE_IDS = intArrayOf(
        CanWriteVerbs.SET_HUD_MODE, CanWriteVerbs.SET_HUD_MODE_FEEDBACK,
        CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.SET_HUD_SWITCH_STATUS_FEEDBACK,
        CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS
    )

    /**
     * The event log, newest-wins.
     *
     * It used to be an `ArrayList` filled by `if (size < CAP) add(line)` — which is the opposite
     * of a ring: once full it kept the OLDEST thousand events and silently ignored everything
     * after. INC-20260826-194829 is what that produced: exactly 1000 lines covering t=0.1s to
     * t=35.9s of a six-minute session, with no banner saying the recording had stopped. The
     * report's own `[last-known]` snapshot, which is not capped, named six feature ids that
     * appear nowhere in those 1000 lines — proof the pushes had kept coming.
     *
     * A recording that goes deaf is survivable. One that goes deaf while looking complete is
     * not, so [drain] now says how many events it dropped.
     */
    private val sBuf = ArrayDeque<String>(CAP + 1)

    /** Events discarded to keep [sBuf] at [CAP]. Reset by [drain]/[clear]. */
    private var sDropped = 0

    /** our AbsBYDAutoSettingListener subclass (strong ref) */
    @Volatile private var sListener: Any? = null

    /** our AbsBYDAutoInstrumentListener subclass (strong ref) */
    @Volatile private var sInstrListener: Any? = null

    @Volatile private var sRegistered = false

    /** True while a registration runnable is posted but not yet completed. Prevents a retry
     *  during the 5s await-timeout window from posting a second runnable that would register
     *  a duplicate device listener and leak the first sink. Cleared on success AND failure. */
    @Volatile private var sRegisterInFlight = false

    /** Looper thread for register + callbacks */
    @Volatile private var sThread: HandlerThread? = null

    /** Last value pushed per feature id — persistent (survives drain). onDataEventChanged fires on
     *  CHANGE only, and the OEM nav sets the HUD mode once at nav-start; keeping the last value lets
     *  us report the CURRENT HUD mode even if it was pushed before the read window. */
    private val sLastValue: MutableMap<Int, Int> = ConcurrentHashMap()

    /** Last buffer (hex) seen per feature id — nav guidance icon/distance/road is likely a buffer. */
    private val sLastBuf: MutableMap<Int, String> = ConcurrentHashMap()

    /** When each id last wrote a BUFFER line (elapsedRealtime ms). See [HudBufferThrottlePolicy]. */
    private val sLastBufLog: MutableMap<Int, Long> = ConcurrentHashMap()

    /** Buffer pushes suppressed since that id's last recorded line — reported on the next one. */
    private val sBufSkipped: MutableMap<Int, Int> = ConcurrentHashMap()

    /** Session start (elapsedRealtime ms) — every log line is timestamped relative to this, so
     *  user-tapped ground-truth markers ([mark]) can be correlated with CAN events. */
    @Volatile private var sT0 = SystemClock.elapsedRealtime()

    private fun record(s: String) {
        val line = String.format(Locale.US, "[t=%6.1f] %s",
                (SystemClock.elapsedRealtime() - sT0) / 1000.0, s)
        synchronized(sBuf) {
            sBuf.addLast(line)
            while (sBuf.size > CAP) { sBuf.pollFirst(); sDropped++ }
        }
    }

    /** Records a user-tapped ground-truth marker (the maneuver shown on the HUD right now). */
    @JvmStatic
    fun mark(label: String) {
        record("TAP $label")
    }

    /** Clears the event log + last-known maps and resets the timestamp clock (fresh recording). */
    @JvmStatic
    fun clear() {
        synchronized(sBuf) { sBuf.clear(); sDropped = 0 }
        sLastValue.clear()
        sLastBuf.clear()
        sLastBufLog.clear()
        sBufSkipped.clear()
        sT0 = SystemClock.elapsedRealtime()
    }

    /**
     * Handles a push (int value + optional byte buffer). The rich nav guidance (turn direction,
     * distance, next road) is often carried in the byte BUFFER, not intValue (which is 0/1 for
     * many features) — so the buffer hex is logged too when present and changed.
     *
     * Changed-payload dedup is necessary but not sufficient: it identifies an EVENT and does
     * nothing against a STREAM. 0x99000198 pushes at ~25 Hz with a fast tick inside its six bytes,
     * so its hex differs every time and it took 903 of the 1000 buffer lines in
     * INC-20260826-194829. Buffer lines are therefore rate-limited per id by
     * [HudBufferThrottlePolicy], and each recorded line carries the count it stands for. A change
     * of the INTEGER value is never throttled — that is a state change, not a sample.
     */
    private fun onEvent(featureId: Int, value: BYDAutoEventValue?) {
        val v = if (value != null) value.intValue else Int.MIN_VALUE
        var buf: ByteArray? = null
        try { buf = value?.bufferDataValue } catch (ignore: Throwable) {}
        val prev = sLastValue.put(featureId, v)
        val intChanged = (prev == null || prev != v)
        if (buf != null && buf.size > 0) {
            val hex = toHex(buf)
            val pb = sLastBuf.put(featureId, hex)
            if (hex != pb || intChanged) {
                val now = SystemClock.elapsedRealtime()
                val last = sLastBufLog[featureId]
                if (!HudBufferThrottlePolicy.shouldRecord(
                                intChanged, if (last == null) 0L else last, now)) {
                    val seen = sBufSkipped[featureId]
                    sBufSkipped[featureId] = if (seen == null) 1 else seen + 1
                    return
                }
                sLastBufLog[featureId] = now
                val seen = sBufSkipped.remove(featureId)
                record(String.format(Locale.US, "evt 0x%08X=%d buf=%s%s", featureId, v, hex,
                        HudBufferThrottlePolicy.sinceSuffix(seen ?: 0)))
                return
            }
            return
        }
        if (intChanged) record(String.format(Locale.US, "evt 0x%08X=%d", featureId, v))
    }

    private fun toHex(b: ByteArray): String {
        val n = Math.min(b.size, 48)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append(String.format(Locale.US, "%02x", b[i].toInt() and 0xff))
        if (b.size > n) sb.append("..").append(b.size)
        return sb.toString()
    }

    /**
     * Our concrete listener — records whatever the framework pushes via the generic
     * `onFeatureChanged(String,int)` callback. We do NOT override `onDataChanged(IBYDAutoEvent)`
     * — it is `final` on the real device (the internal dispatcher), and overriding it throws a
     * [LinkageError] at class load (proven 1.6.80). The overridable surface is the `onXxxChanged`
     * family.
     */
    private class SettingSink : AbsBYDAutoSettingListener() {
        override fun onFeatureChanged(feature: String?, value: Int) {
            record("feat " + feature + "=" + value)
        }
    }

    /**
     * Adds `onDataEventChanged(int, BYDAutoEventValue)` — the most likely per-feature delivery
     * callback (1.6.83's onFeatureChanged captured nothing even when subscribed to the ids). It is
     * NOT in the stub, so it is declared WITHOUT `override` — at runtime it overrides the real
     * method by name+descriptor. It MIGHT be `final` on-device (like onDataChanged); if so this
     * class fails to load with a [LinkageError], which [startSetting] catches to fall back to
     * [SettingSink] — so capture is never disabled entirely.
     */
    private class EventSink : AbsBYDAutoSettingListener() {
        override fun onFeatureChanged(feature: String?, value: Int) {
            record("feat " + feature + "=" + value)
        }
        fun onDataEventChanged(featureId: Int, value: BYDAutoEventValue?) {
            onEvent(featureId, value)
        }
    }

    /**
     * Same, but for the INSTRUMENT device — nav GUIDANCE (turn arrows, distances, next-road) is
     * pushed here, not on the setting device. Registered so a capture WHILE DRIVING a route with
     * turns reveals which instrument feature ids fire for each maneuver → the protocol to feed nav
     * to the HUD ourselves. onDataEventChanged declared without `override` (runtime override).
     */
    private class InstrumentSink : AbsBYDAutoInstrumentListener() {
        fun onDataEventChanged(featureId: Int, value: BYDAutoEventValue?) {
            onEvent(featureId, value)
        }
    }

    /**
     * Registers the setting listener once (idempotent). Uses reflection for getInstance /
     * registerListener (matching [CanWriteVerbs]) so we don't depend on the stub's exact method
     * signatures; only the listener subclass needs the compile stub.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @return a short status string
     */
    @JvmStatic
    @Synchronized
    fun startSetting(wrappedCtx: Context): String {
        if (sRegistered) return "already-registered"
        // A previous attempt's runnable may still be in flight after its caller's 5s await
        // timed out — don't post a second one (it would register a duplicate device listener).
        if (sRegisterInFlight) return "register-in-flight"
        try {
            var thread = sThread
            if (thread == null) {
                thread = HandlerThread("can-feedback-listener")
                thread.start()
                sThread = thread
            }
            // The BYD listener creates a Handler internally → it MUST be built/registered on a
            // thread that has a Looper (the daemon's binder thread has none → NPE). Do it on our
            // dedicated HandlerThread; callbacks are then delivered on that Looper too.
            val h = Handler(thread.looper)
            val result = arrayOf("no-result")
            val latch = CountDownLatch(1)
            sRegisterInFlight = true
            h.post {
                try {
                    val cls = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
                    val dev = cls.getMethod("getInstance", Context::class.java).invoke(null, wrappedCtx)
                    if (dev == null) {
                        result[0] = "ERR getInstance() null"
                    } else {
                        // Try the EventSink (adds onDataEventChanged) — its class load throws a
                        // LinkageError if onDataEventChanged is final on-device; fall back to the
                        // onFeatureChanged-only sink so capture is never disabled entirely.
                        var sink: AbsBYDAutoSettingListener
                        var sinkKind: String
                        try {
                            sink = EventSink()
                            sinkKind = "EventSink"
                        } catch (le: Throwable) {
                            sink = SettingSink()
                            sinkKind = "SettingSink(EventSink load failed: " + le.javaClass.simpleName + ")"
                        }
                        // Prefer registerListener(listener) = ALL setting features: the filtered
                        // variant (our 5 HUD/nav ids) captured our own SET_HUD_MODE writes + the switch
                        // toggle, but NOTHING when a tester changes the HUD display mode in the car's
                        // HUD settings — because that user setting almost certainly uses a DIFFERENT
                        // feature id we weren't subscribed to. Subscribe to everything (EventSink's
                        // onDataEventChanged gives featureId+value) so we discover which id fires.
                        var how: String
                        try {
                            cls.getMethod("registerListener", AbsBYDAutoSettingListener::class.java)
                               .invoke(dev, sink)
                            how = "all features"
                        } catch (nsme: NoSuchMethodException) {
                            cls.getMethod("registerListener",
                                    AbsBYDAutoSettingListener::class.java, IntArray::class.java)
                               .invoke(dev, sink, SUBSCRIBE_IDS)
                            how = "filtered ids"
                        }
                        sListener = sink
                        sRegistered = true
                        val instr = registerInstrument(wrappedCtx)
                        result[0] = "registered (" + how + ", " + sinkKind +
                                ", looper thread) + instrument[" + instr + "]"
                    }
                } catch (t: Throwable) {
                    result[0] = "ERR " + describe(t)
                } finally {
                    // Clear on BOTH success and failure: a genuinely-failed register can be
                    // retried, but a still-pending one blocks a duplicate post above. On
                    // success sRegistered=true is already set, so retries short-circuit there.
                    sRegisterInFlight = false
                    latch.countDown()
                }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) return "ERR register timeout"
            return result[0]
        } catch (t: Throwable) {
            return "ERR(outer) " + describe(t)
        }
    }

    /**
     * Registers the INSTRUMENT-device listener (all features) so nav guidance events are captured
     * too. Runs on the shared Looper thread's caller (already on it). Fully guarded — a failure here
     * must not disable the setting listener. Returns a short status string.
     */
    private fun registerInstrument(wrappedCtx: Context): String {
        if (sInstrListener != null) return "already"
        try {
            val icls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            val idev = icls.getMethod("getInstance", Context::class.java).invoke(null, wrappedCtx)
                    ?: return "getInstance null"
            val isink: InstrumentSink
            try {
                isink = InstrumentSink()
            } catch (le: Throwable) {
                return "sink load failed: " + le.javaClass.simpleName
            }
            val lcls = AbsBYDAutoInstrumentListener::class.java
            try {
                icls.getMethod("registerListener", lcls).invoke(idev, isink)
            } catch (nsme: NoSuchMethodException) {
                icls.getMethod("registerListener", lcls, IntArray::class.java)
                    .invoke(idev, isink, SUBSCRIBE_IDS)
            }
            sInstrListener = isink
            return "all features"
        } catch (t: Throwable) {
            return "ERR " + describe(t)
        }
    }

    /** Unwraps InvocationTargetException and appends the top stack frames, so the cause is visible. */
    private fun describe(t: Throwable): String {
        val r = if (t is java.lang.reflect.InvocationTargetException && t.cause != null) t.cause!! else t
        val sb = StringBuilder(r.javaClass.name)
        if (r.message != null) sb.append(": ").append(r.message)
        val st = r.stackTrace
        if (st != null && st.size > 0) sb.append(" @ ").append(st[0])
        if (st != null && st.size > 1) sb.append(" <- ").append(st[1])
        return sb.toString()
    }

    /**
     * Returns the new events since the last drain (cleared) PLUS the persistent per-feature
     * last-known snapshot (NOT cleared) — so a stable value pushed before this window (e.g. the
     * OEM nav's HUD mode set at nav-start) is still reported.
     */
    @JvmStatic
    fun drain(): String {
        val sb = StringBuilder()
        synchronized(sBuf) {
            // First line, not a footnote: a reader who does not know the window closed will date
            // the whole session from the last timestamp they see.
            if (sDropped > 0) {
                sb.append("[capped — ").append(sDropped)
                  .append(" event(s) dropped, the ").append(sBuf.size)
                  .append(" most recent kept]\n")
            }
            for (s in sBuf) sb.append(s).append('\n')
            sBuf.clear()
            sDropped = 0
        }
        if (!sLastValue.isEmpty()) {
            sb.append("[last-known]")
            for (e in sLastValue.entries) {
                sb.append(String.format(Locale.US, " 0x%08X=%d", e.key, e.value))
            }
            sb.append('\n')
        }
        return if (sb.length == 0) "(no events)" else sb.toString()
    }
}
