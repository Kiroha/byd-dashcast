package com.byd.dashcast.report

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ReportChannel — credentials come from the device, not the binary.
 *
 * These cases pin the precedence rule and the pairing lifecycle. They deliberately do NOT assert
 * anything about the BuildConfig fallback values: those differ between build machines, so a test
 * that depended on them would pass here and fail on a clean checkout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReportChannelTest {

    private val ctx: android.content.Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun useAPlainStore() {
        // EncryptedSharedPreferences needs the Android KeyStore, which Robolectric does not
        // emulate. The rules under test are precedence and normalisation, not the cipher, so a
        // plain store exercises exactly what matters here.
        ReportChannel.setPrefsForTesting(
            ctx.getSharedPreferences("test_report_channel", android.content.Context.MODE_PRIVATE))
        ReportChannel.clear(ctx)
    }

    @After
    fun clean() {
        ReportChannel.clear(ctx)
        ReportChannel.setPrefsForTesting(null)
    }

    @Test
    fun `a device value wins over the build-time value`() {
        ReportChannel.saveTelegram(ctx, "device-token", "-100999", "7", "8")
        assertEquals("device-token", ReportChannel.botToken(ctx))
        assertEquals("-100999", ReportChannel.chatId(ctx))
        assertEquals("7", ReportChannel.threadId(ctx))
        assertEquals("8", ReportChannel.hudThreadId(ctx))
    }

    @Test
    fun `pairing is reported only once something is actually stored`() {
        assertFalse("a clean device is not paired", ReportChannel.isPairedOnDevice(ctx))
        ReportChannel.saveTelegram(ctx, "t", "c", "1", "2")
        assertTrue(ReportChannel.isPairedOnDevice(ctx))
    }

    @Test
    fun `clearing un-pairs the device`() {
        ReportChannel.saveTelegram(ctx, "t", "c", "1", "2")
        ReportChannel.clear(ctx)
        assertFalse(ReportChannel.isPairedOnDevice(ctx))
    }

    @Test
    fun `a complete device pair makes the transport usable`() {
        ReportChannel.saveTelegram(ctx, "t", "c", "", "")
        assertTrue(ReportChannel.hasTelegram(ctx))
        ReportChannel.saveAzure(ctx, "https://example/container", "sp=cw&sig=x")
        assertTrue(ReportChannel.hasAzure(ctx))
    }

    // NOT asserted here: that a PARTIAL device pair (token without chat) reports unusable. While
    // the BuildConfig fields still exist, an empty device value falls through to the build value —
    // which is the migration behaviour this class was written to have — so the partial case is
    // masked on a configured build machine and the assertion would be environment-dependent. It
    // becomes meaningful, and is added, in the commit that removes those fields.

    @Test
    fun `a leading question mark is stripped from the sas`() {
        // The Azure portal hands the SAS out with one; the uploader concatenates it after a '&',
        // so leaving it in produces a malformed query and a 403 that names nothing useful.
        ReportChannel.saveAzure(ctx, "https://example/c", "?sp=cw&sig=x")
        assertEquals("sp=cw&sig=x", ReportChannel.azureSas(ctx))
    }

    @Test
    fun `values are trimmed so a pasted trailing newline cannot break the request`() {
        ReportChannel.saveTelegram(ctx, "  tok  \n", " -100 \n", " 2 ", " 4 ")
        assertEquals("tok", ReportChannel.botToken(ctx))
        assertEquals("-100", ReportChannel.chatId(ctx))
    }

    @Test
    fun `an empty device value falls through to the build value instead of masking it`() {
        // Storing "" must not be read as "the device says the token is empty" — otherwise clearing
        // one field of the pair would silently disable a build that still carries credentials.
        //
        // Asserted WITHOUT naming the build value. The first version of this test compared against
        // BuildConfig.BUG_REPORT_BOT_TOKEN, and when it failed JUnit printed the live bot token in
        // clear into the HTML and XML test reports. A test must never hold a secret it can echo.
        ReportChannel.saveTelegram(ctx, "device", "c", "1", "2")
        assertEquals("device", ReportChannel.botToken(ctx))
        ReportChannel.saveTelegram(ctx, "", "c", "1", "2")
        assertEquals("an empty device value must not win",
            com.byd.dashcast.BuildConfig.BUG_REPORT_BOT_TOKEN.isEmpty(),
            ReportChannel.botToken(ctx).isEmpty())
    }
}
