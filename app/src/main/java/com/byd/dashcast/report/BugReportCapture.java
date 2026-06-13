package com.byd.dashcast.report;

import android.content.Context;
import android.os.Build;

import com.byd.dashcast.BuildConfig;
import com.byd.dashcast.infrastructure.AdbLocalClient;
import com.byd.dashcast.util.AppLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds a single, size-bounded diagnostic file for the in-app bug reporter.
 *
 * <p>Unlike the RE sniffer (which spawns long-lived background logcat processes),
 * this is a one-shot snapshot: it relies on the kernel logcat ring buffer, which
 * already retains the last few minutes of activity, so the report is retroactive
 * (the moments before the bug are captured) without any continuous logging.
 *
 * <p>The shell dump (uid=2000) writes logcat + dumpsys snapshots to the app's
 * external files dir; the in-memory DashCast journal and a header are appended
 * on the Java side. Total size stays well under ~1 MB thanks to {@code logcat -t}.
 */
public final class BugReportCapture {

    private static final String TAG       = "BugReportCapture";
    private static final String PREFIX    = "byd_bugreport_";
    private static final int    LOGCAT_LINES = 3000;

    public interface Callback {
        /** Called on the main thread with the finished file (never null on success). */
        void onReady(File file);
        /** Called on the main thread; {@code partial} is the best-effort file or null. */
        void onError(String message, File partial);
    }

    private BugReportCapture() {}

    /** Device line for the report header and the Telegram caption. */
    public static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " · " + Build.PRODUCT
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /** Version line for the report header and the Telegram caption. */
    public static String versionLine() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    /**
     * Captures a bug-report file. {@code metaHeader} (the user's Title/Steps/Result
     * block) is written at the very top so it is readable without scrolling.
     * Runs the shell dump in the background; {@code cb} fires on the main thread.
     */
    public static void capture(Context context, String metaHeader, Callback cb) {
        final Context app = context.getApplicationContext();
        final File outFile = newFile(app);
        final String p = outFile.getAbsolutePath();

        // One-shot shell dump. logcat -t bounds the size; dumpsys grabs the
        // cluster/display/window state that matters for projection bugs.
        String cmd =
              "echo '=== DASHCAST BUG REPORT ===' > " + p
            + " ; date >> " + p
            + " ; echo '--- LOGCAT (last " + LOGCAT_LINES + " lines) ---' >> " + p
            + " ; logcat -d -t " + LOGCAT_LINES + " -v threadtime >> " + p + " 2>&1"
            + " ; echo '--- LOGCAT EVENTS (last 500) ---' >> " + p
            + " ; logcat -b events -d -t 500 -v threadtime >> " + p + " 2>&1"
            + " ; echo '--- DISPLAYS ---' >> " + p
            + " ; dumpsys display 2>/dev/null >> " + p
            + " ; echo '--- WINDOW STACKS ---' >> " + p
            + " ; dumpsys activity activities 2>/dev/null"
            + "   | grep -E 'Stack #|Task id|taskId|displayId|realActivity|mResumed' >> " + p
            + " ; echo '--- PROCESSES (byd/xdja/daemon) ---' >> " + p
            + " ; ps -A 2>/dev/null | grep -iE 'byd|xdja|daemon|dilink|cluster|app_process' >> " + p
            + " ; echo '--- MIRRORDAEMON LOG ---' >> " + p
            + " ; cat /data/local/tmp/mirrordaemon_latest.log 2>/dev/null | tail -200 >> " + p
            + " ; echo '=== END SHELL DUMP ===' >> " + p;

        AdbLocalClient.executeShellWithResult(app, cmd, new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                finish(app, outFile, metaHeader, cb, null);
            }
            @Override public void onError(String err) {
                // The shell dump failed (ADB down) — still ship the in-memory
                // journal + metadata so the report is never empty.
                AppLogger.w(TAG, "shell dump failed, journal-only report: " + err);
                finish(app, outFile, metaHeader, cb, err);
            }
        });
    }

    /** Appends the metadata header (top) and the in-memory journal (bottom), then reports. */
    private static void finish(Context app, File outFile, String metaHeader,
                               Callback cb, String shellError) {
        try {
            String shellBody = outFile.exists() ? readFile(outFile) : "";
            StringBuilder sb = new StringBuilder(shellBody.length() + 8192);
            sb.append(metaHeader != null ? metaHeader : "").append("\n\n");
            sb.append("Device: ").append(deviceLine()).append('\n');
            sb.append("Version: ").append(versionLine()).append('\n');
            if (shellError != null)
                sb.append("[shell dump unavailable: ").append(shellError).append("]\n");
            sb.append("\n════════ SHELL DUMP ════════\n").append(shellBody);
            sb.append("\n════════ DASHCAST JOURNAL ════════\n").append(AppLogger.get());

            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write(sb.toString());
            }
            post(app, () -> cb.onReady(outFile));
        } catch (IOException e) {
            AppLogger.e(TAG, "finish() write failed", e);
            post(app, () -> cb.onError(e.getMessage(),
                    outFile.exists() ? outFile : null));
        }
    }

    private static File newFile(Context app) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, PREFIX + ts + ".txt");
    }

    private static String readFile(File f) throws IOException {
        byte[] buf = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off);
        }
    }

    private static void post(Context app, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
