package com.byd.dashcast.report

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The relay, and the property that made it worth building: nothing in the APK grants anything.
 *
 * These cases pin the gate and the additive contract. The upload itself is not exercised — it is
 * one HttpURLConnection against a real endpoint, and a test that stubbed it would assert that the
 * stub works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RelayUploaderTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshDevice() {
        ReportConsent.clearForTesting(ctx)
        ReportConsent.init(ctx)
        ReportChannel.init(ctx)
        ReportChannel.setPrefsForTesting(
            ctx.getSharedPreferences("test_relay", Context.MODE_PRIVATE))
        ReportChannel.clear(ctx)
    }

    @After
    fun clean() {
        ReportConsent.clearForTesting(ctx)
        ReportChannel.clear(ctx)
        ReportChannel.setPrefsForTesting(null)
    }

    private fun setRelay(url: String) =
        ReportChannel.applyProperties(ctx, "relay.url=$url")

    // ── the gate ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an endpoint without consent sends nothing`() {
        setRelay("https://example.invalid/api/report")
        assertTrue("the endpoint really is stored", RelayUploader.url().isNotEmpty())
        assertFalse("a refusal is not pending configuration", RelayUploader.isConfigured())
    }

    @Test
    fun `consent without an endpoint sends nothing either`() {
        ReportConsent.grant(ctx)
        assertFalse(RelayUploader.isConfigured())
    }

    @Test
    fun `both together open the road`() {
        ReportConsent.grant(ctx)
        setRelay("https://example.invalid/api/report")
        assertTrue(RelayUploader.isConfigured())
    }

    @Test
    fun `revoking closes it again without touching the endpoint`() {
        ReportConsent.grant(ctx)
        setRelay("https://example.invalid/api/report")
        ReportConsent.deny(ctx)
        assertFalse(RelayUploader.isConfigured())
        assertTrue("the endpoint is not a credential and is not destroyed",
            RelayUploader.url().isNotEmpty())
    }

    // ── additive by construction ────────────────────────────────────────────────────────────

    @Test
    fun `an undeployed relay changes nothing`() {
        // The whole migration rests on this: until DEFAULT_URL is filled in and no device override
        // exists, every reporting path behaves exactly as it did before the relay was written.
        assertEquals("", RelayUploader.DEFAULT_URL)
        ReportConsent.grant(ctx)
        assertFalse(RelayUploader.isConfigured())

        ReportChannel.saveTelegram(ctx, "token", "-100123", "2", "4")
        assertTrue("the direct path still works", TelegramBugReporter.isConfigured())
    }

    @Test
    fun `a relay alone is enough to make the transport configured`() {
        // The point of the exercise: no bot token on the device, and reports still leave.
        ReportConsent.grant(ctx)
        setRelay("https://example.invalid/api/report")
        assertFalse("no credential is stored", ReportChannel.hasTelegram())
        assertTrue(TelegramBugReporter.isConfigured())
    }

    @Test
    fun `a device value overrides the built-in endpoint`() {
        setRelay("https://test-deployment.invalid/api/report")
        assertEquals("https://test-deployment.invalid/api/report", RelayUploader.url())
    }

    @Test
    fun `an endpoint is not counted as a credential set`() {
        // applyProperties returns how many credential sets were stored. A file carrying only a
        // relay URL has provisioned nothing that needs protecting, and reporting otherwise would
        // make the pairing outcome message lie.
        assertEquals(0, setRelay("https://example.invalid/api/report"))
        assertFalse(ReportChannel.isPairedOnDevice(ctx))
    }

    // ── the file name the relay will accept ─────────────────────────────────────────────────

    @Test
    fun `a report name passes through untouched`() {
        assertEquals("byd_bugreport_20260815_120000.txt",
            RelayUploader.safeName("byd_bugreport_20260815_120000.txt"))
    }

    @Test
    fun `a path separator cannot survive in a name`() {
        val n = RelayUploader.safeName("../../etc/passwd")
        assertFalse(n.contains("/"))
        assertFalse(n.contains(".."))
    }

    @Test
    fun `a name is repaired rather than rejected`() {
        // The relay refuses a bad name outright, so repairing here saves a round trip that would
        // otherwise fail after the whole file had been uploaded.
        assertTrue(RelayUploader.safeName("rapport été.txt").matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(RelayUploader.safeName("").isNotEmpty())
        assertTrue(RelayUploader.safeName("_leading").first().isLetterOrDigit())
        assertTrue(RelayUploader.safeName("x".repeat(400)).length <= 120)
    }
}
