package com.byd.dashcast;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SysInfoActivity — generates a complete diagnostic report of the BYD system.
 *
 * Collects and displays:
 *  - Android / device / build info
 *  - All detected displays (id, size, density, flags, category)
 *  - Methods available in ActivityOptions via reflection (public + @hide)
 *  - BYD system packages installed
 *  - Android system properties (ro.product.*, ro.build.*, sys.*)
 *  - BYDAUTO permissions granted or denied
 *  - ADB TCP connectivity
 *
 * The report is saved in:
 *   /sdcard/Android/data/com.byd.dashcast/files/byd_report_<date>.txt
 * Retrievable via: adb pull /sdcard/Android/data/com.byd.dashcast/files/
 */
public class SysInfoActivity extends AppCompatActivity {

    private TextView tvReport;
    private ScrollView scrollView;
    private Button btnGenerate;
    private Button btnSave;
    private Button btnShare;
    private StringBuilder mReport;

    private volatile boolean mDestroyed = false;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sysinfo);

        tvReport    = (TextView)   findViewById(R.id.tv_report);
        scrollView  = (ScrollView) findViewById(R.id.scroll_report);
        btnGenerate = (Button)     findViewById(R.id.btn_generate);
        btnSave     = (Button)     findViewById(R.id.btn_save);
        btnShare    = (Button)     findViewById(R.id.btn_share);

        btnSave.setEnabled(false);
        btnShare.setEnabled(false);

        btnGenerate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startGenerate(); }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveReport(); }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mReport != null) {
                    AppLogger.shareWithReport(SysInfoActivity.this, mReport.toString());
                } else {
                    AppLogger.share(SysInfoActivity.this);
                }
            }
        });

        // ─── v0.9.82 — M3 redesign wiring ───
        wireSysInfoNavRail();
        populateOverviewTiles();
        populateDisplaysList();
        populateServicesList();
        // Auto-generate the full mono report on first open so the right column
        // is filled immediately (Régénérer button is then for refreshing).
        startGenerate();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
    }

    // =========================================================================
    // Report generation (ExecutorService — network off main thread)
    // =========================================================================
    /** Publishes an incremental update to the TextView from a worker thread. */
    private void publishUpdate(final String text) {
        if (mDestroyed) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // Recheck on the main thread: between the worker-side check above
                // and the Runnable being dispatched, onDestroy may have fired.
                if (mDestroyed) return;
                tvReport.setText(colorizeReport(text));
                scrollView.post(new Runnable() {
                    @Override public void run() { scrollView.fullScroll(ScrollView.FOCUS_DOWN); }
                });
            }
        });
    }

    private void startGenerate() {
        btnGenerate.setEnabled(false);
        btnSave.setEnabled(false);
        tvReport.setText(getString(R.string.sysinfo_generating));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(new Runnable() {
            @Override public void run() {
                final String result = generateReport();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mDestroyed) return;
                        AppLogger.log("SysInfo", "Report generated");
                        mReport = new StringBuilder(result);
                        tvReport.setText(colorizeReport(result));
                        btnGenerate.setEnabled(true);
                        btnSave.setEnabled(true);
                        btnShare.setEnabled(true);
                        scrollView.post(new Runnable() {
                            @Override public void run() { scrollView.fullScroll(ScrollView.FOCUS_DOWN); }
                        });
                    }
                });
            }
        });
        executor.shutdown();
    }

    private String generateReport() {
            StringBuilder sb = new StringBuilder();

            section(sb, "BYD SEAL DIAGNOSTIC REPORT");
            sb.append("Date : ").append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date())).append("\n");
            sb.append("App  : ").append(getPackageName()).append("\n");
            publishUpdate(sb.toString());

            // 1. System info
            section(sb, "1. ANDROID SYSTEM");
            sb.append("Model         : ").append(Build.MODEL).append("\n");
            sb.append("Manufacturer  : ").append(Build.MANUFACTURER).append("\n");
            sb.append("Brand         : ").append(Build.BRAND).append("\n");
            sb.append("Product       : ").append(Build.PRODUCT).append("\n");
            sb.append("Device        : ").append(Build.DEVICE).append("\n");
            sb.append("Android       : ").append(Build.VERSION.RELEASE)
              .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Build ID      : ").append(Build.ID).append("\n");
            sb.append("Fingerprint   : ").append(Build.FINGERPRINT).append("\n");
            sb.append("Hardware      : ").append(Build.HARDWARE).append("\n");
            publishUpdate(sb.toString());

            // 2. System properties
            section(sb, "2. SYSTEM PROPERTIES (SystemProperties)");
            String[] props = {
                "ro.product.model", "ro.product.brand", "ro.product.name",
                "ro.product.device", "ro.product.manufacturer",
                "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.fingerprint", "ro.build.description",
                "ro.build.display.id", "ro.build.type",
                "ro.byd.version", "ro.byd.model", "ro.byd.car.type",
                "ro.byd.cluster.enable", "ro.byd.secondary.display",
                "persist.sys.byd.cluster", "persist.sys.secondary_display",
                "sys.byd.cluster", "sys.display.secondary",
                "ro.sf.lcd_density", "qemu.sf.lcd_density",
                "ro.hardware.gralloc", "ro.hardware.hwcomposer",
                "ro.config.low_ram", "dalvik.vm.heapsize"
            };
            for (String prop : props) {
                String val = getSystemProp(prop);
                sb.append(String.format("%-40s = %s\n", prop, val));
            }
            publishUpdate(sb.toString());

            // 3. Detected displays
            section(sb, "3. DETECTED DISPLAYS");
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display[] allDisplays = dm.getDisplays();
            sb.append("Total displays : ").append(allDisplays.length).append("\n\n");
            for (Display d : allDisplays) {
                sb.append("  Display #").append(d.getDisplayId()).append("\n");
                sb.append("    Name      : ").append(d.getName()).append("\n");
                sb.append("    Size      : ").append(getDisplaySize(d)).append("\n");
                sb.append("    Flags     : 0x").append(Integer.toHexString(d.getFlags()))
                  .append(" ").append(displayFlagsToString(d.getFlags())).append("\n");
                sb.append("    Rotation  : ").append(d.getRotation()).append("\n");
                sb.append("    State     : ").append(displayStateToString(d.getState())).append("\n");
                sb.append("\n");
            }

            Display[] presentationDisplays = dm.getDisplays(
                    DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            sb.append("Displays CATEGORY_PRESENTATION : ")
              .append(presentationDisplays.length).append("\n");
            for (Display d : presentationDisplays) {
                sb.append("  → #").append(d.getDisplayId())
                  .append(" ").append(d.getName())
                  .append(" ").append(getDisplaySize(d)).append("\n");
            }
            publishUpdate(sb.toString());

            // 4. ActivityOptions reflection
            section(sb, "4. ACTIVITYOPTIONS — AVAILABLE METHODS");
            try {
                Method[] methods = android.app.ActivityOptions.class.getDeclaredMethods();
                for (Method m : methods) {
                    sb.append("  ")
                      .append(Modifier.isPublic(m.getModifiers()) ? "public " : "hidden ")
                      .append(m.getReturnType().getSimpleName())
                      .append(" ").append(m.getName())
                      .append(paramsToString(m.getParameterTypes()))
                      .append("\n");
                }
            } catch (Exception e) {
                sb.append("  Reflection error: ").append(e.getMessage()).append("\n");
            }

            // setLaunchDisplayId specific
            sb.append("\n  setLaunchDisplayId available: ");
            try {
                android.app.ActivityOptions.class
                        .getDeclaredMethod("setLaunchDisplayId", int.class);
                sb.append("YES\n");
            } catch (NoSuchMethodException e) {
                sb.append("NO\n");
            }
            publishUpdate(sb.toString());

            // 5. BYD packages installed
            section(sb, "5. BYD SYSTEM PACKAGES");
            PackageManager pm = getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(0);
            int bydCount = 0;
            for (PackageInfo pi : packages) {
                String pkg = pi.packageName;
                if (pkg.contains("byd") || pkg.contains("automap")
                        || pkg.contains("cluster") || pkg.contains("instrument")
                        || pkg.contains("launchermap")) {
                    sb.append("  ").append(pkg)
                      .append(" (").append(pi.versionName).append(")\n");
                    bydCount++;
                }
            }
            if (bydCount == 0) sb.append("  No BYD package found\n");
            publishUpdate(sb.toString());

            // 6. Permissions BYDAUTO
            section(sb, "6. PERMISSIONS BYDAUTO");
            String[] bydPerms = {
                "android.permission.BYDAUTO_SPEED_COMMON",
                "android.permission.BYDAUTO_SPEED_GET",
                "android.permission.BYDAUTO_ENERGY_COMMON",
                "android.permission.BYDAUTO_ENERGY_GET",
                "android.permission.BYDAUTO_GEARBOX_COMMON",
                "android.permission.BYDAUTO_GEARBOX_GET",
                "android.permission.BYDAUTO_INSTRUMENT_COMMON",
                "android.permission.BYDAUTO_INSTRUMENT_GET",
                "android.permission.BYDAUTO_BODYWORK_COMMON",
                "android.permission.BYDAUTO_BODYWORK_GET",
                "android.permission.BYDAUTO_AC_COMMON",
                "android.permission.BYDAUTO_AC_GET",
                "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
                "android.permission.BYDAUTO_DOOR_LOCK_GET",
                "android.permission.BYDAUTO_ENGINE_COMMON",
                "android.permission.BYDAUTO_ENGINE_GET",
                "android.permission.BYDAUTO_LIGHT_COMMON",
                "android.permission.BYDAUTO_LIGHT_GET",
                "android.permission.BYDAUTO_TYRE_COMMON",
                "android.permission.BYDAUTO_TYRE_GET",
                "android.permission.BYDAUTO_RADAR_COMMON",
                "android.permission.BYDAUTO_RADAR_GET",
                // SAFETYBELT_COMMON/_GET removed: "Unknown permission" on ROM Seal EU
                "android.permission.BYDAUTO_SENSOR_GET",
            };
            for (String perm : bydPerms) {
                int result = checkSelfPermission(perm);
                sb.append(String.format("  %-50s %s\n",
                        perm.replace("android.permission.", ""),
                        result == PackageManager.PERMISSION_GRANTED ? "✓ GRANTED" : "✗ DENIED"));
            }
            publishUpdate(sb.toString());

            // 7. ADB connectivity
            section(sb, "7. ADB TCP CONNECTIVITY");
            int[] ports = {5037, 5555, 5554};
            for (int port : ports) {
                boolean open = isPortOpen("127.0.0.1", port, 800);
                sb.append(String.format(java.util.Locale.US, "  127.0.0.1:%-5d %s\n",
                        port, open ? "OPEN    ✓" : "closed"));
            }
            publishUpdate(sb.toString());

            // 8. BYD API test (speed)
            section(sb, "8. BYD API — TEST INSTANTIATION");
            sb.append(tryInstantiateBydApi());
            publishUpdate(sb.toString());

            // 8b. BYD live vehicle data (Bodywork + Speed + Energy snapshots)
            section(sb, "8b. BYD VEHICLE DATA (live)");
            sb.append(snapshotBydLiveData());
            publishUpdate(sb.toString());

            // Recent events at the very end (mockup-faithful colored block).
            appendRecentEvents(sb, 5);

            sb.append("\n=== END OF REPORT ===\n");
            return sb.toString();
    }

    // =========================================================================
    // Save to file
    // =========================================================================

    private void saveReport() {
        if (mReport == null) return;

        String filename = "byd_report_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                + ".txt";

        // getExternalFilesDir() = /sdcard/Android/data/com.byd.dashcast/files/
        // Accessible without permission on API 25+, retrievable via:
        //   adb pull /sdcard/Android/data/com.byd.dashcast/files/
        File outDir = getExternalFilesDir(null);
        if (outDir == null) {
            outDir = getFilesDir(); // fallback internal storage
        }
        if (!outDir.exists()) outDir.mkdirs();

        File outFile = new File(outDir, filename);
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(mReport.toString());
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.toast_write_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this,
                getString(R.string.toast_report_saved, outFile.getAbsolutePath()),
                Toast.LENGTH_LONG).show();

        // Display the path in the report itself
        tvReport.append(getString(R.string.sysinfo_file_saved, outFile.getAbsolutePath()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void section(StringBuilder sb, String title) {
        // Single-line ═══ TITLE ═══ header — colorized in colorizeReport().
        sb.append("\n═══ ").append(title).append(" ═══\n");
    }

    /**
     * Builds a Spannable from the plain mono report applying M3 syntax colors:
     *   ═══ … ═══   → md_primary  (section headers)
     *   GRANTED / OPEN / ✓ / RUN  → md_status_ok
     *   DENIED / closed / ✗ / WARN/ [W] → md_status_warn
     *   ERROR / [E]  → md_status_err
     */
    private CharSequence colorizeReport(String text) {
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(text);
        int cPrim = androidx.core.content.ContextCompat.getColor(this, R.color.md_primary);
        int cOk   = androidx.core.content.ContextCompat.getColor(this, R.color.md_status_ok);
        int cWarn = androidx.core.content.ContextCompat.getColor(this, R.color.md_status_warn);
        int cErr  = androidx.core.content.ContextCompat.getColor(this, R.color.md_status_err);
        applyRegex(ssb, "═══ [^═]+ ═══",                       cPrim, true);
        applyRegex(ssb, "\\b(GRANTED|OPEN|RUN|CONN|YES)\\b",   cOk,   true);
        applyRegex(ssb, "✓",                                    cOk,   true);
        applyRegex(ssb, "\\b(DENIED|closed|OFF|NO)\\b",        cWarn, true);
        applyRegex(ssb, "✗",                                    cWarn, true);
        applyRegex(ssb, "\\[I\\]",                              cOk,   true);
        applyRegex(ssb, "\\[W\\]",                              cWarn, true);
        applyRegex(ssb, "\\[E\\]",                              cErr,  true);
        return ssb;
    }

    private static void applyRegex(android.text.SpannableStringBuilder ssb,
                                   String regex, int color, boolean bold) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(ssb);
        while (m.find()) {
            ssb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    m.start(), m.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        m.start(), m.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    /** Appends a "RECENT EVENTS (last N)" block from AppLogger. */
    private static void appendRecentEvents(StringBuilder sb, int n) {
        java.util.List<AppLogger.Entry> entries = AppLogger.getEntries();
        sb.append("\n═══ RECENT EVENTS (last ").append(n).append(") ═══\n");
        if (entries.isEmpty()) { sb.append("  (no events yet)\n"); return; }
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        int from = Math.max(0, entries.size() - n);
        for (int i = from; i < entries.size(); i++) {
            AppLogger.Entry e = entries.get(i);
            String lvl;
            switch (e.level) {
                case ERROR: lvl = "[E]"; break;
                case WARN:  lvl = "[W]"; break;
                default:    lvl = "[I]"; break;
            }
            sb.append(tf.format(new Date(e.timestamp))).append(' ')
              .append(lvl).append(' ').append(e.tag).append(" ")
              .append(e.message).append('\n');
        }
    }

    private String getSystemProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, key, "");
            return val == null || val.isEmpty() ? "(undefined)" : val;
        } catch (Exception e) {
            return "(erreur: " + e.getMessage() + ")";
        }
    }

    private String getDisplaySize(Display d) {
        DisplayMetrics metrics = new DisplayMetrics();
        d.getMetrics(metrics);
        return metrics.widthPixels + "x" + metrics.heightPixels
                + " @" + metrics.densityDpi + "dpi";
    }

    private String displayFlagsToString(int flags) {
        List<String> parts = new ArrayList<>();
        if ((flags & Display.FLAG_SECURE)            != 0) parts.add("SECURE");
        if ((flags & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) parts.add("PROTECTED");
        if ((flags & Display.FLAG_PRIVATE)           != 0) parts.add("PRIVATE");
        if ((flags & Display.FLAG_PRESENTATION)      != 0) parts.add("PRESENTATION");
        return parts.isEmpty() ? "none" : parts.toString();
    }

    private String displayStateToString(int state) {
        switch (state) {
            case Display.STATE_ON:      return "ON";
            case Display.STATE_OFF:     return "OFF";
            case Display.STATE_DOZE:    return "DOZE";
            case 4:                     return "DOZE_SUSPEND"; // Display.STATE_DOZE_SUSPEND = 4 (API 23+)
            case Display.STATE_UNKNOWN: return "UNKNOWN";
            default:                    return "state=" + state;
        }
    }

    private String paramsToString(Class<?>[] types) {
        if (types.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getSimpleName());
        }
        sb.append(")");
        return sb.toString();
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Snapshots a few live values from the BYD API for the report:
     *   Bodywork: VIN, modelName, doorState(driver,passenger), powerLevel, batteryVoltageLevel
     *   Speed:    currentSpeed
     *   Energy:   SOC (battery state of charge)
     * All calls done via reflection so the app builds without the BYD SDK on the classpath.
     */
    private String snapshotBydLiveData() {
        StringBuilder sb = new StringBuilder();
        sb.append(probeBydCall("Bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
                new String[]{"getAutoVIN", "getAutoModelName", "getPowerLevel", "getBatteryVoltageLevel"}));
        sb.append(probeBydCall("Speed",    "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
                new String[]{"getCurrentSpeed", "getAccelPedalDepth", "getBrakePedalDepth"}));
        sb.append(probeBydCall("Energy",   "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
                new String[]{"getElecBatteryRemainingCapacity", "getMileage", "getEvRemainingMileage"}));
        return sb.toString();
    }

    /** Calls a list of zero-arg getters on a BYD device and formats `key = value` lines. */
    private String probeBydCall(String label, String className, String[] methods) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ── ").append(label).append("\n");
        try {
            Class<?> cls = Class.forName(className);
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object dev = getInstance.invoke(null, getApplicationContext());
            if (dev == null) {
                sb.append("    (getInstance returned null)\n");
                return sb.toString();
            }
            for (String m : methods) {
                try {
                    Object v = cls.getMethod(m).invoke(dev);
                    sb.append(String.format("    %-32s = %s\n", m, String.valueOf(v)));
                } catch (NoSuchMethodException nsme) {
                    sb.append(String.format("    %-32s = (no such method)\n", m));
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    sb.append(String.format("    %-32s ✗ %s\n", m,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                }
            }
        } catch (ClassNotFoundException e) {
            sb.append("    (class not in runtime SDK)\n");
        } catch (Throwable t) {
            sb.append("    ✗ ").append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private String tryInstantiateBydApi() {
        StringBuilder sb = new StringBuilder();
        String[] devices = {
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        };
        for (String className : devices) {
            String shortName = className.substring(className.lastIndexOf('.') + 1);
            try {
                Class<?> cls = Class.forName(className);
                Method getInstance = cls.getMethod("getInstance", Context.class);
                Object instance = getInstance.invoke(null, getApplicationContext());
                sb.append(String.format("  %-35s %s\n",
                        shortName,
                        instance != null ? "✓ getInstance() OK" : "✗ getInstance() → null"));
            } catch (SecurityException e) {
                sb.append(String.format("  %-35s ✗ SecurityException (missing permission)\n", shortName));
            } catch (ClassNotFoundException e) {
                sb.append(String.format("  %-35s ✗ Class not found in runtime SDK\n", shortName));
            } catch (NoSuchMethodException e) {
                sb.append(String.format("  %-35s ✗ getInstance() not found\n", shortName));
            } catch (Exception e) {
                sb.append(String.format("  %-35s ✗ %s\n", shortName,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // v0.9.82 — M3 redesign: nav rail + overview tiles + lists
    // =========================================================================

    private void wireSysInfoNavRail() {
        View navApps     = findViewById(R.id.nav_apps_sys);
        View navSettings = findViewById(R.id.nav_settings_sys);
        View navDiag     = findViewById(R.id.nav_diag_sys);
        View navLog      = findViewById(R.id.nav_log_sys);
        View navLogo     = findViewById(R.id.iv_nav_logo_sys);
        if (navApps != null)     navApps.setOnClickListener(v -> { startActivity(new android.content.Intent(this, MainActivity.class)); finish(); });
        if (navSettings != null) navSettings.setOnClickListener(v -> { startActivity(new android.content.Intent(this, SettingsActivity.class)); finish(); });
        if (navDiag != null)     navDiag.setOnClickListener(v -> { startActivity(new android.content.Intent(this, DiagActivity.class)); finish(); });
        if (navLog != null)      navLog.setOnClickListener(v -> { startActivity(new android.content.Intent(this, LogActivity.class)); finish(); });
        if (navLogo != null)     navLogo.setOnClickListener(v -> { startActivity(new android.content.Intent(this, MainActivity.class)); finish(); });
    }

    private void populateOverviewTiles() {
        // Vehicle — tries the BYD Bodywork API (getAutoVIN + getAutoModelName) on a
        // worker thread; falls back to system properties (ro.byd.car.type / ro.byd.model)
        // and finally to Build.MODEL. DiLink version comes from ro.byd.* sysprops.
        final TextView vVal = findViewById(R.id.tv_tile_vehicle_value);
        final TextView vSub = findViewById(R.id.tv_tile_vehicle_sub);
        if (vVal != null) vVal.setText(prettyVehicleNameSync()); // immediate fallback
        if (vSub != null) vSub.setText(prettyDilinkVersion());
        new Thread(new Runnable() { @Override public void run() {
            final String[] biz = fetchBydVehicleInfo();
            runOnUiThread(new Runnable() { @Override public void run() {
                if (mDestroyed) return;
                if (biz[0] != null && !biz[0].isEmpty() && vVal != null) vVal.setText(biz[0]);
                if (biz[1] != null && !biz[1].isEmpty() && vSub != null) vSub.setText(biz[1]);
            }});
        }}, "sysinfo-byd").start();

        // Android
        TextView aVal = findViewById(R.id.tv_tile_android_value);
        TextView aSub = findViewById(R.id.tv_tile_android_sub);
        if (aVal != null) aVal.setText(Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        if (aSub != null) {
            String disp = Build.DISPLAY != null && !Build.DISPLAY.isEmpty() ? Build.DISPLAY : Build.ID;
            aSub.setText("Build " + disp + " · " + Build.SUPPORTED_ABIS[0]);
        }

        // DashCast
        TextView dVal = findViewById(R.id.tv_tile_dashcast_value);
        TextView dSub = findViewById(R.id.tv_tile_dashcast_sub);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (dVal != null) dVal.setText("v" + pi.versionName);
            int code;
            if (Build.VERSION.SDK_INT >= 28) code = (int) pi.getLongVersionCode();
            else code = pi.versionCode;
            String signTag = isPlatformSigned()
                    ? getString(R.string.sysinfo_tile_dashcast_platform_signed)
                    : getString(R.string.sysinfo_tile_dashcast_user_signed);
            if (dSub != null) dSub.setText("build " + code + " · " + signTag);
        } catch (PackageManager.NameNotFoundException e) {
            if (dVal != null) dVal.setText("?");
            if (dSub != null) dSub.setText("");
        }

        // Uptime
        long uptimeMs = android.os.SystemClock.elapsedRealtime();
        long sinceMs  = System.currentTimeMillis() - uptimeMs;
        TextView uVal = findViewById(R.id.tv_tile_uptime_value);
        TextView uSub = findViewById(R.id.tv_tile_uptime_sub);
        if (uVal != null) uVal.setText(formatUptime(uptimeMs));
        if (uSub != null) uSub.setText(getString(R.string.sysinfo_tile_uptime_since,
                new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(sinceMs))));
    }

    /** Sync best-guess of vehicle name without touching the BYD service (used as instant fallback). */
    private String prettyVehicleNameSync() {
        String t = getSystemProp("ro.byd.car.type");
        if (!t.startsWith("(") && !t.isEmpty()) return "BYD " + t;
        String m = getSystemProp("ro.byd.model");
        if (!m.startsWith("(") && !m.isEmpty()) return "BYD " + m;
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    /** Returns a human DiLink version string — sysprop with several fallbacks. */
    private String prettyDilinkVersion() {
        String[] keys = {"ro.byd.dilink.version", "ro.byd.version", "ro.build.display.id"};
        for (String k : keys) {
            String v = getSystemProp(k);
            if (!v.startsWith("(") && !v.isEmpty()) {
                if (k.startsWith("ro.byd.dilink")) return "DiLink " + v;
                if (k.equals("ro.byd.version"))    return "DiLink " + v;
                return v; // ro.build.display.id (e.g., "QPR3 / 2024.08")
            }
        }
        return Build.BRAND + " · " + Build.PRODUCT;
    }

    /**
     * Calls BYDAutoBodyworkDevice.getAutoVIN() + getAutoModelName() via reflection.
     * Returns [headline, subtitle] (either may be empty if the call fails).
     */
    private String[] fetchBydVehicleInfo() {
        String headline = "", subtitle = "";
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object dev = getInstance.invoke(null, getApplicationContext());
            if (dev != null) {
                String vin = "";
                try {
                    Object v = cls.getMethod("getAutoVIN").invoke(dev);
                    if (v instanceof String) vin = (String) v;
                } catch (Throwable ignored) {}
                int modelCode = -1;
                try {
                    Object m = cls.getMethod("getAutoModelName").invoke(dev);
                    if (m instanceof Integer) modelCode = (Integer) m;
                } catch (Throwable ignored) {}
                String pretty = prettyVehicleNameSync();
                headline = pretty;
                StringBuilder sub = new StringBuilder();
                sub.append(prettyDilinkVersion());
                if (modelCode > 0) sub.append(" · model#").append(modelCode);
                if (vin.length() >= 4) sub.append(" · VIN …").append(vin.substring(vin.length() - 4));
                subtitle = sub.toString();
            }
        } catch (Throwable t) {
            // BYD service not reachable (missing perm, ROM stripped) — keep fallbacks.
        }
        return new String[]{headline, subtitle};
    }

    /** True iff the package is signed by the same certificate as the system "android" package. */
    private boolean isPlatformSigned() {
        try {
            PackageManager pm = getPackageManager();
            int sigMatch = pm.checkSignatures("android", getPackageName());
            return sigMatch == PackageManager.SIGNATURE_MATCH;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "min";
        return m + "min";
    }

    private void populateDisplaysList() {
        android.widget.LinearLayout container = findViewById(R.id.ll_displays_list);
        if (container == null) return;
        container.removeAllViews();
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);
        for (Display d : displays) {
            View row = inf.inflate(R.layout.row_sysinfo, container, false);
            android.widget.ImageView icon  = row.findViewById(R.id.row_icon);
            TextView headline              = row.findViewById(R.id.row_headline);
            TextView support               = row.findViewById(R.id.row_support);
            TextView badge                 = row.findViewById(R.id.row_badge);
            int id = d.getDisplayId();
            String name = d.getName() != null ? d.getName() : "?";
            String label;
            int iconRes;
            if (id == 0) { label = getString(R.string.sysinfo_disp_main);     iconRes = R.drawable.ic_tv; }
            else if (name.toLowerCase(Locale.ROOT).contains("virtual") ||
                     name.toLowerCase(Locale.ROOT).contains("mirror"))
                  { label = getString(R.string.sysinfo_disp_virtual); iconRes = R.drawable.ic_screen_share; }
            else  { label = getString(R.string.sysinfo_disp_cluster); iconRes = R.drawable.ic_dashboard; }
            headline.setText(getString(R.string.sysinfo_disp_row_headline, id, label));
            support.setText(getDisplaySize(d) + " · " + name);
            icon.setImageResource(iconRes);
            badge.setText("●");
            container.addView(row);
        }
    }

    private void populateServicesList() {
        android.widget.LinearLayout container = findViewById(R.id.ll_services_list);
        if (container == null) return;
        container.removeAllViews();
        android.view.LayoutInflater inf = android.view.LayoutInflater.from(this);

        // ClusterService — runs in our app process, so own pid + app uptime.
        boolean clusterRunning = isServiceRunning(ClusterService.class);
        String clusterSub;
        if (clusterRunning) {
            int pid = android.os.Process.myPid();
            clusterSub = "pid " + pid + " · " + formatUptime(android.os.SystemClock.elapsedRealtime());
        } else {
            clusterSub = getString(R.string.sysinfo_svc_stopped);
        }
        addServiceRow(inf, container, "ClusterService", clusterSub, clusterRunning, false);

        // MirrorDaemon — separate process started via ADB (uid=2000); pid via pgrep.
        final TextView mirrorSub = addServiceRow(inf, container, "MirrorDaemon",
                clusterRunning ? getString(R.string.sysinfo_svc_mirror_sub)
                               : getString(R.string.sysinfo_svc_stopped),
                clusterRunning, false);
        if (clusterRunning && mirrorSub != null) {
            // Run pgrep off the main thread to avoid StrictMode disk/exec on UI.
            new Thread(new Runnable() { @Override public void run() {
                final int pid = pidOf("MirrorDaemon");
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (mDestroyed) return;
                    if (pid > 0) mirrorSub.setText("pid " + pid + " · poll 500 ms");
                    else         mirrorSub.setText(getString(R.string.sysinfo_svc_mirror_sub));
                }});
            }}, "sysinfo-pidof").start();
        }

        // ADB local — quick TCP probe on 127.0.0.1:5555 (CONN if connected).
        boolean adbOk = isPortOpen("127.0.0.1", 5555, 200);
        addServiceRow(inf, container, "AdbLocalClient",
                adbOk ? "127.0.0.1:5555" : getString(R.string.sysinfo_svc_adb_unreachable),
                adbOk, true /* useConnBadge */);
    }

    /** Adds one service row; returns its support TextView so the caller can update it later. */
    private TextView addServiceRow(android.view.LayoutInflater inf,
                                   android.widget.LinearLayout container,
                                   String name, String sub, boolean running, boolean useConnBadge) {
        View row = inf.inflate(R.layout.row_sysinfo, container, false);
        android.widget.ImageView icon = row.findViewById(R.id.row_icon);
        View leading = row.findViewById(R.id.row_leading);
        icon.setImageResource(R.drawable.ic_play_circle);
        if (running) {
            if (leading != null) leading.setBackgroundResource(R.drawable.bg_service_icon_running);
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.md_status_ok));
        } else {
            icon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.md_outline_variant));
        }
        ((TextView) row.findViewById(R.id.row_headline)).setText(name);
        TextView supportTv = row.findViewById(R.id.row_support);
        supportTv.setText(sub);
        TextView badge = row.findViewById(R.id.row_badge);
        if (running) {
            badge.setText(getString(useConnBadge ? R.string.sysinfo_svc_conn : R.string.sysinfo_svc_run));
            badge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.md_status_ok));
        } else {
            badge.setText(getString(R.string.sysinfo_svc_off));
            badge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.md_status_err));
        }
        container.addView(row);
        return supportTv;
    }

    /** Returns the first PID matching the given pattern via `pgrep -f`, or -1. */
    private static int pidOf(String pattern) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", "pgrep -f " + pattern + " | head -n 1")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            br.close();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Throwable t) {
            // pgrep may not be available on all ROMs; ignore.
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> svcClass) {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            for (android.app.ActivityManager.RunningServiceInfo si :
                    am.getRunningServices(Integer.MAX_VALUE)) {
                if (svcClass.getName().equals(si.service.getClassName())) return true;
            }
        } catch (Throwable t) {
            // ActivityManager.getRunningServices() returns only own-process services since API 26.
        }
        return false;
    }
}
