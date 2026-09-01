package com.byd.dashcast.cluster

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterEvictionMoveWatchdogWiringTest {

    @Test
    fun `move callback and timeout share one continuation`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").readText()
        val move = source.substringAfter("val continued = AtomicBoolean(false)")
            .substringBefore("// ──")
        val continuation = move.substringAfter("fun continueAfterMove")
            .substringBefore("timeout = Runnable")

        assertTrue(continuation.contains("continued.compareAndSet(false, true)"))
        assertTrue(continuation.contains("awaitLandingThenForceStop"))
        assertTrue(move.contains("main.postDelayed(timeout, MOVE_CALLBACK_TIMEOUT_MS)"))
        assertTrue(move.contains("continueAfterMove(false, \"callback-timeout\")"))
        assertTrue(move.contains("continueAfterMove(ok, \"callback\")"))
        assertTrue(source.contains("sEvictionGate.tryBeginDestructive(candidate.token)"))
        assertTrue(source.contains("postDeferredLaunch(completion?.deferredLaunch)"))
    }
}