package com.byd.dashcast.ui.diag

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the real APK planner with sparse fixture files and the normal diagnostic budget. */
class HudOemApkPlanningTest {
    @Test
    fun `HUD evidence receives budget before old projection packages even when discovered last`() {
        val root = Files.createTempDirectory("hud-oem-planning").toFile()
        val oldLargeSink = ApkExtractionPolicy.largeSink
        try {
            ApkExtractionPolicy.largeSink = false
            val candidates = listOf(
                "com.xdja.containerservice" to 8,
                "com.byd.clusterdebug" to 8,
                "com.byd.automap" to 8,
                "com.byd.naviauto" to 2,
                "com.ts.car.someip.service" to 4
            )
            val pm = candidates.joinToString("\n") { (pkg, megabytes) ->
                val apk = File(root, "$pkg.apk")
                RandomAccessFile(apk, "rw").use { it.setLength(megabytes * 1024L * 1024) }
                "package:${apk.absolutePath}=$pkg"
            }
            val (accepted, skips) = plan(pm)
            assertEquals(listOf("com.ts.car.someip.service", "com.byd.naviauto"),
                accepted.take(2).map { it.pkg })
            assertTrue(accepted.sumOf { it.sizeBytes } <= ApkExtractionPolicy.APK_BUDGET)
            assertTrue(skips.any { it.contains("com.byd.automap") && it.contains("over") })
        } finally {
            ApkExtractionPolicy.largeSink = oldLargeSink
            root.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable or oversized receiver is explicitly skipped while other targets still plan`() {
        val root = Files.createTempDirectory("hud-oem-skips").toFile()
        val oldLargeSink = ApkExtractionPolicy.largeSink
        try {
            ApkExtractionPolicy.largeSink = false
            val oversized = File(root, "receiver.apk")
            RandomAccessFile(oversized, "rw").use { it.setLength(ApkExtractionPolicy.BUDGET_FILE + 1) }
            val normal = File(root, "container.apk").apply { writeText("fixture") }
            val missing = File(root, "absent.apk")
            val pm = listOf(
                "package:${oversized.absolutePath}=com.ts.car.someip.service",
                "package:${missing.absolutePath}=com.byd.naviauto",
                "package:${normal.absolutePath}=com.xdja.containerservice"
            ).joinToString("\n")
            val (accepted, skips) = plan(pm)
            assertEquals(listOf("com.xdja.containerservice"), accepted.map { it.pkg })
            assertTrue(skips.any { it.contains("com.ts.car.someip.service") && it.contains("too big") })
            assertTrue(skips.any { it.contains("com.byd.naviauto") && it.contains("unreadable") })
        } finally {
            ApkExtractionPolicy.largeSink = oldLargeSink
            root.deleteRecursively()
        }
    }

    private fun plan(pm: String): Pair<List<BydApkExtractionBundle.PlannedApk>, List<String>> {
        val accepted = mutableListOf<BydApkExtractionBundle.PlannedApk>()
        val skips = mutableListOf<String>()
        val method = BydApkExtractionBundle::class.java.getDeclaredMethod(
            "planApks", String::class.java, List::class.java, List::class.java)
        method.isAccessible = true
        method.invoke(BydApkExtractionBundle, pm, accepted, skips)
        return accepted to skips
    }
}
