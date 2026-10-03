package com.byd.dashcast.ui.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudOemEvidenceCaptureTest {
    @Test
    fun `capture requests receiver paths full caller dumps and exact firmware properties using only reads`() {
        val commands = mutableListOf<String>()
        val report = HudOemEvidenceCapture.collect { command ->
            commands.add(command)
            "evidence for $command"
        }
        for (prefix in listOf(
            "pm path com.ts.car.someip.service", "pm path com.byd.naviauto",
            "dumpsys package com.ts.car.someip.service", "dumpsys package com.byd.naviauto",
            "dumpsys package com.byd.dashcast", "dumpsys package com.android.shell",
            "getprop ro.build.version.release", "getprop ro.build.version.sdk",
            "getprop ro.build.display.id", "getprop ro.build.fingerprint",
            "getprop ro.vehicle.type", "getprop ro.build.car.platform"
        )) {
            assertTrue(prefix, commands.any { it.startsWith("$prefix 2>&1") })
            assertTrue(report.contains("evidence for $prefix"))
        }
        assertTrue(commands.all { command ->
            listOf("getprop ", "pm path ", "dumpsys package ", "dumpsys activity services ", "grep ")
                .any { command.startsWith(it) }
        })
        assertTrue(commands.all { it.contains("2>&1 | head -c ") })
        assertFalse(commands.filter { it.startsWith("dumpsys package ") }
            .any { it.contains("grep") })
        assertTrue(commands.any { it.contains("someip|com\\.ts\\.car|com\\.byd\\.naviauto") })
    }

    @Test
    fun `missing package and permission denial remain different raw evidence`() {
        val report = HudOemEvidenceCapture.collect { command ->
            when {
                command.startsWith("pm path com.ts.car.someip.service ") -> "Error: package not found"
                command.startsWith("dumpsys package com.byd.naviauto ") -> "Permission Denial: cannot dump"
                else -> "ok"
            }
        }
        assertTrue(report.contains("APK paths: com.ts.car.someip.service"))
        assertTrue(report.contains("Error: package not found"))
        assertTrue(report.contains("Permission Denial: cannot dump"))
    }

    @Test
    fun `one failed probe does not discard subsequent receiver evidence and empty output is visible`() {
        val report = HudOemEvidenceCapture.collect { command ->
            when {
                command.startsWith("getprop ro.build.version.release ") -> throw IllegalStateException()
                command.startsWith("pm path com.ts.car.someip.service ") -> ""
                else -> "later evidence"
            }
        }
        assertTrue(report.contains("[FAILED: IllegalStateException]"))
        assertTrue(report.contains("[EMPTY_OUTPUT]"))
        assertTrue(report.contains("Caller package dump: com.byd.dashcast"))
        assertTrue(report.contains("later evidence"))
    }

    @Test
    fun `oversized output is bounded and cannot be mistaken for a complete dump`() {
        val report = HudOemEvidenceCapture.collect { command ->
            if (command.startsWith("dumpsys package com.ts.car.someip.service ")) {
                "x".repeat(96 * 1024) + "TAIL_THAT_MUST_NOT_SURVIVE"
            } else "ok"
        }
        assertTrue(report.contains("[TRUNCATED: output exceeded 98304 bytes]"))
        assertFalse(report.contains("TAIL_THAT_MUST_NOT_SURVIVE"))
        assertTrue(report.length < 100 * 1024)
    }
}
