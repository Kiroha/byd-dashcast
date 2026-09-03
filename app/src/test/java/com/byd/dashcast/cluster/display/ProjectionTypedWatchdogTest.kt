package com.byd.dashcast.cluster.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTypedWatchdogTest {

    @Test
    fun `normal transact cancels timeout and permits fallback`() {
        var timeoutAction: Runnable? = null
        var cancelled = false
        var recovered = false
        val watchdog = ProjectionTypedWatchdog(
            20_000,
            ProjectionTypedWatchdog.Scheduler { _, action ->
                timeoutAction = action
                ProjectionTypedWatchdog.Cancellable { cancelled = true }
            },
            recover = { done -> recovered = true; done.run() },
            onRecovered = Runnable {},
        )

        watchdog.onStart()
        watchdog.onFinish()
        timeoutAction!!.run()

        assertTrue(cancelled)
        assertFalse(recovered)
        assertFalse(watchdog.shouldAbortFallback())
    }

    @Test
    fun `timeout suppresses fallback and completes only after recovery`() {
        var timeoutAction: Runnable? = null
        var recoveryDone: Runnable? = null
        var completed = false
        val watchdog = ProjectionTypedWatchdog(
            20_000,
            ProjectionTypedWatchdog.Scheduler { _, action ->
                timeoutAction = action
                ProjectionTypedWatchdog.Cancellable {}
            },
            recover = { done -> recoveryDone = done },
            onRecovered = Runnable { completed = true },
        )

        watchdog.onStart()
        timeoutAction!!.run()

        assertTrue(watchdog.shouldAbortFallback())
        assertFalse(completed)
        recoveryDone!!.run()
        assertTrue(completed)
    }
}