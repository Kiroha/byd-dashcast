package com.byd.dashcast.fission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionActiveLayoutCommitWiringTest {

    @Test
    fun `all activation paths use rollback capable active layout switch`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.kt").readText()

        val init = source.substringAfter("fun initAsync")
            .substringBefore("private fun ensureClusterProjectionThen")
        val asyncSwitch = source.substringAfter("fun switchToLayoutAsync")
            .substringBefore("fun getSlots()")
        val favorite = source.substringAfter("private fun activateFavoriteLayout")
            .substringBefore("private fun isUsable")
        val manual = source.substringAfter("private fun doActivatePreset")
            .substringBefore("private fun switchActiveLayout")
        val transaction = source.substringAfter("private fun switchActiveLayout")
            .substringBefore("private fun attachFreeZones")

        assertFalse(init.contains("mActiveLayout = favoriteLayout"))
        assertTrue(asyncSwitch.contains("switchActiveLayout(newLayout, null)"))
        assertTrue(favorite.contains("switchActiveLayout(fav, null)"))
        assertTrue(manual.contains("switchActiveLayout(preset, null)"))
        assertTrue(transaction.contains("val previous = mActiveLayout"))
        assertTrue(transaction.contains("mActiveLayout = previous"))
        assertTrue(transaction.indexOf("mActiveLayout = previous") <
            transaction.indexOf("throw error"))
    }
}