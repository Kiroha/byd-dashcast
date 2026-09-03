package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The bot token, on both of the paths that can carry it out of the car.
 *
 * Every other redaction rule protects the tester. This one protects the project: the token is the
 * credential AUD-001 took out of the APK, and losing it lets a stranger post into — and read — the
 * diagnostics channel.
 *
 * The leak was never "the journal is unfiltered". The journal IS assembled into the report body
 * before the single `Redactor.redact` call. The gap was that the redactor had thirteen rules and
 * none of them recognised a bot token, while `TelegramBugReporter` builds a URL containing one and
 * logged raw exceptions that quote it.
 *
 * So there are two defences and both are pinned here: the scrub at the log site (the fix) and the
 * redaction rule (the backstop, for the call site nobody thought of).
 */
class BotTokenRedactionTest {

    // Shaped like a real token — 10 digits, colon, 35 URL-safe characters — but not one.
    private val token = "1234567890:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsawQ"

    // One character shorter. The first draft of the bare rule demanded exactly 35 and this fixture
    // — written by hand, one character short by accident — turned it red. That accident is worth
    // keeping: a credential rule that fails closed on a format change protects nothing, and the
    // failure would be silent. The bare bound is {32,45}, measured at zero false positives.
    private val shortToken = "1234567890:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"
    private val salt = "test-salt"

    private fun redact(s: String) = Redactor.redact(s, salt)

    // ── the backstop: Redactor ──────────────────────────────────────────────────────────────

    @Test
    fun `a token inside the request URL is removed`() {
        // The exact shape a java.net exception quotes back.
        val line = "E TelegramBugReporter: send failed: java.io.FileNotFoundException: " +
            "https://api.telegram.org/bot$token/sendDocument"
        val r = redact(line)

        assertFalse("the credential must be gone", r.text.contains(token))
        assertFalse("including its hash half", r.text.contains("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
        assertTrue("and the rule must own up to it", r.counts.containsKey("bot_token"))
        // The URL shape survives so a triager can still see WHICH call failed.
        assertTrue("the endpoint must stay readable: ${r.text}",
            r.text.contains("api.telegram.org/bot") && r.text.contains("/sendDocument"))
    }

    @Test
    fun `a bare token with no url around it is removed too`() {
        val r = redact("provisioning: bugReport.botToken=$token")
        assertFalse(r.text.contains(token))
        assertTrue(r.counts.containsKey("bot_token"))
    }

    @Test
    fun `a hash one character off the canonical length is still caught`() {
        // The rule must not depend on Telegram never changing the length by a character.
        val r = redact("provisioning: bugReport.botToken=$shortToken")
        assertFalse("a near-length token is still a credential", r.text.contains(shortToken))
        assertTrue(r.counts.containsKey("bot_token"))
    }

    /**
     * Measured on the 178-report corpus before the rule was written: zero matches for either form.
     * These are the shapes that live nearest to it and must survive — a colon-separated pair is
     * everywhere in a logcat.
     */
    @Test
    fun `ordinary log shapes are not eaten`() {
        val clean = listOf(
            "01-01 12:00:00.123  1234  5678 I ActivityManager: Start proc",
            "meminfo: TOTAL PSS: 123456  TOTAL RSS: 234567",
            "SurfaceFlinger: layer 12345678:9 composed",
            "battery: 100:0:0:0",
            "uid=10167:u0_a167 pid=23456",
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        clean.forEach {
            assertEquals("must survive untouched: $it", it, redact(it).text)
        }
    }

    @Test
    fun `the same token yields the same label twice in one pass`() {
        // A stable label lets a triager see it was one credential, not two, without revealing it.
        val r = redact("first https://api.telegram.org/bot$token/sendDocument " +
                       "then https://api.telegram.org/bot$token/sendMessage")
        assertEquals(2, r.counts["bot_token"])
        assertFalse(r.text.contains(token))
    }

    // ── the fix: scrub before it is ever written ────────────────────────────────────────────

    @Test
    fun `the log-site scrub removes the token from an exception message`() {
        val msg = "java.io.FileNotFoundException: https://api.telegram.org/bot$token/sendDocument"
        val out = TelegramBugReporter.scrubToken(msg, token)
        assertFalse(out.contains(token))
        assertTrue("the rest of the message must survive", out.contains("FileNotFoundException"))
        assertTrue(out.contains("<token>"))
    }

    @Test
    fun `the scrub is a no-op when there is no token to remove`() {
        // The common case on a relay-only car: no credential is stored at all, and an unconfigured
        // device must not have its error messages mangled.
        val msg = "java.net.UnknownHostException: api.telegram.org"
        assertEquals(msg, TelegramBugReporter.scrubToken(msg, ""))
    }

    /**
     * The scrub tests above prove the function works. They would ALL still pass if someone put
     * `AppLogger.e(TAG, "send failed", e)` back at the call site — the exact line this fix removed
     * — because the leak only appears when the network fails, which no unit test here provokes.
     *
     * So the call site is guarded at the source level, the same way RedactionCallSiteTest pins its
     * four emitters. Crude, and it is the only thing that fails if the fix is reverted.
     */
    @Test
    fun `the send failure is never logged as a raw exception`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/report").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val src = java.io.File(root, "app/src/main/java/com/byd/dashcast/report/TelegramBugReporter.kt")
            .readText()

        assertTrue("the URL that carries the token must still be built here",
            src.contains("api.telegram.org/bot"))
        assertFalse("a raw Throwable must never be logged next to a tokenised URL",
            Regex("""AppLogger\.[ewi]\([^)]*,\s*e\s*\)""").containsMatchIn(src))
        assertTrue("the scrub must be applied at the log site",
            src.contains("scrubToken(e.toString(), token)"))
    }

    @Test
    fun `the scrub survives a null message`() {
        // Throwable.message is nullable, and this runs inside the catch that must never throw.
        assertEquals("", TelegramBugReporter.scrubToken(null, token))
    }

    @Test
    fun `direct Telegram multipart length matches every serialized byte`() {
        val file = kotlin.io.path.createTempFile("telegram-body", ".zip").toFile()
        try {
            file.writeBytes(ByteArray(8193) { (it % 251).toByte() })
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                TelegramBugReporter.writeMultipart(
                    out,
                    "boundary-123",
                    "-10042",
                    "7",
                    "échec détaillé",
                    file,
                )
            }

            assertEquals(
                bytes.size().toLong(),
                TelegramBugReporter.multipartLength(
                    "boundary-123",
                    "-10042",
                    "7",
                    "échec détaillé",
                    file,
                ),
            )
        } finally {
            file.delete()
        }
    }
}
