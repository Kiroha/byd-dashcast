package com.byd.dashcast.update;

import com.byd.dashcast.util.AppLogger;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * v1.2.43 — Lightweight GitHub release poller for {@code pyamsoft/tetherfusenet}.
 *
 * <p>Hits {@code GET /repos/pyamsoft/tetherfusenet/releases/latest} (unauthenticated,
 * 60 req/h per IP — largely sufficient for a single device that polls on screen open).
 * Parses {@code tag_name} (e.g. {@code "release-67"}) and compares the trailing
 * integer to the locally installed {@code versionCode}. If the remote tag is
 * higher, fires the callback with the new release info.
 *
 * <p>No persistent caching by design : the network call is ~10&nbsp;KB and runs
 * once per {@code HotspotActivity.onResume()}, which is rare enough not to
 * warrant a refresh window.
 */
public final class TetherFiUpdateChecker {

    private static final String TAG = "TFUpdate";
    private static final java.util.concurrent.atomic.AtomicBoolean sChecking =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final String API_URL =
            "https://api.github.com/repos/pyamsoft/tetherfusenet/releases/latest";
    private static final String TF_PKG  = "com.pyamsoft.tetherfi";
    private static final Pattern TAG_RE = Pattern.compile("(?:release-)?(\\d+)");
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS    = 6_000;

    private TetherFiUpdateChecker() { /* static */ }

    public static final class Result {
        public final int    installedVersionCode;
        public final int    remoteVersionCode;
        public final String remoteTagName;
        public final String releasePageUrl;
        public Result(int installed, int remote, String tag, String url) {
            this.installedVersionCode = installed;
            this.remoteVersionCode    = remote;
            this.remoteTagName        = tag;
            this.releasePageUrl       = url;
        }
        public boolean isUpdateAvailable() { return remoteVersionCode > installedVersionCode; }
    }

    public interface Callback {
        /** Always invoked on the main thread. */
        void onResult(Result result);
        /** Always invoked on the main thread. */
        void onError(String message);
    }

    /**
     * Fire-and-forget check on a worker thread.
     * Callback delivered on the main thread.
     */
    public static void check(final Context context, final Callback callback) {
        final int installed = getInstalledVersionCode(context);
        if (installed < 0) {
            postError(callback, "TetherFi not installed");
            return;
        }
        if (!sChecking.compareAndSet(false, true)) {
            AppLogger.w(TAG, "check: already in progress — ignored");
            return;
        }
        new Thread(() -> {
            HttpsURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpsURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "DashCast-TetherFiUpdateCheck");
                int code = conn.getResponseCode();
                if (code != 200) {
                    postError(callback, "HTTP " + code);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                String tag  = json.optString("tag_name", "");
                String page = json.optString("html_url",
                        "https://github.com/pyamsoft/tetherfusenet/releases/latest");
                int remote  = parseTagAsVersionCode(tag);
                if (remote < 0) {
                    postError(callback, "Unparseable tag: " + tag);
                    return;
                }
                final Result r = new Result(installed, remote, tag, page);
                postResult(callback, r);
                AppLogger.i(TAG, "check ok: local=" + installed
                        + " remote=" + remote + " (" + tag + ")");
            } catch (Throwable t) {
                postError(callback, t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            } finally {
                sChecking.set(false);
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignore) { }
                }
            }
        }, "tetherfi-update-check").start();
    }

    @SuppressWarnings("deprecation")
    private static int getInstalledVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(TF_PKG, 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static int parseTagAsVersionCode(String tag) {
        if (tag == null) return -1;
        Matcher m = TAG_RE.matcher(tag.trim());
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static void postResult(Callback cb, Result r) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r));
    }

    private static void postError(Callback cb, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onError(msg));
    }
}
