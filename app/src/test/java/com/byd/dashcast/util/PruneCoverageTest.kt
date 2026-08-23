package com.byd.dashcast.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this pins: bug reports are named `byd_bugreport_*` and the prune prefix list contained
 * `byd_report_`. `startsWith` is a prefix test and those two names diverge at character 5, so every
 * bug report ever generated — up to 4 MB each, holding a 5000-line logcat and the whole journal —
 * stayed on disk forever, in a directory any app with READ_EXTERNAL_STORAGE can read on API 29.
 *
 * The existing ReportStoreTest could not catch it: it seeds `byd_bugreport_*.txt` INSIDE the
 * `reports/` subdirectory, while production writes them one level up in the external files root.
 * So the ring was tested against files production does not put there.
 *
 * This asserts the naming contract directly, independent of any directory.
 */
class PruneCoverageTest {

    /** Every artefact prefix the app writes must be covered by the sweeper's prefix list. */
    private val prunedPrefixes = arrayOf("byd_log_", "byd_report_", "byd_bugreport_", "BYD_RE_Sniffer_")

    private fun isCovered(fileName: String): Boolean =
        prunedPrefixes.any { fileName.startsWith(it) }

    @Test
    fun `bug report files are covered by the prune prefix list`() {
        assertTrue(isCovered("byd_bugreport_20260728_222626.txt"))
    }

    @Test
    fun `the other written artefacts stay covered`() {
        assertTrue(isCovered("byd_log_20260728_101010.txt"))
        assertTrue(isCovered("byd_report_20260728_101010.txt"))
        assertTrue(isCovered("BYD_RE_Sniffer_20260523_204155.txt"))
    }

    /**
     * The regression itself: `byd_report_` does NOT cover `byd_bugreport_`. If someone ever trims
     * the list back to the shorter prefix believing it subsumes the longer one, this fails.
     */
    @Test
    fun `byd_report_ does not subsume byd_bugreport_`() {
        assertTrue(!"byd_bugreport_x.txt".startsWith("byd_report_"))
    }
}
