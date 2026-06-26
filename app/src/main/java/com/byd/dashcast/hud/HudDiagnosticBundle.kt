package com.byd.dashcast.hud

import android.content.Context
import android.os.Build
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.CanWriteVerbs
import com.byd.dashcast.system.CanBusController
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One-shot DL3 HUD investigation bundle. Runs every known way to drive the cluster
 * nav, scrapes the framework, captures the on-device environment (packages, the nav
 * service's declared receivers/services, logcat) and pulls candidate system APKs for
 * off-car reverse engineering — then everything is zipped and uploaded by the caller.
 *
 * Goal: find how the **DiLink 3** HUD is actually driven (even Open-BYD's CAN path,
 * which works on DL5.1, does NOT render on DL3 — so the mechanism is still unknown).
 *
 * Best-effort throughout: every section is independently guarded so a failure in one
 * never aborts the rest.
 */
object HudDiagnosticBundle {

    private const val APK_BUDGET_TOTAL = 35L * 1024 * 1024   // ~35 MB total (Telegram bot limit is 50)
    private const val APK_BUDGET_FILE  = 22L * 1024 * 1024   // skip individual APKs larger than this
    private const val APK_MAX_COUNT    = 10

    // Package-name fragments worth pulling/inspecting, highest RE value first.
    private val HIGH = listOf("navi", "autonavi", "amap", "gaode", "cluster", "instrument",
        "hud", "carlife", "appservice", "someip")
    private val MED  = listOf("byd", "dilink", "neusoft", "map", "nav", "tts")

    /**
     * Collects all artifacts into a fresh working directory and returns it.
     * Caller adds the visual result, then calls [zipDir] and uploads.
     */
    fun collect(ctx: Context, log: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val work = File(ctx.cacheDir, "hud_diag_$stamp").apply { mkdirs() }

        runTests(ctx, work, log)
        capseScrape(work, log)
        captureEnv(work, log)
        pullApks(ctx, work, log)

        return work
    }

    // ── 1. the three HUD-driving paths ──────────────────────────────────────
    private fun runTests(ctx: Context, work: File, log: (String) -> Unit) {
        val sb = StringBuilder()
        sb.append("=== DASHCAST DL3 HUD FULL DIAGNOSTIC ===\n")
        sb.append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} — ${Build.PRODUCT}, API ${Build.VERSION.SDK_INT}\n")
        try { ProxyClient.connect(ctx) } catch (_: Throwable) {}
        sb.append("ProxyClient connected: ${runCatching { ProxyClient.isConnected() }.getOrDefault(false)}\n\n")

        fun step(label: String, block: () -> Int) {
            val line = try { "$label -> rc=${block()}" }
                       catch (t: Throwable) { "$label -> EXCEPTION ${t.javaClass.simpleName}: ${t.message}" }
            sb.append(line).append('\n'); log(line)
        }

        // TEST A — raw feature-ID register path (what DashCast does today, via the daemon)
        sb.append("[TEST A] feature-ID register writes (watch the cluster ~3s)\n"); log("▶ TEST A: feature-ID writes")
        step("A SETTING_NAVI_SCREEN=3") { CanBusController.setSettingFeature(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3) }
        step("A SEND_NAVI_STATUS=active") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, CanWriteVerbs.NAVI_STATUS_ACTIVE) }
        step("A GUIDE_SIMPLE=turn-right") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE, CanBusController.ICON_TURN_RIGHT) }
        step("A GUIDE_ROAD_DISTANCE=turn-right") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_GUIDE_ROAD_DISTANCE, CanBusController.ICON_TURN_RIGHT) }
        step("A FRONT_CROSSING=300m") { CanBusController.setFeatureInt(CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST, 300) }
        step("A NEXT_PATHNAME=TEST (UTF-16LE)") { CanBusController.setFeatureBytes(CanWriteVerbs.INSTRUMENT_NEXT_PATHNAME, "TEST".toByteArray(Charsets.UTF_16LE)) }
        sleep(3000)

        // TEST B — dedicated high-level SDK methods (Open-BYD's working DL5.1 recipe; in-app)
        sb.append("\n[TEST B] dedicated SDK methods (sendAutoNaviStatus etc., watch ~3s)\n"); log("▶ TEST B: SDK methods")
        step("B sendAutoNaviStatus(2)") { HudInstrumentSdk.sendAutoNaviStatus(ctx, 2) }
        step("B sendSimpleGuidanceInfo(2,300)") { HudInstrumentSdk.sendSimpleGuidanceInfo(ctx, CanBusController.ICON_TURN_RIGHT, 300) }
        step("B sendNextPathName(TEST)") { HudInstrumentSdk.sendNextPathName(ctx, "TEST") }
        step("B sendRestRouteInfo(0,12,1200)") { HudInstrumentSdk.sendRestRouteInfo(ctx, 0, 12, 1200L) }
        sleep(3000)

        // TEST C — AUTONAVI/AMap standard broadcast (prime suspect for DL3)
        sb.append("\n[TEST C] AUTONAVI_STANDARD_BROADCAST_SEND (watch ~3s)\n"); log("▶ TEST C: AutoNavi broadcast")
        try {
            val r = HudAutoNaviBroadcast.sendGuide(ctx, HudAutoNaviBroadcast.AMAP_ICON_RIGHT, 300, "TEST", 1200, 720)
            sb.append("C app sendBroadcast: $r\n"); log("C $r")
        } catch (t: Throwable) { sb.append("C app sendBroadcast EXCEPTION: ${t.message}\n") }
        // also from the privileged daemon (uid shell), in case a normal-app broadcast is filtered
        // TYPE must be 0 or 1 for AmapService to process guidance (not 8 — see HudAutoNaviBroadcast).
        val amCmd = "am broadcast -a ${HudAutoNaviBroadcast.ACTION} " +
                "--ei KEY_TYPE 10001 --ei TYPE 1 --ei EXTRA_STATE 8 --ei EXTRA_IS_FOREGROUND 0 " +
                "--ez IS_BYD_MAP true --ei NEW_ICON 4 --ei SEG_REMAIN_DIS 300 --es NEXT_ROAD_NAME TEST " +
                "--ei ROUTE_REMAIN_DIS 1200 --ei ROUTE_REMAIN_TIME 720"
        sb.append("C daemon am broadcast:\n").append(sh(amCmd)).append('\n')
        sleep(3000)

        sb.append("\nrc=0 = SDK accepted the write (negative = rejected / unknown feature).\n")
        write(work, "01_tests.txt", sb.toString())
    }

    // ── 2. framework scrape ─────────────────────────────────────────────────
    private fun capseScrape(work: File, log: (String) -> Unit) {
        log("▶ scraping BYDAutoFeatureIds…")
        val dump = try { HudFeatureScraper.scrape() } catch (t: Throwable) { "scrape failed: ${t.message}" }
        write(work, "02_scrape.txt", dump)
    }

    // ── 3. environment capture (packages, nav-service manifests, logcat) ─────
    private fun captureEnv(work: File, log: (String) -> Unit) {
        log("▶ capturing environment (packages / receivers / logcat)…")

        val pkgList = sh("pm list packages -f")
        write(work, "03_packages.txt", pkgList)

        // Flag candidate packages by name; dump each high-value one's manifest info.
        val candidates = parseCandidates(pkgList)
        write(work, "04_candidates.txt",
            candidates.joinToString("\n") { "${it.tier}\t${it.pkg}\t${it.apkPath}" })

        val dumps = StringBuilder()
        candidates.filter { it.tier == "HIGH" }.take(6).forEach { c ->
            dumps.append("\n===== dumpsys package ${c.pkg} =====\n")
            dumps.append(sh("dumpsys package ${c.pkg}"))
        }
        write(work, "05_candidate_dumpsys.txt", dumps.toString())

        // Cluster-type discriminator: "1" => single-OS fission (BYDAutoInstrumentDevice CAN path,
        // works on DL5.1); anything else => "1 for 2" cluster driven by AutoContainerManager (DL3).
        write(work, "09_props.txt",
            "ro.build.system.fission_single_os=" + sh("getprop ro.build.system.fission_single_os") +
            "\n----- full getprop -----\n" + sh("getprop"))

        // Tag-filtered logcat FIRST (small + the smoking gun): does AmapService forward our
        // broadcast to the 1for2 cluster? It logs "send_to di1for2 cluster" / "发送独立仪表…".
        write(work, "06_logcat_amapservice.txt", sh("logcat -d -s AmapService:V -t 400"))
        // General recent buffer, kept SMALL so the daemon parcel never overflows (-t 4000 killed it).
        val logcat = sh("logcat -d -v threadtime -t 600")
        write(work, "06_logcat.txt", logcat)
        val rx = Regex("(?i)navi|instrument|autonavi|amap|gaode|cluster|hud|byd|guidance|someip|container")
        write(work, "07_logcat_filtered.txt",
            logcat.lineSequence().filter { rx.containsMatchIn(it) }.joinToString("\n"))
    }

    // ── 4. pull candidate APKs (world-readable base.apk) under a size budget ──
    private fun pullApks(ctx: Context, work: File, log: (String) -> Unit) {
        log("▶ pulling candidate APKs…")
        val apkDir = File(work, "apks").apply { mkdirs() }
        val manifest = StringBuilder("APK pull manifest (budget ${APK_BUDGET_TOTAL / 1024 / 1024} MB)\n")
        val candidates = parseCandidates(sh("pm list packages -f"))
            .sortedBy { if (it.tier == "HIGH") 0 else 1 }
        var total = 0L
        var count = 0
        for (c in candidates) {
            if (count >= APK_MAX_COUNT) { manifest.append("SKIP ${c.pkg}: max count\n"); continue }
            val src = File(c.apkPath)
            if (!src.canRead()) { manifest.append("SKIP ${c.pkg}: not readable (${c.apkPath})\n"); continue }
            val sz = src.length()
            if (sz > APK_BUDGET_FILE) { manifest.append("SKIP ${c.pkg}: too big (${sz / 1024 / 1024} MB) ${c.apkPath}\n"); continue }
            if (total + sz > APK_BUDGET_TOTAL) { manifest.append("SKIP ${c.pkg}: over total budget (${sz / 1024 / 1024} MB)\n"); continue }
            try {
                val dst = File(apkDir, c.pkg + ".apk")
                src.copyTo(dst, overwrite = true)
                total += sz; count++
                manifest.append("OK   ${c.pkg} (${sz / 1024} KB) ${c.apkPath}\n")
            } catch (t: Throwable) {
                manifest.append("FAIL ${c.pkg}: ${t.message}\n")
            }
        }
        manifest.append("\nTotal pulled: $count APKs, ${total / 1024 / 1024} MB\n")
        write(work, "08_apk_manifest.txt", manifest.toString())
    }

    // ── zip ──────────────────────────────────────────────────────────────────
    fun zipDir(work: File): File {
        val zip = File(work.parentFile, work.name + ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            work.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(work).path
                zos.putNextEntry(ZipEntry(rel))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }

    // ── helpers ────────────────────────────────────────────────────────────
    private data class Candidate(val tier: String, val pkg: String, val apkPath: String)

    private fun parseCandidates(pmListOutput: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (raw in pmListOutput.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            val body = line.removePrefix("package:")
            val eq = body.lastIndexOf('=')
            if (eq <= 0) continue
            val apk = body.substring(0, eq)
            val pkg = body.substring(eq + 1)
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
        try { File(dir, name).writeText(content) } catch (_: Throwable) { /* best effort */ }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
