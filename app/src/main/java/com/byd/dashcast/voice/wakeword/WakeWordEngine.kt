package com.byd.dashcast.voice.wakeword

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.SystemClock

import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.voice.VoiceLibsManager
import com.byd.dashcast.voice.VoiceService

import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * v1.2.50-beta — Voice PoC step 2/3.
 *
 * Lightweight wrapper around the openWakeWord ONNX pipeline :
 *   raw PCM 16 kHz  →  melspectrogram.onnx
 *                  →  embedding_model.onnx (Google speech_embedding)
 *                  →  hey_jarvis_v0.1.onnx  (placeholder for "Ok DashCast")
 *
 * The engine owns its own worker thread. The caller (the VoiceService
 * capture thread) merely *pushes* 16-bit PCM frames via
 * [feed]; the heavy lifting is decoupled to keep the
 * capture loop's tick stable. If the engine is busy when a new frame
 * arrives, the new frame is appended to the rolling audio buffer but the
 * eval cadence is throttled (one inference per ~100 ms).
 *
 * **Scope and isolation** :
 *  - This class is the only consumer of the ONNX Runtime dependency.
 *  - It is only instantiated from the Diagnostic Voice tab when the user
 *    toggles the "Wake word" switch ON. Production code paths never load
 *    any ONNX session.
 *  - Every `OrtSession.run` call is wrapped in try/catch; on
 *    failure the engine flips into a degraded "unavailable" mode and
 *    broadcasts [ACTION_WAKEWORD] with `EXTRA_WW_UNAVAILABLE=true` so the
 *    UI can show a graceful message instead of crashing the foreground
 *    service.
 *
 * **Streaming math** (mirrors openwakeword/utils.py recompute path) :
 * we maintain a sliding 2.5 s buffer of int16 audio. Every ~100 ms we run :
 *  1. melspectrogram on the full buffer → `(n_frames, 32)` mel
 *     features where `n_frames ≈ ceil(samples/160) - 3`.
 *  2. 16 parallel embedding inferences over 76-frame windows stepped by
 *     8 (last 16 windows that fit in the buffer) → `(16, 96)`.
 *  3. hey_jarvis_v0.1 on that `(1, 16, 96)` tensor → single
 *     sigmoid score in [0..1]. [DETECT_THRESHOLD] = 0.5.
 * A cool-down of [DETECT_COOLDOWN_MS] ms is enforced between two
 * "detected" events so a held wake word does not produce a burst.
 */
class WakeWordEngine(ctx: Context) : VoiceService.SampleConsumer {

    // ─── ONNX state (worker-thread only) ───────────────────────────────────
    // Proof for the `!!` uses in the inference methods below: these are all
    // assigned by initOnnx() at the very top of workerLoop(). If init throws,
    // workerLoop() calls failOnnxInit() and returns immediately, so none of
    // runOneEval*/computeMelspec/advanceMel/advanceEmbeddings/embedWindow can
    // ever run with a null session/env/input-name. The non-null assertions
    // are backed by that lifecycle invariant, not by hope.
    private var mEnv: OrtEnvironment? = null
    private var mSessMel: OrtSession? = null
    private var mSessEmb: OrtSession? = null
    private var mSessWake: OrtSession? = null
    private var mMelInputName: String? = null
    private var mEmbInputName: String? = null
    private var mWakeInputName: String? = null

    // ─── Audio ring buffer (write from feed(), read from worker) ───────────

    private val mRing = ShortArray(AUDIO_BUFFER_LEN)
    @Volatile private var mRingWrite = 0            // total written modulo AUDIO_BUFFER_LEN
    @Volatile private var mTotalWritten = 0L        // monotonic count of written samples
    private val mRingLock = Any()

    // ─── Worker thread ─────────────────────────────────────────────────────

    private val mAppCtx: Context = ctx.applicationContext
    private var mWorker: Thread? = null
    @Volatile private var mAlive = false
    @Volatile private var mUnavailable = false
    @Volatile private var mLastDetectMs = 0L
    @Volatile private var mLastScore = 0f
    // v1.2.80 — rolling peak over PEAK_WINDOW_MS for diag visibility.
    @Volatile private var mPeakScore = 0f
    @Volatile private var mPeakAtMs = 0L

