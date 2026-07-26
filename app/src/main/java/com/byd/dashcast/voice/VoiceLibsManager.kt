package com.byd.dashcast.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.byd.dashcast.util.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * On-demand download and loading of voice native libraries.
 *
 * The following .so files are excluded from the APK to reduce its size (~44 MB saved):
 *   - libonnxruntime.so       (ONNX Runtime core)
 *   - libonnxruntime4j_jni.so (ONNX JNI bridge)
 *   - libjnidispatch.so       (JNA runtime, required by Vosk)
 *   - libvosk.so              (Vosk ASR)
 *
 * They are downloaded once into `getFilesDir()/voice_libs/` when the user
 * first enables voice recognition, then reused on subsequent launches.
 *
 * **Load order invariant**: [ensureLoaded] must be called before the first
 * access to `OrtEnvironment` or any Vosk class. Because Android tracks loaded
 * libs by base name, a `System.load(absolutePath)` call for "libfoo.so"
 * satisfies a subsequent `System.loadLibrary("foo")` — so loading before
 * OrtEnvironment's static initialiser runs is sufficient.
 *
 * **Hosting**: upload `voice_native_libs_arm64.zip` (produced by
 * `scripts/prepare_voice_libs.sh`) to a GitHub release at [LIBS_ZIP_URL].
 * Update [VERSION_TAG] when libs change so that devices re-download automatically.
 */
@SuppressLint("StaticFieldLeak") // singleton holds application context only (set once) — safe
object VoiceLibsManager {

    private const val TAG = "VoiceLibsManager"

    // ─── Configuration ─────────────────────────────────────────────────────
    // Replace this URL with the GitHub release asset URL after running
    // scripts/prepare_voice_libs.sh and uploading the zip.
    const val LIBS_ZIP_URL =
        "https://github.com/Kiroha/byd-dashcast/releases/download/voice-libs-v1/voice_native_libs_arm64.zip"

    // Approximate zip download size (compressed .so files). Used for:
    //   - HTTP Range fallback total when server doesn't repeat Content-Length on 206
    //   - disk space pre-check (together with LIBS_EXTRACTED_BYTES)
    private const val LIBS_ZIP_BYTES = 10_000_000L        // ~8-10 MB compressed
    // Approximate total of extracted .so files (disk space pre-check).
    private const val LIBS_EXTRACTED_BYTES = 27_000_000L  // ~25 MB uncompressed

    // Bump this when the zip changes (new lib versions, etc.) — triggers re-download.
    private const val VERSION_TAG = "onnx1171_vosk0347_v1"

    // ─── Broadcast contract ─────────────────────────────────────────────────
    const val ACTION_LIBS = "com.byd.dashcast.voice.LIBS"

    /** int 0–100, or -1 for indeterminate (unzipping) */
    const val EXTRA_LIBS_PERCENT = "libs_pct"

    /** int — MB downloaded so far */
    const val EXTRA_LIBS_MB_DONE = "libs_mb"

    /** int — total MB expected */
    const val EXTRA_LIBS_MB_TOTAL = "libs_mb_total"

    /** String — error message if download/load failed */
    const val EXTRA_LIBS_ERROR = "libs_error"

    /** boolean — true once all libs are loaded and ready */
    const val EXTRA_LIBS_READY = "libs_ready"

    // ─── Native lib names (order matters for loading) ──────────────────────
    private val LIB_LOAD_ORDER = arrayOf(
        "libonnxruntime.so",        // core — no app-level deps
        "libonnxruntime4j_jni.so",  // JNI bridge — depends on libonnxruntime
        "libjnidispatch.so",        // JNA runtime
        "libvosk.so",               // Vosk ASR
    )

    // ─── State ─────────────────────────────────────────────────────────────
    @Volatile
    private var sLoaded = false
    private val sLock = Any()

    @SuppressLint("StaticFieldLeak") // application context, set once, safe
    private var sAppCtx: Context? = null

    /**
     * Ensures all voice native libs are on disk and loaded into the JVM.
     * Blocks the calling thread until ready; downloads if needed (~25 MB, one time).
     * Safe to call multiple times — no-op once loaded.
     *
     * @throws Exception if download fails or a lib cannot be loaded.
     *                   The exception message is human-readable for display in the UI.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun ensureLoaded(ctx: Context) {
        if (sLoaded) return
        synchronized(sLock) {
            if (sLoaded) return
            val appCtx = ctx.applicationContext
            sAppCtx = appCtx
            val libDir = getLibDir(appCtx)
            libDir.mkdirs()

            if (!areLibsPresent(libDir)) {
                AppLogger.i(TAG, "Voice libs missing or outdated — downloading")
                downloadLibsZip(appCtx, libDir)
            } else {
                AppLogger.i(TAG, "Voice libs found at " + libDir.absolutePath)
            }

            loadLibsInOrder(libDir)
            sLoaded = true
            broadcastReady(appCtx)
            AppLogger.i(TAG, "Voice libs loaded")
        }
    }

    /** True once all libs have been loaded into the JVM. */
    @JvmStatic
    fun isLoaded(): Boolean = sLoaded

    /**
     * True if the libs are already on disk (download not needed).
     * Does NOT imply they are loaded into the JVM.
     */
    @JvmStatic
    fun isDownloaded(ctx: Context): Boolean =
        areLibsPresent(getLibDir(ctx.applicationContext))

