package com.byd.dashcast.hud

import android.content.Context
import android.os.Build
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.ProxyClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One-shot DX_BYD_AUTO (Android Automotive) cluster investigation. Captures everything
 * needed to reverse-engineer how the AAOS cluster is fed — and pulls the candidate
 * system APKs (cluster renderer, Neusoft nav, automotive/display-proxy/ThunderSoft
 * services) for off-car RE — then zips it for upload.
 *
 * Goal: find an **unprivileged data-feeding interface** to the cluster (like
 * AutoContainer/AmapService on DL3). Projecting an app WINDOW is blocked by the
 * cluster's FLAG_OWN_CONTENT_ONLY + non-user-build / signature gates; feeding nav
 * DATA might not be — RE of these APKs will tell.
 *
 * Every step is guarded and reports progress so the on-screen log shows it advancing.
 * Big dumpsys outputs are capped with `head -c` (runShell supports pipes) so the
 * daemon binder parcel never overflows.
 */
object AaosDiagnosticBundle {

    private const val APK_BUDGET_TOTAL = 35L * 1024 * 1024
    private const val APK_BUDGET_FILE  = 22L * 1024 * 1024
    private const val APK_MAX_COUNT    = 12

    private val HIGH = listOf("cluster", "instrument", "neusoft", "navigation", "automotive",
        "displayproxy", "someip", "ts.car", "ts.appservice", "carlauncher", "projection")
    private val MED  = listOf("nav", "map", "amap", "autonavi", "gaode", "byd", "car", "bosch", "ts.")

    private const val TOTAL_STEPS = 14

