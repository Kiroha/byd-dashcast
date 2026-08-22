package com.byd.dashcast.report

import android.content.Context
import com.byd.dashcast.hud.HudCaptureSupport
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Redaction where it is actually APPLIED, not where it is implemented.
 *
 * RedactorTest covers the rules as a pure function, thoroughly — and every one of those tests
 * would still pass if someone deleted the `Redactor.redact` call from an emitter. Nothing else
 * would fail either: the report would simply leave the car unfiltered, under a consent notice
 * promising the opposite. That is the silent mode these tests exist for.
 *
 * There are four call sites in the app. The two that produce an ARTEFACT are covered end-to-end
 * here; the two caption sites (BugReportActivity:84, BugWizardActivity:560) would need Activity
 * scaffolding worth more than the coverage, so the count itself is pinned instead — a fifth
 * emitter, or a deleted one, fails this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RedactionCallSiteTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // Shapes RedactorTest already proves the rules match, so a failure here is about the call
    // site, never about the pattern.
    private val vin = "LC0CE4CC1S0123456"
    private val ssid = "Livebox-ABCD"
    private val mac = "aa:bb:cc:dd:ee:11"
    private val coords = "48.858370,2.294481"

    private fun canaryText() = """
        [persist.sys.cloud.last_vin]: [$vin]
        WifiService: state screen=on SSID: "$ssid" rssi=-52
        wpa_supplicant: RX frame da=$mac
        01-01 12:00:00 I CameraDaemon: GpsMonitor: GPS: $coords alt=35
    """.trimIndent()

    private fun assertClean(where: String, text: String) {
        assertFalse("$where still carries the VIN", text.contains(vin))
        assertFalse("$where still carries the SSID", text.contains(ssid))
        assertFalse("$where still carries the MAC", text.contains(mac))
        assertFalse("$where still carries the coordinates", text.contains("48.858370"))
    }

    // ── the diagnostic archive (HudCaptureSupport.zipDir) ───────────────────────────────────

    @Test
    fun `text entries in a diagnostic archive are redacted on the way in`() {
        val work = File(ctx.cacheDir, "zip_work_redacted").apply { mkdirs() }
        File(work, "02_context.txt").writeText(canaryText())
        val zip = HudCaptureSupport.zipDir(work, File(ctx.cacheDir, "out_redacted.zip"))

        ZipFile(zip).use { z ->
            val entry = z.getEntry("02_context.txt")
            assertNotNull("the entry must exist", entry)
            val stored = z.getInputStream(entry).bufferedReader().readText()
            assertClean("the archived context sweep", stored)
            assertTrue("and it must be replaced, not merely emptied",
                stored.contains("<vin:") || stored.contains("<ssid:"))
        }
    }

    /**
     * The binary payload is the reason the extraction bundle exists. Redacting it would corrupt an
     * APK or a native library, so the extension gate must keep letting it through byte-identical —
     * a fix that redacted everything would be as wrong as one that redacted nothing.
     */
    @Test
    fun `binary entries pass through byte-identical`() {
        val work = File(ctx.cacheDir, "zip_work_binary").apply { mkdirs() }
        val payload = ByteArray(4096) { (it * 31 % 251).toByte() }
        File(work, "backend.apk").writeBytes(payload)
        val zip = HudCaptureSupport.zipDir(work, File(ctx.cacheDir, "out_binary.zip"))

        ZipFile(zip).use { z ->
            val stored = z.getInputStream(z.getEntry("backend.apk")).readBytes()
            assertArrayEquals("a binary payload must not be rewritten", payload, stored)
        }
    }

    /**
     * The size escape hatch, pinned deliberately rather than by accident.
     *
     * A text file over MAX_REDACTABLE_BYTES is streamed unfiltered. That is a real, in-design
     * limit — but it is exactly the kind of threshold that gets widened later "to be safe" without
     * anyone noticing it widens the hole too. If this test starts failing, the question to answer
     * is whether the new limit is still acceptable, not how to make the test pass.
     */
    @Test
    fun `an oversized text file is streamed unfiltered — a known, bounded gap`() {
        val work = File(ctx.cacheDir, "zip_work_huge").apply { mkdirs() }
        val filler = "x".repeat(1024 * 1024)
        val huge = File(work, "huge.txt")
        huge.writeText(canaryText() + "\n")
        repeat(9) { huge.appendText(filler) }          // > 8 MB
        assertTrue("the fixture must actually exceed the cap", huge.length() > 8L * 1024 * 1024)

        val zip = HudCaptureSupport.zipDir(work, File(ctx.cacheDir, "out_huge.zip"))
        ZipFile(zip).use { z ->
            val head = ByteArray(2048)
            z.getInputStream(z.getEntry("huge.txt")).use { it.read(head) }
            assertTrue("over the cap the file is passed through as-is",
                String(head).contains(vin))
        }
    }

    // ── the bug report body (BugReportCapture.finish) ───────────────────────────────────────

    /**
     * The choke point every report goes through, driven with the canary in the journal — which is
     * appended last and survives even the journal-only fallback path, so it is the one section
     * guaranteed to reach the body no matter what else fails under Robolectric.
     */
    @Test
    fun `the report file handed to the caller is redacted and says so`() {
        AppLogger.i("RedactionCallSiteTest", canaryText())

        val out = File(ctx.cacheDir, "byd_bugreport_test.txt")
        val latch = CountDownLatch(1)
        val produced = arrayOfNulls<File>(1)
        val failure = arrayOfNulls<String>(1)

        BugReportCapture.finish(ctx, out, "Title: canary", object : BugReportCapture.Callback {
            override fun onReady(file: File) { produced[0] = file; latch.countDown() }
            override fun onError(message: String, partial: File?) {
                failure[0] = message; produced[0] = partial; latch.countDown()
            }
        }, "transport down (test)", null)

        // finish() posts the callback to the main looper; Robolectric runs it on drain.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("the callback must fire", latch.await(10, TimeUnit.SECONDS))

        val file = produced[0]
        assertNotNull("a report file must be produced (error was ${failure[0]})", file)
        val body = file!!.readText()

        assertTrue("the canary must have reached the body at all", body.contains("Title: canary"))
        assertClean("the finished report", body)
        // The footer is what a triager reads to know whether filtering ran. A refactor that drops
        // the redaction block would take this with it — which is the point.
        assertTrue("the report must state its own redaction status",
            body.contains("════════ REDACTION ════════"))
        assertFalse("and it must not be the fail-open message",
            body.contains("was NOT filtered"))
    }

    // ── the emitter count ───────────────────────────────────────────────────────────────────

    /**
     * Four call sites, named. This is the guard for the two caption paths that are not driven
     * end-to-end above, and for any fifth emitter added later: a new place that builds text
     * leaving the car must either redact or explain itself here.
     */
    @Test
    fun `there are exactly four redaction call sites`() {
        val sites = listOf(
            "app/src/main/java/com/byd/dashcast/report/BugReportCapture.java",   // the report body
            "app/src/main/java/com/byd/dashcast/hud/HudCaptureSupport.kt",       // every zip entry
            "app/src/main/java/com/byd/dashcast/report/BugReportActivity.kt",    // the caption
            "app/src/main/java/com/byd/dashcast/report/BugWizardActivity.kt",    // the caption
        )
        // Robolectric runs with the module dir as the working directory.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, sites[0]).isFile }
        assertNotNull("could not locate the repo root from ${File("").absolutePath}", root)

        val found = sites.count { File(root, it).readText().contains("Redactor.redact") }
        assertEquals("every named emitter must still redact", sites.size, found)

        val all = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("Redactor.redact") }
            .map { it.relativeTo(root!!).path.replace(File.separatorChar, '/') }
            .toSortedSet()
        assertEquals("a new emitter appeared — cover it or document it here",
            sites.toSortedSet(), all)
    }
}
