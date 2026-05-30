package com.byd.dashcast.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;
import com.byd.dashcast.R;

/**
 * v1.2.43-beta — Voice PoC step 1/3.
 *
 * Foreground service that reads the microphone as raw PCM 16 kHz / mono /
 * 16-bit and computes simple level metrics (RMS, peak, clip count) every
 * ~{@link #UPDATE_INTERVAL_MS} ms. Metrics are published to in-process
 * listeners via {@link LocalBroadcastManager} so the Diagnostic Voice tab
 * can render a live VU meter.
 *
 * <p>This step is intentionally <b>ML-free</b>. Its sole purpose is to
 * validate end-to-end that:
 * <ul>
 *   <li>the RECORD_AUDIO runtime permission is grantable on the device,</li>
 *   <li>the foreground service is allowed to start with type=microphone,</li>
 *   <li>AudioRecord opens at 16 kHz and actually delivers non-zero samples
 *       on each DiLink platform (DL3/DL4/DL5).</li>
 * </ul>
 *
 * <p>Once this step is proven on every target, v1.2.44 will wire openWakeWord
 * (ONNX Runtime Mobile) on the same audio stream, and v1.2.45 will add Vosk
 * for post-wake transcription. The service contract (start/stop intents,
 * broadcast format) is designed to stay stable across those iterations.
 *
 * <p>The service holds <b>no reference</b> to any production code path:
 * everything lives under {@code com.byd.dashcast.voice} and the only outside
 * imports are {@link AppLogger} (logging) and {@link R} (notification
 * strings).
 */
public final class VoiceService extends Service {

    private static final String TAG = "VoiceService";

    /** Channel id for the persistent foreground notification. */
    private static final String CHANNEL_ID = "dashcast_voice_poc";

    /** Notification id (arbitrary, unique within DashCast). */
    private static final int NOTIF_ID = 0x42C0;

    /** Sample rate negotiated with AudioRecord. 16 kHz is the standard wake-word / Vosk rate. */
    public  static final int SAMPLE_RATE_HZ = 16_000;

    /** Frame size of the level computation window. ~50 ms @ 16 kHz = 800 samples. */
    private static final int FRAME_SAMPLES = 800;

    /** Broadcast minimum interval to throttle UI updates. */
    private static final long UPDATE_INTERVAL_MS = 50L;

    // ─── Broadcast contract ────────────────────────────────────────────────

    /** Broadcast action carrying live level metrics. Receiver registered by the Diag tab. */
    public static final String ACTION_LEVEL    = "com.byd.dashcast.voice.LEVEL";
    /** Broadcast action carrying state transitions (started, stopped, error). */
    public static final String ACTION_STATE    = "com.byd.dashcast.voice.STATE";

    public static final String EXTRA_RMS       = "rms";          // int 0..32767
    public static final String EXTRA_PEAK      = "peak";         // int 0..32767
    public static final String EXTRA_CLIP      = "clip";         // long cumulative count of |sample|==32767
    public static final String EXTRA_FRAMES    = "frames";       // long cumulative frames processed
    public static final String EXTRA_RUN_MS    = "runMs";        // long uptime since start

    public static final String EXTRA_STATE     = "state";        // STATE_* below
    public static final String EXTRA_REASON    = "reason";       // optional error message

    public static final int STATE_STARTED = 1;
    public static final int STATE_STOPPED = 2;
    public static final int STATE_ERROR   = 3;

    // ─── Internal state ────────────────────────────────────────────────────

    private volatile boolean mRunning;
    private Thread           mCaptureThread;
    private AudioRecord      mRecord;
    private volatile boolean mErrorSignaled;

    /** Singleton "is running" probe used by the UI to render the correct button. */
    private static volatile boolean sIsRunning;
    public  static boolean isRunning() { return sIsRunning; }

    // ─── v1.4.2-beta pre-roll ring buffer ─────────────────────────────────
    //
    // Always keep the last PRE_ROLL_SAMPLES of audio in a circular buffer.
    // VoskTranscriber calls getPreRollCopy() right before installing its
    // SampleConsumer so that audio captured during and just after wake-word
    // detection (while the Recognizer object is being created) is not lost.
    // Without this, the first 100-200 ms of the command is missed, turning
    // "diagnostic" into "stic".

    /** 500 ms of pre-roll at 16 kHz — enough to cover Recognizer init delay. */
    private static final int PRE_ROLL_SAMPLES = SAMPLE_RATE_HZ / 2; // 8 000
    private static final short[] sPreRollBuf  = new short[PRE_ROLL_SAMPLES];
    private static int     sPreRollHead  = 0;   // next write position
    private static boolean sPreRollFull  = false;
    private static final Object PRE_ROLL_LOCK = new Object();

