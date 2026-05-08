package com.byd.dashcast;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.byd.dashcast.R;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AppLogger — structured in-app log buffer.
 *
 * Levels: DEBUG / INFO / WARN / ERROR (color-coded in LogActivity).
 * Each entry captures: timestamp, level, tag, message, thread name.
 * Timing helpers: startTiming() / endTiming() for duration measurement.
 * Backward-compat: log(tag, msg) → INFO.
 *
 * Thread-safe: uses a synchronized ArrayDeque with explicit lock.
 * Circular buffer: MAX_ENTRIES = 3000 entries.
 */
public class AppLogger {

    // ── Niveau de log ─────────────────────────────────────────────────────────
    public enum Level { DEBUG, INFO, WARN, ERROR }

    // ── Structured log entry ──────────────────────────────────────────────────
    public static class Entry {
        public final long   timestamp;
        public final Level  level;
        public final String tag;
        public final String message;
        public final String threadName;

        Entry(Level level, String tag, String message) {
            this.timestamp  = System.currentTimeMillis();
            this.level      = level;
            this.tag        = tag;
            this.message    = message;
            this.threadName = Thread.currentThread().getName();
        }
    }

    // ── Circular buffer ───────────────────────────────────────────────────────
    private static final int MAX_ENTRIES = 3000;
    // Using a synchronized ArrayDeque instead of CopyOnWriteArrayList to avoid
    // an O(N) copy of 3000 entries on every log call, reducing GC pressure.
    private static final java.util.ArrayDeque<Entry> sEntries = new java.util.ArrayDeque<>(MAX_ENTRIES + 1);
    private static final Object LOCK = new Object();

