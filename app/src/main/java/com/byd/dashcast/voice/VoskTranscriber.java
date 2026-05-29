package com.byd.dashcast.voice;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;

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
 * v1.4.0-beta — Voice step 3 : post-wake transcription with Vosk (offline).
 *
 * <p>When the wake-word engine fires, {@link VoiceService} calls
 * {@link #startListening()}. The transcriber then records for up to
 * {@link #MAX_LISTEN_MS} ms (or until silence of {@link #SILENCE_MS}),
 * runs Vosk on the captured audio, and broadcasts the text via
 * {@link #ACTION_TRANSCRIPT} so that {@link VoiceCommandRouter} can act on it.
 *
 * <p>The Vosk model is downloaded once to
 * {@code getExternalFilesDir("vosk")/vosk-model-small-fr-0.22/} on first use
 * (about 40 MB). Subsequent uses are instant (model loaded in RAM once).
 * The model language is French ; additional language packs can be added later.
 *
 * <p><b>Threading</b>: all Vosk inference runs on a dedicated
 * {@code vosk-transcriber} thread. Only broadcasts are posted to the main
 * thread. Safe to call {@link #startListening()} from any thread.
 *
 * <p><b>Isolation</b>: no production code path references this class.
 * It is only instantiated when the user enables voice commands in the Diag tab.
 */
public final class VoskTranscriber {

    private static final String TAG = "VoskTranscriber";

    // ─── Model config ──────────────────────────────────────────────────────

    /** Vosk small FR model — ~40 MB download, good accuracy for short commands. */
    private static final String MODEL_ASSET = "vosk-model-small-fr-0.22";
    /** URL to download the model zip from the Vosk CDN. */
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip";

    // ─── Broadcast contract ────────────────────────────────────────────────

    /** Broadcast action fired when a transcript is ready. */
    public static final String ACTION_TRANSCRIPT = "com.byd.dashcast.voice.TRANSCRIPT";
    /** String extra — the recognised text, lower-case, trimmed. Empty if nothing heard. */
    public static final String EXTRA_TEXT        = "text";
    /** Boolean extra — true if the model is still downloading / loading. */
    public static final String EXTRA_LOADING     = "loading";
    /** String extra — human-readable error if something went wrong. */
    public static final String EXTRA_ERROR       = "error";

    // ─── Audio config ──────────────────────────────────────────────────────

    private static final int SAMPLE_RATE  = 16_000;
    /** Maximum recording window after wake word. */
    private static final int MAX_LISTEN_MS = 5_000;
    /** Stop early when RMS stays below this for SILENCE_MS consecutive ms. */
    private static final int SILENCE_THRESHOLD_RMS = 200;
    /** Silence duration that ends the listen window. */
    private static final int SILENCE_MS  = 1_200;

    // ─── State ────────────────────────────────────────────────────────────

    private final Context     mCtx;
    private final Handler     mMain = new Handler(Looper.getMainLooper());
    private volatile Model    mModel;
    private volatile boolean  mModelLoading;
    private volatile boolean  mListening;

    public VoskTranscriber(Context ctx) {
        mCtx = ctx.getApplicationContext();
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
            new Thread(this::listenAndTranscribe, "vosk-transcriber").start();
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

    // ─── Model loading ────────────────────────────────────────────────────

    private void loadModelThenListen() {
        mModelLoading = true;
        broadcastLoading();
        AppLogger.i(TAG, "Loading Vosk model — " + MODEL_ASSET);

        new Thread(() -> {
            try {
                File modelDir = new File(mCtx.getExternalFilesDir("vosk"), MODEL_ASSET);
                if (!new File(modelDir, "am").exists()) {
                    AppLogger.i(TAG, "Downloading model from " + MODEL_URL);
                    downloadAndUnzip(MODEL_URL, mCtx.getExternalFilesDir("vosk"));
                    AppLogger.i(TAG, "Download complete");
                }
                AppLogger.i(TAG, "Opening model at " + modelDir.getAbsolutePath());
                Model model = new Model(modelDir.getAbsolutePath());
                mModel = model;
                mModelLoading = false;
                AppLogger.i(TAG, "Vosk model ready");
                new Thread(this::listenAndTranscribe, "vosk-transcriber").start();
            } catch (Exception e) {
                mModelLoading = false;
                AppLogger.e(TAG, "Vosk model error: " + e.getMessage());
                broadcastError("Modèle indisponible : " + e.getMessage());
            }
        }, "vosk-model-loader").start();
    }

    private void downloadAndUnzip(String urlStr, File destDir) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(conn.getInputStream()))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
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
        } finally {
            conn.disconnect();
        }
    }

    // ─── Capture + inference ──────────────────────────────────────────────

    private void listenAndTranscribe() {
        mListening = true;
        AppLogger.i(TAG, "Listening for command (max " + MAX_LISTEN_MS + " ms)…");

        final int channel = AudioFormat.CHANNEL_IN_MONO;
        final int format  = AudioFormat.ENCODING_PCM_16BIT;
        final int minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format);
        final int bufSize = Math.max(minBuf, 3200);

        AudioRecord rec = null;
        Recognizer   reco = null;
        try {
            rec = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, channel, format, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                broadcastError("AudioRecord init failed");
                return;
            }
            reco = new Recognizer(mModel, SAMPLE_RATE);

            rec.startRecording();
            final long deadline = System.currentTimeMillis() + MAX_LISTEN_MS;
            long silenceStart   = -1L;
            final short[] frame = new short[bufSize / 2];
            final byte[]  bytes = new byte[bufSize];

            while (System.currentTimeMillis() < deadline) {
                int n = rec.read(frame, 0, frame.length);
                if (n <= 0) continue;

                // Convert short[] → byte[] (little-endian) for Vosk
                for (int i = 0; i < n; i++) {
                    bytes[i * 2]     = (byte) (frame[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((frame[i] >> 8) & 0xFF);
                }
                reco.acceptWaveForm(bytes, n * 2);

                // Silence detection : compute RMS over the frame
                long sumSq = 0L;
                for (int i = 0; i < n; i++) sumSq += (long) frame[i] * (long) frame[i];
                int rms = (int) Math.sqrt((double) sumSq / n);
                if (rms < SILENCE_THRESHOLD_RMS) {
                    if (silenceStart < 0) silenceStart = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - silenceStart >= SILENCE_MS) {
                        AppLogger.d(TAG, "Silence detected — stopping early");
                        break;
                    }
                } else {
                    silenceStart = -1L;
                }
            }

            String resultJson = reco.getFinalResult();
            String text = extractText(resultJson).trim().toLowerCase(java.util.Locale.FRENCH);
            AppLogger.i(TAG, "Transcript: \"" + text + "\"");
            broadcastText(text);

        } catch (IOException e) {
            AppLogger.e(TAG, "Vosk recognizer error: " + e.getMessage());
            broadcastError(e.getMessage());
        } finally {
            if (reco != null) try { reco.close(); } catch (Throwable ignore) {}
            if (rec  != null) {
                try { rec.stop();    } catch (Throwable ignore) {}
                try { rec.release(); } catch (Throwable ignore) {}
            }
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

    private void broadcastError(String msg) {
        Intent i = new Intent(ACTION_TRANSCRIPT);
        i.putExtra(EXTRA_ERROR, msg == null ? "erreur inconnue" : msg);
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(i);
    }
}
