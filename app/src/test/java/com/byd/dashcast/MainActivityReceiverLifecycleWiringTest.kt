package com.byd.dashcast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityReceiverLifecycleWiringTest {
    @Test
    fun `daemon receiver unregister follows successful registration ownership`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/MainActivity.kt").isFile }
        val source = File(root, "app/src/main/java/com/byd/dashcast/MainActivity.kt").readText()
        val registration = source.substringAfter("DaemonBroadcastRegistrar.register(")
            .substringBefore("// Floating mirror button")
        val destroy = source.substringAfter("override fun onDestroy()")
            .substringBefore("private fun restorePendingBootAdoption")

        assertTrue(registration.contains("mDaemonReadyReceiverRegistered = true"))
        assertTrue(destroy.contains("if (mDaemonReadyReceiverRegistered)"))
        assertTrue(destroy.indexOf("unregisterReceiver(mDaemonReadyReceiver)") <
            destroy.indexOf("mDaemonReadyReceiverRegistered = false"))
        assertTrue(destroy.indexOf("mDaemonReadyReceiverRegistered = false") <
            destroy.indexOf("if (mServiceBound)"))
    }
}