package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.*
import org.junit.Test

class EmptyPrimaryStackRecoveryTest {
    private fun before() = EmptyPrimaryStackRecovery.Snapshot(29, EmptyPrimaryStackRecovery.FINGERPRINT,
        0, ClusterDisplayInfo(2, "fission_bg_xdjaVirtualSurface", 1920, 720, 2,
            false, 1000, "com.xdja.containerservice", "ON"), 1, true, TaskLocation.absent(),
        listOf(EmptyPrimaryStackRecovery.Stack(28, 2, 3, 1, emptyList())))
    private fun after() = before().copy(location = TaskLocation.found(42, 2),
        stacks = listOf(EmptyPrimaryStackRecovery.Stack(28, 2, 3, 1, listOf(42))))

    @Test fun `one command requires fresh live empty original primary and confirms task`() {
        var launches = 0
        val result = EmptyPrimaryStackRecovery.recover(2, object : EmptyPrimaryStackRecovery.Operations {
            override fun isCurrent() = true
            override fun snapshot() = if (launches == 0) before() else after()
            override fun launchPrimary() { launches++ }
        })
        assertEquals(1, launches); assertEquals(42, result!!.taskId)
        assertFalse(EmptyPrimaryStackRecovery.launchCommand("com.waze/.Main", 2).contains(" -S"))
    }
    @Test fun `cancellation before entry during metadata and after command fail closed`() {
        for (cancelAt in 0..2) {
            var reads = 0; var launches = 0
            assertNull(EmptyPrimaryStackRecovery.recover(2, object : EmptyPrimaryStackRecovery.Operations {
                override fun isCurrent() = reads < cancelAt
                override fun snapshot(): EmptyPrimaryStackRecovery.Snapshot { reads++; return if (launches == 0) before() else after() }
                override fun launchPrimary() { launches++ }
            }))
            assertEquals(if (cancelAt == 2) 1 else 0, launches)
        }
    }
    @Test fun `unknown platform task display capability or stacks never authorize recovery`() {
        val good = before()
        val invalid = listOf(good.copy(api = 30), good.copy(fingerprint = "other"), good.copy(userId = 10),
            good.copy(displayMode = 0), good.copy(activitySupportsSplit = false),
            good.copy(location = TaskLocation.unknown()), good.copy(location = TaskLocation.found(42, 0)),
            good.copy(display = good.display!!.copy(state = "OFF")),
            good.copy(display = good.display!!.copy(ownerUid = 10001)), good.copy(stacks = null),
            good.copy(stacks = good.stacks!! + good.stacks!!),
            good.copy(stacks = listOf(good.stacks!!.single().copy(taskIds = listOf(9)))),
            good.copy(stacks = listOf(good.stacks!!.single().copy(taskIds = null))))
        invalid.forEach { assertNull(EmptyPrimaryStackRecovery.selectPrimary(it, 2)) }
    }
    @Test fun `replacement stack and wrong display are not success`() {
        assertFalse(EmptyPrimaryStackRecovery.confirmsPrimary(after().copy(stacks = listOf(
            EmptyPrimaryStackRecovery.Stack(29, 2, 3, 1, listOf(42)))), 2, 28))
        assertFalse(EmptyPrimaryStackRecovery.confirmsPrimary(after().copy(location = TaskLocation.found(42, 0)), 2, 28))
    }
}