    fun collect(ctx: Context, progress: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val work = File(ctx.cacheDir, "aaos_diag_$stamp").apply { mkdirs() }
        var step = 0
        fun s(label: String, block: () -> Unit) {
            step++
            progress("[$step/$TOTAL_STEPS] $label…")
            try { block(); progress("    ✓ $label") }
            catch (t: Throwable) { progress("    ✗ $label: ${t.javaClass.simpleName}: ${t.message}") }
        }

        s("connect daemon") { try { ProxyClient.connect(ctx) } catch (_: Throwable) {} }

        s("system properties (build type / automotive)") {
            write(work, "01_props.txt", sh("getprop 2>/dev/null"))
        }
        s("displays (FLAG_OWN_CONTENT_ONLY / cluster)") {
            write(work, "02_displays.txt", sh("dumpsys display 2>/dev/null | head -c 250000"))
        }
        s("car_service (cluster / projection / UX / nav)") {
            write(work, "03_car_service.txt", sh(
                "dumpsys car_service 2>/dev/null | grep -iE " +
                "'cluster|projection|navigation|instrument|focus|display|launch|restriction|distraction|driving|allow|whitelist|package|occupant|zone' " +
                "| head -c 350000"))
            write(work, "03b_car_service_cli.txt", sh("cmd car_service 2>&1 | head -c 20000"))
        }
        s("activities per display") {
            write(work, "04_activities.txt", sh(
                "dumpsys activity activities 2>/dev/null | grep -E " +
                "'Display #|Stack #|Task id|taskId|displayId|realActivity|mResumed|TaskDisplayArea' | head -c 150000"))
        }
        s("window displays + focus") {
            write(work, "05_window.txt",
                sh("dumpsys window displays 2>/dev/null | head -c 200000") + "\n----- focus -----\n" +
                sh("dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp|mDisplayId=|Display: ' | head -c 20000"))
        }
        s("binder services + running cluster/nav services") {
            write(work, "06_services.txt",
                "----- service list -----\n" + sh("service list 2>/dev/null | head -c 80000") +
                "\n----- activity services (cluster/nav) -----\n" +
                sh("dumpsys activity services 2>/dev/null | grep -iE 'cluster|instrument|nav|neusoft|projection|someip|ts.car|ts.appservice' | head -c 80000"))
        }
        s("automotive HALs (lshal)") {
            write(work, "07_lshal.txt",
                sh("lshal 2>/dev/null | grep -iE 'cluster|instrument|display|nav|automotive|vehicle|bosch' | head -c 40000"))
        }
        s("packages (-f) + flag candidates") {
            val pm = sh("pm list packages -f 2>/dev/null")
            write(work, "08_packages.txt", pm)
            val cands = parseCandidates(pm)
            write(work, "09_candidates.txt", cands.joinToString("\n") { "${it.tier}\t${it.pkg}\t${it.apkPath}" })
        }
        s("dumpsys of candidate cluster/nav packages (receivers/services)") {
            val dumps = StringBuilder()
            parseCandidates(sh("pm list packages -f 2>/dev/null")).filter { it.tier == "HIGH" }.take(8).forEach { c ->
                dumps.append("\n===== dumpsys package ${c.pkg} =====\n")
                    .append(sh("dumpsys package ${c.pkg} 2>/dev/null | head -c 60000"))
            }
            write(work, "10_candidate_dumpsys.txt", dumps.toString())
        }
        s("logcat (cluster/car tags)") {
            write(work, "11_logcat.txt", sh("logcat -d -v threadtime -t 600 2>/dev/null | head -c 250000"))
            write(work, "11b_logcat_cluster.txt", sh(
                "logcat -d -t 800 -v threadtime CAR.CLUSTER:V CAR.UXR:V ClusterRenderingService:V " +
                "InstrumentClusterRenderingService:V CarService:I '*:S' 2>/dev/null | head -c 120000"))
        }
        s("DashCast car-permission grants") {
            write(work, "12_dashcast_grants.txt", sh(
                "dumpsys package com.byd.dashcast 2>/dev/null | grep -iE 'versionName|granted=true|flags=|privateFlags' | head -c 30000"))
        }
        s("AAOS experiments (display-proxy / VHAL / fixed-activity gates)") {
            write(work, "13_experiments.txt", runExperiments(ctx))
        }
        s("automotive display HAL probe (definitive app-projection test)") {
            val sb = StringBuilder()
            sb.append("=== IAutomotiveDisplayProxyService REACHABILITY ===\n")
            sb.append("Goal: can we obtain getHGraphicBufferProducer(clusterDisplay)? ")
                .append("non-null ⇒ app projection feasible; absent/null/denied ⇒ closed.\n\n")
            sb.append("--- IN-APP (app uid) ---\n")
            sb.append(runCatching { com.byd.dashcast.proxy.daemon.AaosDisplayHalProbe.probe() }
                .getOrElse { "probe threw ${it.javaClass.name}: ${it.message}" }).append('\n')
            sb.append("--- VIA DAEMON (uid 2000) ---\n")
            sb.append(runCatching { ProxyClient.aaosHalProbe() }
                .getOrElse { "daemon probe ERR ${it.message ?: it.javaClass.simpleName}" }).append("\n\n")
            sb.append("--- lshal: automotive display HAL (registered? clients?) ---\n")
            sb.append(sh("lshal 2>/dev/null | grep -iE 'automotive.display|IAutomotiveDisplayProxy' | head -c 8000")).append('\n')
            sb.append("--- SELinux avc denials (hwservice/display) just now ---\n")
            sb.append(sh("logcat -d -t 400 2>/dev/null | grep -iE 'avc: .*denied' | grep -iE 'automotive|display|hwservice|graphic|surfaceflinger|gpu' | head -c 12000"))
            write(work, "14_display_hal_probe.txt", sb.toString())
        }
        s("pull candidate system APKs") {
            pullApks(work, progress)
        }

        return work
    }

