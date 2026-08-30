package com.byd.dashcast.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterImeRelaySessionTest {

    @Test
    fun `a session accepts only its active display and package`() {
        val session = ClusterImeRelaySession()
        session.bind(3, "com.example.navigation")

        assertTrue(session.accepts(3, "com.example.navigation"))
        assertFalse(session.accepts(4, "com.example.navigation"))
        assertFalse(session.accepts(3, "com.example.messaging"))
    }

    @Test
    fun `invalid targets and session end fail closed`() {
        val session = ClusterImeRelaySession()
        session.bind(0, "com.example.navigation")
        assertFalse(session.hasTargetOn(0))

        session.bind(3, "com.example.navigation")
        session.clear()
        assertFalse(session.hasTargetOn(3))
        assertNull(session.packageOn(3))
    }

    @Test
    fun `rebinding replaces the complete target identity`() {
        val session = ClusterImeRelaySession()
        session.bind(2, "com.example.first")
        session.bind(5, "com.example.second")

        assertFalse(session.accepts(2, "com.example.first"))
        assertEquals("com.example.second", session.packageOn(5))
    }

    @Test
    fun `manual button binds through the watcher before launching the bridge`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast/ime").isDirectory }
        assertTrue("could not locate the repo root", root != null)

        val main = java.io.File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val click = main.substringAfter("btnKeyboardBridge.setOnClickListener")
            .substringBefore("mSessionTracker =")
        val prepareCall = click.indexOf("prepareAndLaunchBridgeManually()")
        val fallbackLaunch = click.indexOf("startActivity(Intent(this, KeyboardBridgeActivity::class.java))")
        assertTrue("manual launch must ask the watcher to bind first", prepareCall >= 0)
        assertTrue("the direct launch is only the unavailable-service fallback",
            fallbackLaunch > prepareCall)

        val watcher = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/ime/ClusterImeWatcherService.java"
        ).readText()
        val method = watcher.substringAfter("public static boolean prepareAndLaunchBridgeManually()")
            .substringBefore("\n    /**")
        assertTrue("manual target must be bound before the bridge opens",
            method.indexOf("mRelaySession.bind") in 0 until method.indexOf("launchBridge()"))
    }
}