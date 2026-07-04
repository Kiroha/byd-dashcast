package com.byd.dashcast.update;
import com.byd.dashcast.BuildConfig;

import android.app.PendingIntent;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.app.InstallResultReceiver;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.proxy.ShellGateway;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.infrastructure.AdbLocalClient;
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
 * Install strategy (see {@link #installApk}):
 *   1. Preferred: fully silent + auto-relaunch via the proxy daemon (uid 2000 =
 *      shell) — {@code pm install -r <apk> && am start <launcher>}. No user tap.
 *   2. Fallback: PackageInstaller. Silent if the app effectively holds
 *      INSTALL_PACKAGES (platform.keystore); otherwise InstallResultReceiver shows
 *      the system install dialog (STATUS_PENDING_USER_ACTION). Used when the daemon
 *      is unreachable (e.g. DL5.1 signing wall) so no car ever loses the update path.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final Handler sUi = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicBoolean sDownloading =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean sChecking =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final String RELEASES_LATEST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases/latest";
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases?per_page=10";
    private static final String APK_CACHE_NAME = "dashcast-update.apk";

    // ── Progress callback (all methods called on the main thread) ─────────────

    public interface ProgressListener {
        /** A newer version was found; download is about to start. */
        void onUpdateFound(String version, String changelog, String downloadUrl);
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
    public static void startDownload(final Context context, final String apkUrl, final ProgressListener listener) {
        if (!sDownloading.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startDownload: download already in progress — ignored");
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File apkFile = resolveApkFile(context);
                downloadToFile(apkUrl, apkFile, listener, ui);
                AppLogger.i(TAG, "APK downloaded: " + apkFile.length() + " bytes → " + apkFile);
                if (listener != null) ui.post(listener::onInstalling);
                installApk(context, apkFile, listener, ui);
                // Do NOT delete apkFile here — PackageInstaller / pm read it asynchronously.
                // The cached file will be overwritten on the next OTA download.
            } catch (Exception e) {
                AppLogger.e(TAG, "OTA download failed", e);
                if (listener != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ui.post(() -> listener.onError(msg));
                }
            } finally {
                sDownloading.set(false);
            }
        }, "ota-download").start();
    }

    public static void checkUpdate(final Context context, final ProgressListener listener) {
        if (!sChecking.compareAndSet(false, true)) {
            AppLogger.w(TAG, "checkUpdate: check already in progress — ignored");
            return;
        }
        new Thread(() -> {
            try {
                doCheckUpdate(context.getApplicationContext(), listener, sUi);
            } catch (Exception e) {
                AppLogger.e(TAG, "OTA check failed", e);
                if (listener != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    sUi.post(() -> listener.onError(msg));
                }
            } finally {
                sChecking.set(false);
            }
        }, "ota-update").start();
    }

    private static void doCheckUpdate(Context context, ProgressListener listener,
                                          Handler ui) throws Exception {
        boolean includePrerelease = context
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_OTA_PRERELEASE,
                        SettingsActivity.DEFAULT_OTA_PRERELEASE);

        // 1. Fetch latest release info from GitHub API.
        // Always use the list endpoint: /releases/latest only returns non-prerelease
        // releases, so it gives HTTP 404 when all releases are tagged as pre-release
        // (which is the case for this repo's beta channel). The list endpoint works
        // for both stable and pre-release, and lets us filter properly.
        JSONObject release = null;
        String json = httpGet(RELEASES_LIST_API);
        JSONArray list = new JSONArray(json);
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            // Skip non-app releases (e.g. "voice-libs-v1"): a valid app tag starts
            // with 'v' followed by a digit (v1.2.3) or directly with a digit (1.2.3).
            String t = r.getString("tag_name");
            String stripped = t.startsWith("v") ? t.substring(1) : t;
            if (stripped.isEmpty() || !Character.isDigit(stripped.charAt(0))) continue;
            // Honour the "include pre-releases" preference.
            if (!includePrerelease && r.optBoolean("prerelease", false)) continue;
            release = r;
            break;
        }
        if (release == null) {
            AppLogger.i(TAG, "No eligible release found (includePrerelease=" + includePrerelease + ")");
            if (listener != null) ui.post(listener::onUpToDate);
            return;
        }
        String tag = release.getString("tag_name");
        String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;

        if (!isNewer(latestVer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
            AppLogger.i(TAG, "Up to date (current=" + BuildConfig.VERSION_NAME
                    + "+build" + BuildConfig.VERSION_CODE + " latest=" + latestVer + ")");
            if (listener != null) ui.post(listener::onUpToDate);
            return;
        }

        String changelog = release.optString("body", "No changelog provided.");
        AppLogger.i(TAG, "Update available: " + BuildConfig.VERSION_NAME
                + "+build" + BuildConfig.VERSION_CODE + " → " + latestVer);

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

        final String finalApkUrl = apkUrl;
        if (listener != null) ui.post(() -> listener.onUpdateFound(latestVer, changelog, finalApkUrl));
    }

    // ── Version comparison ────────────────────────────────────────────────────

    /**
     * @param latest tag from GitHub (e.g. "1.1.9", "1.1.9-build170", "1.2.0-rc1")
     * @param currentName {@link BuildConfig#VERSION_NAME} (e.g. "1.1.9")
     * @param currentCode {@link BuildConfig#VERSION_CODE} (e.g. 170)
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compare base semantic versions (numeric parts only). If they differ,
     *       the higher one wins.</li>
     *   <li>If base versions are equal AND the latest tag carries a {@code -buildN}
     *       suffix, the build number is compared against {@code currentCode}.
     *       This supports the versioning policy where {@code versionName} is
     *       pinned and only {@code versionCode} increments between releases.</li>
     * </ol>
     */
    static boolean isNewer(String latest, String currentName, int currentCode) {
        int latestBuild = extractBuild(latest);     // -1 if no "-buildN" suffix
        String latestBase = stripSuffix(latest);    // "1.1.9-build170" → "1.1.9"
        // v1.2.38 fix: also strip the suffix from currentName, otherwise the
        // last numeric segment of e.g. "1.2.37-beta" becomes 0 (parseInt fails
        // on "37-beta") and the comparison against latest base "1.2.37" wrongly
        // reports the latest as newer than itself. Symptom v1.2.37-beta: the
        // OTA banner re-proposes installing v1.2.37-beta on top of v1.2.37-beta
        // in a loop. See log/byd_report_20260525_105105.log feedback.
        String currentBase = stripSuffix(currentName);
        int[] l = parseVer(latestBase);
        int[] c = parseVer(currentBase);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv != cv) return lv > cv;
        }
        // Base versions are equal — fall back to the build number when available.
        return latestBuild > 0 && latestBuild > currentCode;
    }

    /** Returns the integer N from a "-buildN" / "-bN" suffix, or {@code -1}. */
    private static int extractBuild(String tag) {
        int dash = tag.indexOf('-');
        if (dash < 0 || dash + 1 >= tag.length()) return -1;
        String suffix = tag.substring(dash + 1);
        if (suffix.startsWith("build")) suffix = suffix.substring(5);
        else if (suffix.startsWith("b") && suffix.length() > 1
                && Character.isDigit(suffix.charAt(1))) suffix = suffix.substring(1);
        try { return Integer.parseInt(suffix); } catch (NumberFormatException e) { return -1; }
    }

    /** Strips anything from the first '-' onwards. */
    private static String stripSuffix(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    private static int[] parseVer(String v) {
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
            try (InputStream in = conn.getInputStream()) {
                return readStream(in);
            }
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
            int redirectCount = 0;
            while ((code == 301 || code == 302 || code == 307 || code == 308) && redirectCount < 5) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new Exception("Redirect " + code + " with no Location header");
                // Reject HTTPS->HTTP downgrade. GitHub CDN always uses HTTPS; if a
                // redirect points elsewhere it is either misconfigured or hostile.
                if (!location.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                    throw new Exception("Insecure redirect target: " + location);
                }
                conn = openConnection(location);
                code = conn.getResponseCode();
                redirectCount++;
            }
            if (redirectCount >= 5) throw new Exception("Too many redirects (" + redirectCount + ")");
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

    /**
     * Where to download the APK. Prefers the external files dir so the proxy
     * daemon (uid 2000 = shell) can read it for a silent {@code pm install};
     * falls back to the app-private cache when external storage is unavailable
     * (e.g. the DL5.1/A13 getExternalFilesDir SecurityException). The
     * PackageInstaller path can read either location.
     */
    private static File resolveApkFile(Context context) {
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                //noinspection ResultOfMethodCallIgnored
                ext.mkdirs();
                return new File(ext, APK_CACHE_NAME);
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "getExternalFilesDir unavailable, using cache: " + t);
        }
        return new File(context.getCacheDir(), APK_CACHE_NAME);
    }

    /**
     * Installs the freshly-downloaded APK.
     *
     * <p>Preferred path — <b>fully silent + auto-relaunch via the proxy daemon</b>:
     * the daemon runs as uid 2000 (shell), so {@code pm install -r} needs no user
     * confirmation, exactly like {@code adb install}. The command chains an
     * {@code am start} so the app is relaunched after its running process is
     * replaced. Because the daemon executes the command in its <em>own</em>
     * process, it survives this app being killed mid-install and completes the
     * relaunch on its own.
     *
     * <p>Fallback path — {@link PackageInstaller}: used when the daemon is not
     * reachable (e.g. the DL5.1 signing wall leaves it down) or when {@code pm}
     * reports a failure. This is the previous behaviour, so no car ever loses the
     * ability to update — it just keeps the one manual "Install" tap there.
     */
    private static void installApk(Context context, File apkFile,
                                   ProgressListener listener, Handler ui) throws Exception {
        if (tryDaemonSilentInstall(context, apkFile, listener, ui)) {
            return; // dispatched to the daemon; on success the app is replaced + relaunched
        }
        installViaPackageInstaller(context, apkFile);
    }

    /**
     * Attempts the silent daemon install. Returns {@code true} if the command was
     * dispatched to the daemon (success replaces + relaunches the app; any failure
     * falls back to {@link PackageInstaller} from the callback), {@code false} if
     * the daemon path is unavailable and the caller must install via
     * {@link PackageInstaller} directly.
     */
    private static boolean tryDaemonSilentInstall(final Context context, final File apkFile,
                                                  final ProgressListener listener, final Handler ui) {
        if (!ProxyClient.isConnected()) {
            AppLogger.i(TAG, "daemon not connected — PackageInstaller path");
            return false;
        }
        // uid 2000 (shell) must be able to READ the APK. The app-private cache dir
        // is 0700 (unreadable by shell); only the external files dir is shell-
        // readable. If the download landed in the cache, skip the daemon path.
        String cachePrefix = context.getCacheDir().getAbsolutePath();
        if (apkFile.getAbsolutePath().startsWith(cachePrefix)) {
            AppLogger.i(TAG, "APK in app-private cache (shell can't read) — PackageInstaller path");
            return false;
        }
        final String cmd = "pm install -r '" + apkFile.getAbsolutePath() + "'"
                + " && sleep 1 && " + buildRelaunchCommand(context);
        AppLogger.i(TAG, "daemon silent install + relaunch: " + cmd);
        ShellGateway.execShellWithResult(context, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                // A successful reinstall kills THIS process before this callback can
                // run, so reaching here without a "Success" line means pm failed.
                // Fall back to the interactive installer in that case.
                if (out != null && out.contains("Success")) {
                    AppLogger.i(TAG, "daemon install reported Success");
                    return;
                }
                AppLogger.w(TAG, "daemon install did not report Success, falling back: " + out);
                fallbackInstall(context, apkFile, listener, ui);
            }
            @Override public void onError(String err) {
                AppLogger.w(TAG, "daemon install error, falling back: " + err);
                fallbackInstall(context, apkFile, listener, ui);
            }
        });
        return true;
    }

    /** Builds the shell command that relaunches this app after the install. */
    private static String buildRelaunchCommand(Context context) {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            if (launch != null && launch.getComponent() != null) {
                return "am start -n " + launch.getComponent().flattenToShortString();
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "launch-intent resolve failed, using monkey: " + t);
        }
        return "monkey -p " + context.getPackageName()
                + " -c android.intent.category.LAUNCHER 1";
    }

    /** Runs the PackageInstaller path from a background callback, reporting errors. */
    private static void fallbackInstall(Context context, File apkFile,
                                        ProgressListener listener, Handler ui) {
        try {
            installViaPackageInstaller(context, apkFile);
        } catch (Exception e) {
            AppLogger.e(TAG, "PackageInstaller fallback failed", e);
            if (listener != null && ui != null) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ui.post(() -> listener.onError(msg));
            }
        }
    }

    private static void installViaPackageInstaller(Context context, File apkFile) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
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
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, sessionId, resultIntent, flags);
            session.commit(pi.getIntentSender());
            AppLogger.i(TAG, "PackageInstaller session committed, id=" + sessionId);
        } catch (Exception e) {
            if (session != null) {
                try { session.abandon(); } catch (Throwable ignore) {}
            } else if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignore) {}
            }
            throw e;
        }
    }
}
