package com.byd.dashcast.report;

import android.content.Context;

import com.byd.dashcast.BuildConfig;
import com.byd.dashcast.util.AppLogger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Uploads a bug-report file straight to the DashCast support channel via the
 * Telegram Bot API ({@code sendDocument}, multipart/form-data).
 *
 * <p>Credentials come from {@link BuildConfig} (injected from local.properties at
 * build time — never committed). When the token is absent the caller is expected
 * to fall back to the system share sheet; {@link #isConfigured()} reports that.
 */
public final class TelegramBugReporter {

    private static final String TAG = "TelegramBugReporter";

    public interface Callback {
        void onSent();
        void onFailed(String message);
    }

    private TelegramBugReporter() {}

    /** True when a bot token + chat id are baked in — i.e. direct upload is possible. */
    public static boolean isConfigured() {
        return !BuildConfig.BUG_REPORT_BOT_TOKEN.isEmpty()
            && !BuildConfig.BUG_REPORT_CHAT_ID.isEmpty();
    }

    /**
     * Uploads {@code file} with {@code caption} on a background thread.
     * {@code cb} fires on the main thread.
     */
    public static void send(Context context, File file, String caption, Callback cb) {
        send(context, file, caption, BuildConfig.BUG_REPORT_THREAD_ID, cb);
    }

    /**
     * Same as {@link #send(Context, File, String, Callback)} but routes to a specific
     * topic ({@code message_thread_id}) within the same chat — used e.g. by the HUD
     * self-test to post into its dedicated thread.
     */
    public static void send(Context context, File file, String caption, String threadOverride, Callback cb) {
        new Thread(() -> {
            String error = doSend(file, caption, threadOverride);
            post(() -> {
                if (error == null) cb.onSent();
                else cb.onFailed(error);
            });
        }, "tg-bugreport").start();
    }

    private static String doSend(File file, String caption, String thread) {
        final String boundary = "----dashcast" + System.currentTimeMillis();
        final String token  = BuildConfig.BUG_REPORT_BOT_TOKEN;
        final String chatId = BuildConfig.BUG_REPORT_CHAT_ID;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendDocument");
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(conn.getOutputStream()))) {
                writeField(out, boundary, "chat_id", chatId);
                if (!thread.isEmpty())
                    writeField(out, boundary, "message_thread_id", thread);
                if (caption != null && !caption.isEmpty()) {
                    // Telegram caption hard limit is 1024 chars.
                    String cap = caption.length() > 1024 ? caption.substring(0, 1024) : caption;
                    writeField(out, boundary, "caption", cap);
                }
                writeFileField(out, boundary, "document", file);
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                AppLogger.i(TAG, "bug report sent to Telegram ✓");
                return null;
            }
            String body = readErr(conn);
            AppLogger.w(TAG, "Telegram HTTP " + code + ": " + body);
            return "HTTP " + code;
        } catch (Exception e) {
            AppLogger.e(TAG, "send failed", e);
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void writeField(DataOutputStream out, String boundary,
                                   String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes("UTF-8"));
        out.writeBytes("\r\n");
    }

    private static void writeFileField(DataOutputStream out, String boundary,
                                       String name, File file) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.writeBytes("\r\n");
    }

    private static String readErr(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream()) {
            if (es == null) return "";
            byte[] buf = new byte[2048];
            int n = es.read(buf);
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
