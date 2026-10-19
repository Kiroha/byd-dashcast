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
        val stopAll = methodSection(fission, "private fun stopAll(purgeDaemonSlots: Boolean")
        val destroy = methodSection(fission, "fun destroy(isFinishing: Boolean)")
        assertBefore(stopAll, "abandonClusterActivation()", "submitQuietly(\"stopAll\")")
        assertBefore(destroy, "abandonClusterActivation()", "mMainHandler.removeCallbacksAndMessages(null)")
        assertBefore(destroy, "abandonClusterActivation()", "submitQuietly(\"destroy teardown\")")
    }

    // Scope assertions by method rather than a character budget affected by new lifecycle hooks.
    private fun methodSection(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("Missing method: $signature", start >= 0)
        val tail = source.substring(start + signature.length)
        val next = Regex("\\n    (?:(?:private|internal|public|protected|override|suspend) )*fun ")
            .find(tail)?.range?.first ?: tail.length
        return tail.substring(0, next)
    }

    private fun assertBefore(body: String, first: String, second: String) {
        val firstIndex = body.indexOf(first)
        val secondIndex = body.indexOf(second)
        assertTrue("$first must precede $second in the same lifecycle method",
            firstIndex >= 0 && secondIndex > firstIndex)
    }
}
