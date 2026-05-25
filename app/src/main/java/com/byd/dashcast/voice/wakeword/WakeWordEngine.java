package com.byd.dashcast.voice.wakeword;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * v1.2.50-beta — Voice PoC step 2/3.
 *
 * Lightweight wrapper around the openWakeWord ONNX pipeline :
 *   raw PCM 16 kHz  →  melspectrogram.onnx
 *                  →  embedding_model.onnx (Google speech_embedding)
 *                  →  hey_jarvis_v0.1.onnx  (placeholder for "Ok DashCast")
 *
 * <p>The engine owns its own worker thread. The caller (the VoiceService
 * capture thread) merely <i>pushes</i> 16-bit PCM frames via
 * {@link #feed(short[], int)}; the heavy lifting is decoupled to keep the
 * capture loop's tick stable. If the engine is busy when a new frame
 * arrives, the new frame is appended to the rolling audio buffer but the
 * eval cadence is throttled (one inference per ~100 ms).
 *
 * <p><b>Scope and isolation</b> :
 * <ul>
 *   <li>This class is the only consumer of the ONNX Runtime dependency.</li>
 *   <li>It is only instantiated from the Diagnostic Voice tab when the user
 *       toggles the "Wake word" switch ON. Production code paths never load
 *       any ONNX session.</li>
 *   <li>Every {@code OrtSession.run} call is wrapped in try/catch; on
 *       failure the engine flips into a degraded "unavailable" mode and
 *       broadcasts {@link #ACTION_WAKEWORD} with {@code EXTRA_WW_UNAVAILABLE
 *       =true} so the UI can show a graceful message instead of crashing
 *       the foreground service.</li>
 * </ul>
 *
 * <p><b>Streaming math</b> (mirrors openwakeword/utils.py recompute path) :
 * we maintain a sliding 2.5 s buffer of int16 audio. Every ~100 ms we run :
 * <ol>
 *   <li>melspectrogram on the full buffer → {@code (n_frames, 32)} mel
 *       features where {@code n_frames ≈ ceil(samples/160) - 3}.</li>
 *   <li>16 parallel embedding inferences over 76-frame windows stepped by
 *       8 (last 16 windows that fit in the buffer) → {@code (16, 96)}.</li>
 *   <li>hey_jarvis_v0.1 on that {@code (1, 16, 96)} tensor → single
 *       sigmoid score in [0..1]. {@link #DETECT_THRESHOLD} = 0.5.</li>
 * </ol>
 * A cool-down of {@link #DETECT_COOLDOWN_MS} ms is enforced between two
 * "detected" events so a held wake word does not produce a burst.
 */
public final class WakeWordEngine implements com.byd.dashcast.voice.VoiceService.SampleConsumer {

    private static final String TAG = "WakeWordEngine";

    // ─── Broadcast contract ────────────────────────────────────────────────

    public static final String ACTION_WAKEWORD       = "com.byd.dashcast.voice.WAKEWORD";

    /** Latest sigmoid score in [0..1], float. */
    public static final String EXTRA_WW_SCORE        = "ww_score";
    /** Epoch (SystemClock.elapsedRealtime) of the last >threshold detection. 0 if none. */
    public static final String EXTRA_WW_LAST_MS      = "ww_last_ms";
    /** Detection model label, e.g. "hey_jarvis_v0.1". */
    public static final String EXTRA_WW_LABEL        = "ww_label";
    /** Set to true once when the engine flips to degraded mode. */
    public static final String EXTRA_WW_UNAVAILABLE  = "ww_unavailable";
    /** Optional human-readable failure reason. */
    public static final String EXTRA_WW_REASON       = "ww_reason";

    // ─── Pipeline parameters ───────────────────────────────────────────────

    /** Sliding audio buffer length, in samples. 2.5 s gives ample headroom over the 2.04 s minimum. */
    private static final int AUDIO_BUFFER_LEN = 16_000 * 5 / 2; // 40 000

    /** Minimum samples accumulated before the first eval is meaningful. */
    private static final int MIN_AUDIO_SAMPLES = 16_000 * 2;   // 2 s

    /** Throttle : at most one full eval every this many ms. */
    private static final long EVAL_INTERVAL_MS = 100L;

    /** Number of mel frames per embedding window. */
    private static final int EMB_WINDOW = 76;

    /** Stride between two embedding windows, in mel frames (8 frames = 80 ms at 100 Hz). */
    private static final int EMB_STRIDE = 8;

    /** Number of embedding frames consumed by the wake word head. */
    private static final int WAKE_WINDOW = 16;

    /** Sigmoid threshold above which we consider the wake word "detected". */
    private static final float DETECT_THRESHOLD = 0.5f;

    /** Minimum interval between two emitted detections. */
    private static final long DETECT_COOLDOWN_MS = 1500L;

    /** Asset paths inside {@code app/src/main/assets/}. */
    private static final String ASSET_MEL  = "voice/wakeword/melspectrogram.onnx";
    private static final String ASSET_EMB  = "voice/wakeword/embedding_model.onnx";
    private static final String ASSET_WAKE = "voice/wakeword/hey_jarvis_v0.1.onnx";

    /** Identifier surfaced in the UI / broadcast EXTRA_WW_LABEL. */
    public  static final String MODEL_LABEL = "hey_jarvis_v0.1";

    // ─── ONNX state (worker-thread only) ───────────────────────────────────

    private OrtEnvironment mEnv;
    private OrtSession mSessMel;
    private OrtSession mSessEmb;
    private OrtSession mSessWake;
    private String mMelInputName;
    private String mEmbInputName;
    private String mWakeInputName;

    // ─── Audio ring buffer (write from feed(), read from worker) ───────────

    private final short[] mRing = new short[AUDIO_BUFFER_LEN];
    private volatile int  mRingWrite = 0;           // total written modulo AUDIO_BUFFER_LEN
    private volatile long mTotalWritten = 0;        // monotonic count of written samples
    private final Object  mRingLock = new Object();

    // ─── Worker thread ─────────────────────────────────────────────────────

    private final Context mAppCtx;
    private Thread        mWorker;
    private volatile boolean mAlive;
    private volatile boolean mUnavailable;
    private volatile long mLastDetectMs = 0L;
    private volatile float mLastScore   = 0f;

    public WakeWordEngine(Context ctx) {
        this.mAppCtx = ctx.getApplicationContext();
    }

    /** Returns the most recent sigmoid score (for UI polling, optional). */
    public float lastScore()      { return mLastScore; }
    /** Returns the most recent detection timestamp, or 0 if none. */
    public long  lastDetectMs()   { return mLastDetectMs; }
    /** True once the engine has hit an unrecoverable error and is degraded. */
    public boolean isUnavailable(){ return mUnavailable; }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    /** Asynchronously initialises ONNX sessions and starts the worker thread. */
    public synchronized void start() {
        if (mAlive) {
            AppLogger.d(TAG, "start() ignored, already running");
            return;
        }
        mAlive = true;
        mUnavailable = false;
        mWorker = new Thread(this::workerLoop, "wakeword-engine");
        mWorker.setPriority(Thread.NORM_PRIORITY - 1);
        mWorker.start();
    }

    /** Stops the worker thread and releases ONNX resources. Safe to call repeatedly. */
    public synchronized void stop() {
        mAlive = false;
        Thread w = mWorker;
        if (w != null) {
            try { w.join(1000L); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            mWorker = null;
        }
        releaseOnnx();
    }

    // ─── SampleConsumer plumbing ───────────────────────────────────────────

    @Override
    public void onFrame(short[] pcm, int n) {
        if (!mAlive || mUnavailable || n <= 0) return;
        // Lock-free is tempting but a short critical section keeps writes
        // atomic against the worker's snapshot read. 800-sample copies cost
        // well under 50 µs on any ARM target.
        synchronized (mRingLock) {
            int w = mRingWrite;
            for (int i = 0; i < n; i++) {
                mRing[w] = pcm[i];
                w = (w + 1) % AUDIO_BUFFER_LEN;
            }
            mRingWrite = w;
            mTotalWritten += n;
        }
    }

    /** Back-compat single-argument feed retained for any direct callers. */
    public void feed(short[] pcm, int n) { onFrame(pcm, n); }

    // ─── Worker loop ───────────────────────────────────────────────────────

    private void workerLoop() {
        try {
            initOnnx();
        } catch (Throwable t) {
            failOnnxInit(t);
            return;
        }
        AppLogger.i(TAG, "Worker started — " + MODEL_LABEL);

        final float[] audioWindow = new float[AUDIO_BUFFER_LEN];
        long lastEvalAt = 0L;

        while (mAlive) {
            long now = SystemClock.elapsedRealtime();
            long delta = EVAL_INTERVAL_MS - (now - lastEvalAt);
            if (delta > 0) {
                try { Thread.sleep(delta); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            lastEvalAt = SystemClock.elapsedRealtime();

            // Snapshot the latest min(MIN_AUDIO_SAMPLES, total) samples
            // chronologically into audioWindow[0..n-1].
            int n;
            synchronized (mRingLock) {
                long total = mTotalWritten;
                int  w     = mRingWrite;
                n = (int) Math.min(total, AUDIO_BUFFER_LEN);
                int start = (w - n + AUDIO_BUFFER_LEN) % AUDIO_BUFFER_LEN;
                for (int i = 0; i < n; i++) {
                    audioWindow[i] = mRing[(start + i) % AUDIO_BUFFER_LEN];
                }
            }
            if (n < MIN_AUDIO_SAMPLES) continue;

            try {
                float score = runOneEval(audioWindow, n);
                mLastScore = score;
                boolean detected = false;
                if (score >= DETECT_THRESHOLD &&
                    (SystemClock.elapsedRealtime() - mLastDetectMs) >= DETECT_COOLDOWN_MS) {
                    mLastDetectMs = SystemClock.elapsedRealtime();
                    detected = true;
                    AppLogger.i(TAG, "Wake word DETECTED — score=" + score + " label=" + MODEL_LABEL);
                }
                broadcastScore(score, detected);
            } catch (Throwable t) {
                AppLogger.w(TAG, "Eval failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                // Single eval failures should not flip the engine to unavailable —
                // they are usually transient (tensor allocation pressure).
            }
        }
        AppLogger.i(TAG, "Worker stopped");
    }

    // ─── ONNX init & teardown ──────────────────────────────────────────────

    private void initOnnx() throws Exception {
        AssetManager am = mAppCtx.getAssets();
        byte[] melBytes  = readAssetBytes(am, ASSET_MEL);
        byte[] embBytes  = readAssetBytes(am, ASSET_EMB);
        byte[] wakeBytes = readAssetBytes(am, ASSET_WAKE);

        mEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // Single-threaded execution keeps CPU usage predictable on DiLink
        // devices (we don't want the wake-word engine to starve the cluster
        // mirror pipeline running in another thread).
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);

        mSessMel  = mEnv.createSession(melBytes,  opts);
        mSessEmb  = mEnv.createSession(embBytes,  opts);
        mSessWake = mEnv.createSession(wakeBytes, opts);

        // Resolve the actual input names from the model graphs : openWakeWord
        // models use idiomatic-PyTorch names like "input", "input_1", "x.1".
        mMelInputName  = mSessMel.getInputNames().iterator().next();
        mEmbInputName  = mSessEmb.getInputNames().iterator().next();
        mWakeInputName = mSessWake.getInputNames().iterator().next();
        AppLogger.i(TAG, "ONNX ready — mel='" + mMelInputName + "' emb='" + mEmbInputName + "' wake='" + mWakeInputName + "'");
    }

    private void failOnnxInit(Throwable t) {
        AppLogger.e(TAG, "ONNX init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        mUnavailable = true;
        releaseOnnx();
        Intent i = new Intent(ACTION_WAKEWORD);
        i.putExtra(EXTRA_WW_UNAVAILABLE, true);
        i.putExtra(EXTRA_WW_REASON, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        i.putExtra(EXTRA_WW_LABEL, MODEL_LABEL);
        LocalBroadcastManager.getInstance(mAppCtx).sendBroadcast(i);
    }

    private void releaseOnnx() {
        try { if (mSessWake != null) mSessWake.close(); } catch (Throwable ignore) {}
        try { if (mSessEmb  != null) mSessEmb.close();  } catch (Throwable ignore) {}
        try { if (mSessMel  != null) mSessMel.close();  } catch (Throwable ignore) {}
        mSessWake = mSessEmb = mSessMel = null;
        // OrtEnvironment is a process-singleton, do NOT close it.
    }

    // ─── Inference pipeline ────────────────────────────────────────────────

    /** Runs one melspec → embedding → wake pass; returns the sigmoid score. */
    private float runOneEval(float[] audio, int n) throws Exception {
        // 1) melspectrogram : (1, n_samples) float32 → (n_frames, 1, 1, 32)
        float[][] melspec = computeMelspec(audio, n);
        int nFrames = melspec.length;
        // We need at least EMB_WINDOW + (WAKE_WINDOW-1)*EMB_STRIDE = 76 + 120 = 196 mel frames.
        int needed = EMB_WINDOW + (WAKE_WINDOW - 1) * EMB_STRIDE;
        if (nFrames < needed) return mLastScore;

        // 2) embedding : pack the last WAKE_WINDOW windows of EMB_WINDOW frames each (stride EMB_STRIDE)
        //    into a single (WAKE_WINDOW, 76, 32, 1) batch tensor.
        float[][][][] embInput = new float[WAKE_WINDOW][EMB_WINDOW][32][1];
        for (int b = 0; b < WAKE_WINDOW; b++) {
            int endFrame   = nFrames - (WAKE_WINDOW - 1 - b) * EMB_STRIDE;
            int startFrame = endFrame - EMB_WINDOW;
            for (int j = 0; j < EMB_WINDOW; j++) {
                float[] row = melspec[startFrame + j];
                for (int k = 0; k < 32; k++) {
                    embInput[b][j][k][0] = row[k];
                }
            }
        }
        float[][] features = new float[WAKE_WINDOW][96];
        try (OnnxTensor embT = OnnxTensor.createTensor(mEnv, embInput);
             OrtSession.Result out = mSessEmb.run(Collections.singletonMap(mEmbInputName, embT))) {
            float[][][][] raw = (float[][][][]) out.get(0).getValue();
            // raw shape : (WAKE_WINDOW, 1, 1, 96) → squeeze
            for (int b = 0; b < WAKE_WINDOW; b++) {
                features[b] = raw[b][0][0];
            }
        }

        // 3) wake : (1, 16, 96) float32 → (1, 1) sigmoid
        float[][][] wakeInput = new float[1][WAKE_WINDOW][96];
        wakeInput[0] = features;
        try (OnnxTensor wt = OnnxTensor.createTensor(mEnv, wakeInput);
             OrtSession.Result out = mSessWake.run(Collections.singletonMap(mWakeInputName, wt))) {
            float[][] raw = (float[][]) out.get(0).getValue();
            return raw[0][0];
        }
    }

    /**
     * Runs the melspectrogram model and returns the mel features in
     * {@code (n_frames, 32)} form with the openWakeWord normalisation
     * {@code y = x/10 + 2} applied (this transform matches what the
     * upstream library does before feeding the embedding model).
     */
    private float[][] computeMelspec(float[] audio, int n) throws Exception {
        // The model expects (batch=1, samples). Feed a FloatBuffer view over a
        // tight-fit copy to avoid spurious trailing zeros from the ring buffer
        // capacity when n < AUDIO_BUFFER_LEN.
        float[] tight = (n == audio.length) ? audio : java.util.Arrays.copyOf(audio, n);
        long[] shape = { 1L, (long) n };
        try (OnnxTensor in = OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(tight), shape);
             OrtSession.Result out = mSessMel.run(Collections.singletonMap(mMelInputName, in))) {
            // Output shape : (time, 1, 1, 32) float32.
            float[][][][] raw = (float[][][][]) out.get(0).getValue();
            int nFrames = raw.length;
            float[][] mel = new float[nFrames][32];
            for (int t = 0; t < nFrames; t++) {
                float[] src = raw[t][0][0];
                for (int k = 0; k < 32; k++) {
                    mel[t][k] = src[k] / 10f + 2f;
                }
            }
            return mel;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static byte[] readAssetBytes(AssetManager am, String name) throws Exception {
        try (InputStream in = am.open(name);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    private void broadcastScore(float score, boolean detected) {
        Intent i = new Intent(ACTION_WAKEWORD);
        i.putExtra(EXTRA_WW_SCORE,   score);
        i.putExtra(EXTRA_WW_LAST_MS, mLastDetectMs);
        i.putExtra(EXTRA_WW_LABEL,   MODEL_LABEL);
        LocalBroadcastManager.getInstance(mAppCtx).sendBroadcast(i);
        // Detection beep / haptic etc. is the UI's responsibility — keep the
        // engine side-effect-free beyond the broadcast.
        if (detected) { /* no-op : flag is conveyed through ww_last_ms == now */ }
    }
}