    // ─── Pre-allocated inference buffers (worker-thread only) ─────────────────
    // Reused on every eval cycle to eliminate ~770 KB of heap allocation per 100ms.
    private var mMelBuf: Array<FloatArray>? = null
    private var mMelBufFrames = -1
    private val mEmbInput = Array(WAKE_WINDOW) { Array(EMB_WINDOW) { Array(32) { FloatArray(1) } } }
    private val mWakeInput = Array(1) { Array(WAKE_WINDOW) { FloatArray(96) } }
    private val mMelMap = HashMap<String, OnnxTensor>(2)
    private val mEmbMap = HashMap<String, OnnxTensor>(2)
    private val mWakeMap = HashMap<String, OnnxTensor>(2)
    private val mScoreIntent = Intent(ACTION_WAKEWORD)

    // ─── perf: streaming inference state (worker-thread only) ──────────────────
    private val mMelFeed = FloatArray(AUDIO_BUFFER_LEN)                       // normalized audio fed to mel
    private val mMelRing = Array(MEL_RING_FRAMES) { FloatArray(32) }          // rolling normalized mel frames
    private var mMelTotalFrames = 0L                                          // committed mel frames (absolute)
    private val mEmb1Input = Array(1) { Array(EMB_WINDOW) { Array(32) { FloatArray(1) } } } // batch-1 embedding input
    private val mEmbRing = Array(EMB_CACHE) { FloatArray(96) }                // rolling embeddings (grid k → k % EMB_CACHE)
    private var mEmbHeadGrid = -1L                                            // highest computed grid index
    private var mEmbContigFrom = 0L                                           // lowest grid index of the contiguous valid run
    private var mFirstEvalAt = 0L                                             // self-check window anchor

    /** Returns the most recent sigmoid score (for UI polling, optional). */
    fun lastScore(): Float = mLastScore
    /** Returns the most recent detection timestamp, or 0 if none. */
    fun lastDetectMs(): Long = mLastDetectMs
    /** True once the engine has hit an unrecoverable error and is degraded. */
    fun isUnavailable(): Boolean = mUnavailable

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    /** Asynchronously initialises ONNX sessions and starts the worker thread. */
    @Synchronized
    fun start() {
        if (mAlive) {
            AppLogger.d(TAG, "start() ignored, already running")
            return
        }
        mAlive = true
        mUnavailable = false
        mLastDetectMs = 0L
        val worker = Thread(::workerLoop, "wakeword-engine")
        mWorker = worker
        worker.priority = Thread.NORM_PRIORITY - 1
        worker.start()
    }

    /** Stops the worker thread and releases ONNX resources. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        mAlive = false
        val w = mWorker
        if (w != null) {
            w.interrupt() // wake from Thread.sleep() immediately; no-op if in ONNX inference
            try { w.join(3000L) } catch (ignore: InterruptedException) { Thread.currentThread().interrupt() }
            mWorker = null
            if (w.isAlive) {
                // Worker is still inside a native OrtSession.run (interrupt is a no-op there).
                // Closing the sessions now would be a native use-after-free (SIGSEGV, uncatchable
                // by the worker's try/catch). Abandon them instead — OrtSession's finalizer
                // reclaims the native memory once the stuck worker eventually returns and is GC'd.
                // The common case (worker exits within the 3 s join) still releases below.
                AppLogger.w(TAG, "stop(): worker still in inference after 3s — abandoning ONNX sessions to avoid a native use-after-free")
                return
            }
        }
        releaseOnnx()
    }

    // ─── SampleConsumer plumbing ───────────────────────────────────────────

    override fun onFrame(pcm: ShortArray, n: Int) {
        if (!mAlive || mUnavailable || n <= 0) return
        // Lock-free is tempting but a short critical section keeps writes
        // atomic against the worker's snapshot read. 800-sample copies cost
        // well under 50 µs on any ARM target.
        synchronized(mRingLock) {
            var w = mRingWrite
            for (i in 0 until n) {
                mRing[w] = pcm[i]
                w = (w + 1) % AUDIO_BUFFER_LEN
            }
            mRingWrite = w
            mTotalWritten += n
        }
    }

    /** Back-compat single-argument feed retained for any direct callers. */
    fun feed(pcm: ShortArray, n: Int) = onFrame(pcm, n)

    // ─── Worker loop ───────────────────────────────────────────────────────

