package com.byd.dashcast.ui.main

import android.content.Context
import com.byd.dashcast.cluster.ClusterService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitReplacementStateTest {

    @Test
    fun `verified stop clears both occupant fields and refreshes controls`() {
        val host = RecordingHost()
        val controller = SplitController(host)
        controller.setSecondDashboardApp("Old app")
        controller.setSecondDashboardPkg("old.pkg")
        val generation = controller.beginSecondDashboardReplacement()

        assertTrue(controller.clearSecondDashboardIfMatches("old.pkg", generation))

        assertNull(controller.secondDashboardApp)
        assertNull(controller.secondDashboardPkg)
        assertEquals(1, host.changes)
    }

    @Test
    fun `late stop completion cannot clear a newer occupant`() {
        val host = RecordingHost()
        val controller = SplitController(host)
        controller.setSecondDashboardApp("New app")
        controller.setSecondDashboardPkg("new.pkg")
        val oldGeneration = controller.beginSecondDashboardReplacement()
        controller.beginSecondDashboardReplacement()

        assertFalse(controller.clearSecondDashboardIfMatches("old.pkg", oldGeneration))

        assertEquals("New app", controller.secondDashboardApp)
        assertEquals("new.pkg", controller.secondDashboardPkg)
        assertEquals(0, host.changes)
    }

    @Test
    fun `only latest concurrent replacement generation can complete`() {
        val controller = SplitController(RecordingHost())
        controller.setSecondDashboardApp("Old app")
        controller.setSecondDashboardPkg("old.pkg")
        val first = controller.beginSecondDashboardReplacement()
        val second = controller.beginSecondDashboardReplacement()

        assertFalse(controller.isCurrentSecondDashboardReplacement(first))
        assertTrue(controller.isCurrentSecondDashboardReplacement(second))
        assertFalse(controller.clearSecondDashboardIfMatches("old.pkg", first))
        assertTrue(controller.clearSecondDashboardIfMatches("old.pkg", second))
    }

    @Test
    fun `app and shortcut replacement paths clear only after verified stop`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val splitPath = source.substringAfter("// ── Split mode:")
            .substringBefore("// ── Normal behavior")
        val shortcutPath = source.substringAfter("if (splitOccupantToStop != null)")
            .substringBefore("} else if (layoutTarget")

        assertTrue(splitPath.contains("beginSecondDashboardReplacement()"))
        assertTrue(shortcutPath.contains("splitReplacementGeneration"))
        assertTrue(splitPath.contains("clearSecondDashboardIfMatches("))
        assertTrue(shortcutPath.contains("clearSecondDashboardIfMatches("))
        assertTrue(splitPath.contains("isCurrentSecondDashboardReplacement(replacementGeneration)"))
        assertTrue(shortcutPath.contains("isCurrentSecondDashboardReplacement(generation)"))
        assertTrue(splitPath.contains("mSessionTracker.remove(previousSecond)"))
        assertTrue(shortcutPath.contains("mSessionTracker.remove(splitOccupantToStop)"))
    }

    private class RecordingHost : SplitController.Host {
        var changes = 0
        override fun getContext(): Context = RuntimeEnvironment.getApplication()
        override fun getClusterServiceIfBound(): ClusterService? = null
        override fun getCurrentDashboardPkg(): String? = null
        override fun getCurrentDashboardApp(): String? = null
        override fun setCurrentDashboardPkg(pkg: String?) = Unit
        override fun setCurrentDashboardApp(app: String?) = Unit
        override fun onSplitStateChanged() { changes++ }
        override fun runOnUiThread(runnable: Runnable) = runnable.run()
    }
}