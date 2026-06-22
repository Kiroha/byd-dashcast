package com.byd.dashcast.voice.wakeword;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.util.AppLogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;

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
    /** v1.2.80 — rolling peak score over the last {@link #PEAK_WINDOW_MS}. */
    public static final String EXTRA_WW_PEAK_SCORE   = "ww_peak_score";
    /** v1.2.80 — age in ms of that peak (0 = right now). */
    public static final String EXTRA_WW_PEAK_AGE_MS  = "ww_peak_age_ms";

    // ─── Pipeline parameters ───────────────────────────────────────────────

    /** Sliding audio buffer length, in samples. 2.5 s gives ample headroom over the 2.04 s minimum. */
    private static final int AUDIO_BUFFER_LEN = 16_000 * 5 / 2; // 40 000

    /** Minimum samples accumulated before the first eval is meaningful. */
    private static final int MIN_AUDIO_SAMPLES = 16_000 * 2;   // 2 s

    /** Throttle : at most one full eval every this many ms.
     *  perf — raised 100 → 160 ms (10 Hz → ~6.3 Hz). With a 2.5 s window and a
     *  1.5 s detect cool-down, the wake word still gets ~15 evals while it sits
     *  in the buffer; worst-case added latency +60 ms (imperceptible). Cuts the
     *  continuous wake-word CPU by ~37 % outright. */
    private static final long EVAL_INTERVAL_MS = 160L;

    /** perf — silence gate. If the loudest sample in the WHOLE 2.5 s window is
     *  below this normalised-float floor (~-49 dBFS), the audio is genuine
     *  silence and we skip the ONNX pipeline entirely. Safe by construction:
     *  because the gate inspects the full window, a wake word (and its 2.5 s
     *  echo in the ring) keeps the engine active for 2.5 s, so a real utterance
     *  can never be skipped. A signal below this floor is also below the model's
     *  own sensitivity, so no detection the model could have made is lost.
     *  Set to 0f to disable. */
    private static final float SILENCE_GATE_FLOOR = 0.0035f;

    // ─── perf: streaming inference parameters ──────────────────────────────
    /** Samples per mel frame (100 Hz framing → 160 samples at 16 kHz). */
    private static final int MEL_HOP = 160;
    /** Rolling mel-frame cache capacity. Must exceed the 204 frames the wake
     *  head spans (16*8 + 76) plus a few ticks of advance. */
    private static final int MEL_RING_FRAMES = 320;
    /** Rolling embedding cache (stride-grid windows). Must exceed WAKE_WINDOW. */
    private static final int EMB_CACHE = 24;
    /** Left-context frames discarded from each short mel feed. Generously larger
     *  than any plausible STFT window, so the frames we keep have full left
     *  context and are bit-identical to the full-window computation. */
    private static final int MEL_WARMUP_FRAMES = 48;
    /** Right-context guard: the newest frames of a feed may use padded/reflected
     *  right context (centered STFT), so we defer committing them by one tick
     *  until real future audio exists. 3 frames = 30 ms covers any 16 kHz STFT. */
    private static final int MEL_GUARD = 3;
    /** Transient on-device self-check: run the reference full-recompute path
     *  alongside streaming for this long after the first non-silent eval and log
     *  both scores, so detection parity can be confirmed by voice on the target
     *  unit. Set to 0 to disable. */
    private static final long SELF_CHECK_MS = 12_000L;

    /** Number of mel frames per embedding window. */
    private static final int EMB_WINDOW = 76;

    /** Stride between two embedding windows, in mel frames (8 frames = 80 ms at 100 Hz). */
    private static final int EMB_STRIDE = 8;

    /** Number of embedding frames consumed by the wake word head. */
    private static final int WAKE_WINDOW = 16;

    /** Sigmoid threshold above which we consider the wake word "detected".
     *  v1.2.80 — lowered 0.5 → 0.3 after field reports of the user shouting
     *  "Hey Jarvis" without ever crossing the 0.5 bar. The hey_jarvis_v0.1
     *  model is sensitive enough that 0.3 still avoids most ambient false
     *  positives in a car cabin (validated against engine + HVAC noise). */
    private static final float DETECT_THRESHOLD = 0.3f;

    /** Minimum interval between two emitted detections. */
    private static final long DETECT_COOLDOWN_MS = 1500L;

    /** v1.2.80 — rolling window for the diag "peak score" surfaced to the
     *  UI / Logs. 30 s is short enough to react to user voice tests but long
     *  enough to catch a single attempt the user made 10 s ago. */
    private static final long PEAK_WINDOW_MS = 30_000L;

    /** v1.2.80 — interval between two diag log lines in AppLogger. */
    private static final long DIAG_LOG_INTERVAL_MS = 5_000L;

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
    // v1.2.80 — rolling peak over PEAK_WINDOW_MS for diag visibility.
    private volatile float mPeakScore   = 0f;
    private volatile long  mPeakAtMs    = 0L;

    // ─── Pre-allocated inference buffers (worker-thread only) ─────────────────
    // Reused on every eval cycle to eliminate ~770 KB of heap allocation per 100ms.
    private float[][]    mMelBuf;
    private int          mMelBufFrames = -1;
    private final float[][][][] mEmbInput  = new float[WAKE_WINDOW][EMB_WINDOW][32][1];
    private final float[][][]   mWakeInput = new float[1][WAKE_WINDOW][96];
    private final HashMap<String, OnnxTensor> mMelMap  = new HashMap<>(2);
    private final HashMap<String, OnnxTensor> mEmbMap  = new HashMap<>(2);
    private final HashMap<String, OnnxTensor> mWakeMap = new HashMap<>(2);
    private final Intent mScoreIntent = new Intent(ACTION_WAKEWORD);

    // ─── perf: streaming inference state (worker-thread only) ──────────────────
    private final float[]       mMelFeed   = new float[AUDIO_BUFFER_LEN];        // normalized audio fed to mel
    private final float[][]     mMelRing   = new float[MEL_RING_FRAMES][32];     // rolling normalized mel frames
    private long                mMelTotalFrames = 0L;                            // committed mel frames (absolute)
    private final float[][][][] mEmb1Input = new float[1][EMB_WINDOW][32][1];    // batch-1 embedding input
    private final float[][]     mEmbRing   = new float[EMB_CACHE][96];           // rolling embeddings (grid k → k % EMB_CACHE)
    private long                mEmbHeadGrid   = -1L;                            // highest computed grid index
    private long                mEmbContigFrom = 0L;                             // lowest grid index of the contiguous valid run
    private long                mFirstEvalAt   = 0L;                             // self-check window anchor

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
        mLastDetectMs = 0L;
        mWorker = new Thread(this::workerLoop, "wakeword-engine");
        mWorker.setPriority(Thread.NORM_PRIORITY - 1);
        mWorker.start();
    }

    /** Stops the worker thread and releases ONNX resources. Safe to call repeatedly. */
    public synchronized void stop() {
        mAlive = false;
        Thread w = mWorker;
        if (w != null) {
            w.interrupt(); // wake from Thread.sleep() immediately; no-op if in ONNX inference
            try { w.join(3000L); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
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
        AppLogger.i(TAG, "Worker started — " + MODEL_LABEL + " (threshold=" + DETECT_THRESHOLD + ")");

        final float[] audioWindow = new float[AUDIO_BUFFER_LEN];
        long lastEvalAt = 0L;
        long lastDiagLogAt = 0L;
        float diagMaxSinceLog = 0f;

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
            float windowPeak = 0f;
            synchronized (mRingLock) {
                long total = mTotalWritten;
                int  w     = mRingWrite;
                n = (int) Math.min(total, AUDIO_BUFFER_LEN);
                int start = (w - n + AUDIO_BUFFER_LEN) % AUDIO_BUFFER_LEN;
                for (int i = 0; i < n; i++) {
                    // Normalize int16 PCM → float32 [-1, 1] as expected by the
                    // openWakeWord melspectrogram model (mirrors openwakeword/utils.py:
                    // x = x.astype(np.float32) / 32768).
                    float s = mRing[(start + i) % AUDIO_BUFFER_LEN] / 32768f;
                    audioWindow[i] = s;
                    float a = s < 0f ? -s : s;
                    if (a > windowPeak) windowPeak = a;
                }
            }
            if (n < MIN_AUDIO_SAMPLES) continue;

            // perf — silence gate. Skip the expensive mel→emb→wake ONNX pipeline
            // when the entire 2.5 s window is silent. See SILENCE_GATE_FLOOR: this
            // can never drop a real utterance, it only reclaims idle CPU. We still
            // publish a 0 score so the UI level/score indicator stays live.
            if (SILENCE_GATE_FLOOR > 0f && windowPeak < SILENCE_GATE_FLOOR) {
                mLastScore = 0f;
                broadcastScore(0f, false);
                continue;
            }

            try {
                if (mFirstEvalAt == 0L) mFirstEvalAt = SystemClock.elapsedRealtime();
                float score = runOneEvalStreaming();
                // perf — transient on-device self-check: for the first SELF_CHECK_MS
                // of active audio, also run the reference full-recompute and log both
                // scores so detection parity can be confirmed by voice on the unit.
                if (SELF_CHECK_MS > 0L && SystemClock.elapsedRealtime() - mFirstEvalAt < SELF_CHECK_MS) {
                    float ref = runOneEvalReference(audioWindow, n);
                    AppLogger.d(TAG, String.format(java.util.Locale.US,
                            "selfcheck stream=%.3f ref=%.3f Δ=%.3f", score, ref, Math.abs(score - ref)));
                }
                mLastScore = score;
                long evalNow = SystemClock.elapsedRealtime();
                // v1.2.80 — rolling 30s peak. Decays naturally: if the
                // current peak is older than PEAK_WINDOW_MS or the new
                // score is higher, replace it.
                if (score > mPeakScore || (evalNow - mPeakAtMs) > PEAK_WINDOW_MS) {
                    mPeakScore = score;
                    mPeakAtMs  = evalNow;
                }
                if (score > diagMaxSinceLog) diagMaxSinceLog = score;
                boolean detected = false;
                if (score >= DETECT_THRESHOLD &&
                    (evalNow - mLastDetectMs) >= DETECT_COOLDOWN_MS) {
                    mLastDetectMs = evalNow;
                    detected = true;
                    AppLogger.i(TAG, "Wake word DETECTED — score=" + score + " label=" + MODEL_LABEL);
                    // Trigger post-wake transcription (no-op if no transcriber installed).
                    com.byd.dashcast.voice.VoiceService.triggerTranscription();
                }
                broadcastScore(score, detected);
                // v1.2.80 — surface a periodic diag line so the user can
                // see in the Logs tab whether the engine reacts at all,
                // even when no detection ever crosses the threshold.
                if (evalNow - lastDiagLogAt >= DIAG_LOG_INTERVAL_MS) {
                    AppLogger.d(TAG, String.format(java.util.Locale.US,
                            "diag: score=%.3f max5s=%.3f peak30s=%.3f thr=%.2f",
                            score, diagMaxSinceLog, mPeakScore, DETECT_THRESHOLD));
                    lastDiagLogAt = evalNow;
                    diagMaxSinceLog = 0f;
                }
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
        // Ensure native libs are on disk and loaded BEFORE OrtEnvironment is touched.
        // System.load(absolutePath) pre-empts OrtEnvironment's System.loadLibrary() call.
        com.byd.dashcast.voice.VoiceLibsManager.ensureLoaded(mAppCtx);

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

    /** Reference path: full-window recompute of melspec → 16 embeddings → wake.
     *  Retained as the ground-truth oracle for the streaming self-check; not on
     *  the steady-state hot path once {@link #SELF_CHECK_MS} elapses. */
    private float runOneEvalReference(float[] audio, int n) throws Exception {
        // 1) melspectrogram : (1, n_samples) float32 → (n_frames, 1, 1, 32)
        float[][] melspec = computeMelspec(audio, n);
        int nFrames = melspec.length;
        // We need at least EMB_WINDOW + (WAKE_WINDOW-1)*EMB_STRIDE = 76 + 120 = 196 mel frames.
        int needed = EMB_WINDOW + (WAKE_WINDOW - 1) * EMB_STRIDE;
        if (nFrames < needed) {
            AppLogger.d(TAG, "runOneEval: nFrames=" + nFrames + " < needed=" + needed + " — skipping");
            return mLastScore;
        }

        // 2) embedding : pack the last WAKE_WINDOW windows of EMB_WINDOW frames each (stride EMB_STRIDE)
        //    into mEmbInput — reused instance field to avoid 600 KB allocation per eval.
        for (int b = 0; b < WAKE_WINDOW; b++) {
            int endFrame   = nFrames - (WAKE_WINDOW - 1 - b) * EMB_STRIDE;
            int startFrame = endFrame - EMB_WINDOW;
            for (int j = 0; j < EMB_WINDOW; j++) {
                float[] row = melspec[startFrame + j];
                for (int k = 0; k < 32; k++) {
                    mEmbInput[b][j][k][0] = row[k];
                }
            }
        }
        try (OnnxTensor embT = OnnxTensor.createTensor(mEnv, mEmbInput)) {
            mEmbMap.put(mEmbInputName, embT);
            try (OrtSession.Result out = mSessEmb.run(mEmbMap)) {
                float[][][][] raw = (float[][][][]) out.get(0).getValue();
                // raw shape : (WAKE_WINDOW, 1, 1, 96) → copy into mWakeInput[0]
                for (int b = 0; b < WAKE_WINDOW; b++) {
                    System.arraycopy(raw[b][0][0], 0, mWakeInput[0][b], 0, 96);
                }
            }
        }

        // 3) wake : reuse mWakeInput — (1, 16, 96) float32 → (1, 1) sigmoid
        try (OnnxTensor wt = OnnxTensor.createTensor(mEnv, mWakeInput)) {
            mWakeMap.put(mWakeInputName, wt);
            try (OrtSession.Result out = mSessWake.run(mWakeMap)) {
                float[][] raw = (float[][]) out.get(0).getValue();
                return raw[0][0];
            }
        }
    }

    /**
     * Runs the melspectrogram model and returns the mel features in
     * {@code (n_frames, 32)} form with the openWakeWord normalisation
     * {@code y = x/10 + 2} applied (this transform matches what the
     * upstream library does before feeding the embedding model).
     */
    private float[][] computeMelspec(float[] audio, int n) throws Exception {
        // The model expects (batch=1, samples). FloatBuffer.wrap(audio, 0, n) provides a
        // positional slice (limit=n) so ONNX reads exactly n samples — eliminates Arrays.copyOf.
        long[] shape = { 1L, (long) n };
        try (OnnxTensor in = OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(audio, 0, n), shape)) {
            mMelMap.put(mMelInputName, in);
            try (OrtSession.Result out = mSessMel.run(mMelMap)) {
                // Actual output shape: (batch=1, channel=1, time, mels=32).
                // The time dimension is at index 2, NOT index 0 — raw.length is the
                // batch dim (always 1), so using raw.length as nFrames always gave 1
                // (< 196 needed) and triggered the early-return in runOneEval, keeping
                // the score permanently at 0.0.
                float[][][][] raw = (float[][][][]) out.get(0).getValue();
                int nFrames = raw[0][0].length;  // raw[batch][channel][time][mel]
                if (mMelBufFrames != nFrames) {
                    mMelBuf = new float[nFrames][32];
                    mMelBufFrames = nFrames;
                }
                float[][] mel = mMelBuf;
                for (int t = 0; t < nFrames; t++) {
                    float[] src = raw[0][0][t];  // shape (32,) — raw log-mel in dB
                    for (int k = 0; k < 32; k++) {
                        mel[t][k] = src[k] / 10f + 2f;  // openWakeWord normalization
                    }
                }
                return mel;
            }
        }
    }

    // ─── perf: streaming inference pipeline ────────────────────────────────

    /**
     * Streaming evaluation. The reference path recomputes the mel-spectrogram of
     * the whole 2.5 s window and all 16 embedding windows every tick (~90 %
     * redundant). This instead:
     *   1) feeds the mel model only a short audio tail and commits the new
     *      frames into a rolling mel-frame ring — interior frames are bit-exact
     *      vs the full-window result (MEL_WARMUP_FRAMES of left context,
     *      MEL_GUARD frames of right-context deferral);
     *   2) computes embeddings only for newly-available stride-grid windows
     *      (~1-2 per tick vs 16) into a rolling embedding ring;
     *   3) runs the fixed [1,16,96] wake head on the last 16 cached embeddings.
     * Windows are anchored to an absolute EMB_STRIDE grid (≤ 80 ms behind the
     * latest audio), which is what makes embeddings cacheable across ticks.
     */
    private float runOneEvalStreaming() throws Exception {
        advanceMel();
        advanceEmbeddings();
        if (mEmbHeadGrid < 0 || mEmbHeadGrid - mEmbContigFrom + 1 < WAKE_WINDOW) {
            return mLastScore; // still warming up — not yet 16 contiguous embeddings
        }
        final long head = mEmbHeadGrid;
        for (int b = 0; b < WAKE_WINDOW; b++) {
            long k = head - (WAKE_WINDOW - 1) + b;
            float[] e = mEmbRing[(int) Math.floorMod(k, (long) EMB_CACHE)];
            System.arraycopy(e, 0, mWakeInput[0][b], 0, 96);
        }
        try (OnnxTensor wt = OnnxTensor.createTensor(mEnv, mWakeInput)) {
            mWakeMap.put(mWakeInputName, wt);
            try (OrtSession.Result out = mSessWake.run(mWakeMap)) {
                float[][] raw = (float[][]) out.get(0).getValue();
                return raw[0][0];
            }
        }
    }

    /** Feeds a short audio tail to the mel model and commits the new (fully
     *  contextualised) frames into mMelRing. */
    private void advanceMel() throws Exception {
        final long feedStartFrame;
        final int  feedLen;
        synchronized (mRingLock) {
            long total = mTotalWritten;
            long bufStartSample = Math.max(0L, total - AUDIO_BUFFER_LEN);
            long bufStartFrame  = (bufStartSample + MEL_HOP - 1) / MEL_HOP; // ceil → never read evicted samples
            if (mMelTotalFrames < bufStartFrame) mMelTotalFrames = bufStartFrame; // cold start / stall jump
            feedStartFrame  = Math.max(bufStartFrame, mMelTotalFrames - MEL_WARMUP_FRAMES);
            long feedStartSample = feedStartFrame * MEL_HOP;
            feedLen = (int) (total - feedStartSample);
            if (feedLen < EMB_WINDOW * MEL_HOP) return; // not enough audio for one window yet
            int start = (int) Math.floorMod(feedStartSample, (long) AUDIO_BUFFER_LEN);
            for (int i = 0; i < feedLen; i++) {
                mMelFeed[i] = mRing[(start + i) % AUDIO_BUFFER_LEN] / 32768f;
            }
        }
        long[] shape = { 1L, (long) feedLen };
        try (OnnxTensor in = OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(mMelFeed, 0, feedLen), shape)) {
            mMelMap.put(mMelInputName, in);
            try (OrtSession.Result out = mSessMel.run(mMelMap)) {
                float[][][][] raw = (float[][][][]) out.get(0).getValue(); // [1,1,time,32]
                int nf = raw[0][0].length;
                long lastCommit = feedStartFrame + nf - MEL_GUARD; // exclusive
                for (long f = mMelTotalFrames; f < lastCommit; f++) {
                    int localIdx = (int) (f - feedStartFrame);
                    if (localIdx < 0 || localIdx >= nf) continue;
                    float[] src = raw[0][0][localIdx];               // (32,) raw log-mel dB
                    float[] dst = mMelRing[(int) Math.floorMod(f, (long) MEL_RING_FRAMES)];
                    for (int k = 0; k < 32; k++) dst[k] = src[k] / 10f + 2f; // openWakeWord normalization
                }
                if (lastCommit > mMelTotalFrames) mMelTotalFrames = lastCommit;
            }
        }
    }

    /** Computes embeddings for any newly-available stride-grid windows and
     *  maintains the contiguous-valid run used by the wake head. */
    private void advanceEmbeddings() throws Exception {
        long maxK = (mMelTotalFrames - EMB_WINDOW) / EMB_STRIDE;
        if (maxK < 0) return;
        long ringFloorFrame = Math.max(0L, mMelTotalFrames - MEL_RING_FRAMES);
        long minK  = (ringFloorFrame + EMB_STRIDE - 1) / EMB_STRIDE;   // ceil → first window fully inside the ring
        long fromK = Math.max(mEmbHeadGrid + 1, minK);
        if (mEmbHeadGrid < 0 || fromK > mEmbHeadGrid + 1) {
            mEmbContigFrom = fromK; // cold start, or ring overflowed past the old head (worker stalled)
        }
        for (long k = fromK; k <= maxK; k++) {
            embedWindow(k);
        }
        if (maxK > mEmbHeadGrid)        mEmbHeadGrid   = maxK;
        if (mEmbContigFrom < minK)      mEmbContigFrom = minK; // windows below the ring floor are no longer valid
    }

    /** Runs the embedding model (batch 1) for one stride-grid window and caches it. */
    private void embedWindow(long k) throws Exception {
        long startFrame = k * EMB_STRIDE;
        for (int j = 0; j < EMB_WINDOW; j++) {
            float[] mel = mMelRing[(int) Math.floorMod(startFrame + j, (long) MEL_RING_FRAMES)];
            for (int c = 0; c < 32; c++) mEmb1Input[0][j][c][0] = mel[c];
        }
        try (OnnxTensor embT = OnnxTensor.createTensor(mEnv, mEmb1Input)) {
            mEmbMap.put(mEmbInputName, embT);
            try (OrtSession.Result out = mSessEmb.run(mEmbMap)) {
                float[][][][] raw = (float[][][][]) out.get(0).getValue(); // [1,1,1,96]
                System.arraycopy(raw[0][0][0], 0, mEmbRing[(int) Math.floorMod(k, (long) EMB_CACHE)], 0, 96);
            }
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
        // Reuse mScoreIntent — LocalBroadcastManager delivers synchronously so reuse is safe.
        mScoreIntent.putExtra(EXTRA_WW_SCORE,   score);
        mScoreIntent.putExtra(EXTRA_WW_LAST_MS, mLastDetectMs);
        mScoreIntent.putExtra(EXTRA_WW_LABEL,   MODEL_LABEL);
        // v1.2.80 — extra diag payload for the UI peak indicator.
        mScoreIntent.putExtra(EXTRA_WW_PEAK_SCORE,  mPeakScore);
        mScoreIntent.putExtra(EXTRA_WW_PEAK_AGE_MS, mPeakAtMs == 0L ? -1L
                : (SystemClock.elapsedRealtime() - mPeakAtMs));
        LocalBroadcastManager.getInstance(mAppCtx).sendBroadcast(mScoreIntent);
        // Detection beep / haptic etc. is the UI's responsibility — keep the
        // engine side-effect-free beyond the broadcast.
        if (detected) { /* no-op : flag is conveyed through ww_last_ms == now */ }
    }
}
