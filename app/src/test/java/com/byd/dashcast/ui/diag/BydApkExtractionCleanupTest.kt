package com.byd.dashcast.ui.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}