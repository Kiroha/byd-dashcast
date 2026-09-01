package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterEvictionSerializationWiringTest {
    @Test
    fun `tracker serializes full eviction and caller restoration workflows`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").isFile }
        val tracker = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").readText()
        val activity = File(root,
            "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val entry = tracker.substringAfter("fun evictAllThen")
            .substringBefore("private fun evictAllOwned")
        val owned = tracker.substringAfter("private fun evictAllOwned")
            .substringBefore("// ── Internal")

        assertTrue(entry.contains("sEvictionOperations.submit"))
        assertTrue(owned.contains("mRestoreHome.reset()"))
        assertTrue(owned.contains("val physicalRemaining = AtomicInteger(blind.size)"))
        assertTrue(owned.contains("main.post { lease.markPhysicalDone() }"))
        assertTrue(owned.contains("lease.markPhysicalDone()"))
        assertTrue(owned.contains("val restoreHome = mRestoreHome.consume()"))
        assertEquals(4, Regex("restoreHomeIfRequested\\(restoreHome\\)")
            .findAll(activity).count())
        assertEquals(4, Regex("callerComplete\\.run\\(\\)")
            .findAll(activity).count())
    }
}