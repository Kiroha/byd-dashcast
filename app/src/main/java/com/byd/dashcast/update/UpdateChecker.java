package com.byd.dashcast.update;
import com.byd.dashcast.BuildConfig;

import android.app.PendingIntent;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.app.InstallResultReceiver;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.proxy.ProxyClient;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
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
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * OTA update checker.
 *
 * On every fresh app launch, queries the GitHub releases API for the latest release.
 * If a newer version is found, downloads the APK and prefers a uid-2000 daemon install.
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
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases?per_page=100&page=";
    private static final String APK_CACHE_NAME = "dashcast-update.apk";
    private static final long MAX_APK_BYTES = 100L * 1024L * 1024L;

    public static final class ReleaseAsset {
        public final String version;
        public final String changelog;
        public final String downloadUrl;
        public final String name;
        public final long size;
        public final String sha256;

        ReleaseAsset(String version, String changelog, String downloadUrl,
                     String name, long size, String sha256) {
            this.version = version;
            this.changelog = changelog;
            this.downloadUrl = downloadUrl;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    // ── Progress callback (all methods called on the main thread) ─────────────

    public interface ProgressListener {
        /** A newer version was found; download is about to start. */
        void onUpdateFound(ReleaseAsset asset);
        /** Download progress, 0-100. -1 = indeterminate (Content-Length unknown). */
        void onDownloadProgress(int percent);
        /** Download complete; installation is starting. */
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
    public static void startDownload(final Context context, final ReleaseAsset asset,
                                     final ProgressListener listener) {
        if (!sDownloading.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startDownload: download already in progress — ignored");
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File apkFile = resolveApkFile(context);
                downloadToFile(asset, apkFile, listener, ui);
                validateDownloadedApk(context, apkFile, asset);
                AppLogger.i(TAG, "APK downloaded: " + apkFile.length() + " bytes → " + apkFile);
                if (listener != null) ui.post(listener::onInstalling);
                installApk(context, apkFile);
                // Do NOT delete apkFile here — PackageInstaller / pm read it asynchronously.
                // The cached file will be overwritten on the next OTA download.
            } catch (Exception e) {
                OtaRelaunchCoordinator.clearPending(context);
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

        // 1. Fetch release info from GitHub API. Stable-only users use GitHub's dedicated
        // endpoint, which cannot be pushed off page 1 by frequent betas. Beta users scan every
        // page and choose the semantic maximum instead of trusting release creation order.
        JSONArray list = includePrerelease
                ? fetchAllReleases()
                : new JSONArray().put(new JSONObject(httpGet(RELEASES_LATEST_API)));
        JSONObject release = null;
        String selectedVersion = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            String t = r.getString("tag_name");
            String stripped = t.startsWith("v") ? t.substring(1) : t;
            if (!OtaVersionPolicy.isValidReleaseVersion(stripped)) continue;
            if (!includePrerelease && r.optBoolean("prerelease", false)) continue;
            if (selectedVersion == null
                    || OtaVersionPolicy.compareVersions(stripped, selectedVersion) > 0) {
                release = r;
                selectedVersion = stripped;
            }
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
        String expectedName = "DashCast-v" + latestVer + "-release.apk";
        ReleaseAsset selectedAsset = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (expectedName.equals(name)) {
                long size = asset.optLong("size", -1L);
                if (size <= 0L || size > MAX_APK_BYTES) {
                    throw new Exception("Invalid APK asset size: " + size);
                }
                String digest = asset.optString("digest", "");
                String sha256 = digest.startsWith("sha256:") ? digest.substring(7) : "";
                selectedAsset = new ReleaseAsset(
                        latestVer,
                        changelog,
                        asset.getString("browser_download_url"),
                        name,
                        size,
                        sha256);
            }
        }
        if (selectedAsset == null) {
            AppLogger.e(TAG, "No exact release APK found in release " + latestVer);
            if (listener != null) ui.post(() -> listener.onError(
                    "No verified release APK in release " + latestVer));
            return;
        }

        final ReleaseAsset finalAsset = selectedAsset;
        if (listener != null) ui.post(() -> listener.onUpdateFound(finalAsset));
    }

    private static JSONArray fetchAllReleases() throws Exception {
        JSONArray all = new JSONArray();
        for (int page = 1; ; page++) {
            JSONArray batch = new JSONArray(httpGet(RELEASES_LIST_API + page));
            for (int i = 0; i < batch.length(); i++) all.put(batch.getJSONObject(i));
            if (batch.length() < 100) return all;
        }
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
        return OtaVersionPolicy.isNewer(latest, currentName, currentCode);
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

    private static void downloadToFile(ReleaseAsset asset, File dest,
                                       ProgressListener listener, Handler ui) throws Exception {
        String urlStr = asset.downloadUrl;
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
            if (total > 0 && total != asset.size) {
                throw new Exception("APK Content-Length mismatch: expected="
                        + asset.size + " actual=" + total);
            }
            long downloaded = 0;
            int lastPercent = -2; // -2 so first call with -1 (indeterminate) always fires

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (downloaded + n > asset.size || downloaded + n > MAX_APK_BYTES) {
                        throw new Exception("APK download exceeds declared size " + asset.size);
                    }
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
            if (downloaded != asset.size) {
                throw new Exception("APK download size mismatch: expected="
                        + asset.size + " actual=" + downloaded);
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
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private static void validateDownloadedApk(Context context, File apkFile,
                                              ReleaseAsset asset) throws Exception {
        if (!asset.sha256.isEmpty()) {
            String actual = sha256(apkFile);
            if (!asset.sha256.equalsIgnoreCase(actual)) {
                throw new Exception("APK SHA-256 mismatch");
            }
        }
        PackageManager pm = context.getPackageManager();
        PackageInfo downloaded = pm.getPackageArchiveInfo(
                apkFile.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed = pm.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (downloaded == null || downloaded.applicationInfo == null) {
            throw new Exception("Downloaded file is not a readable APK");
        }
        if (!context.getPackageName().equals(downloaded.packageName)) {
            throw new Exception("APK package mismatch");
        }
        if (!asset.version.equals(downloaded.versionName)) {
            throw new Exception("APK versionName mismatch");
        }
        if (downloaded.getLongVersionCode() <= BuildConfig.VERSION_CODE) {
            throw new Exception("APK versionCode is not newer");
        }
        if ((downloaded.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw new Exception("Debuggable APK refused");
        }
        Signature[] downloadedSigners = downloaded.signingInfo == null
                ? null : downloaded.signingInfo.getApkContentsSigners();
        Signature[] installedSigners = installed.signingInfo == null
                ? null : installed.signingInfo.getApkContentsSigners();
        if (!sameSignerSet(downloadedSigners, installedSigners)) {
            throw new Exception("APK signer mismatch");
        }
    }

    static boolean sameSignerSet(Signature[] left, Signature[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return false;
        }
        byte[][] leftBytes = new byte[left.length][];
        byte[][] rightBytes = new byte[right.length][];
        for (int i = 0; i < left.length; i++) leftBytes[i] = left[i].toByteArray();
        for (int i = 0; i < right.length; i++) rightBytes[i] = right[i].toByteArray();
        return sameByteSet(leftBytes, rightBytes);
    }

    static boolean sameByteSet(byte[][] leftBytes, byte[][] rightBytes) {
        if (leftBytes == null || rightBytes == null
                || leftBytes.length == 0 || leftBytes.length != rightBytes.length) {
            return false;
        }
        java.util.Comparator<byte[]> comparator = (a, b) -> {
            int length = Math.min(a.length, b.length);
            for (int i = 0; i < length; i++) {
                int cmp = Integer.compare(a[i] & 0xff, b[i] & 0xff);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(a.length, b.length);
        };
        Arrays.sort(leftBytes, comparator);
        Arrays.sort(rightBytes, comparator);
        return Arrays.deepEquals(leftBytes, rightBytes);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
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
    * confirmation, exactly like {@code adb install}. The command stages the APK
    * in {@code /data/local/tmp} and chains a launcher start after replacement.
    * Because the daemon executes the command in its <em>own</em> process, it
    * survives this app being killed mid-install and completes the relaunch.
     *
     * <p>Fallback path — {@link PackageInstaller}: used when the daemon is not
     * reachable (e.g. the DL5.1 signing wall leaves it down) or when {@code pm}
    * reports a failure. This preserves the system confirmation fallback where
    * required; the result/replacement receivers still relaunch DashCast afterward.
     */
    private static void installApk(Context context, File apkFile) throws Exception {
        OtaRelaunchCoordinator.markPending(context);
        try {
            if (tryDaemonSilentInstall(context, apkFile)) {
                return;
            }
            installViaPackageInstaller(context, apkFile);
        } catch (Exception e) {
            OtaRelaunchCoordinator.clearPending(context);
            throw e;
        }
    }

    /**
     * Attempts the silent daemon install from the existing OTA worker. A missing
     * Binder is reconnected here instead of immediately forcing the interactive
    * PackageInstaller path. The shell command stages the APK in /data/local/tmp;
    * it tries direct shell read first and run-as for debuggable builds, then lets
    * PackageInstaller handle devices where neither source path is shell-readable.
     */
    private static boolean tryDaemonSilentInstall(final Context context, final File apkFile) {
        Context app = context.getApplicationContext();
        try {
            if (!ProxyClient.isConnected()) {
                AppLogger.i(TAG, "daemon not connected — attempting OTA reconnect");
                if (!ProxyClient.connect(app)) {
                    AppLogger.i(TAG, "daemon OTA reconnect failed — PackageInstaller path");
                    return false;
                }
            }
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(context.getPackageName());
            String component = launch != null && launch.getComponent() != null
                    ? launch.getComponent().flattenToShortString() : null;
            String cmd = OtaInstallCommand.build(
                    apkFile.getAbsolutePath(), apkFile.length(),
                    context.getPackageName(), component);
            AppLogger.i(TAG, "daemon silent install: staging " + apkFile.length() + " bytes");
            String out = ProxyClient.runShell(cmd);
            if (out != null && out.contains("Success")) {
                AppLogger.i(TAG, "daemon install reported Success");
                return true;
            }
            AppLogger.w(TAG, "daemon install did not report Success, falling back: " + out);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            AppLogger.w(TAG, "daemon install unavailable, falling back: " + t);
        }
        return false;
    }

    private static void installViaPackageInstaller(Context context, File apkFile) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apkFile.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        boolean installPackagesGranted = context.checkSelfPermission(
                Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED;
        AppLogger.i(TAG, "PackageInstaller fallback: INSTALL_PACKAGES="
                + installPackagesGranted + " requestNoUserAction="
                + (Build.VERSION.SDK_INT >= 31));

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
