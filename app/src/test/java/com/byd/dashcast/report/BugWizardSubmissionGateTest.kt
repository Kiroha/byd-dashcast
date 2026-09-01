package com.byd.dashcast.report

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugWizardSubmissionGateTest {

    @After
    fun reset() {
        BugWizardSubmissionGate.resetForTest()
    }

    @Test
    fun `only one wizard instance can own an active submission`() {
        val token = BugWizardSubmissionGate.claim()

        assertNotNull(token)
        assertNull(BugWizardSubmissionGate.claim())
        assertEquals(token, BugWizardSubmissionGate.activeToken())
    }

    @Test
    fun `stale owner cannot alter or release a newer submission`() {
        val first = BugWizardSubmissionGate.claim()!!
        BugWizardSubmissionGate.release(first)
        val second = BugWizardSubmissionGate.claim()!!

        BugWizardSubmissionGate.setBackgroundWork(first, true)
        BugWizardSubmissionGate.release(first)

        assertTrue(BugWizardSubmissionGate.isActive(second))
        assertFalse(BugWizardSubmissionGate.hasBackgroundWork(second))
    }

    @Test
    fun `background ownership follows the active token`() {
        val token = BugWizardSubmissionGate.claim()!!

        BugWizardSubmissionGate.setBackgroundWork(token, true)
        assertTrue(BugWizardSubmissionGate.hasBackgroundWork(token))

        BugWizardSubmissionGate.setBackgroundWork(token, false)
        assertFalse(BugWizardSubmissionGate.hasBackgroundWork(token))
    }
}