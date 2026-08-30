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


    @Test
    fun `a leading question mark is stripped from the sas`() {
        // The Azure portal hands the SAS out with one; the uploader concatenates it after a '&',
        // so leaving it in produces a malformed query and a 403 that names nothing useful.
        ReportChannel.saveAzure(ctx, "https://example/c", "?sp=cw&sig=x")
        assertEquals("sp=cw&sig=x", ReportChannel.azureSas(ctx))
    }

    @Test
    fun `an insecure or credential-bearing Azure URL is rejected`() {
        assertFalse(ReportChannel.saveAzure(ctx, "http://blob.example/container", "sp=cw&sig=x"))
        assertFalse(ReportChannel.saveAzure(
            ctx,
            "https://blob.example/container?sv=2024&sig=secret",
            "sp=cw&sig=x"
        ))
        assertFalse(ReportChannel.hasAzure(ctx))
        assertEquals("", ReportChannel.azureUrl(ctx))
    }

    @Test
    fun `values are trimmed so a pasted trailing newline cannot break the request`() {
        ReportChannel.saveTelegram(ctx, "  tok  \n", " -100 \n", " 2 ", " 4 ")
        assertEquals("tok", ReportChannel.botToken(ctx))
        assertEquals("-100", ReportChannel.chatId(ctx))
    }

    @Test
    fun `the provisioning file is looked for in Download before anywhere else`() {
        // Download first is the point of the whole change: it is the only location a tester can
        // reach without a computer — USB stick, file manager, or a download on the head unit.
        // /data/local/tmp needs adb push, so it must never be the one that decides.
        assertTrue(ReportChannel.IMPORT_PATHS.first().contains("/Download/"))
        assertTrue(ReportChannel.IMPORT_PATHS.any { it.startsWith("/data/local/tmp/") })
        assertTrue(ReportChannel.IMPORT_PATHS.all { it.endsWith(ReportChannel.IMPORT_NAME) })
    }

    @Test
    fun `auto-pairing does nothing once the device is paired`() {
        // Guard against a shell call on every cold start for the rest of the install's life.
        ReportChannel.saveTelegram(ctx, "t", "c", "1", "2")
        assertTrue(ReportChannel.isPairedOnDevice(ctx))
        ReportChannel.autoPairIfNeeded(ctx)   // must return before touching the shell
        assertEquals("t", ReportChannel.botToken(ctx))
    }

    @Test
    fun `an unpaired device has no credentials at all`() {
        // The whole point of AUD-001: with the buildConfigField entries gone there is nothing to
        // fall back to, so an unpaired device reports no transport rather than quietly using one
        // baked into the binary.
        assertEquals("", ReportChannel.botToken(ctx))
        assertEquals("", ReportChannel.azureSas(ctx))
        assertFalse(ReportChannel.hasTelegram(ctx))
        assertFalse(ReportChannel.hasAzure(ctx))
    }

    @Test
    fun `a partial pair is not usable`() {
        // Deferred until the build-time fallback was removed: while it existed an empty device
        // value fell through to the build value, so this case was masked on a configured machine.
        ReportChannel.saveTelegram(ctx, "t", "", "", "")
        assertFalse("a token with no destination is not usable", ReportChannel.hasTelegram(ctx))
        ReportChannel.saveAzure(ctx, "https://example/c", "")
        assertFalse("a url with no sas is not usable", ReportChannel.hasAzure(ctx))
    }

    // ── the parser shared by the folder route and the paste route ────────────────────────────

    @Test
    fun `a full provisioning blob stores both credential sets`() {
        val applied = ReportChannel.applyProperties(ctx, """
            # DashCast reporting channel
            bugReport.botToken=123:ABC
            bugReport.chatId=-100999999999
            bugReport.threadId=2
            bugReport.hudThreadId=4
            azure.blobUrl=https://example.blob.core.windows.net/re
            azure.sas=sv=2024-01-01&sp=cw&sig=abc
        """.trimIndent())
        assertEquals(2, applied)
        assertTrue(ReportChannel.hasTelegram(ctx))
        assertTrue(ReportChannel.hasAzure(ctx))
    }

    @Test
    fun `an equals sign inside a value survives`() {
        // Azure SAS strings are query strings: splitting on every '=' instead of the first one
        // would truncate the signature and produce a credential that fails only at upload time.
        ReportChannel.applyProperties(ctx, "azure.blobUrl=https://x/y\nazure.sas=sv=2024&sig=a=b")
        assertEquals("sv=2024&sig=a=b", ReportChannel.azureSas(ctx))
    }

    @Test
    fun `comments blank lines and unknown keys are ignored`() {
        val applied = ReportChannel.applyProperties(ctx, """

            # a comment
            unrelated.key=whatever
            not a properties line
            bugReport.botToken=t
            bugReport.chatId=c
        """.trimIndent())
        assertEquals("one set, from the two keys that mean something", 1, applied)
    }

    @Test
    fun `a blob with nothing usable stores nothing`() {
        assertEquals(0, ReportChannel.applyProperties(ctx, "# empty\n\n"))
        assertFalse(ReportChannel.isPairedOnDevice(ctx))
    }

    @Test
    fun `a partial blob is a valid outcome not an error`() {
        // Telegram without Azure is how most testers will be provisioned; it must pair.
        assertEquals(1, ReportChannel.applyProperties(ctx, "bugReport.botToken=t\nbugReport.chatId=c"))
        assertTrue(ReportChannel.isPairedOnDevice(ctx))
        assertFalse(ReportChannel.hasAzure(ctx))
    }

    @Test
    fun `a shared candidate preview names destinations without exposing secrets`() {
        val token = "123456:SECRET-TOKEN"
        val sas = "sv=2024&sp=cw&sig=SECRET-SIGNATURE"
        val candidate = ReportChannel.candidateForTesting(
            "/sdcard/Download/${ReportChannel.IMPORT_NAME}",
            "bugReport.botToken=$token\nazure.sas=$sas\n" +
                "azure.blobUrl=https://blob.example/container\n" +
                "relay.url=https://relay.example/api/report"
        )

        val summary = candidate.confirmationSummary()
        assertTrue(candidate.requiresSourceDeletion)
        assertTrue(summary.contains("Telegram credentials"))
        assertTrue(summary.contains("Azure credentials"))
        assertTrue(summary.contains("blob.example"))
        assertTrue(summary.contains("relay.example"))
        assertFalse(summary.contains(token))
        assertFalse(summary.contains("SECRET-SIGNATURE"))
    }

    @Test
    fun `an invalid Azure endpoint is disclosed but not treated as credentials`() {
        val candidate = ReportChannel.candidateForTesting(
            "/data/local/tmp/${ReportChannel.IMPORT_NAME}",
            "azure.blobUrl=http://blob.example/container\nazure.sas=sp=cw&sig=secret"
        )

        assertFalse(candidate.hasAzure)
        assertTrue(candidate.hasInvalidAzure)
        assertTrue(candidate.confirmationSummary().contains("invalid Azure URL"))
        assertFalse(candidate.confirmationSummary().contains("sig=secret"))
    }

    @Test
    fun `a shell-private candidate does not require source deletion`() {
        val candidate = ReportChannel.candidateForTesting(
            "/data/local/tmp/${ReportChannel.IMPORT_NAME}",
            "bugReport.botToken=t\nbugReport.chatId=c"
        )
        assertFalse(candidate.requiresSourceDeletion)
    }

    @Test
    fun `a malformed https relay is not stored or counted`() {
        assertEquals(0, ReportChannel.applyProperties(ctx, "relay.url=https://"))
        assertEquals("", ReportChannel.relayUrl(ctx))
    }
}
