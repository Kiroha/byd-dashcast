package com.byd.dashcast.clusterlive

import com.byd.dashcast.cluster.ProjectionAdasPlan
import org.junit.Assert.*
import org.junit.Test

class ClusterFullAppAdasTest {
    @Test fun `diagnostic whitelist is exactly the existing plan with recovery verbs kept separate`() {
        assertEquals(ProjectionAdasPlan.FRAMES, ClusterFullAppAdas.frames.toSet())
        assertTrue(ClusterFullAppAdas.permits(ClusterFullAppAdas.COMMAND, 13, 0, false))
        assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.COMMAND, 13, 0, true))
        for (code in listOf(12, 53)) {
            assertTrue(ClusterFullAppAdas.permits(ClusterFullAppAdas.COMMAND, code, 0, true))
            assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.COMMAND, code, 0, false))
        }
        for (code in listOf(0, 16, 18, 31, 35, 88, 89, 1000))
            assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.COMMAND, code, 0, false))
        for (count in listOf(-1, 0, 11, 100))
            assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.FRAMES, 3, count, false))
        assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.FRAMES, 4, 1, false))
        assertTrue(ClusterFullAppAdas.permits(ClusterFullAppAdas.FRAMES, 4, 10, true))
        assertFalse(ClusterFullAppAdas.permits(ClusterFullAppAdas.FRAMES, 3, 10, true))
    }
}
