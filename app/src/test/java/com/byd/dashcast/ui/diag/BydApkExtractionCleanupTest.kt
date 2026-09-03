package com.byd.dashcast.ui.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile
import java.nio.file.Files

class BydApkExtractionCleanupTest {

    @Test
    fun `successful upload deletes work and the explicit ReportStore archive`() {
        val root = Files.createTempDirectory("extraction-cleanup").toFile()
        try {
            val work = java.io.File(root, "cache/run").apply { mkdirs() }
            java.io.File(work, "payload.apk").writeText("payload")
            val reportDir = java.io.File(root, "reports").apply { mkdirs() }
            val archive = java.io.File(reportDir, "run.zip").apply { writeText("zip") }

            BydApkExtractionBundle.cleanup(work, archive)

            assertFalse(work.exists())
            assertFalse(archive.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ordinary teardown preserves a ReportStore archive kept for the user`() {
        val root = Files.createTempDirectory("extraction-keep").toFile()
        try {
            val work = java.io.File(root, "cache/run").apply { mkdirs() }
            val reportDir = java.io.File(root, "reports").apply { mkdirs() }
            val archive = java.io.File(reportDir, "run.zip").apply { writeText("zip") }

            BydApkExtractionBundle.cleanup(work)

            assertFalse(work.exists())
            assertTrue(archive.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `late upload callback can delete its archive after activity teardown`() {
        val archive = Files.createTempFile("extraction-uploaded", ".zip").toFile()

        BydApkExtractionBundle.cleanup(null, archive)

        assertFalse(archive.exists())
    }

    @Test
    fun `activity teardown cannot delete extraction files owned by the worker`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it,
                "app/src/main/java/com/byd/dashcast/ui/diag/DiagActivity.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = java.io.File(root,
            "app/src/main/java/com/byd/dashcast/ui/diag/DiagActivity.kt").readText()
        val extraction = source.substringAfter("private fun start()")
            .substringBefore("private fun sendViaTelegram")

        assertFalse(source.contains("lastWork"))
        assertFalse(source.substringAfter("override fun onDestroy", "")
            .contains("BydApkExtractionBundle.cleanup"))
        assertTrue(extraction.substringAfter("BydApkExtractionBundle.materialize")
            .contains("BydApkExtractionBundle.cleanup(workDir)"))
        assertTrue(source.contains("WeakReference(this)"))
        assertFalse(extraction.contains("TelegramBugReporter.send(this"))
    }

    @Test
    fun `archive is published complete without staging leftovers`() {
        val root = Files.createTempDirectory("extraction-atomic-zip").toFile()
        try {
            val work = java.io.File(root, "work").apply { mkdirs() }
            java.io.File(work, "manifest.txt").writeText("complete")
            val archive = java.io.File(root, "reports/extraction.zip")

            com.byd.dashcast.hud.HudCaptureSupport.zipDir(work, archive)

            assertTrue(archive.isFile)
            ZipFile(archive).use { zip ->
                assertTrue(zip.getEntry("manifest.txt") != null)
            }
            assertFalse(archive.parentFile?.listFiles().orEmpty()
                .any { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }
}