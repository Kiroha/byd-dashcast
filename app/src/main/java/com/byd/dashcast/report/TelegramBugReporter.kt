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
        ReportConsent.isGranted() && ReportChannel.hasTelegram()

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
            val error = doSend(file, caption, threadOverride)
            post {
                if (error == null) cb.onSent() else cb.onFailed(error)
            }
        }, "tg-bugreport").start()
    }

    private fun doSend(file: File, caption: String?, thread: String): String? {
        val boundary = "----dashcast" + System.currentTimeMillis()
        val token = ReportChannel.botToken()
        val chatId = ReportChannel.chatId()
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.telegram.org/bot$token/sendDocument")
            conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
                writeField(out, boundary, "chat_id", chatId)
                if (thread.isNotEmpty()) writeField(out, boundary, "message_thread_id", thread)
                if (!caption.isNullOrEmpty()) {
                    // Telegram caption hard limit is 1024 chars.
                    val cap = if (caption.length > 1024) caption.substring(0, 1024) else caption
                    writeField(out, boundary, "caption", cap)
                }
                writeFileField(out, boundary, "document", file)
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                AppLogger.i(TAG, "bug report sent to Telegram ✓")
                return null
            }
            val body = readErr(conn)
            AppLogger.w(TAG, "Telegram HTTP $code: $body")
            return "HTTP $code"
        } catch (e: Exception) {
            AppLogger.e(TAG, "send failed", e)
            return e.javaClass.simpleName + ": " + e.message
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
