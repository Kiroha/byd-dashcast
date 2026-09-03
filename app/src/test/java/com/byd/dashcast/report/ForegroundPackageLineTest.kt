package com.byd.dashcast.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two lines below are verbatim from INC-20260826-194829, where the wizard reported
 * "Application inconnue" while Waze sat resumed on the cluster. Both are what Android 10 prints;
 * neither contains the string the wizard was grepping for.
 */
class ForegroundPackageLineTest {

    @Test
    fun `the API 29 task line names the package`() {
        assertEquals("com.waze", ForegroundPackageLine.parse(
            "    * TaskRecord{5cae0a3 #26 A=com.waze U=0 StackId=20 sz=1}"))
    }

    @Test
    fun `the API 29 component line names the package`() {
        assertEquals("com.waze", ForegroundPackageLine.parse(
            "      mActivityComponent=com.waze/.FreeMapAppActivity"))
    }

    @Test
    fun `the form other DiLink generations print still works`() {
        assertEquals("com.waze", ForegroundPackageLine.parse(
            "realActivity=com.waze/.FreeMapAppActivity"))
    }

    /**
     * The regression this file exists for. The command and the parser used to name their markers
     * separately, so the command could ask for a string the parser did not read — which is exactly
     * what shipped — and nothing failed.
     */
    @Test
    fun `every marker the command greps for is one the parser can read`() {
        val markers = ForegroundPackageLine.GREP_ALTERNATION.split("|")
        assertTrue("the alternation must not be empty", markers.isNotEmpty())
        for (marker in markers) {
            assertEquals("the command asks for '$marker' but the parser ignores it",
                "com.example.app",
                ForegroundPackageLine.parse("prefix$marker" + "com.example.app/.Main u0 t26"))
        }
    }

    @Test
    fun `a line naming no package yields nothing`() {
        assertNull(ForegroundPackageLine.parse(null))
        assertNull(ForegroundPackageLine.parse(""))
        assertNull(ForegroundPackageLine.parse("Display #1 (activities from top to bottom):"))
    }

    @Test
    fun `something that is not a package name is refused`() {
        // A truncated or reordered dump must not have a stack id reported as an application.
        assertNull(ForegroundPackageLine.parse("StackId=20 A=26 sz=1"))
        assertNull(ForegroundPackageLine.parse("mActivityComponent="))
    }

    @Test
    fun `the most specific marker wins when a line carries several`() {
        assertEquals("com.byd.dashcast", ForegroundPackageLine.parse(
            "realActivity=com.byd.dashcast/.MainActivity task=TaskRecord{a #1 A=com.other U=0}"))
    }
}
