package com.byd.dashcast.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StagedReportCleanupTest {

    @Test
    fun `concurrent captures receive unique prefix-compatible names`() {
        val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val threads = List(8) {
            Thread {
                repeat(100) { names += BugReportCapture.newFileName() }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(800, names.size)
        assertTrue(names.all {
            it.startsWith(BugReportCapture.PREFIX) && it.endsWith(".txt")
        })
    }

    @Test
    fun `periodic cleanup is age bounded and never wildcard removes active reports`() {
        val command = BugReportCapture.buildStagedCleanupCommand()

        assertTrue(command.contains("-name '${BugReportCapture.PREFIX}*.txt'"))
        assertTrue(command.contains("-mmin +15"))
        assertTrue(command.contains("-delete"))
        assertFalse(command.contains("rm -f \"\$f\""))
    }

    @Test
    fun `error cleanup accepts only one exact staged report path`() {
        val path = "/data/local/tmp/${BugReportCapture.PREFIX}20260830_101112.txt"
        val command = BugReportCapture.buildStagedRemovalCommand(path)

        assertTrue(command.contains("'$path'"))
        assertFalse(command.contains("*"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `error cleanup rejects paths outside staging namespace`() {
        BugReportCapture.buildStagedRemovalCommand("/data/local/tmp/other.txt")
    }

    @Test
    fun `startup and shell error paths both invoke staged cleanup`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java/com/byd/dashcast/report/BugReportCapture.java").isFile }
        val capture = File(root,
            "app/src/main/java/com/byd/dashcast/report/BugReportCapture.java").readText()
        val startup = File(root,
            "app/src/main/java/com/byd/dashcast/app/AppStartupTasks.kt").readText()

        val onError = capture.substringAfter("@Override public void onError(String err)")
            .substringBefore("};")
        assertTrue(onError.contains("removeStagedReport(app, p)"))
        assertTrue(startup.contains("BugReportCapture.pruneStagedReports(ctx)"))
    }
}