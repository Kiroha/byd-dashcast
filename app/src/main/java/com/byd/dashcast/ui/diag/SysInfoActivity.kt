package com.byd.dashcast.ui.diag

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.byd.dashcast.MainActivity
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.display.ClusterManager
import com.byd.dashcast.cluster.dpi.ClusterDpiManager
import com.byd.dashcast.cluster.dpi.ClusterDpiPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ProxyMetrics
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.ui.log.LogActivity
import com.byd.dashcast.ui.nav.NavRailHotspot
import com.byd.dashcast.ui.nav.NavRailLayouts
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.LocaleHelper
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * SysInfoActivity — generates a complete diagnostic report of the BYD system.
 *
 * The report is saved in:
 *   /sdcard/Android/data/com.byd.dashcast/files/byd_report_<date>.txt
 * Retrievable via: adb pull /sdcard/Android/data/com.byd.dashcast/files/
 */
@Suppress("DEPRECATION")
@SuppressLint("SetTextI18n")
class SysInfoActivity : AppCompatActivity() {

    private lateinit var tvReport: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnGenerate: Button
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private var mReport: StringBuilder? = null

    @Volatile
    private var mDestroyed = false
    // SI-2: single reusable executor — avoids creating a new thread pool on every startGenerate() call.
    private val mReportExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sysinfo-report").apply { isDaemon = true }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sysinfo)

        tvReport = findViewById(R.id.tv_report)
        scrollView = findViewById(R.id.scroll_report)
        btnGenerate = findViewById(R.id.btn_generate)
        btnSave = findViewById(R.id.btn_save)
        btnShare = findViewById(R.id.btn_share)

        btnSave.isEnabled = false
        btnShare.isEnabled = false

        btnGenerate.setOnClickListener { startGenerate() }
        btnSave.setOnClickListener { saveReport() }
        btnShare.setOnClickListener {
            val r = mReport
            if (r != null) {
                AppLogger.shareWithReport(this, r.toString())
            } else {
                AppLogger.share(this)
            }
        }

        // v1.2.76 — Reboot button (ADB-shell reboot behind a confirmation dialog).
        val btnReboot: Button? = findViewById(R.id.btn_reboot)
        btnReboot?.setOnClickListener { confirmAndReboot() }

