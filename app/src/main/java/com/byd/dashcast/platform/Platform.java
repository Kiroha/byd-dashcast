package com.byd.dashcast.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * Platform — runtime detection of the BYD generation we are running on
 * (DiLink 3.0 / Android 10 vs DiLink 5.0 / Android 12, etc.).
 *
 * <p>The class is initialised once at process start by {@link
 * com.byd.dashcast.DashCastApp#onCreate()} and exposes a read-only snapshot.
 * It also honours a per-user override stored in the same SharedPreferences
 * file as {@code SettingsActivity} ("byd_app_prefs") so testers can force a
 * given mode without re-installing.
 *
 * <p>Possible user-override states for the DiLink 5 mode:
 * <ul>
 *   <li><b>AUTO</b> (default before the user touches the switch) — effective
 *       value follows {@link #isAutoDetectedDiLink5()}.</li>
 *   <li><b>FORCE_ON</b> — effective value is always true.</li>
 *   <li><b>FORCE_OFF</b> — effective value is always false.</li>
 * </ul>
 *
 * <p>Until proven otherwise, the legacy DiLink 3 code path is the default.
 * Any failure during detection => {@link #isDiLink5()} returns {@code false}.
 */
public final class Platform {

    private static final String PREFS_NAME = "byd_app_prefs";

    /** Tri-state: AUTO (no user override), FORCE_ON, FORCE_OFF. */
    public static final String PREF_DILINK5_OVERRIDE = "platform_dilink5_override";
    public static final String OV_AUTO      = "AUTO";
    public static final String OV_FORCE_ON  = "FORCE_ON";
    public static final String OV_FORCE_OFF = "FORCE_OFF";

    private static volatile Platform INSTANCE;

    private final String  rawProductName;   // ro.product.name
    private final String  rawModel;          // Build.MODEL
    private final String  rawBrand;          // Build.BRAND
    private final String  rawFingerprint;    // Build.FINGERPRINT
    private final int     androidApi;        // Build.VERSION.SDK_INT
    private final boolean autoDiLink5;       // pure auto-detection result

    private Platform() {
        this.rawProductName = readProp("ro.product.name");
        this.rawModel       = safe(Build.MODEL);
        this.rawBrand       = safe(Build.BRAND);
        this.rawFingerprint = safe(Build.FINGERPRINT);
        this.androidApi     = Build.VERSION.SDK_INT;
        this.autoDiLink5    = detectDiLink5(rawProductName, rawModel, rawFingerprint, androidApi);
    }

    /** Lazy init — call from {@code DashCastApp.onCreate()} once. */
    public static Platform get() {
        Platform p = INSTANCE;
        if (p == null) {
            synchronized (Platform.class) {
                p = INSTANCE;
                if (p == null) {
                    INSTANCE = p = new Platform();
                }
            }
        }
        return p;
    }

    // ── Auto-detection (pure, no Context dependency) ──────────────────────────

    private static boolean detectDiLink5(String product, String model, String fingerprint, int api) {
        // Primary signal: ro.product.name contains "DiLink5"
        String p = (product == null ? "" : product).toLowerCase();
        String m = (model    == null ? "" : model).toLowerCase();
        String f = (fingerprint == null ? "" : fingerprint).toLowerCase();
        if (p.contains("dilink5") || m.contains("dilink5") || f.contains("dilink5")) return true;
        if (p.contains("dilink_5") || m.contains("dilink 5") || f.contains("dilink 5")) return true;
        // Secondary signal: Android 12+ on BYD device strongly implies DiLink 5
        if (api >= 31 && (m.contains("byd") || f.contains("byd-auto") || f.contains("/dilink"))) {
            return true;
        }
        return false;
    }

    // ── Raw snapshot accessors ────────────────────────────────────────────────

    public String  rawProductName()  { return rawProductName; }
    public String  rawModel()        { return rawModel; }
    public String  rawBrand()        { return rawBrand; }
    public String  rawFingerprint()  { return rawFingerprint; }
    public int     androidApi()      { return androidApi; }
    public boolean isAutoDetectedDiLink5() { return autoDiLink5; }

    // ── Effective state (auto + user override) ────────────────────────────────

    /**
     * Effective DiLink 5 mode after applying the user override.
     * Read from SharedPreferences each call so changes are visible without
     * a process restart for non-critical UI bits. Hot code paths should
     * cache this value at process start.
     */
    public boolean isDiLink5(Context ctx) {
        String ov = readOverride(ctx);
        if (OV_FORCE_ON.equals(ov))  return true;
        if (OV_FORCE_OFF.equals(ov)) return false;
        return autoDiLink5;
    }

    /** Short summary used for diagnostics ("AUTO=on", "FORCED off", …). */
    public String describeMode(Context ctx) {
        String ov = readOverride(ctx);
        if (OV_FORCE_ON.equals(ov))  return "FORCED on";
        if (OV_FORCE_OFF.equals(ov)) return "FORCED off";
        return "AUTO=" + (autoDiLink5 ? "on" : "off");
    }

    public static String readOverride(Context ctx) {
        return prefs(ctx).getString(PREF_DILINK5_OVERRIDE, OV_AUTO);
    }

    public static void setOverride(Context ctx, String value) {
        if (!OV_AUTO.equals(value) && !OV_FORCE_ON.equals(value) && !OV_FORCE_OFF.equals(value)) {
            value = OV_AUTO;
        }
        prefs(ctx).edit().putString(PREF_DILINK5_OVERRIDE, value).apply();
    }

    /** Convenience used by the Settings switch (boolean-driven). */
    public static void setForcedBoolean(Context ctx, boolean enabled) {
        setOverride(ctx, enabled ? OV_FORCE_ON : OV_FORCE_OFF);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── SystemProperties.get() via reflection (no compile-time dep) ──────────

    private static String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
