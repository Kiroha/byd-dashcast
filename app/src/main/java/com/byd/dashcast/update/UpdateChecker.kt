package com.byd.dashcast.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.app.InstallResultReceiver
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger

import org.json.JSONArray
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTA update checker.
 *
 * On every fresh app launch, queries the GitHub releases API for the latest release.
 * If a newer version is found, downloads the APK and prefers a uid-2000 daemon install.
 *
 * Install strategy (see [installApk]):
 *   1. Preferred: fully silent + auto-relaunch via the proxy daemon (uid 2000 =
 *      shell) — `pm install -r <apk> && am start <launcher>`. No user tap.
 *   2. Fallback: PackageInstaller. Silent if the app effectively holds
 *      INSTALL_PACKAGES (platform.keystore); otherwise InstallResultReceiver shows
 *      the system install dialog (STATUS_PENDING_USER_ACTION). Used when the daemon
 *      is unreachable (e.g. DL5.1 signing wall) so no car ever loses the update path.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private val sUi = Handler(Looper.getMainLooper())
    private val sDownloading = AtomicBoolean(false)
    private val sChecking = AtomicBoolean(false)
    private const val RELEASES_LATEST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases/latest"
    private const val RELEASES_LIST_API =
            "https://api.github.com/repos/Kiroha/byd-dashcast/releases?per_page=100&page="
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L

    class ReleaseAsset internal constructor(
        @JvmField val version: String,
        @JvmField val changelog: String,
        @JvmField val downloadUrl: String,
        @JvmField val name: String,
        @JvmField val size: Long,
        @JvmField val sha256: String
    )

    // ── Progress callback (all methods called on the main thread) ─────────────

    interface ProgressListener {
        /** A newer version was found; download is about to start. */
        fun onUpdateFound(asset: ReleaseAsset)
        /** Download progress, 0-100. -1 = indeterminate (Content-Length unknown). */
        fun onDownloadProgress(percent: Int)
        /** Download complete; installation is starting. */
        fun onInstalling()
        /** No update available. */
        fun onUpToDate()
        /** An error occurred. */
        fun onError(message: String)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call from MainActivity.onCreate() (fresh launch) or from the overflow menu.
     * @param listener optional UI callback; all methods dispatched on the main thread.
     */
    @JvmStatic
    fun startDownload(context: Context, asset: ReleaseAsset, listener: ProgressListener?) {
        if (!sDownloading.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startDownload: download already in progress — ignored")
            return
        }
        val ui = Handler(Looper.getMainLooper())
        Thread({
            try {
                val apkFile = resolveApkFile(context)
                downloadToFile(asset, apkFile, listener, ui)
                validateDownloadedApk(context, apkFile, asset)
                AppLogger.i(TAG, "APK downloaded: " + apkFile.length() + " bytes → " + apkFile)
                if (listener != null) ui.post { listener.onInstalling() }
                installApk(context, apkFile)
                // Do NOT delete apkFile here — PackageInstaller / pm read it asynchronously.
                // The cached file will be overwritten on the next OTA download.
            } catch (e: Exception) {
                OtaRelaunchCoordinator.clearPending(context)
                AppLogger.e(TAG, "OTA download failed", e)
                if (listener != null) {
                    val msg = e.message ?: e.javaClass.simpleName
                    ui.post { listener.onError(msg) }
                }
            } finally {
                sDownloading.set(false)
            }
        }, "ota-download").start()
    }

    @JvmStatic
    fun checkUpdate(context: Context, listener: ProgressListener?) {
        if (!sChecking.compareAndSet(false, true)) {
            AppLogger.w(TAG, "checkUpdate: check already in progress — ignored")
            return
        }
        Thread({
            try {
                doCheckUpdate(context.applicationContext, listener, sUi)
            } catch (e: Exception) {
                AppLogger.e(TAG, "OTA check failed", e)
                if (listener != null) {
                    val msg = e.message ?: e.javaClass.simpleName
                    sUi.post { listener.onError(msg) }
                }
            } finally {
                sChecking.set(false)
            }
        }, "ota-update").start()
    }

    @Throws(Exception::class)
    private fun doCheckUpdate(context: Context, listener: ProgressListener?, ui: Handler) {
        val includePrerelease = context
                .getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_OTA_PRERELEASE,
                        SettingsActivity.DEFAULT_OTA_PRERELEASE)

        // 1. Fetch release info from GitHub API. Stable-only users use GitHub's dedicated
        // endpoint, which cannot be pushed off page 1 by frequent betas. Beta users scan every
        // page and choose the semantic maximum instead of trusting release creation order.
        val list = if (includePrerelease) fetchAllReleases()
                   else JSONArray().put(JSONObject(httpGet(RELEASES_LATEST_API)))
        var release: JSONObject? = null
        var selectedVersion: String? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val t = r.getString("tag_name")
            val stripped = if (t.startsWith("v")) t.substring(1) else t
            if (!OtaVersionPolicy.isValidReleaseVersion(stripped)) continue
            if (!includePrerelease && r.optBoolean("prerelease", false)) continue
            if (selectedVersion == null
                    || OtaVersionPolicy.compareVersions(stripped, selectedVersion) > 0) {
                release = r
                selectedVersion = stripped
            }
        }
        if (release == null) {
            AppLogger.i(TAG, "No eligible release found (includePrerelease=$includePrerelease)")
            if (listener != null) ui.post { listener.onUpToDate() }
            return
        }
        val tag = release.getString("tag_name")
        val latestVer = if (tag.startsWith("v")) tag.substring(1) else tag

        if (!isNewer(latestVer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
            AppLogger.i(TAG, "Up to date (current=" + BuildConfig.VERSION_NAME
                    + "+build" + BuildConfig.VERSION_CODE + " latest=" + latestVer + ")")
            if (listener != null) ui.post { listener.onUpToDate() }
            return
        }

        val changelog = release.optString("body", "No changelog provided.")
        AppLogger.i(TAG, "Update available: " + BuildConfig.VERSION_NAME
                + "+build" + BuildConfig.VERSION_CODE + " → " + latestVer)

        // 2. Find APK asset URL
        val assets = release.getJSONArray("assets")
        val expectedName = "DashCast-v$latestVer-release.apk"
        var selectedAsset: ReleaseAsset? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (expectedName == name) {
                val size = asset.optLong("size", -1L)
                if (size <= 0L || size > MAX_APK_BYTES) {
                    throw Exception("Invalid APK asset size: $size")
                }
                val digest = asset.optString("digest", "")
                val sha256 = parseSha256Digest(digest)
                        ?: throw Exception("Missing or invalid SHA-256 for release APK")
                selectedAsset = ReleaseAsset(
                        latestVer,
                        changelog,
                        asset.getString("browser_download_url"),
                        name,
                        size,
                        sha256)
            }
        }
        if (selectedAsset == null) {
            AppLogger.e(TAG, "No exact release APK found in release $latestVer")
            if (listener != null) ui.post {
                listener.onError("No verified release APK in release $latestVer")
            }
            return
        }

        val finalAsset = selectedAsset
        if (listener != null) ui.post { listener.onUpdateFound(finalAsset) }
    }

    @Throws(Exception::class)
    private fun fetchAllReleases(): JSONArray {
        val all = JSONArray()
        var page = 1
        while (true) {
            val batch = JSONArray(httpGet(RELEASES_LIST_API + page))
            for (i in 0 until batch.length()) all.put(batch.getJSONObject(i))
            if (batch.length() < 100) return all
            page++
        }
    }

    // ── Version comparison ────────────────────────────────────────────────────

    /**
     * @param latest tag from GitHub (e.g. "1.1.9", "1.1.9-build170", "1.2.0-rc1")
     * @param currentName [BuildConfig.VERSION_NAME] (e.g. "1.1.9")
     * @param currentCode [BuildConfig.VERSION_CODE] (e.g. 170)
     *
     * Algorithm:
     *  1. Compare base semantic versions (numeric parts only). If they differ, the higher one wins.
     *  2. If base versions are equal AND the latest tag carries a `-buildN` suffix, the build
     *     number is compared against `currentCode`. This supports the versioning policy where
     *     `versionName` is pinned and only `versionCode` increments between releases.
     */
    @JvmStatic
    internal fun isNewer(latest: String, currentName: String, currentCode: Int): Boolean =
            OtaVersionPolicy.isNewer(latest, currentName, currentCode)

    // ── HTTP ─────────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun httpGet(urlStr: String): String {
        val conn = openConnection(urlStr)
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code for $urlStr")
            conn.inputStream.use { input -> return readStream(input) }
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun downloadToFile(asset: ReleaseAsset, dest: File,
                               listener: ProgressListener?, ui: Handler) {
        val urlStr = asset.downloadUrl
        var conn = openConnection(urlStr)
        try {
            var code = conn.responseCode
            // Manual redirect handling for cross-scheme redirects (GitHub CDN)
            var redirectCount = 0
            while ((code == 301 || code == 302 || code == 307 || code == 308) && redirectCount < 5) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) throw Exception("Redirect $code with no Location header")
                // Reject HTTPS->HTTP downgrade. GitHub CDN always uses HTTPS; if a
                // redirect points elsewhere it is either misconfigured or hostile.
                if (!location.lowercase(Locale.ROOT).startsWith("https://")) {
                    throw Exception("Insecure redirect target: $location")
                }
                conn = openConnection(location)
                code = conn.responseCode
                redirectCount++
            }
            if (redirectCount >= 5) throw Exception("Too many redirects ($redirectCount)")
            if (code != 200) throw Exception("Download HTTP $code")

            val total = conn.contentLengthLong // -1 if unknown
            if (total > 0 && total != asset.size) {
                throw Exception("APK Content-Length mismatch: expected="
                        + asset.size + " actual=" + total)
            }
            var downloaded = 0L
            var lastPercent = -2 // -2 so first call with -1 (indeterminate) always fires

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var n = input.read(buf)
                    while (n != -1) {
                        if (downloaded + n > asset.size || downloaded + n > MAX_APK_BYTES) {
                            throw Exception("APK download exceeds declared size " + asset.size)
                        }
                        out.write(buf, 0, n)
                        downloaded += n
                        if (listener != null) {
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                ui.post { listener.onDownloadProgress(percent) }
                            }
                        }
                        n = input.read(buf)
                    }
                }
            }
            if (downloaded != asset.size) {
                throw Exception("APK download size mismatch: expected="
                        + asset.size + " actual=" + downloaded)
            }
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun openConnection(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "DashCast/" + BuildConfig.VERSION_NAME)
        conn.instanceFollowRedirects = false
        return conn
    }

    @Suppress("DEPRECATION")
    @Throws(Exception::class)
    private fun validateDownloadedApk(context: Context, apkFile: File, asset: ReleaseAsset) {
        if (!asset.sha256.matches(Regex("(?i)[0-9a-f]{64}"))) {
            throw Exception("Release APK has no verified SHA-256")
        }
        val actual = sha256(apkFile)
        if (!asset.sha256.equals(actual, ignoreCase = true)) {
            throw Exception("APK SHA-256 mismatch")
        }
        val pm = context.packageManager
        val downloaded = pm.getPackageArchiveInfo(
                apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        val installed = pm.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (downloaded == null || downloaded.applicationInfo == null) {
            throw Exception("Downloaded file is not a readable APK")
        }
        if (context.packageName != downloaded.packageName) {
            throw Exception("APK package mismatch")
        }
        if (asset.version != downloaded.versionName) {
            throw Exception("APK versionName mismatch")
        }
        if (downloaded.longVersionCode <= BuildConfig.VERSION_CODE) {
            throw Exception("APK versionCode is not newer")
        }
        if ((downloaded.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw Exception("Debuggable APK refused")
        }
        val downloadedSigners = downloaded.signingInfo?.apkContentsSigners
        val installedSigners = installed.signingInfo?.apkContentsSigners
        if (!sameSignerSet(downloadedSigners, installedSigners)) {
            throw Exception("APK signer mismatch")
        }
    }

    @JvmStatic
    internal fun sameSignerSet(left: Array<Signature>?, right: Array<Signature>?): Boolean {
        if (left == null || right == null || left.isEmpty() || left.size != right.size) {
            return false
        }
        val leftBytes = Array(left.size) { left[it].toByteArray() }
        val rightBytes = Array(right.size) { right[it].toByteArray() }
        return sameByteSet(leftBytes, rightBytes)
    }

    @JvmStatic
    internal fun sameByteSet(leftBytes: Array<ByteArray>?, rightBytes: Array<ByteArray>?): Boolean {
        if (leftBytes == null || rightBytes == null
                || leftBytes.isEmpty() || leftBytes.size != rightBytes.size) {
            return false
        }
        val comparator = Comparator<ByteArray> { a, b ->
            val length = minOf(a.size, b.size)
            for (i in 0 until length) {
                val cmp = Integer.compare(a[i].toInt() and 0xff, b[i].toInt() and 0xff)
                if (cmp != 0) return@Comparator cmp
            }
            Integer.compare(a.size, b.size)
        }
        Arrays.sort(leftBytes, comparator)
        Arrays.sort(rightBytes, comparator)
        return Arrays.deepEquals(leftBytes, rightBytes)
    }

    @JvmStatic
    internal fun parseSha256Digest(digest: String?): String? {
        if (digest == null || !digest.matches(Regex("(?i)sha256:[0-9a-f]{64}"))) return null
        return digest.substring("sha256:".length).lowercase(Locale.ROOT)
    }

    @Throws(Exception::class)
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val out = StringBuilder(64)
        for (value in digest.digest()) out.append(String.format("%02x", value.toInt() and 0xff))
        return out.toString()
    }

    @Throws(Exception::class)
    private fun readStream(inputStream: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n = inputStream.read(buf)
        while (n != -1) {
            bos.write(buf, 0, n)
            n = inputStream.read(buf)
        }
        return bos.toString("UTF-8")
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Where to download the APK. Prefers the external files dir so the proxy daemon
     * (uid 2000 = shell) can read it for a silent `pm install`; falls back to the app-private
     * cache when external storage is unavailable (e.g. the DL5.1/A13 getExternalFilesDir
     * SecurityException). The PackageInstaller path can read either location.
     */
    private fun resolveApkFile(context: Context): File {
        try {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                ext.mkdirs()
                return File(ext, OtaArtifactCleanup.APK_CACHE_NAME)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "getExternalFilesDir unavailable, using cache: $t")
        }
        return File(context.cacheDir, OtaArtifactCleanup.APK_CACHE_NAME)
    }

    /**
     * Installs the freshly-downloaded APK.
     *
     * Preferred path — **fully silent + auto-relaunch via the proxy daemon**: the daemon runs as
     * uid 2000 (shell), so `pm install -r` needs no user confirmation, exactly like
     * `adb install`. The command stages the APK in `/data/local/tmp` and chains a launcher start
     * after replacement. Because the daemon executes the command in its *own* process, it
     * survives this app being killed mid-install and completes the relaunch.
     *
     * Fallback path — [PackageInstaller]: used when the daemon is not reachable (e.g. the DL5.1
     * signing wall leaves it down) or when `pm` reports a failure. This preserves the system
     * confirmation fallback where required; the result/replacement receivers still relaunch
     * DashCast afterward.
     */
    @Throws(Exception::class)
    private fun installApk(context: Context, apkFile: File) {
        OtaRelaunchCoordinator.markPending(context)
        try {
            if (tryDaemonSilentInstall(context, apkFile)) {
                return
            }
            installViaPackageInstaller(context, apkFile)
        } catch (e: Exception) {
            OtaRelaunchCoordinator.clearPending(context)
            throw e
        }
    }

    /**
     * Attempts the silent daemon install from the existing OTA worker. A missing Binder is
     * reconnected here instead of immediately forcing the interactive PackageInstaller path.
     * The shell command stages the APK in /data/local/tmp; it tries direct shell read first and
     * run-as for debuggable builds, then lets PackageInstaller handle devices where neither
     * source path is shell-readable.
     */
    private fun tryDaemonSilentInstall(context: Context, apkFile: File): Boolean {
        val app = context.applicationContext
        try {
            if (!ProxyClient.isConnected()) {
                AppLogger.i(TAG, "daemon not connected — attempting OTA reconnect")
                if (!ProxyClient.connect(app)) {
                    AppLogger.i(TAG, "daemon OTA reconnect failed — PackageInstaller path")
                    return false
                }
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val component = launch?.component?.flattenToShortString()
            val cmd = OtaInstallCommand.build(
                    apkFile.absolutePath, apkFile.length(),
                    context.packageName, component)
            AppLogger.i(TAG, "daemon silent install: staging " + apkFile.length() + " bytes")
            val out = ProxyClient.runShell(cmd)
            if (out != null && out.contains("Success")) {
                AppLogger.i(TAG, "daemon install reported Success")
                return true
            }
            AppLogger.w(TAG, "daemon install did not report Success, falling back: $out")
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            AppLogger.w(TAG, "daemon install unavailable, falling back: $t")
        }
        return false
    }

    @Throws(Exception::class)
    private fun installViaPackageInstaller(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apkFile.length())
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val installPackagesGranted = context.checkSelfPermission(
                Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        AppLogger.i(TAG, "PackageInstaller fallback: INSTALL_PACKAGES="
                + installPackagesGranted + " requestNoUserAction="
                + (Build.VERSION.SDK_INT >= 31))

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        try {
            sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            session.openWrite("update", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(8192)
                    var n = input.read(buf)
                    while (n != -1) {
                        out.write(buf, 0, n)
                        n = input.read(buf)
                    }
                    session.fsync(out)
                }
            }
            val resultIntent = Intent(context, InstallResultReceiver::class.java)
            // FLAG_IMMUTABLE must NOT be used here: PackageInstaller needs to inject
            // EXTRA_STATUS and EXTRA_STATUS_MESSAGE into the intent when delivering the result.
            // With FLAG_IMMUTABLE those extras are silently dropped → status=1/null.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pi = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
            session.commit(pi.intentSender)
            AppLogger.i(TAG, "PackageInstaller session committed, id=$sessionId")
        } catch (e: Exception) {
            if (session != null) {
                try { session.abandon() } catch (ignore: Throwable) {}
            } else if (sessionId != -1) {
                try { installer.abandonSession(sessionId) } catch (ignore: Throwable) {}
            }
            throw e
        }
    }
}
