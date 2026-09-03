package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation

/**
 * What to do with an app's task once a force-stop has been attempted.
 *
 * This decision has now caused two shipped regressions in three releases, in opposite directions,
 * and it had no test either time. It lived inline in `AdbLocalClient.forceStopApp`, where the only
 * way to exercise it was a car.
 */
object EvictionOutcomePolicy {

    enum class Outcome {
        /** The task is gone or must go. */
        REMOVE_TASK,
        /** Leave the task where it is: it is the app's way back to the centre screen. */
        KEEP_TASK,
        /** Leave the task, then put the home screen in front of it. */
        KEEP_AND_RESTORE_HOME,
    }

    /**
     * @param killVerified the process was observed to be gone after the force-stop.
     * @param location where the task was, from the SAME round trip that preceded the kill.
     */
    @JvmStatic
    fun decide(killVerified: Boolean, location: TaskLocation): Outcome {
        // The overwhelming majority of packages. Unchanged since before any of this.
        if (killVerified) return Outcome.REMOVE_TASK

        val found = location.status == TaskLocation.Status.FOUND

        // Still on the cluster: it occupies the display the caller is freeing. INC-20260621-130238.
        if (found && location.displayId > 0) return Outcome.REMOVE_TASK

        // On the centre screen, alive, and we put it there. Keeping it is right — it is the app's
        // way home (INC-20260815-181820) — but leaving it RESUMED and on top is what let the OEM
        // host reclaim the foreground and made the screen unusable (INC-20260816). So keep the
        // task and put the launcher back in front of it.
        if (found && location.displayId == 0) return Outcome.KEEP_AND_RESTORE_HOME

        // ABSENT or UNKNOWN. Nothing was seen on the centre screen, so there is nothing to cover;
        // and acting on a lookup that answered nothing is the original defect. Never act here.
        return Outcome.KEEP_TASK
    }
}
