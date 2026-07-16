package com.byd.dashcast.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock

import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.R

import kotlin.math.max
import kotlin.math.sqrt

/**
 * v1.2.43-beta — Voice PoC step 1/3.
 *
 * Foreground service that reads the microphone as raw PCM 16 kHz / mono /
 * 16-bit and computes simple level metrics (RMS, peak, clip count) every
 * ~[UPDATE_INTERVAL_MS] ms. Metrics are published to in-process
 * listeners via [LocalBroadcastManager] so the Diagnostic Voice tab
 * can render a live VU meter.
 *
 * This step is intentionally **ML-free**. Its sole purpose is to
 * validate end-to-end that:
 *  - the RECORD_AUDIO runtime permission is grantable on the device,
 *  - the foreground service is allowed to start with type=microphone,
 *  - AudioRecord opens at 16 kHz and actually delivers non-zero samples
 *    on each DiLink platform (DL3/DL4/DL5).
 *
 * Once this step is proven on every target, v1.2.44 will wire openWakeWord
 * (ONNX Runtime Mobile) on the same audio stream, and v1.2.45 will add Vosk
 * for post-wake transcription. The service contract (start/stop intents,
 * broadcast format) is designed to stay stable across those iterations.
 *
 * The service holds **no reference** to any production code path:
 * everything lives under `com.byd.dashcast.voice` and the only outside
 * imports are [AppLogger] (logging) and [R] (notification strings).
 */
class VoiceService : Service() {

    // ─── Internal state ────────────────────────────────────────────────────

    @Volatile private var mRunning = false
    private var mCaptureThread: Thread? = null
    private var mRecord: AudioRecord? = null
    @Volatile private var mErrorSignaled = false

