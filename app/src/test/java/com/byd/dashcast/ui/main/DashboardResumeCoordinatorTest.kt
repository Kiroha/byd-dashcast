package com.byd.dashcast.ui.main

import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.*
import org.junit.Test

class DashboardResumeCoordinatorTest {
    private class Host : DashboardResumeCoordinator.Host {
        private val expectedService = Any()
        var service = expectedService
        var display = 9
        var alive = true
        val events = mutableListOf<String>()
        lateinit var locationReply: (TaskLocation) -> Unit
        lateinit var reanchorReply: (Boolean) -> Unit
        lateinit var reanchorIsCurrent: () -> Boolean
        override fun isOwnerCurrent() = alive && service === expectedService && display == 9
        override fun findLocation(callback: (TaskLocation) -> Unit) {
            events += "find"
            locationReply = callback
        }
        override fun reanchor(isCurrent: () -> Boolean, callback: (Boolean) -> Unit) {
            events += "reanchor"
            reanchorIsCurrent = isCurrent
            reanchorReply = callback
        }
        override fun onReady() { events += "mirror" }
        override fun onAbsent() { events += "launch" }
        override fun onUnknown() { events += "preserve" }
        override fun onReanchorFailed() { events += "failed" }
        override fun restoreBootLatch() { events += "restoreBoot" }
    }

    private fun request(tracker: DashboardSelectionTracker = DashboardSelectionTracker(),
                        fromBoot: Boolean = false): Host {
        val selection = tracker.begin("com.waze")
        return Host().also {
            DashboardResumeCoordinator(tracker).resume("com.waze", selection.generation, fromBoot, 9, it)
        }
    }

    @Test
    fun `saved Waze marker without a task relaunches instead of showing an empty mirror`() {
        val host = request()
        host.locationReply(TaskLocation.absent())
        assertEquals(listOf("find", "launch"), host.events)
    }

    @Test
    fun `task confirmed on the cluster is adopted without relaunching`() {
        val host = request()
        host.locationReply(TaskLocation.found(73, 9))
        assertEquals(listOf("find", "mirror"), host.events)
    }

    @Test
    fun `unreadable location preserves the saved session and permits the next retry`() {
        val tracker = DashboardSelectionTracker()
        val host = request(tracker)
        host.locationReply(TaskLocation.unknown())
        assertEquals(listOf("find", "preserve"), host.events)
        assertFalse(tracker.begin("com.waze").isDuplicatePending)
    }

    @Test
    fun `task on main is moved before adoption and never relaunched`() {
        val host = request()
        host.locationReply(TaskLocation.found(73, 0))
        assertEquals(listOf("find", "reanchor"), host.events)
        assertTrue(host.reanchorIsCurrent())
        host.reanchorReply(true)
        assertEquals(listOf("find", "reanchor", "mirror"), host.events)
    }

    @Test
    fun `failed reanchor does not launch or adopt the task`() {
        val host = request()
        host.locationReply(TaskLocation.found(73, 0))
        host.reanchorReply(false)
        assertEquals(listOf("find", "reanchor", "failed"), host.events)
    }

    @Test
    fun `late lookup after a new selection cannot clear or relaunch it`() {
        val tracker = DashboardSelectionTracker()
        val host = request(tracker)
        tracker.begin("com.other")
        host.locationReply(TaskLocation.absent())
        assertEquals(listOf("find"), host.events)
    }

    @Test
    fun `layout selection or OEM restore cancels queued reanchor and late adoption`() {
        val tracker = DashboardSelectionTracker()
        val host = request(tracker)
        host.locationReply(TaskLocation.found(73, 0))
        tracker.begin(null)
        assertFalse("the worker must refuse the stale move", host.reanchorIsCurrent())
        host.reanchorReply(true)
        assertEquals(listOf("find", "reanchor"), host.events)
    }

    @Test
    fun `service replacement display change and destroyed activity reject old replies`() {
        for (invalidate in listOf<(Host) -> Unit>(
            { it.service = Any() }, { it.display = 10 }, { it.alive = false })) {
            val tracker = DashboardSelectionTracker()
            val host = request(tracker)
            invalidate(host)
            host.locationReply(TaskLocation.absent())
            assertEquals(listOf("find"), host.events)
            assertFalse(tracker.begin("com.waze").isDuplicatePending)
        }
    }

    @Test
    fun `only a boot adoption restores the boot latch on unknown location`() {
        val host = request(fromBoot = true)
        host.locationReply(TaskLocation.unknown())
        assertEquals(listOf("find", "restoreBoot", "preserve"), host.events)
    }

    @Test
    fun `failed boot reanchor keeps the next tap from relaunching a live task`() {
        val host = request(fromBoot = true)
        host.locationReply(TaskLocation.found(73, 0))
        host.reanchorReply(false)
        assertEquals(listOf("find", "reanchor", "restoreBoot", "failed"), host.events)
    }
}
