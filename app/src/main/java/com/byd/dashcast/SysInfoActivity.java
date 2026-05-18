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
    }

    // =========================================================================
    // Report generation (ExecutorService — network off main thread)
    // =========================================================================

    /** Publishes an incremental update to the TextView from a worker thread. */
    private void publishUpdate(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                tvReport.setText(text);
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
                        AppLogger.log("SysInfo", "Report generated");
                        mReport = new StringBuilder(result);
                        tvReport.setText(result);
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

            sb.append("\n=== END OF REPORT ===\n");

            // Logbook in appendix
            section(sb, "9. LOGBOOK (AppLogger)");
            String log = AppLogger.get();
            sb.append(log.isEmpty() ? "  (empty log)\n" : log);

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
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 60; i++) line.append('─');
        sb.append("\n").append(line).append("\n");
        sb.append(title).append("\n");
        sb.append(line).append("\n");
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
}
