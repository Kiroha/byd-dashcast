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

    /**
     * Points the uploader at a local socket, WITHOUT going through [ReportChannel.applyProperties].
     *
     * applyProperties rejects anything that is not https, on purpose. A test that tried to
     * configure http://127.0.0.1 through it does not get a local relay — it gets a silently
     * unchanged store, [RelayUploader.url] falls back to DEFAULT_URL, and the test uploads to the
     * PRODUCTION relay, which answers 200 and forwards the junk into the triage topic. That is not
     * hypothetical: it happened while these two cases were being written. Write the key the
     * accessor actually reads, and assert the endpoint before sending anything.
     */
    private fun setLocalRelay(port: Int): String {
        val url = "http://127.0.0.1:$port/api/report"
        ctx.getSharedPreferences("test_relay", Context.MODE_PRIVATE)
            .edit().putString("relay_url", url).commit()
        assertEquals("the uploader must be aimed at the local socket, never at production",
            url, RelayUploader.url())
        return url
    }

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
    fun `an endpoint IS counted, because it decides where the report goes`() {
        // This test previously asserted the opposite, and it was pinning the defect rather than
        // the behaviour: "a file carrying only a relay URL has provisioned nothing that needs
        // protecting". The relay endpoint is where every diagnostic report goes, and the
        // provisioning file is read from Download, which any app can write to. Not counting it
        // also made the one visible signal lie — the screen said "no usable credentials in it"
        // immediately after storing it.
        assertEquals(1, setRelay("https://example.invalid/api/report"))
        // Still not "paired": pairing means a channel credential, and an endpoint is not one.
        assertFalse(ReportChannel.isPairedOnDevice(ctx))
    }

    @Test
    fun `a cleartext endpoint is refused`() {
        // No legitimate relay needs http, and the difference is whether a report full of logcat,
        // dumpsys and cluster screenshots can be read in transit.
        assertEquals(0, setRelay("http://example.invalid/api/report"))
        assertEquals("the built-in endpoint must be untouched", RelayUploader.DEFAULT_URL, RelayUploader.url())
    }

    @Test
    fun `a non-http scheme is refused too`() {
        assertEquals(0, setRelay("file:///data/local/tmp/x"))
        assertEquals(RelayUploader.DEFAULT_URL, RelayUploader.url())
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

    @Test
    fun `the caption is capped before it becomes a header`() {
        // The wizard's free-text field has no length limit. The relay and Telegram both truncate at
        // 1024 — but only after the header exists, and an oversized header is refused by the
        // platform before either of them is consulted. A tester who wrote a long description would
        // have lost the entire upload rather than the tail of their sentence.
        assertEquals(1024, RelayUploader.CAPTION_MAX_CHARS)
    }

    // ── the retry, and what it must not retry ────────────────────────────────────────────────

    @Test
    fun `the retry delay is long enough for a wake and short enough not to feel like a hang`() {
        // The relay runs on a consumption plan: Azure deallocates its worker after a few minutes
        // idle, and reports are rare by nature, so the first attempt of a real report is exactly
        // the one most likely to meet a waking function.
        assertEquals(2_000L, RelayUploader.RETRY_DELAY_MS)
    }

    /**
     * The ambiguous failure: the relay took the whole report and then went quiet.
     *
     * A retry cannot recover that — the relay does not deduplicate (relay/README.md), so if the
     * first copy landed, the second one lands too and the triage topic gets the same incident
     * twice. It also re-uploads the whole report from a car on a phone hotspot to do it.
     *
     * The server here accepts the connection, reads the body to the last byte, and closes without
     * answering. That is the real shape of the case — same as a read timeout, without waiting
     * READ_TIMEOUT_MS for it.
     */
    @Test
    fun `a relay that takes the body and never answers is not retried`() {
        ReportConsent.grant(ctx)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val done = java.util.concurrent.CountDownLatch(1)

        val t = Thread {
            try {
                while (true) {
                    val s = server.accept()
                    accepted.incrementAndGet()
                    // Read the request head, then exactly Content-Length body bytes, so the upload
                    // completes normally and the failure lands on the response read — not earlier.
                    val input = s.getInputStream()
                    var length = 0
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val c = input.read()
                        if (c < 0) break
                        head.append(c.toChar())
                    }
                    Regex("(?i)content-length:\\s*(\\d+)").find(head)?.let {
                        length = it.groupValues[1].toInt()
                    }
                    var read = 0
                    val buf = ByteArray(8192)
                    while (read < length) {
                        val n = input.read(buf, 0, minOf(buf.size, length - read))
                        if (n < 0) break
                        read += n
                    }
                    s.close()          // the whole body is in, and the relay says nothing
                    done.countDown()
                }
            } catch (_: Throwable) { /* server closed — that is how this thread ends */ }
        }
        t.isDaemon = true
        t.start()

        setLocalRelay(server.localPort)
        val f = java.io.File.createTempFile("relay", ".txt")
        f.writeText("x".repeat(200))
        try {
            val result = RelayUploader.sendResult(f, "caption", RelayUploader.TOPIC_BUG)
            assertTrue(result is RelayUploader.SendResult.AmbiguousFailure)
            val err = (result as RelayUploader.SendResult.AmbiguousFailure).message
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
            // The retry sleeps RETRY_DELAY_MS before reconnecting, so if it were going to fire it
            // would have by now — send() has already returned.
            assertEquals("the report must be posted exactly once", 1, accepted.get())
            assertTrue("the failure must be reported", err.isNotEmpty())
            assertTrue("and it must warn that the send may have worked: $err",
                err.contains("may already have been sent"))
            assertFalse("an ambiguous relay result must never be sent directly too",
                TelegramBugReporter.shouldFallbackToDirect(result, true))
        } finally {
            f.delete()
            server.close()
        }
    }

    /**
     * The other half of the same decision: a failure BEFORE the body is out reached nothing, so it
     * must still be retried. This is the cold-start case the retry was built for, and narrowing
     * the retry must not have taken it with it.
     */
    @Test
    fun `a connection refused before the body is out is still retried`() {
        ReportConsent.grant(ctx)
        // Bind then immediately close: the port is almost certainly free, so the connect is
        // refused outright and no body is ever written.
        val probe = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val deadPort = probe.localPort
        probe.close()

        setLocalRelay(deadPort)
        val f = java.io.File.createTempFile("relay", ".txt")
        f.writeText("x".repeat(200))
        try {
            val started = System.currentTimeMillis()
            val err = RelayUploader.send(f, "caption", RelayUploader.TOPIC_BUG)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("a failure must be reported", err != null && err.isNotEmpty())
            // Two attempts means the RETRY_DELAY_MS sleep happened between them. Timing is the
            // only observable here, and the margin is wide enough not to flake.
            assertTrue("the retry must still fire on a pre-body failure (took ${elapsed} ms)",
                elapsed >= RelayUploader.RETRY_DELAY_MS)
            assertFalse("and it is not the ambiguous case: $err",
                err!!.contains("may already have been sent"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `an unreachable endpoint fails without throwing`() {
        // The retry path must survive a host that does not resolve — that is the shape of the
        // failure it exists for, and throwing here would take the send thread down instead of
        // falling back to the share sheet.
        ReportConsent.grant(ctx)
        setRelay("https://relay.invalid.example/api/report")   // never resolves, never reached
        val f = java.io.File.createTempFile("relay", ".txt")
        f.writeText("x".repeat(200))
        try {
            val err = RelayUploader.send(f, "caption", RelayUploader.TOPIC_BUG)
            assertTrue("a failure must be reported, not thrown", err != null && err.isNotEmpty())
        } finally {
            f.delete()
        }
    }

    @Test
    fun `only safe relay failures permit configured direct fallback`() {
        val safe = RelayUploader.SendResult.SafeFailure("connection refused")
        val ambiguous = RelayUploader.SendResult.AmbiguousFailure("may already have been sent")

        assertTrue(TelegramBugReporter.shouldFallbackToDirect(safe, true))
        assertFalse(TelegramBugReporter.shouldFallbackToDirect(safe, false))
        assertFalse(TelegramBugReporter.shouldFallbackToDirect(ambiguous, true))
        assertFalse(TelegramBugReporter.shouldFallbackToDirect(RelayUploader.SendResult.Sent, true))
    }

    @Test
    fun `ambiguous relay result stays typed and never invokes direct transport`() {
        var directCalls = 0
        val result = TelegramBugReporter.resolveRelayResult(
            RelayUploader.SendResult.AmbiguousFailure("may already have been sent"),
            directConfigured = true,
        ) {
            directCalls++
            null
        }

        assertTrue(result is TelegramBugReporter.DeliveryResult.Ambiguous)
        assertEquals(0, directCalls)
    }
}
