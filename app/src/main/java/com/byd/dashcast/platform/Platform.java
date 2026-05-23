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
    private final boolean autoDiLink4;       // pure auto-detection result (BYD-AUTO/DiLink4.0, API 29)
    private final boolean autoDiLink2;       // pure auto-detection result (alps/MT6765/API 28)

    private Platform() {
        this.rawProductName = readProp("ro.product.name");
        this.rawModel       = safe(Build.MODEL);
        this.rawBrand       = safe(Build.BRAND);
        this.rawFingerprint = safe(Build.FINGERPRINT);
        this.androidApi     = Build.VERSION.SDK_INT;
        this.autoDiLink5    = detectDiLink5(rawProductName, rawModel, rawFingerprint, androidApi);
        this.autoDiLink4    = detectDiLink4(rawProductName, rawModel, rawFingerprint, androidApi);
        this.autoDiLink2    = detectDiLink2(rawBrand, rawProductName, androidApi);
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

    /**
     * DiLink 4 auto-detection — based on the field report of a BYD-AUTO / DiLink4.0
     * test vehicle (23/05/2026) running Android 10 / API 29:
     *   ro.product.name = "DiLink4.0", Build.MODEL = "DiLink4.0 For BYD AUTO",
     *   Build.FINGERPRINT = "BYD-AUTO/DiLink4.0/DiLink4.0:10/...", API 29.
     * Conservative: requires "dilink4" / "dilink 4" / "dilink_4" substring AND API 29
     * (API 32 would already have been claimed by DL5). Returns false on any uncertainty
     * so DL3/DL5 logic stays unaffected on devices we have not field-validated.
     */
    private static boolean detectDiLink4(String product, String model, String fingerprint, int api) {
        String p = (product == null ? "" : product).toLowerCase();
        String m = (model    == null ? "" : model).toLowerCase();
        String f = (fingerprint == null ? "" : fingerprint).toLowerCase();
        boolean nameHit = p.contains("dilink4") || m.contains("dilink4") || f.contains("dilink4")
                       || p.contains("dilink_4") || m.contains("dilink 4") || f.contains("dilink 4");
        if (!nameHit) return false;
        // DL4 ships Android 10 (API 29). If someone names a future Android 12 ROM
        // "DiLink4" we don't want to mis-route it here, hence the API gate.
        return api == 29 || api == 28;
    }

    /**
     * DiLink 2 auto-detection — based on the alps / k65v1_64_bsp / MT6765 / Android 9
     * signature confirmed by two field reports (21/05/2026):
     *   Build.BRAND = "alps", ro.product.name contains "k65v1", Build.VERSION.SDK_INT == 28.
     * Conservative: requires brand=alps + product containing "k65" + API 28-29.
     * Returns false on any uncertainty so DL3/DL5 logic stays unaffected.
     */
    private static boolean detectDiLink2(String brand, String product, int api) {
        String b = (brand   == null ? "" : brand).toLowerCase();
        String p = (product == null ? "" : product).toLowerCase();
        if (!"alps".equals(b)) return false;
        if (!p.contains("k65")) return false;
        return api == 28 || api == 29;
    }

    // ── Raw snapshot accessors ────────────────────────────────────────────────

    public String  rawProductName()  { return rawProductName; }
    public String  rawModel()        { return rawModel; }
    public String  rawBrand()        { return rawBrand; }
    public String  rawFingerprint()  { return rawFingerprint; }
    public int     androidApi()      { return androidApi; }
    public boolean isAutoDetectedDiLink5() { return autoDiLink5; }
    public boolean isAutoDetectedDiLink4() { return autoDiLink4; }
    public boolean isAutoDetectedDiLink2() { return autoDiLink2; }

    /**
     * Effective DiLink 4 mode — there is no user override for DL4 (no toggle in
     * Settings). DL4 is mutually exclusive with DL5: the {@link #isDiLink5} getter
     * neutralises any FORCE_ON DL5 override when {@code autoDiLink4} is true, so a
     * mis-flipped switch in Settings does not push a DL4 device onto the DL5
     * activation path (which calls the snake_case {@code auto_container} binder
     * that does not exist on the DL3/DL4 service namespace).
     */
    public boolean isDiLink4(Context ctx) {
        return autoDiLink4;
    }

    /**
     * Effective DiLink 2 mode — there is no user override for DL2 (no toggle in Settings).
     * This is read every call so future override hooks can be added without API changes.
     * Safe to call from any thread; cached at process start via the singleton.
     */
    public boolean isDiLink2(Context ctx) {
        return autoDiLink2;
    }

    // ── Effective state (auto + user override) ────────────────────────────────

    /**
     * Effective DiLink 5 mode after applying the user override.
     * Read from SharedPreferences each call so changes are visible without
     * a process restart for non-critical UI bits. Hot code paths should
     * cache this value at process start.
     */
    public boolean isDiLink5(Context ctx) {
        // Hard guard: a device auto-detected as DiLink 4 can never be DiLink 5,
        // regardless of the user override. The two generations share the BYD-AUTO
        // brand + DisplayManager primitives but differ on the AutoContainer binder
        // name (DL3/DL4 = "AutoContainer" PascalCase, DL5 = "auto_container"
        // snake_case). Field log BYD_RE_Sniffer_20260523_173033 caught a DL4 testeur
        // who had FORCE_ON DL5 enabled in Settings, which sent every projection
        // attempt at the non-existent snake_case binder. This guard absorbs the
        // mistake transparently so misconfigured Settings cannot break DL4 cars.
        if (autoDiLink4) return false;
        String ov = readOverride(ctx);
        if (OV_FORCE_ON.equals(ov))  return true;
        if (OV_FORCE_OFF.equals(ov)) return false;
        return autoDiLink5;
    }

    /** Short summary used for diagnostics ("AUTO=on", "FORCED off", …). */
    public String describeMode(Context ctx) {
        if (autoDiLink4) {
            String ov = readOverride(ctx);
            if (OV_FORCE_ON.equals(ov)) return "AUTO=off (DL4 detected — DL5 FORCE_ON ignored)";
            return "AUTO=off (DL4 detected)";
        }
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