    private static void pushPreRoll(short[] pcm, int n) {
        synchronized (PRE_ROLL_LOCK) {
            for (int i = 0; i < n; i++) {
                sPreRollBuf[sPreRollHead] = pcm[i];
                sPreRollHead = (sPreRollHead + 1) % PRE_ROLL_SAMPLES;
                if (sPreRollHead == 0) sPreRollFull = true;
            }
        }
    }

    /**
     * Returns a linearised copy of the pre-roll ring buffer (oldest sample
     * first). The returned array is a snapshot — safe to use from any thread.
     */
    public static short[] getPreRollCopy() {
        synchronized (PRE_ROLL_LOCK) {
            if (!sPreRollFull && sPreRollHead == 0) return new short[0];
            int len = sPreRollFull ? PRE_ROLL_SAMPLES : sPreRollHead;
            short[] out = new short[len];
            if (sPreRollFull) {
                // oldest data starts at sPreRollHead
                int part1 = PRE_ROLL_SAMPLES - sPreRollHead;
                System.arraycopy(sPreRollBuf, sPreRollHead, out, 0,     part1);
                System.arraycopy(sPreRollBuf, 0,            out, part1, sPreRollHead);
            } else {
                System.arraycopy(sPreRollBuf, 0, out, 0, sPreRollHead);
            }
            return out;
        }
    }

    // ─── v1.2.50-beta wake-word hook ───────────────────────────────────────
    //
    // The Diag "Wake word" switch installs an in-process consumer that the
    // capture loop pushes raw PCM frames to. Null in production : when the
    // user never toggles the switch, this field stays null and the capture
    // loop's hot path is unchanged versus v1.2.43.

    /** Receives raw 16 kHz mono PCM 16-bit frames as they come out of AudioRecord. */
    public interface SampleConsumer {
        /** Called on the voice-capture thread. {@code n} is the number of valid samples in {@code pcm}. */
        void onFrame(short[] pcm, int n);
    }

    private static volatile SampleConsumer sSampleConsumer;

    /** Installs (or removes, with {@code null}) the wake-word PCM consumer. */
    public static void setSampleConsumer(SampleConsumer c) { sSampleConsumer = c; }

    /** Returns the currently installed consumer (or null). */
    public static SampleConsumer getSampleConsumer() { return sSampleConsumer; }

    // ─── v1.4.0-beta voice command hook ───────────────────────────────────
    //
    // When the user enables voice commands in the Diag tab, a VoskTranscriber
    // is installed here.  The capture loop calls triggerTranscription() each
    // time the wake-word engine fires a DETECTED broadcast so that Vosk can
    // open its own short AudioRecord window (VoiceService's own record is
    // paused for MAX_LISTEN_MS to avoid capturing both).

    private static volatile VoskTranscriber sTranscriber;

    /** Installs (or removes, with {@code null}) the post-wake transcriber. */
    public static void setTranscriber(VoskTranscriber t) { sTranscriber = t; }

    /** Triggers a Vosk listen window; no-op if no transcriber is installed. */
    public static void triggerTranscription() {
        VoskTranscriber t = sTranscriber;
        if (t != null) t.startListening();
    }

