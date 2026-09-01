package com.byd.dashcast.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterEvictionLaunchWiringTest {
    @Test
    fun `all classic projection entry points invalidate or defer old eviction`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()

        assertEquals(4, Regex("mSessionTracker\\.runWhenSafeToLaunch").findAll(source).count())
        assertTrue(source.substringAfter("private fun quickSwitchToApp")
            .substringBefore("override fun onStart").contains("runWhenSafeToLaunch(pkgName"))
        assertTrue(source.substringAfter("fun launchInComplementarySlot")
            .substringBefore("val previousSecond").contains("runWhenSafeToLaunch(pkgName"))
        assertTrue(source.substringAfter("fun proceedMove")
            .substringBefore("val previousClusterPkg").contains("runWhenSafeToLaunch(pkgName"))
        val shortcut = source.substringAfter("override fun onLaunchShortcut")
            .substringAfter("fun launch()")
            .substringBefore("val previous =")
        assertTrue(shortcut.contains("runWhenSafeToLaunch"))

        val gateWrapper = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").readText()
            .substringAfter("fun runWhenSafeToLaunch")
            .substringBefore("private fun snapshot")
        assertFalse(gateWrapper.contains("remove(pkg)"))
        for (launch in listOf(
            source.substringAfter("private fun quickSwitchToApp").substringBefore("override fun onStart"),
            source.substringAfter("fun launchInComplementarySlot").substringBefore("val previousSecond"),
            source.substringAfter("fun proceedMove").substringBefore("val previousClusterPkg"),
            source.substringAfter("fun launchNow()").substringBefore("fun launch()"),
        )) {
            assertTrue(launch.indexOf("isFinishing || isDestroyed") < launch.indexOf("mSessionTracker.remove"))
        }
    }
}