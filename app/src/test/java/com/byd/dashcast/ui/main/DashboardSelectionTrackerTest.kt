package com.byd.dashcast.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSelectionTrackerTest {

    @Test
    fun duplicateTapWhileBootLookupPendingIsIgnored() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        assertTrue(tracker.markBootPending("com.nav", first.generation))

        val duplicate = tracker.begin("com.nav")

        assertTrue(duplicate.isDuplicatePending)
        assertEquals(first.generation, duplicate.generation)
    }

    @Test
    fun selectingAnotherAppInvalidatesOldBootResult() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markBootPending("com.nav", first.generation)

        val second = tracker.begin("com.media")

        assertFalse(second.isDuplicatePending)
        assertFalse(tracker.completeBoot("com.nav", first.generation))
        assertNull(tracker.takePendingForInvalidation())
    }

    @Test
    fun recreationReturnsPendingPackageForRestoration() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markBootPending("com.nav", first.generation)

        assertEquals("com.nav", tracker.takePendingForInvalidation())
        assertFalse(tracker.completeBoot("com.nav", first.generation))
    }

    @Test
    fun completedLookupClearsPendingState() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markBootPending("com.nav", first.generation)

        assertTrue(tracker.completeBoot("com.nav", first.generation))
        assertNull(tracker.takePendingForInvalidation())
        assertFalse(tracker.begin("com.nav").isDuplicatePending)
    }
}
