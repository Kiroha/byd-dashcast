package com.byd.dashcast.report

import android.content.Context
import com.byd.dashcast.util.AppLogger
import java.io.File

/**
 * Where diagnostic artefacts live, how much room they may take, and whether there is room at all.
 *
 * Two problems made this necessary, and they are the same problem seen twice.
 *
 * **Reachability.** Four of the six diagnostic emitters write their zip into `cacheDir` and then
 * print that path to the tester. `res/xml/file_paths.xml` declares no `cache-path`, so
 * `FileProvider.getUriForFile` throws on it and the share fallback cannot work; and
 * `/data/data/<pkg>/cache` is unreachable for the tester anyway. Artefacts belong under
 * `getExternalFilesDir("reports")`, which the existing `external-files-path name="logs" path="."`
 * entry already covers recursively — no manifest change needed.
 *
 * **Room.** The Azure sink lifted the 50 MB Telegram ceiling, so the extraction budget went from
 * 42 MB to 2 GB while nothing anywhere checks free space first. A search for `usableSpace` /
 * `freeSpace` / `StatFs` across `app/src/main` returns only the voice subsystem, which is itself
 * unreachable. [hasRoomFor] exists so both families — reports and extractions — ask the same
 * question of the same helper rather than each growing their own.
 *
 * Nothing here writes on its own: [prune] is the only destructive entry point, and it is bounded
 * three ways so a diagnostic session can never turn into the 1.08 GB accumulation the log rotation
 * comment in [AppLogger] still documents.
 */
object ReportStore {

    private const val TAG = "ReportStore"

    /** Subdirectory of the app's external files dir. Covered by `file_paths.xml` recursively. */
    const val DIR_NAME = "reports"

    /** Ring size: how many artefacts survive a prune, newest first. */
    const val KEEP_FILES = 3

    /** Anything older than this goes, however few there are. */
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Hard ceiling for the whole directory, oldest evicted first. */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /**
     * Headroom demanded on top of the requested size. A volume driven to exactly zero free bytes
     * misbehaves well before the last byte, and the caller usually needs a second copy (a zip built
     * next to its work directory) that it has not accounted for.
     */
    const val FREE_SPACE_MARGIN_BYTES = 96L * 1024 * 1024

    /**
     * Shallowest ancestor whose free space is accepted as an answer. `/storage/emulated/0` has
     * depth 3 and is the right volume; `/storage` and `/` are not.
     */
    const val MIN_MEASURABLE_DEPTH = 3

    /**
     * The shareable directory for report artefacts, created if needed.
     *
     * Mirrors the fallback in `BugReportCapture.newFile`: `getExternalFilesDir` routes through
     * StorageManagerService and can THROW SecurityException on some DL5.1 / Android 13 ROMs, which
     * is what stopped bug reports from being generated in July. On failure the canonical external
     * path is built directly — it bypasses the throwing API, and the uid-2000 shell can write there.
     */
    @JvmStatic
    fun dir(ctx: Context): File {
        var base: File? = null
        try {
            base = ctx.getExternalFilesDir(null)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "getExternalFilesDir threw (" + t.javaClass.simpleName
                    + ") — using canonical external path")
        }
        if (base == null) {
            base = File("/storage/emulated/0/Android/data/" + ctx.packageName + "/files")
        }
        val d = File(base, DIR_NAME)
        if (!d.exists()) {
            try { d.mkdirs() } catch (_: Throwable) { /* best-effort, same as the report path */ }
        }
        return d
    }

    /**
     * Usable bytes on the volume backing [target], or -1 when it cannot be established.
     *
     * `usableSpace` is preferred over `freeSpace`: it accounts for the reserve the OS keeps, so it
     * is the number a writer can actually consume.
     *
     * The directory may not exist yet, so the nearest existing ancestor is measured instead — but
     * the walk is BOUNDED. Left unbounded it climbs to `/` and reports the system partition, which
     * on these units is a different mount from the emulated storage the artefact is headed for: the
     * guard would then approve a write on the strength of the wrong volume's free space. A
     * shallow ancestor is therefore treated as "cannot tell" rather than as an answer.
     */
    @JvmStatic
    fun usableBytes(target: File): Long {
        var d: File? = target
        while (d != null && !d.exists()) d = d.parentFile
        if (d == null) return -1
        // Depth guard: "/", "/storage", "/data" and friends are not the volume we are asking about.
        if (depthOf(d) < MIN_MEASURABLE_DEPTH) return -1
        return try {
            val n = d.usableSpace
            if (n > 0) n else -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** Number of non-empty path components, e.g. `/storage/emulated/0` -> 3. */
    private fun depthOf(f: File): Int =
        f.absolutePath.split('/').count { it.isNotEmpty() }

    /**
     * True when [needBytes] plus [FREE_SPACE_MARGIN_BYTES] fits on the volume backing [target].
     *
     * Deliberately fails OPEN when the free space cannot be determined: refusing a diagnostic
     * because a ROM will not report its own storage would break the feature on exactly the units
     * that need diagnosing. A caller that must be strict can test [usableBytes] itself.
     */
    @JvmStatic
    fun hasRoomFor(target: File, needBytes: Long): Boolean {
        if (needBytes <= 0) return true
        val free = usableBytes(target)
        if (free < 0) return true
        return free >= needBytes + FREE_SPACE_MARGIN_BYTES
    }

    /**
     * Bounded ring over the reports directory: age first, then total size, then count.
     *
     * Order matters. Age is applied first so a single huge stale artefact cannot survive by being
     * the newest of three; size next so the ceiling holds whatever the count; count last as the
     * steady-state rule.
     *
     * @return bytes reclaimed.
     */
    @JvmStatic
    fun prune(ctx: Context): Long {
        val d = dir(ctx)
        val entries = d.listFiles()?.filter { it != null && it.isFile }?.toMutableList()
            ?: return 0L
        var freed = 0L
        val now = System.currentTimeMillis()

        fun drop(f: File) {
            val n = f.length()
            if (f.delete()) freed += n
        }

        // 1. Age.
        val stale = entries.filter { now - it.lastModified() > MAX_AGE_MS }
        stale.forEach { drop(it) }
        entries.removeAll(stale.toSet())

        // 2. Total size, oldest first.
        entries.sortByDescending { it.lastModified() }
        var total = entries.sumOf { it.length() }
        var i = entries.size - 1
        while (total > MAX_TOTAL_BYTES && i >= 0) {
            val f = entries[i]
            total -= f.length()
            drop(f)
            entries.removeAt(i)
            i--
        }

        // 3. Count.
        while (entries.size > KEEP_FILES) {
            drop(entries.removeAt(entries.size - 1))
        }

        if (freed > 0) {
            AppLogger.i(TAG, "pruned " + (freed / 1024) + " KB from " + DIR_NAME + "/")
        }
        return freed
    }
}
