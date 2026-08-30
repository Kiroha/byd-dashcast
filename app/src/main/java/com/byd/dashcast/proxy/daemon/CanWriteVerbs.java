package com.byd.dashcast.proxy.daemon;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * CanWriteVerbs — CAN bus write verbs that run inside the daemon process (uid 2000).
 *
 * <p>All calls use reflection to reach {@code BYDAutoInstrumentDevice.set(int[], BYDAutoEventValue)}
 * — the API that unlocks cluster HUD writes. That method is NOT exposed in the public DiLink 3
 * stubs (which only carry {@code AbsBYDAutoDevice.set(int device, int event, int value)}), so
 * reflection is the only portable path, matching how OpenBYD 2.2 calls the same surface.
 *
 * <p>The {@code wrappedCtx} parameter must be a context whose permission checks all return
 * {@code PERMISSION_GRANTED}. In the daemon this is {@code ProxyDaemonMain.sWrappedContext}
 * (system context wrapped by {@link com.byd.dashcast.proxy.SystemContextHelper#wrap}).
 * Without this wrapper the SDK internally rejects write calls even from a system-signed process.
 *
 * <p>Reflection objects are cached after the first successful call: Class, Field, and Method
 * lookups are one-shot, thread-safe (double-checked locking), and survive for the daemon
 * process lifetime. The {@code BYDAutoInstrumentDevice} singleton is also cached — it is
 * process-wide, just like any other Android system-service proxy.
 *
 * <p>Feature ID constants are copied verbatim from the OpenBYD 2.2 RE (CarControlImpl.java).
 * They encode both the CAN device type and the event ID in a single opaque integer.
 * Use the high-level helpers in {@link com.byd.dashcast.system.CanBusController} rather than
 * passing raw IDs unless you know exactly what you are doing.
 *
 * @see com.byd.dashcast.system.CanBusController
 * @since v1.4.7-beta (Phase CAN-1)
 */
public final class CanWriteVerbs {

    private CanWriteVerbs() {}

    // ─── CAN feature ID constants ────────────────────────────────────────
    //  The decimal values are authoritative — VERIFIED against the live DL3
    //  android.hardware.bydauto.BYDAutoFeatureIds scrape (HUD bench export
    //  20260625_174428; they match the Open-BYD repo / DiLink 5.1). The earlier
    //  hex comments ("from OpenBYD 2.2 RE") were WRONG and are corrected below —
    //  they had misled a tester into writing the wrong featureId in the bench.

    /** Start / stop navigation display on the instrument cluster HUD.
     *  Pass {@link #NAVI_STATUS_ACTIVE} or {@link #NAVI_STATUS_STOPPED}. */
    public static final int INSTRUMENT_SEND_NAVI_STATUS      = 1138753594; // 0x43E0003A

    /** Simple guidance: primary turn icon ID + distance-to-turn in metres. */
    public static final int INSTRUMENT_GUIDE_SIMPLE          = 1139806224; // 0x43F01010

    // REMOVED: INSTRUMENT_GUIDE_ROAD_DISTANCE (0x43F01030). RE of the OEM nav bridge shows it
    // writes exactly 15 INSTRUMENT_* registers and this is NOT one of them, and the SDK refuses it
    // ("no permission to use the feature: 0x43f01030 with this device: 1007!", 344x in the tester
    // corpus — including on cars whose arrows render perfectly). It was never part of the contract;
    // the constant is gone so no bench or future code can reintroduce the write.

    /** Distance to the next crossing / intersection in metres. */
    public static final int INSTRUMENT_FRONT_CROSSING_DIST   = 1139806232; // 0x43F01018

    /** Next street name — write as UTF-8 bytes via {@link #setBytes}. */
    public static final int INSTRUMENT_NEXT_PATHNAME         = 1140461576; // 0x43FA1008

    /** Remaining route distance in metres. */
    public static final int INSTRUMENT_NAVI_MILEAGE          = 1139810344; // 0x43F02028

    /** ETA hours component (0-23). */
    public static final int INSTRUMENT_NAVI_HOUR             = 1139810320; // 0x43F02010

    /** ETA minutes component (0-59). */
    public static final int INSTRUMENT_NAVI_MINUTE           = 1139810328; // 0x43F02018

    /** Remaining route time in seconds (alternative to hour/minute split). */
    public static final int INSTRUMENT_NAVI_REMAINING_SEC    = 1139810334; // 0x43F0201E

    // Expected-arrival WALL-CLOCK ETA family (distinct from the remaining-DURATION registers above):
    // the OEM AmapService.sendNavigateInfoToCAN writes these to show "arrive at HH:MM" on the cluster.
    /** ETA arrival day-code (1=today, 2=tomorrow, …). We send 1 — a notification carries no day. */
    public static final int INSTRUMENT_EXPECTED_ARRIVE_DAY    = 1139838992; // 0x43F09010
    /** ETA arrival hour (0-23). */
    public static final int INSTRUMENT_EXPECTED_ARRIVE_HOUR   = 1139839000; // 0x43F09018
    /** ETA arrival minute (0-59). */
    public static final int INSTRUMENT_EXPECTED_ARRIVE_MINUTE = 1139839008; // 0x43F09020
    /** ETA arrival second — always 0; latches/commits the day/hour/minute triple. */
    public static final int INSTRUMENT_EXPECTED_ARRIVE_SECOND = 1139839016; // 0x43F09028

    /** Advanced lead-message icon (secondary / advanced HUD variant). */
    public static final int INSTRUMENT_NAVI_LEAD_MSG         = 1139834896; // 0x43F08010

    /** Advanced distance-to-target (secondary / advanced HUD variant). */
    public static final int INSTRUMENT_DISTANCE_TARGET_AHEAD = 1139834904; // 0x43F08018

    // ─── BYDAutoSettingDevice feature IDs ────────────────────────────────

    /** Activates the navigation display lane on the instrument cluster screen.
     *  Lives on {@code BYDAutoSettingDevice} (NOT InstrumentDevice).
     *  Set to value {@code 3} when navigation starts; not cleared on stop. */
    public static final int SETTING_NAVI_SCREEN_STATUS = 1276174357; // 0x4C10E015

    // ─── Windshield HUD (W-HUD) control — BYDAutoSettingDevice ──────────────
    // The HUD has no dedicated nav-content feature: it projects the instrument
    // nav guidance, gated by the switch (on/off) + mode (what it shows).
    //
    // The feature IDs and value semantics below are PROVEN ground truth: they were
    // captured from the OEM `com.byd.carsettings` app's own HalSetter logcat
    // (sendEcu2BYDAuto FeatureId … intValue …) while a tester operated each HUD
    // control (see log.docx, ECU Id=0x4C1 SubId=0xE). Do not "correct" them.

    /** Windshield HUD on/off. SET_HUD_SWITCH_SET.
     *  PROVEN values: {@link #HUD_SWITCH_ON} (1) = on, {@link #HUD_SWITCH_OFF} (2) = off.
     *  (NOT 0/1 — the OEM writes 1 to turn on and 2 to turn off.) */
    public static final int SET_HUD_SWITCH = 1276174371; // 0x4C10E023

    /** Windshield HUD display mode (what it shows: speed / speed+nav / …). SET_HUD_MODE_SET. */
    public static final int SET_HUD_MODE = 1276174373; // 0x4C10E025

    /** HUD ADAS / option-display overlay on/off (OEM "HudOptionDisplay"). 1 = on. Proven. */
    public static final int SET_HUD_OPTION_DISPLAY = 1276174384; // 0x4C10E030

    /** HUD image brightness (OEM "HudBrightness"). Integer level (observed up to 11). Proven. */
    public static final int SET_HUD_BRIGHTNESS = 1276174360; // 0x4C10E018

    /** HUD image vertical position / height (OEM "HudHeight"). Integer level. Proven. */
    public static final int SET_HUD_HEIGHT = 1276174352; // 0x4C10E010

    /** HUD image angle / rotation (OEM "HudRotate"). Written as a DOUBLE, degrees
     *  (each detent ≈ 0.4°). Use {@link #settingSetDouble}. Proven. */
    public static final int SET_HUD_ANGLE = 1276174380; // 0x4C10E02C

    /** Value for {@link #SET_HUD_SWITCH}: turn the windshield HUD ON. */
    public static final int HUD_SWITCH_ON  = 1;

    /** Value for {@link #SET_HUD_SWITCH}: turn the windshield HUD OFF. */
    public static final int HUD_SWITCH_OFF = 2;

    /** HUD request command. SETTING_HUD_REQUEST_COMMAND_SET. */
    public static final int SETTING_HUD_REQUEST_COMMAND = 850436164; // 0x32B0A044

    /** HUD mode read-back (feedback). SET_HUD_MODE_FEEDBACK — read to learn the OEM's nav mode. */
    public static final int SET_HUD_MODE_FEEDBACK = 951058445; // 0x38B0000D

    /** HUD switch read-back (feedback). SET_HUD_SWITCH_STATUS_FEEDBACK. */
    public static final int SET_HUD_SWITCH_STATUS_FEEDBACK = 951058460; // 0x38B0001C

    // ─── Navigation status values ─────────────────────────────────────────

    /** Value for {@link #INSTRUMENT_SEND_NAVI_STATUS}: navigation active. */
    public static final int NAVI_STATUS_ACTIVE  = 2;

    /** Value for {@link #INSTRUMENT_SEND_NAVI_STATUS}: navigation stopped. */
    public static final int NAVI_STATUS_STOPPED = 4;

    // ─── Reflection caches (double-checked locking, process-lifetime) ─────

    private static volatile Object   sDevice;          // BYDAutoInstrumentDevice singleton
    private static volatile Object   sSettingDevice;   // BYDAutoSettingDevice singleton
    private static volatile Class<?> sEvClass;         // BYDAutoEventValue Class
    private static volatile Field    sIntField;        // BYDAutoEventValue.intValue
    private static volatile Field    sBytesField;      // BYDAutoEventValue.bufferDataValue
    private static volatile Field    sDoubleField;     // BYDAutoEventValue.doubleValue (or floatValue)
    private static volatile Method   sSetMethod;       // InstrumentDevice.set(int[], BYDAutoEventValue)
    private static volatile Method   sSettingSetMethod;// SettingDevice.set(int[], BYDAutoEventValue)
    private static volatile Method   sGetMethod;        // InstrumentDevice.get(int[])
    private static volatile Method   sSettingGetMethod; // SettingDevice.get(int[])

    // ─── Public verbs ─────────────────────────────────────────────────────

    /**
     * Write an integer value to a CAN instrument feature.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN feature ID (see constants above)
     * @param value      integer value to write
     * @return SDK result code: 0 = {@code INSTRUMENT_COMMAND_SUCCESS}
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int setInt(Context wrappedCtx, int featureId, int value) throws Throwable {
        ensureDevice(wrappedCtx);
        Class<?> evClass = ensureEvClass();
        Object ev = evClass.newInstance();
        ensureIntField().set(ev, value);
        int rc = (int) ensureSetMethod().invoke(sDevice, new int[]{featureId}, ev);
        logIfRejected("instrument", featureId, rc);
        return rc;
    }

    /**
     * Makes an SDK REJECTION visible. The BYD SDK does not throw when it refuses a feature — it
     * logs its own "no permission to use the feature: 0x… with this device: …!" line and returns a
    * non-zero code, which callers historically discarded. That made a refused write
    * indistinguishable from an accepted one in bug reports and bench zips.
     *
     * <p>One line, at the only place every write actually reaches the SDK, so it covers the batch
     * path, the single-write path and production alike. Throttled per (feature, rc) so a per-frame
     * guidance write cannot flood the log.
     */
    private static void logIfRejected(String device, int featureId, int rc) {
        if (rc == 0) return;
        long key = (((long) featureId) << 32) | (rc & 0xFFFFFFFFL);
        Long last = sRejectLog.get(key);
        long now = android.os.SystemClock.elapsedRealtime();
        if (last != null && now - last < 30_000L) return;
        sRejectLog.put(key, now);
        android.util.Log.w("CanWriteVerbs", String.format(
                "CAN WRITE REJECTED %s feature=0x%08X rc=%d — the SDK refused this register on this car",
                device, featureId, rc));
    }

    /** (featureId,rc) → last time we logged it, so a rejected per-frame write logs ~every 30 s. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> sRejectLog =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Write a byte buffer to a CAN instrument feature (e.g. street name encoded as UTF-8).
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN feature ID (see constants above)
     * @param data       bytes to write; must not be null
     * @return SDK result code: 0 = {@code INSTRUMENT_COMMAND_SUCCESS}
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int setBytes(Context wrappedCtx, int featureId, byte[] data) throws Throwable {
        ensureDevice(wrappedCtx);
        Class<?> evClass = ensureEvClass();
        Object ev = evClass.newInstance();
        ensureBytesField().set(ev, data);
        int rc = (int) ensureSetMethod().invoke(sDevice, new int[]{featureId}, ev);
        logIfRejected("instrument", featureId, rc);
        return rc;
    }

    /**
     * Write an integer value to a CAN <em>setting</em> feature via
     * {@code BYDAutoSettingDevice.set(int[], BYDAutoEventValue)}.
     *
     * <p>The setting device is a separate BYD hardware abstraction from
     * {@code BYDAutoInstrumentDevice}. It is required for features such as
     * {@link #SETTING_NAVI_SCREEN_STATUS} (1276174357) which activate the
     * navigation screen on the cluster display. Mirrors the
     * {@code CarControlImpl.setSettingFeatureValue} path in OpenBYD 2.2.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN setting feature ID (see constants above)
     * @param value      integer value to write
     * @return SDK result code: 0 = success
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int settingSetInt(Context wrappedCtx, int featureId, int value) throws Throwable {
        ensureSettingDevice(wrappedCtx);
        Class<?> evClass = ensureEvClass();
        Object ev = evClass.newInstance();
        ensureIntField().set(ev, value);
        return (int) ensureSettingSetMethod().invoke(sSettingDevice, new int[]{featureId}, ev);
    }

    /**
     * Write a DOUBLE value to a CAN <em>setting</em> feature via
     * {@code BYDAutoSettingDevice.set(int[], BYDAutoEventValue)} with the {@code doubleValue}
     * field set. Required for the HUD angle ({@link #SET_HUD_ANGLE}), which the OEM writes as a
     * double (proven from the OEM HalSetter logcat: {@code doubleValue is 0.0}).
     *
     * <p>The value field name is resolved reflectively ({@code doubleValue}, else {@code floatValue})
     * against the runtime {@code BYDAutoEventValue} class — the compile stub need not carry it.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw BYD CAN setting feature ID
     * @param value      double value to write
     * @return SDK result code: 0 = success
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int settingSetDouble(Context wrappedCtx, int featureId, double value) throws Throwable {
        ensureSettingDevice(wrappedCtx);
        Class<?> evClass = ensureEvClass();
        Object ev = evClass.newInstance();
        Field f = ensureDoubleField();
        if (f.getType() == float.class) {
            f.setFloat(ev, (float) value);
        } else {
            f.setDouble(ev, value);
        }
        return (int) ensureSettingSetMethod().invoke(sSettingDevice, new int[]{featureId}, ev);
    }

    /**
     * Read an integer value from a CAN <em>instrument</em> feature via
     * {@code BYDAutoInstrumentDevice.get(int[])} → {@code BYDAutoEventValue.intValue}.
     *
     * <p>Runs inside the daemon (uid 2000) with the permission-bypass context, exactly
     * like {@link #setInt}. In-app reads (uid of the app) throw an
     * {@code InvocationTargetException} — only the daemon path is accepted by the SDK.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN feature ID (e.g. a {@code *_FEEDBACK} id)
     * @return the feature's current integer value
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int getInt(Context wrappedCtx, int featureId) throws Throwable {
        ensureDevice(wrappedCtx);
        Object res = ensureGetMethod().invoke(sDevice, (Object) new int[]{featureId});
        return readIntFromResult(res);
    }

    /**
     * Read an integer value from a CAN <em>setting</em> feature via
     * {@code BYDAutoSettingDevice.get(int[])} → {@code BYDAutoEventValue.intValue}.
     * Use this to read e.g. {@link #SET_HUD_MODE_FEEDBACK} while the OEM nav drives the HUD.
     *
     * @param wrappedCtx permission-bypass context (must grant all permissions)
     * @param featureId  raw CAN setting feature ID
     * @return the feature's current integer value
     * @throws Throwable on reflection failure or if the SDK rejects the call
     */
    public static int settingGetInt(Context wrappedCtx, int featureId) throws Throwable {
        ensureSettingDevice(wrappedCtx);
        Object res = ensureSettingGetMethod().invoke(sSettingDevice, (Object) new int[]{featureId});
        return readIntFromResult(res);
    }

    /** Extracts {@code intValue} from a {@code get(int[])} result (an array or a single event). */
    private static int readIntFromResult(Object res) throws Throwable {
        Object ev = res;
        if (res != null && res.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(res);
            if (len == 0) throw new IllegalStateException("get(int[]) returned an empty array");
            ev = java.lang.reflect.Array.get(res, 0);
        }
        if (ev == null) throw new IllegalStateException("get(int[]) returned a null value");
        return ensureIntField().getInt(ev);
    }

    // ─── Reflection initialisation (call order: device → evClass → fields/method) ─

    private static void ensureDevice(Context ctx) throws Throwable {
        if (sDevice != null) return;
        synchronized (CanWriteVerbs.class) {
            if (sDevice != null) return;
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Object d = cls.getMethod("getInstance", Context.class).invoke(null, ctx);
            if (d == null) throw new IllegalStateException(
                    "BYDAutoInstrumentDevice.getInstance() returned null");
            sDevice = d;
        }
    }

    private static Class<?> ensureEvClass() throws Throwable {
        Class<?> c = sEvClass;
        if (c != null) return c;
        synchronized (CanWriteVerbs.class) {
            c = sEvClass;
            if (c != null) return c;
            c = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            sEvClass = c;
            return c;
        }
    }

    private static Field ensureIntField() throws Throwable {
        Field f = sIntField;
        if (f != null) return f;
        synchronized (CanWriteVerbs.class) {
            f = sIntField;
            if (f != null) return f;
            f = ensureEvClass().getField("intValue");
            sIntField = f;
            return f;
        }
    }

    private static Field ensureBytesField() throws Throwable {
        Field f = sBytesField;
        if (f != null) return f;
        synchronized (CanWriteVerbs.class) {
            f = sBytesField;
            if (f != null) return f;
            f = ensureEvClass().getField("bufferDataValue");
            sBytesField = f;
            return f;
        }
    }

    /** Resolves the {@code BYDAutoEventValue} floating-point value field: {@code doubleValue}
     *  (preferred, matches the OEM HalSetter log) or {@code floatValue} as a fallback. */
    private static Field ensureDoubleField() throws Throwable {
        Field f = sDoubleField;
        if (f != null) return f;
        synchronized (CanWriteVerbs.class) {
            f = sDoubleField;
            if (f != null) return f;
            Class<?> ev = ensureEvClass();
            try {
                f = ev.getField("doubleValue");
            } catch (NoSuchFieldException nsfe) {
                f = ev.getField("floatValue");
            }
            sDoubleField = f;
            return f;
        }
    }

    private static Method ensureSetMethod() throws Throwable {
        Method m = sSetMethod;
        if (m != null) return m;
        synchronized (CanWriteVerbs.class) {
            m = sSetMethod;
            if (m != null) return m;
            // sDevice must already be set by ensureDevice() before this is called.
            Object device = sDevice;
            if (device == null) throw new IllegalStateException("device not initialised");
            Class<?> evClass = ensureEvClass();
            // Search the runtime class hierarchy for set(int[], BYDAutoEventValue).
            // The method lives on a non-public internal class, not on the stub, so
            // getMethods() (public + inherited) is the right scanner here.
            for (Method cand : device.getClass().getMethods()) {
                if (!"set".equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 2 && pt[0] == int[].class && pt[1] == evClass) {
                    m = cand;
                    break;
                }
            }
            if (m == null) throw new NoSuchMethodException(
                    "no set(int[], BYDAutoEventValue) on " + device.getClass().getName());
            sSetMethod = m;
            return m;
        }
    }

    private static Method ensureGetMethod() throws Throwable {
        Method m = sGetMethod;
        if (m != null) return m;
        synchronized (CanWriteVerbs.class) {
            m = sGetMethod;
            if (m != null) return m;
            Object device = sDevice;
            if (device == null) throw new IllegalStateException("device not initialised");
            m = findGet(device.getClass());
            sGetMethod = m;
            return m;
        }
    }

    private static Method ensureSettingGetMethod() throws Throwable {
        Method m = sSettingGetMethod;
        if (m != null) return m;
        synchronized (CanWriteVerbs.class) {
            m = sSettingGetMethod;
            if (m != null) return m;
            Object device = sSettingDevice;
            if (device == null) throw new IllegalStateException("setting device not initialised");
            m = findGet(device.getClass());
            sSettingGetMethod = m;
            return m;
        }
    }

    /** Finds a {@code get(int[])} method anywhere in the runtime class hierarchy. */
    private static Method findGet(Class<?> cls) throws NoSuchMethodException {
        for (Method cand : cls.getMethods()) {
            if (!"get".equals(cand.getName())) continue;
            Class<?>[] pt = cand.getParameterTypes();
            if (pt.length == 1 && pt[0] == int[].class) {
                return cand;
            }
        }
        throw new NoSuchMethodException("no get(int[]) on " + cls.getName());
    }

    private static void ensureSettingDevice(Context ctx) throws Throwable {
        if (sSettingDevice != null) return;
        synchronized (CanWriteVerbs.class) {
            if (sSettingDevice != null) return;
            Class<?> cls = Class.forName(
                    "android.hardware.bydauto.setting.BYDAutoSettingDevice");
            Object d = cls.getMethod("getInstance", Context.class).invoke(null, ctx);
            if (d == null) throw new IllegalStateException(
                    "BYDAutoSettingDevice.getInstance() returned null");
            sSettingDevice = d;
        }
    }

    private static Method ensureSettingSetMethod() throws Throwable {
        Method m = sSettingSetMethod;
        if (m != null) return m;
        synchronized (CanWriteVerbs.class) {
            m = sSettingSetMethod;
            if (m != null) return m;
            Object device = sSettingDevice;
            if (device == null) throw new IllegalStateException("setting device not initialised");
            Class<?> evClass = ensureEvClass();
            for (Method cand : device.getClass().getMethods()) {
                if (!"set".equals(cand.getName())) continue;
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 2 && pt[0] == int[].class && pt[1] == evClass) {
                    m = cand;
                    break;
                }
            }
            if (m == null) throw new NoSuchMethodException(
                    "no set(int[], BYDAutoEventValue) on " + device.getClass().getName());
            sSettingSetMethod = m;
            return m;
        }
    }
}
