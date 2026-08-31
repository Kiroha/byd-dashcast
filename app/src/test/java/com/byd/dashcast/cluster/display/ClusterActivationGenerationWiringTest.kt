package com.byd.dashcast.cluster.display

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterActivationGenerationWiringTest {

    @Test
    fun `all asynchronous activation families reject stale generations`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/cluster/display").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val source = File(
            root,
            "app/src/main/java/com/byd/dashcast/cluster/display/ClusterManager.kt"
        ).readText()

        assertTrue(source.contains("resolveDl5Display(dm, callback, gen)"))
        assertTrue(source.contains("sendWarmCmd16(found, callback, gen)"))
        assertTrue(source.contains("sendWarmCmd16(originalDisplay, callback, gen)"))

        for (method in listOf(
            "private fun sendWarmCmd16",
            "private fun sendActivationSequence",
            "private fun sendActivationCmd16ThenCmd35",
            "private fun sendActivationCmd35",
            "private fun resolveDl5Display",
        )) {
            val body = source.substringAfter(method).substringBefore("\n    /**")
            assertTrue("$method has no generation guard", body.contains("gen != mActivationGeneration") ||
                body.contains("gen == mActivationGeneration"))
        }
    }
}
