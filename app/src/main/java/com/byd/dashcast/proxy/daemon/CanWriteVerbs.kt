package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.os.SystemClock
import android.util.Log

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * CanWriteVerbs — CAN bus write verbs that run inside the daemon process (uid 2000).
 *
 * All calls use reflection to reach `BYDAutoInstrumentDevice.set(int[], BYDAutoEventValue)` —
 * the API that unlocks cluster HUD writes. That method is NOT exposed in the public DiLink 3
 * stubs (which only carry `AbsBYDAutoDevice.set(int device, int event, int value)`), so
 * reflection is the only portable path, matching how OpenBYD 2.2 calls the same surface.
 *
 * The `wrappedCtx` parameter must be a context whose permission checks all return
 * `PERMISSION_GRANTED`. In the daemon this is `ProxyDaemonMain.sWrappedContext` (system context
 * wrapped by [com.byd.dashcast.proxy.SystemContextHelper.wrap]). Without this wrapper the SDK
 * internally rejects write calls even from a system-signed process.
 *
 * Reflection objects are cached after the first successful call: Class, Field, and Method
 * lookups are one-shot, thread-safe (double-checked locking), and survive for the daemon
 * process lifetime. The `BYDAutoInstrumentDevice` singleton is also cached — it is
 * process-wide, just like any other Android system-service proxy.
 *
 * Feature ID constants are copied verbatim from the OpenBYD 2.2 RE (CarControlImpl.java).
 * They encode both the CAN device type and the event ID in a single opaque integer.
 * Use the high-level helpers in [com.byd.dashcast.system.CanBusController] rather than
 * passing raw IDs unless you know exactly what you are doing.
 *
 * Kotlin port note: the 28 feature ids below are a hardware contract and were transposed
 * mechanically from the Java, then diffed against the Batch-0 baseline. Every `synchronized`
 * block locks [LOCK] = `CanWriteVerbs::class.java`, the Class monitor the Java used — eleven
 * double-checked caches share it, and `@Synchronized` on an object's members would have moved
 * them to the instance monitor instead.
 *
 * @see com.byd.dashcast.system.CanBusController
 * @since v1.4.7-beta (Phase CAN-1)
 */
object CanWriteVerbs {

    /** The monitor every cache in this file shares — the Java `synchronized (CanWriteVerbs.class)`. */
    private val LOCK = CanWriteVerbs::class.java

    // ─── CAN feature ID constants ────────────────────────────────────────
    //  The decimal values are authoritative — VERIFIED against the live DL3
    //  android.hardware.bydauto.BYDAutoFeatureIds scrape (HUD bench export
    //  20260625_174428; they match the Open-BYD repo / DiLink 5.1). The earlier
    //  hex comments ("from OpenBYD 2.2 RE") were WRONG and are corrected below —
    //  they had misled a tester into writing the wrong featureId in the bench.

    /** Start / stop navigation display on the instrument cluster HUD.
     *  Pass [NAVI_STATUS_ACTIVE] or [NAVI_STATUS_STOPPED]. */
    const val INSTRUMENT_SEND_NAVI_STATUS      = 1138753594 // 0x43E0003A

    /** Simple guidance: primary turn icon ID + distance-to-turn in metres. */
    const val INSTRUMENT_GUIDE_SIMPLE          = 1139806224 // 0x43F01010

    // REMOVED: INSTRUMENT_GUIDE_ROAD_DISTANCE (0x43F01030). RE of the OEM nav bridge shows it
    // writes exactly 15 INSTRUMENT_* registers and this is NOT one of them, and the SDK refuses it
    // ("no permission to use the feature: 0x43f01030 with this device: 1007!", 344x in the tester
    // corpus — including on cars whose arrows render perfectly). It was never part of the contract;
    // the constant is gone so no bench or future code can reintroduce the write.

    /** Distance to the next crossing / intersection in metres. */
    const val INSTRUMENT_FRONT_CROSSING_DIST   = 1139806232 // 0x43F01018

    /** Next street name — write as UTF-8 bytes via [setBytes]. */
    const val INSTRUMENT_NEXT_PATHNAME         = 1140461576 // 0x43FA1008

    /** Remaining route distance in metres. */
    const val INSTRUMENT_NAVI_MILEAGE          = 1139810344 // 0x43F02028

    /** ETA hours component (0-23). */
    const val INSTRUMENT_NAVI_HOUR             = 1139810320 // 0x43F02010

    /** ETA minutes component (0-59). */
    const val INSTRUMENT_NAVI_MINUTE           = 1139810328 // 0x43F02018

