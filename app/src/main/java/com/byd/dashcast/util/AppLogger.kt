package com.byd.dashcast.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.byd.dashcast.R
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * AppLogger — structured in-app log buffer.
 *
 * Levels: DEBUG / INFO / WARN / ERROR (color-coded in LogActivity).
 * Each entry captures: timestamp, level, tag, message, thread name.
 * Timing helpers: startTiming() / endTiming() for duration measurement.
 * Backward-compat: log(tag, msg) → INFO.
 *
 * Thread-safe: uses a synchronized ArrayDeque with explicit lock.
 * Circular buffer: MAX_ENTRIES = 5000 entries.
 */
object AppLogger {

    // ── Niveau de log ─────────────────────────────────────────────────────────
    enum class Level { DEBUG, INFO, WARN, ERROR }

    // ── Structured log entry ──────────────────────────────────────────────────
    // @JvmField keeps direct field access (e.level, e.threadName, …) working
    // from the Java readers (LogActivity, LogAdapter, SysInfoActivity). The
    // constructor stays internal — only addEntry() below creates entries.
    class Entry internal constructor(
        @JvmField val level: Level,
        @JvmField val tag: String,
        @JvmField val message: String,
        @JvmField val sequence: Long
    ) {
        @JvmField val timestamp: Long = System.currentTimeMillis()
        @JvmField val threadName: String = Thread.currentThread().name
    }

    // ── Circular buffer ───────────────────────────────────────────────────────
    private const val MAX_ENTRIES = 5000
    /** Per-entry message cap (chars). A single dumpsys/Parcel dump can be tens of KB;
     *  truncate so a handful can't pin megabytes each in this process-wide buffer. */
    private const val MAX_MSG_CHARS = 4096
    /** Global char budget across all retained messages. Evict oldest until BOTH the entry
     *  count AND this budget hold, bounding steady-state retention deterministically. */
    private const val MAX_TOTAL_CHARS = 2_000_000L
    private const val TRUNCATION_SUFFIX = "…[truncated]"

    // Using a synchronized ArrayDeque instead of CopyOnWriteArrayList to avoid
    // an O(N) copy of 3000 entries on every log call, reducing GC pressure.
    // java.util.ArrayDeque (not kotlin.collections.ArrayDeque) is required for
    // pollFirst()'s null-on-empty contract used below.
    private val sEntries = java.util.ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private val LOCK = Any()

    // 1.2.31 — incremental per-level counters maintained on every add/evict/clear.
    // Lets LogActivity.refreshLog() update the level-filter chips without walking
    // the entire 3000-entry buffer twice on every 500 ms tick.
    private val sCountByLevel = IntArray(Level.values().size)

    // Running total of retained message chars (guarded by LOCK). Together with MAX_TOTAL_CHARS
    // this bounds heap retention by BYTES, not just entry count — 5000 multi-KB dumps could
    // otherwise pin many MB for the whole process lifetime.
    private var sTotalChars = 0L

    // Monotonic stamp bumped on every mutation (add or clear). Pollers must use
    // this instead of getEntriesCount(): once the circular buffer saturates at
    // MAX_ENTRIES, every add also evicts one entry so size() stays pinned at
    // 3000 — a size-based change check then reports "no change" forever and the
    // log viewer silently freezes.
    private var sChangeStamp = 0L
    private var sGeneration = 0L
    private var sNextSequence = 1L

    class EntryUpdate internal constructor(
        @JvmField val generation: Long,
        @JvmField val firstSequence: Long,
        @JvmField val lastSequence: Long,
        @JvmField val entries: List<Entry>,
        @JvmField val appendOnly: Boolean,
        @JvmField val totalCount: Int,
        @JvmField val countByLevel: IntArray
    )

