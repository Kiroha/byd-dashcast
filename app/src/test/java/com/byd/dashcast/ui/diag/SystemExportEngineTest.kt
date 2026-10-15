package com.byd.dashcast.ui.diag

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class SystemExportEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ample = { _: File -> Long.MAX_VALUE }
    private fun device(finalIssues: List<String> = emptyList(), finished: () -> Unit = {}) = object : FullApkExport.Device {
        override fun discover(checkCancelled: () -> Unit, update: (String, Int) -> Unit) = FullApkExport.Inventory(
            listOf(FullApkExport.Source("/system/framework/services.jar", 3, linkedSetOf("framework"), "/system/framework/services.jar")),
            listOf("Live non-atomic firmware collection; whole-platform coverage unverified"), mapOf("ro.build.fingerprint" to "before"))
        override fun open(source: FullApkExport.Source) = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        override fun finish(): FullApkExport.ContextCheck {
            finished()
            return FullApkExport.ContextCheck(mapOf("ro.build.fingerprint" to "after"), finalIssues)
        }
    }
    @Test fun `SYSTEM archives use explicit profile paths and final context goes in session manifest`() {
        val directory = temporary.newFolder()
        val states = mutableListOf<FullApkExport.Progress>()
        val result = FullApkExport(device(finished = {
            assertTrue(states.last().running)
            assertTrue(states.last().kind == ExportKind.SYSTEM)
        }), directory, { false }, { states.add(it) }, freeBytes = ample, kind = ExportKind.SYSTEM).run()
        assertEquals("finished", result.outcome)
        assertEquals("system-001.zip", result.archives.single().name)
        ZipFile(result.archives.single()).use { zip ->
            assertNotNull(zip.getEntry("system/000000/services.jar"))
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
            assertTrue(manifest.contains("\"kind\":\"SYSTEM\""))
            assertTrue(manifest.contains("\"contextCheckComplete\":false"))
            assertFalse(manifest.contains("complete APK"))
        }
        val manifest = File(directory, "manifest.json").readText()
        assertTrue(manifest.contains("\"contextCheckComplete\":true"))
        assertTrue(manifest.contains("\"firmwareAfter\":{\"ro.build.fingerprint\":\"after\"}"))
        assertTrue(manifest.contains("\"canonicalPath\":\"/system/framework/services.jar\""))
        assertTrue(states.none { it.stage.contains("Discovering APKs") })
    }
    @Test fun `late changed or missing firmware context makes copied SYSTEM corpus partial`() {
        for (issue in listOf("Build fingerprint changed", "Before/after build fingerprint unavailable")) {
            val directory = temporary.newFolder()
            val result = FullApkExport(device(listOf(issue)), directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM).run()
            assertEquals("partial", result.outcome)
            assertEquals(1, result.completed)
            assertEquals(1, result.archives.size)
            assertTrue(File(directory, "manifest.json").readText().contains(issue))
        }
    }
    @Test fun `final context exception is retained while finished volumes remain usable`() {
        val baseline = device()
        val broken = object : FullApkExport.Device by baseline {
            // Override the overload the engine invokes; delegation would otherwise forward it
            // straight to baseline and bypass an override of finish() on this wrapper.
            override fun finish(cancelled: Boolean): FullApkExport.ContextCheck = throw IllegalStateException("transport lost")
        }
        val directory = temporary.newFolder()
        val result = FullApkExport(broken, directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM).run()
        assertEquals("partial", result.outcome)
        assertTrue(File(directory, "manifest.json").readText().contains("transport lost"))
        ZipFile(result.archives.single()).use { assertNotNull(it.getEntry("manifest.json")) }
    }
    @Test fun `final manifest destination failure does not claim finished export`() {
        val directory = temporary.newFolder()
        val result = FullApkExport(device(finished = {
            // Simulate losing the final metadata destination after a healthy ZIP is closed.
            assertTrue(File(directory, "manifest.json").delete())
            assertTrue(File(directory, "manifest.json").mkdir())
        }), directory, { false }, {}, freeBytes = ample, kind = ExportKind.SYSTEM).run()
        assertEquals("failed", result.outcome)
        assertTrue(result.stage.contains("Manifest write failed"))
        assertEquals(1, result.archives.size)
    }
    @Test fun `APK remains the default profile and uses existing names`() {
        val result = FullApkExport(device(), temporary.newFolder(), { false }, {}, freeBytes = ample).run()
        assertEquals(ExportKind.APK, result.kind)
        assertEquals("apks-001.zip", result.archives.single().name)
    }
}
