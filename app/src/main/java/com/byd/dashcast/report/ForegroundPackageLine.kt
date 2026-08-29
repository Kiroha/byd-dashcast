package com.byd.dashcast.report

/**
 * Reads the foreground package out of one line of `dumpsys activity activities`.
 *
 * The wizard used to grep for `realActivity` alone. INC-20260826-194829 contains that string
 * exactly zero times — its two occurrences are the echo of our own command — while Waze was
 * resumed on the cluster the whole session. Android 10 prints the task as
 * `TaskRecord{.. #26 A=com.waze ..}` and the component as `mActivityComponent=com.waze/..`, so the
 * probe came back empty and the report's own header said "Application inconnue" about a session
 * whose cluster task was unambiguous.
 *
 * The grep alternation is derived from the same list the parser reads, because the previous
 * arrangement had the two written out separately and nothing failed when only one was right. A
 * marker the command asks for that the parser cannot read — or the reverse — is a test failure
 * here, not an empty field in a report six weeks later.
 */
object ForegroundPackageLine {

    /**
     * Markers that can name the package, best first.
     *
     * `realActivity=` stays ahead even though this ROM never emits it: other DiLink generations
     * do, and it is the most specific of the three. ` A=` keeps its leading space — without it the
     * alternation matches any capital A followed by an equals sign anywhere in the dump.
     */
    private val MARKERS = listOf("realActivity=", "mActivityComponent=", " A=")

    /** What the shell command must grep for. Derived, never written out a second time. */
    @JvmField
    val GREP_ALTERNATION: String = MARKERS.joinToString("|")

    /** @return the package name, or null when this line names none. */
    @JvmStatic
    fun parse(line: String?): String? {
        if (line.isNullOrEmpty()) return null
        for (marker in MARKERS) {
            val at = line.indexOf(marker)
            if (at < 0) continue
            val rest = line.substring(at + marker.length)
            val end = rest.indexOfFirst { it == '/' || it == ' ' || it == '}' || it == ',' || it == '\t' }
            val pkg = (if (end >= 0) rest.substring(0, end) else rest).trim()
            // A package name has a dot. The guard is what keeps a truncated or reordered dump from
            // reporting a stack id as an application.
            if (pkg.isNotEmpty() && pkg.contains('.')) return pkg
        }
        return null
    }
}
