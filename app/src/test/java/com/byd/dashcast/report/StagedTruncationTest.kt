package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The A13 half of the report body cap.
 *
 * On DL5.1 the dump is staged in /data/local/tmp and read back through stdout, so
 * [BugReportCapture.readFile] — which held the only copy of the cap AND of its truncation banner —
 * never runs. The cap now lives in the shell (`head -c`), which means the shell is also the only
 * thing that knows the true size; it flags the cut with a trailer and this function turns it into
 * the banner a triager reads.
 *
 * What matters here is not the happy path but the two ways this can go wrong silently: a truncated
 * report that does NOT announce it (indistinguishable from a device that produced nothing — how
 * INC-20260727-203241 nearly went undiagnosed), and a body that gets cut because it happened to
 * quote the marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StagedTruncationTest {

    private val marker = "@@DASHCAST_BODY_TRUNCATED@@"

    @Test
    fun `an untruncated body is returned byte-for-byte`() {
        val body = "=== DASHCAST BUG REPORT ===\nsome dumpsys\n"
        assertEquals(body, BugReportCapture.applyStagedTruncation(body))
    }

    @Test
    fun `the trailer becomes the banner and carries the true size`() {
        val out = BugReportCapture.applyStagedTruncation("body cut here\n\n$marker 9876543")

        assertFalse("the machine trailer must not reach the report", out!!.contains(marker))
        assertTrue("the report must announce its own truncation", out.contains("REPORT TRUNCATED"))
        assertTrue("and name the size that was actually on the device", out.contains("9876543 bytes"))
        assertTrue("the body itself must survive", out.startsWith("body cut here"))
    }

    /**
     * The size is the one field that comes from the device, so it is the one that can arrive
     * malformed. A banner that says less is still infinitely better than no banner: the report
     * must never look complete when it is not.
     */
    @Test
    fun `an unparseable size still produces a banner`() {
        val out = BugReportCapture.applyStagedTruncation("body\n\n$marker notanumber")!!
        assertTrue(out.contains("REPORT TRUNCATED"))
        assertTrue(out.contains("larger than the cap"))
        assertFalse(out.contains(marker))
    }

    /**
     * A bug report quotes logs, and logs can quote a previous bug report. If the marker were
     * honoured anywhere in the body, one report pasted into a log would truncate the next one at
     * that point — losing real evidence and claiming a truncation that never happened.
     */
    @Test
    fun `a marker in the middle of the body is not a trailer`() {
        val body = "line one\n$marker 123\nthousands of lines of dumpsys that must survive\nEND"
        assertEquals(body, BugReportCapture.applyStagedTruncation(body))
    }

    @Test
    fun `a null body stays null`() {
        assertNull(BugReportCapture.applyStagedTruncation(null))
    }
}