    // ─── Service lifecycle ─────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mRunning) {
            AppLogger.d(TAG, "onStartCommand: already running, ignored")
            return Service.START_STICKY
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            AppLogger.e(TAG, "startForeground failed", e)
            broadcastError("foreground_start_failed")
            return Service.START_NOT_STICKY
        }
        startCapture()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        sSampleConsumer = null
        sTranscriber = null
        super.onDestroy()
    }

    // ─── Capture loop ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        mErrorSignaled = false
        synchronized(PRE_ROLL_LOCK) {
            sPreRollHead = 0
            sPreRollFull = false
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channel, format)
        if (minBuf <= 0) {
            broadcastError("AudioRecord.getMinBufferSize returned $minBuf")
            return
        }
        // Pick a buffer that's a generous multiple of the frame size so we never tear a frame.
        val bufBytes = max(minBuf, FRAME_SAMPLES * 2 * 4)
        // Capture the freshly-built recorder in a local so the null-checked field
        // does not need an unsafe deref before the NORM/startRecording checks.
        val record: AudioRecord
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ, channel, format, bufBytes
            )
        } catch (t: Throwable) {
            broadcastError("AudioRecord ctor: " + t.javaClass.simpleName + ": " + t.message)
            return
        }
        mRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            broadcastError("AudioRecord state=" + record.state + " (not INITIALIZED)")
            safeReleaseRecord()
            return
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            broadcastError("startRecording: " + t.javaClass.simpleName + ": " + t.message)
            safeReleaseRecord()
            return
        }
        mRunning = true
        sIsRunning = true
        broadcastState(STATE_STARTED, null)
        val thread = Thread({ captureLoop() }, "voice-capture")
        thread.priority = Thread.NORM_PRIORITY
        mCaptureThread = thread
        thread.start()
        AppLogger.i(TAG, "Capture started — $SAMPLE_RATE_HZ Hz mono 16-bit, minBuf=$minBuf bytes")
    }

    private fun captureLoop() {
        // mRecord is assigned in startCapture() before this thread is started and is
        // only released after this loop exits (stopCapture() joins the thread first),
        // so the recorder is stable and non-null for the lifetime of the loop.
        val record = mRecord ?: return
        val frame = ShortArray(FRAME_SAMPLES)
        val startedAt = SystemClock.elapsedRealtime()
        var clipCount = 0L
        var frameCount = 0L
        var lastBroadcastAt = 0L
        while (mRunning) {
            val read: Int
            try {
                read = record.read(frame, 0, frame.size)
            } catch (t: Throwable) {
                broadcastError("read: " + t.javaClass.simpleName + ": " + t.message)
                break
            }
            if (read <= 0) {
                // ERROR_INVALID_OPERATION (-3) or ERROR_BAD_VALUE (-2) → bail out cleanly.
                if (read < 0) {
                    broadcastError("AudioRecord.read returned $read")
                    break
                }
                continue
            }
            frameCount++
            var sumSq = 0L
            var peak = 0
            for (i in 0 until read) {
                val s = frame[i].toInt()
                val abs = if (s < 0) -s else s
                if (abs > peak) peak = abs
                if (abs >= 32767) clipCount++
                sumSq += s.toLong() * s.toLong()
            }
            val rms = sqrt(sumSq.toDouble() / read.toDouble()).toInt()

            // v1.2.50 wake-word hook : forward the raw frame to the engine
            // if one is currently installed. Null = production behaviour =
            // zero overhead. try/catch keeps a misbehaving consumer from
            // killing the capture loop.
            pushPreRoll(frame, read) // v1.4.2 pre-roll (always, low cost)
            val c = sSampleConsumer
            if (c != null) {
                try {
                    c.onFrame(frame, read)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "SampleConsumer threw: $t")
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastBroadcastAt >= UPDATE_INTERVAL_MS) {
                lastBroadcastAt = now
                // LocalBroadcastManager.sendBroadcast is asynchronous and retains the exact
                // Intent reference in its pending queue. A fresh snapshot prevents the next
                // 50 ms sample from overwriting extras before the main looper delivers this one.
                val levelIntent = Intent(ACTION_LEVEL)
                levelIntent.putExtra(EXTRA_RMS, rms)
                levelIntent.putExtra(EXTRA_PEAK, peak)
                levelIntent.putExtra(EXTRA_CLIP, clipCount)
                levelIntent.putExtra(EXTRA_FRAMES, frameCount)
                levelIntent.putExtra(EXTRA_RUN_MS, now - startedAt)
                LocalBroadcastManager.getInstance(this).sendBroadcast(levelIntent)
            }
        }
        AppLogger.i(TAG, "Capture loop ended — frames=$frameCount clip=$clipCount")
    }

    private fun stopCapture() {
        mRunning = false
        sIsRunning = false
        mRecord?.let { rec ->
            try {
                rec.stop()
            } catch (ignore: Throwable) {
            }
        }
        val t = mCaptureThread
        if (t != null) {
            try {
                t.join(500L)
            } catch (ignore: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            mCaptureThread = null
        }
        // Release only after the capture thread has exited to avoid use-after-release
        // of the native AudioRecord while read() may still be in progress.
        if (mRecord != null) {
            safeReleaseRecord()
        }
        if (!mErrorSignaled) {
            broadcastState(STATE_STOPPED, null)
        }
    }

    private fun safeReleaseRecord() {
        try {
            mRecord?.release()
        } catch (ignore: Throwable) {
        }
        mRecord = null
    }

    // ─── Broadcasts ────────────────────────────────────────────────────────

    private fun broadcastState(state: Int, reason: String?) {
        val i = Intent(ACTION_STATE)
        i.putExtra(EXTRA_STATE, state)
        if (reason != null) i.putExtra(EXTRA_REASON, reason)
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun broadcastError(reason: String) {
        AppLogger.e(TAG, "Capture error: $reason")
        mErrorSignaled = true
        broadcastState(STATE_ERROR, reason)
        sIsRunning = false
        mRunning = false
        // Asking AMS to shut us down; ensures the FG notification is removed.
        stopSelf()
    }

    // ─── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null) {
            var ch = nm.getNotificationChannel(CHANNEL_ID)
            if (ch == null) {
                ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.diag_voice_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
        val b = Notification.Builder(this, CHANNEL_ID)
        b.setContentTitle(getString(R.string.diag_voice_notif_title))
            .setContentText(getString(R.string.diag_voice_notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        return b.build()
    }

    // ─── v1.2.50-beta wake-word hook ───────────────────────────────────────
    //
    // The Diag "Wake word" switch installs an in-process consumer that the
    // capture loop pushes raw PCM frames to. Null in production : when the
    // user never toggles the switch, this field stays null and the capture
    // loop's hot path is unchanged versus v1.2.43.

    /** Receives raw 16 kHz mono PCM 16-bit frames as they come out of AudioRecord. */
    fun interface SampleConsumer {
        /** Called on the voice-capture thread. [n] is the number of valid samples in [pcm]. */
        fun onFrame(pcm: ShortArray, n: Int)
    }

    companion object {
        private const val TAG = "VoiceService"

        /** Channel id for the persistent foreground notification. */
        private const val CHANNEL_ID = "dashcast_voice_poc"

        /** Notification id (arbitrary, unique within DashCast). */
        private const val NOTIF_ID = 0x42C0

        /** Sample rate negotiated with AudioRecord. 16 kHz is the standard wake-word / Vosk rate. */
        const val SAMPLE_RATE_HZ = 16_000

        /** Frame size of the level computation window. ~50 ms @ 16 kHz = 800 samples. */
        private const val FRAME_SAMPLES = 800

        /** Broadcast minimum interval to throttle UI updates. */
        private const val UPDATE_INTERVAL_MS = 50L

        // ─── Broadcast contract ────────────────────────────────────────────

        /** Broadcast action carrying live level metrics. Receiver registered by the Diag tab. */
        const val ACTION_LEVEL = "com.byd.dashcast.voice.LEVEL"

        /** Broadcast action carrying state transitions (started, stopped, error). */
        const val ACTION_STATE = "com.byd.dashcast.voice.STATE"

        const val EXTRA_RMS = "rms"          // int 0..32767
        const val EXTRA_PEAK = "peak"        // int 0..32767
        const val EXTRA_CLIP = "clip"        // long cumulative count of |sample|==32767
        const val EXTRA_FRAMES = "frames"    // long cumulative frames processed
        const val EXTRA_RUN_MS = "runMs"     // long uptime since start

        const val EXTRA_STATE = "state"      // STATE_* below
        const val EXTRA_REASON = "reason"    // optional error message

        const val STATE_STARTED = 1
        const val STATE_STOPPED = 2
        const val STATE_ERROR = 3

        /** Singleton "is running" probe used by the UI to render the correct button. */
        @Volatile private var sIsRunning = false

        @JvmStatic
        fun isRunning(): Boolean = sIsRunning

        // ─── v1.4.2-beta pre-roll ring buffer ─────────────────────────────
        //
        // Always keep the last PRE_ROLL_SAMPLES of audio in a circular buffer.
        // VoskTranscriber calls getPreRollCopy() right before installing its
        // SampleConsumer so that audio captured during and just after wake-word
        // detection (while the Recognizer object is being created) is not lost.
        // Without this, the first 100-200 ms of the command is missed, turning
        // "diagnostic" into "stic".

        /** 500 ms of pre-roll at 16 kHz — enough to cover Recognizer init delay. */
        private const val PRE_ROLL_SAMPLES = SAMPLE_RATE_HZ / 2 // 8 000
        private val sPreRollBuf = ShortArray(PRE_ROLL_SAMPLES)
        private var sPreRollHead = 0   // next write position
        private var sPreRollFull = false
        private val PRE_ROLL_LOCK = Any()

        private fun pushPreRoll(pcm: ShortArray, n: Int) {
            synchronized(PRE_ROLL_LOCK) {
                for (i in 0 until n) {
                    sPreRollBuf[sPreRollHead] = pcm[i]
                    sPreRollHead = (sPreRollHead + 1) % PRE_ROLL_SAMPLES
                    if (sPreRollHead == 0) sPreRollFull = true
                }
            }
        }

        /**
         * Returns a linearised copy of the pre-roll ring buffer (oldest sample
         * first). The returned array is a snapshot — safe to use from any thread.
         */
        @JvmStatic
        fun getPreRollCopy(): ShortArray {
            synchronized(PRE_ROLL_LOCK) {
                if (!sPreRollFull && sPreRollHead == 0) return ShortArray(0)
                val len = if (sPreRollFull) PRE_ROLL_SAMPLES else sPreRollHead
                val out = ShortArray(len)
                if (sPreRollFull) {
                    // oldest data starts at sPreRollHead
                    val part1 = PRE_ROLL_SAMPLES - sPreRollHead
                    System.arraycopy(sPreRollBuf, sPreRollHead, out, 0, part1)
                    System.arraycopy(sPreRollBuf, 0, out, part1, sPreRollHead)
                } else {
                    System.arraycopy(sPreRollBuf, 0, out, 0, sPreRollHead)
                }
                return out
            }
        }

        @Volatile private var sSampleConsumer: SampleConsumer? = null

        /** Installs (or removes, with `null`) the wake-word PCM consumer. */
        @JvmStatic
        fun setSampleConsumer(c: SampleConsumer?) {
            sSampleConsumer = c
        }

        /** Returns the currently installed consumer (or null). */
        @JvmStatic
        fun getSampleConsumer(): SampleConsumer? = sSampleConsumer

        // ─── v1.4.0-beta voice command hook ───────────────────────────────
        //
        // When the user enables voice commands in the Diag tab, a VoskTranscriber
        // is installed here.  The capture loop calls triggerTranscription() each
        // time the wake-word engine fires a DETECTED broadcast so that Vosk can
        // open its own short AudioRecord window (VoiceService's own record is
        // paused for MAX_LISTEN_MS to avoid capturing both).

        // VoskTranscriber holds only the application context (see its ctor) — no leak.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var sTranscriber: VoskTranscriber? = null

        /** Installs (or removes, with `null`) the post-wake transcriber. */
        @JvmStatic
        fun setTranscriber(t: VoskTranscriber?) {
            sTranscriber = t
        }

        /** Triggers a Vosk listen window; no-op if no transcriber is installed. */
        @JvmStatic
        fun triggerTranscription() {
            val t = sTranscriber
            if (t != null) t.startListening()
        }

        // ─── Static helpers for the UI ─────────────────────────────────────

        /** Convenience: start the service. */
        @JvmStatic
        fun start(ctx: Context) {
            val i = Intent(ctx, VoiceService::class.java)
            ctx.startForegroundService(i)
        }

        /** Convenience: stop the service. */
        @JvmStatic
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceService::class.java))
        }
    }
}
