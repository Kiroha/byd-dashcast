package com.byd.dashcast.cluster.display

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectionCallbackOwnershipWiringTest {

    @Test
    fun `display callbacks and direct Fission owner honor process session lifecycle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/display/ClusterManager.kt").isFile }
        val manager = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/display/ClusterManager.kt").readText()
        val fission = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()

        val claim = manager.substringAfter("private fun claimDisplayReady")
            .substringBefore("private fun sendProjectionInfo")
        assertTrue(claim.contains("isCurrentActivation(gen)"))
        assertTrue(manager.contains("ProjectionCommandBus.isCurrent(mProjectionSession)"))
        assertTrue(manager.substringAfter("fun abandon()")
            .substringBefore("companion object").contains("ProjectionCommandBus.endSession"))
        assertTrue(fission.contains("mClusterActivationManager = manager"))
        assertTrue(fission.substringAfter("private fun stopAll(purgeDaemonSlots: Boolean")
            .take(180).contains("abandonClusterActivation()"))
        assertTrue(fission.substringAfter("fun destroy(isFinishing: Boolean)")
            .take(180).contains("abandonClusterActivation()"))
    }
}