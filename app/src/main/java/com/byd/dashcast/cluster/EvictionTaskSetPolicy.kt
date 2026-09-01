package com.byd.dashcast.cluster

import com.byd.dashcast.infrastructure.task.TaskLocation

/** Decides which tasks to remove when one package owns tasks on several displays. */
object EvictionTaskSetPolicy {
    data class Decision(
        val outcome: EvictionOutcomePolicy.Outcome,
        val taskIdsToRemove: List<Int>,
    )

    @JvmStatic
    fun decide(killVerified: Boolean, locations: Collection<TaskLocation>?): Decision {
        val found = locations.orEmpty().filter {
            it.status == TaskLocation.Status.FOUND && it.taskId > 0
        }
        if (killVerified) {
            return Decision(
                EvictionOutcomePolicy.Outcome.REMOVE_TASK,
                found.map { it.taskId }.distinct(),
            )
        }
        val clusterTasks = found.filter { it.displayId > 0 }.map { it.taskId }.distinct()
        val hasCenterTask = found.any { it.displayId == 0 }
        val outcome = when {
            hasCenterTask -> EvictionOutcomePolicy.Outcome.KEEP_AND_RESTORE_HOME
            clusterTasks.isNotEmpty() -> EvictionOutcomePolicy.Outcome.REMOVE_TASK
            else -> EvictionOutcomePolicy.Outcome.KEEP_TASK
        }
        return Decision(outcome, clusterTasks)
    }
}