package com.byd.dashcast.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImePendingTextTest {

    @Test
    fun `failed completion leaves text available for retry`() {
        val pending = ImePendingText()
        pending.set("destination")
        val attempt = pending.snapshot()

        assertEquals("destination", attempt.text)
        assertEquals("destination", pending.snapshot().text)
    }

    @Test
    fun `older successful completion cannot clear a newer edit`() {
        val pending = ImePendingText()
        pending.set("first")
        val first = pending.snapshot()
        pending.set("second")

        assertFalse(pending.clearIfCurrent(first.generation))
        assertEquals("second", pending.snapshot().text)
    }

    @Test
    fun `current successful completion clears exactly that edit`() {
        val pending = ImePendingText()
        pending.set("destination")
        val attempt = pending.snapshot()

        assertTrue(pending.clearIfCurrent(attempt.generation))
        assertNull(pending.snapshot().text)
    }

    @Test
    fun `bridge clears only from the asynchronous accepted callback`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/ime").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val watcher = File(root,
            "app/src/main/java/com/byd/dashcast/ime/ClusterImeWatcherService.java").readText()
        val bridge = File(root,
            "app/src/main/java/com/byd/dashcast/ime/KeyboardBridgeActivity.kt").readText()

        assertTrue(watcher.contains("accepted = node.performAction(actionId)"))
        assertTrue(watcher.contains("completeImeAction(callback, accepted)"))
        assertTrue(bridge.contains("override fun onComplete(accepted: Boolean)"))
        assertTrue(bridge.contains("submittedGeneration != mInputGeneration"))
        assertTrue(bridge.contains("actionGeneration != mImeActionGeneration"))
        // The clear must never key off a SYNCHRONOUS return value — in either language's spelling.
        assertFalse(bridge.contains("boolean ok = ClusterImeWatcherService.performImeEnterOnCluster"))
        assertFalse(bridge.contains("val ok = ClusterImeWatcherService.performImeEnterOnCluster"))
    }
}