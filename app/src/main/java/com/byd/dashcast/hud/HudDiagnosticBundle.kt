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

        // TEST C — AUTONAVI/AMap standard broadcast (the working DL3 path).
        // The forward (send_to di1for2 cluster) is confirmed, but a single 2-frame burst renders
        // only transiently — a tester catches it (YES) or misses it (NO) with the SAME forward.
        // So send SUSTAINED frames (~10 s, distance counting down) like a real nav, after re-asserting
        // the cluster nav-screen page, so the guidance is persistently displayed and reliably observable.
        sb.append("\n[TEST C] AUTONAVI_STANDARD_BROADCAST_SEND — SUSTAINED ~10s\n")
        log("▶ TEST C: AutoNavi broadcast (sustained ~10 s)")
        try { CanBusController.setSettingFeature(CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS, 3) } catch (_: Throwable) {}
        log("▶▶ WATCH THE CLUSTER NOW (~10 s): right-turn arrow + counting-down distance + 'TEST'")
        var dist = 300
        for (frame in 1..12) {
            try { HudAutoNaviBroadcast.sendGuide(ctx, HudAutoNaviBroadcast.AMAP_ICON_RIGHT, dist, "TEST", 1200, 720) }
            catch (t: Throwable) { sb.append("C frame $frame EXCEPTION: ${t.message}\n") }
            dist = (dist - 20).coerceAtLeast(40)
            sleep(800)
        }
        sb.append("C sent 12 sustained AUTONAVI frames (TYPE=1, dist 300→down, road TEST, icon 4)\n")
        // Capture AmapService's OWN log RIGHT NOW (tag-filtered, tail-capped) so the forward proof
        // ("send_to di1for2 cluster") is reliably captured BEFORE the very chatty device buffer
        // scrolls it out. The end-of-run `-t` logcat misses it (≈1 s of logs on this device), and a
        // `-s TAG -t N` filter cuts before filtering → empty. This makes TEST C self-verifying:
        // proof of the cluster forward no longer depends on the tester's visual answer.
        log("▶ capturing AmapService forward proof…")
        sb.append("\n[TEST C — AmapService log (proof of cluster forward)]\n")
            .append(sh("logcat -d -s AmapService:V 2>/dev/null | tail -c 80000")).append('\n')
        sleep(1500)

        sb.append("\nrc=0 = SDK accepted the write (negative = rejected / unknown feature).\n")
        sb.append("DL3 cluster forward CONFIRMED iff the AmapService log above shows " +
                "'GuideInfo.naviState: 1' + 'send_to di1for2 cluster'.\n")

        // TEST D — WINDSHIELD HUD (the actual target). The HUD has no nav-content feature; it
        // projects the instrument nav, gated by switch (on/off) + mode. So turn the HUD on, try the
        // request command, then sweep the HUD mode while feeding nav frames, so the tester can see
        // which mode (if any) shows the nav on the windshield HUD.
        sb.append("\n[TEST D] Windshield HUD — switch ON + SLOW mode sweep (~5 s each) + nav\n")
        log("▶ TEST D: HUD. ▶▶ LOOK AT THE WINDSHIELD HUD (the glass, NOT the cluster) — ~40 s.")
        fun setRc(label: String, id: Int, v: Int) {
            val rc = try { CanBusController.setSettingFeature(id, v).toString() }
                     catch (t: Throwable) { "EXC ${t.javaClass.simpleName}:${t.message}" }
            sb.append("D $label=$v rc=$rc\n"); log("D $label=$v rc=$rc")
        }
        setRc("SET_HUD_SWITCH", CanWriteVerbs.SET_HUD_SWITCH, 1)
        setRc("SETTING_HUD_REQUEST_COMMAND", CanWriteVerbs.SETTING_HUD_REQUEST_COMMAND, 1)
        log("▶▶ Did the WINDSHIELD HUD just turn ON (any content)? (3 s)"); sleep(3000)
        // Slow sweep so the tester can correlate AND photograph each mode separately.
        for (mode in 0..6) {
            setRc("SET_HUD_MODE", CanWriteVerbs.SET_HUD_MODE, mode)
            // Daemon read-back: does the HUD mode feedback reflect the mode we just wrote?
            //  reflects → get() is a live read; stays 0/ERR → push-only feedback (need a listener).
            val fb = try { com.byd.dashcast.proxy.ProxyClient.canSettingGet(CanWriteVerbs.SET_HUD_MODE_FEEDBACK).toString() }
                     catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }
            sb.append("D   readback SET_HUD_MODE_FEEDBACK=$fb (wrote $mode)\n")
            log("D   readback FEEDBACK=$fb (wrote $mode)")
            log("▶▶ HUD MODE = $mode — LOOK AT / PHOTOGRAPH THE WINDSHIELD HUD NOW (~5 s)")
            repeat(6) {  // ~5 s of live nav at this mode
                try { HudAutoNaviBroadcast.sendGuide(ctx, HudAutoNaviBroadcast.AMAP_ICON_RIGHT, 300, "TEST", 1200, 720) }
                catch (_: Throwable) {}
                sleep(800)
            }
        }
        sb.append("D HUD slow sweep done (modes 0..6, ~5 s each). " +
                "NOTE/PHOTOGRAPH which mode (if any) showed nav on the windshield HUD.\n")
        // P2 — capture the HUD's own reaction right after the sweep (tag-filtered, tail-capped)
        // so the zip shows what the HUD/SettingDevice did per write, not just the tester's eyes.
        log("▶ capturing HUD reaction logcat…")
        sb.append("\n[TEST D — HUD reaction logcat]\n")
            .append(sh("logcat -d -v threadtime 2>/dev/null | grep -iE 'HUD|抬头|head.?up|HUD_MODE|HUD_SWITCH' | tail -c 60000"))
            .append('\n')

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

    // ── P4 — OEM nav HUD baseline ──────────────────────────────────────────────
    // The tester starts the REAL OEM nav (BydMap) with the HUD ON; we clear the log,
    // let the OEM drive the HUD for ~12 s, then dump the HUD/nav/instrument-filtered
    // logcat — to find WHICH service/signal actually puts nav on the HUD (we never send
    // anything here, so whatever appears is the OEM's own HUD channel). Returns the work dir.
    fun collectOemBaseline(ctx: Context, progress: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val work = File(ctx.cacheDir, "hud_oem_baseline_$stamp").apply { mkdirs() }
        progress("[1/4] clearing logcat…"); sh("logcat -c -b all 2>/dev/null")
        progress("[2/4] OEM nav must be RUNNING on the HUD now — capturing ~12 s…")
        try { Thread.sleep(12000) } catch (_: InterruptedException) {}
        progress("[3/4] dumping HUD / nav / instrument logcat…")
        write(work, "01_oem_hud_logcat.txt", sh(
            "logcat -d -v threadtime 2>/dev/null | grep -iE " +
            "'HUD|抬头|head.?up|navi|nav|instrument|cluster|amap|neusoft|AutoContainer|InstrumentDevice|SettingDevice|di1for2|发送|仪表' " +
            "| tail -c 300000"))
        write(work, "02_running.txt",
            "----- foreground / resumed -----\n" +
            sh("dumpsys activity activities 2>/dev/null | grep -iE 'mResumedActivity|topActivity|realActivity|displayId' | head -c 30000") +
            "\n----- nav/map/hud packages -----\n" +
            sh("pm list packages 2>/dev/null | grep -iE 'map|nav|hud|neusoft|amap'"))
        write(work, "03_props.txt", sh("getprop 2>/dev/null"))
        progress("[4/4] baseline captured.")
        return work
    }

    // ── HUD nav-mode reader ─────────────────────────────────────────────────────
    // The HUD DOES turn-by-turn (confirmed). We only miss the value the OEM applies to
    // SET_HUD_MODE for nav. Read the HUD/nav feedback features in a loop WHILE the OEM nav
    // is actively guiding on the HUD → capture that mode value, then we replicate it.
    fun collectHudStateRead(ctx: Context, progress: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val work = File(ctx.cacheDir, "hud_state_read_$stamp").apply { mkdirs() }
        val settingIds = intArrayOf(
            CanWriteVerbs.SET_HUD_MODE, CanWriteVerbs.SET_HUD_MODE_FEEDBACK,
            CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.SET_HUD_SWITCH_STATUS_FEEDBACK,
            CanWriteVerbs.SETTING_NAVI_SCREEN_STATUS)
        val instrIds = intArrayOf(
            CanWriteVerbs.INSTRUMENT_SEND_NAVI_STATUS, CanWriteVerbs.INSTRUMENT_GUIDE_SIMPLE,
            CanWriteVerbs.INSTRUMENT_FRONT_CROSSING_DIST)
        val sb = StringBuilder("=== HUD STATE READ — capture WHILE the OEM nav shows turn-by-turn on the HUD ===\n")
        sb.append("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
                "${Build.MANUFACTURER} ${Build.MODEL} ${Build.PRODUCT} API ${Build.VERSION.SDK_INT}\n")
        sb.append("setting ids: ").append(settingIds.joinToString { "0x%08X".format(it) }).append('\n')
        sb.append("instrument ids: ").append(instrIds.joinToString { "0x%08X".format(it) }).append("\n")
        // Describe the read API once (so we learn the real get* signatures + any error cause).
        sb.append(HudStateReader.describeReadApi(ctx, HudStateReader.SETTING_CLASS)).append('\n')
        sb.append(HudStateReader.describeReadApi(ctx, HudStateReader.INSTRUMENT_CLASS)).append("\n")
        // Push-feedback LISTENER API discovery (class structure only — no instance, no permission).
        // get() proved push-only (readback always 0); to capture pushes we must registerListener a
        // BYD listener. This reveals whether AbsBYDAuto*Listener is subclassable + its callback names.
        sb.append("[listener API discovery]\n")
        sb.append(HudStateReader.describeClass(HudStateReader.SETTING_LISTENER, listOf("HUD", "Hud", "Feature", "Navi", "Changed")))
        sb.append(HudStateReader.describeClass(HudStateReader.INSTRUMENT_LISTENER, listOf("HUD", "Hud", "Feature", "Navi", "Guide", "Changed")))
        sb.append(HudStateReader.describeClass(HudStateReader.SETTING_CLASS, listOf("register", "Listener", "unregister")))
        sb.append(HudStateReader.describeClass(HudStateReader.INSTRUMENT_CLASS, listOf("register", "Listener", "unregister")))
        sb.append("\n")
        // One in-app read up front for contrast (known to throw — the SDK rejects app-uid reads).
        sb.append("[in-app read — expected to fail, kept for contrast]\n")
        sb.append(HudStateReader.read(ctx, HudStateReader.SETTING_CLASS, settingIds))
        sb.append(HudStateReader.read(ctx, HudStateReader.INSTRUMENT_CLASS, instrIds)).append('\n')
        // Write→readback self-check: does the daemon get() reflect a value we just WROTE?
        //  • readback == written value → get() is a LIVE read; the all-zero loop below then
        //    means no nav/HUD was active during capture (procedure issue).
        //  • readback stays 0 → the feedback is push-only (listener-based) and get() cannot
        //    observe it → we must register a BYD feedback listener instead of polling get().
        sb.append("[daemon write→readback self-check]\n")
        sb.append(selfCheck("SET_HUD_SWITCH", CanWriteVerbs.SET_HUD_SWITCH, 1,
                CanWriteVerbs.SET_HUD_SWITCH_STATUS_FEEDBACK))
        sb.append(selfCheck("SET_HUD_MODE", CanWriteVerbs.SET_HUD_MODE, 2,
                CanWriteVerbs.SET_HUD_MODE_FEEDBACK))
        sb.append('\n')
        // Register the daemon PUSH listener (get() is push-only) — captures whatever the OEM
        // nav pushes to the setting features (incl. the HUD mode) while it guides on the HUD.
        sb.append("[push listener] start → ").append(listenStart()).append('\n')
        progress("OEM nav must be ACTIVELY guiding on the HUD now — capturing pushes ~12 s…")
        for (t in 1..10) {
            sb.append("---- t=$t ----\n")
            sb.append("[setting via daemon]\n")
            for (id in settingIds) sb.append(daemonRead(id, false)).append('\n')
            sb.append("[instrument via daemon]\n")
            for (id in instrIds) sb.append(daemonRead(id, true)).append('\n')
            sb.append("[push events]\n").append(listenDrain())
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
        }
        write(work, "01_hud_state_read.txt", sb.toString())
        write(work, "02_props.txt", sh("getprop 2>/dev/null | grep -iE 'hud|fission_single_os|model|inswver'"))
        progress("HUD state read captured — look for the SET_HUD_MODE / FEEDBACK value while nav was on the HUD.")
        return work
    }

    /** Writes a known value via the daemon then reads back both the set-id and its feedback-id. */
    private fun selfCheck(name: String, setId: Int, value: Int, feedbackId: Int): String {
        val s = StringBuilder()
        val rc = try { com.byd.dashcast.proxy.ProxyClient.canSettingInt(setId, value).toString() }
                 catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }
        s.append("set %s(0x%08X)=%d → rc=%s\n".format(name, setId, value, rc))
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        s.append("  get set-id   → ").append(daemonRead(setId, false)).append('\n')
        s.append("  get feedback → ").append(daemonRead(feedbackId, false)).append('\n')
        return s.toString()
    }

    /** Registers the daemon push-feedback listener; returns its status (or the error). */
    private fun listenStart(): String = try {
        com.byd.dashcast.proxy.ProxyClient.canListenStart()
    } catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }

    /** Drains the daemon push events (always newline-terminated). */
    private fun listenDrain(): String {
        val s = try { com.byd.dashcast.proxy.ProxyClient.canListenDrain() }
                catch (t: Throwable) { "ERR ${t.message ?: t.javaClass.simpleName}" }
        return if (s.endsWith("\n")) s else "$s\n"
    }

    /** Reads one CAN feature through the daemon (privileged context). Formats the value or the error. */
    private fun daemonRead(id: Int, instrument: Boolean): String = try {
        val v = if (instrument) com.byd.dashcast.proxy.ProxyClient.canInstrumentGet(id)
                else            com.byd.dashcast.proxy.ProxyClient.canSettingGet(id)
        "0x%08X = %d".format(id, v)
    } catch (t: Throwable) {
        "0x%08X ERR %s".format(id, t.message ?: t.javaClass.simpleName)
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
