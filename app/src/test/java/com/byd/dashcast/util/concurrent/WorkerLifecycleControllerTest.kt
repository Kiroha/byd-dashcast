package com.byd.dashcast.util.concurrent

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerLifecycleControllerTest {

    @Test
    fun startDuringStoppingQueuesExactlyOneRestart() {
        val state = WorkerLifecycleController()

        assertEquals(WorkerLifecycleController.StartDecision.START_NOW, state.requestStart())
        assertEquals(WorkerLifecycleController.StartDecision.ALREADY_RUNNING, state.requestStart())
        assertEquals(WorkerLifecycleController.StopDecision.STOP_RUNNING, state.requestStop())
        assertEquals(WorkerLifecycleController.StartDecision.RESTART_QUEUED, state.requestStart())
        assertEquals(WorkerLifecycleController.StartDecision.RESTART_QUEUED, state.requestStart())
        assertEquals(WorkerLifecycleController.ExitDecision.RESTART, state.workerExited())
        assertEquals(WorkerLifecycleController.StartDecision.ALREADY_RUNNING, state.requestStart())
    }

    @Test
    fun secondStopCancelsQueuedRestart() {
        val state = WorkerLifecycleController()
        state.requestStart()
        state.requestStop()
        state.requestStart()

        assertEquals(WorkerLifecycleController.StopDecision.ALREADY_STOPPING, state.requestStop())
        assertEquals(WorkerLifecycleController.ExitDecision.STOPPED, state.workerExited())
        assertEquals(WorkerLifecycleController.StartDecision.START_NOW, state.requestStart())
    }

    @Test
    fun naturalWorkerExitReturnsToStopped() {
        val state = WorkerLifecycleController()
        state.requestStart()

        assertEquals(WorkerLifecycleController.ExitDecision.STOPPED, state.workerExited())
        assertEquals(WorkerLifecycleController.StartDecision.START_NOW, state.requestStart())
    }
}
