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

    /** Singleton "is running" probe used by the UI to render the correct button. */
    private static volatile boolean sIsRunning;
    public  static boolean isRunning() { return sIsRunning; }

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
        Thread t = mCaptureThread;
        if (t != null) {
            try { t.join(500L); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
            mCaptureThread = null;
        }
        if (mRecord != null) {
            try { mRecord.stop(); } catch (Throwable ignore) {}
            safeReleaseRecord();
        }
        broadcastState(STATE_STOPPED, null);
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