    /** Deletes downloaded lib files (forces re-download on next voice enable). */
    @JvmStatic
    fun deleteLibs(ctx: Context) {
        val libDir = getLibDir(ctx.applicationContext)
        if (!libDir.exists()) return
        val files = libDir.listFiles()
        if (files != null) for (f in files) f.delete()
        AppLogger.i(TAG, "Voice libs deleted from " + libDir.absolutePath)
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private fun getLibDir(ctx: Context): File = File(ctx.filesDir, "voice_libs")

    private fun areLibsPresent(libDir: File): Boolean {
        // Version marker must exist and match — if not, re-download
        val marker = File(libDir, ".version")
        if (!marker.exists()) return false
        try {
            val tag = String(readFileBytes(marker)).trim()
            if (VERSION_TAG != tag) {
                AppLogger.i(TAG, "Lib version mismatch (" + tag + " vs " + VERSION_TAG + ") — will re-download")
                return false
            }
        } catch (e: IOException) {
            return false
        }
        for (name in LIB_LOAD_ORDER) {
            if (!File(libDir, name).exists()) return false
        }
        return true
    }

    @Throws(IOException::class)
    private fun readFileBytes(f: File): ByteArray {
        FileInputStream(f).use { fis ->
            val buf = ByteArray(f.length().toInt())
            val n = fis.read(buf)
            return buf.copyOf(n)
        }
    }

    @Throws(Exception::class)
    private fun downloadLibsZip(ctx: Context, libDir: File) {
        val tmpZip = File(libDir, "voice_libs.download.tmp")
        var alreadyDone = if (tmpZip.exists()) tmpZip.length() else 0L

        // Disk space check: need zip download + extracted files
        val freeBytes = libDir.freeSpace
        val needed = maxOf(0L, LIBS_ZIP_BYTES - alreadyDone) + LIBS_EXTRACTED_BYTES
        if (freeBytes < needed) {
            throw IOException(
                "Espace insuffisant : besoin de "
                    + (needed / 1024 / 1024) + " Mo, disponible : "
                    + (freeBytes / 1024 / 1024) + " Mo"
            )
        }

        // ── Download with HTTP Range resume ──────────────────────────────────
        AppLogger.i(
            TAG, "Downloading voice libs from " + LIBS_ZIP_URL
                + " (resume at " + (alreadyDone / 1024 / 1024) + " Mo)"
        )
        val url = URL(LIBS_ZIP_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (alreadyDone > 0) conn.setRequestProperty("Range", "bytes=$alreadyDone-")
        conn.connect()

        val code = conn.responseCode
        val resumed = code == 206
        if (code != HttpURLConnection.HTTP_OK && !resumed) {
            conn.disconnect()
            throw IOException("Téléchargement échoué — HTTP $code")
        }
        if (!resumed) alreadyDone = 0L
        // For 206 (partial), use LIBS_ZIP_BYTES as fallback since Content-Length
        // reflects only the partial range. For 200, use the actual Content-Length.
        val totalBytes = if (resumed) LIBS_ZIP_BYTES else conn.contentLengthLong

        try {
            FileOutputStream(tmpZip, resumed).use { fos ->
                BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                    val buf = ByteArray(65_536)
                    var n = 0
                    var downloaded = alreadyDone
                    var lastPct = -1
                    while (bis.read(buf).also { n = it } != -1) {
                        fos.write(buf, 0, n)
                        downloaded += n
                        val pct = if (totalBytes > 0) (downloaded * 100L / totalBytes).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            broadcastProgress(
                                ctx, pct,
                                (downloaded / 1024 / 1024).toInt(),
                                (totalBytes / 1024 / 1024).toInt()
                            )
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // ── Unzip ─────────────────────────────────────────────────────────────
        AppLogger.i(TAG, "Extracting voice libs zip")
        broadcastProgress(ctx, -1, 0, (LIBS_ZIP_BYTES / 1024 / 1024).toInt())
        ZipInputStream(BufferedInputStream(FileInputStream(tmpZip), 65_536)).use { zis ->
            val buf = ByteArray(65_536)
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                // Only extract files that match our expected lib names (strip any subdir)
                val name = File(entry.name).name
                val out = File(libDir, name)
                FileOutputStream(out).use { fos ->
                    var n = 0
                    while (zis.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                }
                zis.closeEntry()
            }
        }
        tmpZip.delete()

        // Write version marker
        FileOutputStream(File(libDir, ".version")).use { fos ->
            fos.write(VERSION_TAG.toByteArray())
        }
        AppLogger.i(TAG, "Voice libs extracted to " + libDir.absolutePath)
    }

    @Throws(UnsatisfiedLinkError::class)
    private fun loadLibsInOrder(libDir: File) {
        for (name in LIB_LOAD_ORDER) {
            val lib = File(libDir, name)
            if (!lib.exists()) {
                throw UnsatisfiedLinkError("Lib manquante après extraction : $name")
            }
            AppLogger.d(TAG, "Loading " + lib.absolutePath)
            System.load(lib.absolutePath)
        }
    }

    // ─── Broadcasts ────────────────────────────────────────────────────────

    private fun broadcastProgress(ctx: Context, pct: Int, mbDone: Int, mbTotal: Int) {
        val i = Intent(ACTION_LIBS)
        i.putExtra(EXTRA_LIBS_PERCENT, pct)
        i.putExtra(EXTRA_LIBS_MB_DONE, mbDone)
        i.putExtra(EXTRA_LIBS_MB_TOTAL, mbTotal)
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i)
    }

    private fun broadcastReady(ctx: Context) {
        val i = Intent(ACTION_LIBS)
        i.putExtra(EXTRA_LIBS_READY, true)
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i)
    }

    @JvmStatic
    fun broadcastError(ctx: Context, msg: String?) {
        val i = Intent(ACTION_LIBS)
        i.putExtra(EXTRA_LIBS_ERROR, msg ?: "erreur inconnue")
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i)
    }
}
