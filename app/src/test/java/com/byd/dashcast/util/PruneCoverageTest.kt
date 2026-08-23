package com.byd.dashcast.util

import com.byd.dashcast.report.BugReportCapture
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the artefact naming contract: every prefix the app WRITES must be covered by the prefix list
 * the sweeper MATCHES.
 *
 * The bug this exists for: the sweeper listed `byd_report_` while BugReportCapture writes
 * `byd_bugreport_`. `startsWith` is a prefix test and those names diverge at character 5, so no bug
 * report ever matched — each up to 4 MB of logcat, dumpsys and journal, kept forever, in a directory
 * any app with READ_EXTERNAL_STORAGE can read on API 29.
 *
 * The first version of this test declared its OWN copy of the prefix list and asserted against that,
 * which meant it could not fail on the drift it claimed to guard: trimming the real list in
 * AppLogger would have left all of it green. It now asserts against the production constants
 * themselves, so it fails the moment either end of the contract moves.
 */
class PruneCoverageTest {

    private fun coveredByPruner(writtenPrefix: String): Boolean =
        AppLogger.PRUNED_PREFIXES.any { writtenPrefix.startsWith(it) }

    @Test
    fun `bug reports are covered by the pruner`() {
        assertTrue(
            "BugReportCapture.PREFIX='${BugReportCapture.PREFIX}' is not covered by " +
                "AppLogger.PRUNED_PREFIXES=${AppLogger.PRUNED_PREFIXES.toList()} — bug reports " +
                "would accumulate on disk forever",
            coveredByPruner(BugReportCapture.PREFIX)
        )
    }

    @Test
    fun `every other written artefact is covered by the pruner`() {
        for (written in listOf(AppLogger.PREFIX_LOG, AppLogger.PREFIX_REPORT, AppLogger.PREFIX_SNIFFER)) {
            assertTrue("'$written' is written but never swept", coveredByPruner(written))
        }
    }

    /**
     * Guards the specific near-miss: if someone concludes the shorter prefix subsumes the longer one
     * and trims the list, this fails. Asserted against the production constants, not literals.
     */
    @Test
    fun `the report prefix does not subsume the bug-report prefix`() {
        assertTrue(
            "PREFIX_REPORT unexpectedly covers BugReportCapture.PREFIX — if that is now true by " +
                "design, the pruned list may have been trimmed on a false assumption",
            !BugReportCapture.PREFIX.startsWith(AppLogger.PREFIX_REPORT)
        )
    }
}