    // SimpleDateFormat is not thread-safe — one instance per thread via ThreadLocal
    // avoids repeated allocations without risk of corruption.
    private static final ThreadLocal<SimpleDateFormat> sFmt = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        }
    };
    private static final ThreadLocal<SimpleDateFormat> sFileFmt = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        }
    };
    private AppLogger() {}

    // ── Internal helper ───────────────────────────────────────────────────────

    /** Adds an entry to the circular buffer with O(1) allocation. */
    private static void addEntry(Level level, String tag, String msg) {
        Entry e = new Entry(level, tag, msg);
        synchronized (LOCK) {
            if (sEntries.size() >= MAX_ENTRIES) {
                sEntries.pollFirst(); // Remove oldest entry
            }
            sEntries.addLast(e);
        }
    }

    // ── Public logging methods ────────────────────────────────────────────────

    public static void log(Level level, String tag, String msg) {
        addEntry(level, tag, msg);
        // Mirror to logcat
        switch (level) {
            case DEBUG: Log.d(tag, msg); break;
            case INFO:  Log.i(tag, msg); break;
            case WARN:  Log.w(tag, msg); break;
            case ERROR: Log.e(tag, msg); break;
        }
    }

    /** Backward-compatible : log(tag, msg) → INFO */
    public static void log(String tag, String msg) { log(Level.INFO, tag, msg); }

    public static void d(String tag, String msg) { log(Level.DEBUG, tag, msg); }
    public static void i(String tag, String msg) { log(Level.INFO,  tag, msg); }
    public static void w(String tag, String msg) { log(Level.WARN,  tag, msg); }
    public static void e(String tag, String msg) { log(Level.ERROR, tag, msg); }

    /** Throwable variant — stores message + exception type in the buffer,
     *  delegates the full stack trace to Log.e() (visible in logcat/ADB). */
    public static void e(String tag, String msg, Throwable t) {
        String full = t != null
                ? msg + " [" + t.getClass().getSimpleName() + ": "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()) + "]"
                : msg;
        addEntry(Level.ERROR, tag, full);
        Log.e(tag, msg, t); // full stack trace in logcat
    }

    public static void w(String tag, String msg, Throwable t) {
        String full = t != null
                ? msg + " [" + t.getClass().getSimpleName() + ": "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()) + "]"
                : msg;
        addEntry(Level.WARN, tag, full);
        Log.w(tag, msg, t);
    }

    // ── Timing helpers ────────────────────────────────────────────────────────

    /** Starts a timer. Pass the return value to endTiming(). */
    public static long startTiming() {
        return System.currentTimeMillis();
    }

    /** Logs "[tag] msg (42 ms)" at DEBUG level. */
    public static void endTiming(String tag, long start, String msg) {
        long ms = System.currentTimeMillis() - start;
        d(tag, msg + " (" + ms + " ms)");
    }

    // ── Lifecycle helper ──────────────────────────────────────────────────────

    /** Logs a lifecycle event (DEBUG) with the activity name and thread name. */
    public static void lifecycle(String className, String event) {
        d("Lifecycle",
          className + " → " + event + "  [" + Thread.currentThread().getName() + "]");
    }

    // ── Buffer access ─────────────────────────────────────────────────────────

    /** Returns the number of entries in the buffer without allocating a copy. */
    public static int getEntriesCount() {
        synchronized (LOCK) {
            return sEntries.size();
        }
    }

    /** Returns an immutable copy of the buffer (used by LogActivity). */
    public static List<Entry> getEntries() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(sEntries));
        }
    }

    /** Returns the full buffer as a formatted String (for text sharing). */
    public static String get() {
        SimpleDateFormat fmt = sFmt.get();
        StringBuilder sb;
        synchronized (LOCK) {
            sb = new StringBuilder(sEntries.size() * 80); // ~80 chars per line — avoids costly reallocations
            for (Entry e : sEntries) {
                sb.append("[").append(fmt.format(new Date(e.timestamp))).append("]")
                  .append("[").append(e.level.name()).append("]")
                  .append("[").append(e.tag).append("] ")
                  .append(e.message).append("\n");
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            sEntries.clear();
        }
    }

    // ── File save ─────────────────────────────────────────────────────────────

    /**
     * Writes the log buffer to a timestamped .log file in getExternalFilesDir().
     * Retrievable via: adb pull /sdcard/Android/data/com.byd.dashcast/files/
     * @return the created File, or null on error
     */
    public static File saveToFile(Context context) {
        String filename = "byd_log_"
                + sFileFmt.get().format(new Date())
                + ".log";
        File outDir = context.getExternalFilesDir(null);
        if (outDir == null) outDir = context.getFilesDir();
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, filename);
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(get());
        } catch (IOException ex) {
            Log.e("AppLogger", "saveToFile failed", ex);
            return null;
        }
        return outFile;
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    /**
     * Saves the log to a .log file then opens the share chooser with the file
     * as an attachment (content:// via FileProvider).
     * Falls back to plain text if the file write fails.
     */
    public static void share(Context context) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Log");
        File logFile = saveToFile(context);
        if (logFile != null) {
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", logFile);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Fallback: share as plain text
            String content = get();
            if (content.isEmpty()) content = "(empty log)";
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, content);
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log_title)));
    }

    public static void shareWithReport(Context context, String reportText) {
        String combined = reportText
                + "\n\n════════════════════════════════════\n"
                + "LOG\n"
                + "════════════════════════════════════\n"
                + get();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Report + Log");
        intent.putExtra(Intent.EXTRA_TEXT, combined);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_report_title)));
    }

    /**
     * Saves arbitrary text to a timestamped .log file then opens the share
     * chooser with the file as an attachment (content:// via FileProvider).
     * The file name is prefixed with `prefix`.
     * Falls back to plain text if the file write fails.
     */
    public static void shareTextAsFile(Context context, String prefix, String content,
            String chooserTitle) {
        if (content == null) content = "";
        String stamp = sFileFmt.get().format(new Date());
        String filename = prefix + "_" + stamp + ".log";
        File outDir = context.getExternalFilesDir(null);
        if (outDir == null) outDir = context.getFilesDir();
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, filename);
        boolean fileOk = false;
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(content);
            fileOk = true;
        } catch (IOException ex) {
            Log.e("AppLogger", "shareTextAsFile write failed", ex);
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — " + prefix);
        if (fileOk) {
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", outFile);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.putExtra(Intent.EXTRA_TEXT,
                    content.isEmpty() ? "(contenu vide)" : content);
        }
        context.startActivity(Intent.createChooser(intent,
                chooserTitle != null ? chooserTitle : "Partager…"));
    }

    // ── Storage cleanup ───────────────────────────────────────────────────────

    /**
     * Deletes all DashCast-generated files from app external and cache storage:
     *   - byd_log_*.log         (AppLogger.saveToFile)
     *   - byd_report_*.txt      (SysInfoActivity)
     *   - BYD_RE_Sniffer_*.txt  (DiagActivity)
     *   - cluster_live.png      (AdbLocalClient.captureClusterDisplay)
     * ADB keys (adb.key / adb.pub) in getFilesDir() are NOT deleted.
     * Also clears the in-memory log buffer.
     *
     * @return number of files deleted
     */
    public static int cleanupFiles(Context context) {
        int deleted = 0;

        // External files dir: byd_log_*, byd_report_*, BYD_RE_Sniffer_*
        File extDir = context.getExternalFilesDir(null);
        if (extDir != null && extDir.exists()) {
            File[] files = extDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith("byd_log_")
                            || name.startsWith("byd_report_")
                            || name.startsWith("BYD_RE_Sniffer_")) {
                        if (f.delete()) deleted++;
                    }
                }
            }
        }

        // Internal files dir: same patterns (fallback when external not available)
        File intDir = context.getFilesDir();
        if (intDir != null && intDir.exists()) {
            File[] files = intDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith("byd_log_")
                            || name.startsWith("byd_report_")
                            || name.startsWith("BYD_RE_Sniffer_")) {
                        if (f.delete()) deleted++;
                    }
                }
            }
        }

        // External cache: cluster_live.png
        File extCache = context.getExternalCacheDir();
        if (extCache != null && extCache.exists()) {
            File png = new File(extCache, "cluster_live.png");
            if (png.exists() && png.delete()) deleted++;
        }

        // Clear in-memory buffer too
        clear();

        Log.i("AppLogger", "cleanupFiles: " + deleted + " file(s) deleted");
        return deleted;
    }
}