    /** Remaining route time in seconds (alternative to hour/minute split). */
    const val INSTRUMENT_NAVI_REMAINING_SEC    = 1139810334 // 0x43F0201E

    // Expected-arrival WALL-CLOCK ETA family (distinct from the remaining-DURATION registers above):
    // the OEM AmapService.sendNavigateInfoToCAN writes these to show "arrive at HH:MM" on the cluster.
    /** ETA arrival day-code (1=today, 2=tomorrow, …). We send 1 — a notification carries no day. */
    const val INSTRUMENT_EXPECTED_ARRIVE_DAY    = 1139838992 // 0x43F09010
    /** ETA arrival hour (0-23). */
    const val INSTRUMENT_EXPECTED_ARRIVE_HOUR   = 1139839000 // 0x43F09018
    /** ETA arrival minute (0-59). */
    const val INSTRUMENT_EXPECTED_ARRIVE_MINUTE = 1139839008 // 0x43F09020
    /** ETA arrival second — always 0; latches/commits the day/hour/minute triple. */
    const val INSTRUMENT_EXPECTED_ARRIVE_SECOND = 1139839016 // 0x43F09028

    /** Advanced lead-message icon (secondary / advanced HUD variant). */
    const val INSTRUMENT_NAVI_LEAD_MSG         = 1139834896 // 0x43F08010

    /** Advanced distance-to-target (secondary / advanced HUD variant). */
    const val INSTRUMENT_DISTANCE_TARGET_AHEAD = 1139834904 // 0x43F08018

    // ─── BYDAutoSettingDevice feature IDs ────────────────────────────────

    /** Activates the navigation display lane on the instrument cluster screen.
     *  Lives on `BYDAutoSettingDevice` (NOT InstrumentDevice).
     *  Set to value `3` when navigation starts; not cleared on stop. */
    const val SETTING_NAVI_SCREEN_STATUS = 1276174357 // 0x4C10E015

    // ─── Windshield HUD (W-HUD) control — BYDAutoSettingDevice ──────────────
    // The HUD has no dedicated nav-content feature: it projects the instrument
    // nav guidance, gated by the switch (on/off) + mode (what it shows).
    //
    // The feature IDs and value semantics below are PROVEN ground truth: they were
    // captured from the OEM `com.byd.carsettings` app's own HalSetter logcat
    // (sendEcu2BYDAuto FeatureId … intValue …) while a tester operated each HUD
    // control (see log.docx, ECU Id=0x4C1 SubId=0xE). Do not "correct" them.

    /** Windshield HUD on/off. SET_HUD_SWITCH_SET.
     *  PROVEN values: [HUD_SWITCH_ON] (1) = on, [HUD_SWITCH_OFF] (2) = off.
     *  (NOT 0/1 — the OEM writes 1 to turn on and 2 to turn off.) */
    const val SET_HUD_SWITCH = 1276174371 // 0x4C10E023

    /** Windshield HUD display mode (what it shows: speed / speed+nav / …). SET_HUD_MODE_SET. */
    const val SET_HUD_MODE = 1276174373 // 0x4C10E025

    /** HUD ADAS / option-display overlay on/off (OEM "HudOptionDisplay"). 1 = on. Proven. */
    const val SET_HUD_OPTION_DISPLAY = 1276174384 // 0x4C10E030

    /** HUD image brightness (OEM "HudBrightness"). Integer level (observed up to 11). Proven. */
    const val SET_HUD_BRIGHTNESS = 1276174360 // 0x4C10E018

    /** HUD image vertical position / height (OEM "HudHeight"). Integer level. Proven. */
    const val SET_HUD_HEIGHT = 1276174352 // 0x4C10E010

    /** HUD image angle / rotation (OEM "HudRotate"). Written as a DOUBLE, degrees
     *  (each detent ≈ 0.4°). Use [settingSetDouble]. Proven. */
    const val SET_HUD_ANGLE = 1276174380 // 0x4C10E02C

    /** Value for [SET_HUD_SWITCH]: turn the windshield HUD ON. */
    const val HUD_SWITCH_ON  = 1

    /** Value for [SET_HUD_SWITCH]: turn the windshield HUD OFF. */
    const val HUD_SWITCH_OFF = 2

    /** HUD request command. SETTING_HUD_REQUEST_COMMAND_SET. */
    const val SETTING_HUD_REQUEST_COMMAND = 850436164 // 0x32B0A044

    /** HUD mode read-back (feedback). SET_HUD_MODE_FEEDBACK — read to learn the OEM's nav mode. */
    const val SET_HUD_MODE_FEEDBACK = 951058445 // 0x38B0000D

