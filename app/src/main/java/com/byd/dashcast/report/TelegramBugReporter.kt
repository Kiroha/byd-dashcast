package com.byd.dashcast.report

import android.content.Context
import android.os.Handler
import android.os.Looper

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.util.AppLogger

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a bug-report file straight to the DashCast support channel via the
 * Telegram Bot API (`sendDocument`, multipart/form-data).
 *
 * Credentials come from [BuildConfig] (injected from local.properties at build
 * time — never committed). When the token is absent the caller is expected to
 * fall back to the system share sheet; [isConfigured] reports that.
 */
object TelegramBugReporter {

    private const val TAG = "TelegramBugReporter"

    interface Callback {
        fun onSent()
        fun onFailed(message: String)
        fun onAmbiguous(message: String)
    }

    internal sealed class DeliveryResult {
        data object Sent : DeliveryResult()
        data class Failed(val message: String) : DeliveryResult()
        data class Ambiguous(val message: String) : DeliveryResult()
    }

    /**
     * True when this device may upload: the driver has agreed, and a channel is configured.
     *
     * The consent term is first on purpose. It is the cheap check, and it is the one whose answer
     * must not depend on whether a credential happens to be present — a device that has refused is
     * not "unconfigured pending provisioning", it has said no.
     *
     * Every caller of this already handles false by keeping the report on the device or offering a
     * share sheet, which is exactly the right behaviour for a refusal. See [ReportConsent] for why
     * the gate lives here rather than in the seven screens that can start an upload.
     */
    @JvmStatic
    fun isConfigured(): Boolean =
        ReportConsent.isGranted() && (RelayUploader.isConfigured() || ReportChannel.hasTelegram())

    /**
     * Uploads [file] with [caption] on a background thread. [cb] fires on the main thread.
     */
    @JvmStatic
    fun send(context: Context, file: File, caption: String?, cb: Callback) {
        send(context, file, caption, ReportChannel.threadId(), cb)
    }

    /**
     * Same as the 4-arg [send] but routes to a specific topic (`message_thread_id`)
     * within the same chat — used e.g. by the HUD self-test to post into its
     * dedicated thread.
     */
    @JvmStatic
    fun send(context: Context, file: File, caption: String?, threadOverride: String, cb: Callback) {
        Thread({
            val result = doSend(file, caption, threadOverride)
            post {
                when (result) {
                    DeliveryResult.Sent -> cb.onSent()
                    is DeliveryResult.Failed -> cb.onFailed(result.message)
                    is DeliveryResult.Ambiguous -> cb.onAmbiguous(result.message)
                }
            }
        }, "tg-bugreport").start()
    }

    /**
     * Sends through the relay when one is deployed, and through the bot directly otherwise.
     *
     * The choice lives here rather than at the eleven upload call sites, for the same reason the
     * consent gate does: those sites already know how to be told "it did not go out", and none of
     * them should have to learn what a relay is.
     *
     * Order matters and the fallback is deliberate. A relay that is unreachable — the car is on a
     * dead hotspot, the function is cold, the quota tripped — must not turn into a lost report on
     * a device that still holds a working bot token from an earlier provisioning. So a relay
     * failure falls through to the direct path, and only both failing is a failure.
     */
    private fun doSend(file: File, caption: String?, thread: String): DeliveryResult {
        if (RelayUploader.isConfigured()) {
            val topic = if (isHudThread(thread)) RelayUploader.TOPIC_HUD else RelayUploader.TOPIC_BUG
            val relayResult = RelayUploader.sendResult(file, caption, topic)
            return resolveRelayResult(relayResult, ReportChannel.hasTelegram()) {
                doSendDirect(file, caption, thread)
            }
        }
        return directResult(doSendDirect(file, caption, thread))
    }

    @JvmStatic
    internal fun resolveRelayResult(
        relayResult: RelayUploader.SendResult,
        directConfigured: Boolean,
        directSend: () -> String?,
    ): DeliveryResult = when (relayResult) {
        RelayUploader.SendResult.Sent -> DeliveryResult.Sent
        is RelayUploader.SendResult.AmbiguousFailure ->
            DeliveryResult.Ambiguous(relayResult.message)
        is RelayUploader.SendResult.SafeFailure -> {
            if (!directConfigured) DeliveryResult.Failed(relayResult.message)
            else {
                AppLogger.w(TAG, "relay failed safely (${relayResult.message}) — " +
                    "falling back to the direct bot path")
                directResult(directSend())
            }
        }
    }

    private fun directResult(error: String?): DeliveryResult =
        if (error == null) DeliveryResult.Sent else DeliveryResult.Failed(error)

