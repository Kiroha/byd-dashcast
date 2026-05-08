package com.byd.dashcast;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTA update checker.
 *
 * On every fresh app launch, queries the GitHub releases API for the latest release.
 * If a newer version is found, downloads the APK and installs it via PackageInstaller.
 *
 * With platform.keystore (INSTALL_PACKAGES permission), the install is silent.
 * Without it, InstallResultReceiver handles the STATUS_PENDING_USER_ACTION fallback.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_LATEST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases/latest";
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases?per_page=10";
    private static final String APK_CACHE_NAME = "dashcast-update.apk";

    // ── Progress callback (all methods called on the main thread) ─────────────

    public interface ProgressListener {
        /** A newer version was found; download is about to start. */
        void onUpdateFound(String version);
        /** Download progress, 0-100. -1 = indeterminate (Content-Length unknown). */
        void onDownloadProgress(int percent);
        /** Download complete; PackageInstaller session started. */
        void onInstalling();
        /** No update available. */
        void onUpToDate();
        /** An error occurred. */
        void onError(String message);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call from MainActivity.onCreate() (fresh launch) or from the overflow menu.
     * @param listener optional UI callback; all methods dispatched on the main thread.
     */
    public static void checkAndInstall(final Context context, final ProgressListener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                doCheckAndInstall(context.getApplicationContext(), listener, ui);
            } catch (Exception e) {
                AppLogger.e(TAG, "OTA check failed", e);
                if (listener != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ui.post(() -> listener.onError(msg));
                }
            }
        }, "ota-update").start();
    }

    private static void doCheckAndInstall(Context context, ProgressListener listener,
                                          Handler ui) throws Exception {
        boolean includePrerelease = context
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_OTA_PRERELEASE,
                        SettingsActivity.DEFAULT_OTA_PRERELEASE);

        // 1. Fetch latest release info from GitHub API
        JSONObject release;
        if (includePrerelease) {
            String json = httpGet(RELEASES_LIST_API);
            JSONArray list = new JSONArray(json);
            if (list.length() == 0) {
                AppLogger.i(TAG, "No releases found");
                if (listener != null) ui.post(listener::onUpToDate);
                return;
            }
            release = list.getJSONObject(0);
        } else {
            String json = httpGet(RELEASES_LATEST_API);
            release = new JSONObject(json);
        }
        String tag = release.getString("tag_name");
        String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;

        if (!isNewer(latestVer, BuildConfig.VERSION_NAME)) {
            AppLogger.i(TAG, "Up to date (current=" + BuildConfig.VERSION_NAME
                    + " latest=" + latestVer + ")");
            if (listener != null) ui.post(listener::onUpToDate);
            return;
        }

        AppLogger.i(TAG, "Update available: " + BuildConfig.VERSION_NAME + " → " + latestVer);
        if (listener != null) ui.post(() -> listener.onUpdateFound(latestVer));

        // 2. Find APK asset URL
        JSONArray assets = release.getJSONArray("assets");
        String apkUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url");
                break;
            }
        }
        if (apkUrl == null) {
            AppLogger.e(TAG, "No APK asset found in release " + latestVer);
            if (listener != null) ui.post(() -> listener.onError("No APK asset in release " + latestVer));
            return;
        }

        // 3. Download APK to internal cache
        File apkFile = new File(context.getCacheDir(), APK_CACHE_NAME);
        downloadToFile(apkUrl, apkFile, listener, ui);
        AppLogger.i(TAG, "APK downloaded: " + apkFile.length() + " bytes → " + apkFile);

        // 4. Install via PackageInstaller
        if (listener != null) ui.post(listener::onInstalling);
        installApk(context, apkFile);
        // 5. Remove the cached APK — no point keeping ~25 MB after the installer has read it.
        //noinspection ResultOfMethodCallIgnored
        apkFile.delete();
    }

    // ── Version comparison ────────────────────────────────────────────────────

    static boolean isNewer(String latest, String current) {
        int[] l = parseVer(latest);
        int[] c = parseVer(current);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv != cv) return lv > cv;
        }
        return false;
    }

    private static int[] parseVer(String v) {
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return nums;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " for " + urlStr);
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadToFile(String urlStr, File dest,
                                       ProgressListener listener, Handler ui) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        try {
            int code = conn.getResponseCode();
            // Manual redirect handling for cross-scheme redirects (GitHub CDN)
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                conn = openConnection(location);
                code = conn.getResponseCode();
            }
            if (code != 200) throw new Exception("Download HTTP " + code);

            long total = conn.getContentLengthLong(); // -1 if unknown
            long downloaded = 0;
            int lastPercent = -2; // -2 so first call with -1 (indeterminate) always fires

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (listener != null) {
                        int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int p = percent;
                            ui.post(() -> listener.onDownloadProgress(p));
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "DashCast/" + BuildConfig.VERSION_NAME);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ── Install ───────────────────────────────────────────────────────────────

    private static void installApk(Context context, File apkFile) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (OutputStream out = session.openWrite("update", 0, apkFile.length());
                 FileInputStream in = new FileInputStream(apkFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent resultIntent = new Intent(context, InstallResultReceiver.class);
            // FLAG_IMMUTABLE must NOT be used here: PackageInstaller needs to inject
            // EXTRA_STATUS and EXTRA_STATUS_MESSAGE into the intent when delivering the result.
            // With FLAG_IMMUTABLE those extras are silently dropped → status=1/null.
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, sessionId, resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            AppLogger.i(TAG, "PackageInstaller session committed, id=" + sessionId);
        } catch (Exception e) {
            session.abandon();
            throw e;
        }
    }
}
