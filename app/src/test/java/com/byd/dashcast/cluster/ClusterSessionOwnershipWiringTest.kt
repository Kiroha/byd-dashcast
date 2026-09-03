package com.byd.dashcast.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterSessionOwnershipWiringTest {

    @Test
    fun `global eviction removes ownership only after safe probe or verified kill`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/ClusterSessionTracker.kt").readText()
        val moveCallback = source.substringAfter("svc.moveTaskToDisplay(pkg, 0")
            .substringBefore("awaitLandingThenForceStop")
        val force = source.substringAfter("private fun forceStopThenNext")
            .substringBefore("private fun sleepQuietly")
        val terminal = force.substringAfter("fun completeForceStop")
            .substringBefore("try {")

        assertFalse(moveCallback.contains("remove(pkg)"))
        assertTrue(terminal.contains("if (success) remove(pkg) else add(pkg)"))
        assertTrue(terminal.contains("sEvictionGate.finishDestructive"))
    }

    @Test
    fun `direct kill retains ownership until force stop success`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val kill = source.substringAfter("private fun doKillApp")
            .substringBefore("// ---- Miroir cluster")
        val callback = kill.substringAfter("val killCallback")
            .substringBefore("val svc =")
        val success = callback.substringAfter("override fun onSuccess")
            .substringBefore("override fun onError")
        val error = callback.substringAfter("override fun onError")

        assertTrue(success.contains("mSessionTracker.remove(app.packageName)"))
        assertTrue(error.contains("mSessionTracker.add(app.packageName)"))
        assertFalse(kill.substringAfter("override fun onResult(ok: Boolean)")
            .substringBefore("})\n        } else").contains("mSessionTracker.remove"))
    }
}