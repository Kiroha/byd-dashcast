package com.byd.dashcast.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.data.prefs.ClusterPrefs

import org.vosk.Model
import org.vosk.Recognizer

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v1.4.3-beta — Voice step 3 : post-wake transcription with Vosk (offline).
 *
 * Two models available (selected via the Voice panel in DiagActivity):
 *  - **Small** (default, ~40 MB): fast download, good accuracy for short commands.
 *  - **High-accuracy** (~1.3 GB, vosk-model-fr-0.6-linto): much better French ASR,
 *    resumable download with progress bar.
 */
class VoskTranscriber(ctx: Context) {

    // ─── State ────────────────────────────────────────────────────

    private val mCtx: Context = ctx.applicationContext
    // V5: cached process-local LBM singleton — avoids a synchronized lookup per broadcast.
    private val mLbm: LocalBroadcastManager = LocalBroadcastManager.getInstance(mCtx)

    private val mModelAsset: String   // determined from ClusterPrefs at construction
    private val mModelUrl: String
    private val mModelBytes: Long

    @Volatile private var mModel: Model? = null
    @Volatile private var mModelLoading = false
    @Volatile private var mListening = false
    @Volatile private var mPendingListen = false
    @Volatile private var mReleaseRequested = false
    private val mLifecycleLock = Any()

