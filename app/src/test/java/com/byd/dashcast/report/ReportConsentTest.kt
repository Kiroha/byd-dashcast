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
 * The consent gate — what may leave the car, and when.
 *
 * The cases that matter here are the ones where being wrong sends someone's data somewhere they
 * did not agree to. So they are written from that direction: the default, the refusal, and the
 * stale agreement all have to close the road, and the transport has to honour them without any
 * screen being involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReportConsentTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshDevice() {
        ReportConsent.clearForTesting(ctx)
        ReportConsent.init(ctx)
        ReportChannel.init(ctx)
        ReportChannel.setPrefsForTesting(
            ctx.getSharedPreferences("test_report_channel", Context.MODE_PRIVATE))
        ReportChannel.clear(ctx)
    }

    @After
    fun clean() {
        ReportConsent.clearForTesting(ctx)
        ReportChannel.clear(ctx)
        ReportChannel.setPrefsForTesting(null)
    }

    @Test
    fun `out of the box nothing may be sent`() {
        assertEquals(ReportConsent.State.UNKNOWN, ReportConsent.state(ctx))
        assertFalse("silence is not agreement", ReportConsent.isGranted(ctx))
        assertFalse(ReportConsent.isGranted())
    }

    @Test
    fun `an answer is remembered both ways`() {
        ReportConsent.grant(ctx)
        assertEquals(ReportConsent.State.GRANTED, ReportConsent.state(ctx))
        assertTrue(ReportConsent.isGranted(ctx))

        ReportConsent.deny(ctx)
        assertEquals(ReportConsent.State.DENIED, ReportConsent.state(ctx))
        assertFalse(ReportConsent.isGranted(ctx))
    }

    @Test
    fun `resetting puts the question back`() {
        ReportConsent.grant(ctx)
        ReportConsent.reset(ctx)
        assertEquals(ReportConsent.State.UNKNOWN, ReportConsent.state(ctx))
    }

    @Test
    fun `an answer to an older notice does not count`() {
        // Someone agreed to a description of what was collected. If the app then starts collecting
        // more, that old yes is not a yes to the new list — it has to lapse into a question again,
        // or the notice is decoration.
        ctx.getSharedPreferences("dashcast_report_consent", Context.MODE_PRIVATE)
            .edit()
            .putString("answer", "granted")
            .putInt("notice_version", ReportConsent.NOTICE_VERSION - 1)
            .commit()
        assertEquals(ReportConsent.State.UNKNOWN, ReportConsent.state(ctx))
        assertFalse(ReportConsent.isGranted(ctx))
    }

    // ── the transport gate ───────────────────────────────────────────────────────────────────
    //
    // These are the regression guards for the whole design: the seven screens that can start an
    // upload were deliberately left alone, so if these break, every one of them starts uploading
    // without an answer and nothing else in the codebase would notice.

    @Test
    fun `credentials alone do not authorise an upload`() {
        ReportChannel.saveTelegram(ctx, "token", "-100123", "2", "4")
        ReportChannel.saveAzure(ctx, "https://example.blob.core.windows.net/re", "sv=1&sp=cw")
        assertTrue("the channel really is provisioned", ReportChannel.isPairedOnDevice(ctx))

        assertFalse("no answer yet — nothing goes out", TelegramBugReporter.isConfigured())
        assertFalse("no answer yet — nothing goes out", AzureBlobUploader.isConfigured())
    }

    @Test
    fun `a refusal closes every road out of the car`() {
        ReportChannel.saveTelegram(ctx, "token", "-100123", "2", "4")
        ReportChannel.saveAzure(ctx, "https://example.blob.core.windows.net/re", "sv=1&sp=cw")
        ReportConsent.deny(ctx)

        assertFalse("Telegram", TelegramBugReporter.isConfigured())
        assertFalse("Azure — a refusal is not per-channel", AzureBlobUploader.isConfigured())
    }

    @Test
    fun `agreement alone now opens the road, and that is the point of the relay`() {
        // Before the relay this asserted the opposite: consent without a stored credential sent
        // nothing, because the device had nothing to send with. The relay is exactly the change
        // that makes agreement sufficient — a car can report while holding no secret at all.
        ReportConsent.grant(ctx)
        assertTrue("the relay endpoint ships in the APK and is not a credential",
            TelegramBugReporter.isConfigured())

        // Azure is a different transport with its own credential, and the relay does not stand in
        // for it. A blob upload still needs a container and a SAS.
        assertFalse("Azure is still unprovisioned", AzureBlobUploader.isConfigured())
        ReportChannel.saveAzure(ctx, "https://example.blob.core.windows.net/re", "sv=1&sp=cw")
        assertTrue(AzureBlobUploader.isConfigured())
    }

    @Test
    fun `revoking after agreeing stops the uploads again`() {
        ReportChannel.saveTelegram(ctx, "token", "-100123", "2", "4")
        ReportConsent.grant(ctx)
        assertTrue(TelegramBugReporter.isConfigured())

        ReportConsent.deny(ctx)
        assertFalse("revocation takes effect without touching the credentials",
            TelegramBugReporter.isConfigured())
        assertTrue("and it does not destroy them", ReportChannel.isPairedOnDevice(ctx))
    }

    @Test
    fun `the block reason distinguishes the two very different causes`() {
        // A tester reading a diagnostic log has to be able to tell "tap a row in Settings" from
        // "ask the maintainer for a channel".
        assertTrue(ReportConsent.transportBlockReason().contains("Settings"))

        ReportConsent.grant(ctx)
        assertTrue(ReportConsent.transportBlockReason().contains("channel"))
    }
}
