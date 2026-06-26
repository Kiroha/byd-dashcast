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

    /** Guidance with road-ahead distance (secondary display variant). */
    public static final int INSTRUMENT_GUIDE_ROAD_DISTANCE   = 1139806256; // 0x43F01030

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

    /** Windshield HUD on/off. SET_HUD_SWITCH_SET — 1 = on. */
    public static final int SET_HUD_SWITCH = 1276174371; // 0x4C10E023

    /** Windshield HUD display mode (what it shows: speed / speed+nav / …). SET_HUD_MODE_SET. */
    public static final int SET_HUD_MODE = 1276174373; // 0x4C10E025

    /** HUD request command. SETTING_HUD_REQUEST_COMMAND_SET. */
    public static final int SETTING_HUD_REQUEST_COMMAND = 850436164; // 0x32B0A044

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
    private static volatile Method   sSetMethod;       // InstrumentDevice.set(int[], BYDAutoEventValue)
    private static volatile Method   sSettingSetMethod;// SettingDevice.set(int[], BYDAutoEventValue)

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
        return (int) ensureSetMethod().invoke(sDevice, new int[]{featureId}, ev);
    }

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
        return (int) ensureSetMethod().invoke(sDevice, new int[]{featureId}, ev);
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