    /** HUD switch read-back (feedback). SET_HUD_SWITCH_STATUS_FEEDBACK. */
    const val SET_HUD_SWITCH_STATUS_FEEDBACK = 951058460 // 0x38B0001C

    // ─── Navigation status values ─────────────────────────────────────────

    /** Value for [INSTRUMENT_SEND_NAVI_STATUS]: navigation active. */
    const val NAVI_STATUS_ACTIVE  = 2

    /** Value for [INSTRUMENT_SEND_NAVI_STATUS]: navigation stopped. */
    const val NAVI_STATUS_STOPPED = 4

    // ─── Reflection caches (double-checked locking, process-lifetime) ─────

    @Volatile private var sDevice: Any? = null            // BYDAutoInstrumentDevice singleton
    @Volatile private var sSettingDevice: Any? = null     // BYDAutoSettingDevice singleton
    @Volatile private var sEvClass: Class<*>? = null      // BYDAutoEventValue Class
    @Volatile private var sIntField: Field? = null        // BYDAutoEventValue.intValue
    @Volatile private var sBytesField: Field? = null      // BYDAutoEventValue.bufferDataValue
    @Volatile private var sDoubleField: Field? = null     // BYDAutoEventValue.doubleValue (or floatValue)
    @Volatile private var sSetMethod: Method? = null      // InstrumentDevice.set(int[], BYDAutoEventValue)
    @Volatile private var sSettingSetMethod: Method? = null // SettingDevice.set(int[], BYDAutoEventValue)
    @Volatile private var sGetMethod: Method? = null      // InstrumentDevice.get(int[])
    @Volatile private var sSettingGetMethod: Method? = null // SettingDevice.get(int[])

    /** (featureId,rc) → last time we logged it, so a rejected per-frame write logs ~every 30 s. */
    private val sRejectLog = ConcurrentHashMap<Long, Long>()

    // ─── Public verbs ─────────────────────────────────────────────────────

    /**
     * Write an integer value to a CAN instrument feature.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN feature ID (see constants above)
     * @param value      integer value to write
     * @return SDK result code: 0 = `INSTRUMENT_COMMAND_SUCCESS`
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun setInt(wrappedCtx: Context, featureId: Int, value: Int): Int {
        ensureDevice(wrappedCtx)
        val evClass = ensureEvClass()
        val ev = evClass.getDeclaredConstructor().newInstance()
        ensureIntField().set(ev, value)
        val rc = ensureSetMethod().invoke(sDevice, intArrayOf(featureId), ev) as Int
        logIfRejected("instrument", featureId, rc)
        return rc
    }

    /**
     * Makes an SDK REJECTION visible. The BYD SDK does not throw when it refuses a feature — it
     * logs its own "no permission to use the feature: 0x… with this device: …!" line and returns a
     * non-zero code, which callers historically discarded. That made a refused write
     * indistinguishable from an accepted one in bug reports and bench zips.
     *
     * One line, at the only place every write actually reaches the SDK, so it covers the batch
     * path, the single-write path and production alike. Throttled per (feature, rc) so a per-frame
     * guidance write cannot flood the log.
     */
    private fun logIfRejected(device: String, featureId: Int, rc: Int) {
        if (rc == 0) return
        val key = (featureId.toLong() shl 32) or (rc.toLong() and 0xFFFFFFFFL)
        val last = sRejectLog[key]
        val now = SystemClock.elapsedRealtime()
        if (last != null && now - last < 30_000L) return
        sRejectLog[key] = now
        Log.w("CanWriteVerbs", String.format(Locale.ROOT,
                "CAN WRITE REJECTED %s feature=0x%08X rc=%d — the SDK refused this register on this car",
                device, featureId, rc))
    }

