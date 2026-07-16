package com.byd.dashcast.util.concurrent;

/** State machine preventing overlap while allowing one deferred worker restart. */
public final class WorkerLifecycleController {

    public enum StartDecision { START_NOW, ALREADY_RUNNING, RESTART_QUEUED }
    public enum StopDecision { ALREADY_STOPPED, STOP_RUNNING, ALREADY_STOPPING }
    public enum ExitDecision { STOPPED, RESTART }

    private enum State { STOPPED, RUNNING, STOPPING }

    private State state = State.STOPPED;
    private boolean restartPending;

    public synchronized StartDecision requestStart() {
        switch (state) {
            case STOPPED:
                state = State.RUNNING;
                return StartDecision.START_NOW;
            case RUNNING:
                return StartDecision.ALREADY_RUNNING;
            case STOPPING:
                restartPending = true;
                return StartDecision.RESTART_QUEUED;
            default:
                throw new IllegalStateException("unknown worker state");
        }
    }

    public synchronized StopDecision requestStop() {
        restartPending = false;
        switch (state) {
            case STOPPED:
                return StopDecision.ALREADY_STOPPED;
            case RUNNING:
                state = State.STOPPING;
                return StopDecision.STOP_RUNNING;
            case STOPPING:
                return StopDecision.ALREADY_STOPPING;
            default:
                throw new IllegalStateException("unknown worker state");
        }
    }

    /** Called exactly once by the exiting worker after it has released its native resources. */
    public synchronized ExitDecision workerExited() {
        if (restartPending) {
            restartPending = false;
            state = State.RUNNING;
            return ExitDecision.RESTART;
        }
        state = State.STOPPED;
        return ExitDecision.STOPPED;
    }
}