        // ─── v0.9.82 — M3 redesign wiring ───
        wireSysInfoNavRail()
        populateOverviewTiles()
        populateDisplaysList()
        populateServicesList()
        // Auto-generate the full mono report on first open.
        startGenerate()
    }

    override fun onDestroy() {
        super.onDestroy()
        mDestroyed = true
        mReportExecutor.shutdown()
    }

    // =========================================================================
    // Report generation (ExecutorService — network off main thread)
    // =========================================================================

    /** Publishes an incremental update to the TextView from a worker thread. */
    private fun publishUpdate(text: String) {
        if (mDestroyed) return
        runOnUiThread {
            // Recheck on the main thread: onDestroy may have fired meanwhile.
            if (mDestroyed) return@runOnUiThread
            // H6: skip colorizeReport() on intermediate updates.
            tvReport.text = text
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startGenerate() {
        btnGenerate.isEnabled = false
        btnSave.isEnabled = false
        tvReport.text = getString(R.string.sysinfo_generating)

        mReportExecutor.submit(Runnable {
            val result = generateReport()
            runOnUiThread {
                if (mDestroyed) return@runOnUiThread
                AppLogger.log("SysInfo", "Report generated")
                mReport = StringBuilder(result)
                tvReport.text = colorizeReport(result)
                btnGenerate.isEnabled = true
                btnSave.isEnabled = true
                btnShare.isEnabled = true
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        })
    }

    private fun generateReport(): String {
        val sb = StringBuilder()

        section(sb, "BYD SEAL DIAGNOSTIC REPORT")
        sb.append("Date : ").append(SDF_REPORT_HEADER.get()!!.format(Date())).append("\n")
        sb.append("App  : ").append(packageName).append("\n")
        publishUpdate(sb.toString())

        // 1. System info
        section(sb, "1. ANDROID SYSTEM")
        sb.append("Model         : ").append(Build.MODEL).append("\n")
        sb.append("Manufacturer  : ").append(Build.MANUFACTURER).append("\n")
        sb.append("Brand         : ").append(Build.BRAND).append("\n")
        sb.append("Product       : ").append(Build.PRODUCT).append("\n")
        sb.append("Device        : ").append(Build.DEVICE).append("\n")
        sb.append("Android       : ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Build ID      : ").append(Build.ID).append("\n")
        sb.append("Fingerprint   : ").append(Build.FINGERPRINT).append("\n")
        sb.append("Hardware      : ").append(Build.HARDWARE).append("\n")
        publishUpdate(sb.toString())

        // 2. System properties
        section(sb, "2. SYSTEM PROPERTIES (SystemProperties)")
        val props = arrayOf(
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
        )
        for (prop in props) {
            val value = getSystemProp(prop)
            sb.append(String.format("%-40s = %s\n", prop, value))
        }
        publishUpdate(sb.toString())

        // 3. Detected displays
        section(sb, "3. DETECTED DISPLAYS")
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val allDisplays = dm.displays
        sb.append("Total displays : ").append(allDisplays.size).append("\n\n")
        for (d in allDisplays) {
            sb.append("  Display #").append(d.displayId).append("\n")
            sb.append("    Name      : ").append(d.name).append("\n")
            sb.append("    Size      : ").append(getDisplaySize(d)).append("\n")
            sb.append("    Flags     : 0x").append(Integer.toHexString(d.flags))
                .append(" ").append(displayFlagsToString(d.flags)).append("\n")
            sb.append("    Rotation  : ").append(d.rotation).append("\n")
            sb.append("    State     : ").append(displayStateToString(d.state)).append("\n")
            sb.append("\n")
        }

        val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        sb.append("Displays CATEGORY_PRESENTATION : ").append(presentationDisplays.size).append("\n")
        for (d in presentationDisplays) {
            sb.append("  → #").append(d.displayId)
                .append(" ").append(d.name)
                .append(" ").append(getDisplaySize(d)).append("\n")
        }
        publishUpdate(sb.toString())

        // 3b. uid=2000 probe — bypasses FLAG_PRIVATE filter.
        sb.append("\n[uid=2000 dumpsys probe — ALL displays incl. PRIVATE]\n")
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            sb.append("[unavailable: ")
                .append(AdbLocalClient.adbTransportDiagnosis())
                .append("]\n")
        } else run {
            val shellOut = arrayOfNulls<String>(1)
            val latch = CountDownLatch(1)
            AdbLocalClient.executeShellWithResult(
                this,
                "dumpsys display | grep -E 'DisplayDeviceInfo\\{|^\\s+(mDisplayId|mLayerStack|isPrivate)=' | head -120",
                object : AdbLocalClient.Callback {
                    override fun onSuccess(r: String?) { shellOut[0] = r; latch.countDown() }
                    override fun onError(e: String?) { shellOut[0] = "[shell error: $e]"; latch.countDown() }
                },
                AdbLocalClient.PROBE_IDLE_TIMEOUT_MS
            )
            try {
                latch.await(8, TimeUnit.SECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            sb.append(shellOut[0] ?: "[timeout — AdbLocalClient unavailable]").append("\n")
        }
        publishUpdate(sb.toString())

        // 4. ActivityOptions reflection
        section(sb, "4. ACTIVITYOPTIONS — AVAILABLE METHODS")
        try {
            val methods = ActivityOptions::class.java.declaredMethods
            for (m in methods) {
                sb.append("  ")
                    .append(if (Modifier.isPublic(m.modifiers)) "public " else "hidden ")
                    .append(m.returnType.simpleName)
                    .append(" ").append(m.name)
                    .append(paramsToString(m.parameterTypes))
                    .append("\n")
            }
        } catch (e: Exception) {
            sb.append("  Reflection error: ").append(e.message).append("\n")
        }

        // setLaunchDisplayId specific
        sb.append("\n  setLaunchDisplayId available: ")
        try {
            ActivityOptions::class.java.getDeclaredMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
            sb.append("YES\n")
        } catch (e: NoSuchMethodException) {
            sb.append("NO\n")
        }
        publishUpdate(sb.toString())

        // 5. BYD packages installed
        section(sb, "5. BYD SYSTEM PACKAGES")
        val pm = packageManager
        val packages = pm.getInstalledPackages(0)
        var bydCount = 0
        for (pi in packages) {
            val pkg = pi.packageName
            if (pkg.contains("byd") || pkg.contains("automap") ||
                pkg.contains("cluster") || pkg.contains("instrument") ||
                pkg.contains("launchermap")
            ) {
                sb.append("  ").append(pkg).append(" (").append(pi.versionName).append(")\n")
                bydCount++
            }
        }
        if (bydCount == 0) sb.append("  No BYD package found\n")
        publishUpdate(sb.toString())

        // 6. Permissions BYDAUTO
        section(sb, "6. PERMISSIONS BYDAUTO")
        val bydPerms = arrayOf(
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
            "android.permission.BYDAUTO_SENSOR_GET"
        )
        for (perm in bydPerms) {
            val result = checkSelfPermission(perm)
            sb.append(
                String.format(
                    "  %-50s %s\n",
                    perm.replace("android.permission.", ""),
                    if (result == PackageManager.PERMISSION_GRANTED) "✓ GRANTED" else "✗ DENIED"
                )
            )
        }
        publishUpdate(sb.toString())

        // 7. ADB connectivity
        section(sb, "7. ADB TCP CONNECTIVITY")
        val ports = intArrayOf(5037, 5555, 5554)
        for (port in ports) {
            val open = isPortOpen("127.0.0.1", port, 800)
            sb.append(String.format(Locale.US, "  127.0.0.1:%-5d %s\n", port, if (open) "OPEN    ✓" else "closed"))
        }
        sb.append("  transport state : ")
            .append(AdbLocalClient.adbTransportState() ?: "OK/untested")
            .append("\n")
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            sb.append("  diagnosis      : ")
                .append(AdbLocalClient.adbTransportDiagnosis())
                .append("\n")
        }
        publishUpdate(sb.toString())

        // 8. BYD API test
        section(sb, "8. BYD API — TEST INSTANTIATION")
        sb.append(tryInstantiateBydApi())
        publishUpdate(sb.toString())

        // 8b. BYD live vehicle data
        section(sb, "8b. BYD VEHICLE DATA (live)")
        sb.append(snapshotBydLiveData())
        publishUpdate(sb.toString())

        // Recent events at the very end.
        appendRecentEvents(sb, 5)

        // v1.2.78 — proxy daemon persistent recovery metrics.
        section(sb, "9. BETA PROXY METRICS")
        sb.append(ProxyMetrics.snapshot(this)).append('\n')

        // v1.2.81 — per-app cluster DPI overrides currently configured.
        section(sb, "10. CLUSTER DPI OVERRIDES")
        val dpiMap = ClusterDpiPrefs.snapshot(this)
        if (dpiMap.isEmpty()) {
            sb.append("(none)\n")
        } else {
            for ((key, value) in dpiMap) {
                sb.append(String.format(Locale.ROOT, "%-50s = %d dpi\n", key, value))
            }
        }
        val cache = ClusterDpiManager.cacheSnapshot()
        if (cache.isNotEmpty()) {
            sb.append("\nApplied right now:\n")
            for ((key, value) in cache) {
                sb.append(String.format(Locale.ROOT, "  display %d → %d dpi\n", key, value))
            }
        }
        sb.append('\n')

        sb.append("\n=== END OF REPORT ===\n")
        return sb.toString()
    }

    // =========================================================================
    // Save to file
    // =========================================================================

    private fun saveReport() {
        val report = mReport ?: return

        val filename = "byd_report_" + SDF_FILE_STAMP.get()!!.format(Date()) + ".txt"

        // getExternalFilesDir() retrievable via adb pull.
        val outDir = getExternalFilesDir(null) ?: filesDir
        if (!outDir.exists()) outDir.mkdirs()

        val outFile = File(outDir, filename)
        val payload = report.toString()
        // H1: reuse mReportExecutor instead of creating a new thread pool per save.
        mReportExecutor.submit(Runnable {
            var error: String? = null
            try {
                FileWriter(outFile).use { it.write(payload) }
            } catch (e: IOException) {
                error = e.message
            }
            val err = error
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err != null) {
                    Toast.makeText(this, getString(R.string.toast_write_error, err), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    getString(R.string.toast_report_saved, outFile.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
                tvReport.append(getString(R.string.sysinfo_file_saved, outFile.absolutePath))
            }
        })
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun section(sb: StringBuilder, title: String) {
        sb.append("\n═══ ").append(title).append(" ═══\n")
    }

    /** Builds a Spannable from the plain mono report applying M3 syntax colors. */
    private fun colorizeReport(text: String): CharSequence {
        val ssb = SpannableStringBuilder(text)
        val cPrim = ContextCompat.getColor(this, R.color.md_primary)
        val cOk = ContextCompat.getColor(this, R.color.md_status_ok)
        val cWarn = ContextCompat.getColor(this, R.color.md_status_warn)
        val cErr = ContextCompat.getColor(this, R.color.md_status_err)
        applyPattern(ssb, P_SECTION, cPrim, true)
        applyPattern(ssb, P_OK_WORDS, cOk, true)
        applyPattern(ssb, P_OK_TICK, cOk, true)
        applyPattern(ssb, P_WARN_WORDS, cWarn, true)
        applyPattern(ssb, P_WARN_CROSS, cWarn, true)
        applyPattern(ssb, P_TAG_I, cOk, true)
        applyPattern(ssb, P_TAG_W, cWarn, true)
        applyPattern(ssb, P_TAG_E, cErr, true)
        return ssb
    }

    private fun getSystemProp(key: String): String {
        return try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, key, "") as String?
            if (value.isNullOrEmpty()) "(undefined)" else value
        } catch (e: Exception) {
            "(erreur: " + e.message + ")"
        }
    }

    private fun getDisplaySize(d: Display): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getMetrics(metrics)
        return metrics.widthPixels.toString() + "x" + metrics.heightPixels + " @" + metrics.densityDpi + "dpi"
    }

    private fun displayFlagsToString(flags: Int): String {
        val parts = ArrayList<String>()
        if (flags and Display.FLAG_SECURE != 0) parts.add("SECURE")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) parts.add("PROTECTED")
        if (flags and Display.FLAG_PRIVATE != 0) parts.add("PRIVATE")
        if (flags and Display.FLAG_PRESENTATION != 0) parts.add("PRESENTATION")
        return if (parts.isEmpty()) "none" else parts.toString()
    }

    private fun displayStateToString(state: Int): String {
        return when (state) {
            Display.STATE_ON -> "ON"
            Display.STATE_OFF -> "OFF"
            Display.STATE_DOZE -> "DOZE"
            4 -> "DOZE_SUSPEND" // Display.STATE_DOZE_SUSPEND = 4 (API 23+)
            Display.STATE_UNKNOWN -> "UNKNOWN"
            else -> "state=$state"
        }
    }

    private fun paramsToString(types: Array<Class<*>>): String {
        if (types.isEmpty()) return "()"
        val sb = StringBuilder("(")
        for (i in types.indices) {
            if (i > 0) sb.append(", ")
            sb.append(types[i].simpleName)
        }
        sb.append(")")
        return sb.toString()
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Snapshots a few live values from the BYD API for the report (via reflection). */
    private fun snapshotBydLiveData(): String {
        val sb = StringBuilder()
        sb.append(
            probeBydCall(
                "Bodywork", "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
                arrayOf("getAutoVIN", "getAutoModelName", "getPowerLevel", "getBatteryVoltageLevel")
            )
        )
        sb.append(
            probeBydCall(
                "Speed", "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
                arrayOf("getCurrentSpeed", "getAccelPedalDepth", "getBrakePedalDepth")
            )
        )
        sb.append(
            probeBydCall(
                "Energy", "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
                arrayOf("getElecBatteryRemainingCapacity", "getMileage", "getEvRemainingMileage")
            )
        )
        return sb.toString()
    }

    /** Calls a list of zero-arg getters on a BYD device and formats `key = value` lines. */
    private fun probeBydCall(label: String, className: String, methods: Array<String>): String {
        val sb = StringBuilder()
        sb.append("  ── ").append(label).append("\n")
        try {
            val cls = bydClass(className)
            val getInstance = cls.getMethod("getInstance", Context::class.java)
            val dev = getInstance.invoke(null, applicationContext)
            if (dev == null) {
                sb.append("    (getInstance returned null)\n")
                return sb.toString()
            }
            for (m in methods) {
                try {
                    val v = cls.getMethod(m).invoke(dev)
                    sb.append(String.format("    %-32s = %s\n", m, v))
                } catch (nsme: NoSuchMethodException) {
                    sb.append(String.format("    %-32s = (no such method)\n", m))
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    sb.append(
                        String.format(
                            "    %-32s ✗ %s\n", m,
                            cause.javaClass.simpleName + ": " + cause.message
                        )
                    )
                }
            }
        } catch (e: ClassNotFoundException) {
            sb.append("    (class not in runtime SDK)\n")
        } catch (t: Throwable) {
            sb.append("    ✗ ").append(t.javaClass.simpleName)
                .append(": ").append(t.message).append("\n")
        }
        return sb.toString()
    }

    private fun tryInstantiateBydApi(): String {
        val sb = StringBuilder()
        val devices = arrayOf(
            "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        )
        for (className in devices) {
            val shortName = className.substring(className.lastIndexOf('.') + 1)
            try {
                val cls = bydClass(className)
                val getInstance = cls.getMethod("getInstance", Context::class.java)
                val instance = getInstance.invoke(null, applicationContext)
                sb.append(
                    String.format(
                        "  %-35s %s\n", shortName,
                        if (instance != null) "✓ getInstance() OK" else "✗ getInstance() → null"
                    )
                )
            } catch (e: SecurityException) {
                sb.append(String.format("  %-35s ✗ SecurityException (missing permission)\n", shortName))
            } catch (e: ClassNotFoundException) {
                sb.append(String.format("  %-35s ✗ Class not found in runtime SDK\n", shortName))
            } catch (e: NoSuchMethodException) {
                sb.append(String.format("  %-35s ✗ getInstance() not found\n", shortName))
            } catch (e: Exception) {
                sb.append(
                    String.format(
                        "  %-35s ✗ %s\n", shortName,
                        e.javaClass.simpleName + ": " + e.message
                    )
                )
            }
        }
        return sb.toString()
    }

    // =========================================================================
    // v0.9.82 — M3 redesign: nav rail + overview tiles + lists
    // =========================================================================

    private fun wireSysInfoNavRail() {
        val navApps: View? = findViewById(R.id.nav_apps_sys)
        val navSettings: View? = findViewById(R.id.nav_settings_sys)
        val navDiag: View? = findViewById(R.id.nav_diag_sys)
        val navLog: View? = findViewById(R.id.nav_log_sys)
        val navLogo: View? = findViewById(R.id.iv_nav_logo_sys)
        navApps?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        navSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)); finish() }
        navDiag?.setOnClickListener { startActivity(Intent(this, DiagActivity::class.java)); finish() }
        navLog?.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)); finish() }
        navLogo?.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        // v1.2.44 — Hotspot navrail entry (DL3 + use_own_sim runtime-gated)
        NavRailHotspot.apply(this, R.id.nav_hotspot_sys, true)
        // v1.4.9-beta — Layouts
        NavRailLayouts.apply(this, R.id.nav_layouts_sys, true)
    }

    private fun populateOverviewTiles() {
        // Vehicle — BYD Bodywork API on a worker thread, falling back to sysprops/Build.
        val vVal: TextView? = findViewById(R.id.tv_tile_vehicle_value)
        val vSub: TextView? = findViewById(R.id.tv_tile_vehicle_sub)
        vVal?.text = prettyVehicleNameSync() // immediate fallback
        vSub?.text = prettyDilinkVersion()
        Thread({
            val biz = fetchBydVehicleInfo()
            runOnUiThread {
                if (mDestroyed) return@runOnUiThread
                if (biz[0].isNotEmpty()) vVal?.text = biz[0]
                if (biz[1].isNotEmpty()) vSub?.text = biz[1]
            }
        }, "sysinfo-byd").start()

        // Android
        val aVal: TextView? = findViewById(R.id.tv_tile_android_value)
        val aSub: TextView? = findViewById(R.id.tv_tile_android_sub)
        aVal?.text = Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT
        if (aSub != null) {
            val disp = if (!Build.DISPLAY.isNullOrEmpty()) Build.DISPLAY else Build.ID
            aSub.text = "Build " + disp + " · " + Build.SUPPORTED_ABIS[0]
        }

        // DashCast
        val dVal: TextView? = findViewById(R.id.tv_tile_dashcast_value)
        val dSub: TextView? = findViewById(R.id.tv_tile_dashcast_sub)
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            dVal?.text = "v" + pi.versionName
            // minSdk=29 -> getLongVersionCode() always available
            val code = pi.longVersionCode.toInt()
            val signTag = if (isPlatformSigned())
                getString(R.string.sysinfo_tile_dashcast_platform_signed)
            else
                getString(R.string.sysinfo_tile_dashcast_user_signed)
            dSub?.text = "build " + code + " · " + signTag
        } catch (e: PackageManager.NameNotFoundException) {
            dVal?.text = "?"
            dSub?.text = ""
        }

        // Uptime
        val uptimeMs = SystemClock.elapsedRealtime()
        val sinceMs = System.currentTimeMillis() - uptimeMs
        val uVal: TextView? = findViewById(R.id.tv_tile_uptime_value)
        val uSub: TextView? = findViewById(R.id.tv_tile_uptime_sub)
        uVal?.text = formatUptime(uptimeMs)
        uSub?.text = getString(R.string.sysinfo_tile_uptime_since, SDF_TIME_HM.get()!!.format(Date(sinceMs)))
    }

    /** Sync best-guess of vehicle name without touching the BYD service. */
    private fun prettyVehicleNameSync(): String {
        val t = getSystemProp("ro.byd.car.type")
        if (!t.startsWith("(") && t.isNotEmpty()) return "BYD $t"
        val m = getSystemProp("ro.byd.model")
        if (!m.startsWith("(") && m.isNotEmpty()) return "BYD $m"
        return Build.MANUFACTURER + " " + Build.MODEL
    }

    /** Returns a human DiLink version string — sysprop with several fallbacks. */
    private fun prettyDilinkVersion(): String {
        val keys = arrayOf("ro.byd.dilink.version", "ro.byd.version", "ro.build.display.id")
        for (k in keys) {
            val v = getSystemProp(k)
            if (!v.startsWith("(") && v.isNotEmpty()) {
                if (k.startsWith("ro.byd.dilink")) return "DiLink $v"
                if (k == "ro.byd.version") return "DiLink $v"
                return v // ro.build.display.id
            }
        }
        return Build.BRAND + " · " + Build.PRODUCT
    }

    /**
     * Calls BYDAutoBodyworkDevice.getAutoVIN() + getAutoModelName() via reflection.
     * Returns [headline, subtitle] (either may be empty if the call fails).
     */
    private fun fetchBydVehicleInfo(): Array<String> {
        var headline = ""
        var subtitle = ""
        try {
            val cls = bydClass("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
            val getInstance = cls.getMethod("getInstance", Context::class.java)
            val dev = getInstance.invoke(null, applicationContext)
            if (dev != null) {
                var vin = ""
                try {
                    val v = cls.getMethod("getAutoVIN").invoke(dev)
                    if (v is String) vin = v
                } catch (ignored: Throwable) {
                }
                var modelCode = -1
                try {
                    val m = cls.getMethod("getAutoModelName").invoke(dev)
                    if (m is Int) modelCode = m
                } catch (ignored: Throwable) {
                }
                headline = prettyVehicleNameSync()
                val sub = StringBuilder()
                sub.append(prettyDilinkVersion())
                if (modelCode > 0) sub.append(" · model#").append(modelCode)
                if (vin.length >= 4) sub.append(" · VIN …").append(vin.substring(vin.length - 4))
                subtitle = sub.toString()
            }
        } catch (t: Throwable) {
            // BYD service not reachable — keep fallbacks.
        }
        return arrayOf(headline, subtitle)
    }

    /** True iff the package is signed by the same certificate as the system "android" package. */
    private fun isPlatformSigned(): Boolean {
        return try {
            val sigMatch = packageManager.checkSignatures("android", packageName)
            sigMatch == PackageManager.SIGNATURE_MATCH
        } catch (t: Throwable) {
            false
        }
    }

    private fun populateDisplaysList() {
        val container: LinearLayout = findViewById(R.id.ll_displays_list) ?: return
        container.removeAllViews()

        // --- Phase 1: synchronous API pass (instant, no I/O) ---
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        val apiIds = HashSet<Int>()
        val inf = LayoutInflater.from(this)
        for (d in displays) {
            apiIds.add(d.displayId)
            val row = inf.inflate(R.layout.row_sysinfo, container, false)
            val icon = row.findViewById<ImageView>(R.id.row_icon)
            val headline = row.findViewById<TextView>(R.id.row_headline)
            val support = row.findViewById<TextView>(R.id.row_support)
            val badge = row.findViewById<TextView>(R.id.row_badge)
            val id = d.displayId
            val name = d.name ?: "?"
            val label: String
            val iconRes: Int
            if (id == 0) {
                label = getString(R.string.sysinfo_disp_main); iconRes = R.drawable.ic_tv
            } else if (name.lowercase(Locale.ROOT).contains("virtual") ||
                name.lowercase(Locale.ROOT).contains("mirror")
            ) {
                label = getString(R.string.sysinfo_disp_virtual); iconRes = R.drawable.ic_screen_share
            } else {
                label = getString(R.string.sysinfo_disp_cluster); iconRes = R.drawable.ic_dashboard
            }
            headline.text = getString(R.string.sysinfo_disp_row_headline, id, label)
            support.text = getDisplaySize(d) + " · " + name
            icon.setImageResource(iconRes)
            badge.text = "●"
            container.addView(row)
        }

        // --- Phase 2: uid=2000 async probe — finds PRIVATE/HIDDEN displays ---
        ShellGateway.execShellWithResult(
            this,
            "dumpsys display | grep -E '^\\s+mDisplayId=[0-9]|^\\s+mLayerStack=[0-9]' | head -80",
            object : AdbLocalClient.Callback {
                override fun onSuccess(raw: String?) {
                    // Parse id → layerStack. Lines alternate mDisplayId / mLayerStack.
                    val idToStack = LinkedHashMap<Int, Int>()
                    var lastId = -1
                    for (line in (raw ?: "").split("\n")) {
                        val t = line.trim()
                        if (t.startsWith("mDisplayId=")) {
                            lastId = try {
                                t.substring("mDisplayId=".length).trim().toInt()
                            } catch (e: NumberFormatException) {
                                -1
                            }
                        } else if (t.startsWith("mLayerStack=") && lastId >= 0) {
                            try {
                                val stack = t.substring("mLayerStack=".length).trim().toInt()
                                idToStack[lastId] = stack
                            } catch (e: NumberFormatException) {
                                idToStack[lastId] = -1
                            }
                            lastId = -1
                        }
                    }
                    // Hidden = IDs in dumpsys but absent from DisplayManager API.
                    val hidden = LinkedHashMap<Int, Int>()
                    for ((key, value) in idToStack) {
                        if (!apiIds.contains(key)) hidden[key] = value
                    }
                    if (hidden.isEmpty()) return
                    runOnUiThread {
                        if (mDestroyed) return@runOnUiThread
                        val inf2 = LayoutInflater.from(this@SysInfoActivity)
                        for ((key, value) in hidden) {
                            val row = inf2.inflate(R.layout.row_sysinfo, container, false)
                            val icon = row.findViewById<ImageView>(R.id.row_icon)
                            val headline = row.findViewById<TextView>(R.id.row_headline)
                            val support = row.findViewById<TextView>(R.id.row_support)
                            val badge = row.findViewById<TextView>(R.id.row_badge)
                            icon.setImageResource(R.drawable.ic_visibility)
                            headline.text = getString(
                                R.string.sysinfo_disp_row_headline,
                                key, getString(R.string.sysinfo_disp_hidden)
                            )
                            val stackStr = if (value >= 0) "layerStack=$value" else "layerStack=?"
                            support.text = stackStr + " · " + getString(R.string.sysinfo_disp_hidden_sub)
                            badge.text = "●"
                            badge.setTextColor(0xFFFF9800.toInt()) // orange — not visible via API
                            container.addView(row)
                        }
                    }
                }

                override fun onError(err: String?) {
                    AppLogger.w("SysInfoActivity", "uid=2000 display probe failed: $err")
                }
            }
        )
    }

    /** v1.2.78 — manual recovery: replays sendInfo(30)→3s→16→3s→0 regardless of state. */
    private fun replayProjectionSlowPath() {
        Toast.makeText(this, R.string.sysinfo_restart_started, Toast.LENGTH_SHORT).show()
        AppLogger.log("SysInfoActivity", "Projection slow-path replay: 30→3s→16→3s→0")
        val h = Handler(Looper.getMainLooper())
        AdbLocalClient.sendInfo(this, 1000, 30, "", object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                AppLogger.log("SysInfoActivity", "replay cmd=30 OK: $out")
                h.postDelayed({
                    AdbLocalClient.sendInfo(this@SysInfoActivity, 1000, 16, "", object : AdbLocalClient.Callback {
                        override fun onSuccess(out2: String?) {
                            AppLogger.log("SysInfoActivity", "replay cmd=16 OK: $out2")
                            h.postDelayed({
                                AdbLocalClient.sendInfo(this@SysInfoActivity, 1000, 0, "", object : AdbLocalClient.Callback {
                                    override fun onSuccess(out3: String?) {
                                        AppLogger.log("SysInfoActivity", "replay cmd=0 OK: $out3")
                                        // Sync the projection-mode flag and re-init ClusterService so its
                                        // DashboardLauncher re-discovers the cluster display.
                                        ClusterManager.notifyProjectionActive()
                                        try {
                                            val inst = ClusterService.getInstance()
                                            if (inst != null) {
                                                inst.restartProjection()
                                            } else {
                                                val svc = Intent(this@SysInfoActivity, ClusterService::class.java)
                                                startForegroundService(svc)
                                            }
                                        } catch (t: Throwable) {
                                            AppLogger.w("SysInfoActivity", "replay: ClusterService re-init failed: " + t.message)
                                        }
                                        runOnUiThread { if (!mDestroyed) populateServicesList() }
                                    }

                                    override fun onError(err: String?) {
                                        AppLogger.w("SysInfoActivity", "replay cmd=0 ERR: $err")
                                        runOnUiThread {
                                            if (!mDestroyed) Toast.makeText(
                                                this@SysInfoActivity,
                                                R.string.sysinfo_restart_failed, Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                })
                            }, 3000L)
                        }

                        override fun onError(err: String?) {
                            AppLogger.w("SysInfoActivity", "replay cmd=16 ERR: $err")
                            runOnUiThread {
                                if (!mDestroyed) Toast.makeText(
                                    this@SysInfoActivity,
                                    R.string.sysinfo_restart_failed, Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    })
                }, 3000L)
            }

            override fun onError(err: String?) {
                AppLogger.w("SysInfoActivity", "replay cmd=30 ERR: $err")
                runOnUiThread {
                    if (!mDestroyed) Toast.makeText(
                        this@SysInfoActivity,
                        R.string.sysinfo_restart_failed, Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
    }

    private fun populateServicesList() {
        val container: LinearLayout = findViewById(R.id.ll_services_list) ?: return
        container.removeAllViews()
        val inf = LayoutInflater.from(this)

        // ClusterService — runs in our app process.
        val clusterRunning = isServiceRunning(ClusterService::class.java)
        val clusterSub: String = if (clusterRunning) {
            val pid = Process.myPid()
            "pid " + pid + " · " + formatUptime(SystemClock.elapsedRealtime())
        } else {
            getString(R.string.sysinfo_svc_stopped)
        }
        addServiceRow(inf, container, "ClusterService", clusterSub, clusterRunning, false,
            if (clusterRunning) null else Runnable {
                // v1.2.76 — tap on the leading icon when stopped: restart the service.
                try {
                    val i = Intent(this, ClusterService::class.java)
                    startForegroundService(i)
                    Toast.makeText(this, R.string.sysinfo_restart_started, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(this, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                    AppLogger.w("SysInfoActivity", "ClusterService restart failed: " + t.message)
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!mDestroyed) populateServicesList()
                }, 1500L)
            })

        // SurfaceDaemon — separate process started via ADB (uid=2000); pid via pgrep.
        // The row label stays "MirrorDaemon": it is the runtime identity a triager sees in logcat
        // and in mirrordaemon_latest.log, which the class rename deliberately did not touch.
        val mirrorRow = addServiceRow(
            inf, container, "MirrorDaemon",
            if (clusterRunning) getString(R.string.sysinfo_svc_mirror_sub)
            else getString(R.string.sysinfo_svc_stopped),
            clusterRunning, false,
            if (clusterRunning) null else Runnable {
                // v1.2.76 — MirrorDaemon restart via ProxyClient.connect() (off the UI thread).
                Toast.makeText(this, R.string.sysinfo_restart_started, Toast.LENGTH_SHORT).show()
                Thread({
                    val ok: Boolean
                    try {
                        ok = ProxyClient.connect(applicationContext)
                    } catch (t: Throwable) {
                        AppLogger.w("SysInfoActivity", "MirrorDaemon restart failed: " + t.message)
                        runOnUiThread {
                            if (!mDestroyed) Toast.makeText(this, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    runOnUiThread {
                        if (mDestroyed) return@runOnUiThread
                        if (!ok) Toast.makeText(this, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                        populateServicesList()
                    }
                }, "sysinfo-mirror-restart").start()
            })
        if (clusterRunning) {
            // Run pgrep off the main thread to avoid StrictMode disk/exec on UI.
            //
            // Match the RUNTIME process name, never the entry-point class name. `pgrep -f` tests
            // /proc/<pid>/cmdline, and the daemon overwrites its whole arg block with
            // setArgV0("com.byd.dashcast.mirrordaemon") (see SurfaceDaemon.main) — the FQCN
            // app_process was invoked with is gone from the cmdline by the time anyone can look.
            // So "MirrorDaemon" (used until 1.8.x) and "SurfaceDaemon" alike could never match and
            // this row has always fallen back to the generic subtitle; the class rename did not
            // break it, it was already dead. Same shape as the BetaProxy row below, which greps
            // its own runtime name "dashcast_proxy", and as AdbLocalClient.DAEMON_GREP.
            // Still shows the generic subtitle when the lookup yields nothing (e.g. if /proc does
            // not expose a uid-2000 process to our uid) — the row's on/off state comes from
            // clusterRunning, not from this pid.
            // NOTE: pidOf() interpolates the pattern into `sh -c`, so it must stay free of shell
            // metacharacters — hence one literal token, not the DAEMON_GREP alternation.
            Thread({
                val pid = pidOf("com.byd.dashcast.mirrordaemon")
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    val sub = if (pid > 0) "pid $pid · poll 500 ms" else getString(R.string.sysinfo_svc_mirror_sub)
                    setServiceRowState(mirrorRow, true, false, sub)
                }
            }, "sysinfo-pidof").start()
        }

        // BetaProxy daemon — uid=2000 process. Status via ProxyClient.isConnected().
        val proxyConnected = ProxyClient.isConnected()
        val proxyRow = addServiceRow(
            inf, container, "Proxy ADB Daemon",
            if (proxyConnected) getString(R.string.sysinfo_svc_checking)
            else getString(R.string.sysinfo_svc_stopped),
            proxyConnected, false,
            if (proxyConnected) null else Runnable {
                Toast.makeText(this, R.string.sysinfo_restart_started, Toast.LENGTH_SHORT).show()
                Thread({
                    val ok: Boolean
                    try {
                        ok = ProxyClient.connect(applicationContext)
                    } catch (t: Throwable) {
                        AppLogger.w("SysInfoActivity", "BetaProxy restart failed: " + t.message)
                        runOnUiThread {
                            if (!mDestroyed) Toast.makeText(this, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    runOnUiThread {
                        if (mDestroyed) return@runOnUiThread
                        if (!ok) Toast.makeText(this, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                        populateServicesList()
                    }
                }, "sysinfo-proxy-restart").start()
            })
        if (proxyConnected) {
            Thread({
                val pid = pidOf("dashcast_proxy")
                val spawns = ProxyMetrics.get(this, ProxyMetrics.K_COLD_SPAWNS)
                val zombies = ProxyMetrics.get(this, ProxyMetrics.K_BINDER_ZOMBIES)
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    val sub = (if (pid > 0) "pid $pid" else "pid ?") +
                        " · spawns " + spawns +
                        (if (zombies > 0) " · ⚠ $zombies zombie" else "")
                    setServiceRowState(proxyRow, true, false, sub)
                }
            }, "sysinfo-proxy-pid").start()
        }

        // Projection state (v1.2.78). Tap always replays the slow path.
        val projOn = ClusterManager.isQtInProjectionMode()
        addServiceRow(
            inf, container, getString(R.string.sysinfo_svc_projection),
            getString(R.string.sysinfo_svc_projection_sub),
            projOn, false,
            Runnable { replayProjectionSlowPath() },
            true /* alwaysClickable */
        )

        // ADB local — real probe via Dadb (executeShellWithResult).
        val adbRow = addServiceRow(
            inf, container, "AdbLocalClient",
            getString(R.string.sysinfo_svc_adb_unreachable),
            false, true /* useConnBadge */, null /* no manual restart */
        )
        AdbLocalClient.executeShellWithResult(this, "echo ok", object : AdbLocalClient.Callback {
            override fun onSuccess(report: String?) {
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    setServiceRowState(adbRow, true, true, "127.0.0.1:5555")
                }
            }

            override fun onError(err: String?) {
                runOnUiThread {
                    if (mDestroyed) return@runOnUiThread
                    setServiceRowState(adbRow, false, true, getString(R.string.sysinfo_svc_adb_unreachable))
                }
            }
        })

        // v1.2.35 — DL5-only: surface `force_resizable_activities` and allow toggling it.
        if (AdbLocalClient.isDiLink5Safe(this)) {
            val frRow = addServiceRow(
                inf, container,
                getString(R.string.sysinfo_svc_force_resizable),
                getString(R.string.sysinfo_svc_force_resizable_checking),
                false /* probed asynchronously */, false,
                Runnable { toggleForceResizable() },
                true /* alwaysClickable */
            )
            probeForceResizable(frRow)
        }
    }

    /** v1.2.35 — DL5 only. Reads `settings global force_resizable_activities`. */
    private fun probeForceResizable(row: View?) {
        ShellGateway.execShellWithResult(
            this, "settings get global force_resizable_activities",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    val value = out?.trim() ?: ""
                    val on = value == "1"
                    runOnUiThread {
                        if (mDestroyed || row == null) return@runOnUiThread
                        val shown = if (value.isEmpty() || value == "null") "0" else value
                        val sub = getString(R.string.sysinfo_svc_force_resizable_sub, shown)
                        setServiceRowState(row, on, false, sub)
                    }
                }

                override fun onError(err: String?) {
                    runOnUiThread {
                        if (mDestroyed || row == null) return@runOnUiThread
                        setServiceRowState(row, false, false, getString(R.string.sysinfo_svc_adb_unreachable))
                    }
                }
            }
        )
    }

    /** v1.2.35 — DL5 only. Toggles the value then refreshes the services list. */
    private fun toggleForceResizable() {
        if (!AdbLocalClient.isDiLink5Safe(this)) return
        Toast.makeText(this, R.string.sysinfo_restart_started, Toast.LENGTH_SHORT).show()
        ShellGateway.execShellWithResult(
            this,
            "v=\$(settings get global force_resizable_activities); " +
                "if [ \"\$v\" = \"1\" ]; then " +
                "  settings put global force_resizable_activities 0 2>&1; echo SET=0; " +
                "else " +
                "  settings put global force_resizable_activities 1 2>&1; echo SET=1; " +
                "fi",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i("SysInfoActivity", "force_resizable_activities toggle → " + out?.trim())
                    runOnUiThread { if (!mDestroyed) populateServicesList() }
                }

                override fun onError(err: String?) {
                    AppLogger.e("SysInfoActivity", "force_resizable_activities toggle ERROR: $err")
                    runOnUiThread {
                        if (!mDestroyed) Toast.makeText(this@SysInfoActivity, R.string.sysinfo_restart_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    /**
     * Adds one service row; returns the row view so the caller can later toggle state.
     * When [onRestart] is non-null AND ([alwaysClickable] OR the service is not running),
     * the leading icon becomes clickable to trigger [onRestart].
     */
    private fun addServiceRow(
        inf: LayoutInflater,
        container: LinearLayout,
        name: String,
        sub: String,
        running: Boolean,
        useConnBadge: Boolean,
        onRestart: Runnable?,
        alwaysClickable: Boolean = false
    ): View {
        val row = inf.inflate(R.layout.row_sysinfo, container, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(R.drawable.ic_play_circle)
        row.findViewById<TextView>(R.id.row_headline).text = name
        // Tag the row so setServiceRowState() knows which badge variant to use.
        row.setTag(R.id.row_badge, useConnBadge)
        setServiceRowState(row, running, useConnBadge, sub)
        // v1.2.76 — restart affordance: tap the play-circle leading icon when stopped.
        val leading: View? = row.findViewById(R.id.row_leading)
        if (leading != null) {
            if ((alwaysClickable || !running) && onRestart != null) {
                leading.isClickable = true
                leading.isFocusable = true
                leading.contentDescription = getString(R.string.sysinfo_restart_action)
                leading.setOnClickListener { onRestart.run() }
            } else {
                leading.isClickable = false
                leading.isFocusable = false
                leading.setOnClickListener(null)
                leading.contentDescription = null
            }
        }
        container.addView(row)
        return row
    }

    /** Applies the running/off visual state on a row already inflated. */
    private fun setServiceRowState(row: View, running: Boolean, useConnBadge: Boolean, sub: String) {
        val icon = row.findViewById<ImageView>(R.id.row_icon)
        val leading: View? = row.findViewById(R.id.row_leading)
        if (running) {
            leading?.setBackgroundResource(R.drawable.bg_service_icon_running)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.md_status_ok))
        } else {
            leading?.setBackgroundResource(R.drawable.bg_diag_card_icon)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.md_outline_variant))
        }
        row.findViewById<TextView>(R.id.row_support).text = sub
        val badge = row.findViewById<TextView>(R.id.row_badge)
        if (running) {
            badge.text = getString(if (useConnBadge) R.string.sysinfo_svc_conn else R.string.sysinfo_svc_run)
            badge.setTextColor(ContextCompat.getColor(this, R.color.md_status_ok))
        } else {
            badge.text = getString(R.string.sysinfo_svc_off)
            badge.setTextColor(ContextCompat.getColor(this, R.color.md_status_err))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(svcClass: Class<*>): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        try {
            for (si in am.getRunningServices(Int.MAX_VALUE)) {
                if (svcClass.name == si.service.className) return true
            }
        } catch (t: Throwable) {
            // getRunningServices() returns only own-process services since API 26.
        }
        return false
    }

    /**
     * v1.2.76 — Clean tablet reboot triggered from the System panel header.
     * Shows a confirmation dialog, then dispatches `reboot` via AdbLocalClient.
     */
    private fun confirmAndReboot() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.sysinfo_reboot_title)
                .setMessage(R.string.sysinfo_reboot_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sysinfo_reboot_confirm) { _, _ -> doReboot() }
                .show()
        } catch (t: Throwable) {
            // Fall back to immediate reboot if the dialog can't be shown.
            doReboot()
        }
    }

    private fun doReboot() {
        Toast.makeText(this, R.string.sysinfo_reboot_toast, Toast.LENGTH_SHORT).show()
        AppLogger.i("SysInfoActivity", "User-initiated reboot via SysInfo panel")
        try {
            ShellGateway.execShell(applicationContext, "reboot")
        } catch (t: Throwable) {
            AppLogger.e("SysInfoActivity", "reboot dispatch failed: " + t.message)
            Toast.makeText(this, R.string.sysinfo_reboot_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // Thread-local SimpleDateFormat instances (SDF is not thread-safe). withInitial()
        // always supplies a value, so get() never returns null here (it is nonetheless
        // typed nullable in Kotlin → !! at the call sites is safe by construction).
        private val SDF_REPORT_HEADER: ThreadLocal<SimpleDateFormat> =
            ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        private val SDF_FILE_STAMP: ThreadLocal<SimpleDateFormat> =
            ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()) }
        private val SDF_TIME_HMS: ThreadLocal<SimpleDateFormat> =
            ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        private val SDF_TIME_HM: ThreadLocal<SimpleDateFormat> =
            ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

        // H2: BYD reflection caches — Class.forName() is expensive on first call.
        private val sBydClassCache = ConcurrentHashMap<String, Class<*>>()
        private val sBydClassNotFound: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())
        // M4: PID lookup cache — pgrep forks a shell; cap refresh at 5s.
        private const val PID_TTL_MS = 5_000L
        private val sPidCache = ConcurrentHashMap<String, LongArray>()

        // 1.2.30 — patterns pre-compiled once.
        private val P_SECTION = Pattern.compile("═══ [^═]+ ═══")
        private val P_OK_WORDS = Pattern.compile("\\b(GRANTED|OPEN|RUN|CONN|YES)\\b")
        private val P_OK_TICK = Pattern.compile("✓")
        private val P_WARN_WORDS = Pattern.compile("\\b(DENIED|closed|OFF|NO)\\b")
        private val P_WARN_CROSS = Pattern.compile("✗")
        private val P_TAG_I = Pattern.compile("\\[I\\]")
        private val P_TAG_W = Pattern.compile("\\[W\\]")
        private val P_TAG_E = Pattern.compile("\\[E\\]")

        private fun applyPattern(ssb: SpannableStringBuilder, p: Pattern, color: Int, bold: Boolean) {
            val m = p.matcher(ssb)
            while (m.find()) {
                ssb.setSpan(ForegroundColorSpan(color), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold) {
                    ssb.setSpan(StyleSpan(Typeface.BOLD), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        /** Appends a "RECENT EVENTS (last N)" block from AppLogger. */
        private fun appendRecentEvents(sb: StringBuilder, n: Int) {
            val entries = AppLogger.getEntries()
            sb.append("\n═══ RECENT EVENTS (last ").append(n).append(") ═══\n")
            if (entries.isEmpty()) {
                sb.append("  (no events yet)\n")
                return
            }
            val tf = SDF_TIME_HMS.get()!!
            val from = Math.max(0, entries.size - n)
            for (i in from until entries.size) {
                val e = entries[i]
                val lvl = when (e.level) {
                    AppLogger.Level.ERROR -> "[E]"
                    AppLogger.Level.WARN -> "[W]"
                    else -> "[I]"
                }
                sb.append(tf.format(Date(e.timestamp))).append(' ')
                    .append(lvl).append(' ').append(e.tag).append(" ")
                    .append(e.message).append('\n')
            }
        }

        /** Cached Class.forName() — avoids repeated ClassLoader lookups for BYD SDK classes. */
        @Throws(ClassNotFoundException::class)
        private fun bydClass(name: String): Class<*> {
            if (sBydClassNotFound.contains(name)) throw ClassNotFoundException(name)
            sBydClassCache[name]?.let { return it }
            return try {
                val c = Class.forName(name)
                sBydClassCache[name] = c
                c
            } catch (e: ClassNotFoundException) {
                sBydClassNotFound.add(name)
                throw e
            }
        }

        private fun formatUptime(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) h.toString() + "h " + m + "min" else m.toString() + "min"
        }

        /** Returns the first PID matching the given pattern via `pgrep -f`, or -1. */
        private fun pidOf(pattern: String): Int {
            val now = SystemClock.elapsedRealtime()
            val cached = sPidCache[pattern]
            if (cached != null && (now - cached[1]) < PID_TTL_MS) return cached[0].toInt()
            // Qualified: android.os.Process is imported (for myPid()); this is java.lang.Process.
            var p: java.lang.Process? = null
            try {
                p = ProcessBuilder("sh", "-c", "pgrep -f $pattern | head -n 1")
                    .redirectErrorStream(true).start()
                val br = BufferedReader(InputStreamReader(p.inputStream))
                val line = br.readLine()
                br.close()
                // 1.2.30 — bounded waitFor: cap at 2s + destroyForcibly.
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    sPidCache[pattern] = longArrayOf(-1, now)
                    return -1
                }
                if (line != null && line.trim().isNotEmpty()) {
                    val pid = line.trim().toInt()
                    sPidCache[pattern] = longArrayOf(pid.toLong(), now)
                    return pid
                }
            } catch (t: Throwable) {
                // pgrep may not be available on all ROMs; ignore.
            } finally {
                if (p != null) try {
                    p.destroy()
                } catch (ignored: Throwable) {
                }
            }
            sPidCache[pattern] = longArrayOf(-1, now)
            return -1
        }
    }
}