    /**
     * Write a byte buffer to a CAN instrument feature (e.g. street name encoded as UTF-8).
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN feature ID (see constants above)
     * @param data       bytes to write; must not be null
     * @return SDK result code: 0 = `INSTRUMENT_COMMAND_SUCCESS`
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun setBytes(wrappedCtx: Context, featureId: Int, data: ByteArray?): Int {
        ensureDevice(wrappedCtx)
        val evClass = ensureEvClass()
        val ev = evClass.getDeclaredConstructor().newInstance()
        ensureBytesField().set(ev, data)
        val rc = ensureSetMethod().invoke(sDevice, intArrayOf(featureId), ev) as Int
        logIfRejected("instrument", featureId, rc)
        return rc
    }

    /**
     * Write an integer value to a CAN *setting* feature via
     * `BYDAutoSettingDevice.set(int[], BYDAutoEventValue)`.
     *
     * The setting device is a separate BYD hardware abstraction from
     * `BYDAutoInstrumentDevice`. It is required for features such as
     * [SETTING_NAVI_SCREEN_STATUS] (1276174357) which activate the navigation screen on the
     * cluster display. Mirrors the `CarControlImpl.setSettingFeatureValue` path in OpenBYD 2.2.
     *
     * @return SDK result code: 0 = success
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun settingSetInt(wrappedCtx: Context, featureId: Int, value: Int): Int {
        ensureSettingDevice(wrappedCtx)
        val evClass = ensureEvClass()
        val ev = evClass.getDeclaredConstructor().newInstance()
        ensureIntField().set(ev, value)
        return ensureSettingSetMethod().invoke(sSettingDevice, intArrayOf(featureId), ev) as Int
    }

    /**
     * Write a DOUBLE value to a CAN *setting* feature via
     * `BYDAutoSettingDevice.set(int[], BYDAutoEventValue)` with the `doubleValue` field set.
     * Required for the HUD angle ([SET_HUD_ANGLE]), which the OEM writes as a double (proven
     * from the OEM HalSetter logcat: `doubleValue is 0.0`).
     *
     * The value field name is resolved reflectively (`doubleValue`, else `floatValue`) against
     * the runtime `BYDAutoEventValue` class — the compile stub need not carry it.
     *
     * @return SDK result code: 0 = success
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun settingSetDouble(wrappedCtx: Context, featureId: Int, value: Double): Int {
        ensureSettingDevice(wrappedCtx)
        val evClass = ensureEvClass()
        val ev = evClass.getDeclaredConstructor().newInstance()
        val f = ensureDoubleField()
        if (f.type == java.lang.Float.TYPE) {
            f.setFloat(ev, value.toFloat())
        } else {
            f.setDouble(ev, value)
        }
        return ensureSettingSetMethod().invoke(sSettingDevice, intArrayOf(featureId), ev) as Int
    }

    /**
     * Read an integer value from a CAN *instrument* feature via
     * `BYDAutoInstrumentDevice.get(int[])` → `BYDAutoEventValue.intValue`.
     *
     * Runs inside the daemon (uid 2000) with the permission-bypass context, exactly like
     * [setInt]. In-app reads (uid of the app) throw an `InvocationTargetException` — only the
     * daemon path is accepted by the SDK.
     *
     * @return the feature's current integer value
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun getInt(wrappedCtx: Context, featureId: Int): Int {
        ensureDevice(wrappedCtx)
        val res = ensureGetMethod().invoke(sDevice, intArrayOf(featureId))
        return readIntFromResult(res)
    }

    /**
     * Read an integer value from a CAN *setting* feature via
     * `BYDAutoSettingDevice.get(int[])` → `BYDAutoEventValue.intValue`.
     * Use this to read e.g. [SET_HUD_MODE_FEEDBACK] while the OEM nav drives the HUD.
     *
     * @return the feature's current integer value
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun settingGetInt(wrappedCtx: Context, featureId: Int): Int {
        ensureSettingDevice(wrappedCtx)
        val res = ensureSettingGetMethod().invoke(sSettingDevice, intArrayOf(featureId))
        return readIntFromResult(res)
    }

    /** Extracts `intValue` from a `get(int[])` result (an array or a single event). */
    @Throws(Throwable::class)
    private fun readIntFromResult(res: Any?): Int {
        var ev: Any? = res
        if (res != null && res.javaClass.isArray) {
            val len = ReflectArray.getLength(res)
            if (len == 0) throw IllegalStateException("get(int[]) returned an empty array")
            ev = ReflectArray.get(res, 0)
        }
        if (ev == null) throw IllegalStateException("get(int[]) returned a null value")
        return ensureIntField().getInt(ev)
    }

    // ─── Reflection initialisation (call order: device → evClass → fields/method) ─

    @Throws(Throwable::class)
    private fun ensureDevice(ctx: Context) {
        if (sDevice != null) return
        synchronized(LOCK) {
            if (sDevice != null) return
            val cls = Class.forName(
                    "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            val d = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                    ?: throw IllegalStateException(
                            "BYDAutoInstrumentDevice.getInstance() returned null")
            sDevice = d
        }
    }

    @Throws(Throwable::class)
    private fun ensureEvClass(): Class<*> {
        sEvClass?.let { return it }
        synchronized(LOCK) {
            sEvClass?.let { return it }
            val c = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
            sEvClass = c
            return c
        }
    }