    // ─── Service lifecycle ─────────────────────────────────────────────────

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mRunning) {
            AppLogger.d(TAG, "onStartCommand: already running, ignored");
            return START_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        startCapture();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }

    // ─── Capture loop ──────────────────────────────────────────────────────

    private void startCapture() {
        mErrorSignaled = false;
        final int channel = AudioFormat.CHANNEL_IN_MONO;
        final int format  = AudioFormat.ENCODING_PCM_16BIT;
        final int minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channel, format);
        if (minBuf <= 0) {
            broadcastError("AudioRecord.getMinBufferSize returned " + minBuf);
            return;
        }
        // Pick a buffer that's a generous multiple of the frame size so we never tear a frame.
        final int bufBytes = Math.max(minBuf, FRAME_SAMPLES * 2 * 4);
        try {
            mRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ, channel, format, bufBytes);
        } catch (Throwable t) {
            broadcastError("AudioRecord ctor: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        if (mRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            broadcastError("AudioRecord state=" + mRecord.getState() + " (not INITIALIZED)");
            safeReleaseRecord();
            return;
        }
        try {
            mRecord.startRecording();
        } catch (Throwable t) {
            broadcastError("startRecording: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            safeReleaseRecord();
            return;
        }
        mRunning = true;
        sIsRunning = true;
        broadcastState(STATE_STARTED, null);
        mCaptureThread = new Thread(this::captureLoop, "voice-capture");
        mCaptureThread.setPriority(Thread.NORM_PRIORITY);
        mCaptureThread.start();
        AppLogger.i(TAG, "Capture started — " + SAMPLE_RATE_HZ + " Hz mono 16-bit, minBuf=" + minBuf + " bytes");
    }

    private void captureLoop() {
        final short[] frame = new short[FRAME_SAMPLES];
        final long startedAt = SystemClock.elapsedRealtime();
        long clipCount = 0L;
        long frameCount = 0L;
        long lastBroadcastAt = 0L;
        while (mRunning) {
            int read;
            try {
                read = mRecord.read(frame, 0, frame.length);
            } catch (Throwable t) {
                broadcastError("read: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                break;
            }
            if (read <= 0) {
                // ERROR_INVALID_OPERATION (-3) or ERROR_BAD_VALUE (-2) → bail out cleanly.
                if (read < 0) {
                    broadcastError("AudioRecord.read returned " + read);
                    break;
                }
                continue;
            }
            frameCount++;
            long sumSq = 0L;
            int peak = 0;
            for (int i = 0; i < read; i++) {
                int s = frame[i];
                int abs = s < 0 ? -s : s;
                if (abs > peak) peak = abs;
                if (abs >= 32767) clipCount++;
                sumSq += (long) s * (long) s;
            }
            int rms = (int) Math.sqrt((double) sumSq / (double) read);

            // v1.2.50 wake-word hook : forward the raw frame to the engine
            // if one is currently installed. Null = production behaviour =
            // zero overhead. try/catch keeps a misbehaving consumer from
            // killing the capture loop.
            pushPreRoll(frame, read); // v1.4.2 pre-roll (always, low cost)
            SampleConsumer c = sSampleConsumer;
            if (c != null) {
                try { c.onFrame(frame, read); }
                catch (Throwable t) { AppLogger.w(TAG, "SampleConsumer threw: " + t); }
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastBroadcastAt >= UPDATE_INTERVAL_MS) {
                lastBroadcastAt = now;
                Intent i = new Intent(ACTION_LEVEL);
                i.putExtra(EXTRA_RMS, rms);
                i.putExtra(EXTRA_PEAK, peak);
                i.putExtra(EXTRA_CLIP, clipCount);
                i.putExtra(EXTRA_FRAMES, frameCount);
                i.putExtra(EXTRA_RUN_MS, now - startedAt);
                LocalBroadcastManager.getInstance(this).sendBroadcast(i);
            }
        }
        AppLogger.i(TAG, "Capture loop ended — frames=" + frameCount + " clip=" + clipCount);
    }

    private void stopCapture() {
        mRunning = false;
        sIsRunning = false;
        if (mRecord != null) {
            try { mRecord.stop(); } catch (Throwable ignore) {}
            safeReleaseRecord();
        }
        Thread t = mCaptureThread;
        if (t != null) {
            try { t.join(500L); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            mCaptureThread = null;
        }
        if (!mErrorSignaled) {
            broadcastState(STATE_STOPPED, null);
        }
    }

    private void safeReleaseRecord() {
        try { mRecord.release(); } catch (Throwable ignore) {}
        mRecord = null;
    }

    // ─── Broadcasts ────────────────────────────────────────────────────────

    private void broadcastState(int state, String reason) {
        Intent i = new Intent(ACTION_STATE);
        i.putExtra(EXTRA_STATE, state);
        if (reason != null) i.putExtra(EXTRA_REASON, reason);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastError(String reason) {
        AppLogger.e(TAG, "Capture error: " + reason);
        mErrorSignaled = true;
        broadcastState(STATE_ERROR, reason);
        sIsRunning = false;
        mRunning = false;
        // Asking AMS to shut us down; ensures the FG notification is removed.
        stopSelf();
    }

    // ─── Notification ──────────────────────────────────────────────────────

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.diag_voice_notif_channel),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.diag_voice_notif_title))
         .setContentText(getString(R.string.diag_voice_notif_text))
         .setSmallIcon(android.R.drawable.ic_btn_speak_now)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        return b.build();
    }

    // ─── Static helpers for the UI ─────────────────────────────────────────

    /** Convenience: start the service. */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, VoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Convenience: stop the service. */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, VoiceService.class));
    }
}
