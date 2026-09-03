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
    fun `full screen state commits only for verified current occupant`() {
        val host = RecordingHost()
        val controller = SplitController(host)
        controller.setSecondDashboardApp("Old app")
        controller.setSecondDashboardPkg("old.pkg")
        val slot = SplitController::class.java.getDeclaredField("mCurrentSplitSlot")
        slot.isAccessible = true
        slot.setInt(controller, 1)
        val stale = controller.beginSecondDashboardReplacement()
        val current = controller.beginSecondDashboardReplacement()

        assertFalse(controller.commitFullScreenIfMatches("old.pkg", stale))
        assertTrue(controller.isInSplitMode)
        assertEquals("old.pkg", controller.secondDashboardPkg)

        assertTrue(controller.commitFullScreenIfMatches("old.pkg", current))
        assertFalse(controller.isInSplitMode)
        assertNull(controller.secondDashboardPkg)
        assertNull(controller.secondDashboardApp)
        assertEquals(1, host.changes)
    }

    @Test
    fun `full screen flow waits for secondary stop success before state commit`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/SplitController.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/SplitController.java").readText()
        val apply = source.substringAfter("public void applySplitSlot")
            .substringBefore("private void relaunchPrimaryInSlot")
        val success = apply.substringAfter("@Override public void onSuccess")
            .substringBefore("@Override public void onError")
        val error = apply.substringAfter("@Override public void onError")

        assertTrue(success.contains("commitFullScreenIfMatches(secondPkg, generation)"))
        assertTrue(error.contains("isCurrentSecondDashboardReplacement(generation)"))
        assertTrue(error.contains("toast_kill_failed"))
        assertFalse(apply.substringBefore("@Override public void onSuccess")
            .contains("mSecondDashboardPkg = null"))
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
        assertTrue(splitPath.contains("if (launched) cleanupStaleSplitLaunch(pkgName)"))
        val appError = splitPath.substringAfter("override fun onError(error: String?)").take(700)
        val shortcutError = shortcutPath.substringAfter("override fun onError(error: String?)").take(700)
        assertTrue(appError.contains("toast_kill_failed"))
        assertTrue(shortcutError.contains("toast_kill_failed"))
        assertFalse(appError.contains("launchInComplementarySlot()"))
        assertFalse(shortcutError.contains(" launch()"))
    }

    @Test
    fun `stale successful bounded launch stays tracked until verified cleanup`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val cleanup = source.substringAfter("private fun cleanupStaleSplitLaunch")
            .substringBefore("private fun rejectUnsupportedDashboardProjection")
        val success = cleanup.substringAfter("override fun onSuccess")
            .substringBefore("override fun onError")
        val error = cleanup.substringAfter("override fun onError")

        assertTrue(cleanup.indexOf("mSessionTracker.add(packageName)") <
            cleanup.indexOf("AdbLocalClient.forceStopApp"))
        assertTrue(success.contains("mSessionTracker.remove(packageName)"))
        assertFalse(error.contains("mSessionTracker.remove(packageName)"))
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
        override fun isActivityAlive(): Boolean = true
    }
}