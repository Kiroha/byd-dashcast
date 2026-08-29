package com.byd.dashcast.cluster

import com.byd.dashcast.cluster.EvictionOutcomePolicy.Outcome
import com.byd.dashcast.cluster.EvictionOutcomePolicy.decide
import com.byd.dashcast.infrastructure.task.TaskLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keep/remove decision, which has shipped a regression in each direction and had no test
 * either time.
 */
class EvictionOutcomePolicyTest {

    /**
     * THE DEFECT. INC-20260816: the tester projected Android Auto, pressed Stop, and could not use
     * the centre screen again until he rebooted — the OEM drawer and the home screen each lasted
     * about 0.3 s before Android Auto came back in front.
     *
     * Keeping the task is right and must stay: destroying it is what made the app unreachable in
     * INC-20260815-181820. What is wrong is stopping there. DashCast relaunches the app on
     * display 0 to fix its routing, the kill then fails because com.byd.androidauto is persistent,
     * and we walk away leaving it resumed and on top with a live phone session — a foreground for
     * the OEM host to reclaim, handed to it by us.
     */
    @Test
    fun `an unkillable app left on the centre screen gets the home screen put back in front`() {
        assertEquals(
            Outcome.KEEP_AND_RESTORE_HOME,
            decide(killVerified = false, location = TaskLocation.found(taskId = 42, displayId = 0))
        )
    }

    @Test
    fun `a verified kill removes the task, as before`() {
        assertEquals(Outcome.REMOVE_TASK,
            decide(true, TaskLocation.found(42, 0)))
        assertEquals(Outcome.REMOVE_TASK,
            decide(true, TaskLocation.found(42, 1)))
    }

    /**
     * INC-20260621-130238. On DiLink 3 the caller force-stops the app currently ON the cluster to
     * free that display, because the ROM has no way to reparent it. Keeping that task leaves the
     * display occupied and the next launch lands split-screen, which NPEs in WindowManager.
     */
    @Test
    fun `a task still on the cluster is removed even when the kill failed`() {
        assertEquals(Outcome.REMOVE_TASK,
            decide(false, TaskLocation.found(42, 1)))
    }

    /**
     * The original defect, and the reason this must never act on a failed lookup: destroying a
     * task we merely could not see is how the app lost its way home in the first place.
     */
    @Test
    fun `a lookup that answered nothing changes nothing`() {
        assertEquals(Outcome.KEEP_TASK, decide(false, TaskLocation.absent()))
        assertEquals(Outcome.KEEP_TASK, decide(false, TaskLocation.unknown()))
    }
}
