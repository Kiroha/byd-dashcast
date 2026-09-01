package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

class PersistedPackageSetTest {
    @Test
    fun `later mutation cannot persist before an earlier mutation`() {
        val firstWriterEntered = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val removeFinished = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Set<String>>())
        val packages = PersistedPackageSet { snapshot ->
            if (snapshot == setOf("com.example.nav")) {
                firstWriterEntered.countDown()
                releaseFirstWriter.await(2, TimeUnit.SECONDS)
            }
            writes += snapshot
        }

        val adding = Thread { packages.add("com.example.nav") }
        val removing = Thread {
            packages.remove("com.example.nav")
            removeFinished.countDown()
        }
        adding.start()
        assertTrue(firstWriterEntered.await(1, TimeUnit.SECONDS))
        removing.start()

        assertFalse(removeFinished.await(100, TimeUnit.MILLISECONDS))
        releaseFirstWriter.countDown()
        adding.join(1_000)
        removing.join(1_000)

        assertEquals(listOf(setOf("com.example.nav"), emptySet()), writes)
        assertTrue(packages.snapshot().isEmpty())
    }

    @Test
    fun `session tracker delegates persistence to the serialized owner`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").readText()

        assertTrue(source.contains("private val mPkgs = PersistedPackageSet"))
        assertFalse(source.contains("private fun persist()"))
    }
}