    // V1: reusable PCM→byte conversion buffer; grown as needed, never shrunk.
    private var mPcmBuf = ByteArray(0)
    // V6: single-thread executor reused across listen sessions.
    private val mListenExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vosk-transcriber").apply { isDaemon = true }
    }

    init {
        val large = ClusterPrefs.isVoskHighAccuracy(mCtx)
        mModelAsset = if (large) MODEL_LARGE_ASSET else MODEL_SMALL_ASSET
        mModelUrl   = if (large) MODEL_LARGE_URL   else MODEL_SMALL_URL
        mModelBytes = if (large) MODEL_LARGE_BYTES else MODEL_SMALL_BYTES
        // Delete the OTHER variant if it is on disk — prevents small (~40 MB)
        // and large (~1.3 GB) from coexisting after a model switch.
        // deleteModel() is a no-op when the directory does not exist.
        val otherIsLarge = !large
        val cleanup = Thread(Runnable {
            if (deleteModel(mCtx, otherIsLarge)) {
                AppLogger.d(TAG, "auto-purged inactive model (large=$otherIsLarge)")
            }
        }, "vosk-model-cleanup")
        cleanup.isDaemon = true
        cleanup.start()
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /**
     * Triggers a listen window. If the model is not yet loaded, downloads and
     * loads it first (shows a "loading" broadcast) then starts listening.
     * Safe to call from any thread; re-entrant calls while already listening
     * are ignored.
     */
    fun startListening() {
        synchronized(mLifecycleLock) {
            if (mListening) {
                AppLogger.d(TAG, "startListening() ignored — already listening")
                return
            }
            if (mModel == null) {
                if (mModelLoading) {
                    mPendingListen = true
                    AppLogger.d(TAG, "startListening() — model loading, queued")
                    return
                }
                loadModelThenListen()
            } else {
                // C7 fix: set mListening=true here, inside the lifecycle lock, BEFORE
                // submitting to the executor. Without this, a second startListening()
                // call arriving before doListenViaServiceStream() runs sees mListening=false
                // and submits a second session, both racing on VoiceService.setSampleConsumer().
                mListening = true
                mListenExecutor.execute { doListenViaServiceStream() } // V6
            }
        }
    }

    /** Releases the Vosk model from memory. Call from the owner's onDestroy. */
    fun release() {
        mListenExecutor.shutdown() // V6: stop accepting new listen tasks
        synchronized(mLifecycleLock) {
            mReleaseRequested = true
            if (!mListening) {
                val m = mModel
                mModel = null
                if (m != null) try { m.close() } catch (ignore: Throwable) {}
            }
            // If mListening=true, an active session holds the model.
            // doListenViaServiceStream's finally block closes it once the Recognizer is done.
        }
    }

    /**
     * Downloads (and unzips) the current model in the background without starting
     * a listen session. Safe to call when voice commands are disabled. Progress is
     * broadcast via [ACTION_TRANSCRIPT] + [EXTRA_PROGRESS].
     * No-op if the model is already present on disk.
     */
    fun preDownload() {
        // C4 fix: synchronize the check+set so concurrent calls (e.g. UI button + wake word
        // trigger) cannot both pass the guard and start two simultaneous 1.3 GB downloads.
        // C1 fix: use mLifecycleLock consistently (same lock as startListening/release).
        synchronized(mLifecycleLock) {
            if (mModelLoading) return
            mModelLoading = true
        }
        val modelDir = File(mCtx.getExternalFilesDir("vosk"), mModelAsset)
        if (File(modelDir, "am").exists()) {
            // Already on disk — reset flag (we set it above) and broadcast 100%.
            mModelLoading = false
            broadcastProgress(100, (mModelBytes / 1024 / 1024).toInt(), (mModelBytes / 1024 / 1024).toInt())
            return
        }
        Thread(Runnable {
            try {
                downloadWithProgress(voskBaseDir(mCtx), mModelUrl, mModelBytes)
                mModelLoading = false
                AppLogger.i(TAG, "Pre-download complete: $mModelAsset")
                broadcastProgress(100, (mModelBytes / 1024 / 1024).toInt(), (mModelBytes / 1024 / 1024).toInt())
                sMain.post {
                    android.widget.Toast.makeText(mCtx,
                            "Modèle téléchargé — Jarvis prêt",
                            android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mModelLoading = false
                AppLogger.e(TAG, "preDownload error: " + e.message)
                broadcastError("Téléchargement échoué : " + e.message)
            }
        }, "vosk-predownload").start()
    }

    // ─── Model loading ────────────────────────────────────────────────────

    private fun loadModelThenListen() {
        mModelLoading = true
        broadcastLoading()
        AppLogger.i(TAG, "Loading Vosk model — $mModelAsset")

        mListenExecutor.execute { // V6: reuse pooled thread
            try {
                // Ensure native libs loaded BEFORE any Vosk/JNA class is touched.
                VoiceLibsManager.ensureLoaded(mCtx)

                val modelDir = File(mCtx.getExternalFilesDir("vosk"), mModelAsset)
                if (!File(modelDir, "am").exists()) {
                    AppLogger.i(TAG, "Downloading model from $mModelUrl")
                    downloadWithProgress(voskBaseDir(mCtx), mModelUrl, mModelBytes)
                    AppLogger.i(TAG, "Download complete")
                }
                AppLogger.i(TAG, "Opening model at " + modelDir.absolutePath)
                val model = Model(modelDir.absolutePath)
                mModel = model
                mModelLoading = false
                AppLogger.i(TAG, "Vosk model ready")
                sMain.post {
                    android.widget.Toast.makeText(mCtx,
                            "Jarvis prêt — réessayez votre commande",
                            android.widget.Toast.LENGTH_SHORT).show()
                }
                doListenViaServiceStream()
                if (mPendingListen) {
                    mPendingListen = false
                    startListening()
                }
            } catch (e: Exception) {
                mModelLoading = false
                AppLogger.e(TAG, "Vosk model error: " + e.message)
                broadcastError("Modèle indisponible : " + e.message)
            }
        }
    }

    /**
     * Downloads a zip to a resumable temp file, then unzips it.
     * Broadcasts [EXTRA_PROGRESS] every 1% during download and
     * [EXTRA_PROGRESS] = -1 (indeterminate) during unzip.
     */
    @Throws(IOException::class)
    private fun downloadWithProgress(destDir: File, urlStr: String, expectedBytes: Long) {
        val tmpZip = File(destDir, File(urlStr).name + ".download.tmp")
        var alreadyDone = if (tmpZip.exists()) tmpZip.length() else 0L

        // Storage check: need zip + extracted (estimate 2× for peak usage)
        val freeBytes = destDir.freeSpace
        val needed = Math.max(0L, expectedBytes - alreadyDone) + expectedBytes
        if (freeBytes < needed) {
            throw IOException("Espace insuffisant : besoin de "
                    + (needed / 1024 / 1024) + " Mo, disponible : "
                    + (freeBytes / 1024 / 1024) + " Mo")
        }

        // ── Download (with HTTP resume) ───────────────────────────────────
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000     // 60 s inter-packet; avoids infinite hang on TCP stall
        if (alreadyDone > 0) conn.setRequestProperty("Range", "bytes=$alreadyDone-")
        conn.connect()

        val code = conn.responseCode
        val resumed = (code == 206)  // HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !resumed) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        if (!resumed) alreadyDone = 0L // server doesn't support range, restart
        val totalBytes = if (resumed) expectedBytes else conn.contentLengthLong

        try {
            FileOutputStream(tmpZip, resumed).use { fos ->
                BufferedInputStream(conn.inputStream, 65_536).use { bis ->
                    val buf = ByteArray(65_536)
                    var downloaded = alreadyDone
                    var lastPct = -1
                    var n = bis.read(buf)
                    while (n != -1) {
                        fos.write(buf, 0, n)
                        downloaded += n
                        val pct = if (totalBytes > 0) (downloaded * 100L / totalBytes).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            broadcastProgress(pct,
                                    (downloaded / 1024 / 1024).toInt(),
                                    (totalBytes  / 1024 / 1024).toInt())
                        }
                        n = bis.read(buf)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // ── Unzip — extract to a temp dir, rename atomically on success only ─
        // Prevents a partial extraction from being mistaken for a ready model:
        // if interrupted, tmpExtract exists but the final mModelAsset dir does not,
        // so the "am" guard in loadModelThenListen() correctly triggers a re-download.
        broadcastProgress(-1, 0, (expectedBytes / 1024 / 1024).toInt())
        AppLogger.i(TAG, "Unzipping " + tmpZip.name + " ("
                + (tmpZip.length() / 1024 / 1024) + " Mo)")
        val tmpExtract = File(destDir, mModelAsset + ".extracting")
        deleteRecursive(tmpExtract)
        tmpExtract.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(tmpZip), 65_536)).use { zis ->
            val buf = ByteArray(65_536)
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val out = File(tmpExtract, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    // parentFile is never null for an entry rooted under tmpExtract;
                    // safe-call mirrors the Java getParentFile().mkdirs() without a blind !!.
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        var n = zis.read(buf)
                        while (n != -1) {
                            fos.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        val finalModelDir = File(destDir, mModelAsset)
        deleteRecursive(finalModelDir)
        if (!File(tmpExtract, mModelAsset).renameTo(finalModelDir)) {
            throw IOException("Finalisation du modèle impossible : rename échoué")
        }
        deleteRecursive(tmpExtract)
        tmpZip.delete()
        AppLogger.i(TAG, "Unzip complete")
    }

    // ─── Capture + inference (via VoiceService SampleConsumer pipe) ───────
    //
    // Instead of opening a second AudioRecord (which contends with VoiceService
    // for the mic), we temporarily replace VoiceService's SampleConsumer with
    // our own Vosk consumer. VoiceService's single AudioRecord keeps running;
    // WakeWordEngine is paused for the duration. A CountDownLatch unblocks us
    // when silence or the max window is reached.

    private fun doListenViaServiceStream() {
        synchronized(mLifecycleLock) {
            if (mReleaseRequested || !VoiceService.isRunning()) {
                AppLogger.w(TAG, "doListenViaServiceStream: aborted — released or service not running")
                // C7 fix: mListening was pre-set to true by startListening() before spawn;
                // reset it so future startListening() calls are not permanently blocked.
                mListening = false
                return
            }
            // mListening was already set true by startListening() (fast path) or is
            // still being set here by loadModelThenListen() (slow path via model load).
            mListening = true
        }
        AppLogger.i(TAG, "Listening for command via service stream (max $MAX_LISTEN_MS ms)…")

        val prevConsumer = VoiceService.getSampleConsumer()
        val latch = CountDownLatch(1)
        val deadline = System.currentTimeMillis() + MAX_LISTEN_MS
        val silenceStartMs = longArrayOf(-1L)
        var reco: Recognizer? = null

        try {
            // mModel is guaranteed non-null on this path (set by loadModelThenListen /
            // the fast path in startListening); Recognizer's Model param is a platform
            // type so we forward mModel as-is, exactly as the Java did.
            val finalReco = Recognizer(mModel, VoiceService.SAMPLE_RATE_HZ.toFloat())
            reco = finalReco

            // v1.4.2 — feed pre-roll BEFORE installing the live consumer.
            // This covers audio captured during wake-word detection latency
            // (Recognizer creation = ~100-200 ms) which would otherwise be
            // lost, turning "diagnostic" into "stic".
            val preRoll = VoiceService.getPreRollCopy()
            if (preRoll.isNotEmpty()) {
                // V1: reuse mPcmBuf for pre-roll conversion (grown if needed, never shrunk).
                if (mPcmBuf.size < preRoll.size * 2) mPcmBuf = ByteArray(preRoll.size * 2)
                for (i in preRoll.indices) {
                    mPcmBuf[i * 2]     = (preRoll[i].toInt() and 0xFF).toByte()
                    mPcmBuf[i * 2 + 1] = ((preRoll[i].toInt() shr 8) and 0xFF).toByte()
                }
                finalReco.acceptWaveForm(mPcmBuf, preRoll.size * 2)
                AppLogger.d(TAG, "Pre-roll fed: " + preRoll.size + " samples ("
                        + (preRoll.size * 1000 / VoiceService.SAMPLE_RATE_HZ) + " ms)")
            }

            // Install our Vosk consumer; this pauses WakeWordEngine feed.
            // Object expression (rather than a SAM lambda) keeps compiling whether
            // VoiceService.SampleConsumer stays a Java interface or becomes Kotlin.
            VoiceService.setSampleConsumer(object : VoiceService.SampleConsumer {
                override fun onFrame(pcm: ShortArray, n: Int) {
                    if (latch.count == 0L) return // already done
                    if (mReleaseRequested) { latch.countDown(); return }

                    // V1: Feed PCM to Vosk — reuse mPcmBuf to avoid per-frame allocation.
                    if (mPcmBuf.size < n * 2) mPcmBuf = ByteArray(n * 2)
                    for (i in 0 until n) {
                        mPcmBuf[i * 2]     = (pcm[i].toInt() and 0xFF).toByte()
                        mPcmBuf[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    finalReco.acceptWaveForm(mPcmBuf, n * 2)

                    // Silence + timeout detection
                    var sumSq = 0L
                    for (i in 0 until n) sumSq += pcm[i].toLong() * pcm[i].toLong()
                    val rms = Math.sqrt(sumSq.toDouble() / n).toInt()
                    val now = System.currentTimeMillis()

                    if (now >= deadline) {
                        latch.countDown()
                    } else if (rms < SILENCE_THRESHOLD_RMS) {
                        if (silenceStartMs[0] < 0) silenceStartMs[0] = now
                        else if (now - silenceStartMs[0] >= SILENCE_MS) {
                            AppLogger.d(TAG, "Silence detected — stopping early")
                            latch.countDown()
                        }
                    } else {
                        silenceStartMs[0] = -1L
                    }
                }
            })

            // Block until the consumer signals done (or hard timeout)
            val timedOut = !latch.await(MAX_LISTEN_MS + 500L, TimeUnit.MILLISECONDS)
            if (timedOut) AppLogger.d(TAG, "Listen window timed out")

            // Restore WakeWordEngine consumer BEFORE calling getFinalResult so
            // no concurrent acceptWaveForm can happen.
            VoiceService.setSampleConsumer(prevConsumer)

            val resultJson = finalReco.finalResult
            val text = extractText(resultJson).trim().lowercase(Locale.FRENCH)
            AppLogger.i(TAG, "Transcript: \"$text\"")
            broadcastText(text)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Vosk error: " + e.message)
            broadcastError(e.message)
        } finally {
            // Restore consumer, close Recognizer first, then model if release was requested.
            // Order matters: Recognizer must be closed before Model to avoid use-after-free.
            VoiceService.setSampleConsumer(prevConsumer)
            reco?.let { try { it.close() } catch (ignore: Throwable) {} }
            synchronized(mLifecycleLock) {
                mListening = false
                if (mReleaseRequested) {
                    val m = mModel
                    mModel = null
                    if (m != null) try { m.close() } catch (ignore: Throwable) {}
                }
            }
        }
    }

    // ─── Broadcasts ──────────────────────────────────────────────────────

    private fun broadcastText(text: String) {
        val i = Intent(ACTION_TRANSCRIPT)
        i.putExtra(EXTRA_TEXT, text)
        mLbm.sendBroadcast(i)
    }

    private fun broadcastLoading() {
        val i = Intent(ACTION_TRANSCRIPT)
        i.putExtra(EXTRA_LOADING, true)
        mLbm.sendBroadcast(i)
    }

    private fun broadcastProgress(pct: Int, mbDone: Int, mbTotal: Int) {
        val i = Intent(ACTION_TRANSCRIPT)
        i.putExtra(EXTRA_LOADING, true)
        i.putExtra(EXTRA_PROGRESS, pct)
        i.putExtra(EXTRA_PROGRESS_MB, mbDone)
        i.putExtra(EXTRA_PROGRESS_TOTAL, mbTotal)
        mLbm.sendBroadcast(i)
    }

    private fun broadcastError(msg: String?) {
        val i = Intent(ACTION_TRANSCRIPT)
        i.putExtra(EXTRA_ERROR, msg ?: "erreur inconnue")
        mLbm.sendBroadcast(i)
    }

    companion object {
        private const val TAG = "VoskTranscriber"

        // ─── Model catalogue ──────────────────────────────────────────────────

        const val MODEL_SMALL_ASSET = "vosk-model-small-fr-0.22"
        const val MODEL_SMALL_URL   = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        const val MODEL_SMALL_BYTES = 42_000_000L       // ~40 MB

        /** LinTO large FR model — significantly better accuracy, ~1.3 GB download. */
        const val MODEL_LARGE_ASSET = "vosk-model-fr-0.6-linto"
        const val MODEL_LARGE_URL   = "https://alphacephei.com/vosk/models/vosk-model-fr-0.6-linto.zip"
        const val MODEL_LARGE_BYTES = 1_400_000_000L    // ~1.3 GB

        // ─── Broadcast contract ────────────────────────────────────────────────

        /** Broadcast action fired when a transcript is ready. */
        const val ACTION_TRANSCRIPT = "com.byd.dashcast.voice.TRANSCRIPT"
        /** String extra — the recognised text, lower-case, trimmed. Empty if nothing heard. */
        const val EXTRA_TEXT        = "text"
        /** Boolean extra — true if the model is still downloading / loading. */
        const val EXTRA_LOADING     = "loading"
        /** String extra — human-readable error if something went wrong. */
        const val EXTRA_ERROR       = "error"
        /** Int extra (0–100) — download progress percent; -1 = unzipping. Present only during download. */
        const val EXTRA_PROGRESS       = "dl_progress"
        /** Int extra — megabytes downloaded so far. */
        const val EXTRA_PROGRESS_MB    = "dl_mb_done"
        /** Int extra — total megabytes expected. */
        const val EXTRA_PROGRESS_TOTAL = "dl_mb_total"

        // ─── Audio config ──────────────────────────────────────────────────────

        /** Maximum recording window after wake word. */
        private const val MAX_LISTEN_MS         = 5_000
        /** Stop early when RMS stays below this for SILENCE_MS consecutive ms.
         *  500 is safe in a quiet car; raise further if background noise is high. */
        private const val SILENCE_THRESHOLD_RMS = 500
        /** Silence duration that ends the listen window.
         *  800 ms is snappy enough to not cut mid-word. */
        private const val SILENCE_MS            = 800

        // V7: shared main-thread Handler; one instance per process.
        private val sMain = Handler(Looper.getMainLooper())

        // ─── Static helpers (used by the UI) ────────────────────────────────

        /**
         * Safe base directory for Vosk models. `getExternalFilesDir()` routes through
         * StorageManagerService, which does an AppOps package/uid check that can throw
         * `SecurityException` ("callingPackage does not match UID") on some Android 13 /
         * DiLink 5.1 ROMs (observed opening the diagnostic screen, 2026-07-04). This best-effort
         * accessor never throws: it falls back to an internal `files/vosk` dir. Callers must
         * not let a storage probe crash the UI.
         */
        private fun voskBaseDir(ctx: Context): File {
            try {
                val ext = ctx.getExternalFilesDir("vosk")
                if (ext != null) return ext
            } catch (t: Throwable) {
                AppLogger.w(TAG,
                        "getExternalFilesDir(vosk) unavailable (" + t.javaClass.simpleName + ") — using internal")
            }
            return File(ctx.filesDir, "vosk")
        }

        /** Returns true if the given model variant is fully extracted on disk. Never throws. */
        @JvmStatic
        fun isModelDownloaded(ctx: Context, large: Boolean): Boolean {
            return try {
                val asset = if (large) MODEL_LARGE_ASSET else MODEL_SMALL_ASSET
                File(File(voskBaseDir(ctx), asset), "am").exists()
            } catch (t: Throwable) {
                false
            }
        }

        /** Returns free space in MB on the Vosk model directory, or `-1` if it cannot be read.
         *  Best-effort — never throws (see [voskBaseDir]). */
        @JvmStatic
        fun getFreeSpaceMb(ctx: Context): Long {
            return try {
                voskBaseDir(ctx).freeSpace / 1024 / 1024
            } catch (t: Throwable) {
                -1
            }
        }

        /**
         * Deletes the extracted model directory for the given variant.
         * Also removes any partial download temp file.
         * @return true if the directory was deleted (or didn't exist).
         */
        @JvmStatic
        fun deleteModel(ctx: Context, large: Boolean): Boolean {
            val asset = if (large) MODEL_LARGE_ASSET else MODEL_SMALL_ASSET
            val voskDir = ctx.getExternalFilesDir("vosk")
            // Delete temp download file and any partial extraction if present
            File(voskDir, asset + ".download.tmp").delete()
            deleteRecursive(File(voskDir, asset + ".extracting"))
            val modelDir = File(voskDir, asset)
            if (!modelDir.exists()) return true
            return deleteRecursive(modelDir)
        }

        private fun deleteRecursive(f: File): Boolean {
            if (f.isDirectory) {
                val children = f.listFiles()
                if (children != null) for (c in children) deleteRecursive(c)
            }
            return f.delete()
        }

        // ─── JSON parsing ─────────────────────────────────────────────────────

        /** Extracts the "text" field from Vosk's JSON result string. */
        private fun extractText(json: String?): String {
            // Vosk returns: {"text": "bonjour jarvis"}
            // Simple regex-free parse to avoid requiring org.json on API 29.
            if (json == null) return ""
            val i = json.indexOf("\"text\"")
            if (i < 0) return ""
            val colon = json.indexOf(':', i)
            if (colon < 0) return ""
            val q1 = json.indexOf('"', colon + 1)
            if (q1 < 0) return ""
            val q2 = json.indexOf('"', q1 + 1)
            if (q2 < 0) return ""
            return json.substring(q1 + 1, q2)
        }
    }
}
