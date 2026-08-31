package com.byd.dashcast.infrastructure

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AdbKeyBackupRulesTest {

    @Test
    fun `both Android backup formats exclude the complete adb identity`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
        assertTrue("could not locate the repo root", root != null)

        val legacy = excludedFiles(File(root, "app/src/main/res/xml/backup_rules.xml"))
        val modern = excludedFiles(File(root, "app/src/main/res/xml/data_extraction_rules.xml"))
        assertTrue(legacy.containsAll(setOf("adb.key", "adb.pub")))
        assertTrue(modern.containsAll(setOf("adb.key", "adb.pub")))

        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun `backup allowlist contains only locale and setup preferences`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
        assertTrue("could not locate the repo root", root != null)

        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val file = File(root, "app/src/main/res/xml/$name")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val includes = document.getElementsByTagName("include")
            assertTrue(includes.length >= 1)
            for (index in 0 until includes.length) {
                val attributes = includes.item(index).attributes
                assertTrue(attributes.getNamedItem("domain").nodeValue == "sharedpref")
                assertTrue(attributes.getNamedItem("path").nodeValue == "byd_prefs.xml")
            }
        }
    }

    private fun excludedFiles(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val exclusions = document.getElementsByTagName("exclude")
        return buildSet {
            for (index in 0 until exclusions.length) {
                val element = exclusions.item(index)
                val attributes = element.attributes
                if (attributes.getNamedItem("domain")?.nodeValue == "file") {
                    add(attributes.getNamedItem("path").nodeValue)
                }
            }
        }
    }
}