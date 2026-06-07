package com.byd.dashcast.voice;

import android.content.Context;
import android.content.Intent;
import java.io.FileInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.data.prefs.ClusterPrefs;

import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import java.io.File;
import java.io.IOException;

/**
 * v1.4.3-beta — Voice step 3 : post-wake transcription with Vosk (offline).
 *
 * <p>Two models available (selected via the Voice panel in DiagActivity):
 * <ul>
 *   <li><b>Small</b> (default, ~40 MB): fast download, good accuracy for short commands.
 *   <li><b>High-accuracy</b> (~1.3 GB, vosk-model-fr-0.6-linto): much better French ASR,
 *       resumable download with progress bar.
 * </ul>
 */
public final class VoskTranscriber {

    private static final String TAG = "VoskTranscriber";

    // ─── Model catalogue ──────────────────────────────────────────────────

    public static final String MODEL_SMALL_ASSET = "vosk-model-small-fr-0.22";
    public static final String MODEL_SMALL_URL   = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip";
    public static final long   MODEL_SMALL_BYTES = 42_000_000L;       // ~40 MB

    /** LinTO large FR model — significantly better accuracy, ~1.3 GB download. */
    public static final String MODEL_LARGE_ASSET = "vosk-model-fr-0.6-linto";
    public static final String MODEL_LARGE_URL   = "https://alphacephei.com/vosk/models/vosk-model-fr-0.6-linto.zip";
    public static final long   MODEL_LARGE_BYTES = 1_400_000_000L;    // ~1.3 GB

    // ─── Broadcast contract ────────────────────────────────────────────────

    /** Broadcast action fired when a transcript is ready. */
    public static final String ACTION_TRANSCRIPT = "com.byd.dashcast.voice.TRANSCRIPT";
    /** String extra — the recognised text, lower-case, trimmed. Empty if nothing heard. */
    public static final String EXTRA_TEXT        = "text";
    /** Boolean extra — true if the model is still downloading / loading. */
    public static final String EXTRA_LOADING     = "loading";
    /** String extra — human-readable error if something went wrong. */
    public static final String EXTRA_ERROR       = "error";
    /** Int extra (0–100) — download progress percent; -1 = unzipping. Present only during download. */
    public static final String EXTRA_PROGRESS       = "dl_progress";
    /** Int extra — megabytes downloaded so far. */
    public static final String EXTRA_PROGRESS_MB    = "dl_mb_done";
    /** Int extra — total megabytes expected. */
    public static final String EXTRA_PROGRESS_TOTAL = "dl_mb_total";

    // ─── Audio config ──────────────────────────────────────────────────────

    /** Maximum recording window after wake word. */
    private static final int MAX_LISTEN_MS         = 5_000;
    /** Stop early when RMS stays below this for SILENCE_MS consecutive ms.
     *  500 is safe in a quiet car; raise further if background noise is high. */
    private static final int SILENCE_THRESHOLD_RMS = 500;
    /** Silence duration that ends the listen window.
     *  800 ms is snappy enough to not cut mid-word. */
    private static final int SILENCE_MS            = 800;

    // ─── State ────────────────────────────────────────────────────

    private final Context    mCtx;
    private final String     mModelAsset;   // determined from ClusterPrefs at construction
    private final String     mModelUrl;
    private final long       mModelBytes;
    private volatile Model   mModel;
    private volatile boolean mModelLoading;
    private volatile boolean mListening;

