package com.byd.dashcast.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShortcutLaunchWiringTest {

    @Test
    fun `adapter delegates shortcuts without launching the generic app`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/ui").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val adapter = File(root, "app/src/main/java/com/byd/dashcast/ui/AppListAdapter.kt").readText()

        assertTrue(adapter.split("mListener?.onLaunchShortcut(app").size - 1 == 2)
        assertFalse(adapter.contains("startShortcut("))
    }

    @Test
    fun `main launches shortcut once with target display options`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val main = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val method = main.substringAfter("override fun onLaunchShortcut")
            .substringBefore("override fun onSendToMain")

        assertTrue(method.contains("createLaunchOptions("))
        assertTrue(method.split("launcherApps.startShortcut(").size - 1 == 1)
        val afterLaunch = method.substringAfter("launcherApps.startShortcut(")
        assertTrue(afterLaunch.substringBefore("mCurrentDashboardApp")
            .contains("if (layoutTarget != null)"))
        assertTrue(main.contains("mPendingShortcutAfterActivation"))
        assertTrue(method.contains("splitBounds ?: layoutBounds"))
        assertTrue(method.indexOf("forceStopApp(this, splitOccupantToStop") <
            method.lastIndexOf("launch()"))
        assertTrue(method.contains("rejectUnsupportedDashboardProjection()"))
    }
}