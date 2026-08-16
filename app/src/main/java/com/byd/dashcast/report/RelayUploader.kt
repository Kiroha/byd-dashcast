package com.byd.dashcast.report

import com.byd.dashcast.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a report to the DashCast relay, which forwards it to Telegram.
 *
 * ## What this is for
 *
 * AUD-001 took the bot token out of the APK, and that left a hole nothing could fill from inside
 * the car: a device that agreed to send reports still needed a credential, and every way of
 * getting one onto it was either a procedure for the user or a secret in the binary again. The
 * relay removes the question. The token lives in the function's application settings; the car
 * knows a URL.
 *
 * The consequence worth stating plainly: a decompiled APK now yields an ordinary HTTPS endpoint
 * and nothing else. It grants no read of anyone's report and no impersonation of the bot beyond
 * posting into the group the bot already posts into. Rotating the token is an app-setting change
 * with no release and no car to reach — which is the property AUD-001 was really about.
 *
 * ## Why the URL is a plain constant
 *
 * Because it is not a secret, and treating it as one would put provisioning back in front of the
 * user for no gain. What it exposes is the ability to send something to the relay, and the relay
 * validates what it accepts. The residual risk is spam, answered operationally: a daily quota on
 * the Function App, and a redeploy under another route if it is ever abused.
 *
 * ## Additive by construction
 *
 * [DEFAULT_URL] is empty until the relay is deployed and its address is pasted in. Until then
 * [isConfigured] is false, [TelegramBugReporter] takes its existing direct path, and nothing about
 * bug reports, HUD reports or the extraction bundle changes. The device override exists so the
 * relay can be pointed at a test deployment without a build.
 */
object RelayUploader {

    private const val TAG = "RelayUploader"

    /**
     * The deployed relay endpoint — see `relay/README.md` for what stands behind it.
     *
     * Deliberately a plain constant and deliberately not a credential. It grants the ability to
     * send something to the relay and nothing else: no read of anyone's report, no impersonation
     * of the bot beyond posting into the group it already posts into. The release secret-scan
     * workflow looks for token shapes, and a bare HTTPS URL is not one.
     */
    const val DEFAULT_URL = "https://func-dc-relay-bf8097.azurewebsites.net/api/report"

    /** Topic names the relay understands. The thread ids themselves live server-side now. */
    const val TOPIC_BUG = "bug"
    const val TOPIC_HUD = "hud"

    // Chosen against what the wizard does while this runs, not against what a patient network
    // deserves. doSubmitReport disables Send, Back AND Cancel for the whole attempt, so every
    // second here is a second a tester sits in front of a frozen screen with no way out. The relay
    // is tried first and a device that also holds a bot token then pays the direct path on top, so
    // the old 30 s + 180 s made a single send able to hold that screen for over four minutes — long
    // enough to read as a crash and be answered with a power cycle.
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** The relay's own ceiling, mirrored so a doomed upload is refused before it is attempted. */
    const val MAX_BYTES = 45L * 1024 * 1024

    /** Telegram's caption limit, applied here because the caption travels as an HTTP header. */
    const val CAPTION_MAX_CHARS = 1024

    /** A device value wins, so a test deployment needs no build. */
    @JvmStatic
    fun url(): String {
        val onDevice = try { ReportChannel.relayUrl() } catch (_: Throwable) { "" }
        return if (onDevice.isNotEmpty()) onDevice else DEFAULT_URL
    }

    /**
     * True when this device may upload through the relay.
     *
     * Consent first, for the same reason as everywhere else: a device that has refused is not
     * "pending configuration", it has said no.
     */
    @JvmStatic
    fun isConfigured(): Boolean = ReportConsent.isGranted() && url().isNotEmpty()

    /**
     * Uploads [file]. BLOCKING — call it from a background thread.
     *
     * @return null on success, or a human-readable failure the caller can show and fall back on.
     */
    @JvmStatic
    fun send(file: File, caption: String?, topic: String): String? {
        val endpoint = url()
        if (endpoint.isEmpty()) return "relay not configured"
        if (!file.isFile) return "report file missing"
        if (file.length() > MAX_BYTES) return "report too large for the relay (${file.length()} bytes)"

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-DashCast-Topic", topic)
                setRequestProperty("X-DashCast-Filename", safeName(file.name))
                // Base64 because a caption contains newlines, and a header cannot.
                if (!caption.isNullOrEmpty()) {
                    // Capped BEFORE it becomes a header. The wizard's free-text field has no length
                    // limit, the relay and Telegram both truncate at 1024 — but only after the
                    // header has been built, and a header this large is refused by the platform
                    // long before either of them gets a say. So a tester who wrote a long
                    // description would have lost the whole upload, not the tail of their text.
                    val capped = if (caption.length > CAPTION_MAX_CHARS)
                        caption.take(CAPTION_MAX_CHARS - 3) + "..." else caption
                    setRequestProperty("X-DashCast-Caption",
                        android.util.Base64.encodeToString(
                            capped.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                }
                // Streamed rather than buffered: a report can be tens of megabytes and the car has
                // less headroom than a phone.
                setFixedLengthStreamingMode(file.length())
            }
            FileInputStream(file).use { input ->
                conn.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                AppLogger.i(TAG, "relayed ${file.name} (${file.length()} bytes) to $topic")
                null
            } else {
                // The relay answers with a short reason; it never echoes anything sensitive.
                val detail = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                } catch (_: Throwable) { "" }
                "relay refused: HTTP $code $detail".trim()
            }
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    /**
     * The relay rejects anything with a path separator or an unexpected character, so a name that
     * would be refused there is repaired here rather than costing a round trip.
     */
    @JvmStatic
    fun safeName(name: String): String {
        val cleaned = name
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            // A dot is a legitimate character in a report name, so the filter above keeps it — and
            // that lets `..` through intact. Nothing downstream joins this onto a path, so it is
            // inert today; collapsing it costs one regex and removes the need to keep knowing that.
            .replace(Regex("""\.{2,}"""), ".")
            .take(120)
        return if (cleaned.isEmpty() || !cleaned[0].isLetterOrDigit()) "report_$cleaned".take(120)
        else cleaned
    }
}