    private fun workerLoop() {
        try {
            initOnnx()
        } catch (t: Throwable) {
            failOnnxInit(t)
            return
        }
        AppLogger.i(TAG, "Worker started — $MODEL_LABEL (threshold=$DETECT_THRESHOLD)")

        val audioWindow = FloatArray(AUDIO_BUFFER_LEN)
        // Raw int16 snapshot taken under mRingLock; normalized into audioWindow OUTSIDE the
        // lock so onFrame's ring writes aren't blocked by the per-sample float divide (F13).
        val rawWindow = ShortArray(AUDIO_BUFFER_LEN)
        var lastEvalAt = 0L
        var lastDiagLogAt = 0L
        var diagMaxSinceLog = 0f

        while (mAlive) {
            val now = SystemClock.elapsedRealtime()
            val delta = EVAL_INTERVAL_MS - (now - lastEvalAt)
            if (delta > 0) {
                try { Thread.sleep(delta) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            lastEvalAt = SystemClock.elapsedRealtime()

            // Snapshot the latest min(MIN_AUDIO_SAMPLES, total) samples
            // chronologically into audioWindow[0..n-1].
            var n: Int
            synchronized(mRingLock) {
                val total = mTotalWritten
                val w = mRingWrite
                n = Math.min(total, AUDIO_BUFFER_LEN.toLong()).toInt()
                val start = (w - n + AUDIO_BUFFER_LEN) % AUDIO_BUFFER_LEN
                // Under the lock: raw int16 copy only (no FP work) so onFrame's 20Hz ring
                // writes are blocked for the minimum time. Normalization + peak run below,
                // outside the lock — previously the full copy+divide+abs ran inside it.
                for (i in 0 until n) {
                    rawWindow[i] = mRing[(start + i) % AUDIO_BUFFER_LEN]
                }
            }
            if (n < MIN_AUDIO_SAMPLES) continue

            // Normalize int16 PCM → float32 [-1, 1] as expected by the openWakeWord
            // melspectrogram model (mirrors openwakeword/utils.py: x /= 32768) and compute
            // the silence-gate peak — OUTSIDE the ring lock. Identical audioWindow/peak result.
            var windowPeak = 0f
            for (i in 0 until n) {
                val s = rawWindow[i] / 32768f
                audioWindow[i] = s
                val a = if (s < 0f) -s else s
                if (a > windowPeak) windowPeak = a
            }

            // perf — silence gate. Skip the expensive mel→emb→wake ONNX pipeline
            // when the entire 2.5 s window is silent. See SILENCE_GATE_FLOOR: this
            // can never drop a real utterance, it only reclaims idle CPU. We still
            // publish a 0 score so the UI level/score indicator stays live.
            if (SILENCE_GATE_FLOOR > 0f && windowPeak < SILENCE_GATE_FLOOR) {
                mLastScore = 0f
                broadcastScore(0f, false)
                continue
            }

            try {
                if (mFirstEvalAt == 0L) mFirstEvalAt = SystemClock.elapsedRealtime()
                val score = runOneEvalStreaming()
                // perf — transient on-device self-check: for the first SELF_CHECK_MS
                // of active audio, also run the reference full-recompute and log both
                // scores so detection parity can be confirmed by voice on the unit.
                if (SELF_CHECK_MS > 0L && SystemClock.elapsedRealtime() - mFirstEvalAt < SELF_CHECK_MS) {
                    val ref = runOneEvalReference(audioWindow, n)
                    AppLogger.d(TAG, String.format(java.util.Locale.US,
                            "selfcheck stream=%.3f ref=%.3f Δ=%.3f", score, ref, Math.abs(score - ref)))
                }
                mLastScore = score
                val evalNow = SystemClock.elapsedRealtime()
                // v1.2.80 — rolling 30s peak. Decays naturally: if the
                // current peak is older than PEAK_WINDOW_MS or the new
                // score is higher, replace it.
                if (score > mPeakScore || (evalNow - mPeakAtMs) > PEAK_WINDOW_MS) {
                    mPeakScore = score
                    mPeakAtMs = evalNow
                }
                if (score > diagMaxSinceLog) diagMaxSinceLog = score
                var detected = false
                if (score >= DETECT_THRESHOLD &&
                    (evalNow - mLastDetectMs) >= DETECT_COOLDOWN_MS) {
                    mLastDetectMs = evalNow
                    detected = true
                    AppLogger.i(TAG, "Wake word DETECTED — score=$score label=$MODEL_LABEL")
                    // Trigger post-wake transcription (no-op if no transcriber installed).
                    VoiceService.triggerTranscription()
                }
                broadcastScore(score, detected)
                // v1.2.80 — surface a periodic diag line so the user can
                // see in the Logs tab whether the engine reacts at all,
                // even when no detection ever crosses the threshold.
                if (evalNow - lastDiagLogAt >= DIAG_LOG_INTERVAL_MS) {
                    AppLogger.d(TAG, String.format(java.util.Locale.US,
                            "diag: score=%.3f max5s=%.3f peak30s=%.3f thr=%.2f",
                            score, diagMaxSinceLog, mPeakScore, DETECT_THRESHOLD))
                    lastDiagLogAt = evalNow
                    diagMaxSinceLog = 0f
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Eval failed: ${t.javaClass.simpleName}: ${t.message}")
                // Single eval failures should not flip the engine to unavailable —
                // they are usually transient (tensor allocation pressure).
            }
        }
        AppLogger.i(TAG, "Worker stopped")
    }

    // ─── ONNX init & teardown ──────────────────────────────────────────────

    private fun initOnnx() {
        // Ensure native libs are on disk and loaded BEFORE OrtEnvironment is touched.
        // System.load(absolutePath) pre-empts OrtEnvironment's System.loadLibrary() call.
        VoiceLibsManager.ensureLoaded(mAppCtx)

        val am = mAppCtx.assets
        val melBytes = readAssetBytes(am, ASSET_MEL)
        val embBytes = readAssetBytes(am, ASSET_EMB)
        val wakeBytes = readAssetBytes(am, ASSET_WAKE)

        mEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        // Single-threaded execution keeps CPU usage predictable on DiLink
        // devices (we don't want the wake-word engine to starve the cluster
        // mirror pipeline running in another thread).
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)

        // mEnv just assigned above (non-null on every path that reaches here).
        mSessMel = mEnv!!.createSession(melBytes, opts)
        mSessEmb = mEnv!!.createSession(embBytes, opts)
        mSessWake = mEnv!!.createSession(wakeBytes, opts)

        // Resolve the actual input names from the model graphs : openWakeWord
        // models use idiomatic-PyTorch names like "input", "input_1", "x.1".
        mMelInputName = mSessMel!!.inputNames.iterator().next()
        mEmbInputName = mSessEmb!!.inputNames.iterator().next()
        mWakeInputName = mSessWake!!.inputNames.iterator().next()
        AppLogger.i(TAG, "ONNX ready — mel='$mMelInputName' emb='$mEmbInputName' wake='$mWakeInputName'")
    }

    private fun failOnnxInit(t: Throwable) {
        AppLogger.e(TAG, "ONNX init failed: ${t.javaClass.simpleName}: ${t.message}")
        mUnavailable = true
        releaseOnnx()
        val i = Intent(ACTION_WAKEWORD)
        i.putExtra(EXTRA_WW_UNAVAILABLE, true)
        i.putExtra(EXTRA_WW_REASON, "${t.javaClass.simpleName}: ${t.message}")
        i.putExtra(EXTRA_WW_LABEL, MODEL_LABEL)
        LocalBroadcastManager.getInstance(mAppCtx).sendBroadcast(i)
    }

    private fun releaseOnnx() {
        try { mSessWake?.close() } catch (ignore: Throwable) {}
        try { mSessEmb?.close() } catch (ignore: Throwable) {}
        try { mSessMel?.close() } catch (ignore: Throwable) {}
        mSessWake = null
        mSessEmb = null
        mSessMel = null
        // OrtEnvironment is a process-singleton, do NOT close it.
    }

    // ─── Inference pipeline ────────────────────────────────────────────────

    /** Reference path: full-window recompute of melspec → 16 embeddings → wake.
     *  Retained as the ground-truth oracle for the streaming self-check; not on
     *  the steady-state hot path once [SELF_CHECK_MS] elapses. */
    @Suppress("UNCHECKED_CAST")
    private fun runOneEvalReference(audio: FloatArray, n: Int): Float {
        // 1) melspectrogram : (1, n_samples) float32 → (n_frames, 1, 1, 32)
        val melspec = computeMelspec(audio, n)
        val nFrames = melspec.size
        // We need at least EMB_WINDOW + (WAKE_WINDOW-1)*EMB_STRIDE = 76 + 120 = 196 mel frames.
        val needed = EMB_WINDOW + (WAKE_WINDOW - 1) * EMB_STRIDE
        if (nFrames < needed) {
            AppLogger.d(TAG, "runOneEval: nFrames=$nFrames < needed=$needed — skipping")
            return mLastScore
        }

        // 2) embedding : pack the last WAKE_WINDOW windows of EMB_WINDOW frames each (stride EMB_STRIDE)
        //    into mEmbInput — reused instance field to avoid 600 KB allocation per eval.
        for (b in 0 until WAKE_WINDOW) {
            val endFrame = nFrames - (WAKE_WINDOW - 1 - b) * EMB_STRIDE
            val startFrame = endFrame - EMB_WINDOW
            for (j in 0 until EMB_WINDOW) {
                val row = melspec[startFrame + j]
                for (k in 0 until 32) {
                    mEmbInput[b][j][k][0] = row[k]
                }
            }
        }
        OnnxTensor.createTensor(mEnv, mEmbInput).use { embT ->
            mEmbMap.put(mEmbInputName!!, embT)
            mSessEmb!!.run(mEmbMap).use { out ->
                val raw = out.get(0).value as Array<Array<Array<FloatArray>>>
                // raw shape : (WAKE_WINDOW, 1, 1, 96) → copy into mWakeInput[0]
                for (b in 0 until WAKE_WINDOW) {
                    System.arraycopy(raw[b][0][0], 0, mWakeInput[0][b], 0, 96)
                }
            }
        }

        // 3) wake : reuse mWakeInput — (1, 16, 96) float32 → (1, 1) sigmoid
        OnnxTensor.createTensor(mEnv, mWakeInput).use { wt ->
            mWakeMap.put(mWakeInputName!!, wt)
            mSessWake!!.run(mWakeMap).use { out ->
                val raw = out.get(0).value as Array<FloatArray>
                return raw[0][0]
            }
        }
    }

    /**
     * Runs the melspectrogram model and returns the mel features in
     * `(n_frames, 32)` form with the openWakeWord normalisation
     * `y = x/10 + 2` applied (this transform matches what the
     * upstream library does before feeding the embedding model).
     */
    @Suppress("UNCHECKED_CAST")
    private fun computeMelspec(audio: FloatArray, n: Int): Array<FloatArray> {
        // The model expects (batch=1, samples). FloatBuffer.wrap(audio, 0, n) provides a
        // positional slice (limit=n) so ONNX reads exactly n samples — eliminates Arrays.copyOf.
        val shape = longArrayOf(1L, n.toLong())
        OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(audio, 0, n), shape).use { input ->
            mMelMap.put(mMelInputName!!, input)
            mSessMel!!.run(mMelMap).use { out ->
                // Actual output shape: (batch=1, channel=1, time, mels=32).
                // The time dimension is at index 2, NOT index 0 — raw.length is the
                // batch dim (always 1), so using raw.length as nFrames always gave 1
                // (< 196 needed) and triggered the early-return in runOneEval, keeping
                // the score permanently at 0.0.
                val raw = out.get(0).value as Array<Array<Array<FloatArray>>>
                val nFrames = raw[0][0].size  // raw[batch][channel][time][mel]
                if (mMelBufFrames != nFrames) {
                    mMelBuf = Array(nFrames) { FloatArray(32) }
                    mMelBufFrames = nFrames
                }
                // Non-null here: assigned above on the first call (mMelBufFrames starts
                // at -1, which never equals a real nFrames), and retained across later
                // calls that keep the same nFrames.
                val mel = mMelBuf!!
                for (t in 0 until nFrames) {
                    val src = raw[0][0][t]  // shape (32,) — raw log-mel in dB
                    for (k in 0 until 32) {
                        mel[t][k] = src[k] / 10f + 2f  // openWakeWord normalization
                    }
                }
                return mel
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
    @Suppress("UNCHECKED_CAST")
    private fun runOneEvalStreaming(): Float {
        advanceMel()
        advanceEmbeddings()
        if (mEmbHeadGrid < 0 || mEmbHeadGrid - mEmbContigFrom + 1 < WAKE_WINDOW) {
            return mLastScore // still warming up — not yet 16 contiguous embeddings
        }
        val head = mEmbHeadGrid
        for (b in 0 until WAKE_WINDOW) {
            val k = head - (WAKE_WINDOW - 1) + b
            val e = mEmbRing[Math.floorMod(k, EMB_CACHE.toLong()).toInt()]
            System.arraycopy(e, 0, mWakeInput[0][b], 0, 96)
        }
        OnnxTensor.createTensor(mEnv, mWakeInput).use { wt ->
            mWakeMap.put(mWakeInputName!!, wt)
            mSessWake!!.run(mWakeMap).use { out ->
                val raw = out.get(0).value as Array<FloatArray>
                return raw[0][0]
            }
        }
    }

    /** Feeds a short audio tail to the mel model and commits the new (fully
     *  contextualised) frames into mMelRing. */
    @Suppress("UNCHECKED_CAST")
    private fun advanceMel() {
        val feedStartFrame: Long
        val feedLen: Int
        synchronized(mRingLock) {
            val total = mTotalWritten
            val bufStartSample = Math.max(0L, total - AUDIO_BUFFER_LEN)
            val bufStartFrame = (bufStartSample + MEL_HOP - 1) / MEL_HOP // ceil → never read evicted samples
            if (mMelTotalFrames < bufStartFrame) mMelTotalFrames = bufStartFrame // cold start / stall jump
            feedStartFrame = Math.max(bufStartFrame, mMelTotalFrames - MEL_WARMUP_FRAMES)
            val feedStartSample = feedStartFrame * MEL_HOP
            feedLen = (total - feedStartSample).toInt()
            if (feedLen < EMB_WINDOW * MEL_HOP) return // not enough audio for one window yet
            val start = Math.floorMod(feedStartSample, AUDIO_BUFFER_LEN.toLong()).toInt()
            for (i in 0 until feedLen) {
                mMelFeed[i] = mRing[(start + i) % AUDIO_BUFFER_LEN] / 32768f
            }
        }
        val shape = longArrayOf(1L, feedLen.toLong())
        OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(mMelFeed, 0, feedLen), shape).use { input ->
            mMelMap.put(mMelInputName!!, input)
            mSessMel!!.run(mMelMap).use { out ->
                // Zero-copy read: the mel output is a flat [1,1,time,32] float tensor.
                // Reading it as a FloatBuffer avoids materialising the nested
                // Array<Array<Array<FloatArray>>> object graph (~time FloatArrays + wrappers)
                // every ~160 ms tick. Row-major C order: frame f, channel k -> f*32 + k.
                val melBuf = (out.get(0) as OnnxTensor).floatBuffer
                val nf = melBuf.remaining() / 32
                val lastCommit = feedStartFrame + nf - MEL_GUARD // exclusive
                for (f in mMelTotalFrames until lastCommit) {
                    val localIdx = (f - feedStartFrame).toInt()
                    if (localIdx < 0 || localIdx >= nf) continue
                    val base = localIdx * 32                        // (32,) raw log-mel dB row
                    val dst = mMelRing[Math.floorMod(f, MEL_RING_FRAMES.toLong()).toInt()]
                    for (k in 0 until 32) dst[k] = melBuf.get(base + k) / 10f + 2f // openWakeWord normalization
                }
                if (lastCommit > mMelTotalFrames) mMelTotalFrames = lastCommit
            }
        }
    }

    /** Computes embeddings for any newly-available stride-grid windows and
     *  maintains the contiguous-valid run used by the wake head. */
    private fun advanceEmbeddings() {
        val maxK = (mMelTotalFrames - EMB_WINDOW) / EMB_STRIDE
        if (maxK < 0) return
        val ringFloorFrame = Math.max(0L, mMelTotalFrames - MEL_RING_FRAMES)
        val minK = (ringFloorFrame + EMB_STRIDE - 1) / EMB_STRIDE   // ceil → first window fully inside the ring
        val fromK = Math.max(mEmbHeadGrid + 1, minK)
        if (mEmbHeadGrid < 0 || fromK > mEmbHeadGrid + 1) {
            mEmbContigFrom = fromK // cold start, or ring overflowed past the old head (worker stalled)
        }
        for (k in fromK..maxK) {
            embedWindow(k)
        }
        if (maxK > mEmbHeadGrid) mEmbHeadGrid = maxK
        if (mEmbContigFrom < minK) mEmbContigFrom = minK // windows below the ring floor are no longer valid
    }

    /** Runs the embedding model (batch 1) for one stride-grid window and caches it. */
    @Suppress("UNCHECKED_CAST")
    private fun embedWindow(k: Long) {
        val startFrame = k * EMB_STRIDE
        for (j in 0 until EMB_WINDOW) {
            val mel = mMelRing[Math.floorMod(startFrame + j, MEL_RING_FRAMES.toLong()).toInt()]
            for (c in 0 until 32) mEmb1Input[0][j][c][0] = mel[c]
        }
        OnnxTensor.createTensor(mEnv, mEmb1Input).use { embT ->
            mEmbMap.put(mEmbInputName!!, embT)
            mSessEmb!!.run(mEmbMap).use { out ->
                // Zero-copy read of the [1,1,1,96] embedding output straight into the
                // ring slot — no nested-array materialisation per stride window.
                (out.get(0) as OnnxTensor).floatBuffer
                    .get(mEmbRing[Math.floorMod(k, EMB_CACHE.toLong()).toInt()], 0, 96)
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun broadcastScore(score: Float, detected: Boolean) {
        // Reuse mScoreIntent — LocalBroadcastManager delivers synchronously so reuse is safe.
        mScoreIntent.putExtra(EXTRA_WW_SCORE, score)
        mScoreIntent.putExtra(EXTRA_WW_LAST_MS, mLastDetectMs)
        mScoreIntent.putExtra(EXTRA_WW_LABEL, MODEL_LABEL)
        // v1.2.80 — extra diag payload for the UI peak indicator.
        mScoreIntent.putExtra(EXTRA_WW_PEAK_SCORE, mPeakScore)
        mScoreIntent.putExtra(EXTRA_WW_PEAK_AGE_MS, if (mPeakAtMs == 0L) -1L
                else (SystemClock.elapsedRealtime() - mPeakAtMs))
        LocalBroadcastManager.getInstance(mAppCtx).sendBroadcast(mScoreIntent)
        // Detection beep / haptic etc. is the UI's responsibility — keep the
        // engine side-effect-free beyond the broadcast.
        if (detected) { /* no-op : flag is conveyed through ww_last_ms == now */ }
    }

    companion object {
        private const val TAG = "WakeWordEngine"

        // ─── Broadcast contract ────────────────────────────────────────────────

        const val ACTION_WAKEWORD = "com.byd.dashcast.voice.WAKEWORD"

        /** Latest sigmoid score in [0..1], float. */
        const val EXTRA_WW_SCORE = "ww_score"
        /** Epoch (SystemClock.elapsedRealtime) of the last >threshold detection. 0 if none. */
        const val EXTRA_WW_LAST_MS = "ww_last_ms"
        /** Detection model label, e.g. "hey_jarvis_v0.1". */
        const val EXTRA_WW_LABEL = "ww_label"
        /** Set to true once when the engine flips to degraded mode. */
        const val EXTRA_WW_UNAVAILABLE = "ww_unavailable"
        /** Optional human-readable failure reason. */
        const val EXTRA_WW_REASON = "ww_reason"
        /** v1.2.80 — rolling peak score over the last [PEAK_WINDOW_MS]. */
        const val EXTRA_WW_PEAK_SCORE = "ww_peak_score"
        /** v1.2.80 — age in ms of that peak (0 = right now). */
        const val EXTRA_WW_PEAK_AGE_MS = "ww_peak_age_ms"

        // ─── Pipeline parameters ───────────────────────────────────────────────

        /** Sliding audio buffer length, in samples. 2.5 s gives ample headroom over the 2.04 s minimum. */
        private const val AUDIO_BUFFER_LEN = 16_000 * 5 / 2 // 40 000

        /** Minimum samples accumulated before the first eval is meaningful. */
        private const val MIN_AUDIO_SAMPLES = 16_000 * 2   // 2 s

        /** Throttle : at most one full eval every this many ms.
         *  perf — raised 100 → 160 ms (10 Hz → ~6.3 Hz). With a 2.5 s window and a
         *  1.5 s detect cool-down, the wake word still gets ~15 evals while it sits
         *  in the buffer; worst-case added latency +60 ms (imperceptible). Cuts the
         *  continuous wake-word CPU by ~37 % outright. */
        private const val EVAL_INTERVAL_MS = 160L

        /** perf — silence gate. If the loudest sample in the WHOLE 2.5 s window is
         *  below this normalised-float floor (~-49 dBFS), the audio is genuine
         *  silence and we skip the ONNX pipeline entirely. Safe by construction:
         *  because the gate inspects the full window, a wake word (and its 2.5 s
         *  echo in the ring) keeps the engine active for 2.5 s, so a real utterance
         *  can never be skipped. A signal below this floor is also below the model's
         *  own sensitivity, so no detection the model could have made is lost.
         *  Set to 0f to disable. */
        private const val SILENCE_GATE_FLOOR = 0.0035f

        // ─── perf: streaming inference parameters ──────────────────────────────
        /** Samples per mel frame (100 Hz framing → 160 samples at 16 kHz). */
        private const val MEL_HOP = 160
        /** Rolling mel-frame cache capacity. Must exceed the 204 frames the wake
         *  head spans (16*8 + 76) plus a few ticks of advance. */
        private const val MEL_RING_FRAMES = 320
        /** Rolling embedding cache (stride-grid windows). Must exceed WAKE_WINDOW. */
        private const val EMB_CACHE = 24
        /** Left-context frames discarded from each short mel feed. Generously larger
         *  than any plausible STFT window, so the frames we keep have full left
         *  context and are bit-identical to the full-window computation. */
        private const val MEL_WARMUP_FRAMES = 48
        /** Right-context guard: the newest frames of a feed may use padded/reflected
         *  right context (centered STFT), so we defer committing them by one tick
         *  until real future audio exists. 3 frames = 30 ms covers any 16 kHz STFT. */
        private const val MEL_GUARD = 3
        /** Transient on-device self-check: run the reference full-recompute path
         *  alongside streaming for this long after the first non-silent eval and log
         *  both scores, so detection parity can be confirmed by voice on the target
         *  unit. Set to 0 to disable. */
        private const val SELF_CHECK_MS = 12_000L

        /** Number of mel frames per embedding window. */
        private const val EMB_WINDOW = 76

        /** Stride between two embedding windows, in mel frames (8 frames = 80 ms at 100 Hz). */
        private const val EMB_STRIDE = 8

        /** Number of embedding frames consumed by the wake word head. */
        private const val WAKE_WINDOW = 16

        /** Sigmoid threshold above which we consider the wake word "detected".
         *  v1.2.80 — lowered 0.5 → 0.3 after field reports of the user shouting
         *  "Hey Jarvis" without ever crossing the 0.5 bar. The hey_jarvis_v0.1
         *  model is sensitive enough that 0.3 still avoids most ambient false
         *  positives in a car cabin (validated against engine + HVAC noise). */
        private const val DETECT_THRESHOLD = 0.3f

        /** Minimum interval between two emitted detections. */
        private const val DETECT_COOLDOWN_MS = 1500L

        /** v1.2.80 — rolling window for the diag "peak score" surfaced to the
         *  UI / Logs. 30 s is short enough to react to user voice tests but long
         *  enough to catch a single attempt the user made 10 s ago. */
        private const val PEAK_WINDOW_MS = 30_000L

        /** v1.2.80 — interval between two diag log lines in AppLogger. */
        private const val DIAG_LOG_INTERVAL_MS = 5_000L

        /** Asset paths inside `app/src/main/assets/`. */
        private const val ASSET_MEL = "voice/wakeword/melspectrogram.onnx"
        private const val ASSET_EMB = "voice/wakeword/embedding_model.onnx"
        private const val ASSET_WAKE = "voice/wakeword/hey_jarvis_v0.1.onnx"

        /** Identifier surfaced in the UI / broadcast EXTRA_WW_LABEL. */
        const val MODEL_LABEL = "hey_jarvis_v0.1"

        private fun readAssetBytes(am: AssetManager, name: String): ByteArray {
            am.open(name).use { input ->
                ByteArrayOutputStream().use { baos ->
                    val buf = ByteArray(16 * 1024)
                    var r: Int
                    while (input.read(buf).also { r = it } > 0) baos.write(buf, 0, r)
                    return baos.toByteArray()
                }
            }
        }
    }
}