    @JvmStatic
    internal fun shouldFallbackToDirect(
        relayResult: RelayUploader.SendResult,
        directConfigured: Boolean,
    ): Boolean = directConfigured && relayResult is RelayUploader.SendResult.SafeFailure

    /**
     * True when this send is aimed at the HUD topic rather than the bug topic.
     *
     * Two forms, because both exist at once during the migration: the symbolic name the relay
     * understands, and the numeric `message_thread_id` a directly-provisioned device still holds.
     */
    private fun isHudThread(thread: String): Boolean {
        if (thread.equals(RelayUploader.TOPIC_HUD, ignoreCase = true)) return true
        val hud = ReportChannel.hudThreadId()
        return hud.isNotEmpty() && thread == hud
    }

    /**
     * Removes [token] from any text that may be quoting the request URL.
     *
     * Package-private so the test can drive it: this is the only thing standing between an
     * exception message and a live credential in every subsequent bug report, and a refactor that
     * dropped it would be silent — the leak only appears once something on the network fails.
     *
     * Exact-string removal rather than a pattern. We hold the token here, so there is nothing to
     * infer, and an exact match cannot miss a shape we did not anticipate.
     */
    @JvmStatic
    internal fun scrubToken(text: String?, token: String): String {
        val t = text ?: return ""
        return if (token.isEmpty()) t else t.replace(token, "<token>")
    }

    private fun doSendDirect(file: File, caption: String?, thread: String): String? {
        val boundary = "----dashcast" + System.currentTimeMillis()
        val token = ReportChannel.botToken()
        val chatId = ReportChannel.chatId()
        val numericThread = thread.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val cappedCaption = caption?.let {
            if (it.length > 1024) it.substring(0, 1024) else it
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.telegram.org/bot$token/sendDocument")
            conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(
                multipartLength(boundary, chatId, numericThread, cappedCaption, file)
            )

            DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
                writeMultipart(out, boundary, chatId, numericThread, cappedCaption, file)
                out.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                AppLogger.i(TAG, "bug report sent to Telegram ✓")
                return null
            }
            val body = readErr(conn)
            AppLogger.w(TAG, "Telegram HTTP $code: " + scrubToken(body, token))
            return "HTTP $code"
        } catch (e: Exception) {
            // NEVER log this exception raw. The request URL above carries the bot token, and
            // several java.net / java.io exceptions quote the request URL in their own message
            // (FileNotFoundException and the SSL failures are the usual ones). AppLogger's content
            // is copied verbatim into the DASHCAST JOURNAL section of every report sent afterwards,
            // so one raw exception here would put a live credential — the very one AUD-001 took out
            // of the APK — into the next report a tester sends, and into every one after that.
            //
            // Scrubbed by exact string, not by pattern: we hold the token, so there is nothing to
            // guess. Redactor's bot_token rule is the backstop for the call site nobody thought of;
            // this is the fix.
            AppLogger.e(TAG, "send failed: " + scrubToken(e.toString(), token))
            return e.javaClass.simpleName + ": " + scrubToken(e.message, token)
        } finally {
            conn?.disconnect()
        }
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    internal fun writeMultipart(
        out: DataOutputStream,
        boundary: String,
        chatId: String,
        numericThread: String?,
        caption: String?,
        file: File,
    ) {
        writeField(out, boundary, "chat_id", chatId)
        if (numericThread != null) writeField(out, boundary, "message_thread_id", numericThread)
        if (!caption.isNullOrEmpty()) writeField(out, boundary, "caption", caption)
        writeFileField(out, boundary, "document", file)
        out.writeBytes("--$boundary--\r\n")
    }

    internal fun multipartLength(
        boundary: String,
        chatId: String,
        numericThread: String?,
        caption: String?,
        file: File,
    ): Long {
        fun fieldLength(name: String, value: String): Long =
            ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"$name\"\r\n\r\n").length.toLong() +
                value.toByteArray(Charsets.UTF_8).size + 2L
        val fileHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        var total = fieldLength("chat_id", chatId)
        if (numericThread != null) total += fieldLength("message_thread_id", numericThread)
        if (!caption.isNullOrEmpty()) total += fieldLength("caption", caption)
        total += fileHeader.length.toLong() + file.length() + 2L
        total += "--$boundary--\r\n".length
        return total
    }

    private fun writeFileField(out: DataOutputStream, boundary: String, name: String, file: File) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"" + file.name + "\"\r\n")
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
        }
        out.writeBytes("\r\n")
    }

    private fun readErr(conn: HttpURLConnection): String {
        return try {
            val es = conn.errorStream ?: return ""
            es.use {
                val buf = ByteArray(2048)
                val n = it.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun post(r: Runnable) {
        Handler(Looper.getMainLooper()).post(r)
    }
}
