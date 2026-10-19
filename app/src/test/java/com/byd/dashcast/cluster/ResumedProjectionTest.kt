package com.byd.dashcast.cluster

import org.junit.Assert.*
import org.junit.Test

class ResumedProjectionTest {
    @Test fun `only resumed activity on requested display is proof`() {
        val dump = "Display #0 (activities):\n mResumedActivity: ActivityRecord{a u0 com.maps/.Main t2}\n" +
            "Display #9 (activities):\n mResumedActivity: ActivityRecord{b u0 com.other/.Main t3}\n"
        assertFalse(ResumedProjection.matches(dump, 9, "com.maps"))
        assertTrue(ResumedProjection.matches(dump, 9, "com.other"))
        assertFalse(ResumedProjection.matches(dump, 0, "com.maps"))
    }
    @Test fun `placement or global resumed record is insufficient`() {
        assertFalse(ResumedProjection.matches("mDisplayId=9\n mResumedActivity: ActivityRecord{a u0 com.maps/.Main t2}", 9, "com.maps"))
        assertFalse(ResumedProjection.matches("Display #9\n Task{com.maps/.Main}\n", 9, "com.maps"))
        assertFalse(ResumedProjection.matches("topResumedActivity=ActivityRecord{a u0 com.maps/.Main t2}", 9, "com.maps"))
    }
    @Test fun `ambiguous resumed components fail closed`() {
        val dump = "Display #9\n mResumedActivity: ActivityRecord{a u0 com.maps/.Main t2}\n" +
            " topResumedActivity=ActivityRecord{b u0 com.other/.Main t3}\n"
        assertFalse(ResumedProjection.matches(dump, 9, "com.maps"))
    }
    @Test fun `truncated dumps cannot supply proof`() {
        assertFalse(ResumedProjection.matches("Display #9\n mResumedActivity: ActivityRecord{a u0 com.maps/.Main t2}\n[output truncated]", 9, "com.maps"))
    }
    @Test fun `matching package prefix is not enough`() {
        assertFalse(ResumedProjection.matches("Display #9\n mResumedActivity: ActivityRecord{a u0 com.maps.evil/.Main t2}\n", 9, "com.maps"))
    }
}
