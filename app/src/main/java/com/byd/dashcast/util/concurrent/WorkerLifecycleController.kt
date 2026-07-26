package com.byd.dashcast.util.concurrent

/** State machine preventing overlap while allowing one deferred worker restart. */
class WorkerLifecycleController {

    enum class StartDecision { START_NOW, ALREADY_RUNNING, RESTART_QUEUED }
    enum class StopDecision { ALREADY_STOPPED, STOP_RUNNING, ALREADY_STOPPING }
    enum class ExitDecision { STOPPED, RESTART }

    private enum class State { STOPPED, RUNNING, STOPPING }

    private var state = State.STOPPED
    private var restartPending = false

    @Synchronized
    fun requestStart(): StartDecision = when (state) {
        State.STOPPED -> { state = State.RUNNING; StartDecision.START_NOW }
        State.RUNNING -> StartDecision.ALREADY_RUNNING
        State.STOPPING -> { restartPending = true; StartDecision.RESTART_QUEUED }
    }

    @Synchronized
    fun requestStop(): StopDecision {
        restartPending = false
        return when (state) {
            State.STOPPED -> StopDecision.ALREADY_STOPPED
            State.RUNNING -> { state = State.STOPPING; StopDecision.STOP_RUNNING }
            State.STOPPING -> StopDecision.ALREADY_STOPPING
        }
    }

    /** Called exactly once by the exiting worker after it has released its native resources. */
    @Synchronized
    fun workerExited(): ExitDecision {
        if (restartPending) {
            restartPending = false
            state = State.RUNNING
            return ExitDecision.RESTART
        }
        state = State.STOPPED
        return ExitDecision.STOPPED
    }
}
