package com.byd.dashcast.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    // 1.2.31 — incremental per-level counters maintained on every add/evict/clear.
    // Lets LogActivity.refreshLog() update the level-filter chips without walking
    // the entire 3000-entry buffer twice on every 500 ms tick.
    private static final int[] sCountByLevel = new int[Level.values().length];

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
                Entry evicted = sEntries.pollFirst(); // Remove oldest entry
                if (evicted != null) sCountByLevel[evicted.level.ordinal()]--;
            }
            sEntries.addLast(e);
            sCountByLevel[level.ordinal()]++;
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
        // 1.2.31 — snapshot the buffer under LOCK, format outside. Previously
        // the format loop ran inside synchronized(LOCK) and blocked every
        // logger thread (Beta probes, ADB worker, daemon connect) for the
        // duration of formatting 3000 entries (~5-10 ms). Aligned with
        // getEntries() which already uses the snapshot pattern.
        Entry[] snapshot;
        synchronized (LOCK) {
            snapshot = sEntries.toArray(new Entry[0]);
        }
        StringBuilder sb = new StringBuilder(snapshot.length * 80);
        for (Entry e : snapshot) {
            sb.append("[").append(fmt.format(new Date(e.timestamp))).append("]")
              .append("[").append(e.level.name()).append("]")
              .append("[").append(e.tag).append("] ")
              .append(e.message).append("\n");
        }
        return sb.toString();
    }

    /**
     * 1.2.31 — Returns a snapshot copy of the per-level entry counts indexed
     * by {@link Level#ordinal()}. O(Level.values().length) instead of O(N) so
     * the LogActivity chips can refresh at 2 Hz without walking the full buffer.
     */
    public static int[] getCountByLevel() {
        synchronized (LOCK) {
            return java.util.Arrays.copyOf(sCountByLevel, sCountByLevel.length);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            sEntries.clear();
            java.util.Arrays.fill(sCountByLevel, 0);
        }
    }

    // ── File save ─────────────────────────────────────────────────────────────

    /**
     * Writes the log buffer to a timestamped .log file in getExternalFilesDir().
     * Retrievable via: adb pull /sdcard/Android/data/com.byd.dashcast/files/
     * @return the created File, or null on error
     */
    public static File saveToFile(Context context) {
        return writeFile(context, "byd_log_", get());
    }

    /**
     * Generic helper — writes {@code content} to a timestamped
     * {@code <prefix><yyyyMMdd_HHmmss>.log} file in getExternalFilesDir()
     * (falling back to internal storage). Used by {@link #share(Context)} and
     * {@link #shareWithReport(Context, String)} to guarantee every "send log"
     * action emits a real .log file attachment instead of plain text.
     *
     * @return the created File, or null on I/O error.
     */
    public static File writeFile(Context context, String prefix, String content) {
        String filename = prefix + sFileFmt.get().format(new Date()) + ".log";
        File outDir = context.getExternalFilesDir(null);
        if (outDir == null) outDir = context.getFilesDir();
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, filename);
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(content != null ? content : "");
        } catch (IOException ex) {
            Log.e("AppLogger", "writeFile failed: " + filename, ex);
            return null;
        }
        return outFile;
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    /**
     * Saves the log buffer to a .log file and opens the share chooser with the
     * file attached as a {@code content://} URI (via FileProvider). If the file
     * can't be written, falls back to plain text so the user is never blocked.
     */
    public static void share(Context context) {
        File logFile = saveToFile(context);
        if (logFile != null) {
            shareFile(context, logFile,
                    "DashCast — Log",
                    context.getString(R.string.share_log_title));
            return;
        }
        // Fallback: file write failed (rare — typically no external storage).
        Log.w("AppLogger", "share: file write failed, falling back to text");
        String content = get();
        if (content.isEmpty()) content = "(empty log)";
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Log (text fallback)");
        intent.putExtra(Intent.EXTRA_TEXT, content);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log_title)));
    }

    /**
     * Combines an arbitrary report with the current log buffer, writes them to
     * a single timestamped .log file and shares it as an attachment. Used by
     * SysInfoActivity and DiagActivity to send a self-contained file to the
     * developer instead of pasting walls of text.
     */
    public static void shareWithReport(Context context, String reportText) {
        String combined = (reportText != null ? reportText : "")
                + "\n\n════════════════════════════════════\n"
                + "LOG\n"
                + "════════════════════════════════════\n"
                + get();
        File f = writeFile(context, "byd_report_", combined);
        if (f != null) {
            shareFile(context, f,
                    "DashCast — Report + Log",
                    context.getString(R.string.share_report_title));
            return;
        }
        // Fallback if write failed (no external storage etc.).
        Log.w("AppLogger", "shareWithReport: file write failed, falling back to text");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Report + Log (text fallback)");
        intent.putExtra(Intent.EXTRA_TEXT, combined);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_report_title)));
    }

    /**
     * Generic file-attachment share. Use this from any activity that wants to
     * send a .log/.txt file to the developer (DiagActivity "Beta Engine"
     * report, custom diagnostics, …). The MIME type is forced to text/plain so
     * any chooser entry (email, messaging, drive) accepts the file.
     */
    public static void shareFile(Context context, File file, String subject, String chooserTitle) {
        if (file == null || !file.exists()) {
            Log.w("AppLogger", "shareFile: missing file");
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, chooserTitle));
    }

    // ── Telegram targeted share ───────────────────────────────────────────────

    /**
     * Same as {@link #shareWithReport(Context, String)} but tries to open
     * Telegram directly (org.telegram.messenger, then org.telegram.messenger.web,
     * then nekogram/plus variants). Falls back to the generic chooser if no
     * Telegram package is installed. Used by the DL5 Cluster recon panel so
     * the tester can ship the recon report to the developer in one tap.
     */
    public static void shareReportToTelegram(Context context, String reportText) {
        String combined = (reportText != null ? reportText : "")
                + "\n\n════════════════════════════════════\n"
                + "LOG\n"
                + "════════════════════════════════════\n"
                + get();
        File f = writeFile(context, "byd_report_", combined);
        // Known Telegram package candidates, ordered by popularity.
        String[] tgPkgs = new String[] {
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.telegram.plus",
                "nekox.messenger",
                "org.thunderdog.challegram"
        };
        PackageManager pm = context.getPackageManager();
        String hit = null;
        for (String p : tgPkgs) {
            try {
                pm.getPackageInfo(p, 0);
                hit = p; break;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        if (f != null && hit != null) {
            try {
                Uri uri = FileProvider.getUriForFile(
                        context, context.getPackageName() + ".fileprovider", f);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.setPackage(hit);
                intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — DL5 Cluster Recon");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ex) {
                Log.w("AppLogger", "shareReportToTelegram: direct intent failed, falling back", ex);
            }
        }
        // Fallback: generic chooser (text-only if file write failed).
        if (f != null) {
            shareFile(context, f, "DashCast — DL5 Cluster Recon",
                    context.getString(R.string.share_report_title));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — DL5 Cluster Recon (text fallback)");
        intent.putExtra(Intent.EXTRA_TEXT, combined);
        context.startActivity(Intent.createChooser(intent,
                context.getString(R.string.share_report_title)));
    }

    // ── Storage cleanup ───────────────────────────────────────────────────────

    /**
     * Deletes all DashCast-generated files from app external and cache storage:
     *   - byd_log_*.log         (AppLogger.saveToFile)
     *   - byd_report_*.txt      (SysInfoActivity)
     *   - BYD_RE_Sniffer_*.txt  (DiagActivity)
     *   - cluster_live.png      (legacy — produced by captureClusterDisplay, removed in LOT 4 / v1.2.32)
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

    /**
     * v1.2.55-beta — caps the on-disk accumulation of generated files by
     * keeping only the {@code keepPerPrefix} most recent entries per prefix
     * (byd_log_, byd_report_, BYD_RE_Sniffer_). Symptom that motivated this :
     * after several testing sessions across days, the app's external dir grew
     * to 1.08 GB because every share-log / share-report / sniffer run wrote
     * a fresh timestamped file and nothing rotated. Designed to be called
     * from MainActivity.onCreate so the rotation runs on every cold start
     * without UI intervention. Silent; logs the deletion count via Log.i.
     *
     * @param keepPerPrefix maximum number of files to retain per prefix (must be ≥ 1)
     * @return number of files deleted across all prefixes
     */
    public static int pruneOldFiles(Context context, int keepPerPrefix) {
        if (context == null || keepPerPrefix < 1) return 0;
        final String[] prefixes = { "byd_log_", "byd_report_", "BYD_RE_Sniffer_" };
        int deleted = 0;
        File[] dirs = new File[] {
                context.getExternalFilesDir(null),
                context.getFilesDir()
        };
        for (File dir : dirs) {
            if (dir == null || !dir.exists()) continue;
            File[] entries = dir.listFiles();
            if (entries == null) continue;
            for (String prefix : prefixes) {
                java.util.ArrayList<File> matches = new java.util.ArrayList<>();
                for (File f : entries) {
                    if (f != null && f.isFile() && f.getName().startsWith(prefix)) {
                        matches.add(f);
                    }
                }
                if (matches.size() <= keepPerPrefix) continue;
                // Sort most-recent first (descending lastModified).
                java.util.Collections.sort(matches, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (int i = keepPerPrefix; i < matches.size(); i++) {
                    if (matches.get(i).delete()) deleted++;
                }
            }
        }
        // Vosk directory: remove orphan partial-download temp files.
        // Uses File constructor to avoid creating the directory if it doesn't
        // exist (getExternalFilesDir("vosk") would create it as a side effect).
        File extBase = context.getExternalFilesDir(null);
        if (extBase != null) {
            File voskDir = new File(extBase, "vosk");
            if (voskDir.isDirectory()) {
                File[] voskEntries = voskDir.listFiles();
                if (voskEntries != null) {
                    for (File f : voskEntries) {
                        if (f != null && f.isFile() && f.getName().endsWith(".download.tmp")) {
                            if (f.delete()) deleted++;
                        }
                    }
                }
            }
            // exported_apks: keep the 2 most recent, delete the rest.
            File exportDir = new File(extBase, "exported_apks");
            if (exportDir.isDirectory()) {
                java.util.ArrayList<File> apks = new java.util.ArrayList<>();
                File[] exportEntries = exportDir.listFiles();
                if (exportEntries != null) {
                    for (File f : exportEntries) {
                        if (f != null && f.isFile() && f.getName().endsWith(".apk")) {
                            apks.add(f);
                        }
                    }
                }
                if (apks.size() > 2) {
                    java.util.Collections.sort(apks, new java.util.Comparator<File>() {
                        @Override public int compare(File a, File b) {
                            return Long.compare(b.lastModified(), a.lastModified());
                        }
                    });
                    for (int i = 2; i < apks.size(); i++) {
                        if (apks.get(i).delete()) deleted++;
                    }
                }
            }
        }
        if (deleted > 0) Log.i("AppLogger", "pruneOldFiles: " + deleted
                + " file(s) deleted (keep " + keepPerPrefix + "/prefix)");
        return deleted;
    }
}