    public VoskTranscriber(Context ctx) {
        mCtx = ctx.getApplicationContext();
        boolean large = ClusterPrefs.isVoskHighAccuracy(mCtx);
        mModelAsset = large ? MODEL_LARGE_ASSET : MODEL_SMALL_ASSET;
        mModelUrl   = large ? MODEL_LARGE_URL   : MODEL_SMALL_URL;
        mModelBytes = large ? MODEL_LARGE_BYTES : MODEL_SMALL_BYTES;
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /**
     * Triggers a listen window. If the model is not yet loaded, downloads and
     * loads it first (shows a "loading" broadcast) then starts listening.
     * Safe to call from any thread; re-entrant calls while already listening
     * are ignored.
     */
    public void startListening() {
        if (mListening) {
            AppLogger.d(TAG, "startListening() ignored — already listening");
            return;
        }
        if (mModel == null) {
            if (mModelLoading) {
                AppLogger.d(TAG, "startListening() — model still loading, queued");
                return;
            }
            loadModelThenListen();
        } else {
            new Thread(this::doListenViaServiceStream, "vosk-transcriber").start();
        }
    }

    /** Releases the Vosk model from memory. Call from the owner's onDestroy. */
    public void release() {
        Model m = mModel;
        mModel = null;
        if (m != null) {
            try { m.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * Downloads (and unzips) the current model in the background without starting
     * a listen session. Safe to call when voice commands are disabled. Progress is
     * broadcast via {@link #ACTION_TRANSCRIPT} + {@link #EXTRA_PROGRESS}.
     * No-op if the model is already present on disk.
     */
    public void preDownload() {
        if (mModelLoading) return;
        File modelDir = new File(mCtx.getExternalFilesDir("vosk"), mModelAsset);
        if (new File(modelDir, "am").exists()) {
            // Already there — broadcast 100% so the UI updates
            broadcastProgress(100, (int)(mModelBytes / 1024 / 1024), (int)(mModelBytes / 1024 / 1024));
            return;
        }
        mModelLoading = true;
        new Thread(() -> {
            try {
                downloadWithProgress(mCtx.getExternalFilesDir("vosk"), mModelUrl, mModelBytes);
                mModelLoading = false;
                AppLogger.i(TAG, "Pre-download complete: " + mModelAsset);
                broadcastProgress(100, (int)(mModelBytes / 1024 / 1024), (int)(mModelBytes / 1024 / 1024));
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(mCtx,
                                "Modèle téléchargé — Jarvis prêt",
                                android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                mModelLoading = false;
                AppLogger.e(TAG, "preDownload error: " + e.getMessage());
                broadcastError("Téléchargement échoué : " + e.getMessage());
            }
        }, "vosk-predownload").start();
    }

    // ─── Static helpers (used by the UI) ────────────────────────────────

    /** Returns true if the given model variant is fully extracted on disk. */
    public static boolean isModelDownloaded(Context ctx, boolean large) {
        String asset = large ? MODEL_LARGE_ASSET : MODEL_SMALL_ASSET;
        File modelDir = new File(ctx.getExternalFilesDir("vosk"), asset);
        return new File(modelDir, "am").exists();
    }

    /** Returns free space in MB on the external files directory. */
    public static long getFreeSpaceMb(Context ctx) {
        File dir = ctx.getExternalFilesDir("vosk");
        if (dir == null) dir = ctx.getCacheDir();
        return dir.getFreeSpace() / 1024 / 1024;
    }

    /**
     * Deletes the extracted model directory for the given variant.
     * Also removes any partial download temp file.
     * @return true if the directory was deleted (or didn't exist).
     */
    public static boolean deleteModel(Context ctx, boolean large) {
        String asset = large ? MODEL_LARGE_ASSET : MODEL_SMALL_ASSET;
        File voskDir = ctx.getExternalFilesDir("vosk");
        // Delete temp download file if present
        new File(voskDir, asset + ".download.tmp").delete();
        File modelDir = new File(voskDir, asset);
        if (!modelDir.exists()) return true;
        return deleteRecursive(modelDir);
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    // ─── Model loading ────────────────────────────────────────────────────

    private void loadModelThenListen() {
        mModelLoading = true;
        broadcastLoading();
        AppLogger.i(TAG, "Loading Vosk model — " + mModelAsset);

        new Thread(() -> {
            try {
                // Ensure native libs loaded BEFORE any Vosk/JNA class is touched.
                VoiceLibsManager.ensureLoaded(mCtx);

                File modelDir = new File(mCtx.getExternalFilesDir("vosk"), mModelAsset);
                if (!new File(modelDir, "am").exists()) {
                    AppLogger.i(TAG, "Downloading model from " + mModelUrl);
                    downloadWithProgress(mCtx.getExternalFilesDir("vosk"), mModelUrl, mModelBytes);
                    AppLogger.i(TAG, "Download complete");
                }
                AppLogger.i(TAG, "Opening model at " + modelDir.getAbsolutePath());
                Model model = new Model(modelDir.getAbsolutePath());
                mModel = model;
                mModelLoading = false;
                AppLogger.i(TAG, "Vosk model ready");
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(mCtx,
                                "Jarvis prêt — réessayez votre commande",
                                android.widget.Toast.LENGTH_SHORT).show());
                doListenViaServiceStream();
            } catch (Exception e) {
                mModelLoading = false;
                AppLogger.e(TAG, "Vosk model error: " + e.getMessage());
                broadcastError("Modèle indisponible : " + e.getMessage());
            }
        }, "vosk-model-loader").start();
    }

    /**
     * Downloads a zip to a resumable temp file, then unzips it.
     * Broadcasts {@link #EXTRA_PROGRESS} every 1% during download and
     * {@link #EXTRA_PROGRESS} = -1 (indeterminate) during unzip.
     */
    private void downloadWithProgress(File destDir, String urlStr, long expectedBytes)
            throws IOException {
        File tmpZip = new File(destDir, new File(urlStr).getName() + ".download.tmp");
        long alreadyDone = tmpZip.exists() ? tmpZip.length() : 0;

        // Storage check: need zip + extracted (estimate 2× for peak usage)
        long freeBytes = destDir.getFreeSpace();
        long needed    = Math.max(0, expectedBytes - alreadyDone) + expectedBytes;
        if (freeBytes < needed) {
            throw new IOException("Espace insuffisant : besoin de "
                    + (needed / 1024 / 1024) + " Mo, disponible : "
                    + (freeBytes / 1024 / 1024) + " Mo");
        }

        // ── Download (with HTTP resume) ───────────────────────────────────
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(0);          // unlimited — large file
        if (alreadyDone > 0) conn.setRequestProperty("Range", "bytes=" + alreadyDone + "-");
        conn.connect();

        int code = conn.getResponseCode();
        boolean resumed = (code == 206);  // HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && !resumed) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        if (!resumed) alreadyDone = 0; // server doesn't support range, restart
        final long totalBytes = resumed ? expectedBytes : conn.getContentLengthLong();

        try (FileOutputStream fos = new FileOutputStream(tmpZip, resumed);
             BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 65_536)) {
            byte[] buf = new byte[65_536];
            int n;
            long downloaded = alreadyDone;
            int lastPct = -1;
            while ((n = bis.read(buf)) != -1) {
                fos.write(buf, 0, n);
                downloaded += n;
                int pct = totalBytes > 0 ? (int) (downloaded * 100L / totalBytes) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    broadcastProgress(pct,
                            (int) (downloaded / 1024 / 1024),
                            (int) (totalBytes  / 1024 / 1024));
                }
            }
        } finally {
            conn.disconnect();
        }

        // ── Unzip — broadcast indeterminate (-1) during extraction ────────
        broadcastProgress(-1, 0, (int) (expectedBytes / 1024 / 1024));
        AppLogger.i(TAG, "Unzipping " + tmpZip.getName() + " ("
                + (tmpZip.length() / 1024 / 1024) + " Mo)");
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(tmpZip), 65_536))) {
            ZipEntry entry;
            byte[] buf = new byte[65_536];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
        tmpZip.delete();
        AppLogger.i(TAG, "Unzip complete");
    }

    // ─── Capture + inference (via VoiceService SampleConsumer pipe) ───────
    //
    // Instead of opening a second AudioRecord (which contends with VoiceService
    // for the mic), we temporarily replace VoiceService's SampleConsumer with
    // our own Vosk consumer. VoiceService's single AudioRecord keeps running;
    // WakeWordEngine is paused for the duration. A CountDownLatch unblocks us
    // when silence or the max window is reached.

    private void doListenViaServiceStream() {
        if (!VoiceService.isRunning()) {
            AppLogger.w(TAG, "doListenViaServiceStream: VoiceService not running — skip");
            return;
        }
        mListening = true;
        AppLogger.i(TAG, "Listening for command via service stream (max " + MAX_LISTEN_MS + " ms)…");

        final VoiceService.SampleConsumer prevConsumer = VoiceService.getSampleConsumer();
        final CountDownLatch latch = new CountDownLatch(1);
        final long deadline = System.currentTimeMillis() + MAX_LISTEN_MS;
        final long[] silenceStartMs = {-1L};
        Recognizer reco = null;

        try {
            reco = new Recognizer(mModel, VoiceService.SAMPLE_RATE_HZ);
            final Recognizer finalReco = reco;

            // v1.4.2 — feed pre-roll BEFORE installing the live consumer.
            // This covers audio captured during wake-word detection latency
            // (Recognizer creation = ~100-200 ms) which would otherwise be
            // lost, turning "diagnostic" into "stic".
            short[] preRoll = VoiceService.getPreRollCopy();
            if (preRoll.length > 0) {
                byte[] preBytes = new byte[preRoll.length * 2];
                for (int i = 0; i < preRoll.length; i++) {
                    preBytes[i * 2]     = (byte) (preRoll[i] & 0xFF);
                    preBytes[i * 2 + 1] = (byte) ((preRoll[i] >> 8) & 0xFF);
                }
                finalReco.acceptWaveForm(preBytes, preBytes.length);
                AppLogger.d(TAG, "Pre-roll fed: " + preRoll.length + " samples ("
                        + (preRoll.length * 1000 / VoiceService.SAMPLE_RATE_HZ) + " ms)");
            }

            // Install our Vosk consumer; this pauses WakeWordEngine feed.
            VoiceService.setSampleConsumer((pcm, n) -> {
                if (latch.getCount() == 0) return; // already done

                // Feed PCM to Vosk (little-endian byte conversion)
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    bytes[i * 2]     = (byte) (pcm[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
                }
                finalReco.acceptWaveForm(bytes, n * 2);

                // Silence + timeout detection
                long sumSq = 0L;
                for (int i = 0; i < n; i++) sumSq += (long) pcm[i] * (long) pcm[i];
                int rms = (int) Math.sqrt((double) sumSq / n);
                long now = System.currentTimeMillis();

                if (now >= deadline) {
                    latch.countDown();
                } else if (rms < SILENCE_THRESHOLD_RMS) {
                    if (silenceStartMs[0] < 0) silenceStartMs[0] = now;
                    else if (now - silenceStartMs[0] >= SILENCE_MS) {
                        AppLogger.d(TAG, "Silence detected — stopping early");
                        latch.countDown();
                    }
                } else {
                    silenceStartMs[0] = -1L;
                }
            });

            // Block until the consumer signals done (or hard timeout)
            boolean timedOut = !latch.await(MAX_LISTEN_MS + 500L, TimeUnit.MILLISECONDS);
            if (timedOut) AppLogger.d(TAG, "Listen window timed out");

            // Restore WakeWordEngine consumer BEFORE calling getFinalResult so
            // no concurrent acceptWaveForm can happen.
            VoiceService.setSampleConsumer(prevConsumer);

            String resultJson = finalReco.getFinalResult();
            String text = extractText(resultJson).trim().toLowerCase(java.util.Locale.FRENCH);
            AppLogger.i(TAG, "Transcript: \"" + text + "\"");
            broadcastText(text);

        } catch (Exception e) {
            AppLogger.e(TAG, "Vosk error: " + e.getMessage());
            broadcastError(e.getMessage());
        } finally {
            // Always restore previous consumer and mark as idle
            VoiceService.setSampleConsumer(prevConsumer);
            if (reco != null) try { reco.close(); } catch (Throwable ignore) {}
            mListening = false;
        }
    }

    // ─── JSON parsing ─────────────────────────────────────────────────────

    /** Extracts the "text" field from Vosk's JSON result string. */
    private static String extractText(String json) {
        // Vosk returns: {"text": "bonjour jarvis"}
        // Simple regex-free parse to avoid requiring org.json on API 29.
        if (json == null) return "";
        int i = json.indexOf("\"text\"");
        if (i < 0) return "";
        int colon = json.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2);
    }

    // ─── Broadcasts ──────────────────────────────────────────────────────

    private void broadcastText(String text) {
        Intent i = new Intent(ACTION_TRANSCRIPT);
        i.putExtra(EXTRA_TEXT, text);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }

    private void broadcastLoading() {
        Intent i = new Intent(ACTION_TRANSCRIPT);
        i.putExtra(EXTRA_LOADING, true);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }

    private void broadcastProgress(int pct, int mbDone, int mbTotal) {
        Intent i = new Intent(ACTION_TRANSCRIPT);
        i.putExtra(EXTRA_LOADING, true);
        i.putExtra(EXTRA_PROGRESS, pct);
        i.putExtra(EXTRA_PROGRESS_MB, mbDone);
        i.putExtra(EXTRA_PROGRESS_TOTAL, mbTotal);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }

    private void broadcastError(String msg) {
        Intent i = new Intent(ACTION_TRANSCRIPT);
        i.putExtra(EXTRA_ERROR, msg == null ? "erreur inconnue" : msg);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }
}
