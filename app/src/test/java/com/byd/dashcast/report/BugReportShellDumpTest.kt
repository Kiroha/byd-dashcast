package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the bug report asks the shell for.
 *
 * Every defect this command has had was a silently empty section, never a crash: a `head`
 * exhausted on the wrong display, a filterspec naming tags this ROM does not carry, an alternation
 * missing the service that actually logs. Each produced a report that looked complete and answered
 * nothing, and none could fail a test while the command was a local variable inside capture().
 */
class BugReportShellDumpTest {

    private val cmd = BugReportCapture.buildShellDump("/tmp/report.txt")

    @Test
    fun `the cluster SurfaceFlinger pass cannot be spent on the default display`() {
        // INC-20260826-194829: a bare `layerStack` matched every layer on display 0, so the pass
        // exhausted its window there and all 27 captured lines said layerStack=0.
        assertTrue(cmd.contains("layerStack=[1-9]"))
        assertFalse("a bare layerStack alternative matches display 0 first",
            cmd.contains("|layerStack|"))
    }

    @Test
    fun `the mirror is asked for by name`() {
        // A mirror made with SurfaceControl.createDisplay never shows in `dumpsys display`.
        // SurfaceFlinger is the only window onto it, so the leak check depends on this token.
        assertTrue(cmd.contains("byd_myapp_mirror"))
    }

    @Test
    fun `the z-order pass starts at the layer list, not at the preamble`() {
        // 66 of the old 150 lines went to `connections (count=66)` before any layer appeared.
        assertTrue(cmd, cmd.contains("sed -n '/Visible layers/,\$p'"))
    }

    @Test
    fun `the OEM pass names the DiLink 3 service that records the shape commands`() {
        // xdja_AutoContainerService logged all three cluster commands to the millisecond while
        // the section meant to capture OEM projection matched only DL5.0 tags and came back empty.
        assertTrue(cmd.contains("xdja_AutoContainerService"))
        assertTrue("the DL5.0 tags stay — this is an addition, not a swap",
            cmd.contains("BydProjectionService"))
    }

    @Test
    fun `the SurfaceFlinger passes are not bounded so tightly that they cannot answer`() {
        // Scoped to the two SF passes on purpose — `head -40` is a fine bound on a `dumpsys window`
        // focus grep, and was fatal here only because a bare layerStack matched 86 layers first.
        val sf = cmd.split("SURFACEFLINGER").drop(1).joinToString("\n")
        assertTrue("the SF sections must still exist", sf.isNotEmpty())
        assertFalse("40 lines could not clear display 0", sf.contains("head -40"))
        assertFalse("neither could 150 from the top of the dump",
            sf.contains("SurfaceFlinger 2>/dev/null | head -150"))
    }

    @Test
    fun `every section appends to the path it was given`() {
        assertTrue(cmd.contains("/tmp/report.txt"))
        // One truncating redirect only: the first line, which creates the file.
        assertTrue("the dump must create the file exactly once",
            Regex("[^>]> /tmp/report\\.txt").findAll(cmd).count() == 1)
    }
}
