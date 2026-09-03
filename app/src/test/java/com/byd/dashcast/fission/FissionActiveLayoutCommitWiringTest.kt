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
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()

        val init = source.substringAfter("public void initAsync")
            .substringBefore("private void ensureClusterProjectionThen")
        val asyncSwitch = source.substringAfter("public void switchToLayoutAsync")
            .substringBefore("public java.util.Collection")
        val favorite = source.substringAfter("private void activateFavoriteLayout")
            .substringBefore("private boolean isUsable")
        val manual = source.substringAfter("private void doActivatePreset")
            .substringBefore("private void switchActiveLayout")
        val transaction = source.substringAfter("private void switchActiveLayout")
            .substringBefore("private void attachFreeZones")

        assertFalse(init.contains("mActiveLayout = favoriteLayout"))
        assertTrue(asyncSwitch.contains("switchActiveLayout(newLayout, null)"))
        assertTrue(favorite.contains("switchActiveLayout(fav, null)"))
        assertTrue(manual.contains("switchActiveLayout(preset, null)"))
        assertTrue(transaction.contains("LayoutPreset previous = mActiveLayout"))
        assertTrue(transaction.contains("mActiveLayout = previous"))
        assertTrue(transaction.indexOf("mActiveLayout = previous") <
            transaction.indexOf("throw error"))
    }
}