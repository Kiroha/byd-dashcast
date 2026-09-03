package com.byd.dashcast.infrastructure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlindEvictionForceStopWiringTest {
    @Test
    fun `blind force stop has dedicated workers and never enters typed binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/infrastructure/AdbLocalClient.java").readText()
        val entry = source.substringAfter("forceStopAppForBlindEviction")
            .substringBefore("private static void forceStopAppInternal")
        val implementation = source.substringAfter("private static void forceStopAppInternal")
            .substringBefore("// LOT 4")

        assertTrue(entry.contains("sBlindEvictionExecutor, false"))
        assertTrue(entry.contains("PROBE_IDLE_TIMEOUT_MS"))
        assertTrue(implementation.contains("if (allowTyped &&"))
        assertTrue(implementation.contains("connect(context, socketTimeoutMs)"))
        assertFalse(entry.contains("sExecutor, true"))
    }
}