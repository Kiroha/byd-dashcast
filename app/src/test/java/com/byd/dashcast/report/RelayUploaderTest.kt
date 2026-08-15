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
    fun `an explicitly blank device endpoint is not a relay`() {
        // A provisioning file that carries an empty relay.url must not read as "relay available"
        // and shadow the built-in one into silence.
        ReportConsent.grant(ctx)
        ReportChannel.applyProperties(ctx, "relay.url=")
        assertEquals("the built-in endpoint still stands", RelayUploader.DEFAULT_URL, RelayUploader.url())
        assertTrue(RelayUploader.isConfigured())
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
    fun `the built-in endpoint is an https url and nothing else`() {
        // It ships in the APK on purpose, so the thing to keep asserting is that it stays the kind
        // of value that is safe to ship: a plain HTTPS address, never a query string, which is
        // where a function key would end up if someone ever added one.
        val u = RelayUploader.DEFAULT_URL
        assertTrue("built-in endpoint must be https", u.startsWith("https://"))
        assertFalse("a credential must never ride in the URL", u.contains("?"))
        assertFalse(u.contains("code="))
    }

    @Test
    fun `consent still gates the built-in endpoint`() {
        // The endpoint being compiled in must not make the app configured on its own: a device
        // that has not answered the notice sends nothing, relay or no relay.
        assertFalse(RelayUploader.isConfigured())
        ReportConsent.grant(ctx)
        assertTrue(RelayUploader.isConfigured())
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
