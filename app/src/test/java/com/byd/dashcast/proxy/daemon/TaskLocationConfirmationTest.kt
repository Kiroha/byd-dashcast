package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.infrastructure.task.TaskLocation
import com.byd.dashcast.infrastructure.task.TaskLocationDumpParser
import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskLocationConfirmationTest {
    private val pkg = "com.example.navigation"
    private val output = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #2 (activities from top to bottom):
          * Task{abc #298 type=standard A=10020:$pkg U=0 visible=true sz=1}
            topResumedActivity=ActivityRecord{def u0 $pkg/.Main} t298}
        ${TaskLocationDumpParser.COMPLETION_MARKER}
    """.trimIndent()

    @Test fun `sixteen reflected misses need one crosscheck and never a restart`() {
        var queries = 0
        var captures = 0
        var retries = 0
        val current = { true }
        val location = TaskLocationConfirmation.poll(current,
            { queries++; TaskLocation.absent() }, {}, { fast ->
                TaskLocationConfirmation.confirm(33, pkg, fast, current) {
                    captures++
                    ProxyShell.Result(0, output)
                }
            })
        val final = TaskLaunchRecovery.retryIfAbsent(location, current) {
            retries++
            TaskLocation.absent()
        }
        assertEquals(16, queries)
        assertEquals(1, captures)
        assertEquals(0, retries)
        assertEquals(298, final.taskId)
        assertEquals(2, final.displayId)
    }

    @Test fun `final RPC uses the same independent positive evidence after reflected absence`() {
        for (fast in listOf(TaskLocation.absent(), TaskLocation.unknown(), TaskLocation.found(298, -1))) {
            val found = TaskLocationConfirmation.confirm(33, pkg, fast, { true }) { ProxyShell.Result(0, output) }
            assertEquals(TaskLaunchRecovery.Outcome.SUCCESS, TaskLaunchRecovery.verifyPlacement(
                TaskLaunchRecovery.Outcome.SUCCESS, found, 2))
        }
    }

    @Test fun `already positive fast query never captures a dump`() {
        var captures = 0
        val found = TaskLocationConfirmation.poll({ true }, { TaskLocation.found(298, 2) }, {}, { fast ->
            captures++
            fast
        })
        assertEquals(298, found.taskId)
        assertEquals(0, captures)
    }

    @Test fun `conflicting positive identity cannot authorize placement or absence retry`() {
        val location = TaskLocationConfirmation.confirm(33, pkg, TaskLocation.found(42, -1), { true }) {
            ProxyShell.Result(0, output)
        }
        assertEquals(TaskLocation.Status.UNKNOWN, location.status)
    }

    @Test fun `timeout and truncation stay unknown and issue no retry`() {
        for (result in listOf(ProxyShell.Result(-1, "ERR timeout"),
            ProxyShell.Result(0, output + "\n[output truncated]"), ProxyShell.Result(1, output))) {
            var retried = false
            val unknown = TaskLocationConfirmation.confirm(33, pkg, TaskLocation.absent(), { true }) { result }
            TaskLaunchRecovery.retryIfAbsent(unknown, { true }) { retried = true; TaskLocation.absent() }
            assertEquals(TaskLocation.Status.UNKNOWN, unknown.status)
            assertFalse(retried)
            assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED, TaskLaunchRecovery.verifyPlacement(
                TaskLaunchRecovery.Outcome.SUCCESS, unknown, 2))
        }
    }

    @Test fun `changing selection during bounded read discards its positive result`() {
        var current = true
        var retried = false
        val location = TaskLocationConfirmation.confirm(33, pkg, TaskLocation.absent(), { current }) {
            current = false
            ProxyShell.Result(0, output)
        }
        TaskLaunchRecovery.retryIfAbsent(location, { current }) { retried = true; TaskLocation.absent() }
        assertEquals(TaskLocation.Status.UNKNOWN, location.status)
        assertFalse(retried)
    }

    @Test fun `cancelled polling stops without capture or launch`() {
        var current = true
        var queries = 0
        var captures = 0
        val location = TaskLocationConfirmation.poll({ current }, { queries++; TaskLocation.absent() },
            { current = false }, { captures++; it })
        assertEquals(TaskLocation.Status.UNKNOWN, location.status)
        assertEquals(0, queries)
        assertEquals(0, captures)
    }

    @Test fun `task on another display is preserved and cannot validate success`() {
        var retries = 0
        val other = TaskLocationConfirmation.confirm(33, pkg, TaskLocation.absent(), { true }) {
            ProxyShell.Result(0, output.replace("Display #2", "Display #0"))
        }
        TaskLaunchRecovery.retryIfAbsent(other, { true }) { retries++; TaskLocation.absent() }
        assertEquals(0, retries)
        assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED, TaskLaunchRecovery.verifyPlacement(
            TaskLaunchRecovery.Outcome.SUCCESS, other, 2))
    }

    @Test fun `cancel after cleanup prevents the second destructive start`() {
        var current = true
        val effects = mutableListOf<String>()
        val result = TaskLaunchRecovery.retryOnCleanDisplay(object : TaskLaunchRecovery.Operations {
            override fun isCurrent() = current
            override fun cleanDisplay(): String { effects += "clean"; current = false; return "done" }
            override fun launchPlain() { effects += "launch" }
            override fun pollTask(): TaskLocation { effects += "poll"; return TaskLocation.found(298, 2) }
        })
        assertEquals(listOf("clean"), effects)
        assertEquals(TaskLocation.Status.UNKNOWN, result.status)
    }

    @Test fun `legacy fast found absent and unknown stay typed without reading a strict dump`() {
        for (api in listOf(28, 29, 30, 31, 32, 34)) {
            for (fast in listOf(TaskLocation.found(42, 2), TaskLocation.absent(), TaskLocation.unknown())) {
                val result = TaskLocationConfirmation.confirm(api, pkg, fast, { true }) {
                    error("the Android 13 dump must not run on API $api")
                }
                assertEquals(fast.status, result.status)
                assertEquals(fast.taskId, result.taskId)
                assertEquals(fast.displayId, result.displayId)
            }
        }
    }

    @Test fun `legacy original placement can move the requested known task then requires final proof`() {
        for (api in listOf(28, 29, 30, 31, 32)) {
            val effects = mutableListOf<String>()
            val location = TaskLocationConfirmation.confirm(api, pkg, TaskLocation.found(42, 0), { true }) {
                error("no legacy dump")
            }
            if (!TaskLocationConfirmation.placementUnconfirmed(api, location, 2)) effects += "legacy placement"
            assertEquals(listOf("legacy placement"), effects)
            assertEquals(TaskLaunchRecovery.Outcome.PLACEMENT_UNCONFIRMED,
                TaskLaunchRecovery.verifyPlacement(TaskLaunchRecovery.Outcome.SUCCESS, location, 2))
            assertEquals(TaskLaunchRecovery.Outcome.SUCCESS, TaskLaunchRecovery.verifyPlacement(
                TaskLaunchRecovery.Outcome.SUCCESS, TaskLocation.found(42, 2), 2))
        }
        assertTrue(TaskLocationConfirmation.placementUnconfirmed(33, TaskLocation.found(42, 0), 2))
    }

    @Test fun `every SDK refuses unreadable task display and cancelled lookup`() {
        for (api in listOf(28, 29, 30, 31, 32, 33, 34)) {
            for (location in listOf(TaskLocation.unknown(), TaskLocation.found(42, -1))) {
                assertTrue(TaskLocationConfirmation.placementUnconfirmed(api, location, 2))
            }
            val cancelled = TaskLocationConfirmation.confirm(api, pkg, TaskLocation.found(42, 2), { false }) {
                error("cancelled dump")
            }
            assertEquals(TaskLocation.Status.UNKNOWN, cancelled.status)
        }
    }

    @Test fun `legacy absence still reaches plain NPE and exact SX361 recovery without the new dump`() {
        var captures = 0
        var primaryLaunches = 0
        var pauses = 0
        val effects = mutableListOf<String>()
        val absent = TaskLocationConfirmation.afterStart(29, false, { true }, { TaskLocation.absent() }, { pauses++ }, { fast ->
            TaskLocationConfirmation.confirm(29, pkg, fast, { true }) {
                captures++
                ProxyShell.Result(0, "unattributed stale history t-1")
            }
        })
        val plain = TaskLaunchRecovery.retryIfAbsent(absent, { true }) {
            TaskLaunchRecovery.retryOnCleanDisplay(object : TaskLaunchRecovery.Operations {
                override fun cleanDisplay(): String { effects += "clean"; return "empty primary" }
                override fun launchPlain() { effects += "plain" }
                override fun pollTask() = TaskLocationConfirmation.confirm(29, pkg, TaskLocation.absent(), { true }) {
                    captures++
                    ProxyShell.Result(0, "unattributed stale history t-1")
                }
            })
        }
        assertTrue(TaskLaunchRecovery.isBlockedAfterPlainLaunch(
            "NullPointerException ActivityStack.getBounds()", plain))
        val before = EmptyPrimaryStackRecovery.Snapshot(29, EmptyPrimaryStackRecovery.FINGERPRINT,
            0, ClusterDisplayInfo(2, "fission_bg_xdjaVirtualSurface", 1920, 720, 2,
                false, 1000, "com.xdja.containerservice", "ON"), 1, true, plain,
            listOf(EmptyPrimaryStackRecovery.Stack(28, 2, 3, 1, emptyList())))
        val recovered = EmptyPrimaryStackRecovery.recover(2, object : EmptyPrimaryStackRecovery.Operations {
            override fun isCurrent() = true
            override fun snapshot() = if (primaryLaunches == 0) before else before.copy(
                location = TaskLocationConfirmation.confirm(29, pkg, TaskLocation.found(42, 2), { true }) {
                    error("no legacy dump during final recovery proof")
                }, stacks = listOf(before.stacks!!.single().copy(taskIds = listOf(42))))
            override fun launchPrimary() { primaryLaunches++ }
        })
        assertEquals(listOf("clean", "plain"), effects)
        assertEquals(0, captures)
        assertEquals(0, pauses)
        assertEquals(1, primaryLaunches)
        assertNotNull(recovered)
        assertEquals(42, recovered!!.taskId)
    }

    @Test fun `legacy immediate error path keeps cancellation and true unknown non destructive`() {
        var current = true
        val cancelled = TaskLocationConfirmation.afterStart(29, false, { current }, {
            current = false
            TaskLocation.found(42, 2)
        }, { error("no legacy error polling delay") }, { error("cancelled confirmation") })
        assertEquals(TaskLocation.Status.UNKNOWN, cancelled.status)
        var retried = false
        val unknown = TaskLocationConfirmation.afterStart(29, false, { true }, { TaskLocation.unknown() },
            { error("no legacy error polling delay") }, { fast ->
                TaskLocationConfirmation.confirm(29, pkg, fast, { true }) { error("no legacy dump") }
            })
        TaskLaunchRecovery.retryIfAbsent(unknown, { true }) { retried = true; TaskLocation.absent() }
        assertFalse(retried)
    }
}
