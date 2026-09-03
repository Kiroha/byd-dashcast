package com.byd.dashcast.fission

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionReleaseDebtTest {

    @After
    fun reset() {
        FissionReleaseDebt.resetForTest()
    }

    @Test
    fun `failed release remains owned until a later retry succeeds`() {
        FissionReleaseDebt.record("stuck.pkg")
        var fail = true

        val first = FissionReleaseDebt.retry { key ->
            if (fail) throw IllegalStateException("binder died")
            assertEquals("stuck.pkg", key)
        }
        fail = false
        val second = FissionReleaseDebt.retry { key -> assertEquals("stuck.pkg", key) }

        assertEquals(setOf("stuck.pkg"), first)
        assertEquals(emptySet<String>(), second)
    }

    @Test
    fun `stale success clears only its own key`() {
        FissionReleaseDebt.recordAll(listOf("one", "two"))

        FissionReleaseDebt.settled("one")

        assertEquals(setOf("two"), FissionReleaseDebt.snapshot())
    }

    @Test
    fun `orchestrator retries debt before reuse and retains failed free zones`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val ensure = source.substringAfter("private boolean ensureDaemon")
            .substringBefore("private void doStartSlot")
        val free = source.substringAfter("private void releaseFreeZones")
            .substringBefore("private boolean publishDisplayIds")
        val startError = source.substringAfter("public void startSlot")
            .substringBefore("public void stopAll()")

        assertTrue(ensure.contains("retryReleaseDebt(b)"))
        assertTrue(ensure.split("if (!retryReleaseDebt(b)) return false").size - 1 >= 2)
        assertTrue(source.indexOf("if (!retryReleaseDebt(b)) return false") <
            source.indexOf("private void doStartSlot"))
        assertTrue(free.contains("FissionReleaseDebt.record(key)"))
        assertTrue(free.indexOf("FissionClient.releaseSlot") < free.indexOf("mFreeZoneKeys.remove(key)"))
        assertTrue(!startError.contains("FissionClient.releaseSlot"))
    }
}