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
        assertTrue(tracker.markPending("com.nav", first.generation, fromBoot = true))

        val duplicate = tracker.begin("com.nav")

        assertTrue(duplicate.isDuplicatePending)
        assertEquals(first.generation, duplicate.generation)
    }

    @Test
    fun selectingAnotherAppInvalidatesOldBootResult() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markPending("com.nav", first.generation, fromBoot = true)

        val second = tracker.begin("com.media")

        assertFalse(second.isDuplicatePending)
        assertFalse(tracker.complete("com.nav", first.generation))
        assertNull(tracker.takePendingForInvalidation())
    }

    @Test
    fun recreationReturnsPendingPackageForRestoration() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markPending("com.nav", first.generation, fromBoot = true)

        assertEquals("com.nav", tracker.takePendingForInvalidation())
        assertFalse(tracker.complete("com.nav", first.generation))
    }

    @Test
    fun completedLookupClearsPendingState() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        tracker.markPending("com.nav", first.generation, fromBoot = true)

        assertTrue(tracker.complete("com.nav", first.generation))
        assertNull(tracker.takePendingForInvalidation())
        assertFalse(tracker.begin("com.nav").isDuplicatePending)
    }

    @Test
    fun cachedLookupDeduplicatesWithoutInventingABootLaunchOnRecreation() {
        val tracker = DashboardSelectionTracker()
        val first = tracker.begin("com.nav")
        assertTrue(tracker.markPending("com.nav", first.generation))
        assertTrue(tracker.begin("com.nav").isDuplicatePending)

        assertNull(tracker.takePendingForInvalidation())
        assertFalse(tracker.complete("com.nav", first.generation))
    }

    @Test
    fun oldCachedResultCannotCompleteANewerSelectionOfTheSamePackage() {
        val tracker = DashboardSelectionTracker()
        val old = tracker.begin("com.nav")
        tracker.markPending("com.nav", old.generation)
        tracker.begin("com.other")
        val current = tracker.begin("com.nav")
        tracker.markPending("com.nav", current.generation)

        assertFalse(tracker.complete("com.nav", old.generation))
        assertTrue(tracker.complete("com.nav", current.generation))
    }
}