    // SimpleDateFormat is not thread-safe — one instance per thread via ThreadLocal
    // avoids repeated allocations without risk of corruption.
    // Locale.ROOT, not getDefault(): under the Arabic locale getDefault() renders
    // Eastern Arabic digits — filenames like byd_log_٢٠٢٦٠٦١٢_٢٣٥٠٥٩.log and
    // unsearchable timestamps in shared logs (seen in a user report, June 2026).
    // withInitial() always supplies a value, so get() never returns null here;
    // ThreadLocal.get() is nonetheless typed nullable in Kotlin → !! at the
    // (two) call sites is safe by construction.
    private val sFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    private val sFileFmt: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT) }

    // ── Internal helper ───────────────────────────────────────────────────────

    /** Adds an entry to the circular buffer with O(1) allocation. */
    private fun addEntry(level: Level, tag: String, msg: String) {
        // Cap over-long messages (keep the head, where the tag/error context lives) so a full
        // dumpsys/Parcel dump isn't pinned verbatim for the process lifetime.
        val bounded = if (msg.length > MAX_MSG_CHARS)
            msg.substring(0, MAX_MSG_CHARS) + TRUNCATION_SUFFIX else msg
        synchronized(LOCK) {
            val e = Entry(level, tag, bounded, sNextSequence++)
            sEntries.addLast(e)
            sCountByLevel[level.ordinal]++
            sTotalChars += bounded.length
            // Evict oldest until BOTH the entry-count AND char budgets are satisfied. Keep at
            // least the just-added entry (size > 1) so a lone large message can't empty the buffer.
            while (sEntries.size > MAX_ENTRIES ||
                   (sTotalChars > MAX_TOTAL_CHARS && sEntries.size > 1)) {
                val evicted = sEntries.pollFirst() ?: break
                sCountByLevel[evicted.level.ordinal]--
                sTotalChars -= evicted.message.length
            }
            sChangeStamp++
        }
    }

    // ── Public logging methods ────────────────────────────────────────────────

    @JvmStatic
    fun log(level: Level, tag: String, msg: String) {
        addEntry(level, tag, msg)
        // Mirror to logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, msg)
            Level.INFO -> Log.i(tag, msg)
            Level.WARN -> Log.w(tag, msg)
            Level.ERROR -> Log.e(tag, msg)
        }
    }

    /** Backward-compatible : log(tag, msg) → INFO */
    @JvmStatic
    fun log(tag: String, msg: String) = log(Level.INFO, tag, msg)

    @JvmStatic
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)

    @JvmStatic
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)

    @JvmStatic
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)

    @JvmStatic
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    /** Throwable variant — stores message + exception type in the buffer,
     *  delegates the full stack trace to Log.e() (visible in logcat/ADB). */
    @JvmStatic
    fun e(tag: String, msg: String, t: Throwable?) {
        val full = if (t != null)
            "$msg [${t.javaClass.simpleName}: ${t.message ?: t.javaClass.name}]"
        else msg
        addEntry(Level.ERROR, tag, full)
        Log.e(tag, msg, t) // full stack trace in logcat
    }

    @JvmStatic
    fun w(tag: String, msg: String, t: Throwable?) {
        val full = if (t != null)
            "$msg [${t.javaClass.simpleName}: ${t.message ?: t.javaClass.name}]"
        else msg
        addEntry(Level.WARN, tag, full)
        Log.w(tag, msg, t)
    }

    // ── Timing helpers ────────────────────────────────────────────────────────

    /** Starts a timer. Pass the return value to endTiming(). */
    @JvmStatic
    fun startTiming(): Long = System.currentTimeMillis()

    /** Logs "[tag] msg (42 ms)" at DEBUG level. */
    @JvmStatic
    fun endTiming(tag: String, start: Long, msg: String) {
        val ms = System.currentTimeMillis() - start
        d(tag, "$msg ($ms ms)")
    }

    // ── Lifecycle helper ──────────────────────────────────────────────────────

    /** Logs a lifecycle event (DEBUG) with the activity name and thread name. */
    @JvmStatic
    fun lifecycle(className: String, event: String) {
        d("Lifecycle", "$className → $event  [${Thread.currentThread().name}]")
    }

    // ── Buffer access ─────────────────────────────────────────────────────────

    /** Returns the number of entries in the buffer without allocating a copy. */
    @JvmStatic
    fun getEntriesCount(): Int = synchronized(LOCK) { sEntries.size }

    /** Returns the mutation stamp — increases on every add and clear. See
     *  [sChangeStamp] for why pollers must not rely on getEntriesCount(). */
    @JvmStatic
    fun getChangeStamp(): Long = synchronized(LOCK) { sChangeStamp }

    /** Returns an immutable copy of the buffer (used by LogActivity). */
    @JvmStatic
    fun getEntries(): List<Entry> =
        synchronized(LOCK) { Collections.unmodifiableList(ArrayList(sEntries)) }

    /**
     * Returns either the full retained buffer or only entries appended after [knownLastSequence].
     * A delta is safe only while generation and first-retained sequence are unchanged; this
     * detects both clear() and eviction by entry-count or character budget.
     */
    @JvmStatic
    fun getEntryUpdate(
        knownGeneration: Long,
        knownFirstSequence: Long,
        knownLastSequence: Long
    ): EntryUpdate = synchronized(LOCK) {
        val firstSequence = sEntries.peekFirst()?.sequence ?: sNextSequence
        val lastSequence = sEntries.peekLast()?.sequence ?: (sNextSequence - 1L)
        val appendOnly = LogUpdatePolicy.canAppend(
            knownGeneration, knownFirstSequence, knownLastSequence,
            sGeneration, firstSequence, lastSequence
        )
        val entries: List<Entry> = if (appendOnly) {
            val delta = ArrayList<Entry>()
            val iterator = sEntries.descendingIterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.sequence <= knownLastSequence) break
                delta.add(entry)
            }
            delta.reverse()
            Collections.unmodifiableList(delta)
        } else {
            Collections.unmodifiableList(ArrayList(sEntries))
        }
        EntryUpdate(
            sGeneration, firstSequence, lastSequence, entries, appendOnly,
            sEntries.size, sCountByLevel.copyOf()
        )
    }

    /** Returns the full buffer as a formatted String (for text sharing). */
    @JvmStatic
    fun get(): String {
        val fmt = sFmt.get()!!
        // 1.2.31 — snapshot the buffer under LOCK, format outside. Previously
        // the format loop ran inside synchronized(LOCK) and blocked every
        // logger thread (Beta probes, ADB worker, daemon connect) for the
        // duration of formatting 3000 entries (~5-10 ms). Aligned with
        // getEntries() which already uses the snapshot pattern.
        val snapshot: Array<Entry>
        synchronized(LOCK) {
            snapshot = sEntries.toTypedArray()
        }
        val sb = StringBuilder(snapshot.size * 80)
        for (e in snapshot) {
            sb.append("[").append(fmt.format(Date(e.timestamp))).append("]")
                .append("[").append(e.level.name).append("]")
                .append("[").append(e.tag).append("] ")
                .append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 1.2.31 — Returns a snapshot copy of the per-level entry counts indexed
     * by [Level.ordinal]. O(Level.values().size) instead of O(N) so the
     * LogActivity chips can refresh at 2 Hz without walking the full buffer.
     */
    @JvmStatic
    fun getCountByLevel(): IntArray = synchronized(LOCK) { sCountByLevel.copyOf() }

    @JvmStatic
    fun clear() {
        synchronized(LOCK) {
            sEntries.clear()
            sCountByLevel.fill(0)
            sTotalChars = 0L
            sGeneration++
            sChangeStamp++ // clear is a mutation too — viewers must refresh
        }
    }

    // ── File save ─────────────────────────────────────────────────────────────

    /**
     * Writes the log buffer to a timestamped .log file in getExternalFilesDir().
     * Retrievable via: adb pull /sdcard/Android/data/com.byd.dashcast/files/
     * @return the created File, or null on error
     */
    @JvmStatic
    fun saveToFile(context: Context): File? = writeFile(context, "byd_log_", get())

    /**
     * Generic helper — writes [content] to a timestamped
     * `<prefix><yyyyMMdd_HHmmss>.log` file in getExternalFilesDir()
     * (falling back to internal storage). Used by [share] and
     * [shareWithReport] to guarantee every "send log" action emits a real
     * .log file attachment instead of plain text.
     *
     * @return the created File, or null on I/O error.
     */
    @JvmStatic
    fun writeFile(context: Context, prefix: String, content: String?): File? {
        val filename = prefix + sFileFmt.get()!!.format(Date()) + ".log"
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, filename)
        return try {
            FileWriter(outFile).use { fw -> fw.write(content ?: "") }
            outFile
        } catch (ex: IOException) {
            Log.e("AppLogger", "writeFile failed: $filename", ex)
            null
        }
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    /**
     * Saves the log buffer to a .log file and opens the share chooser with the
     * file attached as a `content://` URI (via FileProvider). If the file
     * can't be written, falls back to plain text so the user is never blocked.
     */
    @JvmStatic
    fun share(context: Context) {
        val logFile = saveToFile(context)
        if (logFile != null) {
            shareFile(
                context, logFile,
                "DashCast — Log",
                context.getString(R.string.share_log_title)
            )
            return
        }
        // Fallback: file write failed (rare — typically no external storage).
        Log.w("AppLogger", "share: file write failed, falling back to text")
        var content = get()
        if (content.isEmpty()) content = "(empty log)"
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Log (text fallback)")
        intent.putExtra(Intent.EXTRA_TEXT, content)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log_title)))
    }

    /**
     * Combines an arbitrary report with the current log buffer, writes them to
     * a single timestamped .log file and shares it as an attachment. Used by
     * SysInfoActivity and DiagActivity to send a self-contained file to the
     * developer instead of pasting walls of text.
     */
    @JvmStatic
    fun shareWithReport(context: Context, reportText: String?) {
        val combined = (reportText ?: "") +
            "\n\n════════════════════════════════════\n" +
            "LOG\n" +
            "════════════════════════════════════\n" +
            get()
        val f = writeFile(context, "byd_report_", combined)
        if (f != null) {
            shareFile(
                context, f,
                "DashCast — Report + Log",
                context.getString(R.string.share_report_title)
            )
            return
        }
        // Fallback if write failed (no external storage etc.).
        Log.w("AppLogger", "shareWithReport: file write failed, falling back to text")
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — Report + Log (text fallback)")
        intent.putExtra(Intent.EXTRA_TEXT, combined)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_report_title)))
    }

    /**
     * Generic file-attachment share. Use this from any activity that wants to
     * send a .log/.txt file to the developer (DiagActivity "Beta Engine"
     * report, custom diagnostics, …). The MIME type is forced to text/plain so
     * any chooser entry (email, messaging, drive) accepts the file.
     */
    @JvmStatic
    fun shareFile(context: Context, file: File?, subject: String, chooserTitle: String) {
        if (file == null || !file.exists()) {
            Log.w("AppLogger", "shareFile: missing file")
            return
        }
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    // ── Telegram targeted share ───────────────────────────────────────────────

    /**
     * Same as [shareWithReport] but tries to open Telegram directly
     * (org.telegram.messenger, then org.telegram.messenger.web, then
     * nekogram/plus variants). Falls back to the generic chooser if no
     * Telegram package is installed. Used by the DL5 Cluster recon panel so
     * the tester can ship the recon report to the developer in one tap.
     */
    // getPackageInfo(String, int) is deprecated as of API 33 but is the only
    // overload available on the API 29 target (the PackageInfoFlags variant is
    // API 33+). Suppress the unavoidable deprecation, as the original Java did.
    @JvmStatic
    @Suppress("DEPRECATION")
    fun shareReportToTelegram(context: Context, reportText: String?) {
        val combined = (reportText ?: "") +
            "\n\n════════════════════════════════════\n" +
            "LOG\n" +
            "════════════════════════════════════\n" +
            get()
        val f = writeFile(context, "byd_report_", combined)
        // Known Telegram package candidates, ordered by popularity.
        val tgPkgs = arrayOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "nekox.messenger",
            "org.thunderdog.challegram"
        )
        val pm = context.packageManager
        var hit: String? = null
        for (p in tgPkgs) {
            try {
                pm.getPackageInfo(p, 0)
                hit = p
                break
            } catch (ignored: PackageManager.NameNotFoundException) {
            }
        }
        if (f != null && hit != null) {
            try {
                val uri = FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", f
                )
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.setPackage(hit)
                intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — DL5 Cluster Recon")
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (ex: Exception) {
                Log.w("AppLogger", "shareReportToTelegram: direct intent failed, falling back", ex)
            }
        }
        // Fallback: generic chooser (text-only if file write failed).
        if (f != null) {
            shareFile(
                context, f, "DashCast — DL5 Cluster Recon",
                context.getString(R.string.share_report_title)
            )
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, "DashCast — DL5 Cluster Recon (text fallback)")
        intent.putExtra(Intent.EXTRA_TEXT, combined)
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.share_report_title))
        )
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
    @JvmStatic
    fun cleanupFiles(context: Context): Int {
        var deleted = 0

        // External files dir: byd_log_*, byd_report_*, BYD_RE_Sniffer_*
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null && extDir.exists()) {
            val files = extDir.listFiles()
            if (files != null) {
                for (f in files) {
                    val name = f.name
                    if (name.startsWith("byd_log_") ||
                        name.startsWith("byd_report_") ||
                        name.startsWith("BYD_RE_Sniffer_")
                    ) {
                        if (f.delete()) deleted++
                    }
                }
            }
        }

        // Internal files dir: same patterns (fallback when external not available)
        val intDir = context.filesDir
        if (intDir != null && intDir.exists()) {
            val files = intDir.listFiles()
            if (files != null) {
                for (f in files) {
                    val name = f.name
                    if (name.startsWith("byd_log_") ||
                        name.startsWith("byd_report_") ||
                        name.startsWith("BYD_RE_Sniffer_")
                    ) {
                        if (f.delete()) deleted++
                    }
                }
            }
        }

        // External cache: cluster_live.png
        val extCache = context.externalCacheDir
        if (extCache != null && extCache.exists()) {
            val png = File(extCache, "cluster_live.png")
            if (png.exists() && png.delete()) deleted++
        }

        // Clear in-memory buffer too
        clear()

        Log.i("AppLogger", "cleanupFiles: $deleted file(s) deleted")
        return deleted
    }

    /**
     * v1.2.55-beta — caps the on-disk accumulation of generated files by
     * keeping only the [keepPerPrefix] most recent entries per prefix
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
    @JvmStatic
    fun pruneOldFiles(context: Context?, keepPerPrefix: Int): Int {
        if (context == null || keepPerPrefix < 1) return 0
        val prefixes = arrayOf("byd_log_", "byd_report_", "BYD_RE_Sniffer_")
        var deleted = 0
        val dirs = arrayOf(context.getExternalFilesDir(null), context.filesDir)
        for (dir in dirs) {
            if (dir == null || !dir.exists()) continue
            val entries = dir.listFiles() ?: continue
            for (prefix in prefixes) {
                val matches = ArrayList<File>()
                for (f in entries) {
                    if (f != null && f.isFile && f.name.startsWith(prefix)) {
                        matches.add(f)
                    }
                }
                if (matches.size <= keepPerPrefix) continue
                // Sort most-recent first (descending lastModified).
                matches.sortWith(compareByDescending { it.lastModified() })
                for (i in keepPerPrefix until matches.size) {
                    if (matches[i].delete()) deleted++
                }
            }
        }
        // Vosk directory: remove orphan partial-download temp files.
        // Uses File constructor to avoid creating the directory if it doesn't
        // exist (getExternalFilesDir("vosk") would create it as a side effect).
        val extBase = context.getExternalFilesDir(null)
        if (extBase != null) {
            val voskDir = File(extBase, "vosk")
            if (voskDir.isDirectory) {
                val voskEntries = voskDir.listFiles()
                if (voskEntries != null) {
                    for (f in voskEntries) {
                        if (f != null && f.isFile && f.name.endsWith(".download.tmp")) {
                            if (f.delete()) deleted++
                        }
                    }
                }
            }
            // exported_apks: keep the 2 most recent, delete the rest.
            val exportDir = File(extBase, "exported_apks")
            if (exportDir.isDirectory) {
                val apks = ArrayList<File>()
                val exportEntries = exportDir.listFiles()
                if (exportEntries != null) {
                    for (f in exportEntries) {
                        if (f != null && f.isFile && f.name.endsWith(".apk")) {
                            apks.add(f)
                        }
                    }
                }
                if (apks.size > 2) {
                    apks.sortWith(compareByDescending { it.lastModified() })
                    for (i in 2 until apks.size) {
                        if (apks[i].delete()) deleted++
                    }
                }
            }
        }
        if (deleted > 0) Log.i(
            "AppLogger",
            "pruneOldFiles: $deleted file(s) deleted (keep $keepPerPrefix/prefix)"
        )
        return deleted
    }
}