    // ── AAOS in-app + shell experiments (document the gates / look for openings) ──
    private fun runExperiments(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("=== AAOS EXPERIMENTS ===\n")
        sb.append("isAaos=${runCatching { AaosClusterProbe.isAaos(ctx) }.getOrNull()}\n\n")
        sb.append("----- IAutomotiveDisplayProxyService probe -----\n")
        sb.append(runCatching { AaosClusterProbe.probeDisplayProxy() }.getOrElse { "ERR: ${it.message}" }).append("\n\n")
        sb.append("----- CarPropertyManager vendor VHAL writes (CANDIDATES) -----\n")
        for ((name, prop) in AaosClusterProbe.CANDIDATES) {
            val r = runCatching { AaosClusterProbe.setCarIntProperty(ctx, prop, 0, 1) }.getOrElse { "ERR: ${it.message}" }
            sb.append("%s (0x%08X): %s\n".format(name, prop, r))
        }
        sb.append("\n----- car_service privileged launch gates -----\n")
        sb.append("get-do-activities (neusoft nav): ")
            .append(sh("cmd car_service get-do-activities com.neusoft.na.navigation 2>&1 | head -c 4000")).append("\n")
        sb.append("start-fixed-activity (display 1): ")
            .append(sh("cmd car_service start-fixed-activity 1 com.neusoft.na.navigation com.neusoft.na.navigation.MainActivity 2>&1 | head -c 4000")).append("\n")
        return sb.toString()
    }

    // ── APK pull (world-readable system base.apk, size-budgeted) ──────────────
    private fun pullApks(work: File, progress: (String) -> Unit) {
        val apkDir = File(work, "apks").apply { mkdirs() }
        val manifest = StringBuilder("APK pull manifest (budget ${APK_BUDGET_TOTAL / 1024 / 1024} MB)\n")
        val candidates = parseCandidates(sh("pm list packages -f 2>/dev/null")).sortedBy { if (it.tier == "HIGH") 0 else 1 }
        var total = 0L; var count = 0
        for (c in candidates) {
            if (count >= APK_MAX_COUNT) { manifest.append("SKIP ${c.pkg}: max count\n"); continue }
            val src = File(c.apkPath)
            if (!src.canRead()) { manifest.append("SKIP ${c.pkg}: not readable\n"); continue }
            val sz = src.length()
            if (sz > APK_BUDGET_FILE) { manifest.append("SKIP ${c.pkg}: too big (${sz / 1024 / 1024} MB)\n"); continue }
            if (total + sz > APK_BUDGET_TOTAL) { manifest.append("SKIP ${c.pkg}: over budget\n"); continue }
            try {
                src.copyTo(File(apkDir, c.pkg + ".apk"), overwrite = true)
                total += sz; count++
                progress("      + ${c.pkg} (${sz / 1024} KB)")
                manifest.append("OK   ${c.pkg} (${sz / 1024} KB) ${c.apkPath}\n")
            } catch (t: Throwable) { manifest.append("FAIL ${c.pkg}: ${t.message}\n") }
        }
        manifest.append("\nTotal: $count APKs, ${total / 1024 / 1024} MB\n")
        write(work, "14_apk_manifest.txt", manifest.toString())
    }

    // ── zip ───────────────────────────────────────────────────────────────────
    fun zipDir(work: File): File {
        val zip = File(work.parentFile, work.name + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            work.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(work).path))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private data class Candidate(val tier: String, val pkg: String, val apkPath: String)

    private fun parseCandidates(pm: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (raw in pm.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val apk = body.substring(0, eq); val pkg = body.substring(eq + 1)
            val low = pkg.lowercase(Locale.US)
            val tier = when {
                HIGH.any { low.contains(it) } -> "HIGH"
                MED.any { low.contains(it) }  -> "MED"
                else -> continue
            }
            out.add(Candidate(tier, pkg, apk))
        }
        return out
    }

    private fun sh(cmd: String): String =
        try { ProxyClient.runShell(cmd) ?: "" }
        catch (t: Throwable) { "ERR running [$cmd]: ${t.javaClass.simpleName}: ${t.message}" }

    private fun write(dir: File, name: String, content: String) {
        try { File(dir, name).writeText(content) } catch (_: Throwable) {}
    }

    fun header(ctx: Context): String =
        "DashCast ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
        "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}"
}