    @Throws(Throwable::class)
    private fun ensureIntField(): Field {
        sIntField?.let { return it }
        synchronized(LOCK) {
            sIntField?.let { return it }
            val f = ensureEvClass().getField("intValue")
            sIntField = f
            return f
        }
    }

    @Throws(Throwable::class)
    private fun ensureBytesField(): Field {
        sBytesField?.let { return it }
        synchronized(LOCK) {
            sBytesField?.let { return it }
            val f = ensureEvClass().getField("bufferDataValue")
            sBytesField = f
            return f
        }
    }

    /** Resolves the `BYDAutoEventValue` floating-point value field: `doubleValue` (preferred,
     *  matches the OEM HalSetter log) or `floatValue` as a fallback. */
    @Throws(Throwable::class)
    private fun ensureDoubleField(): Field {
        sDoubleField?.let { return it }
        synchronized(LOCK) {
            sDoubleField?.let { return it }
            val ev = ensureEvClass()
            val f = try {
                ev.getField("doubleValue")
            } catch (nsfe: NoSuchFieldException) {
                ev.getField("floatValue")
            }
            sDoubleField = f
            return f
        }
    }

    @Throws(Throwable::class)
    private fun ensureSetMethod(): Method {
        sSetMethod?.let { return it }
        synchronized(LOCK) {
            sSetMethod?.let { return it }
            // sDevice must already be set by ensureDevice() before this is called.
            val device = sDevice ?: throw IllegalStateException("device not initialised")
            val evClass = ensureEvClass()
            // Search the runtime class hierarchy for set(int[], BYDAutoEventValue).
            // The method lives on a non-public internal class, not on the stub, so
            // getMethods() (public + inherited) is the right scanner here.
            var m: Method? = null
            for (cand in device.javaClass.methods) {
                if ("set" != cand.name) continue
                val pt = cand.parameterTypes
                if (pt.size == 2 && pt[0] == IntArray::class.java && pt[1] == evClass) {
                    m = cand
                    break
                }
            }
            val found = m ?: throw NoSuchMethodException(
                    "no set(int[], BYDAutoEventValue) on " + device.javaClass.name)
            sSetMethod = found
            return found
        }
    }

    @Throws(Throwable::class)
    private fun ensureGetMethod(): Method {
        sGetMethod?.let { return it }
        synchronized(LOCK) {
            sGetMethod?.let { return it }
            val device = sDevice ?: throw IllegalStateException("device not initialised")
            val m = findGet(device.javaClass)
            sGetMethod = m
            return m
        }
    }

    @Throws(Throwable::class)
    private fun ensureSettingGetMethod(): Method {
        sSettingGetMethod?.let { return it }
        synchronized(LOCK) {
            sSettingGetMethod?.let { return it }
            val device = sSettingDevice
                    ?: throw IllegalStateException("setting device not initialised")
            val m = findGet(device.javaClass)
            sSettingGetMethod = m
            return m
        }
    }

    /** Finds a `get(int[])` method anywhere in the runtime class hierarchy. */
    @Throws(NoSuchMethodException::class)
    private fun findGet(cls: Class<*>): Method {
        for (cand in cls.methods) {
            if ("get" != cand.name) continue
            val pt = cand.parameterTypes
            if (pt.size == 1 && pt[0] == IntArray::class.java) {
                return cand
            }
        }
        throw NoSuchMethodException("no get(int[]) on " + cls.name)
    }

    @Throws(Throwable::class)
    private fun ensureSettingDevice(ctx: Context) {
        if (sSettingDevice != null) return
        synchronized(LOCK) {
            if (sSettingDevice != null) return
            val cls = Class.forName(
                    "android.hardware.bydauto.setting.BYDAutoSettingDevice")
            val d = cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                    ?: throw IllegalStateException(
                            "BYDAutoSettingDevice.getInstance() returned null")
            sSettingDevice = d
        }
    }

    @Throws(Throwable::class)
    private fun ensureSettingSetMethod(): Method {
        sSettingSetMethod?.let { return it }
        synchronized(LOCK) {
            sSettingSetMethod?.let { return it }
            val device = sSettingDevice
                    ?: throw IllegalStateException("setting device not initialised")
            val evClass = ensureEvClass()
            var m: Method? = null
            for (cand in device.javaClass.methods) {
                if ("set" != cand.name) continue
                val pt = cand.parameterTypes
                if (pt.size == 2 && pt[0] == IntArray::class.java && pt[1] == evClass) {
                    m = cand
                    break
                }
            }
            val found = m ?: throw NoSuchMethodException(
                    "no set(int[], BYDAutoEventValue) on " + device.javaClass.name)
            sSettingSetMethod = found
            return found
        }
    }
}
