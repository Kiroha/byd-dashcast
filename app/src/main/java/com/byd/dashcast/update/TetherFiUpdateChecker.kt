package com.byd.dashcast.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

import com.byd.dashcast.util.AppLogger

import org.json.JSONObject

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

import javax.net.ssl.HttpsURLConnection

/**
 * v1.2.43 — Lightweight GitHub release poller for `pyamsoft/tetherfusenet`.
 *
 * Hits `GET /repos/pyamsoft/tetherfusenet/releases/latest` (unauthenticated, 60 req/h per IP —
 * largely sufficient for a single device that polls on screen open). Parses `tag_name`
 * (e.g. `"release-67"`) and compares the trailing integer to the locally installed
 * `versionCode`. If the remote tag is higher, fires the callback with the new release info.
 *
 * No persistent caching by design: the network call is ~10 KB and runs once per
 * `HotspotActivity.onResume()`, which is rare enough not to warrant a refresh window.
 */
object TetherFiUpdateChecker {

    private const val TAG = "TFUpdate"
    private val sChecking = AtomicBoolean(false)
    private const val API_URL =
            "https://api.github.com/repos/pyamsoft/tetherfusenet/releases/latest"
    private const val TF_PKG = "com.pyamsoft.tetherfi"
    private val TAG_RE: Pattern = Pattern.compile("(?:release-)?(\\d+)")
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000

    class Result(
            @JvmField val installedVersionCode: Int,
            @JvmField val remoteVersionCode: Int,
            @JvmField val remoteTagName: String,
            @JvmField val releasePageUrl: String
    ) {
        /** An `is`-prefixed val, not a fun: HotspotActivity.kt reads it as `r.isUpdateAvailable`
         *  (property syntax), and this shape still emits isUpdateAvailable() for any Java caller. */
        val isUpdateAvailable: Boolean
            get() = remoteVersionCode > installedVersionCode
    }

    interface Callback {
        /** Always invoked on the main thread. */
        fun onResult(result: Result)
        /** Always invoked on the main thread. */
        fun onError(message: String?)
    }

    /**
     * Fire-and-forget check on a worker thread. Callback delivered on the main thread.
     */
    @JvmStatic
    fun check(context: Context, callback: Callback) {
        val installed = getInstalledVersionCode(context)
        if (installed < 0) {
            postError(callback, "TetherFi not installed")
            return
        }
        if (!sChecking.compareAndSet(false, true)) {
            AppLogger.w(TAG, "check: already in progress — ignored")
            return
        }
        Thread({
            var conn: HttpsURLConnection? = null
            try {
                val url = URL(API_URL)
                conn = url.openConnection() as HttpsURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "DashCast-TetherFiUpdateCheck")
                val code = conn.responseCode
                if (code != 200) {
                    postError(callback, "HTTP $code")
                    return@Thread
                }
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                }
                val json = JSONObject(sb.toString())
                val tag = json.optString("tag_name", "")
                val page = json.optString("html_url",
                        "https://github.com/pyamsoft/tetherfusenet/releases/latest")
                val remote = parseTagAsVersionCode(tag)
                if (remote < 0) {
                    postError(callback, "Unparseable tag: $tag")
                    return@Thread
                }
                val r = Result(installed, remote, tag, page)
                postResult(callback, r)
                AppLogger.i(TAG, "check ok: local=" + installed
                        + " remote=" + remote + " (" + tag + ")")
            } catch (t: Throwable) {
                postError(callback, t.javaClass.simpleName + ": " + t.message)
            } finally {
                sChecking.set(false)
                if (conn != null) {
                    try { conn.disconnect() } catch (ignore: Throwable) {}
                }
            }
        }, "tetherfi-update-check").start()
    }

    @Suppress("DEPRECATION")
    private fun getInstalledVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(TF_PKG, 0).versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
    }

    private fun parseTagAsVersionCode(tag: String?): Int {
        if (tag == null) return -1
        val m = TAG_RE.matcher(tag.trim())
        if (!m.find()) return -1
        return try { m.group(1)!!.toInt() }
        catch (e: NumberFormatException) { -1 }
    }

    private fun postResult(cb: Callback, r: Result) {
        Handler(Looper.getMainLooper()).post { cb.onResult(r) }
    }

    private fun postError(cb: Callback, msg: String) {
        Handler(Looper.getMainLooper()).post { cb.onError(msg) }
    }
}
